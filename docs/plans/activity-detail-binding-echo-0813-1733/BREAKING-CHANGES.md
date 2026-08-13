# 对外可见变更清单 · 活动详情「绑定商品」回显优化

改前必读。共 1 处响应契约语义收窄 + 2 个新增字段 + 2 个新端点。

## 1. `GET /activity-marketing/{id}`（详情）响应 `bindings` 语义收窄：全量 → 仅手动

- **变更**：`ActivityDetail.bindings` 此前是该版本**全部**未删除绑定行（手动 + 商品池自动物化）；现收窄为**仅手动绑定**（`bindSource=0`）。
- **原因**：自动绑定（商品池 × 店铺物化）可达万级，全量下发拖慢详情首屏。它改由两个新端点按店铺聚合 + 分页取。
- **字段/取值**：`bindings` 数组的元素形状**一字节没变**，只是**行数变少**（不再含 `bindSource=1` 的行）。
- **已知消费方处置**：
  - `EditorView.vue`（编辑基线）本就 `filter(bindSource===0)`，收窄后拿到的正是全部手动集 —— **行为不变，免改**。
  - `ListView.vue` 侧板「绑定 SPU N 个」改读新摘要字段 `spuTotal`（否则会少数自动绑定）。
  - `GrantLedgerTest` 建的是手动绑定，`.bindings().get(0)` 仍有值 —— **不受影响**。
- **谁会受影响**：任何**直接依赖详情响应里 `bindings` 含自动绑定行**的下游（本仓已 grep，无此消费方）。若你在仓外有此依赖，改用 `/binding-stores` + `/binding-spus`。

## 2. `ActivityDetail` record 末位新增两个字段（纯增量）

- `int storeCount` —— 该版本绑定覆盖的店铺数（含仅有失效行的店铺）。
- `long spuTotal` —— 该版本**全部**未删除绑定行数（含自动 + 失效），供详情标题与列表页计数。
- 末位追加，位置向后兼容；record 全仓唯一构造点是 `ActivityMarketingService.getDetail`。

## 3. 新增两个只读端点（纯增量，可独立回滚）

```
GET /activity-marketing/{id}/binding-stores?version=
  → [ { storeId: Integer|null, spuCount: long, effectiveCount: long } ]   # O(店铺数)，不分页
GET /activity-marketing/{id}/binding-spus?version=&storeId=&page=0&size=20
  → { total: long, page: int, size: int,
      items: [ { spuId, spuName|null, price|null, bindSource, effective, poolId } ] }
```

- `version` 缺省 = 草稿基线（`latestDraftVersion`，与详情同源）。
- `storeId` **省略**即命中「未指定门店」桶（`store_id IS NULL`）；调用方 `storeId=null` 时**不要传空串**（后端 `@RequestParam Integer` 空串转换会 400）。
- 聚合口径：`spuCount` 计全部未删除行（含失效，与详情标题一致），`effectiveCount` 只数 `effective=1`。
- 下钻不过滤 `effective`，逐行带 `effective/bindSource` 供运营自查；商品名/价一页一次批量补（`demo_product` 查不到 → `null`，前端回退裸 SPU 编号）。

## 4. 索引变更（DDL）

`activity_spu_binding` 的 `idx_sb_aid_ver` 从 `(tenant_id, activity_id, version)` 扩为 `(tenant_id, activity_id, version, store_id)`。全新 demo 库定义即生效；**存量/生产库需手工 `ALTER`**（`ddl-auto:update` 对修改已存在索引不可靠）。decision 侧 `ddl-auto:validate` 不校验索引，不受影响。
