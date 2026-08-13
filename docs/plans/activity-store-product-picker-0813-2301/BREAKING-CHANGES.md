# 对外可见变更清单 · 商品绑定「选店铺→勾商品」picker

改前必读。整体是**纯增量**（新表 / 新端点 / 新组件 / seeder 扩展），保存契约与既有读路径一字未变。

## 1. 新增数据表 `demo_store`（DDL）

- 字段：`store_id`（PK，Integer，业务键）、`tenant_id`（`@TenantId`）、`store_name`、`on_shelf`。无 `is_del`/时间戳（最小替身表，与 `demo_product` 同风格）。
- `store_id` 是**全局业务键**（单列 PK），与 `demo_product.spu_id` 同理：**跨租户不可复用同一 store_id**（seeder 里 `__dev__` 用 1/2、`acme` 用 3/4）。与 `demo_product.store_id` 是逻辑引用，**无物理外键**。
- `demo_product` 新增索引 `idx_dp_store=(tenant_id,store_id)`。
- **DDL 生效**：console `ddl-auto:update` 自动建表/加索引（新表、新命名索引可靠）；decision `@EntityScan` 会扫到 `DemoStoreEntity` → `ddl-auto:validate` 要求 `demo_store` 表存在 → **必须 console 先建表再起 decision**（既有不变量，与 demo_product 同）。存量库需先重建 console。

## 2. 新增两个只读目录浏览端点（编辑态用）

```
GET /activity-marketing/store-picker/stores
  → [ { storeId:int, storeName:String|null, productCount:long } ]        # 当前租户 @TenantId 自动；不分页
GET /activity-marketing/store-picker/stores/{storeId}/products?keyword=&page=0&size=20
  → { total:long, page:int, size:int, items:[ { spuId, spuName, price, onShelf } ] }
```

- 语义**不同于** `/binding-stores`、`/binding-spus`（那是「某活动**已绑定**了什么」）——这两个是「当前租户**有哪些店 / 店里有哪些在架商品可勾选**」的目录浏览。
- 数据源 `demo_product`(+`demo_store` 供名)；只列在架（`on_shelf=1`）；店名 join 不到回退 null（前端显示「店铺 #id」）。全 JPQL 保 `@TenantId`。
- 实现走新 `StorePickerQueryService`（平级 `DistrictQueryService`），控制器构造新增一个依赖 → 直接 `new ActivityMarketingController(...)` 的测试需同步加参（已改 `BindingViewContractTest`/`ActivityMarketingAddOnAliasTest`）。

## 3. `ActivityDemoSeeder` 多租户造数（仅 `seed-demo-data=true` 生效）

- 从「只造 `__dev__`」扩为遍历 `[__dev__, acme]` 逐租户造数（前端 dev 默认租户是 `acme`，原来它目录为空）。逐租户 `storeRepo.count()` 幂等。
- `__dev__` 保留原 9101-9104 全在 store 1（e2e/池断言依赖）+ pool(id=1)，**新增** store 2 与 2 个商品；`acme` 造 store 3/4 + 4 个商品，**不叠 pool**。`e2ev-*` 临时租户不造（靠手填兜底）。
- **测试回归面**：`FixedPriceAndClaimTest` 原本没设 `seed-demo-data`、会真跑 seeder → 已补 `seed-demo-data=false`。其余测试本就关闭该开关。

## 4. 前端 EditorView 绑定区（手动模式）新增 picker——保存格式不变

- manual 模式下新增内联「从店铺勾选商品」picker（`StoreProductPicker.vue`），**保留**原手填 `DynRowTable`（`spu-row-input` testid 不变 → e2e/单测零改动）。
- **不引入新 `bindMode`**；picker 勾选结果按 `(storeId,spuId)` 去重后 append 进 `dr.spu`，与手填共写同一数组。提交契约 `spuBindings:[{storeId,spuId}]` **一字未变**（提交映射内新增一步去重，形状不变）。
- **编辑回填目录外 SPU 不丢**：回填的绑定行（含不在 demo_product 的 SPU，如 990011）落手填行、原样提交；picker 只是「新增录入」旁路，绝不重写 `dr.spu`。
- 新 testid：`store-picker-{toggle,panel,store-<id>,product-<spuId>,prev,next,confirm}`（已录入 `data-testid-contract.md`）。
