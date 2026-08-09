# 执行进度

> 交接锚点。看这里判断「做到哪了、下一步是什么」。
> 计划见 [FINAL_PLAN.md](FINAL_PLAN.md)，裁决见 [DECISION_RECORD.md](DECISION_RECORD.md)。

最后更新：2026-08-09 · 基线 `2c1f49d` + 本轮改动（**P0 全部 + P1-1/2/3 + 前端 PR-0~PR-6 + 折扣型权益**）

## 测试基线

| | 改造前 | 现在 |
|---|---|---|
| activity-common | 63 | **87**（+24；折扣数学 21 例） |
| activity-console | 40 | **167**（+127；金标集在 Java 与全 Drools 两条路各跑一遍；PR-5 批量版本正确性 +2；折扣型金标 9×2 + 写平面 8 + 回退 4） |
| activity-decision | 12 | **17**（+5） |
| 前端 Vitest | 62 | **154**（+92；PR-5 的 benchModel 20 + ListView 5，PR-6 的 playbooks 11 + 模板屏 5 + 编辑器 8；另含视觉换代自带用例） |
| **端到端金额断言** | **7** | **50**（金标集，含折扣型 9 例：含 STACK 超发面与阶梯/折扣脏数据的两路一致） |
| **前端 e2e** | — | **61 条**（tablet 7 / phone 7 / dev 7 / catalog 6 / 刻度尺 4 / 工作台 13 / **玩法模板 17**） |

全绿。docker 全栈 E2E 已跑通（见文末）。

---

## 已完成

### 闸门 G — 已裁决，未做成本实测
玩法清单由用户拍板为「只有金额型」→ **走 S 档**。G2 的 COUPONS 成本实测**未做**（拍板已给出结论，实测的决策价值消失）。

### B0 护栏批 — 全部完成

| 项 | 状态 | 证据 |
|---|---|---|
| B0-1 `storeId` 死条件 | ✅ | `SpuDiscountRequest.storeId` + `requestAttributes` 唯一映射表 + `DecisionContextFieldsTest` 守卫（抽掉来源即红） |
| B0-1 `decision` ddl-auto | ✅ | 改 `validate` + `DecisionDdlGuardTest`（改回 update 即红） |
| B0-1 `DynRowTable` :key | ✅ | WeakMap 稳定 key + 3 条 Vitest（改回 `:key="i"` 即红，报 `typed-B` 串到 `typed-C` 位） |
| B0-2 金标集 | ✅ | `DecisionGoldenSetTest` **39 例**：阶梯边界 10 / 合并策略 9 / 资格淘汰 6 / 金额精度 6 / 生效窗 6 / explain 2 |
| B0-3 决策指标 | ✅ | `DecisionMetrics`：耗时(scene,mode) / **回退率(scene,reason)** / 候选数 / 编译耗时 / fire 触顶 / 缓存三 Gauge |

### 接缝拆分（D12-2 前置）— 完成
`ActivityQueryService` 305 → 248 行，取数搬进 `DecisionDataLoader`。性能线拥有 loader，权益线拥有求值，编排层只剩薄壳。

### P0-3 消灭 N+1 — 完成并证明
四处循环单查改批量 `In` 查询。`DecisionQueryCountTest` 用 Hibernate `Statistics` 数真实语句：

```
[query-count] N=1 → 5 条语句；N=10 → 5 条语句
```

改造前 `3N+2`（N=10 时 32 条）。测试含防假绿断言（统计未生效时计数恒 0 会让断言退化成空转）。

### P0-4 修「编辑即下线」— 完成

三层联动，语义从「编辑覆盖」改为「草稿与线上并存 + 发布才切换」：

1. **写平面**：编辑当前版本是 ONLINE 时**不再软删**它，只建 v+1 草稿（编辑草稿仍沿用原子软删顶掉，草稿不堆积）
2. **发布**：`changeStatus(ONLINE)` 在同一事务里把该活动其它 ONLINE 版本退役 → 原子指针切换
3. **决策**：取「最高的**已上线**版本」，而不是「最高版本再判是否上线」

反向验证：把第 3 步改回旧顺序，金标立刻红在「编辑只产生草稿，正在服务的 v1 不得被下线」，活动 `hit=false` 消失。

docker E2E：建活动上线→命中 50 → 编辑成 v2 草稿 → **仍命中 50** → 发布 v2 → 命中 80，`ONLINE 版本数=1`。

### 库存红线 B — 完成
`inventory` 输入框置灰 + 「声明式 · 决策不扣减」标注；创建响应回 `warnings`：
> 库存（500）当前为声明式：决策链路不读取、不扣减，不构成超发防护。如需限量，请在下游发放/核销环节实现预占。

### P1-1 代际快照包 — 完成

发布代际推进后，decision 的后台线程把整条业务线的决策物料构建成**不可变快照**，就绪后原子切指针。

| 组件 | 职责 |
|---|---|
| `DecisionSnapshot` | 不可变物料：SPU→活动倒排 / 候选模板 / 资格约束 / 合并策略 |
| `DecisionSnapshotBuilder` | 从库构建（后台线程，一次发布一次） |
| `DecisionSnapshotStore` | 原子切指针 + 保留上一代供回滚 |

**收益（均已验证）**
- **热路径零数据库查询**（此前 5 次，再之前 3N+2 次）——`snapshotServesWithZeroQueries` 断言 `getPrepareStatementCount()==0`
- **回滚原语**：`store.rollback()` 切回上一代，决策立刻按旧物料执行，无需重启也无需反向发布
- **来源可观测**：`activity.decision.source{source=snapshot|db}`，占比掉下来即说明发布传播断了

**两处刻意的设计**
1. **不预过滤时间窗**——窗判定留在请求时用 `Instant.now()`。若构建期就剔除「当前不在窗内」的活动，
   一个 20:00 开始的活动要等下一次发布才会出现，快照会随时间**悄悄过期**。
2. **回落自调节，不需要开关**——只有 decision 带轮询器会构建快照；console 的 legacy 读端点没有构建器，
   store 恒空，天然走库。

**对拍是准入门禁**：`SnapshotParityTest` 用同一批场景（阶梯边界 / MAX / PRIORITY / 资格 / storeId / 草稿排除）
跑两遍，逐字段比 `hit / hitActivityId / hitAmount / strategy`。

> 这条测试第一次写出来是**假绿**的：`@SpringBootTest` 直调 service 不经过 `TenantContextFilter`，
> `TenantContext.get()` 返回 null → `store.forTenant(null)` 恒空 → 两遍都走库，等于自己跟自己比。
> 修法是显式置租户上下文，并加一条**零查询断言**当证据：第二遍若发出任何 SQL 就说明退化成了「库 vs 库」。

### P1-2 阶梯落档与折扣合并移出规则引擎 — 完成

判据是「这条规则需不需要**其它规则的结论**」：阶梯是纯标量分段函数、合并是一次 reduce，都不需要。

| | 改造前 | 现在 |
|---|---|---|
| 阶梯落档 | **每档一条 DRL 规则**（200 档 = 200 条规则 ≈ 7.6MB KieBase） | Java 查表 |
| 折扣合并 | O(N²) 规则自连接做 argmax（200 候选 = 4 万次比较） | O(N) 遍历 |

`BenefitEvaluator` 是**逐条复制 DRL 语义**，不是重新设计。Drools 路径由
`activity.marketing.rule-engine.java-benefit-eval=false` 保留，出问题翻回去即可。

**对拍方式**：`DroolsBenefitGoldenSetTest` 继承金标集、把开关翻成 false，让**同一组 41 条断言**
在两个实现上各跑一遍（console 测试数 89 → 131）。这比让两条路互相比对更强——
互相比对无法发现「两个都错得一样」，而金标里的期望值（阶梯边界 5/12/25、MAX 取 80、
PRIORITY 取 10、STACK 累加 60）是独立写死的。

**附带收益**：阶梯与折扣的 DRL **根本不再生成**，缓存里只剩资格判定那一类，D2 的组合爆炸面直接缩小。
`LadderKieBaseFootprintTest` 用 120 档跑 12 次跨档决策，断言金额正确且 KieBase 缓存只增 ≤2 项。

**一处刻意不改的怪异行为**：阶梯只设 `computedAmount`、不设 `amountComputed`，导致活动同时配了
阶梯和固定金额时，固定金额会**覆盖**阶梯结果。看起来像 bug，但它是当前线上语义——
金标用例「订单金额缺失 → 退回固定金额」正是靠它成立。在「只搬不改」的批次里顺手修好它
等于在没有需求依据的情况下改钱，要改必须单独立项、单独对拍。

### P1-3 资格判定移出规则引擎 — 完成（**没有引入 QLExpress，理由见下**）

计划原文是「引入 QLExpress 做资格判定」。查证后**没有这么做**——
`activity_condition.condition_tree_json` 里**已经存了结构化的条件树**，我们手里本来就有 AST。
把 AST 编译成字符串、再引一个表达式引擎去解析那个字符串，是绕远路：

| | 引 QLExpress | 直接解释树 |
|---|---|---|
| 依赖 | 新增运行时依赖 | 零 |
| 注入面 | QLExpress 3.x 默认放开反射，要靠白名单收 | 零（没有字符串要转义） |
| 维护 | 每加一个算子要同时改翻译器与求值器 | 一处 |

表达式引擎的价值在「让**非开发者**写逻辑」，而这里运营写的是条件树、不是表达式——那个价值在此处不存在。

`ConditionTreeEvaluator` 逐条对齐 `RuleConditionTranslator` emit 的 DRL 语义，重点是
**BETWEEN 是双闭区间**（与阶梯的 `[min,max)` 不同）与**否定算子的存在性护栏**（缺字段 → false → 淘汰）。

**一处新增的 fail-closed**：活动有受控约束却没有可用条件树时（JSON 损坏 / schema 漂移），
绝不能当「无条件通过」——那是 fail-open，直接超发。这种情况按「条件不可判定」淘汰并计入
`fallback{reason=condition-tree-unavailable}`。这条在开发中真的救了场：树解析一度失败，
表现是「本该命中的没命中」而不是「本不该命中的发了钱」。

**D2 的最终结果**：红包决策链路上**已经没有任何一步会编译 KieBase**——
`LadderKieBaseFootprintTest` 断言 120 档跑 12 次决策后缓存新增 **0** 项。
「按候选集拼 DRL」这个缓存键爆炸的根源在该路径上彻底消失。

### 前端 PR-5 · 活动工作台 — 完成（含一处后端契约修复）

`POST /activity-marketing/bulk-status` → `{succeeded[], failed[{activityId, reason}]}`。

两条刻意的语义：
1. **逐条独立事务**——一条失败不能回滚已成功的那些。「全成功或全失败」在这里是错的：
   运营要的是「尽量都下线，然后告诉我哪几个没成功」。
2. **部分失败一律 200**——它是正常结果不是错误，由前端渲染回执。

评审点名四份设计稿共同缺失的正是这个回执：只给「批量操作条」而不给部分失败反馈，
运营点完「批量下线 23 个」不知道到底成了几个。大促前这是最高危操作，
静默失败等于让运营以为活动停了、实际还在发钱。

#### 接前端时抓出的两个阻断项（都是 P0-4 的下游影响，此前没人往这看）

**① `GET /list` 会把同一活动返回成多行。** P0-4 之后编辑已上线活动会保留线上 v1 另建草稿 v2，
而 `list()` 无版本归并 → 同一 activityId 两行。旧 `ListView` 直接渲染这批行，
**Vue `:key` 与 `data-testid="activity-row-{id}"` 同时重复**。前端按 activityId 归并解决，
主版本取「正在服务的那一版」。

**② 批量下线打到草稿，线上版继续发钱。** `bulkChangeStatus` 传 `version=null` → 取最高版本 = 草稿 v2。
「批量下线 23 个」把 23 个草稿置成下线、线上版一个没停，回执还报全部成功——
正是这个功能要消灭的那类静默失败。先钉成红的：

```
BulkStatusTest.bulkOfflineHitsTheServingVersionNotTheDraft
  批量下线后不得再有 ONLINE 版本 ==> expected: <true> but was: <false>
```

契约改为 `items:[{activityId, version}]`，下线传当前 ONLINE 版、发布传要发的草稿版。
并补反向验证 `nullVersionHitsTheDraftInstead` 钉住「传 null 就会打到草稿」，防止被改回去。
`BulkStatusTest` 4 → **6** 条。

#### 工作台按「有没有真实数据源」裁的范围（D6）

规范里工作台有 8 列，其中 3 列后端根本没有数据源，一律**不画**而不是先画上等接口：

| 真做 | 降级 | 不做 |
|---|---|---|
| 生效窗甘特条（共享轴）· 跨页选择计数 · 批量上下线 · 密度切换 · 行点击开侧板 · 五态状态 | 额度（只显示声明数字，**不画量具**——`inventory` 落库后零读取，没有分子）· 类型（不虚构规范里那 8 种玩法）· KPI 只留「生效中 N/全部 M」 | 今日命中 · 回退率 sparkline（`DecisionMetrics` 标签里没有 activityId）· 批量撤销 10s（无 opId、无原状态；反向 bulk 会把没上过线的草稿**发布**出去）· 核销导出（无流水表） |

缺口不是消失，是记账：列表页放 D6 说明卡，写明待建 `GET /decision/v1/metrics` 与 `/by-activity`。

详细裁决表、与规范的四处偏离（详情按钮保留跳页 / 滚动锁阈值取 1280 / 撤销改持久回执 / 密度用 `data-density`）
见 [UI 决策记录](../console-ui-coupon-mechanics-0808-2251/DECISION_RECORD.md) 的 PR-5 实施记录。

### 前端 PR-6 · 玩法模板屏 — 完成（**后端零改动**）

起因是「活动类型还是太少了」。查证：枚举 5 个 code、`field-dict` 全返回，但实际只有
红包(1) / 买赠(5) 能用（前端 `enabledTypes` 过滤 + 后端 `create` 只放行这两个）。
根因是权益按类型硬编码，即决策记录里的「加一个玩法 = 五处齐动」。

**但现有 2 个类型配上 6 个可用条件字段，本来就能表达 8 种玩法**，只是没有名字。
所以这一屏走的是零后端改动的路：给已有能力起名字并给出起点（12 张卡 = 8 可用 + 4 不可用，
不可用的逐条写明缺什么，不写「敬请期待」）。

顺带抓到**第三个装饰字段**：编辑器的「随机金额」发放方式——`redPackageTakeType` 全链路零计算读取，
`computeAmounts` 只把固定金额抄给 `computedAmount`，**配了随机线上照样发固定值**。
按库存红线 B 的先例置灰 + 明示（保留选项而不是删掉，删掉会让人以为从没有过这个能力）。

至此已知的声明式装饰字段三个：`inventory`（已置灰）、`redPackageAmountUnit`（折扣类被此阻断）、
`redPackageTakeType`（本次置灰）。

**下一步（前端）**：PR-7~9（沙盘 / 看板 / 发布实验）。屏 5、屏 6 仍整屏无接口。

### 折扣型权益（按折数）— 完成

PR-6 把「折扣券配不出来」的边界摆明之后，接着把它做掉了。**这是本轮唯一一次真的改钱。**

**判别位复用 `redPackageAmountUnit`**（'折' = 折扣型，其余含 null/'元' 回落金额型），不新增判别列——
这个字段本来就一路搬到候选、快照与 DRL 上下文，只是从来没被计算读过。未知取值回落金额型，
保证历史行行为零变更。至此三个装饰字段里有两个变成了真字段（`redPackageAmountUnit` 承担形态判别，
新增列 `red_package_max_discount` 承担封顶），只剩 `inventory` 与 `redPackageTakeType` 仍是声明式。

**数学单独成类 `BenefitMath`，两条求值路径调同一个函数**——Java 的 `BenefitEvaluator` 与
生成的 DRL（`discount-compute-ratio` 规则 RHS 直接调它）。等价性靠构造而不是靠测试：
两边各写一遍取整逻辑迟早漂移，而漂移的表现是「同一张券在两条路上差几分钱」。

```
减免 = 订单金额 × (10 − 折数) / 10，2 位小数向下取整，再按封顶截断
```

三处刻意的取向，都是 fail-closed：

| 决定 | 理由 |
|---|---|
| **向下取整** | 这是往外发的钱。四舍五入会在半数情况下多发——单笔几厘无所谓，但那是系统性偏向 |
| **算不出来返回 null 而非 0** | 0 会以 0 元参与 MAX 竞争并可能挤掉别的活动 |
| **没有封顶 = 不可计算**（不是「不封顶」） | 写平面只管新写入；直接写库/历史数据会让读路径拿到 `cap=null`。把「没配封顶」解释成「不封顶」是越出问题越多发 |

**写平面同时收紧**：单位白名单（只放行 元/折）+ 折扣型强制封顶且折数 ∈ (0,10) + 折扣型不许配阶梯。
`redPackageAmountUnit` 此前是零校验自由文本，引擎开始读它之后，一个拼错的单位就能让活动按另一种形态发钱。

#### 落地中修掉的两处 fail-open（都是本次自己引入的）

1. **`legacyMax` 把折数当元发**。旧逻辑直接读 `redPackageAmount`，而折扣型往那里放的是折数——
   「打 8 折」在回退时变成「减 8 元」。回退不是罕见分支：引擎开关关闭、空决策、规则执行异常都会走到。
   反向验证：去掉修复，`RatioLegacyFallbackTest` 4 条全红。
2. **封顶为 0 反而不封顶**。原写法 `cap.signum() > 0 && off > cap` 让 `cap=0` 落进「不封顶」分支把全额发出去——
   配置里最保守的值产生最激进的结果。

#### 验证

| 层 | 手段 |
|---|---|
| 纯数学 | `BenefitMathTest` 20 例：向下取整的系统性 / 折数越界 / 封顶为 0 与负 / **没有封顶 = 不可计算** / 形态回落 |
| 金标集 | `DecisionGoldenSetTest.Ratio` 7 例，经继承在 **Java 与全 Drools 两条路各跑一遍**（41 → 48 例） |
| 写平面 | `BenefitFormValidationTest` 7 例闸门 |
| 回退路径 | `RatioLegacyFallbackTest` 4 例（真的把 `rule-engine.enabled` 关掉） |
| 线上链路 | docker 全栈：100 元 → 减 20 · 10000 元 → 封顶 50 · **99.99 元 → 减 19.99**（不是 20.00，向下取整的现场证据）· 无封顶的折扣券写入被 400 拒 |

#### 审查缺口已补（原为「三个角度从未执行」）

对抗式审查（4 角度 × 复核）**因会话额度中断，只有 1 个角度跑完、4 个复核 agent 全挂**，
所以那份报告里的 `confirmed: []` 不是「审查通过」。其提出的 4 条由我逐条自查
（读路径 fail-open 已修，另 3 条是前端未跟上，一并做掉），未执行的三个角度随后手工补齐：

| 角度 | 结论 |
|---|---|
| **parity**（DRL 与 Java 的 salience 交互） | 最刁的场景是「历史脏数据同时挂阶梯与折扣」——写平面拒绝这种组合，但直接写库/历史行可能有，而 Java 走 if/else、DRL 走 salience，顺序机制不同。已加金标用例（直接改库craft 该行），**两条路径同为「折扣覆盖阶梯」**，与固定金额覆盖阶梯的既有语义同源 |
| **data**（快照 / 迁移 / 新列） | 经验证据：decision 以 `ddl-auto=validate` 正常启动并通过**快照路径**服务折扣决策（10000 元 → 封顶 50），说明新列已由 console 建好且 `CandidateTemplate` 的封顶拷贝没漏。`CandidateTemplate` 是 record，漏传构造参数编译期即报错 |
| **edge**（边界与绕过） | ① **STACK 可超过订单金额**：两张一折券在 100 元订单上累加成 180。**这是既有语义、非折扣型引入**（两张 60 元固定券同样累加成 120），已写成金标用例记录，夹上限属改钱、需单独立项 ② **折数 9.995 落库被 scale=2 规整成 10.00** → 越界 → 算不出优惠，**fail-closed 而非多发**，已加用例钉住 ③ 编辑走同一个 `create()`，校验一致；bulk-status 只改状态、碰不到权益字段 |

仍未覆盖：Metaspace/性能维度（属 P1-4，与本次无关）。

### P0-2 explain 分离 — 完成（只做了一半，见下）
决策热路径默认 `explain=false`（不 emit trace），控制台试算显式 `true`。E2E 实测：decision `traces=0`，console 试算 `traces=2`，**金额一致**。

---

## 本轮新发现的两个缺陷（都是 docker E2E 抓出来的，单测漏了）

### ① 决策平面完全不解析租户（已修）

`MultiTenancyConfig` 里 `TenantContextFilter` 的 URL 模式只写了 `/activity-marketing/*`。
决策平面 `/decision/v1/*` 是 M1.1 才加的，模式没同步扩。

**后果**：header 档（auth 关闭）下决策平面**静默忽略 `X-Tenant-Id`**，所有请求落到兜底租户——
A 租户读到的是 dev-default 租户的活动，且没有任何报错。auth 档不受影响（`JwtTenantFilter` 挂在同时匹配两个平面的安全链上）。

**为什么单测没抓到**：decision 既有测试全跑在 `dev-default-enabled=true` 且不带 header 的前提下，
与「过滤器没生效」的表现完全一致——两种情况都解析成 dev-default，断言看不出差别。

**修法 + 回归**：URL 模式补 `/decision/v1/*`；`DecisionTenantHeaderTest` 刻意**关掉 dev-default**，
缺 header 必须 403——403 就是「过滤器确实挂在这条路径上」的证据。

### ② 缓存足迹 Gauge 恒为 NaN（已修）

`registry.gauge(name, obj, fn)` 对状态对象持**弱引用**。原实现传的是构造期临时创建的 `Supplier` lambda，
构造返回后即被 GC → 指标变 NaN。而 NaN 在面板上看起来只是「没数据」，是最难察觉的埋点失效。

**修法**：三个 Gauge 的状态对象一律用被服务强引用的 `cache` 本身。
**回归**：`DecisionMetricsTest` 加断言「Gauge 取值不得为 NaN」——原来只断言了 Gauge 存在，正是这个盲区放过了它。

---

## 未完成

### 有意偏离计划的一处
**P0-2 的另一半「编译移出请求线程」没做**，理由不是没时间：

计划原文是「热路径 `compileOrGet` 查不到即走 legacy + 计数」。但查证后发现——
资格求值失败时所有候选保持 `eligible=true`，`legacyMax` 会取**全部候选**的最大值，包括本该被淘汰的。
也就是说「miss → legacy」会在每次冷编译时**系统性超发**。用一个正确性回归换 P99，不划算。

**改到 P1-1 快照包**：那时缓存在发布侧预热完成，热路径必然命中，不需要这个降级分支。

### 按计划仍待做

| 项 | 状态 |
|---|---|
| P1-4 Metaspace churn 验证 | ❌ 未开始 |
| S 档权益模型（BenefitSpec + 前端 schema 表单） | ❌ 未开始（权益线主体，25–35 人日） |
| 前端 PR-7~PR-9（沙盘 / 看板 / 发布实验） | ❌ 未开始（PR-0~PR-6 已完成，见 [UI 决策记录](../console-ui-coupon-mechanics-0808-2251/DECISION_RECORD.md) 的实施记录） |
| **玩法扩容**（折扣/单价类）| ✅ **已完成（2026-08-09，分支 `feat/visual-tech-refresh`）**——PR-6 摆明的三条边界已全部拆掉：<br>· 随机金额：`BenefitEvaluator.drawRandom` + `BenefitMath.randomAmount`，**确定性随机**（SHA-256 派生自「活动+版本｜用户｜购物车指纹」）<br>· 第二件半价：决策入口 `SpuDiscountRequest` 补 `lines`（订单行含逐行单价，六参/七参构造保留），新增 `BenefitForm.NTH_ZHE`（unit=`件折`）<br>· 限时秒杀：新增 `BenefitForm.FIXED_PRICE`（unit=`价`）+ 写平面 `POST /{id}/claim` 原子扣减（`decrementInventory` 把判余量与减一压进同一条 UPDATE）<br>· 加价购：活动类型 6 + `AddOnPurchaseService` 两阶段（`/decision/v1/addon/{options,quote}`）<br>测试：`RandomAmountTest` 14 / `NthItemDiscountTest` 13 / `FixedPriceAndClaimTest` 9（含 100 线程抢 10 件的真并发压测）/ `AddOnPurchaseTest` 7 |
| 决策指标聚合接口 | ⚠️ **部分完成（2026-08-09）**——`GET /decision/v1/metrics`（按 scene/mode 的 count/mean/max + 回退计数）与 `GET /decision/v1/by-activity`（按活动命中量）已实现；`/fallback-rate` 未单独建端点（回退计数已含在 `/metrics` 里）。<br>⚠️ 两点与 PR-8 草案**形状不同**，照旧草案实现会打不通：① 是**单实例进程内视角**（无 `window` 参数、非时序数组），跨实例汇总仍看 Prometheus；② `by-activity` 返回 `{hits, tagCap, overCapTag}`，**activityId 标签有 200 的基数上限**（超出并入 `__over_cap__`）——把 activityId 直接当 Prometheus 标签是基数爆炸，序列数由运营建活动的手速决定 |

---

## docker 全栈 E2E 验证记录

```
DROOLS_AUTH_ENABLED=false DROOLS_DEV_DEFAULT_ENABLED=true \
  docker compose -f deploy/docker-compose.yml up --build -d
```

六个容器全部起来（console / decision / gateway / mysql / prometheus / grafana）。
decision 以 `ddl-auto=validate` + 只读账号 `decision_ro` 正常启动。

| 验证项 | 结果 |
|---|---|
| 前端 SPA `/ui/console` | HTTP 200，`<title>活动引擎控制台</title>` |
| 建活动（`storeId=1` + `orderAmount≥100` 双条件）→ 上线 | 成功 |
| 决策平面命中 | `hit=true amount=30.0 mode=rule-engine` |
| 不传 `storeId` | `hit=false`（fail-closed） |
| 金额不达标（99 < 100） | `hit=false` |
| **跨租户隔离**（同请求换 beta） | `hit=false` |
| explain 分离 | decision `traces=0` · console 试算 `traces=2` |
| 指标暴露 | `activity_decision_*` / `activity_rule_*` 全部可见 |
| Prometheus 抓取 | 3 条时间序列，按 `job`/`scene`/`mode` 正确分裂 |
| 缓存 Gauge | `entries=3.0` `hit_ratio=0.571` `weight_kb=1002.0`（非 NaN） |
| **快照构建与切换** | `[snapshot] 切换 tenant=acme bizLine=mall generation=-→1 活动数=2` |
| **P0-4 编辑不下线** | 上线命中 50 → 编辑成 v2 草稿 → **仍命中 50** → 发布 v2 → 命中 80，`ONLINE 版本数=1` |
| **快照物料来源** | `activity_decision_source_total{source="snapshot"}=2` / `{source="db"}=1`（db 那条是无快照的 beta 租户，正确回落） |
| 库存声明式 warnings | `库存（500）当前为声明式：决策链路不读取、不扣减…` |
| **全 Java 求值链路** | storeId 条件命中 30.0 · 缺 storeId fail-closed · 金额不达标不命中 |
| **控制台试算仍有 trace** | `eligible: ACT…` / `hit by MAX: ACT… amount=30.00` |
| **KieBase 缓存** | `entries=1.0`（红包链路零编译，只剩买赠那一类） |
| 回退计数器 | `fallback_total{reason="empty-decision"}=1.0` |

> 注：本次以 `DROOLS_AUTH_ENABLED=false` 验证（header 档），未验 Casdoor auth 档——
> 那需要本机起 Casdoor 并 provision 应用，见 `deploy.sh --provision-auth`。
