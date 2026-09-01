# 营销发放对账地基 · 设计定稿(drools-demo)

> **归档状态（2026-08-28）：已实现，但最终实现已演进。** 当前代码把红蓝字从可变
> `activity_grant` 拆到追加式 `activity_grant_entry`，recon 视图也改读分录表；本文以下内容保留 8/19
> 评审时的方案与取舍，不能作为当前表结构/运维手册。现状见
> [`../../activity-marketing.md`](../../activity-marketing.md) 与 [`../../deployment.md`](../../deployment.md)，
> 迁移脚本以 `deploy/mysql-grant-recon-onboarding.sql` 为准。

> 由 java-spec-design 判官面板(3 方案→打分→综合)产出。停在定稿,拍板后喂 java-build-review 实现。
> 关联对账系统:recon-platform 营销三方 SEG1(营销侧)。日期:2026-08-19。

## 验收标准(spec)

- activity_grant 新增 4 列:grant_no(varchar,claim 时生成全局唯一号,带唯一约束)、currency(varchar,活动级)、entry_type(varchar,ISSUE/REFUND/REVERSAL)、amount_minor(bigint,带符号最小单位分);activity_manage 新增 currency 列(活动级币种,缺省 CNY)。列均由 console(ddl-auto:update)自动加,decision(validate)因共享 activity-common 实体而能通过校验(前提:console 先起、先建列)。
- 三阶段填值口径:claim → 生成 grant_no + 取活动 currency,state=HELD,amount_minor/entry_type 暂空;confirm(支付成功)→ state=CONFIRMED,setAmount(amount)、amount_minor=+amount×100、entry_type=ISSUE、落 decision_id;release(退款)→ state=RELEASED,对已 CONFIRMED 的流水写 amount_minor=-amount×100、entry_type=REVERSAL。
- confirm 幂等:同一 (orderId, activityId) 重复回调,第二次起返回 ok=true 且 replay=true,不重复落金额、不改变已确认的 amount/amount_minor;幂等硬保证复用既有唯一约束/行状态,不得引入应用层 check-then-act。
- confirm 状态机守卫:无对应 HELD 流水 → NOT_FOUND(收到未 claim 订单的支付回调);已 CONFIRMED → 幂等成功;已 RELEASED(退款先于迟到的支付回调)→ 拒绝或按既定策略处理(需拍板,见待澄清),绝不把 RELEASED 悄悄改回 CONFIRMED。
- 金额换算:BigDecimal 元(scale≤2)→ long 分 用精确换算(如 movePointRight(2).longValueExact()),scale>2 或溢出 fail-fast,不静默截断。
- 对账口径落实:recon 营销侧投影只取 state ∈ {CONFIRMED, RELEASED}(等价 entry_type 非空)的行;HELD 行(有 grant_no/currency,但 amount_minor/entry_type 为空)被排除。
- 新增 POST /activity-marketing/{activityId}/confirm 写端点,并同步登记到 ActivityResourceServerConfig 的 console-write-authority 白名单(与 create/claim/release 同权限守护);ClaimService/Controller 既有 claim/release 契约与状态码映射不回归。
- claim「先落流水(带 grant_no)再扣库存、扣减失败删流水」的既有顺序与契约不被破坏;不带 orderId 的旧三参 claim 仍可用(退化为不落流水、因此不进对账)。
- 全量 ./mvnw -q test(含各模块 ArchUnit + H2 集成)通过;GrantLedgerTest 扩展覆盖 confirm/幂等/amount_minor 符号/grant_no 唯一;若 ClaimResult 增加 grantNo 分量,则同步更新 ClaimResultContractTest 钉死的 JSON。

## 边界情况(spec)

- 【最关键】单行状态覆盖 vs 追加台账:一条流水 claim→confirm→release 会把同一行的 amount_minor 从 +X 覆写成 -X、entry_type 从 ISSUE 覆写成 REVERSAL,原 ISSUE(+X)记录消失。若账务/渠道侧是追加式(ISSUE 与 REVERSAL 两条分录),单行可变的 grant 表将无法逐条勾兑 —— 需拍板对账是『取当前净态』还是『需要追加式两条记录』。
- 释放一条从未确认的 HELD 流水(下单未支付即取消):它从未 ISSUE,却会进入 RELEASED 态。若 release 一律写 REVERSAL(-X) 会凭空产生一笔没有对应 ISSUE 的冲正;且 HELD 无 amount,-amount×100 无法计算。必须区分 HELD→RELEASED(未发放,不写 entry_type/不进对账)与 CONFIRMED→RELEASED(真冲正 -X)。
- 迟到支付回调 vs 已退款:release 先到把流水置 RELEASED,随后迟到的 confirm 到达 —— 必须拒绝 RELEASED→CONFIRMED(不能把已冲正的流水改回已确认),对账口径与资金安全都依赖这条守卫。
- confirm 幂等但金额不一致:重复回调携带与首次不同的 amount,应以首次为准(不覆盖),并考虑是否告警;decision_id 关联报价但系统不强校验回调金额==决策报价(实体注释明确『记的是发放,可能与报价不同』)。
- 收到未 claim 订单的支付回调(无 HELD 行)→ confirm 返回 NOT_FOUND,不得凭空创建 CONFIRMED 流水(否则有账无货/无勾兑起点)。
- grant_no 全局唯一 vs 多租户:唯一约束建在 grant_no 单列(跨租户)而非 (tenant_id, grant_no) —— 依赖生成算法(UUID/雪花)保证全局不撞;与既有 uk_grant_tenant_order_activity(租户内幂等)并存。
- 不带 orderId 的旧三参 claim 不落流水 → 无 grant_no、不进对账;需明确这类无单号发放对营销对账不可见是既有且可接受的行为。
- currency 回填:ddl-auto 加的是可空列,存量 activity_manage 行 currency=null → 继承出的 grant.currency=null。需为存量与新建都兜底默认 CNY,否则 ccy 空导致对账按币种分桶异常。
- amount×100 边界:hitAmount 若出现 >2 位小数(理论上 BenefitMath 已规约到 2 位)或极大金额,换算需 longValueExact + 溢出/scale fail-fast,不能静默四舍五入或截断。
- entry_type 枚举含 REFUND 但三阶段口径只产出 ISSUE/REVERSAL —— REFUND 何时使用未定义(主动退款 vs 系统冲正的区分?),需澄清,否则枚举值与状态机不自洽。

---

## ⭐ 最终采用方案:追加式台账(用户 2026-08-19 拍板 · 覆盖原 §0/§1/§7)

**拍板结果**:记账形态 = **追加式台账**(不再单行覆写);多租户口径 = **单租户**(VIEW 不切 tenant)。
理由:与会计红蓝字 / recon ADR-7「账务事实不删不改」同构,退款场景 recon 按 grant_no 分组能守恒对平(单行覆写在退款场景会假差)。

### A. 核心变化:引入不可变分录台账 `activity_grant_entry`

- `activity_grant` 回归**发放主记录/状态机**(HELD/CONFIRMED/RELEASED):只加 `grant_no`、`currency`;confirm 时 `setAmount(amount)`(既有列首次写入)。**不在它上面加 amount_minor/entry_type**——移到分录台账。
- 新增 `activity_grant_entry`(**追加式,不删不改**):每次确认发放/冲正退款**追加一条分录**。

| activity_grant_entry 列 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK | 自增 |
| grant_no | VARCHAR(64) | 关联 activity_grant.grant_no(对账 issue_id) |
| order_id | VARCHAR(64) | 冗余,对账 group_key(order_no) |
| activity_id | VARCHAR(64) | |
| entry_type | VARCHAR(16) | ISSUE(确认发放) / REVERSAL(退款冲正) |
| amount_minor | BIGINT | 带符号分:ISSUE=+amount×100,REVERSAL=−(对应 ISSUE 分额) |
| currency | VARCHAR(8) | 继承 grant |
| biz_time | TIMESTAMP | 分录业务时间(confirm/release 时刻) |
| (tenant_id / created_stime) | | 继承 TenantScopedEntity |

唯一约束 `uk_entry_grant_type(grant_no, entry_type)`:一次发放最多一条 ISSUE + 一条 REVERSAL(分录幂等硬保证)。

### B. 状态机 + 分录追加(替代原 §2/§4 单行覆写)

- **claim**:activity_grant HELD + grant_no + currency。**无分录**。
- **confirm**(支付回调,CAS `UPDATE activity_grant … WHERE state='HELD'`):HELD→CONFIRMED + setAmount;**同事务追加 1 条 ISSUE 分录**(+amount×100)。幂等:CAS affected==1 才追加;重复回调 affected==0 → 不追加(`uk_entry_grant_type` 兜底)。
- **release**(CONFIRMED→RELEASED):state→RELEASED;**同事务追加 1 条 REVERSAL 分录**(−已存 ISSUE 分额,取负不重算)。幂等:已 RELEASED 直接返回,不重复追加。
- **HELD→RELEASED**(未付即取消):→RELEASED,**无分录**(从未发放)。

### C. 对账 VIEW(替代原 §7,单租户)

```sql
CREATE OR REPLACE VIEW recon_src_marketing AS
 SELECT id, grant_no AS issue_id, order_id AS order_no, currency AS ccy,
        amount_minor, entry_type,
        'POSTED' AS biz_status, biz_time, biz_time AS posting_time
 FROM activity_grant_entry;          -- 单租户:不切 tenant
```
- **退款守恒**:发放后退款 = ISSUE(+X)+REVERSAL(−X)两行,recon 按 grant_no 分组汇总营销组和=0;账务侧同样两条→组和=0→**对得上**(追加式核心收益)。
- HELD 占用无分录天然不进;单租户不切 tenant。

### D. 文件清单增量(叠加在原 §9)

- **新增** `activity-common/.../persistence/ActivityGrantEntryEntity.java`(TenantScopedEntity 子类 + uk_entry_grant_type)。
- **新增** `activity-common/.../persistence/ActivityGrantEntryRepository.java`(追加 + 对账查询)。
- `activity_grant` **只加** grant_no/currency(**不加** amount_minor/entry_type);confirm `setAmount`。
- `GrantService` confirm/release **同事务追加分录**(而非在 grant 行写符号额);confirm 的 CAS + 分录追加同事务。
- 迁移脚本:建 `activity_grant_entry` + uk + `recon_src_marketing` VIEW(从 entry 表,单租户)。

### E. 原设计其余部分仍适用

confirm 接口签名(§3)、金额换算 `BenefitMath.toMinorExact`(§4)、安全白名单 + 租户上下文(§6)、事务边界(§5)、前端拆后续(§12)、REFUND 保留枚举(§11) **均不变**。**原正文 §0(单行覆写基线)与 §1/§7 的单行覆写部分以本章为准。**

---

I have everything I need. All three proposals' code claims check out against the real repo, and I've resolved the two open weaknesses (payment-callback tenant context; recon column contract). Here is the consolidated final design.

---

# 最终设计定稿 — 营销发放对账地基（drools-demo）

主线 = 「最简可用」。已嫁接「贴合现有架构」的 recon VIEW 列精确对齐与 `clearAutomatically`、「健壮优先」的 REVERSAL 取负存额 / 迁移点结构化 / release CAS 硬化，并补掉三案共同的 weaknesses。基线忠于用户验收口径（**单行覆写**），把「追加台账」显式设为拍板闸门 + 阶段二演进路径。

## 0. 头号拍板项（进 java-build-review 前必须对齐，非代码问题）

`uk_grant_tenant_order_activity` 结构上把 drools 侧钉为「一 (order,activity) 一行」。一条 `claim→confirm(+X)→release(-X)` 后，行上 `amount_minor` 从 `+X` **被覆写成 `-X`**、`entry_type` 从 `ISSUE` 覆写成 `REVERSAL`，原 ISSUE(+X) 分录消失。

- recon SEG1：`match_key=issue_id(=grant_no)`、`group_key=order_no(=order_id)`，`GroupSumMatchStrategy` 按 issue 汇总 `amount_minor`。**未退款发放**（marketing +X vs 账务 +X）能勾兑；**被退款发放**在 drools 侧只剩 `-X`，只有当账务/渠道侧也按 issue_id 呈现同款「最新分录净态 -X」时才对得上。若账务侧是**追加式**（ISSUE+REVERSAL 两条、net=0），组内和 `-X ≠ 0` → 假差。
- 更深一层（proposal 1 W1）：单行覆写丢失**时间维度**——晚于退款运行的账期批次永远看不到那笔 `+X`。若对账要按账期切片回溯，追加式 `activity_grant_entry` 台账（与 recon ADR-7「账务事实不删不改」同构）才是正解。
- **我的裁决**：基线交付**单行覆写**（忠于用户三阶段口径原文、尊重现有唯一约束与 claim 顺序、增量最小）；`confirm`/`release` 代码结构化为「状态迁移点」，未来在**同一事务内追加 ledger 行**是局部叠加而非重写。**必须先与 recon owner 确认账务/渠道侧对「被退款发放净额」的呈现方式**，再进实现。术语校正：这不是「净态(=0)」也不是「追加额」，是 **last-write-wins 到最新分录符号额**。

## 1. 表结构（activity_platform 库；console 是唯一 DDL 执行者）

**activity_grant 新增 4 列**（`amount DECIMAL(18,2)`、`decision_id VARCHAR(64)` 已存在，本次首次写入）：

| 列 | 类型 | 填值 |
|---|---|---|
| grant_no | VARCHAR(64) | claim 生成 UUID；新增 `uk_grant_no(grant_no)` 单列全局唯一 |
| currency | VARCHAR(8) | claim 从活动继承，兜底 CNY |
| entry_type | VARCHAR(16) | NULL=HELD/未发放；ISSUE=confirm；REVERSAL=release(CONFIRMED) |
| amount_minor | BIGINT | 带符号分：ISSUE=+amount×100，REVERSAL=−amount×100，HELD=NULL |

**activity_manage 新增**：`currency VARCHAR(8)`（活动级，兜底 CNY）。

- 所有新列 **nullable**（decision 侧 `ddl-auto:validate` + `@EntityScan(com.lrj.drools)` 会校验这些列，必须 console 先起先建列 decision 才起得来）。
- ⚠️ **`uk_grant_no` 不能依赖 `ddl-auto:update`**（Hibernate update 对既有表补唯一约束不可靠）——生产走**显式 DDL/Flyway**；唯一性功能上由应用生成的 UUID 保证，DB 约束是最后兜底。本地 dev `ddl-auto:update` 只自动加列（约束视方言可能不建，dev 可接受）。
- 存量兜底：一次性 `UPDATE activity_manage SET currency='CNY' WHERE currency IS NULL`；写入口 + claim 双兜底。
- 新列均加 Javadoc doc-comment（沿实体既有列注释风格）；表级 `@Comment` 已存在（`MysqlTableCommentMappingTest` 只查表级，不会红）。新列追加在子类字段末尾，`TenantScopedEntity` 的 `@JsonPropertyOrder({"id","activityId","version"})` 保 `EntityJsonOrderTest` 常绿。

## 2. 发放状态机 + 幂等硬保证

`HELD --confirm--> CONFIRMED --release--> RELEASED`；`HELD --release--> RELEASED`（下单未付即取消）；`RELEASED` 终态，**绝不 RELEASED→CONFIRMED**。

- **claim（微改）**：抢库存前 `setGrantNo(UUID)` + `setCurrency(coalesce(row.getCurrency(),"CNY"))`；`amount/amount_minor/entry_type` 留空。「先插流水(带 grant_no)→原子扣减→失败删流水」顺序与契约**一字不动**。
- **confirm（新增，支付成功回调）**：幂等硬保证 = 原子条件 UPDATE `WHERE state='HELD'`（复刻 `decrementInventory` 范式，`@TenantId` 自动追加租户谓词），**受影响行数是唯一写决策**：
  - `affected==1` → 首次确认（replay=false）。
  - `affected==0` → 回读一次**仅用于响应分流**（非 check-then-act）：查无行 → `NOT_FOUND`（404，未 claim 不凭空建账）；`CONFIRMED` → 幂等重放 `ok+replay=true`（**金额不覆盖，first-write-wins**）；`RELEASED` → `STATE_CONFLICT`（409）。
- **release（补字段）**：`CONFIRMED→RELEASED` 时 `setEntryType(REVERSAL)`、`setAmountMinor(-g.getAmountMinor())`（**取负已存分额，不用 -amount×100 重算**——杜绝漂移，且天然避开 amount 为 null 的 NPE）；`HELD→RELEASED` **不写 entry_type/amount_minor**（从未发放，非冲正）。null 守卫：`amountMinor==null`（历史遗留 CONFIRMED 行）时两列都不写。既有 `releaseGrant` 的 RELEASED 幂等返回与 `incrementInventory` 不动。

## 3. 接口签名与落金额来源

- **REST**：`POST /activity-marketing/{activityId}/confirm?orderId=&amount=&decisionId=`（decisionId 可选）；200 首次/重放、400 缺参/amount≤0/scale>2/溢出、404 未 claim、409 已 RELEASED。经既有 `respond()`+`claimStatus()` 出口。
- **Service**：`GrantService.confirmGrant(String activityId, String orderId, BigDecimal amount, String decisionId) → ClaimResult`；`ActivityMarketingService` 加同名薄委派。
- **落金额来源**：amount（BigDecimal 元，源自 `DiscountView.hitAmount` 那条决策报价链）**由回调携带**，drools 不重跑决策、不强校验 `amount==报价`——decision 是无状态只读平面、无持久化报价表可回查，实体注释已明确「记的是发放，可能≠报价」。`decision_id` 作报价↔发放锚点落行。系统只对 amount 做 `>0` 与精确换算校验。**这是**「无报价表可回查」的客观结论，不是搪塞（补掉 proposal 1 W3）；如需拦错误回调金额，加旁路告警（非阻塞）。

## 4. 对账 4 字段填值时机与符号

| 阶段 | grant_no | currency | entry_type | amount_minor | amount(元) |
|---|---|---|---|---|---|
| claim | UUID | 活动继承 | — | — | — |
| confirm | 不变 | 不变 | ISSUE | +amount×100 | +amount |
| release(CONFIRMED) | 不变 | 不变 | REVERSAL | −(已存) | +amount（**保留正向幅值**）|
| release(HELD) | 不变 | 不变 | — | — | — |

**符号约定必须写进字段注释**（补 proposal 3 W4）：`amount`(元) 只记发放幅值、**永不带冲正符号**；红蓝字符号只在 `amount_minor` 上——recon 读的是 `amount_minor`。换算集中到 `BenefitMath.toMinorExact(yuan)=yuan.movePointRight(2).setScale(0,UNNECESSARY).longValueExact()`，scale>2/溢出抛 `ArithmeticException`→400，**绝不静默截断/四舍五入**；刻意区别于本类 `MONEY_ROUNDING(DOWN)`（那是算减免的 fail-closed，这里是记既定金额、亚分即脏输入应 fail-fast）。

## 5. 事务边界

- **confirm**：单条短事务 = 1 条 `@Modifying(clearAutomatically=true)` CAS UPDATE +（affected 无论 0/1 都）1 条 `findFirstByOrderIdAndActivityId` 回读（`clearAutomatically` 保同事务回读见新态，proposal 2 bestIdea）。无外部 I/O。
- **release**：沿用既有 `@Transactional` load-modify-save，仅按前态增设 entry_type/amount_minor，事务边界不变。
- **claim**：`@Transactional` 不变，仅 insert 前多 set 两字段。
- 幂等一律靠 DB：claim 靠 `uk_grant_tenant_order_activity`，confirm 靠 `WHERE state='HELD'` 的 CAS 行状态谓词。**严禁退回应用层先查后写。**

## 6. 安全与租户边界（补掉 proposal 2 头号 weakness）

- **必补白名单**：`ActivityResourceServerConfig` 链一的 `console-write-authority` POST matchers 追加 `"/activity-marketing/*/confirm"`（与 create/claim/release/bulk-status 同权守）。漏补则纯决策 M2M token 可确认发放/落金额 = 越权改账。
- **支付回调如何建立租户上下文（此前悬空，现闭环）**：confirm 与 claim/release **同一条链**——`JwtTenantFilter` 从 JWT `aud` 解析租户落进 `TenantContext`，`@TenantId` 据此为 confirm 的 CAS UPDATE 自动追加 `tenant_id` 谓词并对 insert 自动落值。**因此 confirm 不是裸的匿名网关 webhook**：调用方必须是**内部租户上下文已就绪的服务**（订单/支付适配层），持 `aud` 映射到该租户、且带 `console-write-authority` 的 token 发起。这一集成契约须写进部署文档。
- confirm 键为 `(activityId, orderId)`（与 claim/release 一致）；单订单多活动时调用方按活动逐次 confirm。

## 7. 跨仓对账契约（recon VIEW，已按真实列名核实）

recon `MarketingThreeWayScenario.marketingLikeDescriptor` 硬编码：`idColumn=id, match=issue_id, group=order_no, currencyColumn=ccy, amountColumn=amount_minor, entryTypeColumn=entry_type, bizStatusColumn=biz_status, bizTimeColumn=biz_time, postingTimeColumn=posting_time`；`Config` 默认 `marketingTable="recon_src_marketing"`。由 console 侧**独立迁移脚本**（非 ddl-auto）建只读视图：

```sql
CREATE OR REPLACE VIEW recon_src_marketing AS
 SELECT id, grant_no AS issue_id, order_id AS order_no, currency AS ccy,
        amount_minor, entry_type,
        state AS biz_status, created_stime AS biz_time, modified_stime AS posting_time
 FROM activity_grant
 WHERE entry_type IS NOT NULL;
```

**对账纳入判据 = `entry_type IS NOT NULL`**（严于且更正确于 `state∈{CONFIRMED,RELEASED}`：一个谓词同时排除 HELD 占用与「HELD→RELEASED 从未发放」行——后者 state=RELEASED 但从未 ISSUE，三案共同 bestIdea）。多租户：视图含 `tenant_id`（`id`/`grant_no` 本就全局唯一），recon 单租户对账时 Config 指向租户切片视图或加谓词——与 recon owner 一并拍板。

## 8. 与 legacy / 读写平面边界

- **写平面独占**：发放流水只在 console 写；decision 只读账号物理 `GRANT SELECT` 写不了库；currency 加在共享 `activity_manage` 上，固化「console 先起先建列、decision 后起」部署顺序（`DecisionDdlGuardTest` 钉死 decision `validate`）。
- **legacy 三参 claim**（`claimInventory(activityId,version,quantity)`，无 orderId）：不落流水 → 无 grant_no → **天然不进对账**，保持既有可接受行为，不改。
- **存量遗留行**（部署前的 HELD/RELEASED，grant_no/entry_type=null）：被 VIEW 的 `entry_type IS NOT NULL` 天然排除，「存量发放对营销对账不可见」为可接受既有行为，写进文档。
- **ClaimResult 契约不动**：`ClaimResultContractTest` 钉死精确 JSON——**不给 ClaimResult 加 grantNo**；grant_no 经 `GET /activity-marketing/grants`（实体序列化）暴露。新增 `FailureKind.STATE_CONFLICT` 带 `@JsonIgnore`，不进 JSON。
- **跨仓 grant_no 传播（诚实标注的开放缺口，proposal 3 W2）**：账务/渠道侧须持同一 grant_no 才能按 issue_id join。本仓在 claim 生成 grant_no，经 `GET /grants` 与 recon VIEW 暴露；**账务侧获取 grant_no 依赖订单/结算流程的既有消息**（本仓外的集成契约）。建议将 grant_no 纳入结算/记账事件——作为跨系统假设显式挂账，与拍板项一并对齐。

## 9. 文件级 / 方法级改动清单

1. **`activity-common/.../persistence/ActivityGrantEntity.java`**：加 4 字段 + getter/setter + Javadoc；`@Table.uniqueConstraints` 追加 `uk_grant_no(grant_no)`；加常量 `ISSUE`/`REVERSAL`，`REFUND` 保留但状态机不产出（见 §11）。构造器只在 claim 一处用，新列走 setter，不改 arity。
2. **`activity-common/.../persistence/ActivityManageEntity.java`**：加 `currency` 列 + getter/setter + Javadoc。
3. **`activity-common/.../persistence/ActivityGrantRepository.java`**：加 `@Modifying(clearAutomatically=true) int confirmIfHeld(orderId, activityId, amount, minor, decisionId, now)`（JPQL：`set state='CONFIRMED', amount=:amount, amountMinor=:minor, entryType='ISSUE', decisionId=:decisionId, modifiedStime=:now where orderId and activityId and state='HELD'`）。`findFirstByOrderIdAndActivityId` 复用于回读。
4. **`activity-common/.../domain/ActivityCreateRequest.java`**：canonical record 末尾加 `String currency`（→24 分量）；新增一个与旧 canonical 同签名的兼容构造（currency=null），既有 21/22 参兼容构造各多转一个 `null`——十几处按位置构造的测试全部免改。
5. **`activity-common/.../engine/BenefitMath.java`**：加 `public static long toMinorExact(BigDecimal yuan)`。
6. **`activity-common/.../tenant/ActivityResourceServerConfig.java`**：白名单 POST matchers 追加 `"/activity-marketing/*/confirm"`。
7. **`activity-console/.../service/GrantService.java`**：(a) `claimInventory` 构造 grant 后 `setGrantNo`/`setCurrency`；(b) 新增 `@Transactional confirmGrant(...)`（算法见 §2/§3）；(c) `releaseGrant` 加 `wasConfirmed` 分支写 REVERSAL/取负分额（含 null 守卫）；(d) `FailureKind` 加 `STATE_CONFLICT`。
8. **`activity-console/.../service/ActivityMarketingService.java`**：`saveManage` 加 `m.setCurrency(normalizeCcy(req.currency()))`（blank→CNY、大写）；加 `confirmGrant(...)` 薄委派。
9. **`activity-console/.../controller/ActivityMarketingController.java`**：加 `@PostMapping("/{activityId}/confirm")`；`claimStatus` switch 补 `case STATE_CONFLICT -> 409`（无 default，漏补即编译失败）。
10. **迁移脚本（非 ddl-auto）**：`uk_grant_no` 显式 DDL + `recon_src_marketing` VIEW + `activity_manage.currency` 存量回填。

## 10. 测试策略

- **`GrantLedgerTest` 扩展**：confirm HELD→CONFIRMED 落 `amount/amount_minor=+X/entry_type=ISSUE/decision_id`；confirm 幂等（重复回调 replay=true、携异额也不覆盖首次）；confirm 未 claim→NOT_FOUND；confirm 已 RELEASED→STATE_CONFLICT 且不回改；grant_no 非空且唯一；CONFIRMED→RELEASED 写 `-X`/REVERSAL 且 `|amount_minor|` 与 ISSUE 对称、amount(元) 仍为正；HELD→RELEASED 不写 entry_type/amount_minor 且仍还库存；currency 继承 + 存量 null 兜底 CNY；`toMinorExact` 亚分/溢出 fail-fast。
- **回归**：`claimInventory` 并发防超发与「失败不留流水」契约（`FixedPriceAndClaimTest`），确认 UUID 生成不引入失败面；`ClaimResultContractTest` / `EntityJsonOrderTest` / `MysqlTableCommentMappingTest` 保持绿。
- 全量 `./mvnw -q test`（跑 console 前先 `install activity-common`，否则用旧 jar：common 绿、console 红）。

## 11. 已补掉的 weaknesses / 取舍

- **REFUND 枚举（补字面需求 vs 状态机自洽）**：需求列 `entry_type∈{ISSUE,REFUND,REVERSAL}`。定稿：三个常量**都定义**（尊重需求 4 列字段域），但本迭代状态机只产出 ISSUE/REVERSAL，`REFUND` 标注为**保留**（未来区分「主动退款 vs 系统冲正」时接线）——honor 字面需求且状态机自洽。
- **release 并发双还库存（既有隐患，proposal 3 bestIdea，作可分离硬化项)**：基线保留 load-modify-save（最小 blast radius、既有测试不动）。**推荐但可独立取舍**的硬化：把 release 改守卫式 CAS（`releaseConfirmed WHERE state='CONFIRMED'` / `releaseHeld WHERE state='HELD'`，`affected==1` 才 `incrementInventory`），顺带修掉并发双退款重复加库存——本次已为 confirm 引入 CAS 范式，成本极低、语义一致；作为清晰分离的改动项，可与基线同 PR 或独立立项。
- 其余取舍：grant_no 用 UUID（无中心、多实例零撞）；confirm 走 CAS 而非 check-then-act；`entry_type IS NOT NULL` 作纳入判据；ClaimResult 不加 grantNo 保契约。

## 12. 前端（拆为后续小项）

`activity_manage.currency` 后端先落地并兜底 CNY；活动编辑页 `EditorView.vue` 币种选择器属中大型表单改动，按全局规范**另走 frontend-plan**，不阻塞对账地基、不改后端契约。

---

**进入实现的前置闸门**：§0 拍板项（单行覆写 vs 追加台账 / 账务侧净额呈现）+ §7 recon 多租户视图口径 + §6 confirm 调用方租户上下文契约，三者与 recon owner 对齐后，本定稿可直接喂 java-build-review。相关文件绝对路径见 §9。
