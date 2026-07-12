# FINAL PLAN

## 背景

当前 `drools-demo` 是一个 Spring Boot + Drools 学习项目，后端已经通过 REST 暴露 Step 1 到 Step 18 的规则演示能力。README 主要用 `curl` 说明如何调用，用户希望添加前端页面，以便在浏览器中更直观地观察演示效果。

本计划基于对当前仓库实际代码的阅读，不假设不存在的类、方法、表或接口。

## 目标

- 增加一个浏览器可访问的 Drools 演示台。
- 复用现有 REST Controller 和 Service，不重写规则业务。
- 能展示请求、响应、关键摘要、错误信息。
- 让折扣、推荐、审计轨迹、TMS、CEP、热加载、持久会话、活动资格等效果可视化。
- 第一版优先本地学习体验和低改动风险。

## 非目标

- 不新增 Drools 业务规则。
- 不调整现有 DRL、DMN、决策表。
- 不重构 `KieSession` 生命周期。
- 不做登录、权限、规则审批、生产级安全治理。
- 不新增数据库表。
- 不引入 KieServer、Business Central 或多服务部署。

## 已确认的业务规则

- `DiscountController.calculate` 构造 `Order`，再调用 `DiscountService.calculate`。
- `DiscountService.calculate` 使用 `discountSession`，插入 `Customer` 和 `Order` 后 `fireAllRules()`。
- `CartController`、`RiskController`、`PipelineController` 以 `Cart` 为主要 fact。
- `PipelineService.runInternal` 对 agenda group 采用反向压栈：`risk` -> `discount` -> `validate`，实际执行 validate -> discount -> risk。
- `PipelineService.runWithAudit` 返回 `AuditedRun(Cart cart, List<AuditEvent> auditTrail)`。
- `FraudService.check` 会按 `OrderEvent.timestamp` 排序，逐个推进 `SessionPseudoClock`。
- `TmsService.compare` 分别运行 `tmsLogicalSession` 和 `tmsRegularSession`，用于展示 logical insert 与 regular insert 的区别。
- `LoyaltyService` 通过 `SessionSnapshot` 表保存 marshall 后的 session bytes。
- `CampaignService.create` 先编译 DRL，再保存到 `campaign` 表；`CampaignService.check` 收集 `Eligibility` fact 判断资格。
- `HotReloadService.upsert` 和 `ScannerService.deploy` 会返回带行号的编译错误，前端应原样展示。

## 当前代码与调用链分析

### 启动与配置

- `DroolsDemoApplication.main`
  - Spring Boot 启动入口。
- `DroolsConfig.kieContainer`
  - 程序化加载 `kmodule.xml`、DRL、XLS 决策表、DMN 模型。
- `application.yml`
  - 端口 `8081`。
  - 默认 profile `mysql`。
  - Actuator 暴露 `health,info,metrics,prometheus`。

### 主要 Controller

- `DiscountController`
  - `hello(Customer)`
  - `calculate(CalculateRequest)`
- `CartController`
  - `checkout(CheckoutRequest)`
- `RiskController`
  - `evaluate(EvaluateRequest)`
- `PipelineController`
  - `run(RunRequest)`
  - `runWithAudit(RunRequest)`
  - `buildCart(RunRequest)` private helper
- `FraudController`
  - `check(CheckRequest)`
- `HotReloadController`
  - `upsert(UpsertRequest)`
  - `run(String name, RunRequest)`
  - `list()`
- `LoyaltyController`
  - `start(StartRequest)`
  - `purchase(String sessionId, PurchaseRequest)`
  - `get(String sessionId)`
- `TmsController`
  - `compare(CompareRequest)`
- `BackwardChainingController`
  - `contains(EvaluateRequest)`
- `GuardController`
  - `runaway(RunawayRequest)`
  - `timeout(TimeoutRequest)`
  - `canary(CanaryRequest)`
- `MetricsController`
  - `calculate(CalculateRequest)`
- `ScannerController`
  - `deploy(DeployRequest)`
  - `run(RunRequest)`
  - `startPolling(PollRequest)`
  - `stopPolling()`
  - `status()`
- `DmnController`
  - `price(PriceRequest)`
- `CampaignController`
  - `create(CreateRequest)`
  - `check(String campaignId, UserProfile user)`
  - `end(String campaignId)`
  - `list()`

### 数据模型

- `Customer`, `OrderItem`, `Order`, `Cart`
- `Promotion`, `Eligibility`, `UserProfile`
- `OrderEvent`, `BurstAlert`
- `Sensor`, `Alert`
- `Location`
- `Counter`
- `LoyaltyState`, `PurchaseEvent`
- `CampaignEntity` -> table `campaign`
- `SessionSnapshot` -> table `session_snapshot`

## 候选方案对比与评分

| 方案 | 正确性 | 改动风险 | 复杂度 | 可维护性 | 扩展性 | 测试难度 | 回滚成本 | 总分 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| A Spring Boot 静态前端 | 4 | 5 | 5 | 4 | 3 | 4 | 5 | 30 |
| B Thymeleaf 服务端渲染 | 3 | 3 | 3 | 3 | 3 | 3 | 4 | 22 |
| C Vite/React 独立前端 | 4 | 2 | 2 | 4 | 5 | 3 | 3 | 23 |

## 最终方案及选择原因

最终选择方案 A：Spring Boot 静态前端，并吸收方案 C 的配置驱动思想。

选择原因：

- 当前后端 REST API 已完整，前端只需调用并展示。
- 静态资源同源托管在 `8081`，无需 CORS、无需双进程、无需 Node 工具链。
- 不修改业务 Controller、Service、Domain、Repository、DRL、DMN。
- 回滚成本最低。
- 符合学习项目目标，不把复杂度转移到前端工程化。

已知弱点：

- 原生 JavaScript 没有 TypeScript 类型保护，请求字段漂移需靠测试发现。
- 如果后续要做复杂 DRL 编辑器、流程图、保存场景、多人协作，静态方案会吃力。
- 示例配置需要人工维护，不能自动从 Java record 生成。

## 精确修改清单

### 新增文件

- `src/main/resources/static/index.html`
  - 新增静态首页。
  - 放在 `static` 根目录，使 Spring Boot 默认 welcome page 机制可直接通过 `/` 访问。
  - 不涉及 Java 类或方法。
- `src/main/resources/static/assets/styles.css`
  - 新增页面样式。
- `src/main/resources/static/assets/examples.js`
  - 新增 demo catalog。
  - 包含端点、HTTP 方法、路径参数、示例 payload、摘要配置。
  - **覆盖原则（Claude 阶段二修订）**：用户诉求是"看到全部演示效果"，而 catalog 是纯配置、payload 均可从 README 已有 curl 转写，补全所有端点的边际成本极低。因此 demo catalog **必须覆盖后端全部 31 个功能端点（Step 1–18 全量）**，不留 Step 7/11/16 无 UI 的缺口。以下为完整清单：
    - `hello` -> `POST /hello`
    - `discount-calculate` -> `POST /discount/calculate`
    - `cart-checkout` -> `POST /cart/checkout`
    - `risk-evaluate` -> `POST /risk/evaluate`
    - `pipeline-run` -> `POST /pipeline/run`
    - `pipeline-audit` -> `POST /pipeline/audit`
    - `decision-calculate` -> `POST /decision/calculate`  ← 补：Step 7 决策表
    - `fraud-check` -> `POST /fraud/check`
    - `hot-upsert` -> `POST /hot/upsert`
    - `hot-run` -> `POST /hot/run/{name}`
    - `hot-list` -> `GET /hot/list`
    - `loyalty-start` -> `POST /loyalty/start`
    - `loyalty-purchase` -> `POST /loyalty/{id}/purchase`
    - `loyalty-get` -> `GET /loyalty/{id}`
    - `stateless-calculate` -> `POST /stateless/calculate`  ← 补：Step 11
    - `stateless-batch` -> `POST /stateless/batch`  ← 补：Step 11
    - `tms-compare` -> `POST /tms/compare`
    - `backward-contains` -> `POST /backward/contains`
    - `guard-runaway` -> `POST /guard/runaway`
    - `guard-timeout` -> `POST /guard/timeout`
    - `guard-canary` -> `POST /guard/canary`
    - `metrics-discount` -> `POST /metrics/discount`
    - `scanner-deploy` -> `POST /scanner/deploy`  ← 补：Step 16
    - `scanner-run` -> `POST /scanner/run`  ← 补：Step 16
    - `scanner-poll-start` -> `POST /scanner/poll/start`  ← 补：Step 16
    - `scanner-poll-stop` -> `POST /scanner/poll/stop`  ← 补：Step 16
    - `scanner-status` -> `GET /scanner/status`  ← 补：Step 16
    - `dmn-price` -> `POST /dmn/price`
    - `campaign-create` -> `POST /campaign/create`
    - `campaign-check` -> `POST /campaign/{id}/check`
    - `campaign-end` -> `POST /campaign/{id}/end`
    - `campaign-list` -> `GET /campaign/list`
    - （可选）`metrics-prometheus` -> `GET /actuator/prometheus`，作为 `responseType: "text"` 演示，供观察指标随调用累积
- `src/main/resources/static/assets/app.js`
  - 新增前端运行逻辑。
  - 建议函数：
    - `renderDemoList()`
    - `renderDemoPanel(demoId)`
    - `loadExample(demoId, exampleId)`
    - `parsePayload(text)`
    - `buildUrl(demo, params)`
    - `sendRequest(demo, payload)`
    - `renderResponse(demo, status, body)`
    - `renderSummary(demo, body)`
    - `renderAuditTrail(auditTrail)`
    - `renderTmsComparison(result)`
    - `renderError(status, errorBody)`
  - 路径参数 `{name}`、`{id}` 必须从页面输入中替换；缺失时前端阻止请求，不向后端发送 `/hot/run/{name}` 这类未替换路径。

### 修改文件

- `README.md`
  - 增加“前端演示台”小节。
  - 说明访问地址：`http://localhost:8081/`。
  - 说明推荐用 H2 profile 快速体验：`./mvnw spring-boot:run -Dspring-boot.run.profiles=h2`。

### 不修改的文件

- 不修改 `pom.xml`。
- 不修改 `src/main/java/com/lrj/drools/controller/*.java`。
- 不修改 `src/main/java/com/lrj/drools/service/*.java`。
- 不修改 `src/main/java/com/lrj/drools/domain/*.java`。
- 不修改 `src/main/resources/rules/**`。
- 不修改 `src/main/resources/application*.yml`。

实施前注意：当前工作区已有 `src/main/java/com/lrj/drools/audit/AuditEvent.java` 未提交修改，执行开发任务时必须保留。

## 数据库、接口、配置、消息结构变更

- 数据库：无变更。
- REST 接口：无变更。
- Spring 配置：无变更。
- 消息结构：无后端变更。
- 前端内部配置：新增 `examples.js` 中的 demo catalog，属于静态资源，不影响后端协议。

## 分阶段实施步骤及依赖关系

### 阶段 1：数据结构与领域模型

目标：整理前端 demo catalog，而不是修改后端领域模型。

步骤：

1. 在 `examples.js` 中定义 demo 分组：
   - 基础规则
   - 折扣与购物车
   - 推理与事件
   - 规则热加载
   - 会话与治理
   - 决策模型
2. 为每个 demo 定义：
   - `id`
   - `title`
   - `method`
   - `path`
   - `pathParams`
   - `contentType`
   - `examples`
   - `summaryType`
3. 示例 payload 从 `README.md` 已有 curl 转写，避免自造字段。
4. 为 GET 请求配置 `body: null`，为 `/actuator/prometheus` 这类文本响应预留 `responseType: "text"`。第一阶段不强制接入 `/actuator/prometheus`，但 `fetch` 封装不能假设所有响应都是 JSON。

完成标准：

- `examples.js` 覆盖第一阶段端点。
- 每个 payload 字段都能在实际 Controller request record 或 Domain record/class 中找到对应字段。
- `items`、`customer`、path params 这类关键输入有前端基础校验；校验只防明显误操作，不改变后端业务语义。

### 阶段 2：核心业务逻辑

目标：实现前端请求和响应渲染逻辑，不改后端业务逻辑。

步骤：

1. 在 `app.js` 实现 JSON 编辑、payload 解析、路径参数替换。
2. 实现 `fetch` 封装：
   - JSON body；
   - 无 body GET；
   - JSON 响应；
   - 文本响应，如 `/actuator/prometheus`；
   - HTTP 非 2xx 错误。
3. 实现关键摘要：
   - 折扣摘要；
   - 推荐摘要；
   - auditTrail 时间线；
   - TMS logical/regular 对比；
   - campaign list/check；
   - loyalty state；
   - scanner status；
   - DRL 编译错误。

完成标准：

- 任一 demo 都可从示例 payload 发起请求。
- 非法 JSON 不发请求并显示错误。
- 400/404/409 响应能展示状态码和错误 body。

### 阶段 3：接口与适配层

目标：把前端页面接入 Spring Boot 静态资源机制。

步骤：

1. 新增 `index.html` 并引用 CSS/JS。
2. 新增基础布局：
   - 导航；
   - 请求编辑区；
   - 操作按钮；
   - 响应原文；
   - 摘要视图；
   - 错误视图。
3. 确认所有请求使用相对路径，如 `/discount/calculate`，保持同源。
4. README 增加访问说明。
5. 不引入 `/api` 前缀；当前后端真实路径均为根路径端点，前端应直接调用真实路径。

完成标准：

- 访问 `http://localhost:8081/` 可打开页面。
- 页面静态资源 200。
- 不需要 CORS 配置。

### 阶段 4：测试

目标：验证前端覆盖核心演示，不破坏后端。

步骤：

1. 启动 H2 profile。
2. 手动或自动跑核心 demo：
   - `/hello`
   - `/discount/calculate`
   - `/risk/evaluate`
   - `/pipeline/audit`
   - `/fraud/check`
   - `/hot/upsert` 成功和失败
   - `/loyalty/start` + purchase + get
   - `/tms/compare`
   - `/guard/canary`
   - `/dmn/price`
   - `/campaign/create` + check + end
3. 如环境允许，运行 `./mvnw test`。
4. 检查浏览器控制台无未处理异常。

完成标准：

- 核心 demo 都能得到响应。
- 错误场景显示清晰。
- 后端测试通过或明确记录未执行原因。

### 阶段 5：文档与最终检查

步骤：

1. 更新 README。
2. 检查新增文件路径。
3. 检查没有改动业务代码。
4. 检查工作区 diff，确认只包含预期静态资源和 README。

完成标准：

- README 能指导用户启动后端并打开前端。
- `git diff --stat` 中没有业务 Java/DRL/配置文件变更。

## 测试方案

采用 `test-plan.md` 中的测试设计。最低验收集：

- `/discount/calculate`：VIP 2 + 老用户 + 660 总额展示折扣结果。
- `/risk/evaluate`：电子产品无保险展示推荐。
- `/pipeline/audit`：展示 auditTrail 时间线。
- `/fraud/check`：展示 BurstAlert。
- `/hot/upsert`：合法 DRL 成功、非法 DRL 显示 400 错误。
- `/loyalty/*`：展示状态跨请求变化。
- `/tms/compare`：logical phase2 为空，regular phase2 保留 Alert。
- `/campaign/*`：创建、校验、结束、结束后 409。

## 风险、监控、灰度与回滚方案

### 风险

- 前端示例和后端 record 字段漂移。
- 用户提交后端未校验的异常输入导致 500。
- DRL 热加载相关接口被误用于生产公网环境。
- 原生 JS 文件变大后可维护性下降。

### 监控

- 使用浏览器控制台观察前端错误。
- 使用现有 `/actuator/health` 判断后端健康。
- 使用现有 `/actuator/prometheus` 和 `/metrics/discount` 观察 Drools 指标。

### 灰度

- 第一阶段只在本地启用，不增加独立开关。
- 如需要隐藏入口，可以暂不在 README 主路径强调，仍通过 `/index.html` 访问。
- 不新增后端开关，避免配置膨胀。

### 回滚

- 删除：
  - `src/main/resources/static/index.html`
  - `src/main/resources/static/assets/app.js`
  - `src/main/resources/static/assets/examples.js`
  - `src/main/resources/static/assets/styles.css`
- 回滚 README 中前端说明。
- 无数据库回滚。
- 无 Java 代码回滚。

## 最终验收清单

- [ ] 访问 `http://localhost:8081/` 能看到 Drools 演示台。
- [ ] demo catalog 覆盖后端全部 31 个功能端点（含 decision / stateless / scanner，Step 1–18 全量）。
- [ ] 页面可加载每个 demo 的示例 payload。
- [ ] 页面可编辑 JSON 并调用后端。
- [ ] 页面展示 HTTP 状态码。
- [ ] 页面展示原始响应 JSON 或文本。
- [ ] 页面展示折扣结果和 `discountReasons`。
- [ ] 页面展示 `recommendations`。
- [ ] 页面按 sequence 展示 `auditTrail`。
- [ ] 页面并排展示 TMS logical 与 regular。
- [ ] 页面展示 DRL 编译错误。
- [ ] 页面展示 Loyalty 状态变化。
- [ ] 页面展示 Campaign 创建、校验、结束状态。
- [ ] 页面处理 400、404、409。
- [ ] README 包含前端访问说明。
- [ ] 未修改业务 Java、DRL、DMN、数据库实体或配置。

## 架构复审结论

复审后，最终方案无自相矛盾点：它明确不修改后端接口，却允许新增静态资源；它选择同源调用，因此不需要 CORS；它声明无数据库变更，因此不会触碰 `CampaignEntity` 或 `SessionSnapshot`。本次复审补充了第一阶段真实端点集合、路径参数替换规则、GET/text 响应处理和静态 welcome page 约束，使方案可以直接交给开发 Agent 执行。最大弱点仍是原生 JS 的长期可维护性，已通过 demo catalog 配置化和阶段性覆盖控制风险。
