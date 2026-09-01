# 活动营销模块（`com.lrj.drools.activity`）

> 活文档。最后核对：2026-08-28。当前实现基线包含生命周期调度、发放确认与不可变分录、grant outbox，
> 以及企业权益中台 AwardIntent 连接器；带日期的 `docs/plans/**` 仅保留当时的设计与验收语境。

本模块承载活动规则平台的营销业务能力，覆盖“活动创建 → 制定规则 → 上线 → 优惠决策”的完整流程。初始领域模型参考了 `autolife/mall-shop` 的活动营销设计，当前代码已按本项目的读写平面、租户隔离和规则执行架构持续演进；上线前仍需结合实际业务完成容量、安全与运维验收。

> 规划与实施全过程（Codex 规划 → Claude 跨模型复核 → 分阶段实施 → /frontend-plan）见 `docs/plans/activity-marketing-port-0712-1917/`：`FINAL_PLAN.md` / `FRONTEND_PLAN.md` / `IMPLEMENTATION_PROGRESS.md`。
>
> 2026-08 的**权益模型 + 性能分层重构**（P0-x 观测/取数/租户缺口、P1-1 代际快照、P1-2/1-3 分层引擎、折扣型/一口价/第 N 件折/随机红包/加价购）见 `docs/plans/benefit-model-refactor-0808-2218/`：`FINAL_PLAN.md` / `DECISION_RECORD.md`（D1–D12）/ `PROGRESS.md`（进度锚）。

## 能力范围

覆盖来源的**红包(RED_PACKAGE)**、**买赠(BUY_AND_GET)** 与新增的**加价购(ADD_ON_PURCHASE=6)** 三类活动。2026-08 收敛后，三条通道的请求上下文与资格淘汰固定共用 `DecisionEligibilityService`；红包的固定、随机、阶梯、折扣、一口价、第 N 件折六种形态固定由 `BenefitEvaluator` 算额并合并。旧红包 DRL（算额 + 合并那套）**已随灰度开关一并从代码里删除**——`buildDiscountDrl` 与 `evalDiscount` / `evalLadder` / `evalEligibility` 都不存在了，仓库里**找不到**可供对拍的第二份算额权威，别去找：

| 场景 | 说明 | 生产实现 | 兼容边界 |
| ---- | ---- | ---- | ---- |
| eligibility | 资格淘汰（可视化条件树 → 受控约束，fail-closed） | `DecisionEligibilityService` 统一上下文，`ConditionTreeEvaluator` 解释条件树 | `java-eligibility-eval` **属性已删**：主源码里已无 `@Value` 绑定点，配了既不报错也不生效 |
| discount | 多活动折扣合并（MAX / MUTEX / STACK / PRIORITY） | `BenefitEvaluator.merge()`，一次 O(N) reduce | `java-benefit-eval` **属性已删**：同上 |
| ladder | 阶梯满减（`redPackageRangeAmount` JSON 分档） | `BenefitEvaluator.applyLadder()`，线性查表 | 与其余五形态一样固定走 Java |
| gift | 共享资格淘汰后保留/汇总买赠奖品 | 合格候选送入运行时 DRL；引擎关闭/失败时只聚合合格候选 | — |
| addon | 共享资格淘汰后列选项、按当前配置重新报价 | `AddOnPurchaseService` 两阶段查询（报价不占库存） | — |

**为什么把这三件事移出 Drools**：判据是「这条规则需不需要*其它规则的结论*」。阶梯是标量分段函数（原实现**每档生成一条 DRL 规则**，档位多时 KieBase 随运营配置线性膨胀）、折扣合并是一次 reduce（原实现用 `$c : ActivityCandidate() and not ActivityCandidate(computedAmount > $c.computedAmount)` 做 argmax，是 O(N²) beta 评估）、资格是单事实布尔谓词（活动之间零交互）——三者都不需要。规则引擎的价值在规则**之间**的关系，留给 Drools 的是互斥矩阵、级联改写、CEP 频控这类场景。

生产语义不靠配置默认值偶然对齐：**`DecisionGoldenSetTest`（52 例）守金额/边界**，是发布门禁。

> ⚠️ **别把 `DroolsBenefitGoldenSetTest` 当门禁**——它是个**只有注解、类体为空**的子类（`class DroolsBenefitGoldenSetTest extends DecisionGoldenSetTest {}`），
> 父类用例全在 `@Nested` 内部类里，跑子类时这些嵌套类仍按**父类**的 `@TestPropertySource` 执行，
> 拿不到子类设的 false，所以**它自身跑 0 个用例**。真正守「旧 `java-*` 开关配 false 也不得把生产切回 DRL」这条的是
> `ActivityQuerySafetyFallbackTest#legacyFalseFlagsCannotSwitchProductionBackToDrools`。
> 2026-08 重构后那两个 `@Value` 字段已被删除，该用例不再需要反射置字段——它直接断言「六形态照常由 `BenefitEvaluator` 求值、
> `ActivityRuleRuntimeService` 零交互」。`DroolsBenefitGoldenSetTest` 的 `@TestPropertySource` 里仍写着那两个 `=false`，
> **那两行现在没有任何读取方**，留着只是历史注记。

外加**商品池自动圈选**：规则驱动圈选 `catalog_product` 并物化进绑定表（`bind_source=AUTO`，按目标态 diff 幂等刷新）。

**纯 Drools，不引 QLExpress**：来源用 QLExpress 跑资格表达式，这里先翻译成 Drools LHS 约束；P1-3 之后默认连表达式引擎那步也省了——`activity_condition.condition_tree_json` 里本来就存着结构化 AST，直接解释树比「树 → 串 → 再解析串」少一层翻译、零新依赖、零转义面。

## 权益形态（`BenefitForm`）

同一列 `red_package_amount` 里的数字**是什么意思**，由既有的 `red_package_amount_unit` 判别（不新增列——该字段一路从 `activity_rule` 搬到候选/快照/DRL 上下文，但此前从来没被任何计算读过）：

| 单位 | 形态 | `redPackageAmount` 的含义 | 配套字段 |
| ---- | ---- | ---- | ---- |
| `元`（或 null） | `AMOUNT` 金额型 | 要减的钱 | 阶梯分档走 `redPackageRangeAmount` 数组 |
| `折` | `RATIO_ZHE` 折扣型 | **折数**，取值 (0,10)，8 = 八折 = 减 20%（打在**作用域基数**上，不是无条件整单） | `redPackageMaxDiscount` **必填**（封顶减免额） |
| `价` | `FIXED_PRICE` 一口价/秒杀 | **卖多少**（减免 = **作用域基数** − 一口价） | 配套 `/{id}/claim` 扣库存 |
| `件折` | `NTH_ZHE` 第 N 件折 | **折数**（只在**作用域内**的订单行里数「第 N 件」，域外商品不替它凑件数） | `redPackageRangeAmount` = `{"nth":2}` |
| `元` + `redPackageTakeType=2` | 随机红包 | 不读该字段 | `redPackageRangeAmount` = `{"min":5,"max":20}` |

- **未知单位一律回落金额型**（`BenefitForm.of`）：历史数据全是 `元`/null，行为一个字节不变；写错的单位表现为「按金额发」（旧行为）而不是「按猜出来的比例发」。写平面另有白名单 `BenefitForm.isSupportedUnit`，非受控单位直接 400。
- **`redPackageRangeAmount` 是多用途列，解析出口只有一个**：`RangePayload.parse(form, takeType, json)`（sealed interface，产出 `Ladder` / `Random` / `Nth` / `None` / `Invalid` 五选一）。判别规则原样照搬既有实现两步走——① 顶层 JSON **是不是数组**先把阶梯与其余切成不相交两半（数组 → 阶梯，内部仍走 `LadderRangeParser`）；② 非数组再由 `redPackageAmountUnit` + `redPackageTakeType`（即 `RangePayload.expectedKind`）切成随机区间 / 第 N 件（内部走 `RandomRangeParser`）。<br>之所以要收成一处：这条约定此前在 Java 侧写了三遍（`ladderDefs` 无条件按阶梯解析、`BenefitEvaluator` 按形态各自解析、写平面 `validateRangeColumn` 又分叉一次），三份各自演化的后果是**写侧接受的配置读侧算不出金额**——活动以「不适用」的姿态上线，而运营看到的是「已上线」。注意第 ① 步**不看形态**：单位配成「件折」但内容是阶梯数组时解析结果就是阶梯，与改造前 `ladderDefs` 的行为一致；载荷与调用方期望不匹配时，读侧 fail-closed 当算不出金额，写侧按 `expectedKind` 报错。
- **底层算术只有一份实现**：`BenefitMath`（静态纯函数）。历史 DRL 和当前 `BenefitEvaluator` 都调这些函数，可避免取整/封顶公式各写一份；但分支判别、适用性与合并策略仍要靠金标和 fallback 测试守，不能宣称“不可能漂移”。钱一律 2 位小数、`RoundingMode.DOWN`（向下取整是系统性偏向"不多发"，与 fail-closed 一致）。
- **「作用域基数」是折/价/件折三形态算钱的分母**（`BenefitEvaluator.baseAmount`，三档顺序不能反）：① 候选的 `scopedSpuIds == null`（作用域未知，手工构造或还没接上作用域的装配路径）→ 整单 `orderAmount`，与改造前逐字节一致；② 作用域**覆盖**本次请求的全部 SPU → 整单（今天绝大多数流量落在这档：单 SPU 查询、全场券）；③ 作用域是**真子集** → 必须靠订单行分摊，`Σ 作用域行小计`，**拿不到行就返回 null 让候选被淘汰**，绝不用整单金额顶替。之所以要这一层：绑定关系此前只是个候选筛选器，一个只绑了 B 的「9.9 一口价」在「A 5000 元 + B」的车里会算成 `5009.9 − 9.9`，**整车按 9.9 成交**。
- **算不出来返回 null = 本活动不适用，不是"减 0 元"**：0 元会以 0 参与 MAX 竞争并挤掉别的真能减钱的活动。缺订单金额、折数越界 [不在 (0,10)]、折扣型无封顶、缺逐行单价、订单比一口价还便宜、作用域基数不可知——全部按"不适用"处理。
- **随机红包是确定性随机**：金额 = `SHA-256(活动id|版本|userId|购物车指纹)` 落进 [min,max] 区间，同一上下文永远抽到同一个数（刷新不变价、可重放、可对账）。购物车指纹是 `canonical(orderAmount)|canonical(quantity)|randomSeedSpu`，因为决策入口没有订单号。**这条串的任何一个字节都不能动**：
  - 数值段过 `canonical()`（`stripTrailingZeros().toPlainString()`）规范化，否则客户端把金额写成 `100` 还是 `100.00` 会得到两个种子、两个红包金额——「同一笔订单刷新不变价」正是这套机制存在的全部理由。
  - SPU 段刻意读 `randomSeedSpu` 而**不是** `spuId`：后者已从「购物车第一件」改成整个列表（作用域改造），`toString()` 从 `990011` 变成 `[990011]`。`DecisionEligibilityService` 专门维持 `randomSeedSpu` = 列表第一件，唯一职责就是把这条种子链钉住。**改它等于改所有历史金额**（全量随机红包一次性重抽，用户刷新变价、历史对账全部对不上）。
  - 发放流水表 `activity_grant` 现在有了，但随机红包仍是**确定性派生**、没有改成真抽奖。
  - **判别顺序**：先由 `BenefitForm` 判单位；只有 `AMOUNT` 才继续看 `redPackageTakeType=2`。因此 API 手造的「折 + takeType=2」仍按折扣算，不会被随机分支抢走。进入 `AMOUNT` 后，随机分支仍必须排在 `redPackageAmount == null` guard 前，否则「只配区间」的随机活动会被静默跳过。未知 takeType 回落固定金额（不抛异常，一条脏数据不该打断整批候选的算额）。
- **形态分派是无 `default` 的 `switch` 表达式**（`BenefitEvaluator.computeAmounts`）：六形态各占一支，`BenefitForm` 新增一个常量而漏了分支就是**编译失败**。此前是 5 段 `if (...) { ...; continue; }`，漏一支的表现是「静默按金额型发钱」而不是报错。<br>⚠️ **不能改成 arrow `switch` 语句**——语句对枚举常量不强制穷尽，写成语句等于白改。<br>两道**横切 guard 必须留在 switch 之外**，顺序也不能反：① 随机红包分支（`AMOUNT` + `RANDOM_AMOUNT`），② `redPackageAmount == null` 的通用兜底。② 排在 ① 前面的话，「只配了区间、没配固定金额」的随机活动会被静默跳过。
- **写平面校验（`validateBenefitForm`）**——四种形态各有硬校验，违反一律 400：
  | 形态 | 校验 | 为什么 |
  | ---- | ---- | ---- |
  | 折扣型（`折`） | 必须有折数 + 封顶（`>0`），且**不允许同时配阶梯** | 阶梯 reward 是「元」，两种形态打架 |
  | 一口价（`价`） | `redPackageAmount>0` **且 `inventory≥1`** | 0/负数不是运营本意；库存 null/0 时 `claim` 的原子 UPDATE 永远更新 0 行，活动看着健康、实际永远抢不到 |
  | 第 N 件折（`件折`） | 折数必填且须在 **(0,10)** | 10 折=不打折、0 折=白送，都按配置错误拒绝 |
  | 金额型（`元`/null） | 不允许填封顶 | 封顶只对折扣型有意义 |

**生产红包固定由 `BenefitEvaluator` 实现六种形态**（固定、随机、阶梯、折扣、一口价、第 N 件折）。
`java-benefit-eval` / `java-eligibility-eval` 这两个属性**连绑定点都没有了**——2026-08 重构删掉了那两个
`@Value` 字段（此前是「绑定但不读取」，现在是根本不绑定），两份 `application.yml` 里也从来没有过这两个 key。
进程内**没有任何开关**能把求值切回 DRL；不要再用它们当回滚手段。
真正的回滚手段是**部署级回滚上一版 jar** + decision 侧快照代际 `rollback`（后者现在有生产可达的入口，
见下节「决策平面拆分」的 `POST /decision/v1/snapshot/rollback`）。

## 跑起来

> **M2.1 起是 Maven 四模块**（`activity-common` 共享库：domain/engine/persistence/tenant + 只读查询服务 / `drools-lab` Step1–24 教学 / `activity-console` 写平面 app:8081 / `activity-decision` 只读决策 app:8082）。根 `./mvnw spring-boot:run` **不再可用**（父是聚合 pom，无 main），起服务要 `-pl` 指定 app 模块。拆分详情见下节「决策平面拆分」。

```bash
# 控制台写平面（活动创建/编辑/上下线 + 前端 /ui/ + Step1–24），默认 8081；H2 profile 免装 MySQL
./mvnw -pl activity-console spring-boot:run -Dspring-boot.run.profiles=h2
# 只读决策热路径（/decision/v1/* + 发布代际轮询预热），默认 8082
./mvnw -pl activity-decision spring-boot:run -Dspring-boot.run.profiles=h2
# 顺带把 Vue SPA 构建拷进 static/ui/（否则前端用 frontend/ 的 Vite dev server :5173）
./mvnw -pl activity-console -Pfrontend spring-boot:run
```

浏览器打开 `http://localhost:8081/ui/`（根 `/` 是构建无关落地页，跳 `/ui/`；旧原生控制台已于 F3 退役）→ 活动配置台 `/ui/console/activities`，即可用报表式表单创建活动、拖出资格条件树、上线，并在「优惠验证」页 `/ui/console/validate` 检查命中结果。

浏览器里还有 `/ui/console/playbooks`「玩法模板目录」：12 张玩法卡给已有能力起名字并预填编辑器（满 X 减 Y / 第二件半价 / 限时秒杀 / 加价购…），当前均可创建。`/ui/console/validate` 从这 12 份 playbook 直接派生验证场景，并额外提供 random 场景；页面按 discount / gifts / addon 三通道调用真实接口，展示结构化结果与 trace，而不是只打印原始 JSON。场景不指定活动、不强制命中；第 N 件折只编辑订单行，`spuIdList / orderAmount / quantity / lines` 从行项唯一导出。该页**默认打决策平面**（`/api/decision/*`），并可切「控制台走库」或「两条都打对拍」——此前它固定打 console 的 legacy 读端点，而 console 进程里 store 恒空、必然走库，于是「用来自证优惠有没有生效的工具」恰好是唯一照不到快照侧问题（陈旧快照、绑定收窄、轮询延迟）的那条路。页面另带物料来源徽章（`provenance` + 落后几代）、逐活动明细（含被淘汰候选与原因）与快照探针；**「决策服务不可达」与「决策未命中」是两种状态**，401/403 单独判为「可达但未授权」而不降级回走库（降级只会掩盖权限配置问题）。

开关：
- `activity.marketing.rule-engine.enabled`（默认 true）：false 时仍先跑共享资格，再用 `BenefitEvaluator` 安全重算六种形态。合并策略取 `Materials.strategy()`——它随物料从**同一个来源**出来（快照桶里的 / 走库查出来的），`STACK / PRIORITY / MAX`（以及同类的 MUTEX）原样保留，不会统一退化成 MAX。开关关闭或空决策都不能退回只认固定金额的算法。
  > ~~`java-benefit-eval` / `java-eligibility-eval`~~ **已删除**：主源码里再没有这两个 `@Value` 绑定点，配置里写了也没有任何读取方（Spring 不会报错，只是没人读）。它们从来不是灰度/回滚机制，现在连「配置兼容」这层壳也不留了。
- `activity.marketing.snapshot.max-age-ms`（默认 **60000**，≤0 关闭）：decision 侧快照的**兜底重建阈值**——轮询器每轮扫完代际后，把年龄超过它的快照按数据库真相重建一遍（见下节「发布代际轮询预热」）。⚠️ **两份 `application.yml` 里都没有这个 key，默认值只在 `GenerationWarmService` 的 `@Value` 里**；想改必须自己加。
- `activity.marketing.lifecycle-schedule.mode`（默认 **local**）：`local` 由 Spring `@Scheduled` 按 `interval-ms` 扫描，`xxl` 由 XXL-JOB 的 `activityLifecycleSweep` Handler 触发，`off` 完全关闭；三种模式互斥。Docker 默认使用 `xxl`，本机直接启动默认使用 `local`。只有显式置为 `PENDING_EFFECT(3)` 的未来版本才会自动上线；ONLINE 版本在结束时间之后自动下线。`batch-size` 控制每个租户每轮最多处理的活动数，普通 NORMAL 草稿永远不会被后台自动发布。
- `activity.marketing.seed-catalog-data`（默认 **false**）：仅供本地开发或验收环境按需开启；启动时写入 4 个目录商品和商品池（poolId=1，圈电子类 100~200 元），便于验证自动圈选。正式环境应由商品主数据同步链路供数，不应开启该选项。
- `activity.grant-outbox.enabled`（默认 **false**）：控制 confirm/release 是否同事务写跨系统传播事件。
  `relay-mode=local|xxl|off` 选择 Spring 调度、XXL-JOB `grantOutboxRelaySweep` 或只保留手动触发；
  webhook 为空时只记录日志。FAILED 按指数退避重试，触顶进入 DEAD，需调用服务层 redrive 后再投。
- `activity.award-intent.relay-enabled`（默认 **false**）：控制 CENTER 模式 AwardIntent outbox 是否发往
  `benefit-center-url`。relay 用行租约避免多实例重复抢占；HTTP 最坏调用时长必须小于 `lease-ms`。
- `activity.tenant.dev-default-enabled`（默认 **false**，`application.yml` dev-run 显式开为 true）：多租户开关，见下节「多租户隔离」。本地开着时不带 `X-Tenant-Id` 也能跑（回落单租户 `__dev__`），下面的 curl 示例照常工作。

## REST 接口（全部在 `/activity-marketing`）

| 方法 | 路径 | 说明 |
| ---- | ---- | ---- |
| POST | `/create` | 创建/编辑（带 `activityId` 即编辑，version+1；`requestId` 幂等）。入参含 `userInventory`（每人限领份数，null/≤0 = 不限）。返回体新增 `warnings[]` |
| POST | `/{id}/status` | 状态变更 `{version,targetStatus}`：0 待上线、1 立即上线、2 下线、3 **预约上线**。预约只接受未来开始的合法时间窗，到 `activityStartTime` 自动上线，超过 `activityEndTime` 自动下线；改回 0 即取消预约。四眼开启时，立即上线和预约上线都要求审批人≠提交人，失败返回 **403** |
| POST | `/bulk-status` | **批量**状态变更 `{items:[{activityId,version}],targetStatus}`，回执 `{succeeded[],failed[{activityId,reason}]}`。部分失败仍是 200；但 `targetStatus` 本身非法时**进循环前**就返回 400（否则几十条各失败一次） |
| POST | `/{id}/claim` | **抢占秒杀库存**（`?version=&quantity=&userId=&orderId=`）：抢到 200；没抢到按失败种类分流——**400**（缺 activityId / 数量非正 / 限领活动没带 `userId`）、**404**（活动或版本不存在）、**409**（余量不足、不在可用窗口、超出每人限领）。`orderId` 是幂等键的一半（**不传就退化成不幂等**）；auth 档受已配置的 `console-write-authority` 保护 |
| POST | `/{id}/confirm` | 支付成功确认（`?orderId=&amount=&decisionId=`）：CAS 执行 `HELD→CONFIRMED`，同事务追加正数 `ISSUE` 分录；重复确认返回 replay 且不覆盖首次金额。缺参/亚分金额/溢出 400，未 claim 404，已 RELEASED 409。受 `console-write-authority` 保护 |
| POST | `/{id}/release?orderId=` | **冲正**：把发放记录置 RELEASED 并归还库存与限领额度。幂等（重复释放不重复加库存）。缺参/空 `orderId` 返回 **400**，确实没有对应发放记录才 **404**。同受 `console-write-authority` 保护——不设防的话反复调它就能把限量活动的库存刷到任意大 |
| GET | `/grants?orderId=` | 按单查发放记录（客服「这一单用了哪些优惠」的数据源） |
| GET | `/generation?bizLine=` | 库里当前发布代际——决策响应里 `provenance.generation` 的**参照物**（行不存在返回 0）。只看决策侧那个数判断不了「我刚发布的那次进去了没有」 |
| GET | `/list` | 活动列表（当前版本） |
| GET | `/{id}` | 详情（manage/rules/conditions/bindings/gifts/poolRefs）+ **`servingVersion`**。返回的 `manage` 是**最高未删除版**（P0-4 之后通常是草稿）——编辑器拿它当编辑基线，编辑就该编草稿。而 `servingVersion` 是**当前正在发钱的那一版**（最高 ONLINE 版，没有上线版本时为 null）：「你看的这一版可能不是正在服务的那一版」这件事由服务端说出来，不留给调用方拿列表行去猜 |
| POST | `/spu-discount` | 红包优惠查询（资格→阶梯→折扣合并 + 回退 + trace）。控制台是运营的调试入口，**显式 `DecisionMode.EXPLAIN`** |
| POST | `/gifts` | 买赠查询（同样 `EXPLAIN`） |
| POST | `/addon/options` | 加价购第一阶段：列出当前请求上下文可选的换购品（空列表是正常结果）。同样是 `EXPLAIN` 档 |
| POST | `/addon/quote?activityId=&item=` | 加价购第二阶段：按当前配置权威重报价；失效/伪造选项返回 **409** |
| POST | `/preview` | 资格条件树预览（翻译+试编译，不落库；恒 200 读 `ok`） |
| GET | `/field-dict` | 字段白名单 + 运算符 + 枚举（前端下拉的唯一来源，防漂移） |
| GET | `/auth-config` | 前端 OIDC 引导配置。**auth 档下是这个前缀里唯一匿名可读的路径**（安全链一 permitAll + `JwtTenantFilter` 跳过），auth 关时只返回 `{authEnabled:false}`。定义在 `activity-common` 的 `AuthConfigController`，由 console 暴露；`deployment.md` 拿它做最小验收 |

企业权益中台使用独立入口 `POST /activity-awards/v1/intents`，不在 `/activity-marketing` 前缀下。它接收
`activityId`、`activityVersion`、`sourceRequestId`、收件人、场景和服务器端决策上下文：LEGACY 不生成
AwardIntent，SHADOW 只返回对拍结果，CENTER 以 `(sourceSystem,sourceRequestId)` 幂等写 outbox。该入口同样受
`console-write-authority` 保护；完整契约见 [`benefit-center-connector.md`](benefit-center-connector.md)。

### 发放账本与 outbox 边界

- `activity_grant` 是可变状态机（HELD / CONFIRMED / RELEASED）；`activity_grant_entry` 是不可变红蓝字账，
  confirm 追加 `ISSUE(+amount_minor)`，已确认后的 release 追加 `REVERSAL(-amount_minor)`。HELD 直接 release
  从未发放，因此不产生分录。
- 分录幂等由 `uk_entry_grant_type(grant_no,entry_type)` 保证；传播事件幂等由
  `uk_outbox_grant_event(grant_no,event_type)` 保证。都不能退回应用层先查后写。
- grant outbox 是 at-least-once，不是 exactly-once。下游必须按 `(grant_no,event_type)` 去重；DEAD 不会
  自动重试。生产必须先执行显式 SQL 迁移，不能依赖 `ddl-auto:update` 给既有表补唯一约束。
- AwardIntent outbox 与 grant outbox 是两条不同链路：前者承载“要向权益中台发什么”，后者传播营销发放账
  的 grant_no/红蓝字事件；不要混用表、开关或幂等键。

几条容易踩的语义：

- **错误响应体的形状**：`{"error":"<中文说明>","code":"<机器可读分类>"}`。`error` 字段名与改造前逐字一致（前端 `apiClient` 的 `errMsg` 读的就是它），`code` 是 2026-08 新增的**纯附加**分量，取值来自 `ActivityErrorCode`（`INVALID_ARGUMENT` / `FOUR_EYES_REQUIRED` / `VERSION_CONFLICT` / `DUPLICATE_REQUEST` / `STATE_CONFLICT` / `INTERNAL`），**状态码由这张表决定，不再由「抛的是哪个 JDK 异常」决定**。四眼失败从 409 改判 403 就是这么发生的：它说的是「不该由你来做」，不是「重试可能会成」。映射收在 `ActivityExceptionAdvice`（`basePackages` 只圈 `com.lrj.drools.activity.controller`，不误伤 classpath 上 drools-lab 的 Step 1–24 controller）。<br>⚠️ console 侧**刻意没有** `IllegalStateException → 409` 的兜底：advice 的作用域是整个 controller 包，而那些 `catch` 只挂在 create / status 上——提成兜底等于宣布 `list` / `grants` / `preview` 上任何 ISE（`Optional.get`、懒加载、bean 状态错）都是「状态冲突」，于是写平面的 bug 变成不计错误预算的 4xx。真正需要 409 的路径都已由 `ActivityException` 分类承载。
- **批量接口的 `version` 必须显式传**。P0-4 之后线上版与草稿**并存**（见下），只给 id 就会落到「最高未删除版本」= 那个还没发布的草稿，于是「批量下线 23 个」把 23 个草稿置成下线、**正在发钱的线上版一个都没停**，回执还报全部成功。工作台按它在列表里看到的那一行传版本。逐条捕获异常、互不影响，部分失败是正常结果（200 + `failed[]`，只有 `targetStatus` 本身非法才整请求 400）。每一项由 `TransactionTemplate` 显式开启独立事务，避免同类自调用绕过 `@Transactional`；单项内的锁定版本、状态切换和代际推进要么一起提交，要么一起回滚。
- **编辑不再下线正在服务的版本**（P0-4）：当前版本已上线时保留它继续服务、另建 v+1 草稿；当前版本还是草稿时直接顶掉。发布草稿时在同一事务里把该活动其它仍 ONLINE 的版本退役 = **原子指针切换**。
- **定时上线不是“时间到了就扫所有草稿”**：运营必须先把目标版本置为 3，预约时完成时间窗和四眼校验。无论触发源是本地 Spring 还是 XXL-JOB，后台都调用同一个生命周期编排服务：按租户扫描并对同一活动的所有版本加悲观写锁；到点时发布最高的到期预约版、退役旧 ONLINE 版和更低预约版。结束边界与决策一致为闭区间——恰好等于结束时间仍可命中，只有 `end < now` 才自动下线。分页按 `activityId` 保存续扫游标并在末尾回卷，某一页的永久故障活动不会饿死后面的正常活动。重复扫描、XXL 失败重试和多 console 实例并发执行都保持幂等；单活动失败会让 XXL 任务整体标记失败，避免控制台显示“假成功”。
- **`inventory` 是声明式的**：字段存得下，**决策链路不读取、不扣减**。create 返回的 `warnings[]` 会把这一点明说（沉默最危险：运营以为配了就生效）。真要限量走 `/{id}/claim`。
- **`claim` 才是权威扣减**：`decrementInventory` 把「判余量」和「减一」压进同一条 `UPDATE ... WHERE inventory >= :n`，靠数据库对同一行的串行化防超发；**不能改成先查后减**（check-then-act 竞态，低并发测不出、大促必现）。谓词里另含活动状态与时间窗——否则已下线/未开始/已结束的版本库存都能被扣干净。它与 create/status/release 同属写端点：当 `activity.tenant.auth.console-write-authority` 非空时，token 必须具备该 authority，否则 403；配置文件中的空值仅用于本地开发，正式环境必须配置。决策接口只报价，**不能拿决策成功当作抢到了**。
- **`claim` 现在幂等，靠的是先插流水再扣库存**（`activity_grant`，唯一约束 `tenant+order_id+activity_id`）：命中已有流水直接返回**首次结果**、不再扣减。**顺序不能反**——唯一约束必须在任何扣减发生之前就拦住并发的同一单重复提交；反过来（先扣后插）要靠事务边界一路不出错才能救回库存。扣减失败会把刚插的流水**删掉**：留着一条 HELD 却没有对应扣减的记录，在对账上就是「有账无货」，还会永久占掉该用户的限领额度、并让这一单再也 claim 不了（幂等分支会命中它）。
- **不传 `version` 时 `claim` 解析成当前 ONLINE 版本**（不是最高版本）。此前取最高未删除版本 = **草稿**，而决策发的是最高 ONLINE 版本——防超发的闸门装在了另一行数据上：线上版本库存一件没少、草稿的库存被扣干净。<br>这两套定义现在**具名化**在 `ActivityVersionResolver` 里，是「当前是哪一版」的唯一出口：`latestDraftVersion()`（最高未删除版 = 编辑基线 / 详情 / `changeStatus` 的缺省，P0-4 之后通常是**草稿**）与 `currentOnlineVersion()`（最高 ONLINE 版 = 正在发钱的那一版 = `claim` 的缺省，也是决策侧 `DecisionDataLoader` 认的那一版）。**两者互斥，调用点必须显式选一个**；活动只有一版且已上线时两者恰好相等，那是巧合不是同义词。
- **发放台账（claim / release / grants）已从 `ActivityMarketingService` 拆到独立的 `GrantService`**。`ActivityMarketingService` 保留同名委派方法（含旧三参 `claimInventory` 签名），**REST 路径与语义一字未变**；两者之间零共享状态，唯一的公共知识是上面那两条版本定义（各自注入 `ActivityVersionResolver`）。`ClaimResult` 另加了一个标了 `@JsonIgnore` 的 `FailureKind`（`BAD_REQUEST` / `NOT_FOUND` / `OUT_OF_STOCK` / `PER_USER_LIMIT`）——它**只用于服务端分流状态码，响应体一字节没变**（`ok` / `reason` 原样保留）。controller 侧的映射是**不写 `default` 的 switch 表达式**：新增一种失败种类而漏了映射就是编译失败；旧的三参 `ClaimResult` 构造器（未标注种类）沿用历史行为 409。
- **每人限领 `userInventory` 有执行路径了**：按 `activity_grant` 里 (活动, 用户) 的已领份数计。配了限领的活动，`claim` **不带 `userId` 一律拒绝**——无从判断是不是同一个人时放行，等于这条限制不存在。冲正走 `POST /{id}/release?orderId=`，把库存与限领额度一起还回去（归还刻意不判活动状态与时间窗：活动结束之后仍可能有退款进来）。

创建红包活动示例（资格 orderAmount≥100 + 手动绑定 spu 1001）：

```bash
curl -X POST localhost:8081/activity-marketing/create -H 'Content-Type: application/json' -d '{
  "activityName":"新人红包","bizLine":"mall","activityType":1,
  "activityStartTime":1780000000000,"activityEndTime":1790000000000,
  "activityAreaType":1,"priority":1,"inventory":100,
  "redPackageTakeType":1,"redPackageAmount":50,"redPackageAmountUnit":"元","discountStrategy":"MAX",
  "eligibilityConditionTree":{"logic":"AND","children":[{"field":"orderAmount","op":"ge","value":100}]},
  "spuBindings":[{"storeId":1,"spuId":1001}]
}'
# → {"activityId":"ACT...","version":1,"status":0,"idempotentHit":false,"autoBoundCount":0,
#    "warnings":["库存（100）当前为声明式：决策链路不读取、不扣减，不构成超发防护。…"]}
# 上线后 POST /spu-discount {"spuIdList":[1001],"orderAmount":200} → hit=true, amount=50
```

折扣型（八折、最多减 30）与一口价（9.9 秒杀）只是换 `redPackageAmountUnit`：

```bash
# 八折券：单位=折，折数写进 redPackageAmount，封顶必填
#   "redPackageAmount":8,"redPackageAmountUnit":"折","redPackageMaxDiscount":30
#   → 200 元订单减 40 → 封顶截到 30
# 9.9 秒杀：单位=价，redPackageAmount 是"卖多少"
#   "redPackageAmount":9.9,"redPackageAmountUnit":"价"
#   → 200 元订单减 190.1；随后 POST /activity-marketing/{id}/claim?version=1&quantity=1 才算抢到
# 第二件半价：单位=件折 + {"nth":2}，且决策请求必须带 lines（逐行单价）
#   "redPackageAmount":5,"redPackageAmountUnit":"件折","redPackageRangeAmount":"{\"nth\":2}"
#   请求：{"spuIdList":[1001],"lines":[{"spuId":1001,"unitPrice":100,"quantity":2}]} → 减 50
```

## 决策平面拆分（console / decision）

M2 把本模块沿**读写平面**拆成两个独立 Spring Boot 应用，共用 `activity-common`（domain/engine/persistence/tenant + 只读查询服务 `ActivityQueryService`）：

| 应用 | 端口 | 承载 | Maven 依赖 |
| ---- | ---- | ---- | ---- |
| `activity-console` | 8081 | 写平面（create/status/幂等/四眼）+ Step1–24 教学 + 前端 `/ui/` | `activity-common` + `drools-lab`（全量，含 kie-ci/dmn/decisiontables） |
| `activity-decision` | 8082 | 只读决策热路径 `/decision/v1/*` + 发布代际轮询预热 | 仅 `activity-common`（甩掉 `drools-lab` 带来的 kie-ci/dmn/decisiontables 与全部写面依赖，更轻） |

**`/decision/v1` 是决策热路径将来物理拆出去的稳定契约**——`DecisionPlaneController` 复用与控制台**同一份** `ActivityQueryService`（与 `/activity-marketing/spu-discount` 走同一代码，金额一致）；旧 `/activity-marketing/*` 路径保留、不弃用，前端与旧脚本不受影响。

唯一的差别是 **决策档位 `DecisionMode`**（2026-08 起取代裸 `boolean explain`，只有两个常量）：决策平面传 `HOT_PATH`——构建期就不生成 `result.trace(...)`，正常命中时 `traces` 为空（回退/说明性文案仍会出现）；控制台试算传 `EXPLAIN`，运营照旧看得到链路。档位**只影响 trace，绝不影响谁被淘汰、发多少钱**。

> **为什么必须是枚举、而且四个「省掉档位」的便捷重载被删掉了**：改造前 `ActivityQueryService.spuDiscount/buyAndGetGifts` 的无参重载默认 `false`（热路径），而姊妹服务 `AddOnPurchaseService.options/quote` 的默认是 `true`（试算）——**两个默认值方向相反**，在调用点上无法本地推理这次决策是哪一档。今天没出事只是因为 console 恰好调加价购那一侧、decision 恰好调红包那一侧，各自撞对了默认值。现在每个调用点必须显式表态，漏了就编译不过。<br>⚠️ 它与 `ActivityDrlBuilder` 的 `explain` **无关**：那个是**构建期**布尔，它改变生成的 DRL 文本，而 `compileOrGet` 的缓存 key 就是 `tenant + DRL 全文`——耦合进去会让同一份规则被编译两遍，所以买赠链路那里做了一次显式降级（`mode.explains()`），刻意留成 boolean。

| 决策平面路径（decision, 8082） | 等价的控制台路径（console, 8081） |
| ---- | ---- |
| POST `/decision/v1/spu-discount` | POST `/activity-marketing/spu-discount`（控制台版 `EXPLAIN`） |
| POST `/decision/v1/gifts` | POST `/activity-marketing/gifts`（同上） |
| POST `/decision/v1/addon/options` | POST `/activity-marketing/addon/options` |
| POST `/decision/v1/addon/quote?activityId=&item=` | POST `/activity-marketing/addon/quote?activityId=&item=` |

决策平面**独有**的端点（控制台没有等价物）：

| 方法 | 路径 | 说明 |
| ---- | ---- | ---- |
| GET | `/decision/v1/metrics` | 决策耗时 / 回退计数聚合（**单实例视角**，跨实例汇总仍看 Prometheus） |
| GET | `/decision/v1/by-activity` | 按活动的命中量 `hits` 与**发出的减免金额** `amounts`（带基数上限，见「决策指标」） |
| GET | `/decision/v1/snapshot[?activityId=]` | 本租户的快照桶清单（bizLine / generation / builtAt / ageSeconds / activityCount）；带 `activityId` 时直接回答**在哪个桶 / 不在任何桶** |
| POST | `/decision/v1/snapshot/rollback?bizLine=` | **快照回滚**（2026-08 新增）：把本租户这条业务线的决策指针切回上一个发布代际，立刻生效。上面三个是只读诊断，**这一个是写动作** |

**`/snapshot/rollback` 补的是一张空头支票**：`DecisionSnapshotStore.rollback()` 此前**零生产调用方**（全仓只有测试在调），也就是说「回滚是求值出 bug 时的止损手段」这句承诺一直按不下去——止损手段只有能从生产按下去才算数。它的边界必须一起记住：

- **它不写数据库**，只动本进程内存里的指针，因此不违反「decision 连只读账号」的边界。推论有两条：**① 只影响被打到的那个实例**（多实例部署要逐实例调用）；**② 下一次代际推进会把它盖掉**——回滚是止血，真正的修复仍是在 console 侧下线/改配置再发布一代。
- 它是运营级操作，与 create/status/claim/confirm/release 及 AwardIntent 触发入口共用 `activity.tenant.auth.console-write-authority`（配了才生效）；角色门上它与 `GET /snapshot` 同级（`console` 角色的实例上 404）。
- **没有上一代可回时返回 409**（而不是假装成功），并在响应里给 `hint`。常见于「刚重启、只发布过一代」，以及「上一次推进是兜底重建（`refresh`，按设计不占回滚槽位）」。响应体带 `fromGeneration` / `toGeneration` / `activityCount`。

**`/snapshot` 这个诊断端点为什么必须存在**：`provenance` 三个值在最要命的那条故障上**全绿**——活动的 `bizLine` 为空（写平面不强制必填）时它进不了任何桶（`DecisionSnapshotBuilder` 按 bizLine **精确匹配**收活动），而兜底重建只遍历**已存在**的桶、永远建不出不存在的那个。此时决策照常走快照、代际是别的业务线的正常数、快照也很新，只是这个活动根本不在里面，页面上看到的「未命中」与「活动确实不该命中」完全同形。`/snapshot` 是这个困惑的终点。它**只读、不发起决策**，因此不会把诊断流量混进 `activity.decision.{hit,amount}`，也不消耗 `ACTIVITY_TAG_CAP` 的标签位。

> ⚠️ 它返回的 `ageSeconds` 取的是**本租户**的桶，与 gauge `activity.decision.snapshot.age.seconds`（`DecisionSnapshotStore.oldestAgeSeconds`，**跨租户**统计——调度线程与指标线程没有租户上下文）**不是同一个数**，多租户下两者永远对不上，别拿来互相印证。

**加价购为什么必须两阶段**：卡点不在算钱而在交互形状——既有链路是「一次调用返回最终优惠」，加价购必须先列清单、等用户挑一个、再二次定价。第一阶段与红包、买赠复用 `DecisionEligibilityService` 的请求上下文与 fail-closed 资格判断；第二阶段**不发 quoteToken、不读客户端传来的价格**，只接受「哪个活动的哪个换购品」，并重新跑资格、重新读取当前选项与价格，失效或伪造返回 409。数据上复用 `activity_gift` 承载换购品，`giftName` 是品名、`absoluteAmount` 是**加价金额**（不是赠品价值）；加价金额 ≤0 的选项直接排除（那不是加价购）。console 别名沿用同一服务与既有租户/JWT 边界。两阶段都只查询/报价、不会调用 `claim` 或占库存；秒杀验证也只是试算，只有显式调用写平面的 `/{id}/claim` 才会扣库存。

- **角色门控**（`RoleGateFilter`，靠 `activity.role`，仅显式设置该属性时才装配）：`decision` 只放行 `/decision/v1/**` + `/actuator/**`；`console` 屏蔽 `/decision/v1/**`、放行写面 + Step1–24 + SPA；`all`（默认，本地/测试）全开。这是**部署角色边界**而非安全边界（同一份代码），真隔离仍靠 Casdoor 验签 + `@TenantId`。
- **发布代际轮询预热（M1.4）+ 代际快照包（P1-1）**：console 的**任何活动状态变化**（上线 / 下线 / 回待上线）都在同一事务里 bump `(tenant,bizLine)` 代际——只在上线时发的后果是**下线传播不出去**，decision 的快照继续按原配置发钱。`bizLine` 为空时跳过 bump（但状态照常落库）：`activity_generation.biz_line` 是 NOT NULL，插 null 会在同事务里抛约束违例、把刚写下的状态一起回滚，「下线传播不出去」会升级成「下线根本做不到」；而没有 bizLine 的活动本来就进不了任何快照，也就没有代际可传播。decision 后台按 `activity.marketing.generation-poll.interval-ms`（默认 3000ms）轮询，见代际增长即走**三步、切指针在最后**：① 构建决策快照 → ② 预热该 `(tenant,bizLine)` 的全部 ACTIVE artifact → ③ 全部成功才 `publish` 切指针。<br>**publish 排在最后是 2026-08 修的**：此前是「先 publish、再查 ACTIVE artifact、再预热」，中间任何一步抛异常都会被轮询器吞掉且**不更新 `lastSeen`**——于是一次半完成的推进已经被记成一次发布（占掉了回滚槽位），下一轮还会对同一代际再来一遍。现在三步共享同一个失败边界：要么整体推进，要么整体留在上一代等下轮重试。「先建好快照再切指针」这条更早的约束当然仍成立。
- **`bizLine` 为空的孤儿活动现在会在构建期吵**：每次构建真实桶时数一次「有多少已上线活动的 `bizLine` 为空」，落 WARN 日志 + 计数器 `activity.decision.snapshot.orphan`。这类活动进不了任何桶（构建期按 bizLine **精确匹配**），而决策侧 `provenance` 三个值全绿、回退率/耗时/命中数全都不动——此前只有诊断端点 `GET /decision/v1/snapshot?activityId=` 照得出来，而那要求排查的人**已经怀疑到某个具体活动头上**。绝对值没有意义（每次构建按当时库存量 `increment(n)`），能用的是 `rate(...) > 0`：数据修干净后立刻停。观测失败不会拖垮构建。
- **快照兜底重建**：轮询器每轮扫完代际后，把年龄超过 `activity.marketing.snapshot.max-age-ms`（默认 60s）的快照按数据库真相重建一遍。它守的不是某个已知 bug，而是「信号漏发」这**一整类**故障（bump 因异常没提交、轮询线程被拖死后恢复、构建抛异常导致 lastSeen 没更新……都表现为快照静默过期而决策照常成功）——代际信号依赖每个写入口都记得发信号，那是一条要人持续维护的纪律，本仓库已经在它上面失手过一次（下线不 bump）。有了兜底，后果从**永久**降为**一轮**。它走 `DecisionSnapshotStore.refresh` 而**不是** `publish`：这不是一次发布，不能占用回滚槽位，否则 `rollback` 会退到几十秒前的自己 = 等于没回滚；代际号也保持不变。
- **decision 不碰 DDL**：`activity-decision/application.yml` 的 `ddl-auto` 已从 `update` 改回 **`validate`**（此前只有 compose 的环境变量盖住它，按文档化的 `./mvnw -pl activity-decision spring-boot:run` 本地起会带 DDL 权限跑），并由 `DecisionDdlGuardTest` 钉死防漂回。建表仍由 console 独占。
- **网关**（Compose 共 7 个服务）：nginx 自行托管 `/ui/*`，把 `/api/decision/*`→decision、`/api/console/*`→console、其余后端入口→console；host 端口由 `DROOLS_UI_PORT` 决定（默认 8095）。`docker compose stop console` 后 `/api/decision/*` 仍可决策，可当场展示拆分价值。

### 取数：快照优先，回落批量查库

热路径取数已从 `ActivityQueryService` 拆到 `DecisionDataLoader`（求值留在原处，编排层只剩几十行）。取数层的**唯一出参**是顶层值对象 `Materials(candidates, eligibilityDefs, eligibilityTrees, provenance, strategy)`：

> **`Materials` 与 `OfferSpec` 是 2026-08 补的两道类型级约束**，针对的是同一类事故——「同一张券在走库与走快照两条路上发不同的钱」（不报错、不回退、日志干净，只有对账时才发现）。
>
> - **`Materials` 提升为顶层类型**（原先嵌在 `DecisionDataLoader` 里，快照那条路只好另造一个自称「同形」的影子 record `DecisionSnapshot.Materialized`，再手工缝合：拆开候选与约束、手算最小代际、手拼 provenance，最后还要在**另一个方法**里第二次判定来源才能取到合并策略）。现在两条路在类型上只能产出同一个值对象，缝合收敛成 `Materials.merge` / `.ordered()`；**来源判定也只剩一处**——此前合并策略在独立的 `resolveStrategy` 里用**另一个判据**又判了一次来源，两个判据不同步时会出现「物料走快照、策略却回去查库」的混合结论，而 `provenance` 只声明物料那一半，这种混合在响应里是看不见的。`resolveStrategy` 已删，策略随物料从同一个来源出来。
> - **`OfferSpec` 是「行 → 候选」的唯一装配入口**（不可变 record，走库的 `flatten` 与快照构建都调 `OfferSpec.from`）。此前同一份配置被**三处手写**地铺进 `ActivityCandidate` 的十几个字段（走库 17 个 setter / 快照 `CandidateTemplate` 18 个位置参数 / `toCandidate` 又一遍 setter），只有中间那份被编译器守着——**同一条缝已经裂开过两次**：`scopedSpuIds`（CLAUDE.md 坑 16）与 `redPackageMaxDiscount`。收成规范构造器之后，「加一个配置字段却漏了某条路」在类型上不可表达。`CandidateTemplate` 已删除，`ActivityCandidate` 改成持有 `OfferSpec` + 本轮计算态。
> - **`scopedSpuIds` 与本轮计算态刻意不在 `OfferSpec` 里**：前者是「请求 SPU ∩ 本活动当前版本绑定」的逐请求交集，不属于配置，且 `null`（作用域未知 → 按整单算）与空集（作用域已知）的语义差异必须保留。

- **命中代际快照 → 零数据库查询**。`DecisionSnapshot` 是某 `(tenant,bizLine,generation)` 的不可变物料包：SPU→活动 id 倒排索引、**`OfferSpec` 权益配置**、已翻译的受控约束、已解析的条件树、合并策略，全部在**发布侧的后台线程**建好。`DecisionSnapshotStore.publish()` 是**发布路径唯一**的指针切换点（另外两条改指针的路径是：兜底重建 `refresh()`——按设计**不占**回滚槽位；止损 `rollback()`——**消费**回滚槽位，只留一代，回滚后 previous 清空，再回滚一次必须失败而不是静默成功）。发布时保留上一代供 `rollback()`，入口见上节。<br>store 的一个桶现在是**一个不可变 `SnapshotSlot(current, previous)`**、靠 `ConcurrentHashMap.compute` 一次原子替换：此前 current 与 previous 是两张 map，「切当前代」与「移交上一代」是两条独立语句，中间存在一个两张表互相矛盾的窗口。另外**只有代际前进时才移交回滚槽位**——预热失败时轮询器不更新 `lastSeen`，下一轮会对**同一代际**再发一次，若同代重发也占槽位，previous 就被挤成「同一代的旧副本」，`rollback` 从此是空转（退到的还是出事的这一代）。
- **快照不存在 → 回落逐请求查库，不超过 5 次**：红包是「按 SPU 查绑定 / 批量查活动全部未删除版本 / 批量查规则行 / 批量查资格条件行 / 查合并策略」；买赠与加价购把最后那次**换成**批量查赠品行——合并策略行本身就是按 `DISCOUNT` 场景存的，这两条通道不合并权益、也从不读 `Materials.strategy()`（它们在**走库路径上** `strategy` 恒为默认 `MAX`；快照路径上它随桶白来，仍是该 bizLine 的真值）。<br>**次数没变，变的是策略在哪一步解析**：2026-08 之前它由编排层在取数之后单独调一次 `resolveStrategy`，现在随物料一起从取数层出来（见上面 `Materials` 那条：那次独立调用用的是**另一个来源判据**）。原实现是 `3N+2` 次往返（N=候选活动数），索引全建对了也救不了 round-trip 次数。上限由 `DecisionQueryCountTest` 钉死。
- **构建期的查询数也是常数**：一次快照构建固定 6 次（活动 / 规则 / 赠品 / 条件 / 绑定 / 合并策略），真实桶另加一次孤儿 bizLine 计数 = 7 次，**与活动目录规模无关**，由 `SnapshotBuildQueryCountTest` 钉死。改造前是 `5+N`——固定的只有 5 次（活动 / 规则 / 赠品 / 条件 / 合并策略），绑定查询在 `for (活动)` 循环体里逐个发（N+1，是仓库接口缺批量方法逼出来的），活动那次还是**全租户**扫描再用 Java 丢掉非本桶的；批量化后绑定成为第 6 次固定查询，孤儿 bizLine 计数是本次新增的第 7 次。（`SnapshotBuildQueryCountTest` 的 `EXPECTED_QUERIES` javadoc 里那个 `6+N` 是同源笔误。）热路径当年有门禁、构建期一道都没有，而它每分钟被兜底重建重跑一遍、全打在只读连接上。<br>⚠️ bizLine 过滤虽然**下推进了 SQL**，Java 侧那行精确 `equals` **删不得**：生产 MySQL 8 的默认排序规则 `utf8mb4_0900_ai_ci` 大小写/重音不敏感，`biz_line = 'retail'` 会把 `Retail` / `RETAIL` 的在线活动一并收进 `retail` 桶——而桶归属决定的是**谁能被发钱**。更麻烦的是它测不出来：测试跑在 H2 上（字符串比较默认大小写敏感），两条谓词在测试里恒等价，只在生产 MySQL 上分叉。这条由 `SnapshotBizLineCollationTest`（用 `IGNORECASE=TRUE` 把 H2 调成生产那种不敏感行为）钉住。
- **取数层与快照构建注入的是只读仓储**：六个 `*ReadRepository` 继承 `Repository<T, ID>` 而**不是** `JpaRepository`，于是 `save` / `delete` **在类型上不存在**。此前「决策侧不写库」只靠 MySQL 只读账号在**运行期**兜住——读路径上一次手滑的 `save(...)` 能编译、能过全部单测（测试库可写），只在生产炸。现在这条保证提前到编译期，由 `DecisionReadRepositoryGuardTest` 钉住。
- 这个回落**自调节、无开关**：`DecisionSnapshotBuilder` 虽然是 `activity-common` 里的 bean（两个 app 都有），但只有 decision 的代际轮询器会调它；console 从不构建快照，store 恒空，legacy 读端点天然走库。两条路语义由 `SnapshotParityTest` 对拍。
- **脏合并策略行现在两条路都 fail-safe 回落 `MAX` + WARN**（2026-08）：`StackStrategy` 拆成两个解析口径——写平面 `fromCode` 仍**严格抛**（创建期就该拒），读路径（决策取数 / 快照构建）走宽容的 `tryFromCode`，读不懂返回 null 由调用方回落 MAX。此前读路径也走 `fromCode`，于是一条脏策略行会让整次决策 500——而「查不到策略行」本来就是 `orElse(MAX)`，脏行给决策的可用信息量与查不到完全相同，这条不对称没有道理。两条路必须同口径，否则快照与走库会对同一条脏数据发不同的钱。
- **刻意不在构建期预过滤时间窗**：活动生效窗仍在请求时用 `Instant.now()` 判——否则快照会在时间跨过窗边界时悄悄过期（20:00 开始的活动要等下一次发布才出现）。
- **走库侧的候选身份也按版本收窄**：绑定查询不带 `version`、旧版本的绑定行也不软删，于是「v1 绑 A/B → 编辑成 v2 只绑 A」之后单查 B，走库路径仍会把它当候选（作用域为空）；而 AMOUNT 形态**不看基数**，会把 `redPackageAmount` 原样发出去——走库照发 50 元、走快照根本不是候选。判据与快照侧对齐：**当前线上版本的绑定 ∩ 本次请求 SPU 为空 ⇒ 不是候选**。这不会误伤全场券：走库路径的候选身份本来就只从绑定行推出来，没有任何绑定的活动压根进不了候选集。由 `SnapshotParityTest#narrowedBindingStopsPayingOnBothPaths` 守。
- **候选在两条路的合流点统一按 activityId 定序**（`Materials.ordered()`，取数层出口只调一次）：`pickByAmount` / `pickByPriority` 打平时是严格 `>` 比较 = 先到先得，而两条装配路径的天然顺序都不可靠——快照侧倒排索引的值是 `Set.copyOf`，迭代序由 JDK SALT 决定（**同一进程内稳定，每次 JVM 启动翻面**，表现是 decision 重启后一整片决策的赢家可能翻面，比逐请求抖动更难认出来）；走库侧跟着 SQL 返回顺序走，没有 order by 就没有承诺。定序把「金额并列时谁赢」变成确定且可解释的结果。<br>⚠️ 定序**刻意不放进 `Materials` 的规范构造器**：那会改变所有手工构造物料的测试桩的候选顺序，而「打平先到先得」会让一批断言静默翻面。

### 物料溯源（`provenance`）

`DiscountView` / `GiftView` / `AddOnOptions` / `AddOnQuote` 四个响应契约都带 `provenance`（都保留了不带它的兼容构造，缺省取**保守值 `db`**——缺省成 snapshot 会让一条从没碰过快照的路径谎称走了快照）：

| 字段 | 含义 |
| ---- | ---- |
| `source` | `snapshot` = 物料来自代际快照（零数据库查询）；`db` = 逐请求查库 |
| `generation` | 参与本次决策的快照桶里**最落后的那一代**；`source=db` 时为 null |
| `buckets` | 参与本次决策的桶数 |

- **为什么它必须进业务响应而不是只当指标**：`activity.decision.source` 回答的是「整体上多少比例走了快照」，而运营/QA 在验证页上问的是「**我这一次**看到的结论，是照着数据库现状算的，还是照着一份可能落后的快照算的」。后者只能由响应自己回答。
- **`generation` 取下确界而不是最大值**：一次决策会合并该租户**所有业务线**的桶，这个数要回答的是「我刚发布的那次进去了没有」——任何一个桶落后都意味着「还没全进去」，取最大值会把落后的那个桶藏起来。`buckets > 1` 时它是多个桶的下确界而非某一个桶的真值，`buckets` 字段就是这个约定的**诚实声明**。拿它跟 console 的 `GET /activity-marketing/generation?bizLine=` 对照，才知道快照跟没跟上。
- 加价购第二阶段的 `provenance` 来自 **quote 自己那次**重新装载，不是第一阶段的：两阶段之间快照可能已经换代，而「这个价是按哪一代报的」正是这个端点最该自证的事。
- **决策审计日志已覆盖三条通道**（2026-08）：拼装收敛到 `DecisionAuditor`（独立 logger 名 `activity.decision.audit`，单行 JSON、不走序列化框架），红包 / 买赠 / 加价购各有一个出口。此前它是 `ActivityQueryService` 的私有方法且 scene 写死红包——买赠生成了 `decisionId` 却从不落盘，加价购的两个 record 连 `decisionId` 分量都没有，于是「拿着 decisionId 去日志里查」只在红包通道上成立，另外两条查出来一无所获，**而客服并不知道这个区别**。现在 `AddOnOptions` / `AddOnQuote` 都带 `decisionId`（纯增量，兼容构造缺省为 null），quote 的两阶段共用**同一个** id（一次 quote 就是一次决策，内部那次重新装载是它的一部分）。
  - 红包那条日志的**字段顺序与改造前完全一致**，取值在不含 JSON 特殊字符时逐字节一致——它已经是日志系统里的检索契约。**转义是有意改过的一处**：改造前只对 `rejectReason` 做 `replace("\"", "'")`、其余字段（activityId / form / strategy / mode / source / hitActivityId）零转义，现在统一走 `DecisionAuditor.escape`（双引号输出成 `\"`，另转反斜杠与 `\n`/`\r`/`\t`/`\b`/`\f` 及控制字符），含这些字符的值上两者输出不同。引号与转义收敛成一处：`quoteOrNull` 保留裸 `null`（不是字符串 `"null"`，这个区别对下游解析是实打实的）、`quoted` 恒带引号。
  - 三条通道都落 `source` / `generation`：只有 `hitVersion`（活动版本）而没有代际时，「活动版本对、但快照是旧代」这类事故在日志里查不出来——而它恰恰是客服最难缠的那类工单。

### 决策指标（`DecisionMetrics`）

改造前 `activity-*` 主源码里一处 `MeterRegistry` 都没有，Prometheus 抓的全是 JVM/HTTP/CPU——**决策本身是黑的**，而 `safeRun` 的 fail-safe 回退会**改变实际发给用户的金额**却只打一条 `log.warn`。现在：

| 指标 | 类型 | 标签 | 用途 |
| ---- | ---- | ---- | ---- |
| `activity.decision.duration` | Timer | scene, mode | 决策耗时分位。红包/买赠的 `mode` 取 `rule-engine` / `legacy`（与响应体的 mode 字段同源）；**加价购的 `mode` 取阶段名 `options` / `quote`**——它压根不进规则引擎，也就无所谓回退，硬填 `legacy` 会让面板读成「加价购一直在回退」；而 quote 内部会重跑一遍装载与资格，耗时天然高于 options，混在一个序列里看分位数只会互相污染 |
| `activity.decision.fallback` | Counter | scene, reason | **回退率——头号告警项** |
| `activity.decision.candidates` | Summary | scene | 候选数分布 |
| `activity.decision.source` | Counter | scene, source | 物料来自 `snapshot`（零查询）还是 `db`（逐请求查库）。⚠️ **2026-08 `scene` 标签值改了**，见下 |
| `activity.decision.hit` | Counter | scene, activityId | 按活动的命中量（控制台工作台的数据来源）。三条通道的**口径不同**，见下「命中计数」 |
| `activity.decision.amount` | Summary | scene, activityId | **实际发出的减免金额**分布（带 percentile histogram）。补它之前，「满 300 减 50」被配成「满 3 减 50」在监控上全盘绿灯：回退率 0、耗时正常、命中数只是稍高。**仍只在红包出口调用**（买赠发的是实物；加价购虽已补齐其余埋点，但换购加价不是减免额） |
| `activity.decision.clamped` | Counter | — | 减免额超过订单金额被截断的次数。**正常业务恒 0，出现一次即疑似配错**（面额多打个零 / 门槛写反 / 叠加没上限），建议按「>0 即告警」配 |
| `activity.decision.reject` | Counter | scene, reason | **「配了但不发」的唯一信号**。此前淘汰只写在候选的 `rejectReason` 上，而热路径是 `HOT_PATH` 档，那个字段与 trace 两个出口在生产上都不打开 |
| `activity.decision.snapshot.count` | Gauge | — | 本进程持有的快照桶数。0 = 该实例全部决策在走库 |
| `activity.decision.snapshot.age.seconds` | Gauge | — | **最旧**快照的年龄（跨租户；-1 = 无快照）。**下线传播断掉时唯一会动的读数**，建议阈值取轮询间隔的数倍 |
| `activity.decision.snapshot.orphan` | Counter | — | **2026-08 新增**：每次快照构建时数一次「有多少已上线活动的 `bizLine` 为空」（这些活动进不了任何桶 = 永远不会被命中）。绝对值无意义（按当时库存量 `increment(n)`），按 `rate(...) > 0` 用；刻意不打 tenant / bizLine 标签（同一笔基数账），定位到具体活动看构建期那条 WARN 日志 |
| `activity.rule.compile` | Timer | outcome | KieBase 编译次数与耗时（落在请求线程上的那部分 = P99 尖刺证据） |
| `activity.rule.fire.ceiling` | Counter | scene | fire 触顶（runaway 护栏被触发） |
| `activity.rule.cache.entries` / `.hit.ratio` / … | Gauge | — | KieBase 缓存条目数 / 命中率 / 足迹（Caffeine stats 绑上来） |

- **`scene` 标签的词汇表收敛成一个枚举 `DecisionScene`**（`spu-discount` / `gifts` / `addon` / `benefit`）。<br>⚠️ **`activity_decision_source_total` 的 `scene` 取值 2026-08 改过一次**，这是有意的契约变更：它此前用的是 `ActivityType.name()`（`RED_PACKAGE` / `BUY_AND_GET` / `ADD_ON_PURCHASE`），而本类其它九个指标用的是 `DecisionScene` 词汇表——后果是 `activity_decision_source_total{scene="gifts"}` 查出来**恒为空**，而「按 scene 把回退率与来源占比 join 起来看」正是这条指标存在的理由：**它此前一 join 就空，且空得毫无提示**。已核对 `deploy/` 下没有消费者需要同步改（Grafana 面板只查 JVM 与 HTTP 指标；`prometheus.yml` 没有 `rule_files`，也就没有告警规则引用它）。影响面只剩手写的临时查询：旧的三条时间序列停止增长、三条新序列开始增长，历史数据仍在旧标签下可查。<br>⚠️ **`reject` / `benefit` 那个第四取值仍在**：它表示**阶段**（算额）而不是通道，与 `spu-discount` / `gifts` / `addon` 并列出现——算额阶段的淘汰全记在这一格，按 scene 统计「买赠通道淘汰量」会漏掉它们。**刻意保持不变**（换成真实通道又是一次 Prometheus 序列变更，要与 Grafana 同批做），代码里留着 TODO。
- **`fallback` 的 reason 标签是有限集**：`engine-disabled`（总开关关闭）、`empty-decision`（跑通了但没结论）、`condition-tree-unavailable`（声明了受控约束却拿不到条件树）。**绝不把异常全文塞进标签**——编译错误带行号与 DRL 片段，会直接爆掉基数。<br>⚠️ 以上三个是 `DecisionScene` 档的取值；**另有三个仍在发射**：`ActivityRuleRuntimeService.safeRun` 走 `DecisionMetrics` 裸 String 档的 `fallback(String, String)` 重载，按异常类型产出 `compile-error`（DRL 编译失败）/ `fire-ceiling`（runaway 护栏 halt）/ `eval-error`（其余异常），而它跑在**生产买赠链路**上（`evalGift` → `safeRun`）。它传的 scene 是 `RuleScene.name()`（`ELIGIBILITY` / `LADDER` / `GIFT`），与 `DecisionScene` 词汇表对不上——按 `scene="gifts"` 统计买赠回退会漏掉这三类，`DecisionMetrics` 里那条 TODO 记的就是这个未收敛的 scene 词汇。
- **`reject` 的 reason 由 `RejectReason` 枚举唯一定义**——**码与文案钉在同一行**：`code()` 进指标标签（已是线上 Prometheus 序列，一个字节都不能改）、`message()` 进候选的 `rejectReason` 并经 `DiscountItem` 出到响应、被验证页直接渲染（前端测试直接断言这些中文串）。两段来源：资格阶段 `ineligible`（用户不符合门槛，**正常业务**）/ `condition-unavailable`（条件树不可判定，**是故障**——两者分开计数是关键，处理方式完全相反）；算额阶段 `no-ladder-tier` / `missing-lines` / `bad-random-range` / `bad-ratio` / `price-above-base` / `out-of-scope`（基数算不出来时统一归到它，且**优先于形态自己的原因码**——「算不出基数」和「基数不够」是两种排查方向）。<br>收成枚举之前，同一件事有三份互不相干的拷贝（指标码 / 中文串 / `ActivityDrlBuilder` 在 DRL 文本里 emit 的第三份），靠人在每个调用点手工配对：**漏码** → 这类淘汰在指标里凭空消失，**漏串** → 用户看到空的「未生效」原因，两者都不会让任何测试变红。漂移已实证发生过一次——文档此前那条「javadoc 写 `price-above-order`、代码实际发 `price-above-base`，以代码为准」的注记就是它的化石，现在**由枚举结构性消除**。<br>⚠️ 算额阶段的 `rejectReason` 出到响应时会带「本活动不适用：」前缀，资格阶段不带；这个不对称是既有行为，前缀由算额阶段自己拼，枚举里存的是**不带前缀**的 message。
- **`activityId` 标签有基数上限** `DecisionMetrics.ACTIVITY_TAG_CAP = 200`，超出并进 `__over_cap__` 哨兵：总量仍准，只是分不出是哪几个。活动数是运营行为不是工程可控量，基数爆炸的代价是整套监控在大促当天一起挂。
- **命中计数：三条通道都收在各自的唯一出口上，但口径各不相同**——
  - **红包**：打在决策的唯一出口，不打在"引擎命中"那个分支里，否则回退路径（legacy 也会命中活动）系统性少计，而**少计的指标比没有指标更危险**：它看起来是权威的，而回退恰恰是最该盯的时刻。
  - **买赠**：2026-08 从「DRL 命中路径与回退路径各打一次」收到出口一处，口径同时改成**按实际发出赠品的来源活动去重计数**（一个活动出三件赠品仍只算一次，否则命中量会被赠品配置条数放大）。引擎分支本来就等价（DRL 的 LHS 要求 `gifts.size()>0`），**变的是回退分支**：一个「资格通过但一件赠品都没配」的活动，回退时的命中量会从 1 掉到 0——看板上读起来像「回退后这个活动突然不命中了」，实际是口径修正，它本来就没发出任何东西。
  - **加价购**：2026-08 补齐埋点（`duration` / `candidates` / `hit`），命中**只打 quote 不打 options**——options 只是列清单、没有替用户选定任何东西，把它也算成命中会让加价购的命中量恒等于曝光量。⚠️ 于是它开始与红包/买赠**抢同一份 200 个 `activityId` 标签位预算**（`ACTIVITY_TAG_CAP` 是跨 scene 共享的全局预算），「按活动看命中量/金额」的分辨率会在活动目录变大时提前塌掉。
- 建议告警：`rate(activity_decision_fallback_total[5m]) / rate(activity_decision_duration_count[5m]) > 0.001`。
- **Gauge 的状态对象必须是 cache 本身**：Micrometer 对状态对象持弱引用，传构造期临时 lambda 会在构造返回后被 GC，指标变成 NaN——而 NaN 在面板上看起来只是"没数据"，是最难察觉的埋点失效（本项目已在 docker 验证中踩到一次）。

```bash
# 直连 decision 服务的决策别名（等价 console 的 /activity-marketing/spu-discount）
curl -X POST localhost:8082/decision/v1/spu-discount -H 'X-Tenant-Id: acme' \
  -H 'Content-Type: application/json' -d '{"spuIdList":[1001],"orderAmount":200}'
# 经网关：POST localhost:${DROOLS_UI_PORT:-8095}/api/decision/spu-discount（同 body）
# Compose 默认 auth=true，需再带 Authorization: Bearer <valid-token>

# 加价购两阶段
curl -X POST localhost:8082/decision/v1/addon/options -H 'X-Tenant-Id: acme' \
  -H 'Content-Type: application/json' -d '{"spuIdList":[1001],"orderAmount":200}'
curl -X POST 'localhost:8082/decision/v1/addon/quote?activityId=ACT...&item=保温杯' \
  -H 'X-Tenant-Id: acme' -H 'Content-Type: application/json' -d '{"spuIdList":[1001],"orderAmount":200}'

# 决策指标（单实例视角）
curl localhost:8082/decision/v1/metrics -H 'X-Tenant-Id: acme'
curl localhost:8082/decision/v1/by-activity -H 'X-Tenant-Id: acme'

# 「我的活动到底在不在决策服务眼里」——只读诊断，不发起决策、不占指标标签位
curl 'localhost:8082/decision/v1/snapshot?activityId=ACT...' -H 'X-Tenant-Id: acme'
# 库里当前代际（写平面），拿它跟上面 provenance.generation 对照才知道快照跟没跟上
curl 'localhost:8081/activity-marketing/generation?bizLine=mall' -H 'X-Tenant-Id: acme'

# 止损：把这条业务线的决策指针切回上一代（写动作！只影响被打到的这个实例，
# 下一次代际推进会覆盖它；没有上一代时返回 409 + hint，不会假装成功）
curl -X POST 'localhost:8082/decision/v1/snapshot/rollback?bizLine=mall' -H 'X-Tenant-Id: acme'
```

## 多租户隔离（P0-4，Track B）

活动数据按租户隔离，**靠机制不靠纪律**：20 张实体表里 **18 张**带 `@TenantId`（直接声明或继承 `TenantScopedEntity`），Hibernate 对查询自动追加 `tenant_id = ?` 谓词、insert 自动落租户——业务代码不手动拼 where、不手动 set 租户，漏不掉（`TenantArchGuardTest` 另外钉死「不手写 tenant 谓词、不用 `nativeQuery`」）。发放流水、不可变分录、grant outbox、AwardBinding 与 AwardIntent outbox 都在这 18 张之内。

> 两个例外都有明确原因：`activity_generation` 不加 `@TenantId`、改用显式 `tenant_id` 列，因为 decision 侧代际轮询运行在没有 `TenantContext` 的后台线程；否则 `findAll()` 会被追加 `tenant_id = NO_TENANT` 而恒空。`district` 是全租户共享的国家行政区划字典，不含租户列。它们是例外，不是隔离遗漏。

> **公共列 2026-08 收进两层 `@MappedSuperclass`**：`TenantScopedEntity`（`tenant_id` + 双时间戳）与 `SoftDeletableTenantEntity`（再加 `is_del`）。分两层是因为发放账、分录与 outbox **确实没有 `is_del` 列**——发放不软删，冲正走状态机与追加分录，不为了「都一样」给账务事实硬塞一列。列名、长度、`nullable` 逐字节照搬原实体，**生成的 DDL 与改造前完全一致**（decision 侧是 `ddl-auto: validate`，这里任何一处漂移都会让只读平面起不来）。<br>⚠️ 两个连带后果：① `TenantArchGuardTest` 必须**沿继承链往上找** `@TenantId`，只看 `getDeclaredFields()` 会把每个继承来的实体误判成「缺租户列」；② Jackson 默认把超类属性排在子类之前，收上来的那一刻 `/activity-marketing/{list,detail,grants}` 里每个实体对象的键序就从 `{"id":…,"activityId":…}` 变成了 `{"tenantId":…,"createdStime":…,…}`——字段名与取值一个字节没变，但那仍是响应体的一次**静默**改变，对响应做 hash / ETag / 快照比对的下游会飘。**已修回**：`TenantScopedEntity` 上一个 `@JsonPropertyOrder({"id","activityId","version"})` 把身份字段提回队首（一处注解覆盖全部继承实体），由 `EntityJsonOrderTest` 钉住。

- **租户来源（可插拔接缝，两档）**：`activity.tenant.auth.enabled=false` 时从 HTTP 头 `X-Tenant-Id` 取（仅 dev/header 档）；`=true`（Compose 默认）时 `/activity-marketing/**` 与 `/decision/v1/**` 都需带 Casdoor 验签 JWT，**租户从 `aud`(client_id) 解析**（命脉实测：Casdoor client_credentials 的 `owner`=admin 非组织；`aud` 由 Casdoor 绑定到已认证 client + 独立 secret → 不可伪造，比 owner 更实在），信封 `X-Tenant-Id` 只校验（≠解析出的租户→403）、绝不作来源。两档都写进同一个 `TenantContext`(ThreadLocal)，下游 `@TenantId` 隔离机制一行不动。
  - **aud→tenant 解析**：`AudienceTenantResolver` —— `client-tenant-map` 显式映射优先（生产推荐），`activity-{tenant}-cid` 家族反解兜底；`AudienceTenantValidator` 常开，aud 解析不到租户即拒（401）。
  - **浏览器 Casdoor 档**：`./deploy.sh --provision-auth` 幂等创建 acme/beta public SPA client 与当前 `DROOLS_UI_PORT` callback；M2M 调用方仍使用 `scratchpad/casdoor-m2m-verify.sh` 的独立 client_credentials。
- **前端**：dev 档显示 `X-Tenant-Id` 切换条；Casdoor 档使用 Authorization Code + PKCE、state、sessionStorage token 和 Bearer，登录回调为 `/ui/auth/callback`，统一门户只跳目标 `/ui/login` 而不接触 token。
- **fail-closed**：`TenantContextFilter` 是面向用户的闸，挂在**两个平面**上——`/activity-marketing/*` **与 `/decision/v1/*`**。无 `X-Tenant-Id` 且 dev-default 关时直接 **403**；`X-Tenant-Id` 含非法字符（非 `[A-Za-z0-9_-]{1,64}`）**400**。其它教学 Step（1–24）不挂此过滤器、不受影响。
  - 该过滤器写于 P0-4，当时只有 `/activity-marketing/*`；M1.1 加决策平面时**漏了同步扩 URL 模式**，于是 header 档下 `/decision/v1/*` 完全不解析租户——`X-Tenant-Id` 被静默忽略，全部落到 `TenantIdentifierResolver` 的兜底（dev-default 或 NO_TENANT），即 A 租户查到的是 dev-default 的活动。auth 档不受影响（`JwtTenantFilter` 挂的安全链本来就同时匹配两个平面）。这条由 docker 端到端验证发现——单元测试全跑在 dev-default 下，恰好绕过缺口；回归由 `DecisionTenantHeaderTest` 钉死。
- **dev-only 默认租户**：`activity.tenant.dev-default-enabled=true` 时，不带头的请求回落到单租户 `__dev__`，方便本地/前端手点；**生产必须关**（默认就是关 = 无头即 403）。
- **只做数据行隔离**：字段 schema（`/field-dict` 白名单）当前仍全租户共享（`RuleSchemaRegistry` 仍走 `DEFAULT_TENANT`）；按租户定制字段元数据属后续（P0-1 的 Track B 扩展），不在 P0-4。
- **结构守卫**：`TenantArchGuardTest` 钉死两条不变量——每个 `@Entity` 必带 `@TenantId`（全局表走白名单显式豁免）、仓库不得用 `nativeQuery`（原生 SQL 会绕过租户过滤）。加了 `@TenantId` 后裸 `findAll()` 已被机制自动加谓词、本身安全。

```bash
# 显式带租户（生产形态）：acme 建的活动，globex 看不到
curl -X POST localhost:8081/activity-marketing/create -H 'X-Tenant-Id: acme' -H 'Content-Type: application/json' -d '{...}'
curl localhost:8081/activity-marketing/list -H 'X-Tenant-Id: acme'    # 含刚建的
curl localhost:8081/activity-marketing/list -H 'X-Tenant-Id: globex'  # []
```

## 来源字段映射（收敛，非 1:1）

| 当前平台实现 | 来源（mall-shop / mall-common） | 取舍 |
| ---- | ---- | ---- |
| `ActivityManageEntity` (activity_manage) | `ActivityAdminPlatformManage` | 去掉合伙人/审核/权益系统 id 等 |
| `ActivityRuleEntity` (activity_rule) | `ActivityDynamicRules` | 保留红包/阶梯字段；本仓库新增 `red_package_max_discount`（折扣型封顶），`red_package_amount_unit` 由自由文本收成受控判别位 |
| `ActivitySpuBindingEntity` | `ActivityAdminStoreSpuProduct` | 保留 bindSource/effective/poolId |
| `ActivityConditionEntity` | `ActivityRuleExpression` + 条件树 | 存条件树 JSON + 翻译后 DRL |
| `ActivityStrategyEntity` | `ActivityRuleStrategy` | bizLine 级合并策略 |
| `ActivityGiftEntity` | `BuyAndGetConfig.GiftConfig`（来源存 extraData JSON） | 拆成结构化行；加价购复用该表，`absoluteAmount` 改读作**加价金额** |
| `ProductPool*/PoolRef` | `ActivityProductPool(Item/Rule)/ActivityPoolRef` | 圈选维度简化为 价格/类目/标签 |
| `CatalogProductEntity` (catalog_product) | 真实商品/车辆表 | 平台内商品目录；正式接入主数据后可替换为同步表或查询适配器 |
| `RuleSchemaRegistry` + `SchemaField` | `activity_rule_field_dict` 表 | 内置白名单（原 `RuleField` 枚举），按 (tenant,bizLine) 解析、单租户 stub |

facts（`ActivityCandidate/ActivityRuleContext/ActivityRuleResult/GiftResult`）与来源 `engine/fact/*` 对齐。

**决策入参（`SpuDiscountRequest`）新增两个字段，都是纯增量**（老调用方走兼容构造，行为一个字节不变）：

- `storeId`——条件白名单里一直有「店铺」，但决策入参没有这个键，于是**配了 `storeId` 条件的活动永远不命中**，且因为 fail-closed 是「静默不发」而不是报错。写侧其实完整建模了店铺（`CatalogProductEntity` / `ActivitySpuBindingEntity` / 编辑器的「店铺ID」列），只有入参漏了，故补入参而不是删白名单。语义是「这一单来自哪个门店」，不是「活动绑在哪个店」。
- `lines: [{spuId, unitPrice, quantity}]`——「第 N 件折」必需的逐行单价。整单金额 ÷ 件数是均价，拿均价当第二件的价去打折，在混着贵重与便宜商品的车里会**静默算错钱**。不传 `lines` 时该形态返回 null（不适用），而不是拿均价瞎算。按行不按件：算「第 N 件」只需 (单价, 件数)。

请求维度 → 属性袋的映射收敛成**一张表** `DecisionEligibilityService.requestAttributes()`；`ActivityQueryService` 的兼容入口只委托给它。红包、买赠、加价购都复用这份上下文与候选淘汰，并由 `DecisionContextFieldsTest` 钉死不变量「白名单里的每个 key 都必须在这里有来源」——此前是手写 `putAttr` 与 `RuleSchemaRegistry` 白名单两处独立维护，两个方向都漏过。当前白名单 6 个字段：`orderAmount` / `quantity` / `userDistrictId` / `userTags` / `spuId` / `storeId`；`userId`、`orderLines` 与 `randomSeedSpu` 三个入袋但**不进白名单**（运营写不出、也不该能写「第 3 行单价 > 100」或「随机种子 = X」这种条件）。`randomSeedSpu` 是随机红包种子专用的那个标量（= `spuIdList` 第一件），见上「随机红包是确定性随机」。

> **被 Java 硬引用的那几个 key 现在有常量出处** `DecisionAttrs`（`orderAmount` / `spuId` / `userId` / `randomSeedSpu` / `orderLines`）。属性袋的键分两类：运营可配的条件字段权威在 `RuleSchemaRegistry` 白名单，写侧改名会被 `DecisionContextFieldsTest` 当场照出来；而**代码自己读**的那几个此前是写侧（`requestAttributes`）与读侧（`ActivityRuleContext` 便捷访问器 / `BenefitEvaluator` 的随机指纹 / `ActivityQueryService` 的阶梯字段）各写一遍字面量，中间没有任何编译期或测试期的联结。<br>⚠️ 常量化**不替代**测试守卫：`DecisionContextFieldsTest` 里那条键集合断言写的是**字面量、不引用 `DecisionAttrs`**——那条才是真正的守卫，常量类改名它照样红。<br>⚠️ `spuId`（ARRAY，整车 SPU 列表）与 `randomSeedSpu`（标量）是**两个不同的键，不要合并——合并即全量随机红包重抽**。

## 投放地域（`activityAreaType` + `districtIds`）

控制台「新建/编辑活动」的**地域类型**选「指定地域」后，用级联多选选择器挑省 / 地市 / 区县；
取值域来自 `sys_district`（3212 行，见 [`architecture.md` §7](architecture.md)），读口是
`GET /activity-marketing/districts`（缺省全量，可 `?level=` / `?parent=`）。支持中文、简称、拼音、首字母搜索。

```bash
# 字典（前端一次拉全量，本地建索引做级联与搜索）
curl -s localhost:8081/activity-marketing/districts | head -c 200
# → [{"code":"110000","name":"北京市","shortName":"北京","level":1,"parent":null,"pinyin":"beijing","pinyinInitial":"b"},…

# 建一个只投广东的活动
curl -XPOST localhost:8081/activity-marketing/create -H 'Content-Type: application/json' -d '{
  "activityName":"广东专享","bizLine":"mall","activityType":1,
  "activityStartTime":…,"activityEndTime":…,
  "activityAreaType":2,"districtIds":"440000",
  "redPackageAmount":50,"redPackageAmountUnit":"元","spuBindings":[{"bindType":1,"spuId":9001}]}'
```

**它是怎么生效的**（2026-08 之前它不生效，见下）：保存时写平面把选中地域展开成「**自身 + 全部后代**」的
代码集合，合成一条 `userDistrictId IN (...)` 叶子，与运营自己的资格条件树做 AND，落进同一份
`condition_tree_json`。也就是说它**复用**了本来就唯一生效的那条地域链路（`RuleSchemaRegistry` 白名单字段
`userDistrictId` + `ConditionTreeEvaluator`），**决策侧一行未改**。决策请求里带 `userDistrictId` 即可命中：

- 展开含各级祖先自身，所以调用方送**省级码**（`440000`）还是**区县码**（`440305`）都能命中。
  只展开到叶子的话前者一律不中，而那种失败方式是「少发钱」，最难被发现。
- 注入的节点带 `source="district"` 标记，编辑器回读时剥掉、后端下次合成前也先剥掉。
  不剥的话每保存一次叶子翻倍、树深 +1，很快撞上 `RuleConditionTranslator.MAX_DEPTH=5`。

**四条边界**：

1. **只在保存时翻译**。绕过控制台直接改库里的 `district_ids` 不会重译——一次性翻译，不是活绑定。
2. **`districtIds` 只存所选层级的码**（选广东就是 `440000` 一个码）。它是 `varchar(1024)`，
   一个广东展开后是 144 个码 / 1007 字符，一个省就几乎吃满整列、选两个必爆；
   展开只发生在 `condition_tree_json`（`text`，64 KB）那一份。
3. **最多 146 个码**（6 位 + 逗号 = 7 字符）。前端选满即禁选，后端 `validateCommon` 前置校验返回 400
   ——此前超限会掉进为 `requestId` 唯一约束写的 catch，报成 **500**，让调用方去无限重试一个永不成功的请求。
4. **租户装了不含 `userDistrictId` 的自定义 schema 时跳过注入**（打 warn），而不是把每一次「指定地域」的
   保存都变成一个莫名其妙的 400。这种情况下地域退回声明式。

> **历史**：`activityAreaType` / `districtIds` 曾是一对**假开关**——能编辑、能落库、能进候选和快照，
> 但 `service/` / `engine/` / `snapshot/` 三个包对这两个字段名 grep 为空，**零读取点**。
> 运营配了地域，活动照样全国发钱，详情页还把它当生效配置回显（审计 `activity-chain-review-0811-1730/REVIEW.md`
> 编号 B2）。对照组是库存：同样是声明式，但它三处都标了「声明式」。地域一处都没有——
> 这正是**沉默比不做更危险**的样本。

## 本次未迁移（来源存在）

砍价/拼团/门店拼团/抽奖等其它玩法、CPS 订单分润、红包合伙人签名校验、真实商品/权益/钉钉集成、活动版本历史浏览/回滚。`COUPONS/CPS/RIGHT_COUPON` 三类活动类型保留枚举位但未实现（后端 400、前端禁用）。

## 已知落差（配置得下 ≠ 会执行）

这些不是"待办清单"，是**当前代码的真实边界**，写在这里是因为沉默最危险——运营以为配了就生效：

- **验证/决策报价不占库存**：`inventory` / `userInventory` 虽然进了 `ActivityCandidate`，discount、gifts、加价购 options/quote 与秒杀试算都不扣减，不构成超发防护。写平面的 `/{id}/claim` 仍是 `inventory` 唯一会被真正扣减的地方——但它现在**幂等**（`activity_grant` 流水 + `tenant+order_id+activity_id` 唯一约束），且 `userInventory`（每人限领）**已有执行路径**（按流水计数；配了限领却不传 `userId` 一律拒绝）。冲正走 `POST /{id}/release`（幂等，把库存与限领额度一起还回去）。create 的 `warnings[]` 会把 `inventory` 这条明说。
- **旧红包 DRL 不是生产回退**：它自身不支持一口价 / 第 N 件折 / 随机红包，所以六形态已固定由 `BenefitEvaluator` 求值。两个 `java-*` 旧属性**连绑定点都已删除**（配了不生效），不存在把生产切回 DRL 的开关；灰度时只能使用明确支持当前六形态与合并策略的路径。
- ~~**`STACK` 下多张折扣券会累加，可能超过订单金额**~~ **已修**：出口统一 `hitAmount = min(hitAmount, orderAmount)`，被截断时 `clamped=true` 并计 `activity.decision.clamped`。逐活动明细（`items[]`）仍如实报<b>封顶前</b>各自的金额——封顶是订单级的，不改写各活动自己算出的减免。订单金额缺省时不封顶（红包面额本就与订单金额无关），这是有意保留的边界。
- **同时配了阶梯与「固定金额（元）」的历史脏数据**：`computeAmounts` 的覆盖语义（阶梯只设 `computedAmount` **不设** `amountComputed`，随后被固定金额 `setComputedAmount(redPackageAmount)` **覆盖**）原样保留。看着像 bug，但它是当前线上语义、金标用例依赖它，改它要单独立项。<br>⚠️ 打架的是**阶梯 vs 固定金额**，不是「阶梯 vs 折扣」——折扣型（单位=折）在写平面就被禁止同时配阶梯，根本走不到这里。<br>⚠️ 也**没有「两条路对拍」可做了**：算额的第二条路（DRL）已删，现在只剩 `BenefitEvaluator` 一条，守它的是 `DecisionGoldenSetTest` 的 52 例金标。
- **权益作用域的两处已知落差**：① 作用域覆盖整单时用 `orderAmount`、是真子集时用 Σ作用域订单行，两者口径可能不同（运费/补贴算不算进 `orderAmount`，入参契约没规定）；② 调用方若只传「想查的那件」的 `spuIdList` 却给整单 `orderAmount`，覆盖判定会返回 true，商品级活动重新退化成按整单算钱且**无任何报错**。收敛方向是在入参契约里要求订单行或写死 `orderAmount` 口径。
- **`spuId` 资格条件已放宽**：字段类型由 NUMBER 改 ARRAY，属性袋装整个 `spuIdList`（此前是 `get(0)`＝「购物车第一件」，同样两件商品换个加购顺序结论就相反）。存量 `eq/in/notIn` 由求值器与翻译器映射成集合语义，**无需数据迁移**；代价是多 SPU 请求下「第一件是 X」放宽成「购物车含 X」，更容易命中——但同批改动里作用域把「命中后发多少」收窄到了活动自己的商品上，合起来不是放大敞口。
- ~~**决策快照的回滚只有原语、没有入口**~~ **已补**：`POST /decision/v1/snapshot/rollback?bizLine=`（2026-08）。但它**只留一代、只影响被打到的那个实例、下一次代际推进就会覆盖它**——是止血不是修复，真正的修复仍在 console 侧下线/改配置再发布一代。`refresh`（兜底重建）按设计不占回滚槽位，同代重发也不占，所以「刚重启只发过一代」「上一次推进是兜底重建」这两种情况调它会拿到 409。**活动本身的版本历史浏览/回滚仍未迁移**（见上节）。
- **验证流量会污染业务指标**：控制台「优惠验证」页默认打决策平面，而 `/decision/v1/*` 与生产流量走**同一份** `ActivityQueryService`——每点一次验证就真的记一次 `activity.decision.hit`（红包通道另记一次 `activity.decision.amount`；加价购的 quote 自 2026-08 补齐埋点后也开始计 hit）。**这两个指标只能读作「决策发生了多少次 / 报了多少钱」，不能读作「花了多少预算」**：控制台若把 `/by-activity` 的 `amounts` 渲染成预算消耗，那是拿自己造的流量记账（真正的账在 `activity_grant` 里，那张表只有 `claim` 才写）。e2e 脚本同理。<br>唯一不脏指标的诊断入口是 `GET /decision/v1/snapshot`（只读、不发起决策）。
- **`activityId` 标签位是 200 个、跨 scene 共享且不可回收**：`DecisionMetrics.cappedTag` 用一个只增不减的 `taggedActivities` 集合做先到先得，进程生命周期内**没有任何淘汰**。后果是 e2e / 验证页反复跑造出的临时活动会**永久**占住标签位，等真实活动上线时只能落进 `__over_cap__`——总量仍准，但「按活动看命中量」这块面板对新活动一片空白。重启 decision 才会清零。2026-08 加价购补齐埋点后也开始占这份预算，活动数量级接近 200 时需要重新评估这个上限。
- **双打对拍只能照出取数层的分歧**：验证页的「两条平面都打」比的是候选集、版本、绑定、代际这类**取数**差异；两条路共用同一份 `BenefitEvaluator`，**求值口径错了两边会一起错**——绿 ≠ 算对了。对拍还刻意排除五类正常差异（`decisionId`、`traces`（两侧 `DecisionMode` 档位不同：console 别名是 `EXPLAIN`、decision 热路径是 `HOT_PATH`）、`mode`、`items` 顺序、`strategy`（合法瞬态：策略行在 create 时 upsert，而代际只在状态流转时推进）），并在**两侧 source 都是 snapshot 时判红**——那说明有人给 console 也加了预热，对拍已经退化成「拿快照跟它自己比」，而永久绿比飘红更彻底地骗人。
