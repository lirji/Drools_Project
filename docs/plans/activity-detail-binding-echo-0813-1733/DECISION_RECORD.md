# 决策记录 · 活动详情「绑定商品」回显性能优化

- 日期：2026-08-13
- 触发问题：自动化绑商品后，一个活动版本的 `activity_spu_binding` 可能有 O(店铺×SPU) 万级行。`ActivityMarketingService.getDetail`（`activity-console/.../service/ActivityMarketingService.java:492`）全量返回 `bindings`，前端 `DetailView.vue:308-317` 扁平 `v-for` 渲染，导致详情页加载卡（DB 大结果集 → JSON 序列化整包 → 前端造上千 DOM 节点，三层同时买单）。
- 目标 UX：详情先展示**店铺聚合**（店铺 + 该店绑定商品数），点某店铺「查看」再**分页**展示该店铺下的绑定商品明细。
- 来源：`frontend-plan` 工作流，5 个只读子代理（需求·数据语义 / UI·UX / 前端架构+后端 API / 移动端 / 测试·风险）并行调查后综合。

---

## 关键事实（五方共识，均带代码位）

- **绑定表爆炸源是自动绑定**：`bind_source=1`（商品池 × 店铺物化，`ActivityPoolMatchService`），随商品上下架翻 `effective`；手动绑定 `bind_source=0` 是运营手敲的，天然小（O(手填数)）。`ActivitySpuBindingEntity.java:40-50`。
- **`getDetail` 用「最高未删除版」= 草稿基线**（`latestVersionRow` → `manage.getVersion()`，`ActivityMarketingService.java:493-495`），**不是线上版**。聚合与下钻必须复用同一 version，否则数字对不上。
- **无店铺名表**：全仓 `store_id` 是裸 `Integer`（`ActivitySpuBindingEntity.java:31`、`DemoProductEntity.java:36`）。EditorView/ValidateView 也只用「店铺ID」数字。
- **商品名在 `demo_product.spu_name`**（`DemoProductEntity`，PK=`spuId`，带 `@TenantId`）。`JpaRepository.findAllById(spuIds)` 走 PK `IN` + `@TenantId` 自动，一次补全一页。
- **`getDetail.bindings` 的消费面（grep 全量）**：`DetailView.vue:60/308`（本次替换）、`EditorView.vue:481` `filter(bindSource===0)` 重建编辑基线（**红线**）、`ListView.vue:621` `countOf('bindings')`、`GrantLedgerTest.java:287`、`DecisionScopeGoldenTest.java:210/222`（位置构造）。
- **`ActivitySpuBindingEntity` 继承链带 `@TenantId`**（`TenantScopedEntity`），JPQL/派生查询自动追加 `tenant_id` 谓词；**native SQL 会绕过它 → 跨租户泄漏（红线）**。
- **现成可复用**：`SidePanel.vue`（三档响应式抽屉）、`ListView` 的 `.table-footer/.pager` 分页范式、`.binding-row` + `.effective/.inactive` pill、`EmptyState/Card/Badge/Skeleton/Icon`；店铺场景既有图标约定用 `workflow`（无 store 图标）。
- **DetailView 全页无 `@media(pointer:coarse)` 触控放大规则**，新按钮必须自带 `min-height:44px`。

---

## 决策

### D1 · getDetail 瘦身：收窄 `bindings` 为「仅手动」+ 末位新增摘要字段 `storeCount`/`spuTotal`（备选 B'）

| 备选 | 是否解决性能 | EditorView | GrantLedgerTest 编译 | 契约兼容 | 结论 |
| --- | --- | --- | --- | --- | --- |
| A 删 bindings 字段 | ✅ | ❌ 丢编辑基线 | ❌ 失败 | 差 | 否 |
| B 截断到 N 条 | ✅ | ❌ 静默丢手动行→发钱事故 | ✅ | 中 | **危险，否** |
| C 原样保留 + 仅加端点 | ❌ getDetail 仍下发万级行 | ✅ | ✅ | 好 | **不满足目标，否** |
| **B' 收窄为仅手动 + 摘要字段** | ✅ auto 行移出 getDetail | ✅ 免改 | ✅ 字段保留 | 好（末位增量） | **选中** |

- **裁决理由**：架构子代理与测试子代理的分歧本质是「截断 vs 收窄」的混淆。**收窄 ≠ 截断**：手动行一条不少（EditorView `filter(bindSource===0)` 拿到的正是全部手动），只移除它本就不用的 auto 行（池绑定走 `poolRefs`）。auto 行正是爆炸源，移出 getDetail 才真正解决「加载慢」。测试子代理担心的「截断丢手动」对 B' 不适用。
- **落地**：`getDetail:500` 的 bindings 查询从 `findByActivityIdAndVersionAndIsDel` 改为已存在的 `findByActivityIdAndVersionAndBindSourceAndIsDel(activityId, v, 0, NOT_DEL)`（`ActivitySpuBindingRepository.java:19`）；`ActivityDetail` record 末位追加 `int storeCount, long spuTotal`（由新聚合查询汇总），位置向后兼容。
- **破坏面处置**（已直接 grep 核实）：`EditorView.vue:481` 免改；`ListView.vue:621` `countOf('bindings')` 改读 `detail.spuTotal`；`GrantLedgerTest.java:150/303` 建的是**手动**绑定（`SpuBinding(1, spu)`）→ 收窄后 `:287` 的 `.bindings().get(0)` 照常工作，**无需改**；`ActivityDetail` record **全仓唯一构造点是 `ActivityMarketingService.java:496`**（子代理声称的 `DecisionScopeGoldenTest:210/222` 位置构造并不存在），末位加字段只改这一处。
- **契约变更**：`bindings` 语义从「全量」变「仅手动」，属有意的响应契约变更，写进 BREAKING-CHANGES 段。

### D2 · 商品名批量补：用 `JpaRepository.findAllById`，不新增仓库方法

`DemoProductEntity` PK=`spuId`，`findAllById(pageSpuIds)` = PK `IN` + `@TenantId` 自动，**一次查询补全一页**，无 N+1。避免架构子代理提议的多余 `findBySpuIdIn`（agent-brief 第六条：不造只有一个实现的抽象）。

### D3 · 下钻载体：行内 accordion 展开，不用 SidePanel、不改共享组件

- **冲突**：UI·UX 子代理主 accordion（SidePanel ≥1280 强制 push、无 prop 可 override、DetailView 无父 grid 让列 → 错位；用它须改共享组件或重构布局，风险高）；移动端子代理主 SidePanel sheet（担心 accordion 撑长页面）。
- **裁决**：移动端的顾虑建立在「客户端全量分组」前提上；改为 **D4 服务端有界分页**后，accordion 展开只有 ~10 行 + pager，页面不会被撑长，顾虑消解。故取 **accordion**：非模态、吃窄 aside、随 `≤1180` 自动全宽、无焦点陷阱负担、不动共享 `SidePanel`（回滚面最小）。

### D4 · 分页：服务端分页（硬要求），不客户端分组

万级行不能全量下发——这是本次的根本前提。UI·UX/移动端曾假设「客户端对已返回的 bindings 分组」，被性能要求否决。因此店铺聚合与每页商品明细各是**独立 fetch**，各自需要 loading/empty/error 态（覆盖 UI·UX 那条基于客户端数据的「无独立 loading」结论）。

### D5 · 聚合口径：`isDel=0` 全计（含失效）+ 附 `effectiveCount`；下钻展示全部行并标失效

- 对齐现状 `bindings.length`（现在就含 `effective` 0/1，`DetailView.vue:313` 已展示生效/失效）。聚合 `spuCount = count(isDel=0)`，另给 `effectiveCount = count(isDel=0 且 effective=1)`，供「N 件 · X 生效」。
- 下钻查询**不加 effective 过滤**，逐行带 `effective/bindSource` 让运营自查，与现状信息量一致。

### D6 · 版本一致性：新端点 `version` 缺省 = `latestDraftVersion`（与 getDetail 同源），前端显式回传 `detail.manage.version`

后端 `v = version != null ? version : latestDraftVersion(activityId)`；前端把详情响应里的 `manage.version` 回传，防两次调用间草稿漂移。

### D7 · `store_id = null`：归「未指定门店」桶

聚合 `group by b.storeId` 自然产生 null 组；前端渲染「未指定门店」。下钻用 null-safe `@Query`（`(:storeId is null and b.storeId is null) or b.storeId = :storeId`），避免派生查询把 `storeId=null` 编成恒假的 `store_id = null`。

### D8 · 仓库归属：写平面 `ActivitySpuBindingRepository`（`JpaRepository`），全 JPQL/派生查询

R17 的 `*ReadRepository extends Repository` 是 **decision 只读平面（物理只读账号）专用**；console 是写平面、本就用 `JpaRepository`，不新建只有一个实现的只读投影接口。红线：**只用 JPQL/派生查询保 `@TenantId`，禁 native SQL**。

### D9 · 索引：扩 `idx_sb_aid_ver` 为 `(tenant_id, activity_id, version, store_id)`

下钻谓词与 `group by store_id` 变全索引命中。`ddl-auto:update` 对**修改已存在索引**不可靠 → 对全新 demo DB（本仓常态）定义即生效；live/存量库需手工 `ALTER`，写进风险段。`effective/is_del` 低基数留残余过滤，不进索引。

---

## Non-Goals

- 不改 EditorView 的绑定回填逻辑（D1 收窄后它拿到的正是它要的手动集）。
- 不引入店铺名字典（无数据源；沿用 storeId 数字标识，与 EditorView/ValidateView 一致）。
- 不做物化店铺汇总表（`group by` 全表扫该活动版本行的成本，列为后续；本次 demo 量级足够）。
- 不做 keyset/cursor 深分页（单店铺 SPU 上万才需要；OFFSET 够用，列为已知落差）。
- 不改共享 `SidePanel.vue`。
