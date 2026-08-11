# 决策记录 · 验证页改打决策平面

> 2026-08-11。承接 `docs/plans/activity-chain-review-0811-1730/REVIEW.md` 的 P1-9 与总表「验证页改打 `/decision/v1`」。
> 上一轮四件事（作用域 / 上限 / 止损 / 留痕）落地后，评审里唯一没做的两件事之一。

## 背景：为什么原样照做会白做

REVIEW.md 的改法是「验证页改打 `/decision/v1`，响应回显 `source=snapshot|db` + `generation` + `builtAt`」。
两轮对抗评审逐条读码后推翻了这个方案的前提：

1. **`builtAt` 不是配置新鲜度探针，是 poller 存活探针。** 上一轮加的兜底重建（`GenerationWarmService:116-136`，阈值
   `snapshotMaxAgeMs` 默认 60s，轮询 3s）把「陈旧快照」压成最多一轮的瞬态。健康系统里 `ageSeconds` 恒落在 `[0, ~63s]`，
   验证页作为随机采样点几乎采不到。运营会盯着一个几乎恒定的数看。
2. **唯一能长期存在的快照侧故障，三个值全绿。** `bizLine` 为空的活动进不了任何快照桶
   （`DecisionSnapshotBuilder:75` 按 bizLine 精确匹配；`ArtifactService:117-122` bizLine 空时直接 return 不 bump），
   而兜底重建只遍历 `snapshotStore.all()`——**不存在的桶永远建不出来**。此时页面读数是
   `source=snapshot`（真的）、`generation`=另一条业务线的代际（正常数）、`builtAt` 几秒前（很新）、`hit=false`、`items=[]`。
   缺的关键件是「**这个活动在不在当前快照里**」，而它在全仓没有任何出口。
3. **双打 diff 有三个真噪声源。** 快照侧候选顺序来自 `Set.copyOf`（`DecisionSnapshot:71-73`），迭代序由 JDK SALT 决定、
   **每次进程启动翻面**；`strategy` 是合法瞬态（策略行在 create 时 upsert，代际只在 changeStatus 推进）；
   两次 HTTP 是两个 `Instant.now()`，活动时间窗边界会被标成假红。

## 顺带查实的一条真 bug（上一轮 P1-9 只修了一半）

`DecisionDataLoader.loadFromDb` 的候选身份来自绑定行，而绑定查询**不带 version**、旧版本绑定行也不软删（`:141-143`）。
`scopeOf`（`:170-182`）只把非当前版本的绑定挡在**作用域**外，候选身份仍在，拿到空作用域（`:274` 的 `getOrDefault(..., Set.of())`）。
而 `BenefitEvaluator:191`——**AMOUNT（直减/满减）形态压根不调 `baseAmount`**，直接 `setComputedAmount(redPackageAmount)`。

于是「v1 绑 A/B → 编辑成 v2 只绑 A → 只查 B」：**走库照发满减，走快照根本不是候选**。
最常见的形态，且正好绕开现有 `DecisionScopeGoldenTest`（它用「折」且请求里留着 A）。

这条必须先修：否则它会在双打 diff 上线当天飘红，且极易被当成快照噪声一起压掉。

## 决策

| # | 决策 | 理由 | 被否方案 |
| - | ---- | ---- | -------- |
| **D1** | 先修走库路径的版本过滤，再动页面 | 真分歧混进 diff 噪声里就再也捞不出来了 | 「先上页面，diff 出来再说」——那条红会被当噪声压掉 |
| **D2** | 新增 `GET /decision/v1/snapshot` 诊断端点（桶清单 + `?activityId=` 落桶归属） | 这是页面要照的东西**本身**，也是 source/generation/builtAt 三个值全绿时唯一能说话的出口 | 只回显三个值——最重要的故障上全绿 |
| **D3** | 业务响应只加**最小** provenance（`source` + `generation`），`builtAt/ageSeconds/buckets` 交给诊断端点 | 业务契约不该背运维口径。且 `oldestAgeSeconds`（`DecisionSnapshotStore:103-110`）是**跨租户**口径、与决策走的 `forTenant` 不是同一个数，混在一起写文案会让 SRE 与运营对不上账 | 三个值全塞进 DiscountView |
| **D4** | provenance 用共享 record 而非平铺三字段 | `DiscountView` 已有 11 个分量，`miss`/`withTraces`/`withMode` 三个 helper 各自把全部分量重列一遍；平铺后 source/strategy/mode/decisionId 四个相邻同类型 String 可换位而编译通过 | 平铺 |
| **D5** | 双打 diff 做成**默认关的高级档**，判红口径重写 | 用户明确要「两者都做」。但它的检出上界只到取数层（两条路共用同一份 `BenefitEvaluator`），页面必须写明这条，否则绿 diff 会被读成「决策正确」，比只有一条路更危险 | 双打作默认 |
| **D6** | 两侧 `source` 都为 `snapshot` 时**判红**而不是判绿 | 「console 恒走库」不是不变量，只是**没有调用方**——`DecisionSnapshotStore`/`Builder` 都是 activity-common 的 bean，在 console 上下文里存在且可用（`SnapshotParityTest:145-165` 就是在 console 上下文里 publish 后跑出 0 条 SQL 的）。一旦有人给 console 加预热，diff 变成「快照 vs 同一份快照」，永久绿 = 最彻底的错误安心 | 判一致即绿 |
| **D7** | 服务端给候选定确定序（按 activityId），而不是前端排序补救 | 前端排序只能救 items 比对，救不了 `hitActivityId`——`pickByAmount`/`pickByPriority` 打平时先到先得，赢家本身就不确定 | 前端排序 |
| **D8** | 不把 `strategy` 折进 `load()` 去消 `resolveStrategy` 的二次 store 查找 | gifts 与 addon 两条链路根本不解析合并策略，折进公共入口等于给这两条热路径各加一次 `strategyRepo` 查询；而 `DecisionQueryCountTest:88-90` 买赠断言上界是 `EXPECTED*2=10`，从 4 涨到 5 照样绿，抓不住 | 顺手消重复查找 |
| **D9** | `console` 单体形态下**不做**「静默降级回 console 通道」 | 那等于让 `e2e:tablet`/`e2e:phone`（默认 BASE 是 :8097 裸 console）与 QA 的常用启动**永久跑在走库态**上——正是 REVIEW.md:180 批评的现状。改法是把这些入口切到网关 :8095，并把「不可达」与「未命中」在 UI 上拆成两种可分辨状态 | 页面自动降级 |
| **D10** | 新增 `GET /activity-marketing/generation?bizLine=` | 只回显 decision 一侧的 generation，运营看到「generation=7」无法判断自己刚发布的那次进没进去。没有参照物的 generation 是装饰数字 | 只显示单侧 generation |

## 已知落差（本轮不做，写明而不是掩盖）

- **diff 的检出上界只到取数层**：求值本身的 bug（形态判别、封顶、取整）在两侧产出完全相同的错答案 → 恒绿。页面上必须写明这句。
- **验证流量会进 `activity.decision.{hit,amount}`**，而控制台正打算把 `/by-activity` 渲染成「这个活动花了多少预算」——
  会形成「用自己造的验证流量渲染预算消耗」的自造假账闭环。且 `ACTIVITY_TAG_CAP=200` 的标签位**不可回收**，
  e2e 每轮造 13 个一次性活动，反复跑会把真实活动挤进 `__over_cap__`。本轮只记录，不改指标口径。
- **`resolveStrategy` 是第二次独立 store 查找**：候选 bizLine 与快照桶键对不上时它会真发一次查询，
  此时 `provenance.source` 已宣称 `snapshot`。javadoc 里写明「source 描述的是**物料**来源」，不等价于「本次决策零查询」。
