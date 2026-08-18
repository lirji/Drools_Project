# Step 逐步指南

本文是 CLAUDE.md 的详细配套：每个 Step 的完整说明、REST 接口清单、以及各 Step 特有的 DRL 语义 / 实现注意点。CLAUDE.md 只保留概览与通用规范，具体到某个 Step 时来这里查。

> **代码位置（2026-07-20 四模块重构后）**：本文涉及的 Step 1–24 全部实现（controller / service / domain / `rules/` / `DroolsConfig` / `META-INF/kmodule.xml` 等）已从仓库根 `src/` 迁入 **`drools-lab`** 模块（落在 `drools-lab/src/main/...`），本文中形如 `service/HotReloadService.java`、`rules/hello/hello.drl`、`audit/RuleAuditListener.java`、`META-INF/kmodule.xml` 的相对路径均相对该模块根。`drools-lab` 是**库模块（不含 Spring Boot 启动类）**：Step 1–24 的 REST 入口由依赖它的 **`activity-console`** 应用对外提供（`ConsoleApplication`，端口 **8081**，`@SpringBootApplication(scanBasePackages="com.lrj.drools")` 把 lab 里的 controller 扫进来）。因此根 `./mvnw spring-boot:run` 已失效，改用 `./mvnw -pl activity-console spring-boot:run` 起服务后再打本文各接口。
>
> **不想 curl 就开 SPA**：前端「规则能力」目录（`/ui/capabilities`）已把 Step 1–24 的全部端点做成可点即跑的示例台（每个 Step 预置若干示例请求，执行后除了 HTTP 状态还会把**最近 12 次**耗时画成 sparkline；换示例会清空该序列，避免不同 payload 的耗时混进同一条线）。SPA 要么用 `./mvnw -pl activity-console -Pfrontend spring-boot:run` 打进 console 的 `static/ui/`，要么走 compose 的网关 `http://localhost:8095/ui/capabilities`（编排里前端由 gateway 镜像托管，不在 console 的 JAR 里）。
>
> **8082 的 `activity-decision` 不含 Step 1–24**，它暴露活动引擎的决策 / 观测端点（`/decision/v1/**`：`spu-discount` / `gifts` / `addon/*` / `metrics` / `by-activity` / `snapshot` / `snapshot/rollback`）。它已改回 `ddl-auto: validate`（只读平面不碰 DDL，建表由 console 独占），所以本地单独 `./mvnw -pl activity-decision spring-boot:run` 前得先让 console 起过一次把表建好，否则启动即 validate 失败。
>
> ⚠️ **「decision 是只读平面」这条断言从 2026-08-12 起有了一个例外**：`POST /decision/v1/snapshot/rollback`（把该业务线的决策快照切回上一代，是 `BenefitEvaluator` 出 bug 时的止损按钮）。
> 它**不写数据库**，所以只读账号那条边界依然成立——但它切的是进程内的快照指针，**下一次决策发出去的钱会立刻改变**。
> 正因为它不写库，才最容易被「只读平面」这四个字掩盖过去：它需要 `console-write-authority`，作用域是**本进程单实例**，且下一次代际推进就会被覆盖（是止血，不是回退发布）。
> 想改活动版本本身，仍然只能走 console 的 `POST /activity-marketing/{id}/status`。

## 各 Step 详解

规则能力按渐进式路径组织，从 Hello World 到“引擎安全护栏 / DMN / 真实业务场景”逐步加深：

- **Step 1 / Hello World**：`POST /hello` → `rules/hello/hello.drl`，覆盖 facts / when-then / 多规则独立触发
- **Step 2 / 订单折扣**：`POST /discount/calculate` → `rules/discount/order-discount.drl`，覆盖 salience 优先级、跨事实 join、规则叠加
- **Step 3 / 购物车**：`POST /cart/checkout` → `rules/cart/cart-rules.drl`，覆盖 `accumulate`（按品类聚合）和 `modify`（字段级联触发其他规则）
- **Step 4 / 风控推荐**：`POST /risk/evaluate` → `rules/risk/risk-rules.drl`，覆盖 `not` / `exists` 两种否定/存在判断，并用“insert 标记 fact + not 检测”替代 `no-loop` 做自终止
- **Step 5 / agenda-group 流水线**：`POST /pipeline/run` → `rules/pipeline/pipeline-rules.drl`，覆盖 `agenda-group`（validate→discount→risk→notify 四阶段）+ `setFocus` 栈式控制 + `auto-focus` + `lock-on-active`
- **Step 6 / 规则可观测性**：`POST /pipeline/audit` → `audit/RuleAuditListener.java`，挂 `AgendaEventListener` + `RuleRuntimeEventListener` 把规则触发轨迹打成结构化 `AuditEvent[]` 一起返回，能直接"看见" agenda 栈压弹和 auto-focus 时序
- **Step 7 / 决策表**：`POST /decision/calculate` → `rules/decision/vip-discount.xls`，业务方用 Excel/Numbers 直接维护 VIP 折扣档位；XLS 由 `drools-lab/src/test/java/.../VipDiscountSheetGenerator` 一键生成
- **Step 8 / CEP 滑窗风控**：`POST /fraud/check` → `rules/fraud/fraud-rules.drl`，覆盖 `@role(event)` / `@timestamp` / `over window:time(5m)` / pseudo clock 推进事件时间线
  - **扩展 `POST /fraud/patterns`** → `rules/fraudcep/fraud-cep.drl` + `service/FraudCepService.java`（独立 `fraudCepKBase`，同样 stream + pseudo）。补齐另外三种 CEP 形态：① **长度滑窗** `over window:length(5)`（按最近 N 条事件，对比 time 窗按时钟）；② **时序操作符** `this after[0s,2m] $small`（Allen 区间代数，"小额试探→紧接大额"）；③ **多 entry-point** —— `OrderEvent` 走 `"order-stream"`、`LoginEvent` 走 `"login-stream"`，规则跨流关联"登录后 30 秒内大额下单"。`FraudCepService` 把两条流按 timestamp 合并成一条时间线再推进时钟；告警输出 `FraudAlert`（带 type），原 `/fraud/check` 的 `BurstAlert` 一字节不动
- **Step 9 / 规则热加载**：`POST /hot/upsert` + `POST /hot/run/{name}` → `service/HotReloadService.java`，把 DRL 字符串运行时编译成 KieBase 缓存进 `ConcurrentHashMap`，同名 upsert 替换；编译错误返回 400 + 行号
- **Step 10 / KieSession 持久化**：`POST /loyalty/start` + `POST /loyalty/{id}/purchase` + `GET /loyalty/{id}` → `service/LoyaltyService.java`，用 `Marshaller` 把整个 working memory + agenda state 序列化成 byte[]，经 Spring Data JPA 存库（**默认 mysql profile**；显式 `-Dspring-boot.run.profiles=h2` 时才落 H2 file `activity-console/data/activity-platform.mv.db`）。同一 sessionId 跨请求、跨重启接着上次状态继续累积积分 + 链式升级 (BRONZE → SILVER → GOLD)
- **Step 11 / StatelessKieSession 对比**：`POST /stateless/calculate` + `POST /stateless/batch` → `service/StatelessDiscountService.java`，复用 Step 2 的 `discountKBase` 但派生 `type="stateless"` 的 ksession；同输入结果跟 stateful 完全等价。教学重点：API 极简 (无 `dispose()`)、实例可复用 (线程安全)、execute(Iterable) 一次插入多 fact、批处理零隔离成本
- **Step 12 / TMS (Truth Maintenance System)**：`POST /tms/compare` → `service/TmsService.java` + `rules/tms/logical/` + `rules/tms/regular/`。同一组 LHS 各写两份 DRL，一份用 `insertLogical` 一份用 `insert`，单次请求里在两个 kbase 各跑两阶段 fire（先 value=95 触发告警 → modify 改成 50 再 fire）。结果对比：logical 的衍生 Alert 被引擎自动 retract，regular 的依然留在 working memory。展示"前提-结论"因果链由引擎维护
- **Step 13 / 后向链 + query**：`POST /backward/contains` → `service/BackwardChainingService.java` + `rules/backward/backward-chaining.drl`。前面 Step 1-12 全是前向链（data-driven push）；这一步用经典 `isContainedIn(x, y)` 递归 query 实现反向（goal-driven pull）推理。给定 Location(thing, container) 直接关系，引擎反向证明“Office 是否在 Continent 里”，递归走 Office→House→City→Country→Continent 四跳
  - **扩展 `POST /backward/derive`**：换消费方式——不再走 Java 侧 `getQueryResults` 主动 pull，而是插一批 `WatchTarget(thing, zone)` 驱动 fact，让**前向链规则**在 LHS 用 `?isContainedIn($thing, $zone;)` 反向拉起后向证明（`?` 前缀 = pull 式查询调用），成立则 `fireAllRules` 时 insert `ContainmentFinding`。同一个 `isContainedIn` query 被 Java pull 与规则 LHS 的 `?query` push 两种范式共用
- **Step 14 / 引擎安全护栏**：`POST /guard/runaway` + `POST /guard/timeout` + `POST /guard/canary` → `service/GuardService.java` + `guard/ReleaseAgendaFilter.java` + `rules/guard/guard-rules.drl`。第一个偏"生产工程"的 Step，给规则引擎加兜底防失控。三个护栏：① `fireAllRules(maxFires)` 硬上限熔断（失控自增规则被截断在 N 条）；② 另一线程 `session.halt()` watchdog 超时打断（裸 fireAllRules 跑到 timeout 被优雅终止，非 kill 线程）；③ `fireAllRules(AgendaFilter)` 按规则 `@release(...)` 元数据灰度放行（canary 规则编译进 KieBase 但运行时被拦，改白名单即放量，不重编译不重启）。失控靶子用 `Counter`（mutable POJO）触发，灰度靶子用 `Cart` 触发，同 kbase 靠 fact 类型天然隔离
- **Step 15 / 规则可观测性指标**：`POST /metrics/discount` → `service/MeteredDiscountService.java` + `metrics/MeteredRuleListener.java`，指标经 `GET /actuator/prometheus` 暴露。把 Step 6 的 listener 思路从"攒事件数组"升级成"打 Micrometer 指标"：复用 Step 2 的 discountKBase 零改动，挂 `MeteredRuleListener`（fire/match/fact 累加成 counter）+ 一个 `Timer` 包住 fireAllRules（耗时 + p50/p95/p99）。指标：`drools_rules_fired_total{rule}`（哪条规则最热）/ `drools_matches_created_total` / `drools_matches_cancelled_total` / `drools_facts_total{op}` / `drools_session_fire_seconds`。Step 6 是单请求"放大镜"，Step 15 是跨请求"仪表盘"，两者互补
- **Step 16 / KieScanner + KJAR**：`POST /scanner/deploy` + `POST /scanner/run` + `POST /scanner/poll/start|stop` + `GET /scanner/status` → `service/ScannerService.java`。Step 9 热加载的"生产正解版"：DRL → 程序化打成 **KJAR**（带 kmodule.xml + pom 的标准 Maven 构件）→ `KieMavenRepository.installArtifact` 装进本地 `~/.m2` → `KieContainer` 绑 **ReleaseId**（`com.lrj.rules:scanner-cart-rules:1.0.0-SNAPSHOT`）而非 classpath → `KieScanner.scanNow()`（或 `start(ms)` 自动轮询）发现同 GAV 新内容就**热替换 KieBase**，container 不重建、应用不重启。跟 Step 9 的对照：Step 9 是"DRL 字符串 → Map 缓存"的应用内临时态；Step 16 是"规则跟代码独立发版"的工业路径（规则团队 mvn deploy → 所有实例的 scanner 自动拉到）
  - **扩展 `POST /scanner/update-version`**（`KieContainer.updateToVersion`）：跟 SNAPSHOT + scanNow 对照的另一条路——每次 install 到一个**新的固定 release**（1.0.1 / 1.0.2 …）再 `container.updateToVersion(newReleaseId)` **手动切**过去。固定 release 内容不可变、可精确回滚到任一历史版本，不经 scanner（所以**不触发** KieScannerEventListener），适合"版本号治理 + 可回退"
  - **扩展 `GET /scanner/events`**（`KieScannerEventListener`）：给 scanner 挂监听器，攒它的 `STATUS_CHANGE`（SCANNING/UPDATING/STOPPED…）+ `UPDATE_RESULTS` 事件。只有走 scanner 的 scanNow / 自动轮询会触发；`updateToVersion` 那条手动路径不产生 scanner 事件
- **Step 17 / DMN (Decision Model and Notation)**：`POST /dmn/price` → `service/DmnService.java` + `rules/dmn/vip-pricing.dmn`。**第一个非 DRL 体系的 Step**：DMN 是 OMG 跨厂商标准（`.dmn` XML + FEEL 表达式 + 决策需求图 DRG），跟前面 Step 1-16 全部基于 DRL 的引擎是两套独立东西。模型 `VipPricing` 覆盖：结构化输入（`tCustomer` 带 schema）→ `Discount Rate`（DMN **原生**决策表，hitPolicy UNIQUE，对照 Step 7 的 Excel→DRL 决策表）→ `Final Price`（FEEL 字面表达式，依赖 Discount Rate 形成**决策链**）+ `Membership Tier`（FEEL if/else）。DMN 不走 KieSession/fireAllRules，而是 `DMNRuntime.evaluateAll(model, context)` 按 DRG 拓扑求值
- **Step 18 / 营销活动资格判定**：`POST /campaign/create` + `POST /campaign/{id}/check` + `POST /campaign/{id}/end` + `GET /campaign/list` → `service/CampaignService.java` + `domain/UserProfile.java` + `domain/Eligibility.java` + `persistence/CampaignEntity.java`。**第一个把多步拼成"实际业务场景"的 Step**：运营创建活动时**绑定一段资格规则 (DRL)**，用户申请参加时判定够不够格。三个 Step 的合体——① 创建活动复用 **Step 9** 的 `KieHelper` 运行时编译 DRL（编译失败返回 400 + 行号，绝不把跑不起来的规则落库）；② 规则**源文本**持久化到 `campaign` 表（复用 **Step 10** 的 JPA 思路，但存的是 DRL 文本而非 marshall 的 session byte[]；DB 默认 MySQL，可切 H2 profile）；③ 资格判定走 **Step 4** 的"白名单标记 fact"套路——默认不够格，规则只在满足条件时 `insert(new Eligibility(true, reason))`，fire 完看 working memory 有没有 `Eligibility(eligible==true)`。两级存储：内存 `ConcurrentHashMap<campaignId, KieBase>` 当热路径缓存 + DB 存规则文本；应用重启后内存空了，check 时 `computeIfAbsent` 从 DB 捞 DRL 重新编译（rehydrate），所以活动不随重启丢失——这正是"Step 9 + 持久化"比纯 Step 9 多出来的能力。多活动靠"一个 campaignId 一个独立 KieBase"天然隔离，不像 Step 12 要担心衍生 fact 互相污染
- **Step 19 / LHS 量词补全**：`POST /quantifier/review` → `rules/quantifier/quantifier-rules.drl` + `service/QuantifierService.java`。补 Step 4（`not`/`exists`）没覆盖的另外三种 LHS 条件元素，用"订单合规审查"场景串起来：① **`collect`** —— `List(size >= 3) from collect(OrderItem(category == "BOOK"))` 把匹配 fact 收集成集合再对集合本身做约束（对比 Step 3 `accumulate` 出的是聚合标量）；② **`forall`** —— `forall($i: OrderItem() OrderItem(this == $i, category != null, unitPrice > 0))` 全称量词，注意**空集为真**（vacuous truth），前置 `exists OrderItem()` 兜底；③ **`eval`** —— 逃生舱式任意布尔表达式，判"总额 > 按 VIP 等级动态算的免审额度"（依赖跨 fact 聚合值 + DRL `function reviewThreshold`，无法索引化才用它）。规则产出 `ReviewFinding` 标记 fact，service 从 working memory 捞回来
- **Step 20 / RHS 对外**：`POST /dispatch/run` → `rules/dispatch/dispatch-rules.drl` + `service/DispatchService.java` + `service/NotificationSink.java`。规则动作侧往引擎外部推副作用，两条正交出口：① **`global`** —— `global ...NotificationSink sink;` 注入外部句柄，RHS 主动调 `sink.audit(...)`（pull 式，fire 前必须 `session.setGlobal` 否则 NPE）；② **`channel` / exit point** —— RHS `channels["notify"].send(new Notice(...))` 把对象 push 给 Java 侧 `session.registerChannel("notify", ...)` 注册的回调，对象不进 working memory。大额订单走 global 记审计、VIP 客户走 channel 发通知；`sink` 与 channel 收集器都每请求 new（KieSession 不线程安全不复用）
- **Step 21 / traits**：`POST /traits/evaluate` → `rules/traits/traits-rules.drl` + `domain/Applicant.java`（`@Traitable`）+ `service/TraitsService.java`。给 fact **运行时**贴/摘"接口实现"做动态多态：`@Traitable` 的 `Applicant` 核心对象，规则里 `don($a, PremiumApplicant.class)` 贴上一层 trait（由 `declare trait` 生成的接口），之后这个 applicant **同时"是" PremiumApplicant**，能被 `PremiumApplicant(...)` 模式匹配到；trait 里核心类没有的字段（tier/creditLimit）存进核心对象的隐藏动态属性表。`don`/`shed`/`isA` 是 drools-traits（8.x 独立模块）提供的关键字。跟普通 Java 继承的区别：运行时可加可减。⚠ `@Traitable` 在 `org.drools.base.factmodel.traits`（drools-base，已传递）
- **Step 22 / fireUntilHalt**：`POST /fireuntilhalt/process` → `rules/fireuntilhalt/fire-until-halt.drl` + `service/FireUntilHaltService.java`。跟前面所有 Step "insert → fireAllRules → dispose" 的最大不同：fire 跑在**另一个守护线程**里，是个**不返回**的循环——`fireAllRules` 把 agenda 跑空就返回，`fireUntilHalt` 空了就阻塞等新事实，像常驻消费者。编排：起线程跑 `session.fireUntilHalt()` → 主线程逐个 insert Task 被实时消费 → 最后 insert 一个 `id="__STOP__"` 哨兵任务，规则 RHS 调 `drools.halt()` 让循环退出 → join 线程后捞结果。用哨兵而非主线程直接 halt，是因为 fireUntilHalt 异步消费、主线程 halt 可能在任务没处理完时就打断
- **Step 23 / 规则模板 .drt**：`POST /template/discount` → `resources/templates/discount-template.drt` + `service/TemplateService.java`。跟 Step 7 决策表同源——决策表本质就是"模板的表格皮"，drools-decisiontables 内部把表格行喂给同一套模板引擎；这里直接用底层 `org.drools.template.ObjectDataCompiler`：把每档折扣配置（minAmount/maxAmount/discount）做成 Map 数据行 → `compile(数据行, 模板流)` 展开成 DRL（每行一条 rule）→ 走 **Step 9 的 KieHelper** 编译成 KieBase 跑。价值：规则结构固定、只有阈值在变时，模板 + 数据比手写 N 条近乎重复的 DRL 好维护，且数据行可来自 DB/请求（"规则即数据"）。响应带上生成的 DRL 方便看展开结果
- **Step 24 / PMML（规则里嵌 ML 模型评分 + Scorecard）**：`POST /pmml/score` + `GET /pmml/models` → `resources/pmml/*.pmml` + `service/PmmlService.java`。PMML 是 DMG 跨厂商标准，把训练好的模型（评分卡 / 回归 / 决策树 / 混合）写成 XML，用**独立求值引擎**跑，跟 DRL/RETE（Step 1–16）和 DMN（Step 17）都不是一套。8.44.2 走 trusty(efesto) 实现，`.pmml` 运行时编译成 Java 类再求值。上线两个模型：`credit-scorecard`（评分卡，初始分 + 各特征档位分 + reasonCode）+ `risk-regression`（线性回归）。**接法有坑**（下面 impl 注记 + `docs/plans/drools-capabilities-tier3-0817-1430/PLAN.md` §1 有完整复盘）：官方便捷工厂 standalone 不通，得自己把编译和求值串进同一个 classloader + 反射灌 `generatedResourcesMap`；且 fat jar 里 JAXB RI 必须用 `com.sun.xml.bind:jaxb-impl:2.3.1`（不能用被 Spring Boot BOM 管到 4.0.5 的 glassfish jaxb-runtime），否则启动崩

> **Rule Units（原拟 Step 25）已回退**：`drools-ruleunits-impl` 与 `drools-traits`(Step 21) 争 Drools 唯一的 `RuntimeComponentFactory` SPI 单例、二者互斥（加它 traits 就 `ClassCastException`）；且解释 provider 在 Spring Boot fat jar 里扫不到自己的 DRL（`collectResourcesInJar` 打不开 nested jar）。surefire 测得过、部署即崩，是"不追 incubator"要防的那类不稳定。详见 PLAN §5/§10。

后续（LLM 联动）按需扩展，**没需求时不要提前加**。Step 16 的 `kie-ci` 是重依赖（会引入 maven-core / aether 等传递依赖），且 `installArtifact` 会写入本机 Maven 仓库的 `com/lrj/rules/` 坐标；执行前应确认这项本地副作用。Step 10 / Step 18 的 JPA 仅服务于对应的规则持久化能力，不应据此把全项目状态都迁入数据库。

## REST 接口

| 方法 | 路径                    | 说明 |
| ---- | ----------------------- | ---- |
| POST | `/hello`                | Step 1：插 Customer，跑 helloSession，返回触发条数 |
| POST | `/discount/calculate`   | Step 2：插 Customer + Order，跑 discountSession，返回带折扣的 Order |
| POST | `/cart/checkout`        | Step 3：插 Cart（内含 items + customer），跑 cartSession，返回带折扣 + goldStatus 的 Cart |
| POST | `/risk/evaluate`        | Step 4：插 Cart，跑 riskSession，规则会 insert Promotion fact 并汇总到 recommendations |
| POST | `/pipeline/run`         | Step 5：插 Cart，按 validate→discount→risk→notify 四阶段跑 pipelineSession |
| POST | `/pipeline/audit`       | Step 6：跑同样的 pipeline 但挂 listener，响应里包含 `auditTrail: AuditEvent[]` |
| POST | `/decision/calculate`   | Step 7：插 Cart，跑 decisionSession，VIP 折扣档位读 `vip-discount.xls` |
| POST | `/fraud/check`          | Step 8：批量插 OrderEvent，按 timestamp 排序 + 推进 pseudo clock + 滑窗检测 burst，返回 `BurstAlert[]` |
| POST | `/fraud/patterns`       | Step 8 扩展：orders + logins 两条流合并成时间线，覆盖 `window:length` / `after[..]` 时序 / 多 entry-point，返回 `FraudAlert[]`（type = LENGTH_BURST / PROBE_THEN_STRIKE / FAST_ORDER_AFTER_LOGIN） |
| POST | `/hot/upsert`           | Step 9：推 DRL 字符串运行时编译并缓存到 registry；同名替换；编译错误 400 |
| POST | `/hot/run/{name}`       | Step 9：用 registry 里 name 对应的 KieBase 跑 cart |
| GET  | `/hot/list`             | Step 9：列出当前已注册的规则名 |
| POST | `/loyalty/start`        | Step 10：新建会话, 注入空 LoyaltyState, marshall 落盘 |
| POST | `/loyalty/{id}/purchase`| Step 10：恢复会话, 插入 PurchaseEvent, fire 触发积分+升级, 再 marshall 落盘 |
| GET  | `/loyalty/{id}`         | Step 10：只读 peek 当前会话状态 (不 fire, 不写回) |
| POST | `/stateless/calculate`  | Step 11：跟 `/discount/calculate` 同入参同输出, 走 StatelessKieSession |
| POST | `/stateless/batch`      | Step 11：一次提交 N 个 Order, stateless 单实例反复 execute, 单笔间完全隔离 |
| POST | `/tms/compare`          | Step 12：同 Sensor 在 logical / regular 两个 kbase 各跑两阶段 fire, 返回前后两次 Alert 快照, 看 TMS 自动撤销 |
| POST | `/backward/contains`    | Step 13：注入一组 Location 直接关系 + 一组查询, 用 `isContainedIn` 递归 query 反向证明每条查询是否成立 |
| POST | `/backward/derive`      | Step 13 扩展：注入 Location + 一组 `WatchTarget(thing, zone)`, 前向规则 LHS 用 `?isContainedIn` 拉起后向证明, fire 后返回 `ContainmentFinding[]` |
| POST | `/guard/runaway`        | Step 14：插 Counter 跑失控自增规则, fireAllRules(maxFires) 硬上限截断, 返回 fire 次数 + 截断时的值 |
| POST | `/guard/timeout`        | Step 14：失控规则裸跑, watchdog 线程在 timeoutMillis 后 halt() 打断, 返回中断前 fire 次数 |
| POST | `/guard/canary`         | Step 14：插 Cart 跑三条带 @release 标记的规则, AgendaFilter 按 allowedReleases 白名单放行, 返回结果 + 被拦规则 skipped |
| POST | `/metrics/discount`     | Step 15：跟 /discount/calculate 同入参同折扣, 但挂 MeteredRuleListener + Timer, 把 fire/match/fact/耗时打进 Micrometer |
| GET  | `/actuator/prometheus`  | Step 15：Prometheus 抓取端点, grep `drools_` 看规则指标随调用累积（同端点还混着活动引擎平台的 `activity_*` 指标，见下方注意点） |
| POST | `/scanner/deploy`       | Step 16：DRL 打成 KJAR 装进本地 ~/.m2, 首次创建 container 否则 scanNow 热替换; 编译错误 400 |
| POST | `/scanner/run`          | Step 16：用当前 live KieBase 跑 cart, 返回 fire count + cart + generation (内容代次) |
| POST | `/scanner/poll/start`   | Step 16：开 KieScanner 自动轮询 (默认 5000ms), 生产形态——deploy 后无人值守自动生效 |
| POST | `/scanner/poll/stop`    | Step 16：停自动轮询 |
| POST | `/scanner/update-version` | Step 16 扩展：install 到新固定 release + `KieContainer.updateToVersion` 显式切换（不经 scanner）；编译错误 400 |
| GET  | `/scanner/events`       | Step 16 扩展：`KieScannerEventListener` 攒到的热替换事件（STATUS_CHANGE / UPDATE_RESULTS） |
| GET  | `/scanner/status`       | Step 16：看 releaseId / container 是否就绪 / 当前 generation / 是否在轮询 |
| POST | `/dmn/price`            | Step 17：插 Customer + orderAmount, 走 DMN 模型求值, 返回 Discount Rate / Final Price / Membership Tier 三个决策结果 |
| POST | `/campaign/create`      | Step 18：运营创建活动 + 绑定资格规则 (DRL), 编译并落库; 同 campaignId 覆盖; 编译失败 400 + 行号 |
| POST | `/campaign/{id}/check`  | Step 18：插 UserProfile 判定够不够格, 白名单式 (命中规则才 insert Eligibility), 返回 eligible + reasons |
| POST | `/campaign/{id}/end`    | Step 18：结束活动 (status→ENDED), 之后 check 返回 409; 清掉内存 KieBase 缓存 |
| GET  | `/campaign/list`        | Step 18：列出所有活动 (含 status + 是否已编译进内存缓存 cached) |
| POST | `/quantifier/review`    | Step 19：插 Customer + OrderItem[]，跑 collect/forall/eval 三条规则，返回 `ReviewFinding[]`（BULK_BOOK / ALL_LINES_VALID / MANUAL_REVIEW） |
| POST | `/dispatch/run`         | Step 20：插 Cart(customer+items)，大额订单经 global 记审计、VIP 客户经 channel 发通知，返回 `{auditLog, notices}` |
| POST | `/traits/evaluate`      | Step 21：插 Applicant[]，高收入者 `don` 上 PremiumApplicant 后被 trait 类型匹配，返回 `TraitFinding[]` |
| POST | `/fireuntilhalt/process`| Step 22：守护线程跑 fireUntilHalt，逐个 insert Task 实时处理，哨兵任务 halt 收尾，返回 `{processed, processedCount}` |
| POST | `/template/discount`    | Step 23：档位配置(tiers) + .drt 模板 → 生成 DRL 编译跑，返回 `{order, firedCount, generatedDrl}` |
| POST | `/pmml/score`           | Step 24：{model, inputs} → trusty PMML 求值，返回 `{model, resultCode, variables}`（评分卡/回归） |
| GET  | `/pmml/models`          | Step 24：列出已编译的 PMML 模型 key |

## 各 Step 的 DRL 语义 / 实现注意点

- `accumulate(... from $cart.getItems(), sum($q))` — 用 `from` 锁数据源到 Java 集合，不扫整个 working memory（多个 Cart 并发不会窜户）。但 list 内部增删 working memory 感知不到，要么 Java 侧改完显式 `update(cart)`，要么把 `OrderItem` 也 `insert` 成独立 fact
- accumulate 的 `$result` 类型是 `Number`，所以写 `intValue >= 5` / `doubleValue >= 1000`，**不能直接 `$result >= 5`**
- `modify($cart) { setGoldStatus(true) }` 比 `update` 精准（告诉引擎具体哪个属性变了），Phreak 能更精确传播。modify 仍不防死循环，靠"LHS 自然不再满足"或 `no-loop` 终止
- Step 3 的 Promote → Gold extra 级联：goldStatus 是某规则 LHS 判断字段，modify 改它后那条规则被重新评估 → 这就是教学要观察的"字段级联"
- Step 4 的 `not` / `exists` 都用 `from $cart.getItems()` 锁数据源到 Java 集合；而 `not Promotion(...)` 不带 `from` → 检 working memory。两种语义不要混淆
- Step 4 用"`insert(new Promotion(...))` + `not Promotion(...)`"做自终止，比 `no-loop true` 更通用：能跨规则、能 retract 重置
- Step 5 的 agenda 是 **LIFO 栈**，`setFocus("validate") + setFocus("discount") + setFocus("risk")` 实际执行顺序是 **risk → discount → validate**（最后压栈的先弹）。想 validate 先跑，Java 侧 setFocus 顺序要反着写，PipelineService.java 就这么写的
- Step 5 的 `lock-on-active true` vs `no-loop true`：前者在当前 agenda-group 拿焦点期间锁死该规则（任何外部 update/modify 都不重激活）；后者只防"自己 RHS 重激活自己"。生产规则集做"阶段开关"用前者，做"单条规则 update 自身"用后者
- Step 6：`org.kie.api.definition.rule.Rule` 公共接口**没有** `getAgendaGroup()` 方法（只在内部 `RuleImpl` 上）。Listener 里想知道 MATCH 属于哪个 group，看上下文最近的 `GROUP_PUSHED` 事件——agenda 是栈，当前栈顶就是规则所属 group
- Step 6：listener 一个实例对应一个 session（events 是非线程安全 ArrayList），跟 `KieSession` 一样按请求新建
- Step 7 决策表 schema：`RuleTable` 后必须按顺序排"列类型 → **对象声明** → 约束片段 → 标签 → 数据"五行，**漏了对象声明那行**会报 "It looks like you have snippets in the row that is meant for object declarations" — 这是 Drools 决策表新手最常踩的坑
- Step 7 决策表：Drools 8.44.2 的 ClasspathKieProject 不自动识别 `.xls/.xlsx/.csv`（即使在 kbase packages 目录下），必须程序化 `KieFileSystem` + `setResourceType(ResourceType.DTABLE)`。代码在 `DroolsConfig.kieContainer()`
- Step 8 CEP：事件必须按 timestamp 升序 insert，stream mode 不允许时间倒退；用 `SessionPseudoClock.advanceTime` 推进时钟到事件时刻再 insert，每个事件后立刻 `fireAllRules()` 否则滑窗结果不对
- Step 8 CEP：把 Java 类标成 event 的两种方式：①类上 `@Role(Role.Type.EVENT)` 注解；②DRL `declare ClassName @role(event) @timestamp(field) end`。本项目用方式 ② 不污染 domain 类
- Step 9 热加载用 `KieHelper` (内部 API, 包名带 `internal`)，胜在一行编译完 DRL → KieBase。生产更稳的是 `KieFileSystem + KieBuilder`（`DroolsConfig.kieContainer()` 用了那条路径），但学习场景 KieHelper 足够
- Step 9：老 KieSession **不会被** registry 里的 KieBase 替换影响——session 关联到创建时的 KieBase 引用，dispose 前一直用老的。这是热加载安全的关键，进行中的请求不会被打断
- Step 9 跟 KieScanner 生产路径的关系：KieScanner = “KJAR + Maven repo + 定时轮询版本号 + 自动 upsert”。当前能力把“上传 + upsert”暴露成 HTTP，增加一层 `@Scheduled` 定时轮询即可形成 KieScanner 等价路径
- Step 10：`MarshallerFactory` 在 Drools 8 移到 **`org.kie.internal.marshalling`**（不是 `org.kie.api.marshalling`），从 Drools 7 升级或照旧教程抄都会踩这坑。`Marshaller` 接口本身还在 `kie-api` 里
- Step 10：必须显式加 `drools-serialization-protobuf`，否则 `MarshallerFactory.newMarshaller(kb)` 拿不到实现（Drools 8 把 protobuf 序列化拆成独立模块）
- Step 10：所有进 working memory 的 fact 类**必须 `implements Serializable`**，Drools 默认用 Java 原生序列化把 fact 整体塞进 byte[]。record 不自动实现 Serializable，要手动声明（`public record PurchaseEvent(...) implements Serializable {}`）
- Step 10：`marshaller.unmarshall(InputStream)` 返回**新的** KieSession 实例，不是把状态注入回原 session。每次请求都是"load byte[] → 新 session → fire → save byte[] → dispose"四步走
- Step 10：marshall 时 agenda 上还没执行的 activation 也会被序列化进去，下次 unmarshall 后 fireAllRules 会接着跑剩下的。这是"中断恢复"语义的核心
- Step 10：改了 DRL 后老快照可能反序列化失败（规则签名变 / fact 类字段变）。本地开发可重建 `./data/` 中的状态；正式环境要采用“快照版本号 + DRL 变更迁移脚本”
- Step 10：跟 Drools 官方 `drools-persistence-jpa` 的差异：后者走 JTA + SessionInfo/WorkItemInfo 多张表 + 自动 commit；当前能力采用一张 `session_snapshot` 单表 + 手动 marshall 边界，适合轻量状态快照。Spring Boot 3 配 JTA（Bitronix/Atomikos）没有官方 starter，因此这里采用简化路径
- Step 11：`StatelessKieSession` 是 Drools 提供的"一次性"会话封装。`execute(Iterable)` = newSession + insertAll + fireAllRules + dispose 四步合一；用错（漏 dispose）的概率为 0
- Step 11：**StatelessKieSession 线程安全**，可以注成 Spring 单例反复用；KieSession（stateful）不能跨请求复用，每次都得 newKieSession。当前实现把 stateless 当字段持有，符合官方推荐用法
- Step 11：同一 KBase 可以同时挂 stateful + stateless ksession (kmodule.xml 里两条 `<ksession>` 不同 name + 不同 type)，业务规则零改动复用；选哪个看调用形态——RPC 单笔 / 批处理就 stateless，长寿命累积 / CEP / agenda 编排就 stateful
- Step 11：stateless 不能做什么：① 没有 fire 之间的干预点 (没法 setFocus 后再 fire)、② 不支持 stream mode + pseudo clock 跨调用重放、③ 没有 `getObjects()` 给你拿结果——结果靠传入 mutable fact 引用 (规则改完直接读 Java 对象)
- Step 11：跟 Step 10 持久化的关系：stateless 天生没有持久化需求 (没有跨调用状态)；marshall/unmarshall 那一套只对 stateful 有意义
- Step 12：`insertLogical(fact)` vs `insert(fact)` —— logical 把衍生 fact 跟"导出它的 LHS 匹配"绑定，匹配失配引擎自动 retract；普通 insert 跟前提解耦，要手动 retract 才能撤销。**这是 Drools 真正的 TMS 实现**，不是简单语法糖
- Step 12：要让 TMS 撤销发生，前提 fact 字段必须可变 (modify / update 才能"让 LHS 失配")。`Sensor` 故意做成 mutable POJO 而不是 record，跟 Step 10 `LoyaltyState` 同理
- Step 12 跟 Step 4 的对照：Step 4 `insert(Promotion) + not Promotion` 防的是"再次触发同一规则"，没有撤销语义 (Promotion 不会因为购物车变了消失); Step 12 `insertLogical(Alert)` 才是真撤销。两种模式按业务诉求选——一次性"发券"型用 Step 4，"状态告警"型用 Step 12
- Step 12：logical 和 regular 用两个 kbase 隔离是有意为之，避免"两份规则跑在同一 working memory 里 Alert 互相污染"。教学场景同请求并跑做对比；生产里只会选一种
- Step 13：query 是声明式查询模式，跟规则的核心区别是 **不参与 agenda 也不需要 fireAllRules**。`session.getQueryResults("name", args...)` 是同步 pull 调用，引擎当场启动后向链证明
- Step 13：位置模式 `Location(x, y;)` 末尾的分号是关键语法标记，告诉解析器这是位置参数解构。**fact 类字段必须 `@Position(N)` 注解**, 否则报 "Unable to find @Positional field 0 for class Location"。record 组件上加 `@Position` 也能识别 (走 FIELD target)
- Step 13：递归 query 的标准结构是 `base case or (链一步 and 递归调用)`。基础情形先匹配直接 fact, 失败时引擎尝试递归情形, 引入中间变量 z (不在 query 参数表里, 是 query body 内的"自由变量"绑定)
- Step 13：DRL 里也可以用 `?queryName(args;)` 在规则 LHS 触发后向链做“pull-driven 规则”——`/backward/contains` 走 Java API 路径（getQueryResults）更直观，扩展 `/backward/derive` 则覆盖 `?query` 的 LHS 形态：插一个 driver fact（`WatchTarget`），规则 LHS 用 `?isContainedIn($thing, $zone;)` 反向证明，成立才 fire。同一个 query 由两种范式共用
- Step 13：query 的“输出变量”模式（列出所有满足条件的绑定）要用 `org.drools.core.runtime.rule.impl.Variable.v` 占位 unbound arg，但这是 internal API；当前实现改成“枚举候选 + 逐个 boolean 后向链证明”，既避开 internal API 也让 query 复用价值更直白
- Step 14：`fireAllRules(int max)` 是应当默认带上的护栏——正式环境里几乎所有 `fireAllRules()` 都应写成带上限的版本。失控规则 fire 满 max 强制返回，不会把请求线程挂死。“Runaway increment”能力故意不写 no-loop，用于验证护栏是否生效
- Step 14：`session.halt()` 是 KieSession 上**少数几个能跨线程调**的方法。watchdog 线程在超时后调 halt(), 引擎跑完当前 activation 后优雅返回 (不是 kill 线程, 不留脏状态)。按"挂钟时间"兜底比按"fire 次数"更通用——有的规则一次 fire 就很慢, 次数卡不住。这是 KieServer 等部署里"单请求超时"的标准做法
- Step 14：`AgendaFilter.accept(match)` 在每条 activation **真正 fire 前**被调一次, 返回 false 这条就被跳过 (不执行 RHS, 但仍留在 agenda, 下次没 filter 的 fire 还能跑)。用它做灰度/金丝雀/紧急下线: 规则全量编译进 KieBase, 运行时按白名单决定哪些真正生效, **不重编译、不重启**
- Step 14：AgendaFilter 里读规则元数据走的是公共 API `Rule.getMetaData()` (返回 `Map<String,Object>`, key 是 `@release` 里的 `release`)。这跟 Step 6 注释里那条"`Rule` 公共接口没有 `getAgendaGroup()`"形成对照——元数据是公共的, agenda-group 不是
- Step 14：ReleaseAgendaFilter 约定"没标 @release 的规则默认放行 (视为稳定基线)", 这样灰度只控带标记的实验规则, 不会因为忘标 release 把基线规则一起拦掉。这是个有意的安全默认, 不是 Drools 强制的
- Step 14：失控规则 (Counter) 和灰度规则 (Cart) 共用一个 guardKBase / guardSession, 靠 fact 类型天然隔离 (跑 runaway 只插 Counter, 跑 canary 只插 Cart, 互不触发对方的规则)。不需要像 Step 12 那样拆两个 kbase, 因为这里没有"衍生 fact 互相污染"的问题
- Step 15：指标分两层挂。**listener 层** (`MeteredRuleListener`) 出 counter——`afterMatchFired` → `drools.rules.fired{rule}`、`matchCreated/Cancelled` → `drools.matches.*`、`object*` → `drools.facts{op}`；**service 层** 出 Timer——`fireAllRules` 整段耗时。为什么 Timer 不在 listener 里: listener 的回调是"单条 match fire 前后", 拿不到"整段 fireAllRules"的起止边界, 那是 service 调 fire 的那一行才有的
- Step 15：Micrometer → Prometheus 命名转换会**改名**。代码里 `drools.rules.fired` (点分) 在 `/actuator/prometheus` 输出成 `drools_rules_fired_total` (下划线 + counter 自动加 `_total` 后缀); `drools.session.fire` Timer 出 `drools_session_fire_seconds` (自动补单位)。查指标按下划线名, 别拿点分名去 grep
- Step 15：`Counter.builder(name).tags(...).register(registry)` 对**相同 meter id 幂等** (返回已存在实例), 所以 listener 每次事件都 builder→register→increment 是安全的, 不会重复创建 meter。但 `rule` 当 tag 要警惕 **高基数**: 规则名数量可控没事, 千万别把 orderId / customerId 这种无界值塞进 tag, 会把时序库打爆
- Step 15：`Timer.record(...)` 对返回 `int` 的 `fireAllRules` 有重载歧义 (`DoubleSupplier` / `Supplier<T>` / `Runnable` 都能匹配), 必须显式转 `(Supplier<Integer>)` 才能编译。这是 Micrometer + 原始类型返回值的常见坑
- Step 15：`management.endpoints.web.exposure.include` 默认只开 `health`, 必须显式加 `prometheus` 才有抓取端点。`management.metrics.tags.application` 给所有指标加公共 tag (本项目 `activity-platform`), 多实例部署时用来区分来源
- Step 15：`/actuator/prometheus` 上**不只有** `drools_*`。console 的 classpath 上还有 activity-common 的 `DecisionMetrics`(`@Component`), 它往**同一个 MeterRegistry** 打活动引擎平台那套埋点: `activity_decision_duration_seconds`(决策耗时, tag `scene`/`mode`) / `activity_decision_fallback_total`(**回退到旧 Java 逻辑的次数——会静默改发放金额, 平台侧头号告警项**) / `activity_decision_candidates`(候选活动数分布) / `activity_decision_source_total`(物料来自代际快照还是逐请求查库) / `activity_decision_hit_total`(按活动命中) / `activity_rule_compile_seconds` / `activity_rule_fire_ceiling_total` / `activity_rule_cache_{entries,hit_ratio,weight_kb}`。**按前缀分清楚**: `drools_` 是本 Step 的教学 listener + Timer, `activity_` 是平台产线埋点, 别混着 grep。缓存那三个 Gauge 在 `ActivityRuleRuntimeService` 构造时就 `bindKieBaseCache` 绑好了, 没打过任何活动决策请求也能读到值
- Step 15 的高基数警告在平台侧被真正执行了: `activity_decision_hit_total` 的 `activityId` 标签带 **200 的基数上限**(`DecisionMetrics.ACTIVITY_TAG_CAP`), 超出的一律并进 `__over_cap__` 哨兵——总量仍准, 只是分不出是哪几个活动。理由跟本 Step "别把 orderId/customerId 塞进 tag" 同源: 活动数是运营行为不是工程可控量, 序列爆掉的时刻恰好是活动最多的大促当天
- Step 14 / Step 15 的"产线对照版"就是 activity-common 的 `ActivityRuleRuntimeService`, 读完这两步再去看那个类能直接对上号: fire 上界按**该 KieBase 的编译规则数**派生 (`maxFiresBase + maxFiresPerRule × 规则数`, 非全局常量), `FireCeilingListener` 数到上界就 `halt()` (= Step 14 的 `fireAllRules(max)` + `halt()` 合体), 触顶后抛异常让 `safeRun` 回退旧 Java 逻辑, 同时打 `activity_rule_fire_ceiling_total` + `activity_decision_fallback_total{reason="fire-ceiling"}` (= Step 15 的"给护栏装仪表", 护栏不再静默)。`reason` 刻意是**有限集**(引擎侧 `compile-error` / `fire-ceiling` / `eval-error`; 查询侧另有 `engine-disabled` / `empty-decision` / `condition-tree-unavailable`), 绝不塞异常全文——编译错误里带行号和 DRL 片段, 进了标签就是基数爆炸
- Step 16：KieScanner 的“发现新版本”对 **release 固定版本（1.0.0）不触发**（Maven 契约：固定版本内容不可变）。必须用 **SNAPSHOT**（`1.0.0-SNAPSHOT`）才能同 GAV 滚动更新——`scanNow()` 重新解析并比对时间戳后替换。因此能力实验室固定一个 SNAPSHOT GAV 更新内容；正式规则发版应使用递增 release 版本 + `KieContainer.updateToVersion(newReleaseId)`
- Step 16：`installArtifact(ReleaseId, InternalKieModule, File pom)` 三个参数都不能省。pom 文件用 `KieBuilderImpl.generatePomXml(releaseId)` (返回 String) 写临时文件; KJAR 本身的 pom 由 `kfs.generateAndWritePomXML(releaseId)` 写进构件内部, 两者一个给 maven install 元数据、一个让 KJAR 自描述
- Step 16：`KieMavenRepository` / `KieScanner` / `InternalKieModule` / `KieBuilderImpl` 这些类来自 `kie-ci` (`org.kie.scanner.*` + `org.drools.compiler.kie.builder.impl.*`)。`KieScanner` 接口本身在 `kie-api`, 但实现要 kie-ci。没加 kie-ci 时 `newKieScanner` 会拿不到实现
- Step 16：KJAR 内的 kmodule 用 `KieModuleModel` 程序化生成 (`newKieBaseModel("scannerKBase").addPackage("rules.scanner")` + `newKieSessionModel("scannerSession")`), 跟主项目 `META-INF/kmodule.xml` 互不影响。DRL 的 `package rules.scanner` 必须跟 kbase 的 addPackage 对齐, 否则规则不进这个 kbase
- Step 16：老 KieSession 安全性跟 Step 9 同理——`container.newKieSession()` 拿的是 scanner 当前指向的 KieBase, scanNow 替换后**新建的** session 才用新 KieBase, 已经在跑的 session 跑完它的活。热替换不打断进行中的 fire
- Step 16：`scanNow()` 是同步立即扫描（测试/手动触发用），`start(intervalMillis)` 是后台线程周期轮询（正式运行形态，规则发布后无人值守自动生效）。当前 deploy 能力内部调用 scanNow，保证 HTTP 响应里立即看到新内容；`/scanner/poll/start` 用于验证自动轮询
- Step 17：`kie-dmn-core` 的版本**不在 drools-bom 管理范围内** (drools-bom 只管 org.drools:* 和部分 org.kie:*)。不写 `<version>` 直接报 "version is missing"。显式锁 `${drools.version}` (8.44.2.Final, KIE 各模块同步发版)。它传递带出 kie-dmn-api/model/backend/feel
- Step 17：`.dmn` 跟 `.xls` 决策表同病: Drools 8.44 的 ClasspathKieProject **不自动识别** `.dmn`, 必须程序化 `KieFileSystem` + `setResourceType(ResourceType.DMN)`。`DroolsConfig.kieContainer()` 里扫 `.dmn` 的循环是 Step 17 加的, 跟扫 `.xls` 那段并列
- Step 17：DMN 模型用 **DMN 1.2 命名空间**（`http://www.omg.org/spec/DMN/20180521/MODEL/`），Drools 8.44 对它解析最稳。手写 `.dmn` 时 `>` / `<` 在 XML 文本里要转义（`&gt;` / `&lt;`），当前模型的 `&gt;= 4` / `if ... &gt;= 3` 都已处理。模型有语法错会在**启动时**由 KieBuilder.buildAll() 抛出（不是 lazy），比 DRL 更早暴露
- Step 17：DMN 不走 KieSession/fireAllRules。从 kbase 取 `DMNRuntime` (`KieRuntimeFactory.of(kbase).get(DMNRuntime.class)`), `evaluateAll(model, context)` 按决策需求图 (DRG) 拓扑顺序求所有 decision。DMNRuntime 线程安全可复用, 当字段缓存 (跟 Step 11 StatelessKieSession 同理), 不像 stateful KieSession 要每请求新建
- Step 17：`DMNContext.set(key, value)` 的 **key 必须跟 .dmn 里 inputData 的 name 一字不差**, 包括空格 —— `"Order Amount"` 带空格也要原样传, 写成 `"OrderAmount"` 会导致那个输入为 null。`getModel(namespace, name)` 的两个参数也要跟 `<definitions namespace=... name=...>` 完全一致, 否则返回 null
- Step 17：FEEL 的 `number` 类型底层是 **BigDecimal**, 所以 `Discount Rate` / `Final Price` 返回的是 BigDecimal (JSON 序列化成普通数字, 如 `0.10` / `900.00`)。结构化输入 `Customer` 灌的是 `Map<String,Object>` (key 对应 itemComponent name), FEEL 里 `Customer.vipLevel` 按 key 取值
- Step 17 跟 Step 7 的对照: Step 7 是 Excel 决策表 → drools-decisiontables 编译成 DRL → 跑 KieSession (本质还是 DRL/RETE); Step 17 是 DMN 标准模型 → DMNRuntime 独立求值引擎。两者都能让业务方维护表格, 但 DMN 是跨厂商标准 + 自带 FEEL + 决策链, 可移植性和表达力更强; 决策表只是"规则的表格写法"
- Step 18：是个"合体 Step", 没有引入新 Drools 机制, 而是把 Step 9 (KieHelper 编译) + Step 10 (JPA/H2 持久化) + Step 4 (标记 fact) 拼成一个真实业务流。教学价值在"怎么组合", 不在单点能力。读它之前先读那三步
- Step 18：活动绑定的 DRL 由请求传入 (运营写的), **不放 resources/rules/ 也不进 kmodule.xml** —— 跟 Step 9 同理走 KieHelper 编译, 不依赖 DroolsConfig 那个 classpath KieContainer。所以加活动不用改 kmodule、不用重启
- Step 18：运营写的 DRL 必须 `import com.lrj.drools.domain.UserProfile` 和 `com.lrj.drools.domain.Eligibility` (全限定类名), 因为 KieHelper 编译的是裸 DRL 字符串, 没有项目的 import 上下文。RHS 里 `insert(new Eligibility(true, "理由"))` 是构造新对象, 不踩 record RHS accessor 的坑 (见坑 6); 但若 RHS 要读 UserProfile 字段得写 `$u.registrationDays()` 不是 `getRegistrationDays()`
- Step 18：白名单式判定的安全默认 —— "默认不够格, 命中规则才放行"。漏写规则的后果是"没人够格"(保守、安全), 而不是"所有人都放进来"。跟 Step 14 ReleaseAgendaFilter "没标 @release 默认放行" 是相反方向的默认值选择, 都是按"出错时往安全侧倒"设计的
- Step 18：两级存储的 rehydrate 是关键观察点 —— `registry.computeIfAbsent(campaignId, id -> compile(DB里的DRL))`。重启后内存缓存空 (list 里 cached=false), 第一次 check 触发从 DB 捞 DRL 重新编译进缓存 (cached 翻 true)。这就是"规则即数据 + 持久化"相比 Step 9 纯内存态的增量。编译贵, 所以缓存; DB 是真相源, 所以重启不丢
- Step 18：`session.getObjects(ObjectFilter)` 用 lambda `o -> o instanceof Eligibility` 过滤 working memory 拿衍生 fact, 比 Step 4 在 RHS 里手动往 list 里塞更直接。一个活动可能多条规则命中 (示例里"新人"+"一线城市"两条都中), reasons 收集成列表全返回
- Step 8 扩展 (`/fraud/patterns`)：`over window:length(N)` 跟 `over window:time(T)` 的分水岭是"按条数 vs 按时钟"——length 窗只看最近 N 条到达的事件, 跟 pseudo clock 推没推进无关; time 窗反过来。两者都写在 `from accumulate(... over window:...)` 里
- Step 8 扩展：`over window:length(5) from entry-point "order-stream"` 是"滑窗修饰 + 数据源修饰"同时挂在一个 pattern 上, **顺序是 `over window:...` 在前、`from entry-point` 在后**（跟 Drools 官方 StockTick 示例一致, 写反解析不过）
- Step 8 扩展：时序操作符 `$big : OrderEvent(this after[0s, 2m] $small)` 是 Allen 区间代数——`after[min,max]` 表示 $big 在 $small 之后 min~max 内发生。`before` / `coincides` / `during` 等同族语法一致, 都比较**事件时间戳**, 跟事件在哪个 entry-point 无关 (所以跨流关联"登录后 30s 内下单"能成立)
- Step 8 扩展：多 entry-point 靠 `session.getEntryPoint("order-stream").insert(...)` 分流, **不是** `session.insert(...)`。⚠ RHS `insert(...)` 落**默认** entry-point, 所以 `session.getObjects()` 能捞到 `FraudAlert`, 但捞不到具名流里的原始 `OrderEvent`/`LoginEvent`。两条流要按 timestamp 合并成一条时间线再逐个推进时钟, 否则跨流 `after` 因时钟乱序失配
- Step 19：`collect` vs `accumulate` —— `List(size >= 3) from collect(...)` 出的是**集合本身**(能拿到具体哪些 fact), `accumulate(..., sum/count)` 出的是**聚合标量** `Number`。`List(size >= N)` 里 `size` 能当属性用是 MVEL 对集合的特殊处理
- Step 19：`forall` **空集为真**（vacuous truth）——`forall(OrderItem(...))` 在一条 OrderItem 都没有时也成立，空购物车会被误判“全部合规”。当前规则前置一条 `exists OrderItem()` 兜底。两模式 `forall($i: 基准 附加约束)` 用 `this == $i` 锁到同一条 fact，是官方 Bus 示例的标准写法
- Step 19：`eval(任意布尔)` 是 LHS 最贵的元素——**不进 RETE 字段索引，每次相关 fact 变化整段重算**。只在“确实无法索引化”时使用（当前规则用于判断“跨 fact 聚合总额 > 按 VIP 等级动态计算的阈值”）；能写成 `Customer(vipLevel >= 2)` 这种字段约束或 join 的，不应退化成 eval。DRL `function reviewThreshold(int)` 是包级辅助方法，LHS/RHS 都能调用
- Step 20：`global` 是 pull 式外部句柄——`global ...NotificationSink sink;` 声明后 **fire 前必须 `session.setGlobal("sink", 对象)`**, 规则引用了 global 却没注入会 NPE。RHS 主动调 `sink.audit(...)`, 常用于记日志 / 读外部配置 / 累加
- Step 20：`channel` 是 push 式 exit point——RHS `channels["notify"].send(obj)` (`channels` 是 RHS 隐式可用的 Map, 跟 `drools`/`kcontext` 一样) 把对象交给 Java 侧 `session.registerChannel("notify", ch)` 注册的回调, **对象不进 working memory**。`Channel` 是单方法接口, 可用 lambda。跟"insert 标记 fact 再 getObjects 捞回来"(Step 4/8/19) 是两条不同的出参路径
- Step 20：`global` 与 `channel` 都每请求 new (KieSession 不线程安全不复用), 别注成 Spring 单例跨请求共享可变状态。RHS 读 record 字段仍守坑 6——`$cart.getCustomer().name()` 不是 `getName()`
- Step 16 扩展：`updateToVersion` 走**固定 release**(1.0.1/1.0.2…)不是 SNAPSHOT——KieScanner 的"发现新版本"对固定 release 不触发(内容不可变是 Maven 契约)，所以显式切换这条路必须递增版本号。它不经 scanner，因此**不产生** KieScannerEventListener 事件；只有 deploy 的 scanNow / 自动轮询会
- Step 16 扩展：`buildAndInstall(drl, releaseId)` 被 deploy(SNAPSHOT) 和 updateToVersion(固定版) 共用；两条路都先编译校验(错误带行号)再 `installArtifact` 到各自 GAV。listener 在 scanner 首次创建时 `scanner.addListener(...)` 挂上
- Step 21：`@Traitable` 必须来自 `org.drools.base.factmodel.traits`(drools-base，已随核心传递)，别写成别的包；`don`/`shed`/`isA` 关键字要 classpath 上有 **drools-traits**(8.x 独立模块)才认。核心类要 mutable POJO(带 getter/setter)，trait 代理要能读写核心字段；trait 里核心类没有的字段(tier/creditLimit)存进 @Traitable 加的隐藏动态属性表
- Step 21：don 之后 trait 代理被自动插入 working memory，所以"贴了 trait 的核心对象能被 `TraitName(...)` 模式匹配"——这是动态多态的观察点。没 don 的普通 Applicant 不会被 trait 类型的规则看到
- Step 22：`fireUntilHalt` **阻塞不返回**，必须跑在单独线程，否则请求线程卡死。停止只能靠 `session.halt()`（可跨线程调，跟 Step 14 一样）。当前能力用哨兵任务 + 低 salience 的 halt 规则让引擎处理完所有任务后自行收尾，比主线程直接 halt 更确定（避免异步竞态漏处理）。`session.getObjects()` 获取结果前要先 join 对应的 fire 线程
- Step 23：`.drt` 放 `resources/templates/`(不在 `rules/` 下，`DroolsConfig` 的 `rules/**/*.drl` 扫描不碰它，`.drt` 也不是 `.drl`)。`ObjectDataCompiler.compile(Collection<Map>, InputStream)` 的 Map key 必须跟模板 `template header` 里的列名一字不差；`@{row.rowNumber}` 是内置的行号占位。生成的 DRL 是普通 DRL，走 Step 9 的 KieHelper 编译，跟决策表(Step 7 编译成 DRL 跑 RETE)本质同源
- Step 24：依赖是 `org.kie:kie-pmml-dependencies`(pom 聚合，8.44.2 **无** `kie-pmml-trusty` 单构件) + `javax.xml.bind:jaxb-api:2.3.1` + `org.glassfish.jaxb:jaxb-runtime:2.3.8`(trusty 编译器用 `javax.xml.bind` 解析 XML，Java 21 已从 JDK 移除 JAXB，不补就编译期 CNFE)
- Step 24：**官方便捷工厂 `PMMLRuntimeFactory.getPMMLRuntimeFromFile/Classpath` standalone 下不通**——getPMMLRuntimeFromClasspath 要 `.pmml` 是磁盘真实 File(fat-jar 内失败)；且它编译进一个内部新建的 classloader 后就丢掉，返回的 runtime 与你另建的求值 context 不共享 `GeneratedResources` → evaluate 报 `Failed to retrieve EfestoOutput`
- Step 24 正解(PmmlService)：① 自己编译进一个**持有的** `MemoryCompilerClassLoader`(生成类字节码进它)；② 求值用**同一个** classloader 建 `PMMLRuntimeContextImpl`；③ 编译产物 `generatedResourcesMap` 只在编译 context 对象里、没写进 classloader，而求值 context 靠 scan classloader 的 IndexFile 填这张表(standalone 扫不到)，所以**反射** `putAll` 进求值 context 的 `EfestoRuntimeContextImpl.generatedResourcesMap`(protected final 可变 Map、无公开注入口)。反射仅此一处、锁死 8.44.2
- Step 24：模型 bean 构造时编译一次，`MemoryCompilerClassLoader` + 产物 map 只读共享跨请求复用(像 Step 17 DMNRuntime 那样)；每请求只新建 requestData + context，线程安全。`.pmml` 从 classpath 读流拷临时文件(jar-safe，`EfestoFileResource` 要真实 File)。这条链路完全不经 `DroolsConfig`/kmodule，跟 DMN(Step 17 从 KieContainer 派生 DMNRuntime)又不一样
