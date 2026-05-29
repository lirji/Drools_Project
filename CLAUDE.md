# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

这是给在本仓库工作的 Claude Code 的指南，描述项目用途、技术栈、约定与已踩过的坑。

## 项目概览

Drools 学习脚手架，配合 LangChain4j 项目用，**不是生产代码**。三阶段渐进式 demo：

- **Step 1 / Hello World**：`POST /hello` → `rules/hello/hello.drl`，演示 facts / when-then / 多规则独立触发
- **Step 2 / 订单折扣**：`POST /discount/calculate` → `rules/discount/order-discount.drl`，演示 salience 优先级、跨事实 join、规则叠加
- **Step 3 / 购物车**：`POST /cart/checkout` → `rules/cart/cart-rules.drl`，演示 `accumulate` (按品类聚合) 和 `modify` (字段级联触发其他规则)
- **Step 4 / 风控推荐**：`POST /risk/evaluate` → `rules/risk/risk-rules.drl`，演示 `not` / `exists` 两种否定/存在判断，并用 "insert 标记 fact + not 检测" 替代 `no-loop` 做自终止
- **Step 5 / agenda-group 流水线**：`POST /pipeline/run` → `rules/pipeline/pipeline-rules.drl`，演示 `agenda-group` (validate→discount→risk→notify 四阶段) + `setFocus` 栈式控制 + `auto-focus` + `lock-on-active`
- **Step 6 / 规则可观测性**：`POST /pipeline/audit` → `audit/RuleAuditListener.java`，挂 `AgendaEventListener` + `RuleRuntimeEventListener` 把规则触发轨迹打成结构化 `AuditEvent[]` 一起返回，能直接"看见" agenda 栈压弹和 auto-focus 时序
- **Step 7 / 决策表**：`POST /decision/calculate` → `rules/decision/vip-discount.xls`，业务方用 Excel/Numbers 直接维护 VIP 折扣档位；XLS 由 `src/test/java/.../VipDiscountSheetGenerator` 一键生成
- **Step 8 / CEP 滑窗风控**：`POST /fraud/check` → `rules/fraud/fraud-rules.drl`，演示 `@role(event)` / `@timestamp` / `over window:time(5m)` / pseudo clock 推进事件时间线
- **Step 9 / 规则热加载**：`POST /hot/upsert` + `POST /hot/run/{name}` → `service/HotReloadService.java`，把 DRL 字符串运行时编译成 KieBase 缓存进 `ConcurrentHashMap`，同名 upsert 替换；编译错误返回 400 + 行号
- **Step 10 / KieSession 持久化**：`POST /loyalty/start` + `POST /loyalty/{id}/purchase` + `GET /loyalty/{id}` → `service/LoyaltyService.java`，用 `Marshaller` 把整个 working memory + agenda state 序列化成 byte[]，经 Spring Data JPA 存到 H2 file (`./data/drools-demo.mv.db`)。同一 sessionId 跨请求、跨重启接着上次状态继续累积积分 + 链式升级 (BRONZE → SILVER → GOLD)
- **Step 11 / StatelessKieSession 对比**：`POST /stateless/calculate` + `POST /stateless/batch` → `service/StatelessDiscountService.java`，复用 Step 2 的 `discountKBase` 但派生 `type="stateless"` 的 ksession；同输入结果跟 stateful 完全等价。教学重点：API 极简 (无 `dispose()`)、实例可复用 (线程安全)、execute(Iterable) 一次插入多 fact、批处理零隔离成本
- **Step 12 / TMS (Truth Maintenance System)**：`POST /tms/compare` → `service/TmsService.java` + `rules/tms/logical/` + `rules/tms/regular/`。同一组 LHS 各写两份 DRL，一份用 `insertLogical` 一份用 `insert`，单次请求里在两个 kbase 各跑两阶段 fire（先 value=95 触发告警 → modify 改成 50 再 fire）。结果对比：logical 的衍生 Alert 被引擎自动 retract，regular 的依然留在 working memory。展示"前提-结论"因果链由引擎维护
- **Step 13 / 后向链 + query**：`POST /backward/contains` → `service/BackwardChainingService.java` + `rules/backward/backward-chaining.drl`。前面 Step 1-12 全是前向链 (data-driven push)；这一步用经典 `isContainedIn(x, y)` 递归 query 演示反向 (goal-driven pull) 推理。给定 Location(thing, container) 直接关系，引擎反向证明"Office 是否在 Continent 里"，递归走 Office→House→City→Country→Continent 四跳
- **Step 14 / 引擎安全护栏**：`POST /guard/runaway` + `POST /guard/timeout` + `POST /guard/canary` → `service/GuardService.java` + `guard/ReleaseAgendaFilter.java` + `rules/guard/guard-rules.drl`。第一个偏"生产工程"的 Step，给规则引擎加兜底防失控。三个护栏：① `fireAllRules(maxFires)` 硬上限熔断（失控自增规则被截断在 N 条）；② 另一线程 `session.halt()` watchdog 超时打断（裸 fireAllRules 跑到 timeout 被优雅终止，非 kill 线程）；③ `fireAllRules(AgendaFilter)` 按规则 `@release(...)` 元数据灰度放行（canary 规则编译进 KieBase 但运行时被拦，改白名单即放量，不重编译不重启）。失控靶子用 `Counter`（mutable POJO）触发，灰度靶子用 `Cart` 触发，同 kbase 靠 fact 类型天然隔离
- **Step 15 / 规则可观测性指标**：`POST /metrics/discount` → `service/MeteredDiscountService.java` + `metrics/MeteredRuleListener.java`，指标经 `GET /actuator/prometheus` 暴露。把 Step 6 的 listener 思路从"攒事件数组"升级成"打 Micrometer 指标"：复用 Step 2 的 discountKBase 零改动，挂 `MeteredRuleListener`（fire/match/fact 累加成 counter）+ 一个 `Timer` 包住 fireAllRules（耗时 + p50/p95/p99）。指标：`drools_rules_fired_total{rule}`（哪条规则最热）/ `drools_matches_total` / `drools_matches_cancelled_total` / `drools_facts_total{op}` / `drools_session_fire_seconds`。Step 6 是单请求"放大镜"，Step 15 是跨请求"仪表盘"，两者互补
- **Step 16 / KieScanner + KJAR**：`POST /scanner/deploy` + `POST /scanner/run` + `POST /scanner/poll/start|stop` + `GET /scanner/status` → `service/ScannerService.java`。Step 9 热加载的"生产正解版"：DRL → 程序化打成 **KJAR**（带 kmodule.xml + pom 的标准 Maven 构件）→ `KieMavenRepository.installArtifact` 装进本地 `~/.m2` → `KieContainer` 绑 **ReleaseId**（`com.lrj.rules:scanner-cart-rules:1.0.0-SNAPSHOT`）而非 classpath → `KieScanner.scanNow()`（或 `start(ms)` 自动轮询）发现同 GAV 新内容就**热替换 KieBase**，container 不重建、应用不重启。跟 Step 9 的对照：Step 9 是"DRL 字符串 → Map 缓存"的应用内临时态；Step 16 是"规则跟代码独立发版"的工业路径（规则团队 mvn deploy → 所有实例的 scanner 自动拉到）

- **Step 17 / DMN (Decision Model and Notation)**：`POST /dmn/price` → `service/DmnService.java` + `rules/dmn/vip-pricing.dmn`。**第一个非 DRL 体系的 Step**：DMN 是 OMG 跨厂商标准（`.dmn` XML + FEEL 表达式 + 决策需求图 DRG），跟前面 Step 1-16 全部基于 DRL 的引擎是两套独立东西。模型 `VipPricing` 演示：结构化输入（`tCustomer` 带 schema）→ `Discount Rate`（DMN **原生**决策表，hitPolicy UNIQUE，对照 Step 7 的 Excel→DRL 决策表）→ `Final Price`（FEEL 字面表达式，依赖 Discount Rate 形成**决策链**）+ `Membership Tier`（FEEL if/else）。DMN 不走 KieSession/fireAllRules，而是 `DMNRuntime.evaluateAll(model, context)` 按 DRG 拓扑求值

- **Step 18 / 营销活动资格判定**：`POST /campaign/create` + `POST /campaign/{id}/check` + `POST /campaign/{id}/end` + `GET /campaign/list` → `service/CampaignService.java` + `domain/UserProfile.java` + `domain/Eligibility.java` + `persistence/CampaignEntity.java`。**第一个把多步拼成"实际业务场景"的 Step**：运营创建活动时**绑定一段资格规则 (DRL)**，用户申请参加时判定够不够格。三个 Step 的合体——① 创建活动复用 **Step 9** 的 `KieHelper` 运行时编译 DRL（编译失败返回 400 + 行号，绝不把跑不起来的规则落库）；② 规则**源文本**持久化到 `campaign` 表（复用 **Step 10** 的 JPA 思路，但存的是 DRL 文本而非 marshall 的 session byte[]；DB 默认 MySQL，可切 H2 profile）；③ 资格判定走 **Step 4** 的"白名单标记 fact"套路——默认不够格，规则只在满足条件时 `insert(new Eligibility(true, reason))`，fire 完看 working memory 有没有 `Eligibility(eligible==true)`。两级存储：内存 `ConcurrentHashMap<campaignId, KieBase>` 当热路径缓存 + DB 存规则文本；应用重启后内存空了，check 时 `computeIfAbsent` 从 DB 捞 DRL 重新编译（rehydrate），所以活动不随重启丢失——这正是"Step 9 + 持久化"比纯 Step 9 多出来的能力。多活动靠"一个 campaignId 一个独立 KieBase"天然隔离，不像 Step 12 要担心衍生 fact 互相污染

后续（LLM 联动）按需扩展，**没需求时不要提前加**。Step 16 的 `kie-ci` 是个重依赖（拉进 maven-core / aether 一票传递依赖），且 `installArtifact` 会真写 `~/.m2/repository/com/lrj/rules/`，这是 demo 自己的 GAV、每次 deploy 覆盖，清理直接 `rm -rf ~/.m2/repository/com/lrj/rules`。Step 10 / Step 18 已加 JPA 但仅服务于持久化 demo，不要把它扩成"全项目状态都进数据库"。

## 技术栈与版本背景

- Java 21 / Spring Boot 3.3.5 / Drools 8.44.2.Final / Maven (wrapper)
- **为什么选 8.44.2.Final**：Drools 9.x 仍偏 incubator；8.44.2 是社区验证过的 Spring Boot 3.3 + Java 21 稳定组合。Drools 10 (Apache KIE 改名后的新线) 文档/教程跟不上，本项目不追
- **数据库**：默认 **MySQL**（`mysql` profile，`mysql-connector-j` 驱动），备用 **H2 file**（`h2` profile）。Step 10 / Step 18 的 JPA 持久化用它。两个驱动 pom 都留，靠 `spring.profiles.active` 切换，连接参数全走环境变量（见下）

## 常用命令

```bash
# 默认 MySQL profile，连接走环境变量覆盖（不写死真实值）
DB_HOST=localhost DB_PORT=3306 DB_NAME=drools_demo DB_USERNAME=root DB_PASSWORD=yourpass \
  ./mvnw spring-boot:run               # 起 web 服务 (默认 8081)
./mvnw spring-boot:run -Dspring-boot.run.profiles=h2   # 没 MySQL 时切 H2 file 模式
./mvnw test                   # 跑测试 (目前只有 spring-boot-starter-test，没业务测试)
./mvnw clean package          # 打 jar
./mvnw clean compile          # 只编译 Java；不会校验 DRL 语法
```

**端口 8081** 跟主项目 LangChain4j (8080) 错开，方便两个 demo 同时跑。改端口看 `application.yml`。
**数据库 profile**：`application.yml` 是公共配置 + `spring.profiles.active: mysql` 默认；数据源细节分到 `application-mysql.yml`（带 `createDatabaseIfNotExist=true`，库不存在自动建）/ `application-h2.yml`。连接参数 `DB_HOST`/`DB_PORT`/`DB_NAME`/`DB_USERNAME`/`DB_PASSWORD` 都能用环境变量覆盖。

## 已踩过的坑（务必先读，再动 pom / DRL）

1. **`org.kie:kie-bom` 在 8.44.2 没发布** → `pom.xml` 必须用 `org.drools:drools-bom`
2. **Drools 8.x 把 XML 解析拆成独立模块** → 必须显式加 `drools-xml-support`，否则启动报 `Unable to build index of kmodule.xml ... add module org.drools:drools-xml-support`
3. **不要随便加 `update($fact)` → 会死循环**：
   - `update()` 重新评估所有依赖该 fact 的规则
   - 本项目 LHS 条件都看 `vipLevel` / `totalAmount` / `yearsSinceRegistration`（不可变字段），修改 `finalAmount` 不会让条件失配 → 规则永远满足，被反复触发，请求挂住
   - `no-loop true` 只防"自己 consequence 重新激活自己"，**不防其他规则的 update 间接重新激活自己**
   - cross-rule 防护要用 `lock-on-active true` + `agenda-group`
   - **本 demo 根本不需要 update()**，因为没有规则读被修改的字段。教学上的"什么时候用 update()"放在 Step 3 真实级联（`modify` + goldStatus）里讲
4. **DRL 是运行时解析**，`mvn compile` 过了不代表规则没语法错。改完 DRL 必须至少启动一次或跑一次冒烟请求
5. **Customer / OrderItem 是 Java record，DRL 里 `Customer( age >= 18 )` 能正常用** — Drools 8.x 的 LHS 会自动尝试 record accessor (`age()`)，不是只有 `getAge()`。这条算确认信息，不是坑
6. **RHS 没有 record accessor 糖** — LHS `Customer(age >= 18)` 引擎自己适配 record，但 RHS 是直接编译成 Java 代码，没有适配层。所以 `$p.getMessage()` 对 record `Promotion` 会编译失败，必须写 `$p.message()`。改完 DRL 重启时**第一次请求**才会触发 KieBase 重新编译并报错（DRL 是 lazy compile），冒烟一次比读启动日志可靠
7. **MySQL 下 `@Lob` 大字段会被截断** — `@Lob byte[]`（Step 10 的 session 快照）在 MySQL 默认建成 64KB 的 `blob`、`@Lob String`（Step 18 的 DRL 文本）建成 64KB `text`，大会话 / 长 DRL 会超限。本项目改用 `@JdbcTypeCode(SqlTypes.LONGVARBINARY)` / `@JdbcTypeCode(SqlTypes.LONGVARCHAR)`，映射成 MySQL `longblob` / `longtext`，H2 下也是大对象，两个 profile 都够装。H2 时代用 `@Lob` 不暴露这个坑（H2 的 LOB 默认就够大），换 MySQL 才踩到
8. **MySQL 中文乱码 / 时区** — `application-mysql.yml` 的 URL 必须带 `characterEncoding=UTF-8`（DRL / reason 里有中文）+ `serverTimezone`（`Instant` 字段），否则中文乱码 / 时间偏移。`createDatabaseIfNotExist=true` 让库不存在时自动建，学习省事；生产应预建库 + 收紧账号权限

## 代码结构（按职责，不是按目录）

- `domain/` — fact 类型。record (`Customer`, `OrderItem`, `Promotion`) 用于不可变事实；mutable POJO (`Order`, `Cart`) 用于会被规则改字段的事实。`Promotion` 是 Step 4 引入的"标记 fact"，规则自己 insert 出来给 `not` 检测用
- `audit/` — Step 6 引入。`RuleAuditListener` 实现两个 Drools listener 接口攒 `AuditEvent[]`，静态 `attachTo(session)` 工厂方法让任意 service 都能挂载
- `service/` — KieSession 生命周期。**每次请求 `newKieSession` + `fireAllRules` + `dispose`**，KieSession 线程不安全，不要为了"省"复用
- `config/DroolsConfig.java` — `KieContainer` 注成单例 Bean（编译规则贵，启动时一次性扫 classpath 的 `META-INF/kmodule.xml`）
- `resources/rules/<kbase>/*.drl` — DRL 文件，**目录名必须和 `kmodule.xml` 里 `<kbase packages="...">` 对齐**
- `resources/META-INF/kmodule.xml` — 声明 `helloKBase` / `discountKBase` / `cartKBase` / `riskKBase` / `pipelineKBase` / `decisionKBase` / `fraudKBase` / `loyaltyKBase` / `tmsLogicalKBase` / `tmsRegularKBase` / `backwardKBase` / `guardKBase` / `dmnKBase` 等 kbase + 对应 ksession 名（`fraudKBase` 用 stream mode + pseudo clock；Step 12 的两个 kbase 隔离避免 logical / regular 衍生 fact 互相污染；`dmnKBase` 只声明 kbase 不带 ksession，因为 DMN 走 DMNRuntime 不走 KieSession）
- `guard/` — Step 14 引入。`ReleaseAgendaFilter` 实现 `org.kie.api.runtime.rule.AgendaFilter`，按规则 `@release(...)` 元数据放行/拦截，跟 `audit/` 同属"挂在 session 上的横切组件"。读元数据走公共 API `Rule.getMetaData()`（跟 `getAgendaGroup()` 只在 internal 上不同）
- `metrics/` — Step 15 引入。`MeteredRuleListener` 跟 `audit/RuleAuditListener` 实现同一套 listener 接口、同样按请求挂一个实例，区别只在输出去向：audit 攒进 `List<AuditEvent>` 随请求返回，metrics 累加进全局 `MeterRegistry` 经 `/actuator/prometheus` 抓取。`fireAllRules` 整段耗时是个 `Timer`，包在 service 外层（listener 拿不到 fire 边界）
- `service/ScannerService.java` — Step 16 引入。**不走** `config/DroolsConfig` 那个 classpath KieContainer，而是自己维护一个绑 ReleaseId 的 `KieContainer` + `KieScanner`（懒创建于首次 deploy）。KJAR 程序化构建（`KieModuleModel` 生成独立 kmodule，跟主项目 `META-INF/kmodule.xml` 完全隔离）。`generation` 计数器标记当前 live 的内容代次，run 时返回，肉眼可见热替换发生
- `service/DmnService.java` — Step 17 引入。**第二套引擎**：不是 KieSession，而是 `DMNRuntime`（构造时 `KieRuntimeFactory.of(kieContainer.getKieBase("dmnKBase")).get(DMNRuntime.class)` 拿一次缓存当字段，线程安全可复用，跟 Step 11 的 StatelessKieSession 持有方式同理）。`DMNModel` 也一次性解析缓存。`.dmn` 文件放 `rules/dmn/`，由 `DroolsConfig` 扫 `.dmn` 标 `ResourceType.DMN` 编进 `dmnKBase`
- `persistence/` — Step 10 引入。`SessionSnapshot` (JPA entity, sessionId 做主键, `byte[] data` 存 marshall 出来的 KieSession) + `SessionSnapshotRepository` (Spring Data)。默认连 MySQL（`mysql` profile），H2 file（`h2` profile）在 `./data/drools-demo.mv.db`，repo 根 `.gitignore` 已豁免。Step 18 又加了 `CampaignEntity` (campaignId 主键, `eligibilityDrl` 存 DRL 源文本) + `CampaignRepository`，同一个库不同表——一个存 session 运行时状态、一个存规则定义文本。两个 entity 的大字段都用 `@JdbcTypeCode`（不是 `@Lob`），见坑 7
- `config/DroolsConfig.java` — Drools 8.44.2 的 `getKieClasspathContainer()` **不识别 spreadsheet 决策表**（启动报 "No files found"），所以这里改成程序化 `KieFileSystem` 构建：扫 `.drl` 自动加 + `.xls` 显式标 `ResourceType.DTABLE`

## 扩展点

- **加新规则**：在 `rules/<kbase 名>/` 下加 `.drl`，重启生效
- **加新 KieBase**：编辑 `kmodule.xml` 加 `<kbase>` + `<ksession>`，service 里换 `newKieSession("新名字")`
- **加新 fact**：放 `domain/`，POJO / record 都行，DRL 里 `import` 后即可用
- **决策表**：依赖已加 `drools-decisiontables`；放 `.xls` 到 classpath，在 `kmodule.xml` 加 `<ruleTemplate>` 或 `<kbase>` 指 packages

## 项目特有的 DRL 语义注意点

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
- Step 9 跟 KieScanner 生产路径的关系：KieScanner = "KJAR + Maven repo + 定时轮询版本号 + 自动 upsert"。本 demo 把"上传 + upsert"暴露成 HTTP，定时轮询自己加一层 `@Scheduled` 就成了 KieScanner 等价物
- Step 10：`MarshallerFactory` 在 Drools 8 移到 **`org.kie.internal.marshalling`**（不是 `org.kie.api.marshalling`），从 Drools 7 升级或照旧教程抄都会踩这坑。`Marshaller` 接口本身还在 `kie-api` 里
- Step 10：必须显式加 `drools-serialization-protobuf`，否则 `MarshallerFactory.newMarshaller(kb)` 拿不到实现（Drools 8 把 protobuf 序列化拆成独立模块）
- Step 10：所有进 working memory 的 fact 类**必须 `implements Serializable`**，Drools 默认用 Java 原生序列化把 fact 整体塞进 byte[]。record 不自动实现 Serializable，要手动声明（`public record PurchaseEvent(...) implements Serializable {}`）
- Step 10：`marshaller.unmarshall(InputStream)` 返回**新的** KieSession 实例，不是把状态注入回原 session。每次请求都是"load byte[] → 新 session → fire → save byte[] → dispose"四步走
- Step 10：marshall 时 agenda 上还没执行的 activation 也会被序列化进去，下次 unmarshall 后 fireAllRules 会接着跑剩下的。这是"中断恢复"语义的核心
- Step 10：改了 DRL 后老快照可能反序列化失败（规则签名变 / fact 类字段变）。学习场景手动清 `./data/` 目录即可；生产要做"快照版本号 + DRL 变更迁移脚本"，超出本 demo 范围
- Step 10：跟 Drools 官方 `drools-persistence-jpa` 的差异：那个走 JTA + SessionInfo/WorkItemInfo 多张表 + 自动 commit；本 demo 一张 `session_snapshot` 单表 + 手动 marshall 边界。教学概念一致（序列化整段 session 状态），工程复杂度差一个数量级。Spring Boot 3 配 JTA (Bitronix/Atomikos) 没官方 starter，落地路径选简化版
- Step 11：`StatelessKieSession` 是 Drools 提供的"一次性"会话封装。`execute(Iterable)` = newSession + insertAll + fireAllRules + dispose 四步合一；用错（漏 dispose）的概率为 0
- Step 11：**StatelessKieSession 线程安全**，可以注成 Spring 单例反复用；KieSession (stateful) 不能跨请求复用，每次都得 newKieSession。本 demo 把 stateless 当字段持有，符合官方推荐用法
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
- Step 13：DRL 里也可以用 `?queryName(args;)` 在规则 LHS 触发后向链做"pull-driven 规则"。本 demo 走 Java API 路径 (getQueryResults) 因为更直观, 不需要额外的 driver fact
- Step 13：query 的"输出变量"模式 (列出所有满足条件的绑定) 要用 `org.drools.core.runtime.rule.impl.Variable.v` 占位 unbound arg, 但这是 internal API; 本 demo 改成"枚举候选 + 逐个 boolean 后向链证明", 既避开 internal API 也让 query 复用价值更直白
- Step 14：`fireAllRules(int max)` 是最该默认带上的护栏——生产里几乎所有 `fireAllRules()` 都应写成带上限的版本。失控规则 fire 满 max 强制返回, 不会把请求线程挂死。本 demo 的 "Runaway increment" 故意不写 no-loop, 就是要它失控当靶子
- Step 14：`session.halt()` 是 KieSession 上**少数几个能跨线程调**的方法。watchdog 线程在超时后调 halt(), 引擎跑完当前 activation 后优雅返回 (不是 kill 线程, 不留脏状态)。按"挂钟时间"兜底比按"fire 次数"更通用——有的规则一次 fire 就很慢, 次数卡不住。这是 KieServer 等部署里"单请求超时"的标准做法
- Step 14：`AgendaFilter.accept(match)` 在每条 activation **真正 fire 前**被调一次, 返回 false 这条就被跳过 (不执行 RHS, 但仍留在 agenda, 下次没 filter 的 fire 还能跑)。用它做灰度/金丝雀/紧急下线: 规则全量编译进 KieBase, 运行时按白名单决定哪些真正生效, **不重编译、不重启**
- Step 14：AgendaFilter 里读规则元数据走的是公共 API `Rule.getMetaData()` (返回 `Map<String,Object>`, key 是 `@release` 里的 `release`)。这跟 Step 6 注释里那条"`Rule` 公共接口没有 `getAgendaGroup()`"形成对照——元数据是公共的, agenda-group 不是
- Step 14：ReleaseAgendaFilter 约定"没标 @release 的规则默认放行 (视为稳定基线)", 这样灰度只控带标记的实验规则, 不会因为忘标 release 把基线规则一起拦掉。这是个有意的安全默认, 不是 Drools 强制的
- Step 14：失控规则 (Counter) 和灰度规则 (Cart) 共用一个 guardKBase / guardSession, 靠 fact 类型天然隔离 (跑 runaway 只插 Counter, 跑 canary 只插 Cart, 互不触发对方的规则)。不需要像 Step 12 那样拆两个 kbase, 因为这里没有"衍生 fact 互相污染"的问题
- Step 15：指标分两层挂。**listener 层** (`MeteredRuleListener`) 出 counter——`afterMatchFired` → `drools.rules.fired{rule}`、`matchCreated/Cancelled` → `drools.matches.*`、`object*` → `drools.facts{op}`；**service 层** 出 Timer——`fireAllRules` 整段耗时。为什么 Timer 不在 listener 里: listener 的回调是"单条 match fire 前后", 拿不到"整段 fireAllRules"的起止边界, 那是 service 调 fire 的那一行才有的
- Step 15：Micrometer → Prometheus 命名转换会**改名**。代码里 `drools.rules.fired` (点分) 在 `/actuator/prometheus` 输出成 `drools_rules_fired_total` (下划线 + counter 自动加 `_total` 后缀); `drools.session.fire` Timer 出 `drools_session_fire_seconds` (自动补单位)。查指标按下划线名, 别拿点分名去 grep
- Step 15：`Counter.builder(name).tags(...).register(registry)` 对**相同 meter id 幂等** (返回已存在实例), 所以 listener 每次事件都 builder→register→increment 是安全的, 不会重复创建 meter。但 `rule` 当 tag 要警惕 **高基数**: 规则名数量可控没事, 千万别把 orderId / customerId 这种无界值塞进 tag, 会把时序库打爆
- Step 15：`Timer.record(...)` 对返回 `int` 的 `fireAllRules` 有重载歧义 (`DoubleSupplier` / `Supplier<T>` / `Runnable` 都能匹配), 必须显式转 `(Supplier<Integer>)` 才能编译。这是 Micrometer + 原始类型返回值的常见坑
- Step 15：`management.endpoints.web.exposure.include` 默认只开 `health`, 必须显式加 `prometheus` 才有抓取端点。`management.metrics.tags.application` 给所有指标加公共 tag (本项目 `drools-demo`), 多实例部署时用来区分来源
- Step 16：KieScanner 的"发现新版本"对 **release 固定版本 (1.0.0) 不触发** (Maven 契约: 固定版本内容不可变)。必须用 **SNAPSHOT** (`1.0.0-SNAPSHOT`) 才能同 GAV 滚动更新——`scanNow()` 重新解析 + 比对时间戳命中替换。所以 demo 固定一个 SNAPSHOT GAV 反复 install 新内容; 生产规则发版用递增 release 版本 + `KieContainer.updateToVersion(newReleaseId)`
- Step 16：`installArtifact(ReleaseId, InternalKieModule, File pom)` 三个参数都不能省。pom 文件用 `KieBuilderImpl.generatePomXml(releaseId)` (返回 String) 写临时文件; KJAR 本身的 pom 由 `kfs.generateAndWritePomXML(releaseId)` 写进构件内部, 两者一个给 maven install 元数据、一个让 KJAR 自描述
- Step 16：`KieMavenRepository` / `KieScanner` / `InternalKieModule` / `KieBuilderImpl` 这些类来自 `kie-ci` (`org.kie.scanner.*` + `org.drools.compiler.kie.builder.impl.*`)。`KieScanner` 接口本身在 `kie-api`, 但实现要 kie-ci。没加 kie-ci 时 `newKieScanner` 会拿不到实现
- Step 16：KJAR 内的 kmodule 用 `KieModuleModel` 程序化生成 (`newKieBaseModel("scannerKBase").addPackage("rules.scanner")` + `newKieSessionModel("scannerSession")`), 跟主项目 `META-INF/kmodule.xml` 互不影响。DRL 的 `package rules.scanner` 必须跟 kbase 的 addPackage 对齐, 否则规则不进这个 kbase
- Step 16：老 KieSession 安全性跟 Step 9 同理——`container.newKieSession()` 拿的是 scanner 当前指向的 KieBase, scanNow 替换后**新建的** session 才用新 KieBase, 已经在跑的 session 跑完它的活。热替换不打断进行中的 fire
- Step 16：`scanNow()` 是同步立即扫 (测试/手动触发用), `start(intervalMillis)` 是后台线程周期轮询 (生产形态, 规则 deploy 后无人值守自动生效)。本 demo deploy 内部调 scanNow 保证 HTTP 响应里立刻看到新内容; `/scanner/poll/start` 单独演示自动轮询
- Step 17：`kie-dmn-core` 的版本**不在 drools-bom 管理范围内** (drools-bom 只管 org.drools:* 和部分 org.kie:*)。不写 `<version>` 直接报 "version is missing"。显式锁 `${drools.version}` (8.44.2.Final, KIE 各模块同步发版)。它传递带出 kie-dmn-api/model/backend/feel
- Step 17：`.dmn` 跟 `.xls` 决策表同病: Drools 8.44 的 ClasspathKieProject **不自动识别** `.dmn`, 必须程序化 `KieFileSystem` + `setResourceType(ResourceType.DMN)`。`DroolsConfig.kieContainer()` 里扫 `.dmn` 的循环是 Step 17 加的, 跟扫 `.xls` 那段并列
- Step 17：DMN 模型用 **DMN 1.2 命名空间** (`http://www.omg.org/spec/DMN/20180521/MODEL/`), Drools 8.44 对它解析最稳。手写 `.dmn` 时 `>` / `<` 在 XML 文本里要转义 (`&gt;` / `&lt;`), 本 demo 的 `&gt;= 4` / `if ... &gt;= 3` 都转了。模型有语法错会在**启动时** KieBuilder.buildAll() 抛出来 (不是 lazy), 比 DRL 早暴露
- Step 17：DMN 不走 KieSession/fireAllRules。从 kbase 取 `DMNRuntime` (`KieRuntimeFactory.of(kbase).get(DMNRuntime.class)`), `evaluateAll(model, context)` 按决策需求图 (DRG) 拓扑顺序求所有 decision。DMNRuntime 线程安全可复用, 当字段缓存 (跟 Step 11 StatelessKieSession 同理), 不像 stateful KieSession 要每请求新建
- Step 17：`DMNContext.set(key, value)` 的 **key 必须跟 .dmn 里 inputData 的 name 一字不差**, 包括空格 —— `"Order Amount"` 带空格也要原样传, 写成 `"OrderAmount"` 会导致那个输入为 null。`getModel(namespace, name)` 的两个参数也要跟 `<definitions namespace=... name=...>` 完全一致, 否则返回 null
- Step 17：FEEL 的 `number` 类型底层是 **BigDecimal**, 所以 `Discount Rate` / `Final Price` 返回的是 BigDecimal (JSON 序列化成普通数字, 如 `0.10` / `900.00`)。结构化输入 `Customer` 灌的是 `Map<String,Object>` (key 对应 itemComponent name), FEEL 里 `Customer.vipLevel` 按 key 取值
- Step 17 跟 Step 7 的对照: Step 7 是 Excel 决策表 → drools-decisiontables 编译成 DRL → 跑 KieSession (本质还是 DRL/RETE); Step 17 是 DMN 标准模型 → DMNRuntime 独立求值引擎。两者都能让业务方维护表格, 但 DMN 是跨厂商标准 + 自带 FEEL + 决策链, 可移植性和表达力更强; 决策表只是"规则的表格写法"
- Step 18：是个"合体 Step", 没有引入新 Drools 机制, 而是把 Step 9 (KieHelper 编译) + Step 10 (JPA/H2 持久化) + Step 4 (标记 fact) 拼成一个真实业务流。教学价值在"怎么组合", 不在单点能力。读它之前先读那三步
- Step 18：活动绑定的 DRL 由请求传入 (运营写的), **不放 resources/rules/ 也不进 kmodule.xml** —— 跟 Step 9 同理走 KieHelper 编译, 不依赖 DroolsConfig 那个 classpath KieContainer。所以加活动不用改 kmodule、不用重启
- Step 18：运营写的 DRL 必须 `import com.lrj.drools.domain.UserProfile` 和 `com.lrj.drools.domain.Eligibility` (全限定类名), 因为 KieHelper 编译的是裸 DRL 字符串, 没有项目的 import 上下文。RHS 里 `insert(new Eligibility(true, "理由"))` 是构造新对象, 不踩 record RHS accessor 的坑 (CLAUDE.md 坑 6); 但若 RHS 要读 UserProfile 字段得写 `$u.registrationDays()` 不是 `getRegistrationDays()`
- Step 18：白名单式判定的安全默认 —— "默认不够格, 命中规则才放行"。漏写规则的后果是"没人够格"(保守、安全), 而不是"所有人都放进来"。跟 Step 14 ReleaseAgendaFilter "没标 @release 默认放行" 是相反方向的默认值选择, 都是按"出错时往安全侧倒"设计的
- Step 18：两级存储的 rehydrate 是关键观察点 —— `registry.computeIfAbsent(campaignId, id -> compile(DB里的DRL))`。重启后内存缓存空 (list 里 cached=false), 第一次 check 触发从 DB 捞 DRL 重新编译进缓存 (cached 翻 true)。这就是"规则即数据 + 持久化"相比 Step 9 纯内存态的增量。编译贵, 所以缓存; DB 是真相源, 所以重启不丢
- Step 18：`session.getObjects(ObjectFilter)` 用 lambda `o -> o instanceof Eligibility` 过滤 working memory 拿衍生 fact, 比 Step 4 在 RHS 里手动往 list 里塞更直接。一个活动可能多条规则命中 (示例里"新人"+"一线城市"两条都中), reasons 收集成列表全返回

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
| POST | `/guard/runaway`        | Step 14：插 Counter 跑失控自增规则, fireAllRules(maxFires) 硬上限截断, 返回 fire 次数 + 截断时的值 |
| POST | `/guard/timeout`        | Step 14：失控规则裸跑, watchdog 线程在 timeoutMillis 后 halt() 打断, 返回中断前 fire 次数 |
| POST | `/guard/canary`         | Step 14：插 Cart 跑三条带 @release 标记的规则, AgendaFilter 按 allowedReleases 白名单放行, 返回结果 + 被拦规则 skipped |
| POST | `/metrics/discount`     | Step 15：跟 /discount/calculate 同入参同折扣, 但挂 MeteredRuleListener + Timer, 把 fire/match/fact/耗时打进 Micrometer |
| GET  | `/actuator/prometheus`  | Step 15：Prometheus 抓取端点, grep `drools_` 看规则指标随调用累积 |
| POST | `/scanner/deploy`       | Step 16：DRL 打成 KJAR 装进本地 ~/.m2, 首次创建 container 否则 scanNow 热替换; 编译错误 400 |
| POST | `/scanner/run`          | Step 16：用当前 live KieBase 跑 cart, 返回 fire count + cart + generation (内容代次) |
| POST | `/scanner/poll/start`   | Step 16：开 KieScanner 自动轮询 (默认 5000ms), 生产形态——deploy 后无人值守自动生效 |
| POST | `/scanner/poll/stop`    | Step 16：停自动轮询 |
| GET  | `/scanner/status`       | Step 16：看 releaseId / container 是否就绪 / 当前 generation / 是否在轮询 |
| POST | `/dmn/price`            | Step 17：插 Customer + orderAmount, 走 DMN 模型求值, 返回 Discount Rate / Final Price / Membership Tier 三个决策结果 |
| POST | `/campaign/create`      | Step 18：运营创建活动 + 绑定资格规则 (DRL), 编译并落库; 同 campaignId 覆盖; 编译失败 400 + 行号 |
| POST | `/campaign/{id}/check`  | Step 18：插 UserProfile 判定够不够格, 白名单式 (命中规则才 insert Eligibility), 返回 eligible + reasons |
| POST | `/campaign/{id}/end`    | Step 18：结束活动 (status→ENDED), 之后 check 返回 409; 清掉内存 KieBase 缓存 |
| GET  | `/campaign/list`        | Step 18：列出所有活动 (含 status + 是否已编译进内存缓存 cached) |

## 配套文档

- `README.md` — 每个 Step 的完整请求示例 + 学习观察点 + 下一步指引
- `docs/rete-intuition.md` — RETE 算法直觉（拿本仓库折扣规则当例子，讲网络结构 + 增量传播 + 写规则原则）
- `docs/drools-capabilities.md` — Drools 能力地图（按七大块分类梳理 Drools 全部能力，每项标注本仓库哪一步演示 / 未演示，含能力选型决策树）
- `docs/drools-vs-aviator.md` — Drools 与 Aviator（轻量表达式引擎）的选型对照：表达式求值 vs 规则引擎，怎么选、怎么组合用
- `docs/drools-use-cases.md` — Drools 应用场景与定位：澄清"营销平台专用"的误区，梳理风控/保险/信贷/计费等高频领域，以及什么时候不该上 Drools
- `examples/aviator/AviatorDemo.java` — Aviator 独立学习示例（**故意放在 Maven 源码根外，不进 `./mvnw compile`、不引 pom 依赖**）。一个 main 方法 6 段：基本求值 / 编译缓存 / 对象属性 / 自定义函数 / 沙箱安全 / 折扣同题对照 Step 2。未编译验证，API 按 Aviator 5.4.3 写，运行方式见文件头
