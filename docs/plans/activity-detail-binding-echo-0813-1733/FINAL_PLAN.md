# 实施计划 · 活动详情「绑定商品」回显性能优化

配套决策见同目录 `DECISION_RECORD.md`（D1–D9）。本计划可直接用 `/codex-review` 对照实际 diff 审查。

## Goals

1. 活动详情页 `getDetail` 首屏不再下发万级自动绑定行——`bindings` 收窄为「仅手动」，性能瓶颈（大结果集/序列化/DOM 扇出）从根上消除。
2. 详情「商品绑定」区改为两级：**店铺聚合**（storeId + 该店商品数/生效数，O(店铺数) 一次返回）→ 点「查看」**行内展开**该店铺下**服务端分页**的商品明细。
3. 商品名一页一次批量补（`findAllById`，无 N+1）；全链路 JPQL/派生查询保 `@TenantId` 租户隔离。

## Non-Goals

见 `DECISION_RECORD.md`「Non-Goals」：不改 EditorView 绑定回填、不引入店铺名字典、不做物化汇总表、不做 keyset 深分页、不改共享 SidePanel。

## 视觉方向与设计参考（沿用既有视觉语言）

沿用仓库既有设计系统（`frontend/src/shared/styles/tokens.css` 令牌 + `shared/ui/*` 原语），**不引入新风格**。具体映射：

- **店铺聚合行**：复用 `.binding-row` 的 `grid: auto minmax(0,1fr) auto`（`DetailView.vue:358`）。左 `Icon name="workflow"`（全站店铺既有约定）；中「店铺 #{storeId}」+ 小字「{spuCount} 件 · {effectiveCount} 生效」（`--fs-2xs`/`--text-faint`）；右「查看」按钮 + 展开态 `chevron-down`/`chevron-right`。`store_id=null` 显示「未指定门店」。
- **商品明细行**：直接复用现有 `.binding-row` + `.effective/.inactive` pill（`--green-soft/--green`、`--red-soft/--red`），零新样式即视觉一致；SPU 名 + 小字来源（手动/商品池）+ 生效/失效 pill。
- **分页条**：照 `ListView.vue:552-559`/`.pager`（`:731-734`）——「显示 X–Y，共 Z 项」+ `Icon arrow-left/arrow-right` 上下页 + `:disabled` 边界 + `aria-label`。放在**可滚动内容内**（不放固定 footer，避开 iOS safe-area 遮挡，移动端子代理结论）。
- **空/错**：`EmptyState icon="inbox"`；错误用行内可重试提示（复用 `Banner` 观感或 `.muted` + 重试按钮）。
- **令牌**：`--sp-1..4`、`--fs-2xs/xs/sm`、`--radius-sm/pill`、`--border`、`--bg-elev/bg-soft`、`--accent-soft`、`--touch-min:44px`。

## 路由与页面流

无新路由。改造发生在既有 `activity-detail`（`DetailView.vue`）页右侧 `aside.side-column` 的「商品绑定」Card。

```
进入 /console/activities/:id
  → getDetail 一次（现有），返回 manage/rules/.../bindings(仅手动)/storeCount/spuTotal
  → 渲染 BindingStores（自取店铺聚合 GET /binding-stores?version=）
     · 展示店铺行 + 每店计数
  → 点某店铺「查看」（accordion 展开）
     → BindingSpuList 自取该店首页 GET /binding-spus?version=&storeId=&page=0&size=10
     → 翻页 → 再取对应 page（按 storeId#page 缓存，AbortController 防竞态）
     → 再次点击店铺行 → 收起
```

## 组件树（复用 vs 新建）

```
DetailView.vue（改）
└─ aside.side-column
   ├─ Card「活动元数据」                         复用，不动
   ├─ <BindingStores :activity-id :version />    ★新建
   │   （替换原 :308-317 的内联 binding-list Card）
   │   ├─ Card「商品绑定 · {spuTotal}」          复用 Card
   │   ├─ Skeleton/EmptyState/错误重试            复用
   │   ├─ v-for 店铺行（复用 .binding-row 结构）   复用 Icon/Badge
   │   └─ <BindingSpuList/>（展开的店铺其下）     ★新建
   │       ├─ Skeleton/EmptyState/错误重试        复用
   │       ├─ v-for 商品行（复用 .binding-row + pill）
   │       └─ pager（复用 ListView .table-footer 范式）
   └─ .next-action                               复用，不动
```

- 新建组件置于 `frontend/src/console/binding/`（沿用 `condition-tree/`、`district/` 子目录约定）：`BindingStores.vue`、`BindingSpuList.vue`。
- 状态：`BindingStores` 内 `stores` + `expandedStoreId`（单店展开）；`BindingSpuList` 内 `items/total/page/loading/err` + `Map<`${storeId}#${page}`>` 缓存 + `AbortController`（仿 `DetailView.load:123-125`、`ListView.openDetail:277` 的晚到丢弃）。`version` 由 `detail.manage.version` 下发。

## 状态与边界（逐区）

| 区 | loading | empty | error | 特殊 |
| --- | --- | --- | --- | --- |
| 店铺聚合 | `Skeleton :rows="3"`（懒触发前占位可选） | `EmptyState`「没有绑定商品」 | 行内「加载失败，重试」按钮重拉 | `store_id=null`→「未指定门店」桶 |
| 商品明细（展开后） | 明细区内 `Skeleton :rows="4"` | 「该店铺暂无商品」 | 行内重试 | 失效行 `.inactive` pill；翻页 loading 时保留旧页避免闪 |

- 店铺聚合 fetch 在 `BindingStores` **挂载即发起**（`GET /binding-stores`）。**注意（评审 M2 纠正）**：`DetailView.test.ts:47-49` 的 stub 是「非 `/field-dict` 一律回 detail 对象」，挂载即 fetch 会命中 else 分支拿到对象而非数组 → 破坏存量用例。**必须在共享 `setup()` stub 增加 `/binding-stores`→`[]`、`/binding-spus`→分页体两分支**（一处 helper 改动覆盖全部存量用例）——这不是「避免改 stub」，而是「必须改这一处 helper」。
- 下钻 fetch **仅点击展开后才打**（可被 `fetch.mock.calls` 断言未点击时无 `/binding-spus`）。

## API 契约（新增两端点，挂 `ActivityMarketingController`，紧邻 `:198` detail）

```
GET /activity-marketing/{activityId}/binding-stores?version=
  200 → [ { storeId: Integer|null, spuCount: long, effectiveCount: long } ]   # O(店铺数)，不分页
  400 → 非法/不存在（复用现有 bad()/IllegalArgumentException→400）

GET /activity-marketing/{activityId}/binding-spus?version=&storeId=&page=0&size=10
  200 → { total: long, page: int, size: int,
          items: [ { spuId, spuName|null, price|null, bindSource, effective, poolId } ] }
  400 → 非法/不存在
```

- `version` 缺省 → `latestDraftVersion(activityId)`（D6）。`storeId` 后端 `@RequestParam(required=false) Integer`，**缺省即 null 桶**（D7）——前端 `storeId===null` 时 **URL 完全省略 `storeId` 参数**（不传 `&storeId=` 空串，否则 Spring 空串转 Integer 抛 400）；契约测试补一条「省略 storeId → 命中 null 桶」（评审 L4）。
- 后端每页恒 **2~3 条 SQL**：page 数据 + count + 一次 `findAllById` 批量名/价；无逐行 N+1（`BindingViewQueryCountTest` 钉死）。

### 仓库/服务改动

`ActivitySpuBindingRepository`（写平面，`activity-common`）新增：

```java
// 聚合：JPQL 投影，@TenantId 自动
interface StoreSpuCount { Integer getStoreId(); long getSpuCount(); long getEffectiveCount(); }
@Query("select b.storeId as storeId, count(b) as spuCount, " +
       "sum(case when b.effective = 1 then 1 else 0 end) as effectiveCount " +
       "from ActivitySpuBindingEntity b " +
       "where b.activityId = ?1 and b.version = ?2 and b.isDel = 0 group by b.storeId")
List<StoreSpuCount> aggregateStoresByVersion(String activityId, Integer version);

// 下钻分页：null-safe storeId，@Query 保 @TenantId；Pageable 自动 count
@Query("select b from ActivitySpuBindingEntity b where b.activityId = :aid and b.version = :v " +
       "and b.isDel = 0 and ((:storeId is null and b.storeId is null) or b.storeId = :storeId)")
Page<ActivitySpuBindingEntity> pageStoreBindings(@Param("aid") String aid, @Param("v") Integer v,
                                                 @Param("storeId") Integer storeId, Pageable pageable);
```

`ActivityMarketingService` 新增两个读方法 + DTO（`record StoreBindingView(Integer storeId, long spuCount, long effectiveCount)`、`record SpuBindingRow(Long spuId, String spuName, BigDecimal price, Integer bindSource, Integer effective, Long poolId)`、`record SpuBindingPage(long total, int page, int size, List<SpuBindingRow> items)`）。下钻：取 `Page` → 收集 `spuId` → `demoProductRepo.findAllById(ids)` 一次 → `Map<Long,DemoProductEntity>` 回填 name/price（join 不到 → name/price 为 null，前端回退裸 `SPU {id}`）。

> **接口投影兜底（评审 M3）**：本仓无 Spring Data 接口投影先例。`StoreSpuCount` 投影 + `sum(case…)` 理论可行（Hibernate 6.5，整型 CASE 返回 `Long`，`Long→long` 自动转），但若运行期报类型/映射异常，回退为 JPQL 构造表达式 `select new com...StoreBindingView(b.storeId, count(b), sum(case when b.effective=1 then 1 else 0 end)) ...`——此时 DTO 字段须用包装类型（`Integer storeId, Long spuCount, Long effectiveCount`，count/sum 是 `Long`，用 `long` 会解析不匹配）。`ActivityBindingViewTest` 是该模式的第一道验证，务必覆盖 null 店铺桶与 `effectiveCount` 口径。

`getDetail`（D1）：bindings 查询改 `findByActivityIdAndVersionAndBindSourceAndIsDel(activityId, v, 0, NOT_DEL)`；调 `aggregateStoresByVersion` 汇总 `storeCount = 行数`、`spuTotal = Σ spuCount`；`ActivityDetail` record 末位补 `int storeCount, long spuTotal`。

### 前端 API（`frontend/src/console/activityApi.ts`，紧跟 `getDetail:30`）

```ts
export function getBindingStores(id: string, version?: number, signal?: AbortSignal):
  Promise<ApiResult<{ storeId: number | null; spuCount: number; effectiveCount: number }[]>>
export function getBindingSpus(id: string,
  p: { version?: number; storeId: number | null; page: number; size: number }, signal?: AbortSignal):
  Promise<ApiResult<{ total: number; page: number; size: number; items: BindingSpuRow[] }>>
```
`BindingSpuRow` 类型加到 `shared/types.ts`。

## 响应式与移动端适配

沿用既有断点（`1280/1023/767/560/1180`），**不新立档**。

| 断点 | 策略 |
| --- | --- |
| `>1180`（桌面） | 维持双列，店铺聚合在右 `aside.side-column`（~280–340px）；accordion 就地展开明细（窄列，行用 `--fs-xs` 紧凑，pageSize=10） |
| `≤1180 且 >560`（平板/中屏） | `detail-grid` 已塌单列、`side-column` 变 static 全宽（`DetailView.vue:360`），店铺聚合与展开明细自动获全宽 |
| `≤560`（极窄） | 店铺行改上下两段（店铺名+计数一行，「查看」按钮 `grid-column:1/-1` 跨整行，仿 ListView 卡片范式）；pager 每键 ≥44×44 |

- **触控**：新增「查看」按钮与 pager 按钮补 `@media(pointer:coarse){ min-height/min-width: var(--touch-min) }`（DetailView 全页无 coarse 规则，必须自带）。pager 图标按钮宽度须 ≥44（ListView 现状 <44 的短板不照抄）。
- **可达性**：accordion 切换按钮加 `aria-expanded` + `aria-controls`；pager 用 `aria-label="上一页/下一页"` + `:disabled` 边界。
- 分页条在可滚动内容内（非固定 footer），规避 iOS safe-area。

## 文件级改动清单

**后端**
1. `activity-common/.../persistence/ActivitySpuBindingRepository.java` — 加 `StoreSpuCount` 投影、`aggregateStoresByVersion`、`pageStoreBindings`。
2. `activity-common/.../persistence/ActivitySpuBindingEntity.java` — `idx_sb_aid_ver` columnList 加 `store_id`（D9）。
3. `activity-console/.../service/ActivityMarketingService.java` — 新增 `bindingStores(id,version)`、`bindingSpus(id,version,storeId,page,size)` + 三个 DTO record；改 `getDetail`（bindings 收窄 + 摘要字段）；`ActivityDetail` record 末位 +2 字段。**构造器新增注入 `DemoProductRepository demoProductRepo`**（已核实现构造无此依赖，下钻补名要用；注意同步任何直接 `new ActivityMarketingService(...)` 的测试构造点）。
4. `activity-console/.../controller/ActivityMarketingController.java` — 加 `GET /{id}/binding-stores`、`GET /{id}/binding-spus`（沿用现有 try/catch→400）。

**前端**
5. `frontend/src/console/activityApi.ts` — 加 `getBindingStores`、`getBindingSpus`。
6. `frontend/src/shared/types.ts` — 加 `BindingSpuRow`（及聚合行类型）。
7. `frontend/src/console/binding/BindingStores.vue` — ★新建。
8. `frontend/src/console/binding/BindingSpuList.vue` — ★新建。
9. `frontend/src/console/pages/DetailView.vue` — 移除内联 binding-list（`:60` computed 与 `:308-317`），挂 `BindingStores`；相关 scoped 样式可留给复用。
10. `frontend/src/console/pages/ListView.vue:621` — `countOf('bindings')` → `Number(detail?.spuTotal ?? 0)`（收窄后 bindings 是手动子集，旧计数会偏小；`getDetail` 返回 `Record<string,unknown>`，`.spuTotal` 是 `unknown`，渲染前须转 number 并兜底，评审 L5）。

**测试与文档**
11. 后端：新增 `ActivityBindingViewTest`、`BindingViewQueryCountTest`；扩 `TenantIsolationTest`。**已核实**：`ActivityDetail` record 全仓唯一构造点是 `ActivityMarketingService.java:496`（改 getDetail 时一并补末位实参即可，无测试位置构造需改）；`GrantLedgerTest.java:150/303` 建的是**手动**绑定（`SpuBinding(1, spu)`），故 `:287` 的 `.bindings().get(0)` 在收窄后照常工作，**无需改**。
12. 前端：扩 `DetailView.test.ts`（多分流 stub + 新 describe）；新增 `BindingSpuList.test.ts`（分页/缓存/竞态）。
13. `frontend/e2e/data-testid-contract.md` — 补新 testid（`binding-store-<id>`、`binding-spu-<id>`、`binding-view`、pager）。
14. `docs/plans/.../BREAKING-CHANGES.md` — 记 `getDetail.bindings` 语义收窄 + record 新增字段。

## 实施步骤（按依赖排序）

1. **后端仓库层**：加两个查询 + 投影 + 索引列（步骤 1、2）。`./mvnw -pl activity-common install -DskipTests`（下游要用新 jar，见坑「-pl 拿旧 jar」）。
2. **后端服务/控制器**：DTO + 两读方法 + getDetail 改造 + 两端点（步骤 3、4）。
3. **后端测试**：`ActivityBindingViewTest`、`BindingViewQueryCountTest`、`TenantIsolationTest` 增例、修 `DecisionScopeGoldenTest`/核 `GrantLedgerTest`（步骤 11）。跑 `./mvnw -pl activity-common install -DskipTests && ./mvnw -pl activity-console test`。
4. **前端 API/类型**：`activityApi.ts` + `types.ts`（步骤 5、6）。
5. **前端组件**：`BindingStores.vue` + `BindingSpuList.vue`（步骤 7、8），DetailView 挂载与内联移除（步骤 9），ListView 计数改读（步骤 10）。
6. **前端测试**：扩 `DetailView.test.ts` + 新 `BindingSpuList.test.ts`（步骤 12）。跑 `cd frontend && npx vitest run && npm run typecheck`。
7. **文档**：testid 契约 + BREAKING-CHANGES（步骤 13、14）。
8. **独立计划评审**已在计划阶段完成；实现后走 `diff-review` 或 `/codex-review` 对照本计划。

## 测试策略

**后端**
- `ActivityBindingViewTest`（`@SpringBootTest @ActiveProfiles("h2")`）：`storeAggregationCounts()`（跨 2 店铺 + null 店铺组不崩）、`drillDownPagination()`（page1/page2 切片与 total、无重叠）、`effectiveAndBindSourceFilter()`（混 `effective=0`/`isDel=1`，聚合含失效但 `effectiveCount` 只数生效；下钻展示全部）、`emptyStoreAndMissingActivity()`（空返回空、非法 id→400）。
- `BindingViewQueryCountTest`（照 `DecisionQueryCountTest`）：`SessionFactory.getStatistics().getPrepareStatementCount()`，同页 M=1 vs M=5 SPU **语句数相等**（钉「页查询 + count + 一次批量名 = 常数」防 N+1），`assertTrue(count>0)` 防假绿。
- `TenantIsolationTest#bindingViewIsolation()`：TenantA 建绑定，切 TenantB 查 A 的 activityId → 空/fail-closed（照 `:76-91`）。**红线**：误用 native SQL 时此例应变红。
- 控制器契约（`ActivityMarketingAddOnAliasTest` 同款 MockMvc）：两新路由 200/JSON 形态/分页参数透传。

**前端**
- `DetailView.test.ts`：`setup` 的 fetch stub 扩多分流（`/field-dict`/`/binding-stores`/`/binding-spus`/else→detail，照 `ListView.test.ts:35-45`）。新 describe：渲染店铺聚合行与每店计数、**点「查看」才触发 `/binding-spus`**（先断 `fetch.mock.calls` 无该 URL，click+`flushPromises` 后有且带 `storeId=`/`page=`，照 `EditorView.test.ts:631-643`）、翻页 `page` 递增 + 商品名回填、空店铺空态、失效行标记与 `effectiveCount`。
- `BindingSpuList.test.ts`：分页缓存命中免请求、AbortController 晚到丢弃、错误重试。
- 为店铺行/商品行补 `data-testid`（断言需要，现缺）。

**移动端验收矩阵**：≤560 与 ≤768 两视口下（e2e/playwright 或手测）——店铺行不横向溢出、「查看」与 pager 触控目标 ≥44px、accordion 展开后页面可正常滚动、`e2e-tablet-smoke` 的详情横向溢出 ≤4px 仍绿。

## 验收标准

1. 详情页 `getDetail` 响应不再含自动绑定行；`bindings` 仅手动；`storeCount/spuTotal` 正确。
2. 店铺聚合一次返回（O 店铺数），点「查看」才拉该店分页明细；翻页只拉对应页。
3. 后端 `BindingViewQueryCountTest` 证同页语句数与 SPU 数无关（无 N+1）；`TenantIsolationTest#bindingViewIsolation` 绿。
4. `./mvnw -pl activity-common install -DskipTests && ./mvnw -pl activity-console test` 全绿；`cd frontend && npx vitest run && npm run typecheck` 全绿。
5. **移动端**：≤560 视口下店铺行与展开明细无横向溢出、触控目标 ≥44px（至少一条移动端验收项）。
6. EditorView 编辑既有活动仍能正确回填全部手动绑定（无静默丢绑定）。

## 风险与回滚

| 风险 | 缓解 |
| --- | --- |
| `getDetail.bindings` 语义收窄破坏未知消费方 | grep 已列全消费面（D1）；record 字段保留、末位增量；BREAKING-CHANGES 存档 |
| `GrantLedgerTest:287` 依赖 bindings 首元素 | 实现时核对其绑定为手动；若为自动则改测试读新端点 |
| 误用 native SQL 绕过 `@TenantId` 跨租户泄漏 | 全 JPQL/派生查询；`TenantIsolationTest#bindingViewIsolation` 守 |
| `ddl-auto:update` 改已存在索引不可靠 | 全新 demo DB 定义即生效；live/存量库文档注明需手工 `ALTER`（本仓常态是可重建 DB） |
| 单店铺 SPU 上万时 OFFSET 深分页退化 | 列为已知落差；`idx_sb_aid_ver(+store_id)` 缓解；后续可上 keyset(cursor=spuId) |
| `group by` 仍全表扫该活动版本行 | demo 量级足够；后续物化汇总表（Non-Goal） |
| 新交互污染 DetailView 存量 stub | 店铺聚合懒触发 + 下钻仅点击后打，存量单分流 stub 用例不受影响 |

**回滚**：后端两端点纯增量、可独立回滚；getDetail 改动是「加字段 + 收窄一处查询」，回退即恢复原查询与去掉两字段；前端组件为新增，DetailView 恢复内联 binding-list 即可。无数据迁移、无 DDL 破坏性变更（仅加索引列）。
