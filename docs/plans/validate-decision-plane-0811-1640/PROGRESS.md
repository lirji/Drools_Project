# 进度 · 验证页改打决策平面

> 决策与取舍见同目录 `DECISION_RECORD.md`。本文件记录**做到哪了、验到什么程度**。
> 2026-08-11。全部改动在工作树，**未提交**。

## 工作包

| # | 工作包 | 状态 | 证据 |
| - | ------ | ---- | ---- |
| W1 | 走库路径候选按当前线上版本收窄（真 bug） | ✅ | 先写红：`SnapshotParityTest#narrowedBindingStopsPayingOnBothPaths` 复现「走库发 50.00、走快照不命中」；修 `DecisionDataLoader.loadFromDb` 后转绿 |
| W2 | 候选确定序 | ✅ | `DecisionDataLoader.ordered()`，两条路合流点按 activityId 排序 |
| W3 | `DecisionProvenance` 贯通四个响应契约 | ✅ | `Materials`/`DiscountView`/`GiftView`/`AddOnOptions`/`AddOnQuote` 各留兼容构造器，`clean test-compile` 零破口 |
| W4 | `GET /decision/v1/snapshot` 诊断端点 | ✅ | 见下「实跑证据」② |
| W5 | `GET /activity-marketing/generation` | ✅ | 决策侧 generation 的参照物 |
| W6 | 前端管道（apiClient / vite proxy / 类型） | ✅ | base 取网关前缀 `/api/decision`；dev 另加独立 rewrite proxy |
| W7 | `activityApi` 尾部 `plane` 参数 | ✅ | 既有位置参数断言全部保命 |
| W8 | 渲染 items[]/rejectReason/hitVersion/clamped/decisionId | ✅ | 这些数据一直都在，只是页面丢掉了 |
| W9 | 平面选择器 + provenance 徽章 + 不可达/未命中分离 | ✅ | 401/403 单列为「可达但未授权」，不降级 |
| W10 | 双打对拍（默认关）+ 判红口径 | ✅ | 两侧同为 snapshot 判红；排除 5 类正常差异 |
| W11 | e2e 跟随 | ✅ | `e2e-validation` 改打 `/api/decision/*` + `waitForSnapshot` 显式等桶；两条布局 smoke 显式选走库平面 |
| W12 | 测试 | ✅ | 后端 `./mvnw clean test` **430**（common 166 含 3 skipped / console 244 / decision 20）；前端 `vitest` **283**；`vue-tsc` 无错 |
| W13 | 文档全量同步 | ⏳ | 36 条历史欠账 + 本轮新落差；第一次并行起草因会话额度全数失败（未写入任何文件），已重跑 |
| W14 | 全栈实跑 | ✅ | 见下 |

## 实跑证据（gateway :8095，header 档，镜像已按本轮代码重建）

**① 止损链路端到端**（`livecheck-1786457747` / SPU 882747 / 满减 50）

```
上线 → 3s 内进快照：{"generation":1,"activityCount":1,"containsActivity":true,"ageSeconds":0.0}
决策 → hit=True amount=50.0 hitVersion=1 provenance={source: snapshot, generation: 1, buckets: 1}
下线 → 4s 内停止命中，provenance={source: snapshot, generation: 2, buckets: 1}
        快照桶 activityCount 归 0、inSnapshot=false
```

**② 诊断端点的存在理由——那条「三个值全绿却不命中」的故障**

建一个 `bizLine=null` 的活动并上线（写平面**允许**，只校验长度 ≤64）：

```
决策 → hit=False，但 provenance={source: snapshot, generation: 2, buckets: 1}  ← 三个值全绿
诊断 → inSnapshot=false
       hint: 该活动不在本租户的任何快照桶里：要么它还没上线，要么它的 bizLine 为空/与桶键
             对不上（bizLine 为空的活动永远不会进入任何桶，兜底重建也救不了它）
```

这正是「只回显 source/generation/builtAt 三个值就够了」这个原方案会漏掉的那条故障，
也是把诊断端点列为必做项（`DECISION_RECORD` D2）的全部理由。

## 没做的 / 留给下一轮

- **验证流量会打进 `activity.decision.{hit,amount}`**：控制台若把 `/by-activity` 渲染成
  「这个活动花了多少预算」，就是拿自己造的验证流量记账。且 `ACTIVITY_TAG_CAP=200` 的标签位
  **不可回收**，e2e 每轮造 13 个一次性活动，反复跑会把真实活动挤进 `__over_cap__`。
  本轮只在文档里记账，没改指标口径——要么验证流量不进这两个指标，要么那块卡别接这个端点，两者不能同时做。
- **`resolveStrategy` 是第二次独立的 store 查找**：候选 bizLine 与桶键对不上时它会真发一次查询，
  而此时 `provenance.source` 已宣称 snapshot。javadoc 已写明「source 描述的是**物料**来源」，
  不等价于「本次决策零查询」。收敛需要动 loader 的公共入口，而 gifts/addon 两条链根本不解析合并策略
  （折进去等于给它们各加一次查询，且 `DecisionQueryCountTest` 的买赠断言上界抓不住），故本轮不做。
- **`e2e:validate` 尚未在新平面上重跑**：`docs/delivery/promotion-validation-all-playbooks` 那套
  「全玩法已验证」的证据链验的仍是走库路径。脚本已改好，需要一次完整重跑才能产出覆盖真实平面的证据。
- **双打对拍的检出上界只到取数层**：两条路共用同一份 `BenefitEvaluator`，形态判别/封顶/取整的 bug
  会在两侧产出同样的错答案。页面上已写明这句，但它仍是这个功能的天花板。
