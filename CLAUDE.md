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

后续（LLM 联动 / KieScanner+KJAR 全套）按需扩展，**没需求时不要提前加**。Step 10 已加 JPA + H2 但仅服务于持久化 demo，不要把它扩成"全项目状态都进数据库"。

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
- `resources/META-INF/kmodule.xml` — 声明 `helloKBase` / `discountKBase` / `cartKBase` / `riskKBase` / `pipelineKBase` / `decisionKBase` / `fraudKBase` / `loyaltyKBase` / `tmsLogicalKBase` / `tmsRegularKBase` / `backwardKBase` 等 kbase + 对应 ksession 名（`fraudKBase` 用 stream mode + pseudo clock；Step 12 的两个 kbase 隔离避免 logical / regular 衍生 fact 互相污染）
- `persistence/` — Step 10 引入。`SessionSnapshot` (JPA entity, sessionId 做主键, `byte[] data` 存 marshall 出来的 KieSession) + `SessionSnapshotRepository` (Spring Data)。H2 file 在 `./data/drools-demo.mv.db`，repo 根 `.gitignore` 已豁免
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

## 配套文档

- `README.md` — 3 个 case 的完整请求示例 + 学习观察点 + 下一步指引
- `docs/rete-intuition.md` — RETE 算法直觉（拿本仓库折扣规则当例子，讲网络结构 + 增量传播 + 写规则原则）
