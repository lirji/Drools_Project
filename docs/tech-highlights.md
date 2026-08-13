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
- 第 N 件折缺 `lines`（订单行）→ 不适用，**不拿均价凑**；
- 脏 `logic` code（条件树的 AND/OR 编码非法）在读路径**不再抛异常**：求值器给的是**三态**
  （`PASS` / `FAIL` / `UNDECIDABLE`），`UNDECIDABLE` 只淘汰**这一个**候选。
  此前它直抛 `IllegalArgumentException`，而决策链路一路无 catch——**一条脏数据把整次请求打成 500**，
  同一次请求里其它完全正常的活动跟着一起没了。写平面创建期**仍然抛**（脏 code 就该在入库前被拒）。
  三态而不是 boolean 的理由是计数：`UNDECIDABLE` 是故障（要有人去修数据）、`FAIL` 是每天都在发生的正常业务，
  合成一格就再也分不开（对应 `RejectReason.CONDITION_UNAVAILABLE` 与 `INELIGIBLE` 两个原因码）。

**代码**：`BenefitEvaluator.java` / `ConditionTreeEvaluator.java`（`Verdict` 三态）/
`NotApplicableCandidateTest`（14 例）+ `ConditionTreeGuardTest`

### 2.3 判别顺序即语义

**问题**：六种权益形态挤在同一个 `redPackageAmount` 字段上，靠 `red_package_amount_unit` 判别。
判别分支的**顺序**直接决定发多少钱。

**三条必须记住的顺序**：

1. **形态判别排在 `takeType` 之前**——否则 API 手造的「折 + takeType=2」会被抢进随机分支，永远走不到折扣计算；
2. **随机分支排在 `redPackageAmount==null` 的 guard 之前**——随机金额来自 `redPackageRangeAmount`；
3. **未知单位回落金额型**——脏数据表现为"按旧行为发"，而不是"按猜出来的形态发"。

**让编译器守住"六种形态一种都不能漏"**：形态分派现在是一个**枚举 `switch` 表达式且不写 `default`**——
新增一种 `BenefitForm` 而漏了分支，是**编译失败**，不是"少算一种券"。
（⚠️ 只有 switch **表达式**有这个性质；改写成 arrow switch **语句**对枚举常量不强制穷尽，等于白改。）
而有**两道 guard 刻意留在 switch 之外**：上面第 2 条那个随机 guard（它只对 `AMOUNT` 形态生效，
留在外面是为了排在下面这道之前——随机金额来自 `redPackageRangeAmount`，放到后面会被 `redPackageAmount==null` 静默跳过），
以及「既没有固定金额、也不是随机型 ⇒ 唯一的金额来源就是阶梯，没落过档就是不适用」那道（§2.2 的 `ladderApplied`，**对所有形态生效**）。
执行顺序读起来就是「随机 guard → 阶梯未落档 guard → 形态分派」，不再靠注释维持。

**非显然之处**：`redPackageRangeAmount` 是**三用途列**（阶梯数组 / 随机区间 `{"min","max"}` /
第 N 件 `{"nth":N}`），先靠 JSON 顶层类型把数组与非数组切成不相交两半，非数组再由单位 + 发放方式判别。
写成 `[{"min":5,"max":20}]` 会被阶梯路径认领。
这条判别规则此前在 Java 侧被写了**三遍**（决策取阶梯定义 / 求值器按形态解析 / 写平面校验），
各自演化的后果是**写侧接受的配置读侧算不出金额**——活动以"不适用"的姿态上线，而运营看到的是"已上线"。
现在三处共调 `RangePayload.parse`，"期望哪种载荷"的权威只有 `RangePayload.expectedKind` 一处。

**代码**：`BenefitEvaluator.computeAmounts` / `RangePayload`（唯一解析出口，内部仍复用
`LadderRangeParser` / `RandomRangeParser`）/ 金标集 `DecisionGoldenSetTest`（52 例）

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

**这条种子链现在有两道防线**：属性袋的键名收进 `DecisionAttrs` 常量类
（把"写侧改名、读侧不知情"从静默变成可见），而**真正的守卫**是 `DecisionContextFieldsTest`
里那条**写死字面量**的闭集断言——它刻意不引用 `DecisionAttrs`，否则改名时常量与断言会一起改、
测试跟着变绿。`randomSeedSpu` 不在条件白名单里，所以此前**改掉它全仓测试照样全绿**。

**代码**：`BenefitEvaluator.drawRandom` + `BenefitMath.randomSeedKey` + `DecisionAttrs`

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

### 2.7 "同一张券在两条路上发不同的钱"：把装配收成一条路

**问题**：走库与走快照是两条取数路径，但它们最终都要产出同一个 `ActivityCandidate`。
这条「配置行 → 候选」的扇出此前被**手写了三份**（走库 `flatten` 的 17 个 setter、快照的
`CandidateTemplate` 19 个位置参数、`toCandidate` 里再来一遍 setter），
其中**只有中间那份被编译器守着**。于是同一条缝**已经裂开过两次**——
`scopedSpuIds` 与 `redPackageMaxDiscount` 各漏填过一次。
两次的表现完全一样：不报错、不回退、日志干净，只有对账时才发现。

**做法**：加一个不可变的 `OfferSpec`（record）当**唯一装配目标**，
两条生产路径都必须走 `OfferSpec.from(manageRow, ruleRow, gifts)` 这一个规范构造器；
`CandidateTemplate` 随之删除，`ActivityCandidate` 改成"持有一份 `OfferSpec` + 本轮计算态"。
加一个配置字段却漏了某条路，从此**在类型上不可表达**。

**非显然之处**：

- **哪些东西刻意不进 `OfferSpec`**：`scopedSpuIds` 是「请求 SPU ∩ 本活动当前版本绑定」，
  **逐请求**算出来的，不是配置；本轮计算态（`eligible` / `computedAmount` / `ladderApplied`）同理。
  它们留在候选上，而 `null`（作用域未知 → 按整单算）与空集（作用域已知）的语义差异必须原样保留（见 §2.5）。
- **配置与计算态焊在同一个可变对象上，正是那条事故链的起点**：候选可变 → 快照必须不可变 →
  只好造影子类 → 装配变成三份手写扇出。拆开之后这条链就断了。
- 行为面由 `SnapshotParityTest` / `DecisionGoldenSetTest` 守，**结构面另有一道**
  `OfferSpecArchGuardTest`——下一次漂移会先表现为"有人给候选加了个配置 setter"，
  那时行为测试还是绿的。

**代码**：`OfferSpec.java` / `ActivityCandidate` / `DecisionDataLoader.flatten` /
`DecisionSnapshot.materialize` / `OfferSpecArchGuardTest`

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
  「当前是哪一版」在写平面其实有**两套互斥定义**（最高未删除版 = 编辑基线；最高 ONLINE 版 = 正在发钱的那版），
  从前它们各自散在五个调用点里就地解释一次，事故因此无处可读。现在收成 `ActivityVersionResolver`
  的两个具名方法，调用点必须显式选一个。
- **失败种类必须能分流到状态码**。claim 的七个失败点从前只有一个中文 `reason` 串，
  controller 只能按布尔映射成**恒 409**。而 409 的标准语义是「重试可能成功」——
  于是「少传一个参数」会被下游**无限重试到活动结束**，而「真的售罄」又被当成自己写错了。
  现在 `ClaimResult` 带一个 `FailureKind`，按种类分流成 400 / 404 / 409
  （`release` 的 `orderId` 传空串同理：从 404 改成 400——404 会让调用方以为"这一单没领过、不用冲正"，
  从而**放弃冲正**，库存与限领额度就永久漏掉了）。
  两个细节：`FailureKind` 标了 `@JsonIgnore`（**响应体一字节未变**，状态码已经把信息表达出去了），
  映射用的是**不写 `default` 的 switch 表达式**——新增一种失败种类而漏了映射就是编译失败。
- **不复用 `activity_idempotency`**：那张表的键是客户端生成的 `requestId`，语义是"这个请求处理过没有"；
  流水的键是业务事实，语义是"这份优惠发出去没有"。混在一起的后果是换个客户端重试实现就能把同一单领两次。
- 冲正走 `POST /{id}/release`（同样幂等：已 `RELEASED` 的记录直接返回成功，不重复加库存），
  归还**不判活动状态与时间窗**——活动结束之后仍会有退款进来。

**代码**：`GrantService.claimInventory` / `releaseGrant`（从 977 行的 `ActivityMarketingService` 拆出，
后者保留同名委派方法；两者零共享状态，唯一交集是 `ActivityVersionResolver`）/
`ActivityGrantEntity` / `GrantLedgerTest` + `ClaimResultContractTest`

### 3.3 上线是原子指针切换，不是"先下线再上线"

**问题**：活动改版时，如果"先把旧版下线，再把新版上线"，中间有个窗口**没有任何版本在服务**。

**做法**：上线在**同一事务**里把该活动其它 ONLINE 版本退役 + 本行置 ONLINE。
且**编辑不下线正在服务的版本**——线上版与草稿并存。

**顺带补上的一张表**：`changeStatus` 从前只校验 `targetStatus` 本身合法，**从不看当前状态**——
"哪些流转是合法的"在代码里没有任何一处写下来，接手人只能从各个调用点反推。
现在有一张 from × to 的 `ALLOWED_TRANSITIONS`。**它按今天实际发生的流转成文，不趁机收紧**
（收紧是行为变更，要单独立项），包括那条看起来不对称、实则原样保留的 `OFFLINE → ONLINE`。

唯一被封死的是 `targetStatus=3`（`PENDING_EFFECT`）：它是 `fromCode` 认可的合法码，
但全 main 源码**零生产者、零消费者**——置成该状态的活动代际照常 bump，可它永远进不了任何读路径，
控制台显示成草稿、决策永远不命中，而这个落差没有任何一条日志或指标会说出来。
与其给一个没实现的状态立法，不如在写入口封口（作为**源**仍可迁出，好让外部导入的脏数据有逃生口）。

**代码**：`ActivityMarketingService.changeStatus` / `ALLOWED_TRANSITIONS` / `resolveTargetStatus`

### 3.4 缓存击穿：Caffeine 天然 single-flight

**问题**：同一份 DRL 并发首次编译，N 个线程各编一遍（编译是 100ms~秒级）。

**做法**：`cache.get(key, mappingFunction)` 是**原子计算**——同 key 并发只编译一次，其余等结果。
不需要自建锁。

**非显然之处**：cache key = **tenant + DRL 全文**，显式带租户维度而不是靠"DRL 因 schema 而异"隐式分片——
为 per-tenant 容量 / 淘汰 / 失效留口，且不跨租户共享 KieBase。

**代码**：`ActivityRuleRuntimeService.compileOrGet`

### 3.5 批量操作：部分失败是正常结果

**问题**：批量上下线里有一条失败，是整批回滚还是继续？

**做法**：**逐条独立处理，失败不影响已成功项**，返回 **200 + 部分失败回执**
（`{succeeded:[...], failed:[{activityId, reason}]}`）。

**非显然之处**：返回 200 不是"忽略错误"，是"部分失败是这个接口的正常语义"——
用 4xx/5xx 表达会让调用方无法知道**哪些成功了**。

**唯一的例外是 `targetStatus` 本身非法**：那不是"某几条失败"，是这次请求根本不成立，
现在进循环之前就 400——否则几十条各失败一次，回执里全是同一句话。

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

**关于"回滚"这个止损手段，有三条是后来才补上的**：

- **一个桶就是一个不可变的 `SnapshotSlot(current, previous)`，靠 `compute` 一次原子替换**。
  从前 current 与 previous 是两张 map，"切当前代"与"移交上一代"是两条独立语句——中间有个两表互相矛盾的窗口。
- **只有代际前进时才占用回滚槽位**。预热失败时 `GenerationWarmService` 不更新 `lastSeen`，
  下一轮会对**同一代际**再发一次；若同代重发也占槽位，previous 会被挤成"同一代的旧副本"，
  于是 `rollback` 从此是空转——退到的还是出事的那一代。超龄兜底重建走 `refresh`，同样不占槽位。
- **它此前没有任何生产调用方**：全仓只有两个测试在调 `DecisionSnapshotStore.rollback`。
  也就是说"回滚是求值出 bug 时的止损手段"一直是**空头支票**——previous 槽位修得再对，运维也按不下去。
  现在有 `POST /decision/v1/snapshot/rollback?bizLine=`（与 `GET /snapshot` 同一道角色门 + 同一条 JWT 安全链，
  另需 `console-write-authority`）。**止损手段只有能从生产按下去才算数。**
  两条推论运维必须知道：**① 它只动本进程内存里的指针**（不写库，所以不破坏"decision 连只读账号"这条边界；
  代价是多实例要逐实例调用）；**② 下一次代际推进会把它盖掉**——回滚是止血，真正的修复仍是在 console 侧改配置再发一代。
  没有上一代可回时返回 409 并说明原因（刚重启只发过一代 / 上一次推进是兜底重建），**不假装成功**。

**代码**：`GenerationWarmService` / `DecisionSnapshotStore`（`SnapshotSlot`）/
`DecisionPlaneController.rollbackSnapshot` / `SnapshotParityTest` + `SnapshotRollbackEndpointTest`

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

被拒时返回 **403**（此前是 409）。这不是措辞讲究：409 说的是"资源状态和你以为的不一样，重试可能会成"，
而这里再怎么重试都不会成——必须换一个人来点。**状态码选错时，客户端的正确行为也就跟着写错了。**
它此前是 409 纯属实现细节泄漏（写平面用 `IllegalStateException` 表达它，controller 把所有 ISE 一律映射成 409），
现在由 `ActivityErrorCode.FOUR_EYES_REQUIRED` 决定，与"抛的是哪个 JDK 异常"脱钩（见 §7.7）。
响应体形状未变（`error` 字段原样），只新增机器可读的 `code`。

---

## 六、性能与容量

### 6.1 把 3N+2 次查询压成固定 5 次

**问题**：取数原本是 `3N+2` 次查询（N = 候选活动数）——典型 N+1。

**做法**：全部改批量（`findBy...In`），压成**固定 5 次**，与候选数无关。

**非显然之处**：其中一步的**顺序不能反**——`activity_manage` 批量查回来后，
必须**先筛 ONLINE 再取最高版本**；反过来会取到一个更高版本的草稿然后发现它没上线，
于是这个活动整个丢失。

**另一条 N+1 藏在快照构建期**：热路径压到 5 次之后，`DecisionSnapshotBuilder` 仍在循环体里逐活动查绑定行
（仓库接口里根本没有批量方法——**N+1 是接口缺口逼出来的**，不是谁写岔了），
活动查询还捞该租户**全部**在线活动再用 Java 丢掉非本桶的。现在补了批量绑定查询、bizLine 过滤下推 SQL，
一次构建**固定 6 次**（真实桶另加一次孤儿计数 = 7 次），与活动目录规模无关（`SnapshotBuildQueryCountTest`）。

⚠️ **但 bizLine 过滤下推 SQL 之后，Java 侧那道精确相等判断不能删**——这是一条只在生产成立的差异：
生产 MySQL 8 默认排序规则 `utf8mb4_0900_ai_ci` **大小写不敏感**，
`biz_line = 'retail'` 会把 `Retail` / `RETAIL` 的活动一并收进 `retail` 桶；
而全部快照测试跑在**大小写敏感**的 H2 上，这个问题**永远照不出来**。
而桶归属决定的是「谁在快照里 = 谁能被发钱」。`SnapshotBizLineCollationTest` 用 `IGNORECASE=TRUE` 把
H2 调成大小写不敏感来复现它——**这个测试类的 JDBC URL 本身就是断言的一部分**，改它等于关掉门禁。

**代码**：`DecisionDataLoader` / `DecisionSnapshotBuilder` /
回归锁 `DecisionQueryCountTest` + `SnapshotBuildQueryCountTest` + `SnapshotBizLineCollationTest`

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

不需要 trace 时**构建期就不 emit** `result.trace(...)`，而不是响应期过滤——
大租户大规则集下省掉每次 fire 的字符串拼接与 GC。console 试算要 trace，decision 热路径不要。

**运行期档位与构建期开关是两个东西，刻意没有合并**：

- **运行期**是 `DecisionMode`（`HOT_PATH` / `EXPLAIN`）。它从裸 boolean 改成枚举的真正原因不是可读性，
  而是**那四个"省掉 explain"的便捷重载被删掉了**——它们的默认值**方向相反**
  （`spuDiscount`/`buyAndGetGifts` 默认热路径，加价购 `options`/`quote` 默认试算），
  六个决策入口里有四个走默认值，读者在调用点上无法本地推理这次决策是哪一档。
  今天没出事只是因为两条默认值各自撞对了自己那一侧的调用方；任何一次跨平面复用都会**静默换档**：
  热路径开始外泄逐候选资格明细，或试算页丢掉全部链路。现在每个调用点必须显式表态，漏了编译不过。
- **构建期**是 `ActivityDrlBuilder` 的 `explain` 布尔，它改变**生成的 DRL 文本**，
  而编译缓存的 key 就是 `tenant + DRL 全文`。把它与运行期档位耦合，会让同一份规则被编译两遍。

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

**三条通道现在都收到唯一出口了**，但口径各不相同：

| 通道 | 现状 |
| --- | --- |
| 红包 `spuDiscount` | 唯一出口，`hit` + `amount` 都打 |
| 买赠 `buyAndGetGifts` | 唯一出口。**口径不能照抄红包**——买赠没有单一赢家、没有 `hitActivityId`，改成按实际出了赠品的**来源活动去重**计数（一个活动出三件赠品仍只算一次命中，否则命中量会被赠品配置条数放大） |
| 加价购 `AddOnPurchaseService` | 此前**完全没有埋点**，现已补齐 `timeDecision` / `candidates` / `hit` + 审计留痕 |

两个口径变化值得先说在前面：① 买赠回退分支上，「资格通过但一件赠品都没配」的活动命中量会从 1 掉到 0——
看板上像"回退后这个活动突然不命中了"，实际是**口径修正**（它本来就没发出任何东西，引擎分支也从不计它）；
② 加价购从此**开始占用 `ACTIVITY_TAG_CAP` 那 200 个 activityId 标签位**，与红包/买赠抢同一份预算（见 §7.2）。

另有 `metrics.reject(scene, reason)` 回答「配了但不发」：淘汰原因此前只写在候选对象的 `rejectReason` 上，
而热路径是 `DecisionMode.HOT_PATH`，那个字段与 trace 两个出口在生产上都不打开；唯一沾边的空决策回退
只在"这张券是唯一候选"时才走到，**恰恰是多活动并存这种最容易配错的场景观测全黑**。

### 7.4 决策留痕：客服查得到单，财务归得了因

**问题**：决策路径**零 repository 写入、零业务日志**。用户投诉「该减 50 只减了 20」，
系统里能查到的只有"活动配置**现在**长什么样"——查不到当时问了什么、返回了什么、命中哪个活动、按哪一版算的。

**做法**：每次决策生成 `decisionId`（响应与日志同值），
出参补 `hitVersion` / `clamped` / `items`（逐活动明细：activityId、version、benefitForm、amount、applied、rejectReason），
再由独立 logger `activity.decision.audit` 打一行 JSON（另落 `source` / `generation`，见 §7.5）。

> **覆盖面（已补齐三通道）**：拼装收敛到 `DecisionAuditor`，红包 / 买赠 / 加价购（options 与 quote 两阶段各一行，
> 带 `phase` 字段）都会落日志，`AddOnOptions` / `AddOnQuote` 也补上了 `decisionId`。
> 此前只有红包落盘——`auditLog(...)` 全仓唯一调用点写死在 `spuDiscount` 出口，
> 买赠生成了 `decisionId` 却从不落日志、加价购两个 record 连这个字段都没有，
> 于是客服拿着这两条通道的工单按 decisionId 去检索会**一无所获，而客服并不知道有这个区别**。

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
- **引号与转义只能有一处实现**。原先它是散的：`hitActivityId` 的引号在**实参**里、
  而 `scene` / `strategy` / `mode` 的引号在**模板**里。那个分裂不是随手写的——
  引号在实参里才能让 null 输出成**裸 `null`** 而不是字符串 `"null"`，这个区别对下游解析是实打实的。
  收敛后由 `quoteOrNull`（保留裸 null）与 `quoted`（恒带引号）两个方法把这层语义显式化，输出逐字节不变。
- **格式刻意是手拼的单行 JSON，不走 Jackson**：热路径的开销就是一次字符串拼接，
  换 `ObjectMapper` 等于把反射与树构建搬进决策热路径，换来的只是省掉几十行拼装。

**代码**：`DecisionAuditor`（三通道共用）/ `DiscountView`（`items` / `decisionId` / `clamped` / `provenance`）/
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
而且噪声源是真的：`decisionId` 每次新 UUID、`traces` 两侧决策档位不同（`EXPLAIN` vs `HOT_PATH`）、`mode` 与钱无关、
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

### 7.6 标签值只能有一份定义

**问题**：指标标签的取值是**契约**（面板与告警按它过滤），但它此前是靠人在每个调用点手工配对的。

两处实证：

- **淘汰原因**：给 Prometheus 的原因码（`metrics.reject(scene, "missing-lines")`）与给人看的中文串
  （`candidate.reject("第 N 件折缺订单行或 N 非法")`）是**两条独立语句**。
  漂移已经发生过——`DecisionMetrics` 的 javadoc 写着 `price-above-order`，而实际发出去的是
  `price-above-base`，文档只好补一句"以代码为准"。**漏码** → 这类淘汰在指标里凭空消失；
  **漏串** → 用户看到空的"未生效"原因；两者都不会让任何测试变红。
- **场景名**：`scene` 分裂成**四套**词汇，其中 `activity_decision_source_total` 用的是
  `ActivityType.name()`（`RED_PACKAGE` / `BUY_AND_GET` / `ADD_ON_PURCHASE`），
  而其余九个指标用 `spu-discount` / `gifts` / `addon`。后果不是"不整齐"，是
  **`activity_decision_source_total{scene="gifts"}` 查出来恒为空**——
  而"按 scene 把回退率与来源占比 join 起来看"正是这条指标存在的理由。**它一 join 就空，且空得毫无提示。**

**做法**：`RejectReason`（码 + 文案钉在同一行）与 `DecisionScene`（唯一场景词汇表）两个枚举。
用枚举而不是常量类，是因为它给出**编译期封闭集合**——标签取值集合必须有限，
这与 `ACTIVITY_TAG_CAP` 是同一套顾虑（见 §7.2）。

**非显然之处**：

- `RejectReason.message()` 是**前端契约**（`ValidateView.test.ts` 直接断言中文串），改文案等于改前端；
  而「本活动不适用：」这个前缀**刻意不在枚举里**——资格阶段不加、算额阶段才加，这个不对称是既有行为。
- 统一 `decisionSource` 的 scene 是一次**有意的契约变更**：旧的三条时间序列停止增长、三条新序列开始增长
  （历史数据仍在旧标签下可查）。**已核对 `deploy/` 下没有消费者需要同批改**——
  Grafana 面板只查 JVM 与 HTTP 指标，`prometheus.yml` 没有 `rule_files` 因此没有告警规则引用它。
- **还剩两处没收敛，这里如实写出来**：① 算额阶段的淘汰仍记在 `scene="benefit"` 上（那是**阶段**不是通道）；
  ② 规则执行失败的回退仍传 `RuleScene.name()`（`ELIGIBILITY` / `LADDER` / `GIFT`）。
  两者都会改变已有序列，属于要与 Grafana 面板/告警同批做的变更，不是能顺手塞进重构的东西。

**代码**：`RejectReason` / `DecisionScene` / `DecisionMetrics`

### 7.7 错误分类：别让 bug 伪装成客户端错误

**问题**：全仓 `@ControllerAdvice` 零命中，唯一的映射是 `ActivityMarketingController` 里**手抄三遍**的
`catch (IllegalArgumentException) → 400 / catch (IllegalStateException) → 409`。两个后果：
写平面九个端点只有四个抄到了（其余端点抛异常时落到 Spring 默认 `/error`，一个 500 带着完整 message），
且"分类"被压缩成 JDK 两个通用异常类型——**四眼校验失败**（说的是"不该由你来做"）就是这么变成 409「冲突」的，
而 409 的标准语义是"重试可能成功"，这里再怎么重试也永远不会成功：必须换一个人来点。

**做法**：`ActivityException` + `ActivityErrorCode`（每个取值自带 `httpStatus`），
console 与 decision **各一个** `@RestControllerAdvice`。四眼失败由此从 409 改成 **403**。

**非显然之处**——两个**刻意留空的格子**，方向恰好相反：

- **console 不注册 `IllegalStateException → 409` 兜底**。advice 的作用域是整个 controller 包，
  而那些 `catch` 只挂在 `create` / `status` 两个方法上；提成兜底等于顺带宣布
  `list` / `grants` / `preview` 上抛出的**任何** ISE 都是"状态冲突"——可那里的 ISE 来自 `Optional.get`、
  懒加载、bean 状态错，是 **bug**。代价有三层：① 4xx 不计错误预算、不触发告警，写平面的故障在监控上直接消失；
  ② 调用方去重试一个永远不会成功的请求；③ 排查时先去查"谁在并发改这条活动"，而根因在另一个方向。
- **decision 不注册 `IllegalArgumentException → 400` 兜底**。只读热路径的 IAE 只可能是**脏数据或真 bug**，
  报成 400 等于对调用方说"是你请求写错了"，于是没人来看、调用方去改自己那条本来没问题的请求、
  脏数据继续留在库里影响发钱。这里只留两个出口：分类明确的按自己的码走，其余一律 500 且**不回显 message**
  （toC 流量，异常文案里可能带活动 id / SQL 片段）。它继承 `ResponseEntityExceptionHandler`，
  免得把 Spring 本来就该 400 的情况（请求体不是合法 JSON、缺必填 `@RequestParam`）一起吞成 500。

另两条约束：advice 的 `basePackages` **只圈本包**（console 的 classpath 上还挂着 drools-lab 的
Step 1–18 教学 controller，全局 advice 会把它们的错误行为一起改掉）；
错误码表**刻意不先铺完整**——一个没人抛的错误码，与文档里写着却没人调用的回滚入口是同一类东西。

**代码**：`ActivityErrorCode` / `ActivityException` / `ActivityExceptionAdvice`（console）/
`DecisionExceptionAdvice`（decision）/ `ActivityErrorMappingTest` + `DecisionErrorMappingTest`

---

## 八、把架构约束变成会失败的测试

这个仓库里有一类测试不测业务，只测**架构约束**——它们的价值是让违规**编译不过 / 测试变红**，
而不是靠文档和 code review 记得：

| 测试 | 钉死的约束 |
| --- | --- |
| `TenantArchGuardTest` | 不手写 tenant 谓词、不用 `nativeQuery`；且 `@TenantId` **沿继承链**认（租户列现在收在 `@MappedSuperclass` 里，只看 `getDeclaredFields` 会把每个子类误判成缺租户列） |
| `OfferSpecArchGuardTest` | 候选的**配置**只能来自 `OfferSpec` 一条装配路径（防"有人给候选加了个配置 setter"——那时行为测试还是绿的） |
| `DecisionReadRepositoryGuardTest` | 决策取数层在**类型上**写不了库：`*ReadRepository` 只继承 `Repository`（没有 `save`/`delete`），且 `DecisionDataLoader` / `DecisionSnapshotBuilder` 的仓库字段一个都不能是 `CrudRepository` 子类型 |
| `DecisionDdlGuardTest` | decision 的 `ddl-auto` 必须是 `validate`（**读源文件**，不是读运行时配置——否则被环境变量盖住就测不出来） |
| `DecisionTenantHeaderTest` | `/decision/v1/*` 也受租户过滤器管 |
| `DecisionQueryCountTest` | 取数查询次数不随候选数增长 |
| `SnapshotBuildQueryCountTest` | **快照构建期**的查询次数不随活动目录规模增长（固定 6 次，真实桶另加一次孤儿计数） |
| `ActivityCacheWeigherTest` | 缓存按足迹加权，不退回按个数 |
| `DecisionContextFieldsTest` | ① 白名单字段都必须在决策入参里有来源；② 属性袋键集合是**闭集**（恰好 9 个，含 `userId`/`randomSeedSpu`/`orderLines` 三个不在白名单里的内部键）。断言**写死字面量、刻意不引用 `DecisionAttrs`**——引用常量的话改名时两边一起改、测试跟着变绿，而 `randomSeedSpu` 改一个字节 = 全量随机红包重抽 |
| `ActivityQuerySafetyFallbackTest` | 红包链路**零规则运行时交互**（六形态各验一遍，`verifyNoInteractions(runtime)`），且安全回退保留 STACK/PRIORITY 与资格门槛。两个旧 `java-*` 开关已从代码里删除（此前是"绑定但不读取"，现在是根本不绑定），这条约束不再依赖开关 |
| `SnapshotParityTest` | 快照路径与走库路径结果等价（含「编辑收窄绑定后两条路都不再发钱」） |
| `SnapshotBizLineCollationTest` | 快照桶归属按 bizLine **精确相等**判定，不把判据交给数据库排序规则（用 `IGNORECASE=TRUE` 在 H2 上复现 MySQL 的大小写不敏感） |
| `OfflinePropagationTest` | **任意**状态流转都要推进发布代际——它是 decision 侧唯一的"配置变了"信号；且兜底重建不得占用 `rollback` 那一个槽位 |
| `SnapshotStaleRebuildTest` | 快照超龄能自愈；且快照记录的是**库里的真实代际号**，不是 `lastSeen+1` |
| `DecisionOutputContractTest` | 决策出参契约：`hitVersion` / `clamped` / `decisionId` / `items`（含落选者与淘汰原因） |
| `EntityJsonOrderTest` | 实体响应的键序（身份字段在队首）——继承结构一变，Jackson 会把超类属性排到子类之前，字段名与取值一字未改却**静默**改了响应体 |
| `BenefitScopeTest` + `DecisionScopeGoldenTest` | 权益作用域三档判定；商品级活动只对自己的商品算钱，纯求值层与端到端两条路各钉一遍 |
| `GrantLedgerTest` | claim 幂等；不传 version 打到当前 ONLINE 版本而非草稿；失败的 claim 不留"有账无货"的流水 |
| `ClaimResultContractTest` | claim/release 的失败种类 → 状态码分流（400/404/409）不退回成恒 409，且 `FailureKind` 不进响应体 |
| `SpuIdConditionCompatTest` | `spuId` 从 NUMBER 放宽成 ARRAY 后，存量 `eq`/`in` 仍读成集合语义（`contains`/`containsAny`） |

从 `ActivityQuerySafetyFallbackTest` 往下严格说是**行为回归锁**而不是结构守卫（不读源文件、不扫包），
但立意相同：把"这条不能退回去"写成会失败的测试，而不是写在文档里等人记得。

**可被追问 ①**：为什么 `DecisionDdlGuardTest` 要读源文件？
答：它守的是"本地按文档命令单跑 decision 时也不能带 DDL 权限"。
读运行时配置的话，compose 里的环境变量会把它盖住，测试绿但本地单跑仍然裸奔——**这个坑真踩过**。

**可被追问 ②**：已经有只读数据库账号了，为什么还要 `DecisionReadRepositoryGuardTest`？
答：只读账号是**运行期**的最后一道，而它只在生产那条连接上生效。
读路径上一次手滑的 `save(...)` 能编译、能过全部单测（测试库是可写的 H2），
只有到了生产才炸——那已经是最贵的时刻。把它提前到**类型层**（`Repository` 而非 `JpaRepository`），
写操作在编译期就不存在。两道一起看是四层叠加：init 脚本 GRANT + compose 传账号 + `ddl-auto: validate` + 类型边界。

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
  **四层叠加**（init 脚本 GRANT + compose 传账号 + 应用 `ddl-auto: validate` +
  取数层只注入 `*ReadRepository` 这种**类型上就没有 `save`/`delete`** 的仓库），任一层单独失效仍有兜底。
  前三层都是运行期的，只有第四层能让一次手滑的写操作**编译不过**（见 §八）。
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
| `scene` 词汇表还剩两处没收敛 | `decisionSource` 已统一成 `DecisionScene.code()`（见 §7.6），但**算额阶段**的淘汰仍记在 `scene="benefit"`（阶段名不是通道），**规则执行失败的回退**仍传 `RuleScene.name()`（`ELIGIBILITY`/`LADDER`/`GIFT`）。两者都会改动已有 Prometheus 序列，要与 Grafana 同批做 |
| 加价购开始抢标签位 | 它此前一个 activityId 标签位都不占（压根没埋点），补齐后与红包/买赠共用同一份 200 个的预算——"按活动看命中量/金额"的分辨率会在活动目录变大时提前塌掉 |
| 验证流量污染按活动的指标 | 优惠验证页默认打决策平面，运营点一次"验证"就在 `activity.decision.{hit,amount}` 里记一笔真实命中——按活动聚合的读数含验证流量且**分不出来**（诊断端点 `/decision/v1/snapshot` 刻意不占，但真发起的决策就是会占） |
| `activityId` 标签位不可回收 | 进了标签集就没有淘汰（见 §7.2），验证 / 压测造的活动同样占位；200 个位置用完之后新活动一律并入 `__over_cap__` |
| 作用域两档口径可能不同 | 覆盖整单时用 `orderAmount`、真子集时用 `Σ 作用域行`，而运费 / 补贴算不算进 `orderAmount` 契约里没规定（见 §2.5） |
| per-tenant 公平份额只有设计 | Caffeine 单缓存不原生支持 per-key 配额，机制延后 |
| `rollback` 只能滚一次 | 只保留一代，显式设计；且它**只影响被调到的那个实例**、下一次代际推进会把它盖掉（见 §4.1） |
| "活动不存在"仍报 400 | 语义上是 404，但今天它走 `IllegalArgumentException` → 400。改成 404 是面向调用方的状态码变更（前端 / 脚本 / e2e 都会看到），要单独立项，不塞进异常分类那一批 |
| `PENDING_EFFECT`(3) 这个状态没实现 | 写入口已封死（见 §3.3），但读路径仍然完全不认它——库里如果已经躺着这种脏数据，它对决策就是不存在 |
| `bizLine` 为空的活动进不了任何快照桶 | 写平面不强制必填，兜底重建也只遍历**已存在**的桶。现在构建期会数一遍并打 `activity.decision.snapshot.orphan` + WARN（从"完全静默"变成"有读数"），但根治要么是写入口必填、要么是给它一个默认桶 |

更完整的清单见 [`activity-marketing.md`](activity-marketing.md) 的「已知落差」一节。
