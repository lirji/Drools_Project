# Test Plan

## 测试目标

验证新增前端页面不改变现有后端行为，并能稳定调用当前仓库已经存在的 Drools 演示端点。测试重点是请求结构、响应展示、错误路径和关键 Drools 效果可见。

## 测试前提

- 后端启动端口为 `8081`，来自 `src/main/resources/application.yml`。
- 推荐使用 H2 profile 做本地验收，避免依赖 MySQL：
  - `./mvnw spring-boot:run -Dspring-boot.run.profiles=h2`
- 本次规划阶段未执行命令，因为用户限制唯一可写目录为规划目录，Maven 会写 `target/`。

## 单元测试设计

若采用方案 A，前端是静态 JS，建议将逻辑拆成可测试的纯函数：

- `formatJson(value)`
  - 输入对象，输出缩进 JSON。
  - 输入字符串文本，保持文本展示。
- `parsePayload(text)`
  - 合法 JSON 返回对象。
  - 非法 JSON 返回错误对象，不发请求。
- `buildRequest(demo, payload)`
  - 对固定 path 正确生成 URL。
  - 对路径参数如 `/hot/run/{name}`、`/campaign/{id}/check` 正确替换。
- `summarizeResponse(demoId, response)`
  - `/discount/calculate` 提取 `totalAmount`, `finalAmount`, `discountReasons`。
  - `/pipeline/audit` 提取 `auditTrail`。
  - `/tms/compare` 提取 logical/regular 两阶段 alerts。
  - `/campaign/list` 提取 `campaignId`, `status`, `cached`。
- `normalizeError(status, body)`
  - JSON error body 显示 `error` 字段。
  - 文本 body 原样显示。

验收标准：

- 合法 JSON 能发起请求。
- 非法 JSON 不发请求，并在页面显示解析错误。
- 路径参数缺失时页面阻止请求并给出提示。

## 集成测试设计

以下测试通过浏览器或 HTTP 自动化执行均可。

### 基础规则

- `/hello`
  - 请求：`Customer{name:"Alice", age:20, vipLevel:0, yearsSinceRegistration:0}`
  - 期望：HTTP 200，响应包含 `rulesFired`，页面展示 rules fired。

### 订单折扣

- `/discount/calculate`
  - 请求：VIP 2 + 老用户 + 总额 660。
  - 期望：HTTP 200，`finalAmount` 为 README 示例中的 `516.8` 或 JSON 序列化等价值，`discountReasons` 包含 VIP、满减、老用户。

### 风控推荐

- `/risk/evaluate`
  - 电子产品无保险。
  - 期望：`recommendations` 包含“建议加购意外险”。
- 电子产品有保险。
  - 期望：不出现保险推荐。

### 流水线与审计

- `/pipeline/run`
  - 大额 VIP 订单。
  - 期望：返回 `Cart`，包含折扣和推荐。
- `/pipeline/audit`
  - 同样请求。
  - 期望：`auditTrail` 非空，事件包含 `OBJECT_INSERTED`、`GROUP_PUSHED`、`MATCH_FIRED`。
  - 页面按 `sequence` 顺序展示。

### CEP

- `/fraud/check`
  - 同 customer 5 分钟内 3 个 `OrderEvent`。
  - 期望：`alerts` 至少 1 条，包含对应 `customerName`。
- 时间跨度超过 5 分钟。
  - 期望：不触发或少于 burst 告警，具体以 README 示例为准。

### 热加载

- `/hot/upsert`
  - 合法 DRL。
  - 期望：HTTP 200，`status = registered`。
- `/hot/run/{name}`
  - 使用上一步 name。
  - 期望：返回 `firedCount` 和 `cart`。
- `/hot/upsert`
  - 非法 DRL：`this is not valid drl`
  - 期望：HTTP 400，页面显示后端返回的错误文本和行号。

### 持久会话

- `/loyalty/start`
  - sessionId `frontend-demo`
  - 期望：`tier = NONE`, `totalPoints = 0`
- `/loyalty/frontend-demo/purchase`
  - amount 1000
  - 期望：`totalPoints` 增加，`tier` 经过规则升级，`unlockedBadges` 展示。
- `GET /loyalty/ghost`
  - 期望：HTTP 404，页面显示 `ErrorResponse.error`。

### TMS

- `/tms/compare`
  - 默认 hot 95, cool 50。
  - 期望：logical 的 `phase2Alerts` 为空；regular 的 `phase2Alerts` 仍包含 Alert。
  - 页面必须并排展示 logical 和 regular。

### 后向链

- `/backward/contains`
  - Office -> House -> City -> Country。
  - 查询 Office in Country。
  - 期望：`answers[0].contained = true`，`ancestorsLookup` 包含 House/City/Country。

### 护栏

- `/guard/runaway`
  - maxFires 10。
  - 期望：`fireCount = 10` 或与后端语义一致，`guard` 描述包含 maxFires。
- `/guard/timeout`
  - timeoutMillis 50 或 200。
  - 期望：请求返回而不挂死，页面显示 elapsedMillis。
- `/guard/canary`
  - allowedReleases 默认或只 stable。
  - 期望：`skipped` 包含 canary 规则；允许 canary 后 skipped 变化。

### DMN

- `/dmn/price`
  - 输入 Customer + orderAmount。
  - 期望：响应 `decisions` 包含 `Discount Rate`、`Final Price`、`Membership Tier`。

### 营销活动

- `/campaign/create`
  - 合法资格 DRL。
  - 期望：HTTP 200，`status = ACTIVE`。
- `/campaign/{id}/check`
  - 符合条件的 `UserProfile`。
  - 期望：`eligible = true`，`reasons` 非空。
- `/campaign/{id}/end`
  - 期望：HTTP 200，`status = ENDED`。
- 结束后再次 check。
  - 期望：HTTP 409，页面显示错误。

## 回归测试

后端回归：

- `./mvnw test`
- 使用 H2 profile 启动并手工或自动调用 README 中核心 curl。

前端回归：

- 打开 `/` 页面无控制台错误。
- 所有 demo 示例按钮可加载 payload。
- 所有已接入 demo 的“运行”按钮能得到响应或明确错误。
- 页面刷新后仍可使用，不依赖内存中的前端状态。

## 异常场景测试

- 输入非法 JSON。
- 删除必需路径参数，例如 hot rule name 或 campaign id。
- 后端未启动时访问独立前端，显示网络错误。
- 后端返回 400/404/409 时，页面显示状态码和错误 body。
- `/actuator/prometheus` 返回文本时，页面不尝试 JSON.parse。
- 大响应如 Prometheus 文本或 auditTrail 较长时，页面不布局溢出。

## 最终验收标准

- 前端可从浏览器访问。
- 至少覆盖最终实施计划定义的第一阶段端点。
- 每个端点都有示例输入、可编辑请求、运行按钮、响应展示和错误展示。
- 折扣、推荐、审计、TMS、CEP、活动资格这些核心效果不用看控制台即可观察。
- 后端现有 REST 行为未被改变。
- README 中增加前端启动和访问说明。
