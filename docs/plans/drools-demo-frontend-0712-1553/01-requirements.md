# 01 Requirements

## 任务目标

为当前 `drools-demo` 项目增加一个前端演示页面，让使用者不需要手写 `curl`，即可通过浏览器观察 Drools 各演示步骤的输入、输出、规则触发结果、审计轨迹、热加载错误和指标入口。

本次任务只做分析与规划，不改业务代码。本文件结论基于已阅读的实际代码：

- `README.md`
- `docs/drools-capabilities.md`
- `pom.xml`
- `src/main/java/com/lrj/drools/controller/*.java`
- `src/main/java/com/lrj/drools/service/*.java`
- `src/main/java/com/lrj/drools/domain/*.java`
- `src/main/java/com/lrj/drools/persistence/*.java`
- `src/main/java/com/lrj/drools/config/DroolsConfig.java`
- `src/main/resources/META-INF/kmodule.xml`
- `src/main/resources/application*.yml`
- `src/main/resources/rules/**/*.drl`

## 完整需求

### 用户需求

用户希望“给 Drools 项目添加前端页面，结合前端更好地看到演示效果”。从现有仓库看，项目是一个 Drools 学习脚手架，已经通过 REST 暴露 Step 1 到 Step 18 的能力，README 中主要用 `curl` 展示。前端应把这些已有能力组织成可交互演示，而不是发明新的规则业务。

### 功能需求

前端至少应覆盖以下已存在接口，并按演示主题组织：

| 分组 | 后端端点 | 现有 Controller / Service | 前端展示重点 |
| --- | --- | --- | --- |
| 基础规则 | `POST /hello` | `DiscountController.hello` -> `DiscountService.runHello` | `rulesFired`、输入 `Customer` |
| 订单折扣 | `POST /discount/calculate` | `DiscountController.calculate` -> `DiscountService.calculate` | `Order.totalAmount`、`finalAmount`、`discountReasons` |
| 购物车聚合 | `POST /cart/checkout` | `CartController.checkout` -> `CartService.checkout` | `Cart.goldStatus`、品类聚合折扣 |
| 风控推荐 | `POST /risk/evaluate` | `RiskController.evaluate` -> `RiskService.evaluate` | `recommendations`、`exists/not` 效果 |
| 流水线 | `POST /pipeline/run` | `PipelineController.run` -> `PipelineService.run` | validate -> discount -> risk -> notify 的结果 |
| 审计轨迹 | `POST /pipeline/audit` | `PipelineController.runWithAudit` -> `PipelineService.runWithAudit` | `auditTrail` 的 sequence/type/detail |
| 决策表 | `POST /decision/calculate` | `DecisionController.calculate` -> `DecisionService.calculate` | Excel 决策表规则输出 |
| CEP 风控 | `POST /fraud/check` | `FraudController.check` -> `FraudService.check` | `BurstAlert` 列表、时间戳输入 |
| 热加载 | `POST /hot/upsert`, `POST /hot/run/{name}`, `GET /hot/list` | `HotReloadController` -> `HotReloadService` | DRL 编辑、编译错误行号、运行结果 |
| 持久会话 | `POST /loyalty/start`, `POST /loyalty/{id}/purchase`, `GET /loyalty/{id}` | `LoyaltyController` -> `LoyaltyService` | 跨请求积分、等级、404/400 错误 |
| Stateless | `POST /stateless/calculate`, `POST /stateless/batch` | `StatelessDiscountController` -> `StatelessDiscountService` | 与 stateful 折扣结果对照 |
| TMS | `POST /tms/compare` | `TmsController.compare` -> `TmsService.compare` | logical/regular 两阶段 Alert 差异 |
| 后向链 | `POST /backward/contains` | `BackwardChainingController.contains` -> `BackwardChainingService.evaluate` | `answers`、`ancestorsLookup` |
| 护栏 | `POST /guard/runaway`, `POST /guard/timeout`, `POST /guard/canary` | `GuardController` -> `GuardService` | fire 上限、超时 halt、AgendaFilter 灰度 |
| 指标 | `POST /metrics/discount`, `GET /actuator/prometheus` | `MetricsController` -> `MeteredDiscountService` | `rulesFired`、Prometheus 文本入口 |
| KieScanner | `POST /scanner/deploy`, `POST /scanner/run`, `POST /scanner/poll/start`, `POST /scanner/poll/stop`, `GET /scanner/status` | `ScannerController` -> `ScannerService` | 部署状态、generation、编译错误 |
| DMN | `POST /dmn/price` | `DmnController.price` -> `DmnService.evaluate` | decisions: `Discount Rate` / `Final Price` / `Membership Tier` |
| 营销活动 | `POST /campaign/create`, `POST /campaign/{id}/check`, `POST /campaign/{id}/end`, `GET /campaign/list` | `CampaignController` -> `CampaignService` | 活动 DRL、资格理由、状态、cached |

### 体验需求

- 首页直接进入演示台，不做营销落地页。
- 左侧或顶部按 Drools 能力分组导航，避免 18 个 Step 平铺造成认知负担。
- 每个演示卡片提供：
  - 可编辑 JSON 或结构化表单；
  - “载入示例”按钮；
  - “运行”按钮；
  - 响应 JSON；
  - 关键字段摘要；
  - 错误响应展示，尤其是 DRL 编译错误。
- 对审计轨迹、TMS、CEP、流水线等“效果不直观”的能力，增加专门可视化：
  - `auditTrail` 按 sequence 时间线展示；
  - TMS logical vs regular 并排对比；
  - CEP 事件列表按 timestamp 排序展示；
  - `discountReasons` 和 `recommendations` 以列表呈现。
- 默认请求同源后端 `http://localhost:8081`，如果采用独立前端开发服务器，则需要可配置 API base 或开发代理。

## 已确认业务规则

以下规则来自当前代码和 DRL：

- `Order` 和 `Cart` 构造时基于 `OrderItem.subtotal()` 计算 `totalAmount`，初始 `finalAmount = totalAmount`。
- `Order.applyRatioDiscount` 和 `Cart.applyRatioDiscount` 乘以折扣比例后四舍五入到两位小数。
- `Order.applyFixedDiscount` 和 `Cart.applyFixedDiscount` 会把折后价限制为不小于 0。
- `/discount/calculate` 插入 `Customer` 和 `Order`，运行 `discountSession`。
- `/cart/checkout`、`/risk/evaluate`、`/pipeline/run` 等以 `Cart` 为主 fact，通常不单独 insert `OrderItem`。
- `OrderItem.category` 可为空；注释说明旧折扣接口不传 category 时 Jackson 反序列化为 null，规则中的 category 匹配不会误触发。
- `KieSession` 不线程安全，现有 service 均按请求新建并在 `finally` 中 `dispose`；`StatelessKieSession` 在 `StatelessDiscountService` 中单例复用。
- `/loyalty/start` 同 sessionId 会覆盖原快照；`/loyalty/{id}/purchase` 未知 sessionId 返回 400；`GET /loyalty/{id}` 未知 sessionId 返回 404。
- `/campaign/create` 会先编译 DRL，编译失败不落库；`/campaign/{id}/check` 对 ENDED 活动返回 409。
- 默认 profile 是 MySQL，H2 需通过 profile 切换；数据库只被 Loyalty 和 Campaign 相关功能使用。

## 边界条件

- 空 `items`：`Cart` 和 `Order` 构造可计算为 0；`/pipeline/run` 有 `Reject empty cart` 规则，但 demo 没 retract，后续阶段仍可能继续跑。
- `customer` 为 null、`items` 为 null、负数金额或负数数量：当前 Controller 未显式校验，前端不应假设后端会返回友好校验信息。规划中应在前端做基础输入保护，但不能把它描述为已有后端能力。
- DRL 编辑类接口可能返回 400，body 是 `ErrorResponse(error)`。
- `/guard/timeout` 会执行失控规则直到 `halt()` 返回，前端应避免默认过大的 timeout。
- `/scanner/deploy` 会写入本地 Maven 仓库 `~/.m2`，属于后端现有行为；前端只是触发接口。
- `/actuator/prometheus` 返回文本，不是 JSON。
- 前端开发服务器若不是 8081 同源，会遇到 CORS；当前代码未发现 CORS 配置。

## 非目标

- 不重写 Drools 规则、不新增业务规则、不调整现有规则触发顺序。
- 不修改 `DiscountService`、`PipelineService`、`CampaignService` 等核心业务逻辑，除非最终执行阶段发现必须补充非业务适配。
- 不引入用户登录、权限、生产级审计、规则审批流。
- 不做数据库结构重构；前端项目本身不需要新增表。
- 不把项目拆成多服务，不引入 KieServer / Business Central。
- 不保证生产安全暴露 `/actuator/prometheus`、DRL 热加载接口；学习 demo 允许本地使用，生产化需要另行设计鉴权。

## 歧义与易遗漏点

- “前端页面”未指定技术栈：当前仓库无 `package.json`、无前端目录、`pom.xml` 也无模板引擎依赖。需要在方案中给出不同栈的取舍。
- “更好地看到演示效果”可能只要求覆盖核心 Step，也可能希望覆盖全部 Step 1-18。最终方案建议分阶段：第一阶段覆盖最能展示效果的 Step 1/2/4/5/6/8/9/10/12/14/17/18，第二阶段补齐剩余 Step。
- 是否允许新增后端只读元数据接口：不是必须。推荐第一版不新增，直接维护前端示例配置，避免动业务接口。
- 是否部署到生产：未明确。默认目标是本地学习演示。
- 现有 `src/main/java/com/lrj/drools/audit/AuditEvent.java` 工作区已有未提交修改，实施时必须先确认该变更内容，不能覆盖。

## 验收标准

- 项目启动后可在浏览器打开前端页面，并完成主要 Drools 示例调用。
- 对每个已接入端点，前端展示请求体、响应体、关键摘要和错误信息。
- 不破坏现有 README 中列出的 REST 调用。
- 不要求用户手写 `curl` 即可观察折扣、推荐、审计轨迹、TMS、CEP、活动资格等结果。
- 对 DRL 编译错误能原样显示后端返回的错误文本。
- 若采用同源静态前端，访问 8081 即可使用，无 CORS 额外配置。
- 后端现有测试或至少 `./mvnw test`、`./mvnw spring-boot:run -Dspring-boot.run.profiles=h2` 可通过；本次规划阶段不执行会写 `target/` 的命令。
