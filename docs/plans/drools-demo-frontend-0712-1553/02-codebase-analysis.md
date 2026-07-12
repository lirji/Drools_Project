# 02 Codebase Analysis

## 项目结构

当前仓库是单模块 Maven / Spring Boot 项目：

- `pom.xml`
  - Spring Boot `3.3.5`
  - Java `21`
  - Drools `8.44.2.Final`
  - 主要依赖：`spring-boot-starter-web`、Drools/KIE、`spring-boot-starter-data-jpa`、MySQL、H2、Actuator、Prometheus registry。
  - 未发现 `spring-boot-starter-thymeleaf`、Node/Vite/React、WebJars 等前端依赖。
- `src/main/java/com/lrj/drools/DroolsDemoApplication.java`
  - 标准 Spring Boot 启动类。
- `src/main/resources/application.yml`
  - 服务端口 `8081`
  - 默认 active profile: `${SPRING_PROFILES_ACTIVE:mysql}`
  - 暴露 actuator: `health,info,metrics,prometheus`
- `src/main/resources/application-h2.yml`
  - H2 file 数据源 `jdbc:h2:file:./data/drools-demo`
- `src/main/resources/application-mysql.yml`
  - MySQL 数据源，环境变量覆盖连接参数。
- `src/main/resources/META-INF/kmodule.xml`
  - 声明多个 KieBase / KieSession。
- `src/main/resources/rules/**`
  - 存放 DRL、DMN、决策表。
- `src/test/java/com/lrj/drools/tools/VipDiscountSheetGenerator.java`
  - 仅发现一个生成决策表的工具类，未发现常规单元/集成测试。
- `docs/`
  - 已有 Drools 能力说明文档。
- `docs/plans/drools-demo-frontend-0712-1553/`
  - 本次规划文档输出目录。

## KIE 配置与规则加载

`src/main/java/com/lrj/drools/config/DroolsConfig.java`

- `DroolsConfig.kieContainer()`
  - 用 `KieServices` 和 `KieFileSystem` 程序化构建 KIE module。
  - 写入 `META-INF/kmodule.xml`。
  - 扫描 `classpath*:rules/**/*.drl`。
  - 扫描 `classpath*:rules/**/*.xls` 并显式设置 `ResourceType.DTABLE`。
  - 扫描 `classpath*:rules/**/*.dmn` 并显式设置 `ResourceType.DMN`。
  - `KieBuilder.buildAll()` 后如果有 ERROR，抛 `IllegalStateException`。
  - 返回 `KieContainer` Bean。

`src/main/resources/META-INF/kmodule.xml` 已声明：

- `helloKBase` / `helloSession`
- `discountKBase` / `discountSession` / `discountStatelessSession`
- `cartKBase` / `cartSession`
- `riskKBase` / `riskSession`
- `pipelineKBase` / `pipelineSession`
- `decisionKBase` / `decisionSession`
- `fraudKBase` / `fraudSession`，`eventProcessingMode="stream"`，`clockType="pseudo"`
- `loyaltyKBase` / `loyaltySession`
- `tmsLogicalKBase` / `tmsLogicalSession`
- `tmsRegularKBase` / `tmsRegularSession`
- `backwardKBase` / `backwardSession`
- `guardKBase` / `guardSession`
- `dmnKBase`

## 领域模型

### 订单与购物车

- `src/main/java/com/lrj/drools/domain/Customer.java`
  - `record Customer(String name, int age, int vipLevel, int yearsSinceRegistration)`
- `src/main/java/com/lrj/drools/domain/OrderItem.java`
  - `record OrderItem(String name, int quantity, double unitPrice, String category)`
  - `subtotal()` 返回 `quantity * unitPrice`
- `src/main/java/com/lrj/drools/domain/Order.java`
  - 构造参数：`orderId`, `Customer`, `List<OrderItem>`
  - 字段：`totalAmount`, `finalAmount`, `discountReasons`
  - 方法：`applyRatioDiscount(double ratio, String reason)`, `applyFixedDiscount(double amount, String reason)`
- `src/main/java/com/lrj/drools/domain/Cart.java`
  - 构造参数：`cartId`, `Customer`, `List<OrderItem>`
  - 字段：`totalAmount`, `finalAmount`, `goldStatus`, `discountReasons`, `recommendations`
  - 方法：`applyRatioDiscount`, `applyFixedDiscount`, `setGoldStatus`, `addRecommendation`

### 事件、推理、状态

- `OrderEvent`
  - `record OrderEvent(String orderId, String customerName, double amount, long timestamp)`
- `BurstAlert`
  - `record BurstAlert(String customerName, int eventCount, long detectedAt)`
- `Sensor`
  - mutable POJO：`name`, `value`
- `Alert`
  - `record Alert(String sensorName, String level, String message)`
- `Location`
  - `record Location(@Position(0) String thing, @Position(1) String container)`
- `Counter`
  - mutable POJO：`value`
- `LoyaltyState`
  - serializable mutable POJO：`totalPoints`, `tier`, `unlockedBadges`, `lastEarned`
- `PurchaseEvent`
  - `record PurchaseEvent(double amount) implements Serializable`
- `Promotion`
  - `record Promotion(String type, String message)`
- `UserProfile`
  - `record UserProfile(String userId, int age, int vipLevel, int registrationDays, double totalSpent, String city)`
- `Eligibility`
  - `record Eligibility(boolean eligible, String reason)`

## REST 接口与调用链

### 基础折扣链路

- `DiscountController.hello(Customer)`
  - `POST /hello`
  - 调用 `DiscountService.runHello(Customer)`
  - 返回 `Map`，包含 `customer`, `rulesFired`, `hint`
- `DiscountService.runHello(Customer)`
  - `kieContainer.newKieSession("helloSession")`
  - `session.insert(customer)`
  - `session.fireAllRules()`
  - `session.dispose()`

- `DiscountController.calculate(CalculateRequest)`
  - `POST /discount/calculate`
  - `CalculateRequest(String orderId, Customer customer, List<OrderItem> items)`
  - 构造 `Order`，缺 `orderId` 时使用 `UUID.randomUUID().toString()`
  - 调用 `DiscountService.calculate(Order)`
- `DiscountService.calculate(Order)`
  - `kieContainer.newKieSession("discountSession")`
  - insert `order.getCustomer()` 和 `order`
  - fire 后返回同一个 `Order`

### Cart / Risk / Pipeline

- `CartController.checkout(CheckoutRequest)`
  - `POST /cart/checkout`
  - 构造 `Cart`
  - 调用 `CartService.checkout(Cart)`
- `CartService.checkout(Cart)`
  - `newKieSession("cartSession")`
  - insert `Cart`

- `RiskController.evaluate(EvaluateRequest)`
  - `POST /risk/evaluate`
  - 构造 `Cart`
  - 调用 `RiskService.evaluate(Cart)`
- `RiskService.evaluate(Cart)`
  - `newKieSession("riskSession")`
  - insert `Cart`

- `PipelineController.run(RunRequest)`
  - `POST /pipeline/run`
  - `buildCart(RunRequest)`
  - 调用 `PipelineService.run(Cart)`
- `PipelineController.runWithAudit(RunRequest)`
  - `POST /pipeline/audit`
  - 调用 `PipelineService.runWithAudit(Cart)`
- `PipelineService.runInternal(Cart, Object auditMarker)`
  - `newKieSession("pipelineSession")`
  - 可选 `RuleAuditListener.attachTo(session)`
  - insert `Cart`
  - 依次 `setFocus("risk")`, `setFocus("discount")`, `setFocus("validate")`
  - `fireAllRules()`
  - 返回 `AuditedRun(Cart cart, List<AuditEvent> auditTrail)`

### 决策表、CEP、TMS、后向链

- `DecisionController.calculate(CalcRequest)`
  - `POST /decision/calculate`
  - 构造 `Cart`
  - 调用 `DecisionService.calculate(Cart)`
- `DecisionService.calculate(Cart)`
  - `newKieSession("decisionSession")`

- `FraudController.check(CheckRequest)`
  - `POST /fraud/check`
  - 调用 `FraudService.check(List<OrderEvent>)`
- `FraudService.check(List<OrderEvent>)`
  - `newKieSession("fraudSession")`
  - 获取 `SessionPseudoClock`
  - 按 `OrderEvent.timestamp` 排序
  - 对每个事件推进 clock、insert、fire
  - 从 session objects 收集 `BurstAlert`

- `TmsController.compare(CompareRequest)`
  - `POST /tms/compare`
  - 默认 `sensorName = "sensor-1"`, `hotValue = 95.0`, `coolValue = 50.0`
  - 调用 `TmsService.compare`
- `TmsService.compare`
  - 分别运行 `tmsLogicalSession` 和 `tmsRegularSession`
  - 阶段 1 insert hot sensor/fire/collect alerts
  - 阶段 2 update sensor to cool/fire/collect alerts

- `BackwardChainingController.contains(EvaluateRequest)`
  - `POST /backward/contains`
  - 调用 `BackwardChainingService.evaluate(List<Location>, List<Query>)`
- `BackwardChainingService.evaluate`
  - `newKieSession("backwardSession")`
  - insert locations
  - 对每个 query 调 `session.getQueryResults("isContainedIn", thing, container)`
  - 不调用 `fireAllRules`

### 热加载、KieScanner、活动规则

- `HotReloadController.upsert(UpsertRequest)`
  - `POST /hot/upsert`
  - 调用 `HotReloadService.upsert(String name, String drl)`
  - 编译失败返回 400 `ErrorResponse(error)`
- `HotReloadService.upsert`
  - `KieHelper.addContent(drl, ResourceType.DRL)`
  - `verify()` 检查错误
  - 成功后 `registry.put(name, KieBase)`
- `HotReloadController.run(String name, RunRequest)`
  - `POST /hot/run/{name}`
  - 构造 `Cart`
  - 调用 `HotReloadService.execute`

- `ScannerController.deploy(DeployRequest)`
  - `POST /scanner/deploy`
  - 调用 `ScannerService.deploy(String drl)`
- `ScannerService.deploy`
  - 程序化生成 KJAR 到固定 `ReleaseId`：`com.lrj.rules:scanner-cart-rules:1.0.0-SNAPSHOT`
  - 编译成功后 install 到本地 Maven 仓库
  - 首次创建 `KieContainer` / `KieScanner`，之后 `scanner.scanNow()`
- `ScannerController.run(RunRequest)`
  - `POST /scanner/run`
- `ScannerController.startPolling(PollRequest)`
  - `POST /scanner/poll/start`
- `ScannerController.stopPolling()`
  - `POST /scanner/poll/stop`
- `ScannerController.status()`
  - `GET /scanner/status`

- `CampaignController.create(CreateRequest)`
  - `POST /campaign/create`
  - 调用 `CampaignService.create`
- `CampaignService.create`
  - 先 `compile(drl)`，成功后保存 `CampaignEntity`
  - `registry.put(campaignId, compiled)`
- `CampaignController.check`
  - `POST /campaign/{id}/check`
  - 调用 `CampaignService.check`
- `CampaignService.check`
  - 从 DB 查 `CampaignEntity`
  - ENDED 抛 `IllegalStateException`
  - 缓存不存在时从 DB 的 `eligibilityDrl` 重编译
  - insert `UserProfile`，fire，收集 `Eligibility(eligible == true)`

### 持久会话与指标

- `LoyaltyController.start(StartRequest)`
  - `POST /loyalty/start`
  - 调用 `LoyaltyService.start`
- `LoyaltyService.start`
  - `newKieSession("loyaltySession")`
  - insert `LoyaltyState`
  - `snapshot(sessionId, session)` 保存到 `SessionSnapshot`
- `LoyaltyController.purchase`
  - `POST /loyalty/{id}/purchase`
  - 调用 `LoyaltyService.purchase`
- `LoyaltyService.purchase`
  - `restore(sessionId)` 反序列化 session
  - insert `PurchaseEvent`
  - fire
  - snapshot
- `LoyaltyController.get`
  - `GET /loyalty/{id}`
  - 调用 `LoyaltyService.peek`

- `MetricsController.calculate(CalculateRequest)`
  - `POST /metrics/discount`
  - 构造 `Order`
  - 调用 `MeteredDiscountService.calculate`
- `MeteredDiscountService.calculate`
  - `newKieSession("discountSession")`
  - `MeteredRuleListener.attachTo`
  - `Timer.record(session::fireAllRules)`
  - 返回 `Result(Order order, int rulesFired)`

## 持久化模型

- `CampaignEntity`
  - 表名：`campaign`
  - 主键：`campaign_id`
  - 字段：`name`, `eligibility_drl`, `status`, `created_at`, `updated_at`
  - `eligibilityDrl` 使用 `@JdbcTypeCode(SqlTypes.LONGVARCHAR)`
- `SessionSnapshot`
  - 表名：`session_snapshot`
  - 主键：`session_id`
  - 字段：`data`, `updated_at`
  - `data` 使用 `@JdbcTypeCode(SqlTypes.LONGVARBINARY)`
- `CampaignRepository extends JpaRepository<CampaignEntity, String>`
- `SessionSnapshotRepository extends JpaRepository<SessionSnapshot, String>`

## 可复用代码

前端可以直接复用的后端能力：

- 所有现有 REST 端点，不需要新增业务接口。
- Controller 内的请求 record 可作为前端请求结构来源：
  - `DiscountController.CalculateRequest`
  - `CartController.CheckoutRequest`
  - `RiskController.EvaluateRequest`
  - `PipelineController.RunRequest`
  - `FraudController.CheckRequest`
  - `CampaignController.CreateRequest`
  - 等。
- `README.md` 中的 curl payload 可转为前端示例数据。
- `docs/drools-capabilities.md` 的能力地图可转为导航分组。
- `RuleAuditListener` 已能返回结构化 `AuditEvent`，前端只需展示。
- `MeteredDiscountService` 和 Actuator 已暴露指标，前端只需调用或提供链接。

## 受影响文件清单

最终实施时可能新增或修改的文件，按方案不同有所差异。

### 方案 A：Spring Boot 静态前端

新增：

- `src/main/resources/static/index.html`
- `src/main/resources/static/assets/app.js`
- `src/main/resources/static/assets/styles.css`
- `src/main/resources/static/assets/examples.js`

可能修改：

- `README.md`：增加前端访问方式。

不需要修改：

- `pom.xml`
- `src/main/java/com/lrj/drools/controller/*.java`
- `src/main/java/com/lrj/drools/service/*.java`
- `src/main/resources/application*.yml`

### 方案 B：Spring MVC + Thymeleaf 服务端渲染

新增：

- `src/main/java/com/lrj/drools/controller/DemoPageController.java`
- `src/main/resources/templates/demo/index.html`
- `src/main/resources/static/css/demo.css`

修改：

- `pom.xml`：新增 `spring-boot-starter-thymeleaf`
- 可能新增页面 DTO 或 view model 文件，具体待实现时确认。

### 方案 C：独立 Vite/React 前端

新增：

- `frontend/package.json`
- `frontend/vite.config.*`
- `frontend/src/**`
- `frontend/index.html`

可能修改：

- `pom.xml`：如果要 Maven 一键构建前端，需要增加前端构建插件，待验证。
- `src/main/java/com/lrj/drools/config/CorsConfig.java`：如果不使用开发代理或同源部署，需要 CORS，待验证。
- `README.md`：增加双服务启动说明。

## 当前测试情况

- 仓库中未发现常规 `*Test.java`。
- 仅有 `src/test/java/com/lrj/drools/tools/VipDiscountSheetGenerator.java`。
- 本次规划阶段未运行 `./mvnw test`，因为用户要求唯一可写目录是规划文档目录，而 Maven 测试通常会写 `target/`。

## 待验证信息

- 当前未发现 OpenAPI/Swagger 配置；若希望自动生成前端类型，需要另行引入或手写轻量类型。
- 当前未发现 CORS 配置；独立开发服务器方案需要通过 Vite proxy 或新增 CORS 配置解决。
- 当前 `docs/plans/drools-demo-frontend-0712-1553/` 目录已存在且为空。
- 工作区已有 `src/main/java/com/lrj/drools/audit/AuditEvent.java` 未提交修改，执行开发任务前需读取并避免覆盖。
