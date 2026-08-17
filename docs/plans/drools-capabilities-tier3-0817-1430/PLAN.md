# Drools 能力地图 · 三档（重型 / 改架构）落地规划

> 2026-08-17。配套 [`docs/drools-capabilities.md`](../../drools-capabilities.md)。
> 一档（traits / fireUntilHalt / .drt / KieScanner 监听 + updateToVersion）与二档里的 LHS/CEP/RHS 那批**已实现**（Step 19–23 + Step 8/13/16 扩展）。
> 本文件规划**剩下的重型能力**：怎么做、改动半径、工作量、风险、建议。PMML/Scorecard 原属二档，因 standalone 求值有 blocker，一并放这里作**暂缓项**。

## 0. 判据（先立规矩，再谈怎么做）

三条约束决定了这档"值不值得做"和"该怎么做"：

1. **教学层 vs 生产层物理隔离**。Step 1–23 全在 `drools-lab`（classpath `KieContainer` via `DroolsConfig`）；生产活动引擎在 `activity-common`（运行时 `KieHelper` 编译 + 足迹加权 LRU 缓存 + 代际快照）。**只有 per-tenant KieContainer 会碰生产层**，其余都落在教学层。
2. **生产决策主链路 2026-08 起默认纯 Java**（阶梯/合并/资格都移出了规则引擎）。所以 KieServer / jBPM / Rule Units 就算做，也只能是**教学演示**——往生产链路塞是逆着现有架构方向游。
3. **改动半径分三级**：① 只在 `drools-lab` 内加代码/依赖（零架构改动）；② 引重依赖但仍锁在 `drools-lab`（不动模块拓扑/两 app/部署）；③ 新增部署单元 / 引第二个引擎 / 碰生产平台。本档全是 ② 或 ③。

**总体结论（2026-08-17 核实 + 收官）**：~~PMML~~ **已完成（Step 24）**。**Rule Units 破例做了 Step 25、但部署时撞 fat-jar 硬墙后回退**（与 traits 争 SPI 单例互斥 + 扫不到 nested-jar 里的 DRL，见 §5）——恰好印证"不追 incubator"。其余重型项撞上 **Jakarta 边界**（Drools 8.44.2 的 persistence/server/流程模块仍是 `javax.*`，跟本项目 SB3/Jakarta 栈不兼容）——`drools-persistence-jpa` / `KieServer` / `jBPM` 三项**本栈上做不了**；per-tenant / Business Central / .scesim 是反模式或超范围。**能力地图到此收官**，完整判断见 §10。逐项核实见下。

---

## 1. PMML / Scorecard —— ✅ 已完成（走路径 A，Step 24 `/pmml/score`）

> **2026-08-17 落地**。路径 A 打通，Step 24 上线两个模型：`credit-scorecard`（评分卡，含 reasonCode）+ `risk-regression`（线性回归）。下面保留当初的探查记录 + 最终接法，供复盘。

**改动半径**：② 只在 `drools-lab` 内（PMML trusty 运行时不经 `DroolsConfig`/kmodule）。

### 已探明（本轮做过的功课）
- 8.44.2 **无** `kie-pmml-trusty` 单构件；正确依赖是聚合 pom `org.kie:kie-pmml-dependencies`（`<type>pom</type>`），拉齐 trusty 全栈（regression / tree / **scorecard** / mining 的 compiler+evaluator）。**scorecard 就是 PMML 的一种模型**，接通 PMML 即得 Scorecard。
- trusty 编译器用 `javax.xml.bind`（JAXB 2.3，老命名空间）解析 `.pmml`；Java 21 已从 JDK 移除，须补 `javax.xml.bind:jaxb-api:2.3.1` + `org.glassfish.jaxb:jaxb-runtime:2.3.8`，否则编译期 `ClassNotFoundException: javax.xml.bind.JAXBContext`。
- **`.pmml` 能成功编译**（`getPMMLRuntimeFromFile` 不报错）。

### Blocker（卡在哪）
`PMMLRuntime.evaluate(modelName, context)` 报 `KiePMMLException: Failed to retrieve EfestoOutput`，`getPMMLModels(context)` 返回空。根因：trusty 的 efesto 把编译产物（生成的 Java 字节码）放进 `getPMMLRuntimeFromFile` **内部新建**的 `MemoryCompilerClassLoader`，而另建的 `PMMLRuntimeContextImpl` 用的是**另一个** classloader，拿不到那批 `GeneratedResources`。简言之：**编译上下文与求值上下文没串在同一个 efesto context 里**。

### 三条可选路径
| 路径 | 做法 | 工作量 | 风险 |
| --- | --- | --- | --- |
| **A. 补齐 efesto 同 context**（推荐） | 不用 `getPMMLRuntimeFromFile` 这个"便捷但状态不共享"的工厂；改成手动：建一个带 `MemoryCompilerClassLoader` 的 `PMMLRuntimeContext` → 用 `EfestoCompilationManager` 把 `.pmml` 编译进**这个** context（populating 它的 `generatedResourcesMap` + classloader）→ 用同一个 context 调 `PMMLRuntimeHelper`/`RuntimeManager` 求值。参照 kie 源码里 `PMMLRuntimeInternalImpl(generatedResourcesMap)` 那个构造 + `getGeneratedResourcesMap()`。 | 0.5–1.5 天（efesto 内部 API，需读源码 spike） | 中：efesto 是 trusty 新编译框架，公开 API 文档少 |
| **B. JPMML 替代** | 用 `org.jpmml:pmml-evaluator:1.5.1`（+extension）直接吃 `.pmml`，完全绕开 trusty。API 成熟稳定（`LoadingModelEvaluatorBuilder().load(file).build()` → `evaluate(Map)`）。 | 0.5 天 | 低，但**引入第三方 evaluator**（非 Drools 亲儿子，License 是 AGPL，教学 demo 可接受、商用要评估） |
| **C. PMML-in-DMN** | 不做 standalone PMML，改成在 DMN 里 `<reasoning><document>model.pmml</document>`，复用 Step 17 的 `DMNRuntime` 求值。 | 0.5–1 天 | 中：DMN→PMML 桥接也走 trusty/JPMML 底座，配置更绕，且教学上跟 Step 17 重叠 |

### 最终接法（路径 A，已实现于 `service/PmmlService.java`）
关键在于**把编译和求值串进同一个 classloader，并把编译产物的 `generatedResourcesMap` 灌进求值 context**：
1. classpath 读 `.pmml` 流 → 拷临时文件（**jar-safe**；不能用 `getPMMLRuntimeFromClasspath`，它要 `.pmml` 是磁盘真实 File，fat-jar 里失败）；
2. `new MemoryCompilerClassLoader(parent)` → `SPIUtils.getCompilationManager(true)` + `PMMLCompilationContextImpl(fileName, cl)` → `cm.processResource(ctx, new EfestoFileResource(file))`；生成的类字节码进这个 `cl`，产物索引进**编译 context 的** `getGeneratedResourcesMap()`；
3. 求值：`new PMMLRuntimeContextImpl(requestData, fileName, cl)`（**同一个 cl**）——但它靠 scan classloader 的 IndexFile 填 `generatedResourcesMap`，standalone 下扫不到（编译产物只在编译 context 对象里、没写进 classloader），所以**反射 `putAll`** 编译产物的 map 进求值 context 的 `EfestoRuntimeContextImpl.generatedResourcesMap`（protected final 可变 Map，无公开注入口）；
4. `new PMMLRuntimeInternalImpl().evaluate(modelName, ctx)` → `PMML4Result`。

模型编译一次（bean 构造时），classloader + 产物 map 只读共享跨请求复用；每请求只新建 requestData + context。**唯一一处反射**锁死 8.44.2，是 trusty standalone API 缺"用刚编译产物建 context"公开口子的补丁。依赖：`kie-pmml-dependencies`(pom) + `javax.xml.bind:jaxb-api:2.3.1` + `org.glassfish.jaxb:jaxb-runtime:2.3.8`（Java 21 移除了 JDK 自带 JAXB）。

---

## 2. `drools-persistence-jpa`（官方会话持久化）—— ⛔ 已核实：本栈上被 Jakarta 边界卡死

> **2026-08-17 核实**：`drools-persistence-jpa:8.44.2` 的实体（`org.drools.persistence.info.SessionInfo` / `WorkItemInfo`）**编译期依赖 `javax.persistence`**（jar 里 53 处 `javax/persistence`、0 处 `jakarta/persistence`）。本项目跑在 **Spring Boot 3.3 / Jakarta EE 10 / Hibernate 6（`jakarta.persistence`）** 上——两套 JPA 命名空间**不兼容**：Jakarta 的 Hibernate 不会扫 `javax.persistence` 注解的实体。
>
> 这比原判断（"没有 JTA starter"）更根本，**也正是 Step 10 当初选简化单表 Marshaller 的真实原因**（CLAUDE.md 那句"SB3 配 JTA 没官方 starter"只说了表层）。要硬上得付出：① 字节码把 drools-persistence jar 做 javax→jakarta 变换（Eclipse Transformer 之类），或 ② 塞第二套 Hibernate 5.x（javax）与 SB3 的 Hibernate 6 并存——都是给教学脚手架引一堆脆弱构建/类加载魔法，投入产出为负。**结论：本栈上不做**；真要官方多表持久化得换 Drools 版本线或降 Spring Boot 到 2.x（javax），那是另一个项目级决策。

**（以下为原规划，保留作背景）改动半径**：③ 引入 JTA 事务管理器（app 级配置改造），落在 `drools-lab` 但外溢到应用配置。

- **是什么**：把 Step 10 的"简化单表 Marshaller"换成官方 `drools-persistence-jpa` —— JTA + `SessionInfo`/`WorkItemInfo` 多表 + 自动事务边界。
- **落地步骤**：① 加 `drools-persistence-jpa` + JTA 事务管理器（Spring Boot 3 **无官方 starter**，选 Narayana `narayana-spring-boot-starter` 或 Bitronix）；② 配 `Environment` 绑 `EntityManagerFactory` + `TransactionManager`；③ `KieServices.getStoreServices().newKieSession(kbase, null, env)` 建持久化会话；④ 新 Step 或改造 Step 10（建议**新 Step 25** 并列，别动 Step 10——它演示的"简化版"本身有教学价值）。
- **工作量**：1–2 天（JTA 配置 + 多表 schema + 跟现有 H2/MySQL profile 共存的验证）。
- **风险**：中。JTA 事务管理器跟 Spring Boot 3 的整合有坑；跟现有 `spring-boot-starter-data-jpa`（本地事务）并存要分清边界。
- **建议**：**可做**，教学价值明确（"官方重工程版 vs 本仓库简化版"对照）。优先级中。CLAUDE.md 已注明"Spring Boot 3 配 JTA 没官方 starter，落地路径选了简化版"——本项就是把那句话兑现成一个对照 Step。

---

## 3. KieServer（规则执行做成独立服务）—— ⛔ 同被 Jakarta 边界卡死

> **2026-08-17 核实**：`org.kie.server:kie-server-spring-boot-starter:8.44.2` 在中央/镜像仓**根本没这个坐标**（KIE Server 走 jbpm/kie-server 独立版本线，8.44.2 那代仍是 **Spring Boot 2.x / javax**）。就算换到能用的 KIE Server 版本，它也是 SB2/javax 世界，跟本项目 SB3/Jakarta 主栈对不上；而且它本就是"另起一个部署单元"（新 app + 动 docker-compose/网关），大改动。**结论：本栈上不做。**

**（以下为原规划，保留作背景）改动半径**：③ 新增部署单元 + 客户端。

- **是什么**：把规则执行做成独立 REST/SOAP 服务，应用通过 kie-server client 远程调，规则与应用彻底解耦。
- **落地步骤**：① 用 `kie-server-spring-boot-starter` 起一个**新的 Spring Boot 应用**（第 3/4 个 app，端口错开）；② 把某个教学 kbase 打成 KJAR 部署给它（复用 Step 16 的 KJAR 构建）；③ `drools-lab` 侧加一个 `kie-server-client` 的 `KieServicesClient` 调它；④ 动 `deploy/docker-compose.yml`（加 kie-server 容器）+ `nginx.conf`（网关分流，参照 decision 那一跳）。
- **工作量**：2–3 天（新 app + KJAR 部署契约 + 网关 + 编排验证）。
- **风险**：中。kie-server 有自己的一套 container 部署/生命周期 API，学习曲线在"部署契约"不在"规则"。
- **建议**：**可做但优先级低**。它演示的是"部署拓扑"而非新的规则能力，且跟本仓库已有的 console/decision 两 app 拆分在"服务化"主题上重叠。想演示"规则即服务"再做。

---

## 4. jBPM 集成（+ `ruleflow-group`）—— ⛔ 同代 javax/SB2 + 最大体量

**改动半径**：③ 引入第二个引擎 + 流程持久化。8.44.2 那代 jBPM/流程运行时同样是 javax/SB2 世界（跟 §2/§3 同一道 Jakarta 边界），叠加"第二个完整引擎 + 流程持久化"的体量，本栈上**不做**。

- **是什么**：把规则嵌进业务流程（BPMN2），`ruleflow-group` 由流程节点驱动而非 `setFocus`。这是"规则 + 流程编排"。
- **落地步骤**：① 加 `jbpm-flow` / `jbpm-bpmn2`（重，拉一大票流程运行时依赖）；② 画一个 `.bpmn2`（含 Business Rule Task 指向某 kbase 的 `ruleflow-group`）；③ `KieBase` 里规则标 `ruleflow-group "x"`，流程走到 Rule Task 时激活该组；④ 可选：流程实例持久化（又要 JTA，跟第 2 项叠加）。
- **工作量**：3–5 天（jBPM 是另一个完整引擎，光跑通一个 BPMN + Rule Task 就不小）。
- **风险**：高。体量最大，且 jBPM 在 Apache KIE 改名后的版本线跟 Drools 8.44.2 的兼容需要核对。
- **建议**：**默认不做**。它把 demo 从"Drools 能力地图"扩成"KIE 全家桶"，超出本仓库定位（CLAUDE.md：`ruleflow-group` 标了 ⬜️ 且注明"要 jBPM 才能激活"）。若只想演示 `ruleflow-group` 而不想引 jBPM——做不到，它就是要流程驱动。

---

## 5. Rule Units（Drools 8 incubator）

**改动半径**：② 只在 `drools-lab` 内（加 `drools-ruleunits-*` + 新类）。

- **是什么**：把规则 + 数据源打包成强类型单元（`RuleUnitData` + `DataStore`/`DataStream`），是**另一种规则组织范式**（替代 KieBase/KieSession 的编程模型）。
- **落地步骤**：加 `drools-ruleunits-engine` + `drools-ruleunits-dsl`，写一个 `RuleUnitData` 实现 + 对应 DRL（`unit XxxUnit;`），`RuleUnitProvider.get().createInstance(data)` 跑。
- **工作量**：1 天（技术上 contained）。
- **技术可行性 2026-08-17 已核实**：`drools-ruleunits-{api,engine,dsl}:8.44.2` 全部解析成功，且是**纯引擎**（jar 里 0 处 `javax.persistence`/`jakarta.persistence`/`javax.servlet`）——**不碰 Jakarta 边界，本栈上真能编能跑**。是 §2–§4 里唯一没被卡死的重型项。
- **风险**：**原则冲突**。项目明确"不追 incubator"（选 8.44.2 就是为了避开 9.x incubator）。Rule Units 在 8.x 仍是 incubator 特性。
- **决定（2026-08-17）→ 尝试后回退**：先决定破一次 incubator 例做了 Step 25，但**打包部署时撞两堵 fat-jar 硬墙，遂回退**：
  1. **与 traits 互斥**：`drools-ruleunits-impl` 和 `drools-traits`(Step 21) 都要独占 Drools 唯一的 `RuntimeComponentFactory` SPI 单例（`RuntimeComponentFactory.get()` 只认一个）。两者同 classpath 时 ruleunits 的工厂胜出，traits 的 `TraitKnowledgePackageImpl.mergeTraitRegistry` 强转失败 → **加 Step 25 把 Step 21 弄崩**（`ClassCastException`）。无开关可解，二者只能留一个。
  2. **fat jar 里扫不到自己的 DRL**：解释执行 provider `RuleUnitProviderImpl.collectResourcesInJar` 用 `java.nio.file` 遍历 jar 找 DRL，遇到 Spring Boot **嵌套 jar** 路径（`…/console.jar/!BOOT-INF/lib/drools-lab.jar`）抛 `NoSuchFileException`。单独也崩，要修得上 exploded jar 或 `kie-maven-plugin` 编译期代码生成（重活）。
  - **surefire 扁平 classpath + 真实 `target/classes` 目录测得过，打包成 fat jar 即崩**——这正是"不追 incubator"要防的那类"测试绿、部署崩"的不稳定。**保 traits(稳定、非 incubator)、回退 Rule Units**。代码/依赖/文档已全部撤回。

---

## 6. per-tenant KieContainer（多租户隔离）

**改动半径**：③ **唯一会碰生产平台代码的**。

- **是什么**：给每个租户一套独立 KieContainer/KieBase。
- **为什么不建议**：平台**已在应用层做多租户**（`TenantContextFilter` + 租户列 + 决策快照按租户+bizLine 分桶）。规则编译缓存现在是"单个足迹加权 LRU、按 artifact 键、跨租户共享"（`ActivityRuleRuntimeService`）。改成 per-tenant 容器要重构这个缓存模型 + 快照归属模型，**放大内存足迹 N 倍**（容量模型见 `docs/capacity-model.md`，规则引擎内存本就是头号约束线），换来的隔离性应用层已经有了。
- **建议**：**明确不做**。这是反模式，投入产出为负。多租户的正解在数据面隔离（已实现），不在引擎实例隔离。

---

## 7. Business Central / KIE Workbench

**改动半径**：③ 独立 web 平台 + git 规则仓库，基本不进 Spring Boot 体系。

- **是什么**：规则可视化编辑 + 版本治理 Web 平台（历史上是 WildFly/EAP 上的 WAR + 内置 git）。
- **建议**：**不做**。它不是"往模块里加代码"，而是"部署另一套系统"，且与本仓库"代码即规则脚手架"的定位正交。要给业务方可视化编辑，更现实的是自建一个薄前端调 Step 9/18 的运行时编译端点（前端控制台已有雏形）。

---

## 8. KIE Test Scenarios（`.scesim`）

**改动半径**：② 但工具链耦合 Business Central。

- **是什么**：表格化"输入→期望输出"回归测试。
- **建议**：**不做**。`.scesim` 设计上就为 Business Central 编辑/运行而生，脱离它独立跑很别扭，价值低。本仓库的回归测试用 JUnit + 金标集（生产侧 `DecisionGoldenSetTest`）已经覆盖同一诉求。

---

## 9. 一页纸结论

> **⚠ 一道横切约束：Jakarta 边界**。Drools 8.44.2 的**持久化/服务器/流程**模块仍是 `javax.*`（javax.persistence / SB2），跟本项目 **Spring Boot 3.3 / Jakarta EE 10** 主栈命名空间不兼容。这一刀砍掉了 §2/§3/§4 三个重型项——不是"要不要做"，是**这个版本栈上做不了**（除非字节码变换 / 塞第二套 Hibernate / 起 SB2 sidecar，都得不偿失）。

| 项 | 半径 | 状态 / 建议 |
| --- | --- | --- |
| ~~PMML / Scorecard~~ | ② | ✅ **已完成**（Step 24，路径 A） |
| ~~drools-persistence-jpa~~ | ③ | ⛔ **被 Jakarta 边界卡死**（实体是 javax.persistence）——本栈不做 |
| ~~KieServer~~ | ③ | ⛔ **被 Jakarta 边界卡死**（SB2/javax，8.44.2 无该 starter 坐标）——本栈不做 |
| ~~jBPM（+ruleflow-group）~~ | ③ | ⛔ 同 javax/SB2 边界 + 最大体量——本栈不做 |
| **Rule Units** | ② | ⛔ **试过后回退**：fat jar 里与 traits 争 `RuntimeComponentFactory` SPI 单例互斥（加它 traits 崩）+ 解释 provider 扫不到 nested-jar 里的 DRL。测试绿、部署崩——保 traits、撤 Rule Units |
| per-tenant KieContainer | ③ | ⛔ 反模式（会放大生产内存 N 倍）——明确不做 |
| Business Central | ③ | ⛔ 独立平台，超出脚手架定位——不做 |
| KIE Test Scenarios | ② | ⛔ 耦合 Business Central，价值低——不做 |

**进度 & 结论（2026-08-17 定稿）**：一档 + 二档全部落地（Step 19–24 + Step 8/13/16 扩展）。三档里 **PMML（Step 24）已做**；**Rule Units 破例做了 Step 25 但部署撞 fat-jar 硬墙后回退**（见 §5）；其余重型项要么被 Jakarta 边界卡死（persistence-jpa / KieServer / jBPM），要么是反模式/超范围。**结论：在当前 SB3/Jakarta/Drools-8.44.2 栈上，凡"技术可行且能稳定部署"的能力已全部落地**——剩下的不做是因为版本卡死、部署崩、反模式或收益不成立，不是没做完。

---

## 10. 收官判断：为什么就此打住（决策留痕）

> 2026-08-17。做完 Step 25 后，有人问"剩下没做的是不是版本问题？做了收益如何？"——这一节把当时的判断固化下来，作为**这轮能力地图扩展的收尾决策**。

### 判断一：不是纯版本问题——7 个剩余项里只有 3 个是版本卡的

| 项 | 卡在哪 | 是版本问题吗 |
| --- | --- | --- |
| drools-persistence-jpa | `javax.persistence`，跟 SB3/Jakarta 不兼容 | ✅ 是 |
| KieServer | SB2/javax 版本线 | ✅ 是 |
| jBPM | 同 javax/SB2 边界 + 体量 | ✅ 版本 + 范围 |
| ~~Rule Units~~ | **不卡版本**（纯引擎）——被"不追 incubator"原则挡 | ❌ 原则问题 → 破例做了 Step 25 → **部署撞 fat-jar 硬墙后回退**（见 §5） |
| per-tenant KieContainer | 架构反模式（放大生产内存 N 倍） | ❌ 换任何版本都是坏主意 |
| Business Central | 是"部署另一套 web 平台"，不是往模块加代码 | ❌ 性质问题 |
| KIE Test Scenarios | 耦合 Business Central，金标集已覆盖 | ❌ 价值问题 |

### 判断二：那 3 个的"版本解法"很贵，且解了收益也低

- **版本解法 = 迁到 Jakarta 原生的 Drools 9.x/10（Apache KIE）**，而这恰是项目**当初刻意避开**的（9.x incubator 不稳、10 文档跟不上，CLAUDE.md 明写不追）。它不是"改一行版本"：整个 reactor 四模块 + 全部 Step 的 API 可能动，且 **`activity-common`（生产侧）也依赖 Drools**——一升就碰生产链路，是项目级大迁移。
- **就算版本免费，收益也撑不起**：
  - `drools-persistence-jpa`：Step 10 已把核心概念（marshall session→bytes→库→rehydrate）讲透，官方版多的是 JTA 多表的**工程仪式**，非新概念，且该模块偏 legacy → **边际收益**。
  - `KieServer`：项目已有 **console/decision 两 app 拆分**，decision 就是"规则执行独立成服务" → **跟已有架构高度重复**。
  - `jBPM`：唯一有**新能力**（BPMN 流程编排 + ruleflow-group），但第二个完整引擎、体量最大，且把项目从"Drools 能力地图"漂成"KIE 全家桶"，**跑题**。

### 判断三：唯一"不卡版本"的 Rule Units → 破例做了、又因部署崩回退

Rule Units 代表 Drools **未来的主 API 方向**（9/10/Kogito），教学前瞻性高，`surefire` 里也确实跑通了（fired=2、query=2）。于是破例做成 Step 25。**但打包成 Spring Boot fat jar 部署时崩了两处**（见 §5）：① 与 `drools-traits`(Step 21) 争 `RuntimeComponentFactory` SPI 单例、二者互斥，加它把 traits 弄崩；② 解释 provider 扫不到 nested-jar 里的 DRL。测试绿、部署崩——**遂回退，保住 traits**。

这反而是"不追 incubator"原则的一次**实证**：incubator 特性不仅是"不稳定"的抽象风险，具体表现就是"扁平 classpath 测得过、真实打包部署即崩，还顺手弄坏一个稳定特性"。回退后本轮对该原则**零例外**。

### 收官结论

**在当前 Spring Boot 3.3 / Jakarta / Drools 8.44.2 栈上，能力地图已扩展到边界**：凡技术可行、有教学价值**且能稳定打包部署**的都做了（Step 1–24 + 扩展）；没做的四类——① Rule Units 试过但 fat jar 部署崩（回退，见 §5）、② 被 Jakarta 边界卡死（persistence-jpa/KieServer/jBPM）、③ 架构反模式（per-tenant）、④ 超出脚手架定位（Business Central/.scesim）——都不是"没做完"，是**经判断/实测后有意不做**。想再往前，唯一的门是换 Drools 版本线，那是独立的项目级决策。**本轮到此收官。**
