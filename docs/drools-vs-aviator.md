# Drools vs Aviator

> 活文档。最后核对：2026-08-28；本项目对照基于 Drools 8.44.2.Final，教学能力已扩展到 Step 1–24。
>
> 这俩名字里都带"规则/表达式"的味道，容易混，但其实是**两个量级完全不同的东西**。
> 本文给出选型对照，配合 `docs/drools-capabilities.md`（Drools 能力全景）一起看。
> 注：Aviator 指 [killme2008/aviator](https://github.com/killme2008/aviator) 这个轻量级 Java 表达式求值引擎。

## 一句话区分

- **Aviator** = 一个**表达式求值引擎**。给它一个字符串 `"a > 18 && b.vip == true"` 和一个变量 Map，它算出 `true/false`（或任意值）。仅此而已。
- **Drools** = 一个**规则引擎 / 产生式系统**。管理一堆 `when-then` 规则 + 一个工作内存（facts），用 RETE/Phreak 算法做**模式匹配**，自动决定哪些规则该触发、按什么顺序触发、规则之间如何级联。

## 核心差异对照

| 维度 | Aviator | Drools |
| --- | --- | --- |
| 定位 | 表达式求值（expression eval） | 规则引擎（rule engine / production system） |
| 输入 | 一个表达式字符串 + 变量绑定 | 一组规则 (DRL) + 工作内存里的 facts |
| 核心算法 | 编译成字节码直接求值，**无状态** | RETE/Phreak 增量模式匹配，**有工作内存** |
| "多条规则"如何协作 | 没有这个概念，你自己在 Java 里逐条算 | 引擎自动 agenda 调度、salience 优先级、规则互相激活 |
| 跨事实 join | 没有，自己拼 | LHS 里天然支持多 fact join（本项目 Step 2） |
| 聚合 / 否定 / 存在 | 没有 | `accumulate` / `not` / `exists`（本项目 Step 3、4） |
| 状态累积 | 无状态 | KieSession 可长寿命累积、持久化（本项目 Step 10） |
| CEP / 时间窗 | 无 | `over window:time(5m)` 等（本项目 Step 8） |
| 真值维护 (TMS) | 无 | `insertLogical` 自动撤销衍生结论（本项目 Step 12） |
| 量级 | 极轻（一个 jar，几百 KB） | 重（一票 kie-* 模块） |
| 学习曲线 | 几分钟 | 陡（本项目用 24 个 Step 才铺完主要能力） |

## 反直觉的点：Aviator 反而占优的维度

上面那张表容易给人"Drools 全面更强"的错觉。但下面这些维度,**Aviator 反而赢**——这恰恰是选型时最容易漏掉的:

| 维度 | 为什么 Aviator 占优 |
| --- | --- |
| **安全 / 沙箱** | Aviator 有 sandbox 模式,能禁函数、限制可调用的 Java 方法,防表达式注入,适合"规则来自用户输入/不可信来源"。Drools 的 RHS 是**直接编译成 Java**(本项目 CLAUDE.md 坑 6),能写任意代码,规则源不可信时安全边界很危险。 |
| **动态性** | 改规则这件事 Aviator 天生赢:表达式就是字符串,即改即用、零编译成本。Drools 改规则要重新**编译 KieBase**(贵),所以本项目要花 Step 9(热加载)、Step 16(KJAR) 两步专门补"动态改规则"的工程化。 |
| **单表达式计算表达力** | 纯计算上 Aviator 不输:自定义函数、运算符重载、BigDecimal/BigInteger、seq 集合库、正则。Drools 的强项**不在单表达式**,而在规则编排——别误以为 Drools 能力 ⊇ Aviator。 |
| **冷启动 / 部署足迹 / 嵌入性** | Aviator 几乎无传递依赖,能塞进 Android / 边缘 / Serverless;Drools 一票 kie 模块 + 运行时需要编译器在场,KieBase 编译让**启动慢**。 |
| **测试成本** | Aviator 单测就是"输入→断言输出";Drools 要起 KieSession、造 facts、fire、断言 working memory 状态,重得多。 |

## Drools 的暗面（对比里通常只列优点）

| 风险 | 说明 |
| --- | --- |
| **副作用 + 触发顺序 + 死循环** | Aviator 是纯求值、无副作用、确定性强。Drools 规则有 `insert`/`modify`/`retract` 副作用,触发顺序受 salience/agenda 影响("规则顺序变,结果变"),还有死循环风险——本项目 CLAUDE.md 坑 3 用一大段讲 `update()` 死循环,这正是 Aviator 根本不存在的问题类别。 |
| **性能反直觉** | RETE 的优势是"**N 条规则 × M 个 fact**"时增量匹配 + 自动 join,规模越大越值。但**规则少、fact 少时**,Drools 的 working memory/agenda 开销反而让它比 Aviator 一次字节码求值慢。不是"Drools 总是更快"。 |

## 治理维度（纯技术之外）

- **谁来维护规则**：Drools 有决策表(Step 7)、DMN(Step 17)让**非程序员**用 Excel 维护;Aviator 语法仍偏程序员。
- **可观测 / 可审计**：这条 Drools 赢——Step 6 的触发轨迹、Step 15 的 Micrometer 指标能回答"哪条规则触发了、几次";Aviator 是黑盒求值,没有"规则"粒度可追踪。

## 怎么选

- **只是想把"判断逻辑"从代码里抽出来变成可配置字符串** → Aviator（或 QLExpress、MVEL、SpEL）。比如风控里"单条规则"`amount > 1000 && region in ['A','B']`，业务方改字符串就行。
- **规则之间有依赖、要级联触发、要聚合/否定/累积状态、要引擎帮你调度** → Drools。也就是当你的需求超出"算一个布尔表达式"、开始变成"一坨规则互相影响的系统"时。

## 和本项目的关系

本项目的规则能力实验室覆盖了 Aviator **做不到**的那些能力——salience 调度、`accumulate` 聚合、TMS 真值维护、agenda-group 流水线、CEP 滑窗。

- 如果整个需求只是"算几个独立的布尔/数值表达式"，Drools 是杀鸡用牛刀，Aviator 更合适。
- 一旦规则开始互相激活、需要工作内存，Aviator 就撑不住了，得上 Drools。

实践中常见的组合：**用 Aviator 当 Drools 规则 LHS/RHS 里的"表达式插槽"**，或者轻量场景用 Aviator 顶上、复杂场景才引 Drools。

> 同类对照：表达式引擎这一档里 Aviator / QLExpress / MVEL / SpEL 彼此可换；规则引擎这一档 Drools 之外还有 Easy Rules（更轻）、Camunda DMN（只做决策表/DMN）。Aviator ↔ Drools 不是同档竞品，是"表达式 ↔ 系统"的层级差。
