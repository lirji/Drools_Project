# QLExpress 与 Drools：使用方法 · 原理 · 流程

> 面试用速查/复习文档。左右对照两套引擎的 **API 用法 → 内部原理 → 执行流程 → 生产落地 → 踩坑**，最后给对比表、选型决策树、高频追问参考答案。
> 手写代码题在 [`coding-drills.md`](coding-drills.md)。Drools 侧的细节可回查 [`../drools-capabilities.md`](../drools-capabilities.md)（能力全景）、[`../rete-intuition.md`](../rete-intuition.md)（算法直觉）、[`../drools-vs-aviator.md`](../drools-vs-aviator.md)（表达式引擎对照）。

---

## 0. 先把定位说清楚（开场第一句就靠它）

| | QLExpress | Drools |
| --- | --- | --- |
| 本质 | **表达式 / 脚本求值引擎**（阿里开源） | **规则引擎 / 产生式系统**（RedHat KIE 体系） |
| 输入 | 一个脚本字符串 + 一个变量 Map | 一组规则（DRL）+ 工作内存里的 facts |
| 输出 | 一个值 | 一批被触发的规则 + 被规则改写的 facts |
| 核心机制 | 词法/语法分析 → **指令集** → 栈式**解释执行** | RETE / **Phreak** 增量模式匹配 + Agenda 调度 |
| 规则之间的关系 | **没有这个概念**，编排靠你自己写 Java | **有**，引擎负责匹配、排序、级联、撤销 |
| 量级 | 一个 jar，几百 KB，几乎无传递依赖 | 一票 `kie-*` 模块，编译期重、启动慢 |

> **一句话**：QLExpress 解决的是"把**一个判断**从代码里抽成可配置字符串"；Drools 解决的是"**一坨规则互相影响**时谁先跑、怎么级联、结论怎么自动撤销"。两者不是同一档竞品，是"表达式 ↔ 系统"的层级差。

---

## 1. QLExpress

### 1.1 版本分裂（先说这个，显得真用过）

| | 3.x | 4.x（2024 重构） |
| --- | --- | --- |
| 坐标 | `com.alibaba:QLExpress:3.3.x` | `QLExpress4` |
| 入口 | `ExpressRunner` | `Express4Runner` |
| 配置 | 构造参数 + `execute` 参数 | `InitOptions`（构造期）+ `QLOptions`（执行期） |
| 安全 | 默认放开，靠策略开关收 | **默认隔离**：脚本不能反射访问 Java 成员，需白/黑名单显式放开 |
| 超时 | 无内置，需外层控制 | `QLOptions.timeoutMillis`，超时抛 `QLTimeoutException` |
| 语法 | 自成一体 | 向 Java / Groovy 子集靠拢 |

**API 不兼容，迁移要重写。** 生产主流仍是 3.x，面试按 3.x 讲、把 4.x 当"我知道它重构了什么"的加分项。

### 1.2 使用方法（3.x）

```xml
<dependency>
  <groupId>com.alibaba</groupId>
  <artifactId>QLExpress</artifactId>   <!-- artifactId 是大写 -->
  <version>3.3.4</version>
</dependency>
```

```java
// ExpressRunner 线程安全，可当单例 Bean 复用（内部指令集缓存是 ConcurrentHashMap）
// 参数：isPrecise = 金额场景走 BigDecimal；isTrace = 打印指令执行轨迹
ExpressRunner runner = new ExpressRunner(true, false);

// context 每次请求新建，不能复用（3.x 里脚本内定义的变量会写回 context）
DefaultContext<String, Object> ctx = new DefaultContext<>();
ctx.put("amount", 660);
ctx.put("vipLevel", 2);

// execute(脚本, 上下文, 错误列表, 是否走指令集缓存, 是否 trace)
Object r = runner.execute("amount > 500 && vipLevel >= 2", ctx, null, true, false);
```

**核心扩展 API（考点）**

| API | 用途 | 例子 |
| --- | --- | --- |
| `addFunctionOfClassMethod` | 把静态方法暴露给脚本 | `addFunctionOfClassMethod("取绝对值", Math.class.getName(), "abs", new String[]{"double"}, null)` |
| `addFunctionOfServiceMethod` | **把 Spring Bean 的方法暴露给脚本**（最常用） | `addFunctionOfServiceMethod("查会员等级", memberService, "getLevel", new Class[]{String.class}, null)` |
| `addFunction` / `addOperator` | 注册自定义函数 / 操作符（继承 `Operator` 实现 `executeInner`） | 自定义 `包含`、`join` |
| `addOperatorWithAlias` | **操作符中文别名**，让业务方读得懂规则 | `addOperatorWithAlias("如果", "if", null)` |
| `addMacro` | 宏：把一段表达式命名复用 | `addMacro("是否成年", "age > 18")` |
| `getOutVarNames(script)` | **静态分析出脚本要用到哪些外部变量** | 按需取数，避免"为跑一条规则把整个用户画像查出来" |
| `parseInstructionSet(script)` | 只编译不执行 | **规则保存时做语法校验**，语法错直接拒绝入库 |
| `clearExpressCache()` | 清指令集缓存 | 规则热更新后调用 |

`getOutVarNames` 是很多人不知道的加分点：

```java
String express = "int 平均分 = (语文+数学+英语+综合考试.科目2)/4.0; return 平均分";
String[] names = runner.getOutVarNames(express);
// var: 数学, var: 综合考试, var: 英语, var: 语文
```

**语法能力**：不声明类型也行、`if/else`、`for/while/break/continue`、`return`、`function` 自定义函数、`import`、`new`、数组/集合、三目、`in`。所以它比 Aviator / SpEL 更像"小脚本语言"，而不只是"一个表达式"。

**4.x 写法（对照记）**

```java
Express4Runner runner = new Express4Runner(
    InitOptions.builder()
        .securityStrategy(QLSecurityStrategy.whiteList(memberList))   // 白名单
        .build());

Object r = runner.execute("a + b * c", contextMap,
    QLOptions.builder()
        .cache(true)           // 编译缓存
        .precise(true)         // BigDecimal 精确计算
        .timeoutMillis(200L)   // 内置超时
        .build()).getResult();

runner.parseToDefinitionWithCache("a+b");   // 预热，避免首次执行抖动
runner.clearCompileCache();                 // 缓存无上限，需定期清
```

### 1.3 原理（分层，背下来）

```
脚本字符串
   │
   ├─① 词法分析 (Lexer) ───── 切成 word：关键字 / 标识符 / 字面量 / 操作符
   │
   ├─② 语法分析 (Parser) ──── 按内置 EBNF 语法定义自顶向下匹配
   │                          产出 ExpressNode 语法树（AST）
   │
   ├─③ 编译 ──────────────── 遍历 AST，压平成线性 InstructionSet（指令数组）：
   │                            · 压常量 / 压变量属性（LoadAttr）
   │                            · 操作符指令（弹操作数 → 算 → 压回）
   │                            · 条件跳转 / 无条件跳转（实现 if / for / while）
   │                            · 调用自定义函数
   │                            · 开 / 闭作用域（局部变量区）
   │        ▲ 缓存就缓在这一层：Map<脚本文本, InstructionSet>
   │
   └─④ 解释执行 ──────────── InstructionSetRunner：
                              建 RuntimeEnv（操作数栈 + 上下文引用）
                              pc 指针逐条取指令 → execute → 压 / 弹栈
                              跳转指令改 pc
                              结束时栈顶即返回值
```

四个必背结论：

1. **它是"编译成自定义指令集 + 解释执行"，不生成 JVM 字节码。** 对比：Aviator 编译成字节码（单表达式更快）；Groovy 动态生成 Class（表达力强，但**类爆炸 → Metaspace 泄漏**风险）。QLExpress 不产生新 Class，所以**规则频繁热更新不会撑爆 Metaspace**——这是它在大促场景被选中的关键原因。
2. **缓存缓在"指令集"这一层**，key 是脚本文本。同一条规则第二次执行省掉 ①②③，只剩 ④。
3. **`ExpressRunner` 可以做单例，`context` 必须每次新建。**
4. **线程安全边界**就是这条：Runner 无状态可共享，执行态全在 `RuntimeEnv` 里。

> 类名随版本略有出入（`ExpressParse` / `ExpressNode` / `InstructionSet` / `InstructionSetRunner` / `RuntimeEnv` / `OperateData` / `DefaultContext`），但**"AST → 线性指令 → 栈式解释执行 + 指令集缓存"这个形状是稳定的**，面试讲形状即可。

### 1.4 单次执行流程

```
业务请求
  → 从 DB / 配置中心取规则脚本（带版本号）
  → runner.getOutVarNames(脚本)      ← 可选：算出要哪些字段，按需查数据
  → new DefaultContext，put 业务变量
  → runner.execute(脚本, ctx, errList, isCache=true, false)
       ├─ 命中指令集缓存 → 直接进 ④ 解释执行
       └─ 未命中 → 词法 → 语法 → 编译 InstructionSet → 入缓存 → 执行
  → 栈顶结果转 Boolean / BigDecimal
  → catch 异常 → 走兜底（默认放行 / 默认拒绝，看业务）
```

### 1.5 生产落地 & 踩坑

| 环节 | 做法 |
| --- | --- |
| 规则存储 | DB 表：脚本文本 + 版本号 + 生效时间 + 状态 |
| **保存时校验** | `parseInstructionSet()` 编译一遍，语法错直接拒绝入库 |
| 发布 | 版本号 / 代际号 + 轮询或 MQ 通知各节点 → 拉新脚本 → 预热编译 |
| 灰度 | 按流量比例走新版脚本，出错自动回滚上一版本 |
| **安全** | 3.x：`QLExpressRunStrategy.setForbidInvokeSecurityRiskMethods(true)` 禁掉 `Runtime.exec` / `System.exit` 之类，再叠自己的方法白名单；4.x：默认隔离 + `QLSecurityStrategy.whiteList/blackList/open` |
| **超时** | 4.x 用 `QLOptions.timeoutMillis`；3.x 用外层线程池 + `Future.get(timeout)`，防 `while(true)` 打挂线程 |
| **精度** | 金额一律开 `isPrecise=true` / `precise(true)`，否则 `0.1+0.2 != 0.3` |
| 可观测 | 自己埋点：脚本 ID、耗时、结果、异常；`isTrace` 只适合排查，别在生产常开 |

**坑：**

1. **缓存无上限**：脚本文本作 key，若把用户 ID 之类的变量**拼进脚本**，key 会无限膨胀 → **规则必须参数化，变量走 context**。
2. **`null` 参与比较**的默认行为容易出意外（QLExpress 有相关策略开关），团队要统一约定。
3. 脚本报错默认返回 null，**调用方必须有 fail-safe 分支**。
4. 没有"规则之间的关系"：优先级、互斥、叠加、终止全得自己写调度器——这是选它时最容易低估的工作量。

---

## 2. Drools

### 2.1 核心概念（心智模型）

| 概念 | 是什么 | 类比 |
| --- | --- | --- |
| **fact** | 塞进引擎的业务对象（POJO / record） | 数据库里的一行 |
| **working memory** | 当前 session 持有的所有 fact | 一张内存表 |
| **rule (DRL)** | `when`（LHS 条件）+ `then`（RHS 动作） | 带触发器的 SQL |
| **KieBase** | 一组**编译好**的规则（含 Phreak 网络） | 编译后的存储过程集 |
| **KieSession** | 从 KieBase 派生的运行时会话，**线程不安全** | 数据库连接 |
| **KieContainer** | 持有 KieBase 的部署单元（按 classpath 或 ReleaseId） | 部署包 |
| **Agenda** | 已匹配、待执行的 activation 队列 | 待办执行计划 |

> **一句话性能观**：KieBase 编译贵（本仓库启动建网络要 0.5–1s），KieSession 廉价。所以 **KieBase 单例缓存 + 每请求 new / dispose 一个 Session**。

### 2.2 使用方法

**方式 A：classpath + kmodule.xml（静态规则 / 教学）**

```
src/main/resources/
  META-INF/kmodule.xml       ← 声明 kbase / ksession
  rules/discount/*.drl       ← 目录名必须和 kbase 的 packages 对齐
```

```java
KieServices ks = KieServices.get();
KieFileSystem kfs = ks.newKieFileSystem();
kfs.writeKModuleXML(kmoduleBytes);
kfs.write("src/main/resources/rules/order-discount.drl", drlResource);

// 决策表 / DMN 必须显式标类型：8.44.2 的 ClasspathKieProject 不会自动认 .xls/.dmn
kfs.write(ks.getResources().newByteArrayResource(xls)
          .setResourceType(ResourceType.DTABLE)
          .setSourcePath("src/main/resources/rules/decision/vip-discount.xls"));

KieBuilder kb = ks.newKieBuilder(kfs).buildAll();
if (kb.getResults().hasMessages(Message.Level.ERROR)) {   // 编译期校验
    throw new IllegalStateException(kb.getResults().getMessages().toString());
}
KieContainer container = ks.newKieContainer(kb.getKieModule().getReleaseId());
```

> 本仓库 `drools-lab/src/main/java/com/lrj/drools/config/DroolsConfig.java` 就是这个写法，注释里写明了为什么不用一行的 `getKieClasspathContainer()`。

**方式 B：运行时编译 DRL 字符串（"规则即数据"，生产更常用）**

```java
KieHelper helper = new KieHelper();
helper.addContent(drlText, ResourceType.DRL);

Results results = helper.verify();                    // 先校验，拿行号级报错
if (results.hasMessages(Message.Level.ERROR)) {
    throw new IllegalArgumentException(results.getMessages(Message.Level.ERROR).stream()
        .map(m -> "line " + m.getLine() + ": " + m.getText())
        .collect(Collectors.joining("\n")));
}
KieBase base = helper.build();                        // 缓存起来，key = 租户 + DRL 文本
```

**执行**

```java
KieSession session = kieBase.newKieSession();
try {
    session.setGlobal("result", resultHolder);
    session.insert(customer);
    session.insert(order);
    int fired = session.fireAllRules();               // 或 fireAllRules(max) 熔断
} finally {
    session.dispose();                                // 必须，否则 working memory 泄漏
}
```

无状态场景直接用 `StatelessKieSession`——**本身线程安全**，`execute(Iterable)` 一把梭。注意：一旦要 `setGlobal`，就别把它当字段缓存复用（global 会被并发请求互相覆盖），每次 `newStatelessKieSession()` 即可——创建极便宜，贵的是 KieBase。

**DRL 结构（写在纸上也要会）**

```java
package com.x.rules
import com.x.domain.Order;
global java.util.List result;                     // 注入外部对象
declare Trade @role(event) @timestamp(occurredAt) end   // CEP 事件声明

rule "VIP2 打八折"
  salience 100              // 优先级，越大越先
  agenda-group "discount"   // 分组，配合 setFocus 做阶段编排
  no-loop true              // 防自己 RHS 重新激活自己
  lock-on-active true       // 防跨规则重新激活自己
when
  $c: Customer(vipLevel == 2)
  $o: Order(customer == $c, totalAmount > 500)    // 跨 fact join
then
  $o.setFinalAmount($o.getTotalAmount().multiply(new BigDecimal("0.8")));
end
```

关键 LHS 能力：`accumulate`（聚合）、`not` / `exists` / `forall`（量词）、`from`（锁数据源）、`over window:time(5m)`（CEP 滑窗）、`eval`（万能但慢，慎用）。

### 2.3 原理：RETE → Phreak

**朴素做法**：每次 fire 全量重扫，复杂度 `O(R × F^P)`（R 规则数、F fact 数、P 每条规则的 pattern 数）；更要命的是 99% 的 fact 跟本次变更无关，重算纯浪费。

**RETE 的两个核心想法**

- **想法 A：把所有规则的 LHS 编译成一张共享 DAG。** 相同的原子条件复用同一个节点——`Customer(vipLevel == 1)` 在 50 条规则里出现也只有一份 Alpha 节点。相当于编译期"提公因式"。
- **想法 B：每个节点缓存"目前匹配到这里的 fact 组合"。** 新 fact 进来只在受影响路径上增量传播。代价是内存——RETE 出名的标签就是 *memory hog*。

**三种节点**

| 节点 | 干啥 | 类比 SQL |
| --- | --- | --- |
| **Alpha** | 单 fact 单字段判断（类型过滤 + 字段过滤） | 单表 WHERE |
| **Beta (Join)** | 多 fact 关联（`customer == $c`），有 **Left / Right 两块 memory** | JOIN |
| **Terminal** | 某规则全部 pattern 满足 → 产生 activation 入 Agenda | 输出结果行 |

**Drools 实际用的是 Phreak，不是教科书 RETE**：在"共享 + 增量"之上加了 **lazy + segmented 传播**——`insert` 时不立刻传到 terminal，按 segment 攒着，`fireAllRules()` 时按需批量算，且是 set-based（成批传 tuple）而非逐个 token。所以现象是：**启动慢**（建网络）、**insert 快**、**fireAllRules 偶尔慢一下**（在补传播）。

**Agenda 冲突消解顺序**：`agenda-group` 焦点栈（LIFO）→ `salience` 高者先 → 同级默认按 recency（后进先出）。所以"规则顺序变，结果可能变"是 Drools 的固有属性，必须靠 salience / 分组显式约束。

**另外三块能力的原理一句话**

- **TMS（真值维护）**：`insertLogical` 把衍生 fact 与"导出它的 LHS 匹配"绑定，前提失配时引擎**自动 retract** 衍生结论；普通 `insert` 与前提解耦，要手动撤销。
- **CEP**：`@role(event)` + stream mode，引擎维护事件时间线，滑窗外事件自动过期 retract；测试用 `clockType="pseudo"` 人工推进时钟保证可重现。
- **后向链**：`query` 是目标驱动的反向递归证明，**不进 agenda、不需要 fireAllRules**，走 `session.getQueryResults(...)` 同步 pull。

### 2.4 完整执行流程（两条时间线分开讲）

**编译期（贵，只做一次）**

```
DRL / 决策表(.xls) / DMN
  → KieServices → KieFileSystem（写资源 + 标 ResourceType）
  → KieBuilder.buildAll()        ← 语法 / 类型校验在这里，报错带行号
  → KieModule → KieContainer
  → KieBase：生成规则类 + 构建 Alpha / Beta / Terminal 网络
             （每个 KieBase 自带 ClassLoader，规则类落 Metaspace）
```

**运行期（每请求）**

```
newKieSession()
  → insert(fact) × N
       Root → ObjectTypeNode(类型) → Alpha(字段过滤) → Beta(join，写 Left/Right memory)
  → 全部 pattern 满足 → Terminal 产生 Match/Activation → 入 Agenda
  → fireAllRules()
       ├ 冲突消解：agenda-group 焦点 → salience → recency
       ├ 执行 RHS
       │    └ RHS 里 insert / modify / retract → 再次传播 → 可能产生新 activation（推理循环）
       └ Agenda 空 → 返回 fired 次数
  → dispose()
```

### 2.5 生产落地：规则热更新三条路线

| 路线 | 机制 | 适用 |
| --- | --- | --- |
| **KJAR + KieScanner** | 规则打成带版本号的 Maven 构件，引擎轮询仓库，发现新版本自动替换 KieBase | 规则要**独立于代码发版**，有制品仓库 |
| **DB 存 DRL + 运行时编译** | 规则即数据，`KieHelper` 编译成 KieBase 进缓存 | 多租户 / 业务后台配规则 / LLM 生成规则（**本仓库活动引擎走这条**） |
| **决策表 / DMN** | 业务方用 Excel 或 DMN 建模器维护，编译成 DRL 或走 DMNRuntime | 让**非程序员**维护规则 |

**热替换安全性关键**：一个 KieSession 关联的是它**创建时**的 KieBase 引用；即使缓存里 KieBase 被换掉，正在跑的 session 继续用老 KieBase 直到 dispose——**不打断进行中的请求**。

**生产护栏（Drools 独有，很好答）**

| 护栏 | 用法 |
| --- | --- |
| `fireAllRules(max)` | fire 次数硬熔断，防失控规则 |
| `session.halt()` | 跨线程打断，做超时优雅返回 |
| `AgendaFilter` | fire 前按规则元数据放行 / 拦截，做**灰度 / 金丝雀** |
| `AgendaEventListener` / `RuleRuntimeEventListener` | 记录"哪条规则触发了几次"，接 Micrometer → Prometheus |

### 2.6 踩坑（说出来就是"真踩过"）

1. **`update()` 死循环**：`update` 会重新评估所有依赖该 fact 的规则；若 LHS 看的字段和 RHS 改的字段不冲突（LHS 看 `vipLevel`、RHS 改 `finalAmount`），条件永远满足 → 无限触发。`no-loop true` **只防自己激活自己，不防其他规则的 update 间接激活自己**，跨规则要 `lock-on-active` + `agenda-group`。优先用 `modify($f){...}`（声明改了哪个属性，Phreak 传播更精准）。
2. **DRL 是运行时解析且 lazy compile**：`mvn compile` 通过 ≠ 规则没语法错，**第一次请求**才触发编译报错。改完 DRL 必须跑一次冒烟。
3. **record 在 RHS 没有 accessor 糖**：LHS 里 `Customer(age >= 18)` 引擎会自动适配 record accessor，但 RHS 直接编译成 Java，必须写 `$p.message()` 而不是 `$p.getMessage()`。
4. **KieSession 线程不安全**，不要为了"省"而复用；要线程安全用 `StatelessKieSession`。
5. **β memory 爆炸**：n 个 Customer × m 个 Order 在 join 节点最多攒 n×m tuple。生产要么每次只 insert 相关 fact，要么走 stateless。
6. **LHS 里别写方法调用**：`Order(expensiveMethod() == X)` 没法 hash 索引，每个 fact 进来都要重算；能预算的字段在 Java 侧算完再 insert。
7. **KieBase 缓存要有界，且按"规则数"加权**：本仓库实测 KieBase 足迹 ≈ `260KB + 37KB × 规则数`（堆 + Metaspace），**由规则数主导而非活动数**——「1 活动 × 200 档」≈「10 活动 × 20 档」。用 `maximumSize`（按个数）会把大规则集低估约 20 倍，噪声租户能悄悄吃爆堆；改成 Caffeine `maximumWeight` + weigher 按足迹计权，并配 `-XX:MaxMetaspaceSize`。
8. **MySQL 下 `@Lob` 大字段被截断**：session 快照建成 64KB `blob`、DRL 文本建成 64KB `text`。改用 `@JdbcTypeCode(SqlTypes.LONGVARBINARY / LONGVARCHAR)` 映射成 `longblob` / `longtext`。

---

## 3. 对比与选型

| 维度 | QLExpress | Drools |
| --- | --- | --- |
| 规则间协作 | 无，自己写调度 | Agenda 自动调度 / salience / 级联激活 |
| 跨事实 join | 无，自己拼 | LHS 原生支持 |
| 聚合 / 否定 / 存在 | 无 | `accumulate` / `not` / `exists` / `forall` |
| 状态累积 | 无状态 | working memory 可累积，可 Marshaller 持久化续跑 |
| CEP 时间窗 | 无 | 滑窗、事件过期、时序操作符 |
| 真值维护 | 无 | `insertLogical` 自动撤销衍生结论 |
| **安全沙箱** | **强**（黑白名单、4.x 默认隔离、超时） | **弱**（RHS 直接编译成 Java，可写任意代码；规则源不可信时很危险） |
| **动态性** | **强**（字符串即改即用，零编译成本） | **弱**（改规则要重编 KieBase，需专门做热加载工程化） |
| **冷启动 / 部署足迹** | **极轻**，能塞进边缘 / Serverless | 重，运行时要带编译器 |
| **测试成本** | 输入 → 断言输出 | 起 session、造 facts、fire、断 working memory |
| **可审计** | 黑盒（4.x 有 trace 树） | **强**：规则粒度的触发轨迹 + 指标 |
| 谁维护规则 | 仍偏程序员 | 决策表 / DMN 让业务方用 Excel 维护 |
| **性能反直觉** | 单表达式几乎必赢 | **规则少、fact 少时反而更慢**（working memory + agenda 开销）；规则多 × fact 多才体现增量匹配优势 |

**选型决策树**

- 只是"把一个布尔 / 数值判断抽成可配置字符串" → **QLExpress**（或 Aviator / MVEL / SpEL），Drools 是杀鸡用牛刀
- 规则之间有依赖、要级联触发、要聚合 / 否定、要引擎帮你调度 → **Drools**
- 需要跨调用累积状态、时间窗、结论自动撤销 → **Drools**（QL 做不到）
- 规则来自不可信来源（用户 / 外部 / LLM 直出）→ **QLExpress**（沙箱），或 Drools + 严格 DRL 模板拼装 + 标识符白名单
- 规则改动极频繁、要求秒级生效 → QLExpress 天然赢；Drools 需 KieScanner 或运行时编译 + 缓存
- 业务方（非程序员）自己维护 → Drools 决策表 / DMN
- 常见组合：**轻场景 QLExpress 顶上，复杂编排才引 Drools**；或把 QLExpress 当 Drools 规则里的"表达式插槽"

---

## 4. 高频追问 + 参考应答

**Q：QLExpress 和 Aviator / Groovy / SpEL 怎么选？**
A：Aviator 编译成 **JVM 字节码**，单表达式最快，但脚本能力弱；QLExpress 是**指令集 + 解释执行**，慢一点但脚本能力完整（if/for/函数/宏/中文操作符别名），业务可读性最好，且**不生成新 Class，热更新不撑 Metaspace**；Groovy 表达力最强但动态编译产生大量类，长期热更新有 Metaspace 泄漏风险且安全面大；SpEL 绑 Spring、无沙箱、复杂脚本吃力。**规则频繁热更新 + 业务方可读 → QLExpress；纯高频数值计算 → Aviator。**

**Q：规则引擎怎么防死循环 / 失控？**
A：Drools 侧四道闸——`no-loop` / `lock-on-active` 防重激活，`fireAllRules(max)` 硬熔断，跨线程 `halt()` 超时打断，`AgendaFilter` 灰度拦截；QLExpress 侧靠执行超时（4.x `timeoutMillis`，3.x 外层 `Future` 超时）+ 禁危险方法 + 规则审核时禁 `while(true)`。

**Q：规则热更新的完整链路怎么设计？**
A：规则入库时先**预编译校验**（QL 用 `parseInstructionSet`，Drools 用 `KieBuilder` / `KieHelper.verify()` 拿行号级报错）→ 发布产生**版本号 / 代际号** → 各节点轮询或 MQ 通知拉取 → **预热编译进缓存**（Drools 缓存 KieBase，key 带租户，容量有界并按规则数加权淘汰）→ 灰度放量 → 异常自动回滚上一代 → 全程 **fail-safe：编译或执行异常一律回退旧 Java 逻辑**，规则引擎不能成为单点。

**Q：Drools 为什么快？慢在哪？**
A：快在 **Phreak 增量匹配**——共享节点提公因式 + 节点 memory 缓存匹配进度，只算 delta。慢在**编译期建网络**（KieBase 贵）和**内存**（β memory 是 n×m 量级）。规模小的时候它比一次表达式求值还慢，别迷信"Drools 更快"。

**Q：为什么 KieBase 单例、KieSession 每次新建？**
A：KieBase 是编译产物（Phreak 网络 + 生成的规则类 + 独立 ClassLoader），构建成本在百毫秒到秒级；KieSession 只是挂在网络上的 working memory 视图，创建极廉价但**线程不安全**，且不 dispose 会泄漏。无状态场景直接用线程安全的 `StatelessKieSession` 复用。

**Q：`no-loop` 和 `lock-on-active` 区别？**
A：`no-loop` 只防**本条规则的 RHS 重新激活它自己**；`lock-on-active` 是"在该 agenda-group 持有焦点期间，本规则只触发一次"，能防**其他规则的 update / modify 间接把我重新激活**。跨规则循环必须靠后者 + agenda-group。

**Q：决策表和 DMN 有什么区别？**
A：决策表是"**规则的表格写法**"，编译成 DRL 跑 Phreak，本质还是 Drools；DMN 是 OMG 的**跨厂商标准模型**，跑独立的 `DMNRuntime`，自带 FEEL 表达式语言和决策需求图（DRG），可移植性更好但和 DRL 不是一套体系。

---

## 5. "我在项目里怎么用的"话术（结合本仓库）

> 我做过一个**多租户活动引擎**：活动规则不写在代码里，而是**规则即数据**存在 DB（活动 / 规则 / 条件 / 发布代际几张表），运行期由 `ActivityDrlBuilder` 把配置翻译成 DRL 文本，`KieHelper` 运行时编译成 KieBase，用 **`StatelessKieSession` + global 收集结果**执行。
>
> 工程化上做了四件事：
> ① **KieBase 缓存用 Caffeine 按足迹加权 LRU**（key = 租户 + DRL 全文，权重按实测 `260KB + 37KB × 规则数`）——压测发现足迹由规则数而非活动数主导，按个数计权会低估约 20 倍、被噪声租户吃爆堆；
> ② 拼进 DRL 的标识符走**正则白名单防注入**；
> ③ **读写分离**——写平面（console:8081）和只读决策热路径（decision:8082）拆成两个服务，decision 的 classpath 上根本没有写平面的 bean，结构上就写不了，还接只读 DB 账号；发布靠**代际号轮询预热**；
> ④ **fail-safe**：编译 / 执行任何异常返回 null，调用方回退旧 Java 逻辑，同时挂 `AgendaEventListener` 出 trace、接 Micrometer 出规则触发指标。

对应代码：

| 讲到的点 | 文件 |
| --- | --- |
| DRL 文本生成 + 标识符白名单 | `activity-common/src/main/java/com/lrj/drools/activity/engine/ActivityDrlBuilder.java` |
| 运行时编译 + 加权缓存 + fail-safe | `activity-common/src/main/java/com/lrj/drools/activity/engine/ActivityRuleRuntimeService.java` |
| 程序化 KieContainer 构建（含决策表 / DMN） | `drools-lab/src/main/java/com/lrj/drools/config/DroolsConfig.java` |
| 热加载最小实现（Step 9） | `drools-lab/src/main/java/com/lrj/drools/service/HotReloadService.java` |
| 护栏 AgendaFilter（Step 14） | `drools-lab/src/main/java/com/lrj/drools/guard/ReleaseAgendaFilter.java` |
| 审计 listener（Step 6） | `drools-lab/src/main/java/com/lrj/drools/audit/RuleAuditListener.java` |

---

## 参考

- [alibaba/QLExpress (GitHub)](https://github.com/alibaba/QLExpress) — README 与 4.x 文档
- [ExpressRunner.java 源码](https://github.com/alibaba/QLExpress/blob/master/src/main/java/com/ql/util/express/ExpressRunner.java)
- [QLExpress 核心 API 用法配置与自定义扩展详解](https://developer.aliyun.com/article/621206)
- [QLExpress 的基本语法](https://developer.aliyun.com/article/621207)
- Charles Forgy, *"Rete: A Fast Algorithm for the Many Pattern/Many Object Pattern Match Problem"* (1982)
