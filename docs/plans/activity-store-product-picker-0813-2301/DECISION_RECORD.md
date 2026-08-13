# 决策记录 · 新建/编辑活动「商品绑定」选店铺→勾商品 picker

- 日期：2026-08-13
- 需求：EditorView 绑定区的**手动模式**从「手填 storeId/spuId 数字」改成「先选店铺→再从该店铺勾选商品」的 picker。
- 用户已拍板：①新增 demo 店铺表（含店铺名）+ 跨租户造数，picker 展示店铺名；②保留手填一行作兜底；③支持多店铺、每店多商品。
- 来源：`frontend-plan` 工作流，5 个只读子代理（需求·边界 / UI·UX+可复用选择器 / 架构+后端数据模型·seeder·API / 移动端 / 测试·风险）并行调查后综合。

---

## 关键事实（五方共识，带代码位）

- **保存格式是硬不变量**：`EditorView.vue:534-535` 提交 `spuBindings = dr.spu.filter(spuId非空).map({storeId,spuId})`；`ActivityCreateRequest.SpuBinding(Integer storeId, Long spuId)`（`:93`）；后端 `saveManualBindings`（`ActivityMarketingService.java:1065-1081`）逐条落 `bindSource=0/effective=1`。**picker 只能是 `dr.spu` 的另一个编辑视图，不能引入第二权威**——这样提交/回填/完成度三处一字不改，也不波及已实现的 `binding-stores`/`binding-spus` 决策取数。
- **提交层天然不丢目录外 SPU**：`:535` 只按「spuId 空不空」过滤、与目录无关。「静默丢 990011」只可能发生在「picker 决定不把回填来的目录外 SPU 放进 `dr.spu`」这一层。
- **空目录是默认体验**：前端 dev 默认租户是 `acme`（`useTenantStore.ts:10`），而 seeder 只造 `__dev__`（`ActivityDemoSeeder.java:52`）→ 默认打开编辑器 `demo_product` 恒空。`@TenantId` 使各租户目录互相隔离。
- **现有 9101-9104 全在 store 1**，`e2e-validation.mjs:307` 有 `storeId eq 1` 断言、商品池 e2e 依赖它 → 多店铺靠**新增**而非重分配。
- **seeder 对测试零依赖**：全部后端测试 `seed-demo-data=false`（30+ 处 grep 确认，含 `ActivityMarketingFlowTest.java:46`），seeder bean 从不实例化；`poolAutoBind`（`:146-176`）inline 自造数据。
- **无独立店铺表**（`store_id` 裸 Integer）、**无 multiselect 组件**、**无「浏览目录」端点**（现有 `binding-stores/binding-spus` 按 activityId 查**已绑定**，语义不符）。
- **可复用范式**：`DistrictPicker/DistrictTree/DistrictTreeNode`（内联展开 + 搜索 16px 防缩放 + 方形 checkbox 勾选行 + 已选 chips 分组 + 纯逻辑层可单测 + coarse 44px）；`BindingSpuList`（商品行「名+价+徽标」+ 服务端分页 + cache + AbortController + join 不到回退裸 `SPU {id}`）；`ActivityMarketingController.java:211-236` + `CatalogQueryService`(平级 `DistrictQueryService`)。

---

## 决策

### D1 · 交互形态：DistrictPicker 式**内联展开面板**（非 SidePanel）

- **冲突**：架构子代理推荐「SidePanel 抽屉」，但把 `DistrictPicker` 误当抽屉——UI·UX 与移动端两个子代理实读证明 `DistrictPicker` 是**内联展开**（`DistrictPicker.vue:100` `<DistrictTree v-if="open">` 内联渲染，非 SidePanel）。
- **裁决**：内联展开面板。理由三方一致——(a) 与同页地域 Section 的内嵌范式一致（运营只学一套「选择」交互）；(b) 移动端「一套内联形态吃下所有断点」是本仓已过 390/768 零横向溢出 e2e 护栏的路线；(c) SidePanel 模态会遮住表单其余 Section、与 `<768` 导航抽屉产生 z 序耦合。架构子代理真正想要的「open ref + defineModel + 逃生门」壳层惯用法正是 DistrictPicker 的内联壳，与此裁决不矛盾。

### D2 · 目录**懒加载**（点开 picker 才拉，仿地域 `areaType===2`）

- picker 挂载即 fetch 会污染 EditorView 全部存量 fetch stub（`dictOk`/`captureCreates`/`backendReturns` 三个 helper 兜底各不同，picker 会收到全错形状）——与 DetailView 特性评审的 M2 同坑。
- **裁决**：目录不在挂载时拉，改为「手动模式下点『从店铺勾选商品』展开面板才拉」（照 `EditorView.vue:142-146` 的 `areaType===2` 惰性 load）。存量单分流 stub 用例天然不受影响，无需改三个 helper。

### D3 · 数据源：Arch B（`demo_product` 驱动店铺列 + `demo_store` 供名）

| | Arch A：demo_store 权威店铺维度 | **Arch B（选中）**：demo_product 驱动 |
| --- | --- | --- |
| 店铺列来源 | `select from DemoStoreEntity` | `group by demo_product.store_id` + `findAllById` 补店名 |
| 空店(0商品) | 会列出 | 不列出（选空店无意义） |
| 店名缺失 | 权威表恒有 | join 不到 → 回退「店铺 #id」 |
| 与现有一致性 | 一般 | **高**（完全复刻 `bindingStores`+`findAllById` 回退，`ActivityMarketingService.java:551`） |

- **裁决 Arch B**：picker 只从「有商品的店」勾商品，productCount 内生，店名是装饰性（有优雅回退，与已上线 `BindingStores.vue:35` 一致）。`demo_store` 表**仍建**——它是店名来源 + seeder 造数目标。

### D4 · demo_store 数据模型：最小内联 `@TenantId` 替身表，逻辑引用不加物理外键

```java
@Entity @Table(name="demo_store", indexes={@Index(name="idx_ds_on_shelf", columnList="tenant_id,on_shelf")})
class DemoStoreEntity { @Id Integer storeId; @TenantId String tenantId; String storeName; Integer onShelf; }
```
- 沿用 `DemoProductEntity` 的最小内联风格（**不**继承 `TenantScopedEntity`：替身表不需软删/双时间戳/`@JsonPropertyOrder` 键序）。无物理外键，与「join 不到就回退」的容错范式一致。
- `demo_product` 加索引 `idx_dp_store=(tenant_id,store_id)`（按店列商品是主路径；现有 `idx_dp_price/idx_dp_category` 不含 store_id）。**新增整表/新命名索引在 `ddl-auto:update` 可靠**（区别于「改已存在索引 columnList」的不可靠）；decision `validate` 只校验表/列不校验索引，且 `@EntityScan` 会校验 `demo_store` 表存在 → **console 先建表**（既有不变量，无新风险）。

### D5 · seeder 多租户、逐租户幂等；保留现有数据不动

- `run()` 遍历 demo 租户 `[__dev__, acme]`，每租户 `TenantContext.runWith` + `if(storeRepo.count()>0) return`（`@TenantId` 使 count 按租户计，天然分租户幂等）。
- **保留 `__dev__` 现有 9101-9104 全在 store 1 + pool(id=1) 不动**（e2e/池断言依赖）；多店铺靠**新增** store 2 及其商品。`acme` 造 2 店 × 数商品，pool 只留 `__dev__`（acme 的 87 活动是库里既有数据、非 seeder 产物）。
- `e2ev-*` 临时 e2e 租户**不 seed**（`CommandLineRunner` 启动期跑一次，抓不到运行期随机租户）——这类租户下 picker 目录为空，靠**手填兜底**兜住（D6）。
- **测试回归面（评审 #3 纠正）**：console 侧 36 个 `@SpringBootTest` 里 **35 个**设 `seed-demo-data=false`（seeder bean 不实例化），唯独 `FixedPriceAndClaimTest.java:34` 没设、吃 base `application.yml:61` 的 `true` → 它会**真跑**新的多租户 seeder（含新建 `demo_store` 行）。它只碰 `activity_manage`、不读 demo 表，大概率仍绿，但**必须**：① 给 `FixedPriceAndClaimTest` 补 `seed-demo-data=false`（稳妥）；② 验收里显式跑它，别当已被保护。新增一个开着 seeder 的 `DemoSeederTest`（照 `DistrictSeederTest`）验逐租户造数 + 幂等 + 跨租户隔离。

### D6 · picker 与手填共写同一 `dr.spu`；目录外 SPU 落手填兜底；`storeId#spuId` 去重

- `bindMode==='manual'` 下渲染 = 「从店铺勾选商品」入口（内联面板）+ **保留的手填 `DynRowTable`**。picker 勾选确认后 append `{storeId,spuId}` 进 `dr.spu`；手填行照旧可增删改。二者都只是 `dr.spu` 的视图 → 提交/回填/完成度零改动。
- **回填目录外 SPU（头号金标）**：`loadForEdit` 回填时，能在当前租户 `demo_product` 定位到的进 picker 已选态、**定位不到的（如 990011）落手填兜底行**并保持可见、原样提交。绝不因「目录里选不到」从 `dr.spu` 剔除（=编辑一次删掉发钱范围，与 DistrictPicker「未知码单列保留」同构）。
- **去重**：picker append 与手填并存，提交前按 `(storeId, spuId)` 复合键去重（后端 `saveManualBindings` 不去重）。
- **手填兜底行沿用 `spu-row-input` testid**（稳定契约，被 `EditorView.test.ts:350/364` 与多个 e2e 填值）→ 门禁零改动。

### D7 · API：新 `StorePickerQueryService` + 两个目录浏览端点，全 JPQL 保 `@TenantId`

> 命名用 `store-picker`（评审 #8）——避免与能力展示中心的「catalog」（`src/demos/catalog.ts`、`e2e-catalog-v2.mjs`）撞词。

```
GET /activity-marketing/store-picker/stores
    → [{storeId, storeName, productCount}]                      # 当前租户，不分页（店少）
GET /activity-marketing/store-picker/stores/{storeId}/products?keyword=&page=&size=
    → {total,page,size,items:[{spuId, spuName, price, onShelf}]}  # 服务端 keyword+分页
```
- 新建只读 `StorePickerQueryService`（平级 `DistrictQueryService`，**不塞进臃肿的 `ActivityMarketingService`**）；查询加到 `DemoProductRepository`(JpaRepository) + 新 `DemoStoreRepository`(JpaRepository)——**不新建 console 侧 ReadRepository**（那是 decision 物理只读账号专用）。全 JPQL/派生查询保 `@TenantId`，**禁 native**（误用则 `storePickerIsolation` 测试变红）。
- 商品侧服务端 keyword+分页（复刻 `BindingSpuList` 范式，size 默认 20）——demo 量级虽小，但契约与前端组件可直接复用，且规模不确定时更稳。店铺列表不分页。

---

## Non-Goals

- 不引入新的 `bindMode`（picker 只是 manual 模式的录入视图）；商品池模式（`dr.pool`）完全不动。
- 不改 `activity_spu_binding` 结构与保存格式（仍 `{storeId,spuId}`）。
- 不接真实店铺/商品服务（新增 demo 店铺表是唯一来源）。
- 不改 `store_id` 类型（仍 `Integer`）。
- 不给 `e2ev-*` 临时租户静态造数（靠手填兜底）。
- 目录端点只挂 console 编辑态，不挂 decision 平面。
