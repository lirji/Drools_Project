# 实施计划 · 商品绑定「选店铺→勾商品」picker

配套决策见同目录 `DECISION_RECORD.md`（D1–D7）。

## Goals

1. EditorView 绑定区**手动模式**新增「从店铺勾选商品」内联 picker：点开→选店铺→勾该店商品→append 进 `dr.spu`。
2. 新增 demo 店铺表（含店铺名）+ 跨租户造数（`__dev__`/`acme`），让 picker 有店铺名与商品可选。
3. 新增两个目录浏览端点（列店铺 / 列某店商品，服务端 keyword+分页）。
4. **保存格式一字节不变**（仍 `dr.spu` 的 `{storeId,spuId}`）；保留手填兜底行；编辑回填目录外 SPU 不丢。

## Non-Goals

见 `DECISION_RECORD.md`。要点：不引入新 bindMode、商品池模式不动、不改保存格式与 `activity_spu_binding`、不接真实店铺服务、不给 e2ev-* 造数、目录端点不挂 decision。

## 视觉方向与设计参考（沿用既有视觉语言）

沿用 `DistrictPicker` 家族 + `BindingSpuList` 的既有视觉，**不引入新风格**：

- **入口按钮**：manual 模式下、手填 `DynRowTable` 上方一个「从店铺勾选商品」按钮（`Button variant="ghost"` + `Icon name="workflow"`），点击 toggle 内联面板（仿 `DistrictPicker.vue:28` 的 `open` ref）。
- **面板内两栏**（≥768）/ 堆叠（<768）：
  - 左「店铺列表」：仿 `BindingStores` 的 store-row（`Icon workflow` + `storeName||('店铺 #'+id)` + `productCount 件`），单选高亮 `.checked{background:--accent-soft}`（仿 `DistrictTreeNode.vue:96`）。
  - 右「该店商品多选」：搜索框（整段照 `DistrictTree.vue:134-145`：`Icon search` + sr-only label + `type=search` + clear + `font-size:--fs-lg`=16px 防 iOS 缩放）+ 商品勾选行（方形 `checkbox accent-color:--accent` 仿 `DistrictTreeNode.vue:118` + `spuName||('SPU '+id)` + `¥price` 仿 `BindingSpuList.vue:104-112`）+ 服务端分页条（仿 `BindingSpuList` pager，放可滚内容内）。
- **已选回显 + 确认**：面板底部「已选 N」计数 + 分组 chips（按 `店铺#id` 分组，仿 `DistrictPicker.vue:103-123`，每 chip 带 `×`）；「加入绑定」按钮把去重后的 `{storeId,spuId}` append 进 `dr.spu`。
- **令牌**：`--sp-1..3`、`--fs-2xs/xs/sm/lg`、`--radius-sm/pill`、`--accent-soft/accent-line`、`--bg-soft/bg-elev/bg-hover`、`--border-ctl`(控件≥3:1)、`--focus-ring`、`--green/green-soft`、`--red/red-soft`、`--touch-min:44px`。

## 路由与页面流

无新路由。改造在 `EditorView.vue` 的 `activity-binding` Section（步骤③）内。

```
编辑器 manual 模式
  → [从店铺勾选商品] 按钮（默认收起，不拉数据）
  → 点开 → 惰性拉 GET /store-picker/stores（当前租户）
  → 选店铺 → 拉 GET /store-picker/stores/{id}/products?keyword=&page=
  → 勾商品（可跨多店，多次勾）→ 已选 chips 累积
  → [加入绑定] → 去重 append {storeId,spuId} 到 dr.spu → 面板收起
  → 手填 DynRowTable 里可见新增行，仍可增删改
提交/回填/完成度：读写同一个 dr.spu，一字不改
编辑既有活动：loadForEdit 回填 → 目录内 SPU 进 picker 已选态、目录外 SPU 落手填行
```

## 组件树（复用 vs 新建）

```
EditorView.vue（改：绑定 Section 内接入 picker，dr.spu 提交/回填格式不变）
└─ activity-binding Section (manual 模式)
   ├─ <StoreProductPicker @append="onPickerAppend" />   ★新建（frontend/src/console/binding/）
   │   ├─ 入口按钮 + open ref（惰性拉）        复用 Button/Icon
   │   ├─ 面板：左店铺列表                     复用 Icon/Skeleton/EmptyState；仿 BindingStores store-row
   │   ├─ 面板：右商品多选 + 搜索 + 分页        复用 Skeleton/EmptyState/Icon；仿 DistrictTree 搜索 + BindingSpuList 商品行/pager
   │   └─ 已选 chips + 加入绑定 → emit('append', 去重后的 {storeId,spuId}[])
   └─ 手填兜底 <DynRowTable :rows="dr.spu">    复用（保留，spu-row-input testid 不变）
```

- 纯逻辑（去重、目录内外判定、已选分组）抽到 `storeProductPickerLogic.ts`（仿 `districtLogic.ts` 可单测）。
- 新 API 加到 `activityApi.ts`；类型加到 `shared/types.ts`。

### 实现纪律（硬约束，评审 #1/#2/#4——违反即回归，非可选润色）

1. **picker 不用裸 `v-model="dr.spu"`，改 `emit('append', rows)`**。`dr.spu` 的脏值通路必须显式：`onPickerAppend` 处理器里对每条按 `(storeId,spuId)` 复合键判重后 **`dr.spu.push(...)`**（或按键 `splice` 移除），并显式调 `markDirty()`。理由：DistrictPicker（`DistrictPicker.vue:74`）根节点 `@click.stop @input.stop` 截停冒泡、脏值全靠 `districtCodes` computed setter 显式 `markDirty`（`EditorView.vue:130-133`，`EditorView.test.ts:721-745` 钉死）；裸 v-model 到 reactive 数组没有这条通路——不截停会「保存后点开看一眼就重铸 requestId」，截停又不置 dirty 会「加了商品却被幂等短路」。
2. **禁止 `model.value = 重建数组`**。`DynRowTable`（`DynRowTable.vue:41-46`）靠 `props.rows.push()/splice()` 原地改数组 + WeakMap 按行对象身份做 key；整表重写会让回填的目录外 SPU（990011）当场蒸发、且已有行 key 全失效串值。picker **只做 push / 按键 splice**，保留既有元素引用。
3. **提交映射 `:534-535` 必须加去重步**（数据*形状*不变，仍 `{storeId,spuId}`）：picker append 与手填自由输入可能重复同一 `(storeId,spuId)`，`saveManualBindings` 不去重会存两条。在 `.map(...)` 后按复合键去重。**「映射代码一字不改」是错的**——形状不变、代码要改。完成度 `:246` 不动（仍读 `dr.spu`）。

## 状态与边界（逐区）

| 区 | loading | empty | error | 特殊 |
| --- | --- | --- | --- | --- |
| 店铺列表 | `Skeleton :rows` | `EmptyState`「该租户暂无可选店铺」 | 行内可重试 | 空目录租户（acme/e2ev 未造数）→ 空态 + 引导用手填 |
| 商品多选 | `Skeleton` | 「该店铺暂无商品」/ 搜索无结果 `EmptyState icon=search` | 行内可重试 | 翻页 loading 保留旧页防闪 |
| 已选 | — | 「未选择商品」 | — | 目录外回填 SPU 在手填行、不进已选 chips |

- 面板惰性拉（点开才 fetch），带 AbortController 防竞态（仿 `BindingSpuList`）。
- 商品分页缓存 `storeId#page`（切页命中免请求）。

## API 契约（新增 `StorePickerQueryService` + 两端点，挂 `ActivityMarketingController` 紧邻 `:211-236`）

> 命名用 `store-picker`/`StorePicker`（评审 #8）——避免与能力展示中心的「catalog」（`src/demos/catalog.ts`、`e2e-catalog-v2.mjs`）撞词误导。

```
GET /activity-marketing/store-picker/stores
  200 → [ { storeId:int, storeName:String|null, productCount:long } ]   # 当前租户 @TenantId 自动；不分页
GET /activity-marketing/store-picker/stores/{storeId}/products?keyword=&page=0&size=20
  200 → { total:long, page:int, size:int, items:[ { spuId, spuName, price, onShelf } ] }
```

### 后端改动

- **新建** `activity-common/.../persistence/DemoStoreEntity.java`（D4：`storeId` PK + `@TenantId` + `storeName` + `onShelf`；`idx_ds_on_shelf`）+ `DemoStoreRepository.java`（JpaRepository）。
- **`DemoProductEntity`** 加索引 `idx_dp_store=(tenant_id,store_id)`。
- **`DemoProductRepository`** 加（`storeId is not null` 防 nullable 列产出 null 组，评审 #5）：
  ```java
  interface StoreProductCount { Integer getStoreId(); long getProductCount(); }
  @Query("select p.storeId as storeId, count(p) as productCount from DemoProductEntity p " +
         "where p.onShelf = 1 and p.storeId is not null group by p.storeId")
  List<StoreProductCount> aggregateStores();
  @Query("select p from DemoProductEntity p where p.storeId = :sid and p.onShelf = 1 " +
         "and (:kw is null or lower(p.spuName) like lower(concat('%', :kw, '%')))")
  Page<DemoProductEntity> pageStoreProducts(@Param("sid") Integer sid, @Param("kw") String kw, Pageable pageable);
  ```
  （投影 + null-safe @Query + Pageable 自动 count 与已跑通的 `ActivitySpuBindingRepository.java:36-62` 同构，评审 #5 确认可编译可跑）
- **新建** `activity-console/.../service/StorePickerQueryService.java`（平级 `DistrictQueryService`）：`stores()` = `aggregateStores()` + `demoStoreRepo.findAllById(storeIds)` 一次批量补店名（无 N+1，join 不到店名 null）；`products(storeId,kw,page,size)` = `pageStoreProducts` → 映射 DTO。
- **`ActivityMarketingController`** 加两端点（try/catch→400，storeId `@PathVariable`，keyword/page/size `@RequestParam`）。
- **`ActivityDemoSeeder`**（D5）：`run()` 遍历 `[getDevDefault(), "acme"]` 逐租户 `runWith`；`seedTenant`：`if(storeRepo.count()>0) return`；`__dev__` 保留 9101-9104@store1 + pool(id=1)，**新增** store 2（如「折扣店」）+ 2 商品；`acme` 造 2 店 × 数商品，不叠 pool。新增 `demoStoreRepo` 注入。

### 前端改动

- `activityApi.ts` 加 `listPickerStores()`、`listPickerProducts(storeId,{keyword,page,size})`。
- `shared/types.ts` 加 `PickerStore{storeId,storeName,productCount}`、`PickerProduct{spuId,spuName,price,onShelf}`、`PickerProductPage`。
- 新建 `frontend/src/console/binding/StoreProductPicker.vue` + `storeProductPickerLogic.ts`。
- `EditorView.vue`：绑定 Section manual 分支接入 `<StoreProductPicker @append="onPickerAppend">`（入口按钮 + 惰性面板），保留手填 `DynRowTable`；新增 `onPickerAppend`（去重 push + markDirty，见「实现纪律」）。`loadForEdit`(`:481-484`) 回填不变——手填 `DynRowTable` 本就承载全部回填行（含目录外 990011），picker 只是「新增录入」的旁路，不参与回填、不重写 `dr.spu`。**提交映射 `:534-535` 加去重步（形状不变）；完成度 `:246` 不动。**

## 响应式与移动端适配

沿用既有断点（`1024/768/560`）、`--touch-min:44px`、`(pointer:coarse)` 各组件自补、`min-width:0`+`dvh` 上限、iOS 输入 16px。picker 是独立 SFC，样式靠 Vue scoped 天然隔离（它挂在「商品绑定」Section、**不在 `.fg` 里**，EditorView 的 `.form :deep(.fg>label>input)` 够不到它——评审 #8 纠正：原「用 `>` 防 :deep 泄漏」的理由不适用，删去）。

| 断点 | 策略 |
| --- | --- |
| `≥1024` | EditorView 已单列、绑定区全宽；面板两栏（店铺列表 | 商品多选）并排 |
| `768–1023` | 面板两栏并排仍可（宽度够）；`.sel/.tree` 用 `max-height:N dvh + overflow-y:auto` |
| `<768` | 面板两栏**改上下堆叠/手风琴**（选店铺→下方展开该店商品，仿 BindingStores）；避开历史「窄屏三栏并排撑破」教训 |
| `≤560` | 强制堆叠；店铺项/商品勾选项整行触控 ≥44px；搜索框 16px；已选 chips 区 `max-height:30dvh` 滚动 |

- 触控：店铺行/商品勾选行/搜索 clear/chip × 各补 `(pointer:coarse){min-height/width:44px}`（全局兜底压不过 scoped）。
- 内联展开（非模态）天然绕开 iOS `visualViewport` 键盘遮挡（内容随页面滚，非 fixed）。
- 手填 `DynRowTable` 的 slotted input 无 16px：**顺手补一条**（可选，见风险表），避免手填时 iOS 缩放。

## 文件级改动清单

**后端**
1. `activity-common/.../persistence/DemoStoreEntity.java` ★新建
2. `activity-common/.../persistence/DemoStoreRepository.java` ★新建
3. `activity-common/.../persistence/DemoProductEntity.java` — 加 `idx_dp_store`
4. `activity-common/.../persistence/DemoProductRepository.java` — 加投影 + 两查询
5. `activity-console/.../service/StorePickerQueryService.java` ★新建 + DTO record
6. `activity-console/.../controller/ActivityMarketingController.java` — 加两端点 + 注入 `StorePickerQueryService`
7. `activity-console/.../ActivityDemoSeeder.java` — 多租户多店造数 + 注入 `DemoStoreRepository`
7b. `activity-console/src/test/.../FixedPriceAndClaimTest.java` — 补 `seed-demo-data=false`（评审 #3：它现在没设、会真跑 seeder）

**前端**
8. `frontend/src/console/activityApi.ts` — 加 `listPickerStores`/`listPickerProducts`
9. `frontend/src/shared/types.ts` — 加 `PickerStore`/`PickerProduct`/`PickerProductPage`
10. `frontend/src/console/binding/StoreProductPicker.vue` ★新建
11. `frontend/src/console/binding/storeProductPickerLogic.ts` ★新建
12. `frontend/src/console/pages/EditorView.vue` — 绑定 Section 接入 picker（保 dr.spu 契约）

**测试与文档**
13. 后端：`StorePickerViewTest`、`StorePickerContractTest`、`TenantIsolationTest#storePickerIsolation`、`DemoSeederTest`（开 seeder）
14. 前端：`StoreProductPicker.test.ts` + `EditorView.test.ts` 新 describe（**补 spuBindings 断言** + 目录外回填保真 + 多店 + 搜索 + 去重）
15. `frontend/e2e/data-testid-contract.md` — 补新 testid；`BREAKING-CHANGES.md`（新表/端点/seeder 均增量）

## 实施步骤（按依赖排序）

1. **后端数据层**：DemoStoreEntity/Repository、demo_product 索引与查询（步骤 1-4）。`./mvnw -pl activity-common install -DskipTests`。
2. **后端服务/控制器/seeder**：StorePickerQueryService + 端点 + seeder 多租户（步骤 5-7）。
3. **后端测试**：StorePickerViewTest / StorePickerContractTest / TenantIsolationTest+ / DemoSeederTest（步骤 13）。`./mvnw -pl activity-common install -DskipTests && ./mvnw -pl activity-console test`。
4. **前端 API/类型**（步骤 8-9）。
5. **前端 picker 组件 + 逻辑**（步骤 10-11），EditorView 接入（步骤 12）。
6. **前端测试**（步骤 14）。`cd frontend && npx vitest run && npm run typecheck`。
7. **文档**（步骤 15）。
8. 实现后 `diff-review` 或 `/codex-review` 对照本计划；docker 重建 console+gateway（含 seed-demo-data，新表 console 建）后浏览器实测 acme/__dev__ 两租户 picker。

## 测试策略

**后端**
- `StorePickerViewTest`（h2）：`listStoresReturnsAll`（2 店字段正确）、`listStoreProductsFiltersByStore`（只回该店）、`keywordFilters` + `pagination`（切片/total/无重叠）、`emptyTenantReturnsEmpty`（空目录租户返回空、非 500）、`offShelfExcluded`（on_shelf=0 不列）。
- `StorePickerContractTest`（MockMvc，照 `BindingViewContractTest`）：路由 200/JSON 形态、keyword/page/size 透传与默认值。
- `TenantIsolationTest#storePickerIsolation`（照 `bindingViewIsolation`）：A seed、切 B 列店/列商品为空——**红线**：误用 native SQL 变红。
- `DemoSeederTest`（开 `seed-demo-data=true`，照 `DistrictSeederTest`）：逐租户 `runWith` 后 count>0、店铺表有数据、幂等 `run()` 再跑 count 不变、A 看不到 B。
- **`FixedPriceAndClaimTest`（评审 #3）**：补 `seed-demo-data=false` 后仍绿；若不补，则验收时显式跑它确认新 seeder 不让它启动即挂。
- （可选）`StorePickerQueryCountTest`：列某店商品语句数与商品数无关（防 N+1）。

**前端**
- `StoreProductPicker.test.ts`（照 DistrictPicker/DistrictTree 纯组件测法，props/stub 注入目录）：选店铺→拉商品、勾选 `update:modelValue`、搜索按 URL 分流（照 `BindingSpuList.test.ts`：断 URL 带 keyword、缓存命中不重复请求、AbortController 晚到丢弃）、空目录空态、去重。
- `EditorView.test.ts` 新 describe：
  1. `选店铺→勾商品→提交含 {storeId,spuId}`（**补当前缺失的 body.spuBindings 断言**）。
  2. `回填目录外 SPU 落手填、submit 一字不差`（**头号金标**：detail stub `bindings:[{bindSource:0,storeId:77,spuId:888888}]`，回填后可见 + submit 仍含 `{77,888888}`）。
  3. `多店多商品`（两条各归各 storeId）。
  4. `picker + 手填去重`（勾 9101 又手填 9101 → spuBindings 无重复）。
  5. `切 pool 再切 manual 不串 / 完成度读 dr.spu`。
  - 目录**惰性拉**（点开才 fetch）→ 存量单分流 stub 用例不受污染（D2）；新用例才 stub 目录端点。

**移动端验收矩阵**：≤560 与 ≤768 视口——面板堆叠不横向溢出、店铺/商品项与 chip × 触控 ≥44px、搜索框 16px 不触发 iOS 缩放、`e2e-tablet-smoke`/`e2e-phone-smoke` 仍绿。

## 验收标准

1. manual 模式点「从店铺勾选商品」→ 选店铺 → 勾商品 → 加入绑定 → 手填表出现对应行；提交 `spuBindings` 含所勾 `{storeId,spuId}`。
2. 编辑既有活动（含目录外 SPU 如 990011）→ 该 SPU 在手填行可见 → 直接保存后绑定一条不少（后端 `getDetail().bindings` 仍含它）。
3. `acme` 与 `__dev__` 两租户下 picker 都有店铺+商品可选（seeder 造数生效）；空目录租户显示空态且可用手填。
4. 后端 `TenantIsolationTest#storePickerIsolation` 绿；`DemoSeederTest` 逐租户幂等绿；`./mvnw -pl activity-common install -DskipTests && ./mvnw -pl activity-console test` 全绿。
5. `cd frontend && npx vitest run && npm run typecheck` 全绿；**新增 body.spuBindings 断言存在**。
6. **移动端**：≤560 视口下面板堆叠无横向溢出、触控 ≥44px（至少一条移动端验收项）。
7. 商品池模式、既有 e2e（`spu-row-input`）零改动仍通过。

## 风险与回滚

| 风险 | 缓解 |
| --- | --- |
| 编辑回填静默丢目录外 SPU（发钱事故） | 目录外 SPU 落手填兜底行、原样提交；EditorView.test 用例 2 钉死 |
| picker 挂载拉目录污染存量 stub | 惰性拉（D2），存量单分流 stub 不受影响 |
| picker 引入第二权威破坏保存格式 | 硬不变量：picker 只写 `dr.spu`；补 spuBindings 断言守卫 |
| 误用 native SQL 绕过 @TenantId | 全 JPQL；`storePickerIsolation` 守 |
| seeder 多租户写生产库幂等 | 逐租户 `count()` 判空（@TenantId 分租户）；`DemoSeederTest` 验幂等；新表 dev 自动建、生产需手工 DDL |
| 移动 9101-9104 破坏 store1 断言 | 保留不动，多店靠新增 store 2 |
| e2e 点 `spu-row-input`（不在闸门） | 手填兜底行沿用同一 testid；主动 grep `frontend/e2e/*.mjs` 核对 |
| 手填 DynRowTable input 无 16px（iOS 缩放） | 可选顺手补一条 coarse 规则 |

**回滚**：新表 + 目录端点 + seeder 多租户化 + 前端 picker 均**纯增量**——端点独立可撤、seeder 可退回单租户、`dr.spu` 契约不变故 picker 可退回纯 `DynRowTable`。无数据迁移、无破坏性 DDL。
