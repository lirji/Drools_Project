# 技术点清单

> 把这个仓库里**值得讲**的技术点成体系列出来：每条给「解决什么问题 → 怎么做的 → 代码在哪 → 非显然之处」。
> 面试、答辩、给新同事做 onboarding 都能直接用。
>
> 架构全貌看 [`architecture.md`](architecture.md)；引擎选型的量化依据看 [`capacity-model.md`](capacity-model.md)；
> Drools 本体知识（原理 / 能力全景 / 与 QLExpress 对照）看 [`drools-capabilities.md`](drools-capabilities.md)、
> [`rete-intuition.md`](rete-intuition.md)、[`interview/qlexpress-vs-drools.md`](interview/qlexpress-vs-drools.md)。

**怎么用这份清单**：每条末尾的「可被追问」是这个技术点真正的深度所在——
能答上来说明是自己做的，答不上来说明只是抄了个结论。

---

## 一、规则引擎选型：分层决策

### 1.1 用一句话判据把 80% 的规则移出规则引擎

**问题**：一上来"营销活动 = 上 Drools"是条件反射，但规则引擎的成本不是免费的。

**做法**：定一条可执行的判据——**「这条规则需不需要*其它规则的结论*」**。

| 计算 | 数学本质 | 需要其它规则的结论吗 | 归属 |
| --- | --- | --- | --- |
| 阶梯落档 | 标量分段函数 | 否 | 纯 Java |
| 折扣合并 | 一次 reduce | 否 | 纯 Java |
| 资格条件树 | 单事实布尔谓词 | 否 | 纯 Java |
| 买赠聚合 | 跨候选收集 | 是（保留） | Drools |

**代码**：`activity-common/.../engine/{BenefitEvaluator,BenefitMath,ConditionTreeEvaluator}.java`

**非显然之处**：这个判据的价值在于它**可证伪**——不是"感觉规则引擎太重"，而是给出一个任何人都能对着规则回答的问题。
实测代价见 §1.2。

**可被追问**：那什么时候该上 Drools？答：级联推理、TMS（`insertLogical` 自动撤销）、
事实被 `modify` 后重新匹配、CEP 滑窗、以及"几百条规则互相引用"的保险核保 / 信贷授信类场景。

### 1.2 给选型配一个价签（实测，不是感觉）

**问题**：选型讨论最后往往变成审美之争，因为没人给出数字。

**做法**：写一个三引擎同负载的基准（`examples/capacity/CapacityBench.java`），
Drools / QLExpress / 纯 Java 跑**同一份**活动负载，测常驻足迹、编译耗时、决策延迟。
并加一道**等价性闸门**：三条路的 checksum 必须一致，否则数字不可比。

**结果**（每活动 1 条 3 叶资格 + 10 档阶梯）：

| | 纯 Java | QLExpress | Drools |
| --- | ---: | ---: | ---: |
| 每活动常驻 | 1.8 KB | 8.4 KB | **~180 KB** |
| 每候选决策 | 0.18 µs | 2.9 µs | **~12 µs** |
| 1 GB 能挂 | ~58 万 | ~12 万 | **~5,800** |

**非显然之处**：等价性闸门是整个基准唯一的正确性保证——没有它，"A 比 B 快 10 倍"
完全可能只是因为 A 少算了一半的活。另外这份基准还**证伪**了两个我自己的猜测
（alpha 节点复用不影响 Drools 足迹；QLExpress 足迹并非弱依赖档位数），
这两条负面结论保留在文档里，因为它们能挡住下一个人去做无用的优化。

**可被追问**：为什么 Drools 每活动 180 KB？答：每档一条独立 `rule`，每条规则编译成类落 **Metaspace**，
每个 KieBase 自带 classloader。所以"档位数"比"活动数"更值得在运营侧收紧——
10 个活动各配 200 档，和 2000 个活动各配 1 档，对 Drools 是同一个价钱。

### 1.3 手里已经有 AST 就别再引表达式引擎

**问题**：计划里原本要"引入 QLExpress 做资格判定"。

**做法**：查证后发现 `activity_condition.condition_tree_json` **已经存了结构化条件树**——
手里本来就有 AST。把 AST 编译成字符串、再引一个引擎去解析那个字符串是绕远路：
多一个运行时依赖、多一层「树 → 串」翻译（每加一个算子要维护两处）、多一个转义面。
于是直接写树解释器。

**代码**：`ConditionTreeEvaluator.java`（类注释里完整记录了这个决策）

**非显然之处**：表达式引擎的价值在**「让非开发者写逻辑」**——而这里运营写的是条件树、不是表达式，
那份价值在此处不存在。绕这一圈的实测成本是 4.7× 内存 + 16× 决策 CPU，换回来的是零。

---

## 二、金额正确性工程

发错钱是这个系统最贵的 bug，所以这一节的每条都是"宁可不发，不可超发"。

### 2.1 取整只能有一份权威

**问题**：同一张券在两条代码路径上少发 / 多发几分钱——这类 bug 极难发现，因为单测各自都绿。

**做法**：所有取整收敛到 `BenefitMath` 的静态方法，一律 `RoundingMode.DOWN` + scale 2。

**非显然之处**：**四舍五入会系统性多发**（期望值偏正）。而各写一遍必然漂移——
"两条路共用同一个取整函数"这个约束比"两条路都记得用 DOWN"强得多。

**代码**：`BenefitMath.java` / 回归锁 `BenefitMathTest`（21 例）

### 2.2 fail-closed：算不出来就淘汰，不是发 0 元

**问题**：候选算不出金额时留一个 0 元候选，会**挤掉真优惠**（合并时它也参与竞争）。

**做法**：`notApplicable(...)` 直接 `candidate.reject(...)`。

**非显然之处**：**判据不能用金额**——首档 reward=0 是运营配得出来的合法 0 元优惠。
用金额判别会误杀，所以引入 `ladderApplied` 这个**落档留痕**布尔位来区分
「阶梯落档发 0 元」与「阶梯根本没落档」。

同类的 fail-closed 还有：
- 条件树的否定算子（`NE` / `NOT_IN` / `NOT_CONTAINS`）都带**存在性护栏**——
  字段缺失时结果为 **false**（淘汰），而不是"没有这个字段所以不等于、算通过"；
- 字段不在 schema 白名单里 → 直接 false；
- 第 N 件折缺 `lines`（订单行）→ 不适用，**不拿均价凑**。

**代码**：`BenefitEvaluator.java` / `ConditionTreeEvaluator.java` / `NotApplicableCandidateTest`（14 例）

### 2.3 判别顺序即语义

**问题**：六种权益形态挤在同一个 `redPackageAmount` 字段上，靠 `red_package_amount_unit` 判别。
判别分支的**顺序**直接决定发多少钱。

**三条必须记住的顺序**：

1. **形态判别排在 `takeType` 之前**——否则 API 手造的「折 + takeType=2」会被抢进随机分支，永远走不到折扣计算；
2. **随机分支排在 `redPackageAmount==null` 的 guard 之前**——随机金额来自 `redPackageRangeAmount`；
3. **未知单位回落金额型**——脏数据表现为"按旧行为发"，而不是"按猜出来的形态发"。

**非显然之处**：`redPackageRangeAmount` 是**双用途列**，靠 JSON 顶层类型互斥
（数组→阶梯，对象→随机区间）。写成 `[{"min":5,"max":20}]` 会被阶梯路径认领。

**代码**：`BenefitEvaluator.computeAmounts` / `LadderRangeParser` / `RandomRangeParser` /
金标集 `DecisionGoldenSetTest`（52 例）

### 2.4 确定性随机：可刷新、可重放、可对账

**问题**：随机红包如果用 `Random`，用户刷新页面就变价，且事后无法对账。

**做法**：金额由 **SHA-256 派生**——种子 = `activityId | version | userId | 购物车指纹`，
指纹 = `canonical(orderAmount) | canonical(quantity) | randomSeedSpu`。同一用户同样的车稳定拿同一个数。

**非显然之处**：入参**没有订单号**，所以"同一个车"就是幂等键；
换 SHA-256 算法等于**改所有历史金额**，代码里明确禁止静默换算法。

指纹的两个细节都是"改一个字节就重抽全量"级别的：

- **数值段必须规范化**（`stripTrailingZeros().toPlainString()`）。此前直接 `toString()`，
  于是客户端把订单金额写成 `100` 还是 `100.00` 会得到**两个种子、两个金额**——
  一个纯格式差异就能让用户看到价格跳动，而"刷新不变价"正是这套机制存在的全部理由。
- **SPU 段读 `randomSeedSpu` 而不是 `spuId`**。作用域改造把 `spuId` 从"购物车第一件"改成了整个列表，
  它的 `toString()` 从 `990011` 变成 `[990011]`——指纹一变，**全量随机红包一次性重抽**：
  用户刷新就变价、历史对账全部对不上。`randomSeedSpu` 由 `DecisionEligibilityService` 专门维持成旧的"第一件"值，
  不进条件白名单、不许被任何条件引用，唯一职责就是把这条种子链钉住。

**代码**：`BenefitEvaluator.drawRandom` + `BenefitMath.randomSeedKey`

### 2.5 权益作用域：绑定表不只是"候选筛选器"

**问题**：算钱一律用 `ctx.getOrderAmount()`，于是「活动绑了哪些 SPU」只被当成**候选筛选器**用。
一个只绑了 B 的「9.9 一口价」，在「A 5000 元 + B」的车里算成 `5009.9 − 9.9`——**整车按 9.9 成交**。

**做法**：把判据从"订单一共多少钱"换成"**本活动的商品**一共多少钱"，
`baseAmount(ctx, candidate)` 三档判定，**顺序不能反**：

| 档 | 条件 | 基数 |
| --- | --- | --- |
| ① 作用域未知 | `scopedSpuIds == null` | 整单（兼容承诺：手工候选 / 未接作用域的装配路径行为逐字节不变） |
| ② 作用域覆盖本次请求全部 SPU | `scope.containsAll(requested)` | 整单（"整单"与"本活动的商品"本就是同一批东西） |
| ③ 作用域是真子集 | 其余 | 作用域行小计；**拿不到订单行就返回 null 让调用方淘汰**，绝不用整单顶替 |

**非显然之处**：

- 第 ② 档不能一起 fail-closed。**今天绝大多数流量落在这一档**（单 SPU 查询、全场券），
  把它一起关掉是"修一个多发的 bug、换来一个全线不发的 bug"。
- 作用域是**零额外查询**算出来的：数据全部来自取数第 ① 步已经查回的绑定行，按「当前线上版本」内存内连接聚合。
  为了拿"活动的全部绑定"再查一次绑定表会当场破掉固定 5 次查询（`DecisionQueryCountTest` 会抓住），
  而作用域要的本来就是交集、不是全集。
- **绑定按版本配对顺带修掉一条真 bug**：绑定查询不带 version、旧版本绑定行也不软删，
  所以「v1 绑 A/B → 编辑成 v2 只绑 A」之后单查 B，走库路径仍把这个活动当候选（只是作用域为空）。
  而空作用域**拦不住 AMOUNT 形态**——直减/满减分支根本不调 `baseAmount`，直接把 `redPackageAmount` 发出去。
  于是走库照发 50 元、走快照根本不是候选（快照侧按 `(activityId, version)` 取绑定），**两条路发不同的钱**。
  现在判据与快照侧对齐：「当前线上版本的绑定 ∩ 请求 SPU 为空 ⇒ 不是候选」。
- **已知落差**：第 ② 档用 `orderAmount`、第 ③ 档用 `Σ 作用域行`，两者口径可能不同
  （运费 / 平台补贴 / 已减金额算不算进 `orderAmount`，入参契约里没规定）。
  收敛方向是在契约上写死，而不是在求值器里猜。

**代码**：`BenefitEvaluator.baseAmount` / `DecisionDataLoader.scopeOf` /
`BenefitScopeTest`（纯求值层）+ `DecisionScopeGoldenTest`（端到端两条路）+
`SnapshotParityTest#narrowedBindingStopsPayingOnBothPaths`

### 2.6 出口封顶：截断不是目的，计数才是

**问题**：合并出口只判 `hitAmount > 0`——**只有下界没有上界**。
三张「满 100 减 50」打在 120 元订单上返回 150，负的应付金额就这样交给下游订单系统；
单张也一样，50 元红包打在 30 元订单上照发 50。

**做法**：出口 `capToOrderAmount` 按订单金额封顶，并在响应里带出 `clamped=true` + 打 `activity.decision.clamped` + WARN 日志。

**非显然之处**：**截断本身几乎没有价值，计数才有**。能触发封顶的配置几乎一定是配错了
（门槛写反、面额多打一个零、几张券叠加没设上限），而这类错误在补这个指标之前
**在监控上是全盘绿灯**：回退率 0、耗时正常、命中数只是稍高。所以正常业务下这条指标应恒为 0，
告警阈值可以设得极其激进（出现一次就该看一眼）。指标**刻意不打 activityId 标签**——
是哪个活动在 WARN 日志里，指标只回答"有没有发生"，不去挤 200 个标签位。

另一条边界：**订单金额缺省时不封顶**。`AMOUNT` 型红包的面额本就与订单金额无关、不要求上游传订单金额，
此时无从判断是否超发；一律按 0 处理会把正常决策打没。要收紧应该在入参契约上要求订单金额。

**代码**：`BenefitEvaluator.capToOrderAmount` / `DecisionMetrics.clamped` / `DecisionOutputContractTest$Cap`

---

## 三、并发与一致性

### 3.1 库存扣减：一条原子 UPDATE

**问题**：`先 SELECT 判余量 → 再 UPDATE 减一` 是经典 check-then-act 竞态。
**低并发测不出、大促必现**——这是最坏的一种 bug。

**做法**：把判余量和减一压进同一条 UPDATE 的 `where` 里：

```sql
update ... set inventory = inventory - :n
 where activityId = :id and version = :v and isDel = 0
   and activityStatus = 1
   and activityStartTime <= :now and activityEndTime >= :now
   and inventory is not null and inventory >= :n
```

返回 0 = 没抢到，**调用方不能忽略返回值**。
谓词里的**状态与时间窗不是装饰**：只判 `isDel + inventory >= n` 时，
已下线、未开始、已结束、草稿版本的库存**都能被扣干净**。

**非显然之处**：这条只能在**写平面**——decision 连的是只读账号，物理上写不了库。
由此推出业务规则「**决策 ≠ 提交**」：决策只报价，`POST /{id}/claim` 才是权威扣减。
秒杀试算与加价购报价都不占库存。

**代码**：`ActivityManageRepository.decrementInventory` / `FixedPriceAndClaimTest$NoOversell`

### 3.2 claim 幂等：一张流水表解四件事

**问题**：原子 UPDATE 只防超卖，不防**重复领取**——用户连点两次就扣两次，因为没有任何东西记得"这一单领过了"。

**做法**：新增 `activity_grant` 发放流水，幂等键 = 唯一约束 `(tenant_id, order_id, activity_id)`。
claim 的顺序是：**查流水命中即返回首次结果 → 校验每人限领 → 插流水 → 原子扣减**。
（`orderId` 是幂等键的一半，**不传就退化成不幂等**——没有订单号就无从判断"是不是同一单"。
旧的三参签名保留为兼容入口，新调用方一律用五参版本。）

**非显然之处**：

- **先插流水后扣库存，顺序不能反**。让唯一约束在**任何扣减发生之前**就拦住并发的同一单重复请求；
  反过来（先扣后插）时两个并发请求会各自扣成功，再由其中一个撞约束回滚——
  回滚能救回库存，但那要靠事务边界一路不出错，而不是靠一条约束。
- **扣减失败要把刚插的流水删掉**。留着一条 `HELD` 却没有对应扣减的记录，对账上就是"有账无货"，
  而且会永久占掉这个用户的限领额度、并让这一单再也 claim 不了（幂等分支会命中它）。
  用显式删除而不是抛异常回滚整个事务，是为了保住既有契约——调用方一直按"返回 `ok=false`"处理没抢到。
- **拿不到 `userId` 时，配了每人限领的活动直接拒绝**。无从判断是不是同一个人时放行，等于限领形同虚设。
- **不传 `version` 解析成当前 ONLINE 版本**（不是最高版本）。取最高版会打到草稿——
  闸门装在了另一行数据上：线上版本的库存一件没少、草稿的库存被扣干净。
- **不复用 `activity_idempotency`**：那张表的键是客户端生成的 `requestId`，语义是"这个请求处理过没有"；
  流水的键是业务事实，语义是"这份优惠发出去没有"。混在一起的后果是换个客户端重试实现就能把同一单领两次。
- 冲正走 `POST /{id}/release`（同样幂等：已 `RELEASED` 的记录直接返回成功，不重复加库存），
  归还**不判活动状态与时间窗**——活动结束之后仍会有退款进来。

**代码**：`ActivityMarketingService.claimInventory` / `releaseGrant` / `ActivityGrantEntity` / `GrantLedgerTest`

### 3.3 上线是原子指针切换，不是"先下线再上线"

**问题**：活动改版时，如果"先把旧版下线，再把新版上线"，中间有个窗口**没有任何版本在服务**。

**做法**：上线在**同一事务**里把该活动其它 ONLINE 版本退役 + 本行置 ONLINE。
且**编辑不下线正在服务的版本**——线上版与草稿并存。

**代码**：`ActivityMarketingService.changeStatus`

### 3.4 缓存击穿：Caffeine 天然 single-flight

**问题**：同一份 DRL 并发首次编译，N 个线程各编一遍（编译是 100ms~秒级）。

**做法**：`cache.get(key, mappingFunction)` 是**原子计算**——同 key 并发只编译一次，其余等结果。
不需要自建锁。

**非显然之处**：cache key = **tenant + DRL 全文**，显式带租户维度而不是靠"DRL 因 schema 而异"隐式分片——
为 per-tenant 容量 / 淘汰 / 失效留口，且不跨租户共享 KieBase。

**代码**：`ActivityRuleRuntimeService.compileOrGet`

### 3.5 批量操作：部分失败是正常结果

**问题**：批量上下线里有一条失败，是整批回滚还是继续？

**做法**：**逐条独立处理，失败不影响已成功项**，一律返回 **200 + 部分失败回执**
（`{succeeded:[...], failed:[{activityId, reason}]}`）。

**非显然之处**：返回 200 不是"忽略错误"，是"部分失败是这个接口的正常语义"——
用 4xx/5xx 表达会让调用方无法知道**哪些成功了**。

---

## 四、发布与热更新

### 4.1 版本 → 代际 → 快照 → 预热：跨进程生效链

**问题**：console 改了活动，decision 进程怎么知道？

**做法**：四段接力——
① 上线时同事务 `GenerationService.bump` 代际 +1 →
② decision 侧 `GenerationPollScheduler` 轮询（默认 3s）→
③ 见涨则**后台线程**建 `DecisionSnapshot`（整条业务线的不可变物料），
   就绪后 `DecisionSnapshotStore.publish` **原子切指针** →
④ 再对每个 ACTIVE artifact `warmAsync` 预热 DRL 编译。

**非显然之处**：顺序是「**先建好，再切指针**」而不是「先切再建」——
后者会有一个窗口指向半成品。而 `rollback` **只保留一代**且是**一次性**的（回滚后滚不回去），
这是显式设计而不是遗漏。

**代码**：`GenerationWarmService` / `DecisionSnapshotStore` / `SnapshotParityTest`

### 4.2 编译不落热路径：独立限速编译线程池

**问题**：冷 miss 在**调用线程**同步编译，直接变成 P99 尖刺。

**做法**：预热 / 重建的编译提交到独立 `ThreadPoolExecutor`（`max = CPU/4`，有界队列 +
`CallerRunsPolicy` 兜底：队列满时退化为调用线程编译，**不丢任务、不无界堆积**）。

**非显然之处**：编译线程没有请求上下文，所以必须**显式传 tenant** 并用 `TenantContext.callWith`
对齐缓存 key——否则预热编译进的是另一个 key，热路径照样 miss。

**代码**：`ActivityRuleRuntimeService.warmAsync`

### 4.3 artifact 冻结：发布的是物料快照，不是"当前配置"

上线时把翻译好并**编译校验过**的 DRL 冻结进 `activity_artifact`。
这样"线上跑的规则"和"数据库里的当前配置"解耦——配置改了但没发布，线上不受影响。

---

## 五、多租户与安全

### 5.1 判别式隔离 + 结构守卫

**做法**：每个实体一个 `@TenantId String tenantId`，`MultiTenancyConfig` 把
`TenantIdentifierResolver` 塞进 Hibernate 的 `MULTI_TENANT_IDENTIFIER_RESOLVER`，
引擎给每条 SQL **自动追加** `tenant_id = ?`。

**非显然之处**：这套的**唯一失效方式**是绕过 Hibernate——所以
「不手写 tenant 谓词」「不用 `nativeQuery`」两条由 **`TenantArchGuardTest` 结构守卫**钉死，
而不是靠 code review 记得。

**踩过的坑**：加决策平面时漏了给 `TenantContextFilter` 扩 `/decision/v1/*` 的 URL 模式，
`X-Tenant-Id` 被**静默忽略**，全部请求落 dev-default 兜底，表现为「A 租户查到别人的活动」。
**单元测试大多跑在 dev-default 下，这类缺口只有端到端才照得出来**。

### 5.2 双档认证：dev 用 header，生产用 OIDC

| 档 | 租户来源 | 用于 |
| --- | --- | --- |
| header 档 | `X-Tenant-Id` | 本地 / e2e |
| auth 档（默认部署） | JWT 的 `aud` → 租户，`sub` → 操作者 | 生产形态 |

完整链：JWKS 验签 → `JwtTimestampValidator` + `JwtIssuerValidator` +
**`AudienceTenantValidator`（aud 必须解析到已知租户，否则 401）** →
写端点 `hasAuthority(console-write-authority)` → `JwtTenantFilter` 灌上下文。

**非显然之处**：`OutageTolerantJwks` 在 IdP 短时不可用时用 last-good JWKS 继续验签——
否则 IdP 抖一下，整个决策平面跟着挂（`LastGoodJwksTest`）。
另：`bulk-status` 是两段路径，`/activity-marketing/*/status` 这个模式**匹配不到**，授权规则必须单列。

### 5.3 「规则即数据」的注入面控制

**问题**：运营在控制台配条件，最终会变成 DRL 文本——这是一个注入面。

**三道闸**：
1. 运营只能从**注册字段** + **允许的运算符** + （ENUM）**候选值白名单**里拼条件，不能提交任意 DRL；
2. 所有拼进 DRL 的标识符（`activityId` / `ladderField`）过 `^[A-Za-z0-9_]+$` 白名单，**非法即抛**，不静默拼接；
3. 创建期先**试编译**，编译失败什么都不落库。

**代码**：`RuleSchemaRegistry` / `RuleConditionTranslator` / `ActivityDrlBuilder.safeId`

### 5.4 四眼原则

上线时校验 `ActorContext ≠ submitted_by`——提交人不能自己发布（可开关）。
`ActivityFourEyesTest` + e2e `e2e:validate` 覆盖（脚本会先验证提交人自审被拒、再由另一 actor 发布）。

---

## 六、性能与容量

### 6.1 把 3N+2 次查询压成固定 5 次

**问题**：取数原本是 `3N+2` 次查询（N = 候选活动数）——典型 N+1。

**做法**：全部改批量（`findBy...In`），压成**固定 5 次**，与候选数无关。

**非显然之处**：其中一步的**顺序不能反**——`activity_manage` 批量查回来后，
必须**先筛 ONLINE 再取最高版本**；反过来会取到一个更高版本的草稿然后发现它没上线，
于是这个活动整个丢失。

**代码**：`DecisionDataLoader` / 回归锁 `DecisionQueryCountTest`

### 6.2 命中快照 = 零数据库查询

代际推进时把整条业务线的物料建成不可变 `DecisionSnapshot`，决策直接 `materialize`。
没有快照自动回落走库（console 天然走库：它没有构建器调用方，store 恒空）。

**非显然之处**：快照命中就**不再回落走库**——即使该 SPU 在快照里查不到任何绑定，
也直接返回空候选，不会补查数据库。这是有意的（否则快照就不是权威了），但排查问题时要知道。

### 6.3 缓存按「实测足迹」加权，不按个数

**问题**：`maximumSize(500)` 按 **KieBase 个数**计权，把每个 KieBase 当 1 单位。
实测证明足迹由**生成规则数**主导：「1 活动 × 200 档」(200 规则, ~5.4MB) ≈「10 活动 × 20 档」(200 规则, ~5.2MB)，
而按个数计权会把前者当 1，**比小 KieBase 低估 ~20×**——噪声邻居能悄悄吃爆堆。

**做法**：`maximumWeight` + `weigher = 260KB + 37KB × 规则数`（实测拟合），预算配置化（默认 256MB）。

**非显然之处**：每个 KieBase 自带 classloader，生成的 rule 类落 **Metaspace**（实测 ~12KB/规则），
所以权重合并堆 + Metaspace 两个池。生产**必须**配 `-XX:MaxMetaspaceSize`——
不配 = Metaspace 无上界，涨爆的是原生内存，比堆 OOM 难诊断得多。

**代码**：`ActivityRuleRuntimeService` / `ActivityCacheWeigherTest` /
实测脚本 `ActivityKieBaseSizingTest`（gated `-Dsizing=true`）

### 6.4 runaway 护栏：fire 上界按 artifact 派生

`fireAllRules` 的上界 = `base + perRule × 该 KieBase 的编译规则数`（**per-artifact，非全局常量**）——
大规则集给大预算、小的给小的。超界 `halt()` + warn（**no-silent-cap**），再由 `safeRun` 兜底回退。

**代码**：`ActivityRuleRuntimeService.FireCeilingListener` / `ActivityFireCeilingTest`
（教学版在 Step 14：`fireAllRules(max)` / `halt()` / `AgendaFilter` 三种护栏对照）

### 6.5 构建期开关省掉热路径字符串累积

`explain=false` 时**构建期就不 emit** `result.trace(...)`，而不是响应期过滤——
大租户大规则集下省掉每次 fire 的字符串拼接与 GC。console 试算走 `explain=true`，decision 热路径走 false。

---

## 七、可观测性

### 7.1 回退必须计数（这是头号告警项）

**问题**：规则执行失败会**静默回退**——而回退会**改变实际发放金额**。
此前它只有一条 `log.warn`，线上完全看不见。

**做法**：`metrics.fallback(scene, reason)`，且 **reason 归类成有限集合**
（`compile-error` / `fire-ceiling` / `eval-error` / `empty-decision` / `engine-disabled` / `condition-tree-unavailable`）。

**非显然之处**：**绝不能把异常全文塞进标签**——编译错误里含行号与 DRL 片段，
会让 Prometheus 的标签基数直接爆掉。

### 7.2 标签基数治理

**问题**：`activityId` 是运营随手能建的，序列数**不受工程控制**。
基数爆炸的代价是大促当天整套监控一起挂。

**做法**：`ACTIVITY_TAG_CAP = 200`，超出部分并入 `__over_cap__` 哨兵（总量仍准，只是分不出是哪几个），
**响应里原样带出不隐藏**（no-silent-cap）。

**已知边界**：`contains → size() → add` 三步在并发下可能略微超过 200；且进了标签集就没有淘汰。

### 7.3 命中计数打在唯一出口

红包通道的 `metrics.hit` 打在 `spuDiscount` 的**唯一出口**而不是引擎命中的那个分支——
否则回退路径（`safeFallback` 也会命中活动）会漏计，表现为"命中率"虚高（分母对、分子少）。
**少计的指标比没有指标更危险**，因为它看起来是权威的，而回退恰恰是最需要盯着的时刻。
同一出口顺带打 `metrics.amount`：手上握着 `hitAmount` 却只计"命中了"，
就意味着「满 300 减 50」被配成「满 3 减 50」时监控全盘绿灯——命中数只是稍高，没有任何一条指标会动。

**现状要说清**（不是每条通道都做到了"唯一出口"）：

| 通道 | 现状 |
| --- | --- |
| 红包 `spuDiscount` | 唯一出口，`hit` + `amount` 都打 |
| 买赠 `buyAndGetGifts` | **两个**调用点（DRL 命中路径与聚合回退路径各一次），因为这条通道没有单一出口对象可挂 |
| 加价购 `AddOnPurchaseService` | **完全没有埋点**——类里 grep 不到 `DecisionMetrics` |

另有 `metrics.reject(scene, reason)` 回答「配了但不发」：淘汰原因此前只写在候选对象的 `rejectReason` 上，
而热路径 `explain=false`，那个字段与 trace 两个出口在生产上都不打开；唯一沾边的空决策回退
只在"这张券是唯一候选"时才走到，**恰恰是多活动并存这种最容易配错的场景观测全黑**。

### 7.4 决策留痕：客服查得到单，财务归得了因

**问题**：决策路径**零 repository 写入、零业务日志**。用户投诉「该减 50 只减了 20」，
系统里能查到的只有"活动配置**现在**长什么样"——查不到当时问了什么、返回了什么、命中哪个活动、按哪一版算的。

**做法**：每次决策生成 `decisionId`（响应与日志同值），
出参补 `hitVersion` / `clamped` / `items`（逐活动明细：activityId、version、benefitForm、amount、applied、rejectReason），
再由独立 logger `activity.decision.audit` 打一行 JSON（另落 `source` / `generation`，见 §3.2）。

> ⚠️ **落盘的只有红包（`spu-discount`）这一条通道**：`auditLog(...)` 全仓唯一调用点在
> `ActivityQueryService.spuDiscount` 出口、写死 `SCENE_DISCOUNT`。买赠虽然也生成了 `decisionId` 并放进 `GiftView`，
> 但从不调用它；加价购更彻底——`AddOnOptions` / `AddOnQuote` 两个 record 连 `decisionId` 字段都没有。
> 所以客服拿着**买赠或加价购**的工单按 decisionId 去日志里检索会一无所获。这是本条技术点当前真实的覆盖边界。

**非显然之处**：

- **为什么是日志不是表**：decision 连的是只读账号，物理上写不了库（`DecisionDdlGuardTest` 钉死这条边界）。
  让热路径去写库会同时毁掉「只读副本可扩」与「写面独占 DDL」两条边界。
  落库版本需要给 decision 配一个只对流水表有 INSERT 权限的第二数据源，属于独立决策；
  在那之前，结构化日志 + 集中采集是零架构变更就能拿到的那 80%。
- **未命中的决策同样带 `decisionId`**——"查不到"也需要能查。
- **落选者也要进 `items`**：单选策略下只有赢家 `applied=true`，但客服工单的多数是
  「为什么我没享受到」，答案在被淘汰候选的 `rejectReason` 里。
- 日志里 `source` / `generation` 必须与 `hitVersion` 一起落：只有活动版本而没有代际时，
  「活动版本对、但快照是旧代」这类事故在日志里查不出来。

**代码**：`ActivityQueryService.auditLog` / `DiscountView`（`items` / `decisionId` / `clamped` / `provenance`）/
`DecisionOutputContractTest`

### 7.5 「自证的决策」：验证工具必须走线上那条路

**问题**：控制台的「优惠验证」页此前打的是**写平面**的 legacy 读端点。
而 console 进程里没有快照构建器的调用方、`DecisionSnapshotStore` 恒空，**必然走库**；
线上决策却优先读代际快照。于是「用来自证优惠有没有生效的工具」，
恰好是**唯一照不到快照侧问题的那条路**——配置改了没进快照、活动进不了任何桶，验证页一律看不见。

**做法**：三件事叠起来，让"这次结论从哪来"由响应自己回答。

1. **验证页改打决策平面** `/api/decision/*`（网关前缀），页面上把「决策服务 / 控制台走库 / 两条都打对拍」做成显式选择，默认决策服务。
2. **响应带 `provenance`**（`DecisionProvenance` record，贯通 `DiscountView` / `GiftView` / `AddOnOptions` / `AddOnQuote`）：
   `source`（snapshot|db）+ `generation`（参与本次决策的桶里**最落后**的一代）+ `buckets`（桶数）。
   console 侧另开 `GET /activity-marketing/generation?bizLine=` 回显库里当前代际当**参照物**——
   只给决策一侧的 `generation=7` 是个装饰数字，运营判断不了"我刚发布的那次进去了没有"。
3. **诊断端点 `GET /decision/v1/snapshot[?activityId=]`**：列出本租户的快照桶
   （bizLine / generation / builtAt / ageSeconds / activityCount），带 `activityId` 时直接回答"在哪个桶 / 不在任何桶"。

**非显然之处**：**第 3 件是必需的，因为 provenance 三个值在最要命的那条故障上全绿**——
活动的 `bizLine` 为空（写平面不强制必填）时它进不了任何桶（构建期按 bizLine 精确匹配），
而超龄兜底重建只遍历**已存在**的桶、永远建不出不存在的那个。
此时决策照常走快照、代际是别的业务线的正常数、快照也很新，只是这个活动根本不在里面——
页面上看到的"未命中"，与"活动确实不该命中"**完全同形**，靠等、靠重启、靠兜底重建都好不了。

诊断端点**只读、不发起决策**，所以不会把验证流量混进 `activity.decision.{hit,amount}`，
也不消耗 `ACTIVITY_TAG_CAP` 的 200 个标签位。

前端另有两处刻意的判定：**「决策服务不可达」与「决策未命中」是两种状态**
（401/403 单判为"可达但未授权"，不降级成"没有活动"）；
**两侧 `source` 都是 snapshot 时对拍判红**——那说明对拍已失效（在拿快照跟自己比），
而"永久绿"是比飘红更彻底的错误安心。

**代码**：`DecisionProvenance` / `DecisionPlaneController.snapshot` /
`ActivityMarketingController.generation` / `frontend/src/console/pages/ValidateView.vue` /
`frontend/src/shared/apiClient.ts`（`decision` service 的 base 必须是网关前缀 `/api/decision`）

**可被追问 ①：为什么不做成"双打 diff"就够了？**
因为**检出上界只到取数层**——两条平面共用同一份 `BenefitEvaluator`，
求值语义错了两边会一起错，对拍照样全绿。页面上明写了这句。
而且噪声源是真的：`decisionId` 每次新 UUID、`traces` 两侧 explain 档位不同、`mode` 与钱无关、
`items` 顺序（服务端已按 activityId 定序，但页面不依赖它）、
`strategy` 是**合法瞬态**（策略行在 create 时就 upsert，而代际只在状态流转时推进，
于是新建带 `discountStrategy` 的草稿会让走库侧立刻看到新策略、快照侧要等下一代）。
再加上两条平面是两次请求、各自取一次 `Instant.now()` 判活动时间窗，恰好跨过起止时刻时会天然分歧。
噪声不排除干净，面板天天飘红然后被所有人忽略。

**可被追问 ②：为什么 `ageSeconds` 不进业务契约？**
诊断端点里的年龄读的是**本租户**的桶（`store.forTenant`），
而 `activity.decision.snapshot.age.seconds` 那个 gauge 来自 `DecisionSnapshotStore.oldestAgeSeconds`，
是**跨租户**统计（调度线程与指标线程没有租户上下文）。多租户下两者永远对不上，
混进业务响应会让 SRE 与运营对着两个数吵架。

**可被追问 ③：`generation` 为什么取最小而不是最大？**
一次决策会合并该租户**所有业务线**的桶。这个数要回答的是"我刚发布的那次进去了没有"，
任何一个桶落后都意味着"还没全进去"；取最大值会把落后的那个桶藏起来。
`buckets > 1` 时 `generation` 是下确界而非某一个桶的真值——`buckets` 字段就是那个约定的诚实声明。

---

## 八、把架构约束变成会失败的测试

这个仓库里有一类测试不测业务，只测**架构约束**——它们的价值是让违规**编译不过 / 测试变红**，
而不是靠文档和 code review 记得：

| 测试 | 钉死的约束 |
| --- | --- |
| `TenantArchGuardTest` | 不手写 tenant 谓词、不用 `nativeQuery` |
| `DecisionDdlGuardTest` | decision 的 `ddl-auto` 必须是 `validate`（**读源文件**，不是读运行时配置——否则被环境变量盖住就测不出来） |
| `DecisionTenantHeaderTest` | `/decision/v1/*` 也受租户过滤器管 |
| `DecisionQueryCountTest` | 取数查询次数不随候选数增长 |
| `ActivityCacheWeigherTest` | 缓存按足迹加权，不退回按个数 |
| `ActivityQuerySafetyFallbackTest` | 旧的 `java-*` 开关配 false **也不能**把生产切回 DRL |
| `SnapshotParityTest` | 快照路径与走库路径结果等价（含「编辑收窄绑定后两条路都不再发钱」） |
| `OfflinePropagationTest` | **任意**状态流转都要推进发布代际——它是 decision 侧唯一的"配置变了"信号；且兜底重建不得占用 `rollback` 那一个槽位 |
| `SnapshotStaleRebuildTest` | 快照超龄能自愈；且快照记录的是**库里的真实代际号**，不是 `lastSeen+1` |
| `DecisionOutputContractTest` | 决策出参契约：`hitVersion` / `clamped` / `decisionId` / `items`（含落选者与淘汰原因） |
| `BenefitScopeTest` + `DecisionScopeGoldenTest` | 权益作用域三档判定；商品级活动只对自己的商品算钱，纯求值层与端到端两条路各钉一遍 |
| `GrantLedgerTest` | claim 幂等；不传 version 打到当前 ONLINE 版本而非草稿；失败的 claim 不留"有账无货"的流水 |
| `SpuIdConditionCompatTest` | `spuId` 从 NUMBER 放宽成 ARRAY 后，存量 `eq`/`in` 仍读成集合语义（`contains`/`containsAny`） |

后五行严格说是**行为回归锁**而不是结构守卫（不读源文件、不扫包），
但立意相同：把"这条不能退回去"写成会失败的测试，而不是写在文档里等人记得。

**可被追问**：为什么 `DecisionDdlGuardTest` 要读源文件？
答：它守的是"本地按文档命令单跑 decision 时也不能带 DDL 权限"。
读运行时配置的话，compose 里的环境变量会把它盖住，测试绿但本地单跑仍然裸奔——**这个坑真踩过**。

---

## 九、前端工程

- **Vue 3 + Vite + TS 前后端分离**，产物由**独立 nginx 镜像**托管（不在 console 的 JAR 里）。
  ⚠️ 改了前端只重建 console 是没用的，要重建 gateway。
- **玩法卡片（playbooks）**：把六种权益形态包装成有名字的玩法，每个一句人话 + 预填参数。
  **做不到的玩法不删卡、标灰并写明缺什么**——比悄悄隐藏诚实。
- **优惠验证页**从 12 份 playbook 派生场景，按 discount / gifts / addon **三通道**展示结构化结果；
  它默认打**决策平面**而不是控制台走库端点，理由与配套的物料来源徽章 / 快照探针见 §7.5。
- **三态主题**（light / dark / system）+ 设计令牌 + 自托管字体拉丁子集（~80KB，不连任何外部 CDN）。
- **e2e 契约文档** `frontend/e2e/data-testid-contract.md` 是活文档——
  `data-testid` 是前后端之外的第三种契约，散落在测试里就会腐烂。
- **视觉红线 e2e**（`e2e:visual`）：把"不能回退的视觉决定"变成会失败的测试。

---

## 十、Drools 本体（教学层覆盖的知识点）

`drools-lab` 的 18 个 Step 是一条从 Hello World 到生产护栏的完整路径，
每个 Step 一个 REST 入口，可以单独跑：

| 主题 | Step | 知识点 |
| --- | --- | --- |
| 基础 | 1–2 | facts / when-then / salience / join / 规则叠加 |
| 聚合与级联 | 3 | `accumulate` / `modify` 触发重新匹配 |
| 存在性量词 | 4 | `not` / `exists` / 标记 fact 自终止 |
| 流程编排 | 5–6 | agenda-group LIFO 栈 / `auto-focus` / `lock-on-active` / listener 可观测 |
| 规则外置 | 7, 17 | 决策表（Excel）/ DMN + FEEL + DRG |
| 时间维度 | 8 | CEP `@role(event)` / 滑窗 / pseudo clock |
| 动态性 | 9, 16 | `KieHelper` 运行时编译热加载 / KieScanner + KJAR 绑 ReleaseId |
| 状态 | 10–11 | `Marshaller` 会话持久化 / Stateless 对比 |
| 推理 | 12–13 | TMS（`insertLogical` 自动 retract）/ 后向链 + query 递归 |
| 生产护栏 | 14–15 | `fireAllRules(max)` / `halt()` / `AgendaFilter` / Micrometer |
| 综合 | 18 | 规则即数据 + rehydrate（Step 9+10+4 合体） |

**几条容易被追问的细节**：

- **`update($fact)` 会死循环**：它重新评估所有依赖该 fact 的规则；`no-loop true` 只防"自己重新激活自己"，
  **不防其他规则的 update 间接重新激活自己**。cross-rule 防护要用 `lock-on-active` + `agenda-group`。
- **record 在 LHS 能用、在 RHS 不能**：Drools 8.x 的 LHS 会自动尝试 record accessor（`age()`），
  但 RHS 直接编译成 Java 代码、没有适配层——`$p.getMessage()` 对 record 会编译失败，必须写 `$p.message()`。
- **DRL 是运行时解析**：`mvn compile` 过了不代表规则没语法错，且是 lazy compile——
  改完重启后**第一次请求**才触发编译并报错。冒烟一次比读启动日志可靠。
- **KieSession 线程不安全**：每次请求 `newKieSession` + `fireAllRules` + `dispose`，不要为了"省"复用。
  `StatelessKieSession` 与 `DMNRuntime` 是线程安全的，可以当字段缓存。

各 Step 的完整说明与实现注意点见 [`steps-guide.md`](steps-guide.md)。

---

## 十一、工程化

- **Maven 四模块 + 依赖方向即架构约束**：`decision ↛ drools-lab`，
  于是 decision 的 jar 甩掉 `kie-ci`（会拉进 maven-core / aether）与 `kie-dmn`，
  且**写能力在物理上不可达**（classpath 上没有写平面 bean）。
- **单库双账号**：console 用 root 建表，decision 用 `GRANT SELECT` 的 `decision_ro`。
  三层叠加（init 脚本 GRANT + compose 传账号 + 应用 `ddl-auto: validate`），任一层单独失效仍有兜底。
- **CI 三 job**：`backend`（全 reactor 测试）与 `frontend`（vitest + typecheck + build）**并行**，
  二者绿后在 PR / main 上跑 `validation-e2e`（起整栈 Docker + Playwright，25–45 分钟）——
  慢 job 不挡特性分支的日常 push。
- **容量基准刻意放在 Maven 源码根之外**（`examples/capacity/`，沿用 `examples/aviator/` 先例）：
  它引 QLExpress，而生产四个模块都不该有这个依赖，也就不该让它进 reactor 的 pom。

---

## 附：这个项目里"不体面但诚实"的部分

技术点清单如果只列亮点就是软文。以下是已知的落差，都写在文档里而不是藏着：

| 落差 | 现状 |
| --- | --- |
| 库存是声明式 | 决策链路**不读** `inventory`，防超发全靠 claim |
| 两组指标的 `scene` 标签对不上 | `decisionSource` 用 `ActivityType.name()`，其余用 `spu-discount`/`gifts`/`addon` |
| 加价购通道缺指标 | `AddOnPurchaseService` 没注入 `DecisionMetrics`，`duration`/`candidates`/`hit` 三条都没有 |
| 验证流量污染按活动的指标 | 优惠验证页默认打决策平面，运营点一次"验证"就在 `activity.decision.{hit,amount}` 里记一笔真实命中——按活动聚合的读数含验证流量且**分不出来**（诊断端点 `/decision/v1/snapshot` 刻意不占，但真发起的决策就是会占） |
| `activityId` 标签位不可回收 | 进了标签集就没有淘汰（见 §7.2），验证 / 压测造的活动同样占位；200 个位置用完之后新活动一律并入 `__over_cap__` |
| 作用域两档口径可能不同 | 覆盖整单时用 `orderAmount`、真子集时用 `Σ 作用域行`，而运费 / 补贴算不算进 `orderAmount` 契约里没规定（见 §2.5） |
| per-tenant 公平份额只有设计 | Caffeine 单缓存不原生支持 per-key 配额，机制延后 |
| `rollback` 只能滚一次 | 只保留一代，显式设计 |

更完整的清单见 [`activity-marketing.md`](activity-marketing.md) 的「已知落差」一节。
