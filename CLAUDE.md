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

后续（LLM 联动 / KieScanner+KJAR 全套）按需扩展，**没需求时不要提前加**。这是学习项目，不要引入持久化、观测性、复杂部署相关的东西，除非 owner 明确要做某个 Step。

## 技术栈与版本背景

- Java 21 / Spring Boot 3.3.5 / Drools 8.44.2.Final / Maven (wrapper)
- **为什么选 8.44.2.Final**：Drools 9.x 仍偏 incubator；8.44.2 是社区验证过的 Spring Boot 3.3 + Java 21 稳定组合。Drools 10 (Apache KIE 改名后的新线) 文档/教程跟不上，本项目不追

## 常用命令

```bash
./mvnw spring-boot:run        # 起 web 服务 (默认 8081)
./mvnw test                   # 跑测试 (目前只有 spring-boot-starter-test，没业务测试)
./mvnw clean package          # 打 jar
./mvnw clean compile          # 只编译 Java；不会校验 DRL 语法
```

**端口 8081** 跟主项目 LangChain4j (8080) 错开，方便两个 demo 同时跑。改端口看 `application.yml`。

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

## 代码结构（按职责，不是按目录）

- `domain/` — fact 类型。record (`Customer`, `OrderItem`, `Promotion`) 用于不可变事实；mutable POJO (`Order`, `Cart`) 用于会被规则改字段的事实。`Promotion` 是 Step 4 引入的"标记 fact"，规则自己 insert 出来给 `not` 检测用
- `audit/` — Step 6 引入。`RuleAuditListener` 实现两个 Drools listener 接口攒 `AuditEvent[]`，静态 `attachTo(session)` 工厂方法让任意 service 都能挂载
- `service/` — KieSession 生命周期。**每次请求 `newKieSession` + `fireAllRules` + `dispose`**，KieSession 线程不安全，不要为了"省"复用
- `config/DroolsConfig.java` — `KieContainer` 注成单例 Bean（编译规则贵，启动时一次性扫 classpath 的 `META-INF/kmodule.xml`）
- `resources/rules/<kbase>/*.drl` — DRL 文件，**目录名必须和 `kmodule.xml` 里 `<kbase packages="...">` 对齐**
- `resources/META-INF/kmodule.xml` — 声明 `helloKBase` / `discountKBase` / `cartKBase` / `riskKBase` / `pipelineKBase` / `decisionKBase` / `fraudKBase` 七个 kbase + 对应 ksession 名（`fraudKBase` 用 stream mode + pseudo clock）
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

## 配套文档

- `README.md` — 3 个 case 的完整请求示例 + 学习观察点 + 下一步指引
- `docs/rete-intuition.md` — RETE 算法直觉（拿本仓库折扣规则当例子，讲网络结构 + 增量传播 + 写规则原则）
