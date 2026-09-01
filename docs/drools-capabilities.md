# Drools 能力地图

> 活文档。最后核对：2026-08-28；当前仓库教学能力覆盖 Step 1–24 及扩展端点。
>
> 把 Drools (8.44.2 / KIE 体系) 提供的能力系统梳理一遍，按"用来干什么"分类。
> 每项标注**本仓库哪一步展示了**——已展示的链到具体 Step + 文件，未展示的列出来当扩展路线。
> 配合 `README.md` (每步的可运行示例) 和 `CLAUDE.md` (踩坑记录) 一起看。

Drools 不只是"if-else 引擎"。它实际是一组能力的集合，可以粗分成 **七大块**：

1. 推理引擎（前向链 / 后向链）
2. 规则表达力（LHS/RHS 的写法）
3. 执行控制（agenda 编排、护栏）
4. 真值维护（TMS）
5. 复杂事件处理（CEP）
6. 会话与状态（stateful/stateless/持久化）
7. 决策建模与规则交付（决策表 / DMN / KJAR / 热加载）

下面逐块展开。`✅ Step N` = 本仓库已展示；`⬜️` = Drools 支持但本仓库没做。

---

## 0. 架构基石（先建立心智模型）

| 概念 | 是什么 | 类比 |
| --- | --- | --- |
| **fact** | 塞进引擎的业务对象（POJO / record） | 数据库里的一行 |
| **working memory** | 当前 session 持有的所有 fact 集合 | 一张内存表 |
| **rule (DRL)** | `when` 条件 (LHS) + `then` 动作 (RHS) | 一条带触发器的 SQL |
| **KieBase** | 一组编译好的规则 | 编译后的存储过程集 |
| **KieSession** | 从 KieBase 派生的运行时会话 | 数据库连接 |
| **KieContainer** | 持有 KieBase 的容器，按 classpath 或 ReleaseId 组装 | 部署单元 |
| **agenda** | 已匹配、待执行的 activation 队列（LIFO 栈语义） | 待办执行计划 |
| **RETE / Phreak** | 增量匹配算法：只算变更 delta，不全量重扫 | 见 `docs/rete-intuition.md` |

> KieBase 编译贵、KieSession 廉价：本仓库把 KieContainer 注成单例 Bean（`config/DroolsConfig.java`），每次请求新建 + dispose 一个 KieSession（`service/*Service.java`）。

---

## 1. 推理引擎

### 1.1 前向链（forward chaining，数据驱动 push）

数据进 working memory → RETE 增量算出所有能触发的规则 → fire。这是 Drools 的主线，Step 1-12/14-16 全是前向链。

- **基础 facts + when-then + 多规则独立触发** — ✅ Step 1（`/hello`）
- **跨事实 join**（一条规则同时约束 Customer + Order） — ✅ Step 2（`/discount/calculate`）

### 1.2 后向链（backward chaining，目标驱动 pull）

给定一个目标，引擎反向递归找前提。用 `query` 实现，**不进 agenda、不需要 fireAllRules**。

- **递归 query `isContainedIn`**（证明 A 是否递归包含于 B） — ✅ Step 13（`/backward/contains`）
- `session.getQueryResults("name", args...)` 同步 pull 调用 — ✅ Step 13
- 规则 LHS 里用 `?queryName(...)` 把后向链嵌进前向链 — ✅ Step 13 扩展（`/backward/derive`，前向规则 LHS 用 `?isContainedIn` 拉起后向证明）

---

## 2. 规则表达力（LHS / RHS）

### 2.1 LHS（条件侧）

| 能力 | 说明 | 本仓库 |
| --- | --- | --- |
| 字段约束 | `Customer(age >= 18)`，record 也支持（自动适配 accessor） | ✅ Step 1 |
| 绑定变量 | `$c: Customer(...)` 供 RHS 引用 | ✅ 全程 |
| `salience` | 同一 agenda-group 内排序（越大越先） | ✅ Step 2 |
| `accumulate` | 按数据源聚合（sum/count/...），`from` 锁数据源 | ✅ Step 3（`/cart/checkout`） |
| `from` | 把模式数据源锁到 Java 集合，不扫整个 working memory | ✅ Step 3/4 |
| `not` | 否定：working memory 里**不存在**满足条件的 fact | ✅ Step 4（`/risk/evaluate`） |
| `exists` | 存在：满足条件的 fact **至少有一个**（只触发一次） | ✅ Step 4 |
| `forall` | 全称量词：所有匹配 fact 都满足某条件（空集为真，需 `exists` 兜底） | ✅ Step 19（`/quantifier/review`） |
| `eval` | 任意布尔表达式（不进 RETE 索引、每次重算，能用字段约束就别用） | ✅ Step 19 |
| `collect` | 把匹配 fact 收集成一个集合（对比 accumulate 出标量） | ✅ Step 19 |
| 位置模式 `Location(x, y;)` | 配合 `@Position` 注解做解构 | ✅ Step 13 |

### 2.2 RHS（动作侧）

| 能力 | 说明 | 本仓库 |
| --- | --- | --- |
| `insert(fact)` | 加新 fact，可能激活其他规则 | ✅ Step 4（标记 fact + not 自终止） |
| `update(fact)` | 改 fact，重新评估所有依赖它的规则（**易死循环**） | ⚠️ 见 CLAUDE.md 坑 #3，本仓库刻意不用 |
| `modify($f){...}` | 比 update 精准（声明改了哪个属性），Phreak 传播更准 | ✅ Step 3（goldStatus 级联） |
| `retract/delete(fact)` | 删 fact | ✅ 概念见 Step 4/12 |
| `insertLogical(fact)` | TMS 逻辑插入（见第 4 块） | ✅ Step 12 |
| record 的 RHS 限制 | RHS 没有 record accessor 糖，要写 `$p.message()` 不是 `getMessage()` | ⚠️ CLAUDE.md 坑 #6 |

### 2.3 类型声明

- `declare` 在 DRL 里声明新类型 / 给已有类加元数据（如 `@role(event)`） — ✅ Step 8
- **traits**（给 fact 动态贴"接口"实现多态，`don`/`shed`/`isA`） — ✅ Step 21（`/traits/evaluate`）

---

## 3. 执行控制（agenda 编排 + 护栏）

### 3.1 流程编排

| 能力 | 说明 | 本仓库 |
| --- | --- | --- |
| `agenda-group` | 把规则切成独立队列，分阶段执行 | ✅ Step 5（`/pipeline/run`） |
| `setFocus()` | 把 group 压上 agenda 栈（**LIFO，反向压栈**） | ✅ Step 5 |
| `auto-focus true` | 规则被激活时自动把自己 group 压栈 | ✅ Step 5（notify 阶段） |
| `lock-on-active true` | 该 group 拿焦点期间，规则只触发一次（防跨规则重激活） | ✅ Step 5 |
| `no-loop true` | 防"自己 RHS 重激活自己"（**不防**跨规则） | ⚠️ Step 5 对比 |
| `ruleflow-group` | 配合 BPMN 流程驱动规则（jBPM 集成） | ⬜️ |
| `fireUntilHalt()` | 持续 fire 直到 halt（守护线程模式） | ✅ Step 22（`/fireuntilhalt/process`） |

### 3.2 生产护栏（防失控）

| 能力 | 说明 | 本仓库 |
| --- | --- | --- |
| `fireAllRules(int max)` | fire 次数硬上限熔断（失控规则截断） | ✅ Step 14（`/guard/runaway`） |
| `session.halt()` | 跨线程调用，超时打断 fire（优雅返回） | ✅ Step 14（`/guard/timeout`） |
| `AgendaFilter` | fire 前按规则元数据放行/拦截（灰度/金丝雀） | ✅ Step 14（`/guard/canary` + `guard/ReleaseAgendaFilter.java`） |

---

## 4. 真值维护系统（TMS）

`insertLogical(fact)` 把衍生 fact 跟"导出它的 LHS 匹配"绑定：前提失配时引擎**自动 retract**衍生 fact。普通 `insert` 跟前提解耦，要手动撤销。

- **logical vs regular 对照**（同一组 Sensor 在两个 kbase 各跑，看 Alert 是否被自动撤销） — ✅ Step 12（`/tms/compare`）
- 要 TMS 生效，前提 fact 字段必须可变（modify 才能让 LHS 失配） — ✅ Step 12（Sensor 是 mutable POJO）

---

## 5. 复杂事件处理（CEP）

把 fact 升级成"事件"，引擎理解时间线、滑窗、过期。

| 能力 | 说明 | 本仓库 |
| --- | --- | --- |
| `@role(event)` | 把 POJO 标成事件 | ✅ Step 8（`/fraud/check`） |
| `@timestamp(field)` | 用业务字段当事件时间（而非 insert 顺序） | ✅ Step 8 |
| `over window:time(5m)` | 时间滑窗 | ✅ Step 8 |
| `over window:length(N)` | 长度滑窗（按最近 N 条事件，对比 time 窗按时钟） | ✅ Step 8 扩展（`/fraud/patterns`） |
| `eventProcessingMode="stream"` | 启用事件时间线 / 滑窗 / 过期 | ✅ Step 8 |
| `clockType="pseudo"` | 人工推进时钟（测试可重现） | ✅ Step 8（`SessionPseudoClock`） |
| `@expires` | 事件自动过期 retract | ✅ Step 8（概念） |
| 时序操作符（`after` / `before` / `coincides` 等） | Allen 区间代数 | ✅ Step 8 扩展（`/fraud/patterns` 用 `after[min,max]`，同族 before/coincides 语法一致） |

---

## 6. 会话与状态

| 能力 | 说明 | 本仓库 |
| --- | --- | --- |
| **stateful KieSession** | 长寿命、working memory 持续累积、可 agenda 编排 / CEP | ✅ 大部分 Step |
| **StatelessKieSession** | 一次性 `execute(Iterable)`，线程安全可复用，无跨调用状态 | ✅ Step 11（`/stateless/*`） |
| **Marshaller 持久化** | 把整个 working memory + agenda 序列化成 byte[] | ✅ Step 10（`/loyalty/*`） |
| 持久化 + JPA 落库 | byte[] 存 H2，跨请求/跨重启接续状态 | ✅ Step 10（`persistence/`） |
| 官方 `drools-persistence-jpa` | JTA + 多表 + 自动事务边界（工程更重） | ⬜️（本仓库用简化单表版） |
| `globals` | 给 RHS 注入外部对象（如 logger / service），RHS 主动调（pull 句柄） | ✅ Step 20（`/dispatch/run`） |
| `channels` / exit points | RHS `channels["x"].send(obj)` 往外部推消息（push 出口） | ✅ Step 20 |
| 多 `entry-point` | CEP 多事件流分流（订单流 / 登录流隔离后跨流关联） | ✅ Step 8 扩展（`/fraud/patterns`） |

---

## 7. 决策建模与规则交付

### 7.1 让业务方维护规则

| 能力 | 说明 | 本仓库 |
| --- | --- | --- |
| **决策表**（Excel/CSV） | 表格 → 编译成 DRL，本质还是 DRL/RETE | ✅ Step 7（`/decision/calculate` + `vip-discount.xls`） |
| **DMN + FEEL** | OMG 跨厂商标准，独立求值引擎，自带决策链 | ✅ Step 17（`/dmn/price` + `vip-pricing.dmn`） |
| 规则模板 `.drt` | 模板 + 数据行生成规则 | ✅ Step 23（`/template/discount`） |
| **PMML** | 规则里嵌机器学习模型评分（trusty/efesto，独立求值引擎） | ✅ Step 24（`/pmml/score`，回归模型 `risk-regression`） |
| Scorecard | 评分卡模型（PMML 的一种模型） | ✅ Step 24（`credit-scorecard`，含 reasonCode） |

> **决策表 vs DMN 的区别**（Step 7 vs Step 17）：决策表是"规则的表格写法"，编译成 DRL 跑 RETE；DMN 是独立标准模型，跑 DMNRuntime，跨厂商可移植 + FEEL 表达式 + 决策需求图。详见 `CLAUDE.md` Step 17 笔记。

### 7.2 规则热更新 / 独立发版

| 能力 | 说明 | 本仓库 |
| --- | --- | --- |
| 运行时编译 DRL 字符串 | `KieHelper` 把 DRL → KieBase 缓存（应用内临时态） | ✅ Step 9（`/hot/*`） |
| `KieFileSystem + KieBuilder` | 程序化构建（生产更稳的编译路径） | ✅ `config/DroolsConfig.java` |
| **KJAR** | 规则打成带版本号的 Maven 构件 | ✅ Step 16（`/scanner/*`） |
| **KieScanner** | 轮询 Maven 仓库，发现新版本热替换 KieBase | ✅ Step 16（`scanNow()` + `start(ms)`） |
| `KieContainer.updateToVersion()` | 显式切到新 ReleaseId | ✅ Step 16 扩展（`/scanner/update-version`，固定 release + 手动切） |
| 热替换不打断进行中请求 | 老 KieSession 用老 KieBase 跑完 | ✅ Step 9 / Step 16 |

---

## 8. 可观测性与治理

| 能力 | 说明 | 本仓库 |
| --- | --- | --- |
| `AgendaEventListener` | 监听 match 创建/触发/撤销 + agenda 栈事件 | ✅ Step 6（`/pipeline/audit` + `audit/RuleAuditListener.java`） |
| `RuleRuntimeEventListener` | 监听 working memory fact 增/改/删 | ✅ Step 6 |
| **Micrometer 指标** | listener → counter（规则触发数/fact 操作） + Timer（fire 耗时） | ✅ Step 15（`/metrics/discount` + `/actuator/prometheus`） |
| `KieScannerEventListener` | 监听规则热替换事件 | ✅ Step 16 扩展（`/scanner/events`，攒 STATUS_CHANGE / UPDATE_RESULTS） |
| `Rule.getMetaData()` | 读规则自定义元数据（公共 API，灰度用） | ✅ Step 14 |

> Step 6 是单请求"放大镜"（这一次怎么跑的），Step 15 是跨请求"仪表盘"（整体趋势）。两者同一套 listener 接口，区别只在输出去向。

---

## 9. 部署形态与重型能力（要改架构 / 加部署单元，规划见下）

> 这一档不像加 Step 那样零架构改动——每条要么新增部署单元、要么引第二个引擎、要么碰生产平台。
> **怎么做、改动半径、工作量、风险、建议**都写进了 [`docs/plans/drools-capabilities-tier3-0817-1430/PLAN.md`](plans/drools-capabilities-tier3-0817-1430/PLAN.md)。
> 其中 **PMML/Scorecard 已按 PLAN 路径 A 落地**（Step 24），其余仍是规划态。

| 能力 | 说明 | 状态 |
| --- | --- | --- |
| ~~**PMML / Scorecard**~~ | 规则里嵌 ML 模型 / 评分卡 | ✅ **已完成**（Step 24，走 PLAN 路径 A：自编译进 memoryCL + 同 classloader 建 context） |
| ~~**drools-persistence-jpa**~~ | 官方多表会话持久化（JTA） | ⛔ **本栈卡死**：实体是 `javax.persistence`，跟 SB3/Jakarta 不兼容（见 PLAN §2） |
| ~~**KieServer**~~ | 把规则执行做成独立 REST/SOAP 服务，应用解耦 | ⛔ **本栈卡死**：SB2/javax 版本线（见 PLAN §3） |
| ~~jBPM 集成（+ `ruleflow-group`）~~ | 规则 + 业务流程编排 | ⛔ 同 javax/SB2 边界 + 最大体量（见 PLAN §4） |
| **Rule Units**（Drools 8 incubator） | 把规则 + 数据源打包成强类型单元 | ⛔ **尝试后回退**：与 `drools-traits`(Step 21) 争 `RuntimeComponentFactory` SPI 单例互斥、且解释 provider 在 Spring Boot fat jar 里扫不到自己的 DRL（见 PLAN §5） |
| **多租户隔离** | per-tenant KieContainer/KieBase | ⛔ 反模式（碰生产缓存/快照模型），**不做**（见 PLAN §6） |
| **Business Central / KIE Workbench** | 规则可视化编辑 + 版本治理 Web 平台 | ⛔ 独立 web 平台，超出脚手架范围（见 PLAN §7） |
| **KIE Test Scenarios** | 表格化"输入→期望输出"回归测试（`.scesim`） | ⛔ 耦合 Business Central，价值低（见 PLAN §8） |

---

## 速查：能力 → 本仓库 Step

| Step | 端点 | 核心能力 |
| --- | --- | --- |
| 1 | `/hello` | facts / when-then / 多规则触发 |
| 2 | `/discount/calculate` | salience / 跨事实 join / 规则叠加 |
| 3 | `/cart/checkout` | accumulate 聚合 / modify 级联 |
| 4 | `/risk/evaluate` | not / exists / insert 标记 fact 自终止 |
| 5 | `/pipeline/run` | agenda-group / setFocus / auto-focus / lock-on-active |
| 6 | `/pipeline/audit` | AgendaEventListener / RuleRuntimeEventListener |
| 7 | `/decision/calculate` | Excel 决策表 |
| 8 | `/fraud/check` | CEP：event / 滑窗 / pseudo clock |
| 9 | `/hot/*` | 运行时 DRL 编译热加载 |
| 10 | `/loyalty/*` | Marshaller 持久化 + JPA |
| 11 | `/stateless/*` | StatelessKieSession 对比 |
| 12 | `/tms/compare` | TMS（insertLogical 因果链） |
| 13 | `/backward/contains` | 后向链 + 递归 query |
| 14 | `/guard/*` | 护栏：fireAllRules(max) / halt / AgendaFilter |
| 15 | `/metrics/discount` | Micrometer/Prometheus 指标 |
| 16 | `/scanner/*` | KJAR + KieScanner 独立发版 |
| 17 | `/dmn/price` | DMN + FEEL + 决策链 |
| 18 | `/campaign/*` | 营销活动资格判定（规则即数据 + rehydrate） |
| 19 | `/quantifier/review` | LHS 量词：collect / forall / eval（+ DRL function） |
| 20 | `/dispatch/run` | RHS 对外：globals / channels（exit points） |
| 21 | `/traits/evaluate` | traits：don / 动态多态（贴接口） |
| 22 | `/fireuntilhalt/process` | fireUntilHalt：引擎作为常驻守护线程消费事实 |
| 23 | `/template/discount` | 规则模板 .drt：模板 + 数据行生成 DRL |
| 24 | `/pmml/score` | PMML / Scorecard：trusty 求值引擎跑 ML 模型评分 |

> **扩展端点**（挂在既有 Step 下、复用同一 kbase 或同族 kbase）：
> - Step 8 扩展 `/fraud/patterns` —— 长度滑窗 `window:length` / 时序操作符 `after` / 多 `entry-point`
> - Step 13 扩展 `/backward/derive` —— 前向规则 LHS 用 `?isContainedIn` 拉起后向链证明
> - Step 16 扩展 `/scanner/update-version`（显式 `updateToVersion`）+ `/scanner/events`（`KieScannerEventListener`）

---

## 怎么选能力（决策树）

- **单笔无状态评估**（RPC / 批处理）→ StatelessKieSession（Step 11）
- **需要跨调用累积 / 长寿命会话** → stateful KieSession（+ Step 10 持久化）
- **多阶段流程编排** → agenda-group（Step 5）
- **时间窗 / 实时风控** → CEP stream mode（Step 8）
- **"前提变了结论自动撤销"** → insertLogical / TMS（Step 12）
- **"目标驱动反向证明"**（图可达性 / 推导链）→ 后向链 query（Step 13）
- **"集合/全称/任意布尔"条件** → collect / forall / eval（Step 19）
- **规则要往外部系统推副作用**（发通知 / 记审计） → globals + channels（Step 20）
- **多来源事件流 + 时序关联**（登录流 × 订单流、A 之后 N 秒内 B） → 多 entry-point + `after`（Step 8 扩展）
- **结构固定、只有阈值/参数在变的一批规则** → 规则模板 `.drt`（Step 23）或决策表（Step 7）
- **给 fact 运行时贴/摘"角色"做动态多态** → traits `don`/`shed`（Step 21）
- **引擎当常驻消费者持续吃事实流** → fireUntilHalt（Step 22）
- **业务方维护表格** → 决策表（Step 7）或 DMN（Step 17）
- **嵌训练好的 ML 模型 / 评分卡打分** → PMML（Step 24，跨厂商标准模型 + 独立求值引擎）
- **跨厂商 / 可移植 / 决策链** → DMN（Step 17）
- **规则要独立于代码发版** → KJAR + KieScanner（Step 16）；要版本号治理 + 可回滚 → `updateToVersion`（Step 16 扩展）
- **规则即数据 / LLM 即时生成** → 运行时编译热加载（Step 9）
- **生产上线必备兜底** → 护栏（Step 14）+ 指标（Step 15）
