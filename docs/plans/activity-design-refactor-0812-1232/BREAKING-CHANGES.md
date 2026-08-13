# 活动引擎重构 · 对外可见变更清单

> 分支 `refactor/activity-design`（8 个提交）· 生成于 2026-08-12
> 配套：[`FINAL_PLAN.md`](FINAL_PLAN.md)（方案）· [`AUDIT-FINDINGS.md`](AUDIT-FINDINGS.md)（原始审查）

这次重构的**总体目标是行为等价**，金标集 52 例、`SnapshotParityTest`、`DecisionQueryCountTest` 全程守着。
下面列的是**有意突破等价**的部分——它们不是重构副作用，是被明确决定要改的东西。
每一条都写清：改了什么、为什么、谁会被影响、要做什么。

**发钱金额没有任何变化。** 这份清单里没有一条会改变任何一次决策的减免额、命中活动或候选淘汰结论。

---

## 一、HTTP 状态码（4 处）· 影响调用方的重试逻辑

### 1. 四眼校验失败：409 → **403**

四眼拒绝是「你没有这个权限发布」，不是「资源状态冲突」。它此前是 409 纯属**实现细节泄漏**——
写平面用 `IllegalStateException` 表达它，而 controller 把所有 ISE 一律映射成 409。
现在它由 `ActivityErrorCode.FOUR_EYES_REQUIRED` 决定状态码，与「抛的是哪个 JDK 异常」脱钩。

| | 改前 | 改后 |
| --- | --- | --- |
| 提交人自审发布 | 409 | **403** |

- **响应体形状未变**：仍有 `error` 字段（原样的中文说明），另**新增** `code` 字段（`FOUR_EYES_REQUIRED`）。按 `error` 取值的客户端不受影响。
- **已同步**：`frontend/e2e/e2e-validation.mjs` 里那条断言 409 的用例已改成 403。
  ⚠️ 这条 e2e **不在 `./mvnw test` 与 `vitest` 的闸门里**，所以整轮重构没有照出它——它是人工核对时发现的。

### 2. `claim` 失败：恒 409 → 按失败种类分流

| 失败种类 | 改前 | 改后 | 举例 |
| --- | --- | --- | --- |
| `BAD_REQUEST` | 409 | **400** | 缺 `activityId`、数量非正、限领活动没带 `userId` |
| `NOT_FOUND` | 409 | **404** | 活动不存在、版本不存在 |
| `OUT_OF_STOCK` | 409 | 409 | 余量不足、不在可用窗口 |
| `PER_USER_LIMIT` | 409 | 409 | 超出每人限领 |

**为什么必须改**：这四种失败此前压在同一个码里，只能靠中文 `reason` 串区分。
409 的标准语义是「重试可能成功」——**下游按 409 写重试逻辑的话，「参数写错」这一类会被无限重试到活动结束**。

- **响应体一字节未变**：`FailureKind` 标了 `@JsonIgnore`，只用于服务端分流。`ok` / `reason` 原样保留。
- 映射由 `ActivityMarketingController.claimStatus` 的 **switch 表达式且不写 `default`** 实现——
  新增一种失败种类而漏了映射就是编译失败。
- 兼容：旧的三参 `ClaimResult` 构造器（未标注种类）沿用历史行为 **409**，没被覆盖到的老路径不会漂。

### 3. `release` 缺参：404 → **400**

`orderId` 传空串时此前落进「没有对应发放记录」的 404 分支。现在缺参/空串是 400，**确实查不到发放记录**才是 404。
（完全不传 `orderId` 一直是 400 —— 它是 `@RequestParam(required=true)`，Spring 直接挡。）

**为什么要改**：404 会让调用方以为「这一单没领过、不用冲正」，从而**放弃冲正**——库存与限领额度就永久漏掉了。

### 4. `bulk-status` 的 `targetStatus` 非法：200 + 逐条回执 → **400**

部分失败仍然是 200 + `failed[]` 回执（这条没变，它是这个接口的正确语义）。
但 `targetStatus` **本身**非法时现在进循环之前就 400——否则几十条各失败一次，回执里全是同一句话。

⚠️ `frontend/src/shared/types.ts` 里那句注释「bulk-status 回执。部分失败是正常结果（HTTP 恒 200）」
现在**不再完全准确**，需要补一句「除非 targetStatus 本身非法」。前端当前不可触发（`askBulk` 只传 `1|2`）。

---

## 二、写平面新增的拒绝（1 处）

**`targetStatus=3`（待生效）现在被写入口拒绝。**

`ActivityStatus.PENDING_EFFECT(3)` 是 `fromCode` 认可的合法码，但全 main 源码**零生产者、零消费者**：
置成该状态的活动代际照常 bump，但它**永远进不了任何读路径**——控制台显示成草稿，决策永远不命中。
与其给一个没实现的状态立法，不如在写入口封口。

`changeStatus` 同时加了**迁移表**（from × to），非法流转直接拒。今天所有合法流转都已放行，
包括原先那个不对称的 `OFFLINE → ONLINE`（编辑 OFFLINE 被拒、上线却放行）——**原样保留，只是让它显式**。

> **已知边界**：`activity_status` 落在 0–3 之外或为 null 的脏数据行，现在会在状态流转时硬失败
> （单条 400 / 批量计入 `failed[]`），而改造前可以被改成任意状态。本仓库只写 0–3 且该列 `nullable=false`，
> 所以只在外部导入/手工 SQL 造出的脏数据上可达。

---

## 三、指标标签（1 处）· 需要 PromQL 侧留意

**`activity_decision_source_total` 的 `scene` 标签取值改了。**

| | 改前 | 改后 |
| --- | --- | --- |
| 红包 | `RED_PACKAGE` | `spu-discount` |
| 买赠 | `BUY_AND_GET` | `gifts` |
| 加价购 | `ADD_ON_PURCHASE` | `addon` |

**为什么必须改**：这条指标此前用的是 `ActivityType.name()`，而本类其它九个指标用的是 `DecisionScene` 词汇表。
后果是 `activity_decision_source_total{scene="gifts"}` 查出来**恒为空**——
而「按 scene 把回退率与来源占比 join 起来看」正是这条指标存在的理由。**它此前一 join 就空，且空得毫无提示。**

**已核对：`deploy/` 下没有任何消费者需要同步改**——

- `deploy/grafana/dashboards/activity-services.json` 只查 JVM 与 HTTP 指标，不碰 `activity_decision_*`
- `deploy/prometheus/prometheus.yml` 没有 `rule_files`，因此没有告警规则引用它

影响面只剩**手写的临时查询**：旧的三条时间序列会停止增长、三条新序列开始增长，历史数据仍在旧标签下可查。
如果你有个人保存的看板/查询，改一下标签值即可。

---

## 四、响应体新增字段（纯附加，无需下游动作）

**`GET /activity-marketing/{id}` 的详情响应新增 `servingVersion`。**

含义是「当前正在发钱的那一版」（最高 ONLINE 版，没有上线版本时为 `null`）。
它存在的理由：详情返回的 `manage` 是**最高未删除版**——P0-4 之后线上版与草稿并存，所以那通常是**草稿**。
编辑器拿它当编辑基线是对的（编辑就该编草稿），但「你看的这一版可能不是正在服务的那一版」
此前只能靠调用方拿列表行自己比对（前端 `ListView.vue` 的 `versionMismatch` 就是这么算的）。

纯附加字段，不影响任何既有解析。**当前零消费者**——前端还在用自算的方式。
这本身值得记一笔：它正是这轮重构在清理的那种「加了但没人用」的模式，
写进文档是为了让它不至于变成下一个空头支票。

---

## 五、响应体键顺序（已修回，无需下游动作）

R13 把 `tenantId` / `isDel` / 双时间戳收进 `@MappedSuperclass` 之后，Jackson 默认把**超类属性排在子类之前**，
于是 `/activity-marketing/{list,detail,grants}` 的每个实体对象从 `{"id":…,"activityId":…}`
变成了 `{"tenantId":…,"createdStime":…,…}`。字段名与取值一个字节没变，前端按键取值也不受影响——
但那仍是响应体的一次**静默**改变，对响应做 hash / ETag / 快照比对的东西会飘。

**已修回**：`TenantScopedEntity` 上一个 `@JsonPropertyOrder({"id","activityId","version"})` 把身份字段提回队首，
一处注解覆盖全部十个子类。`EntityJsonOrderTest` 钉住这个顺序——下次有人动继承结构，它会**响亮地失败**。

---

## 六、观测口径的两处变化（不影响正确性，影响读数）

### 1. 买赠回退分支：「零赠品的合格候选」不再计命中

`activity.decision.hit{scene="gifts"}` 的口径从「资格通过的候选」改成「实际出了赠品的活动（去重）」。
引擎分支本来就等价（`buildGiftDrl` 的 LHS 要求 `gifts.size()>0`），**变的是回退分支**：
一个「资格通过但一件赠品都没配」的活动，回退时的命中量会从 1 掉到 0。

看板上读起来像「回退后这个活动突然不命中了」，实际是口径修正——它本来就没发出任何东西。

### 2. 加价购活动开始占用 `activityId` 标签位预算

`DecisionMetrics.ACTIVITY_TAG_CAP = 200` 是一个**跨 scene 共享**的全局预算。
加价购此前一个标签位都不占（它压根没埋点），现在补齐埋点后会与红包/买赠抢同一份 200 个 activityId 的额度。

总量仍准（超出部分并入 `__over_cap__` 哨兵），但**「按活动看命中量/金额」的分辨率会在活动目录变大时提前塌掉**。
活动数量级接近 200 时需要重新评估这个上限。

---

## 附：这轮为什么值得信

- **6 个批次逐批提交、逐批过全量测试闸门**，一次都没红（`fixRounds: 0`）
- 每批之后有一次**独立的对抗式等价审查**（读 `git diff`，立场是「假设它悄悄改了钱，去证明它」）
- 审查判了 4 次 `EQUIVALENT`、1 次 `SUSPECT`；**SUSPECT 的两条都属实并已修复**（提交 `6ed0e77`）：
  - 快照桶归属被下推成 SQL 相等，生产 MySQL 的大小写不敏感排序规则会让 `Retail` 的活动漏进 `retail` 桶——
    **改变了谁能被发钱**，且因为测试跑在 H2 上（大小写敏感）而照不出来
  - console 的 `ISE → 409` 兜底圈住了整个 controller 包，把 `list`/`grants` 等端点上的内部 bug 伪装成客户端错误
- 三条最关键的不变量由我人工读 diff 确认：随机红包种子链一字未改、形态分派是**无 `default` 的 switch 表达式**
  （漏分支即编译失败）、走库与快照共用 `OfferSpec.from` 单一装配入口

测试：后端 **476** 通过（common 193/3 skipped、console 256、decision 27，基线 430），前端 **285** 通过（基线 283）。
增量全部来自本轮新增的**结构性护栏**用例（`SnapshotBizLineCollationTest` / `EntityJsonOrderTest` /
`OfferSpecArchGuardTest` / `DecisionReadRepositoryGuardTest` / `ConditionTreeGuardTest` …），不是新功能。
