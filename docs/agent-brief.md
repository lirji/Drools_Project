# Agent Brief — 派子代理时让它读这份，不要让它读整份 CLAUDE.md

> 活文档。最后核对：2026-08-28。当前架构、部署和接口事实以本文链接的各领域活文档为准。
>
> **这份文件存在的唯一理由**：`CLAUDE.md` 约 16k tokens，主会话加载一次很划算，
> 但工作流里每个子代理都读一遍就是 N × 16k（上一轮 29 个 agent 的重构，这一项占了约 10% 的 token）。
> 这里只留**任何改动都适用、且没有测试能替你抓住**的铁律 —— 约 1/8 的体量。
>
> **需要某个领域的细节时，让 agent 读那一份**，而不是回头读 CLAUDE.md：
> 架构与不变量 → `docs/architecture.md` · 活动引擎用法与 REST → `docs/activity-marketing.md`
> · 教学 Step 与 DRL 语义 → `docs/steps-guide.md` · 部署与观测 → `docs/deployment.md`
> · 本轮重构的契约变更 → `docs/plans/activity-design-refactor-0812-1232/BREAKING-CHANGES.md`
>
> 维护约定：**只往这里加「测试抓不住」的规则**。一条规则一旦有了具名守卫测试，就从这里删掉、
> 在 `CLAUDE.md` 的坑列表里留一行指向那个测试即可 —— 否则这份文件会长回 CLAUDE.md 的体量。

---

## 一、这是一个**发钱**的系统，失败模式是「静默」

改动的默认判据不是「跑通了吗」，而是「它会不会不报错地发错钱」。
本仓库反复出现的事故形状是同一种：**不报错、不回退、日志干净，只有对账时才发现**。

- **行为等价是默认要求。** 金标集 `DecisionGoldenSetTest`（52 例）、`SnapshotParityTest`、
  `DecisionQueryCountTest`（热路径 5 次查询上限）是发布门禁，不是参考。
- **绝不"顺手修好"看起来像 bug 的既有语义。** 三个最容易被误修的：
  1. **固定金额会覆盖阶梯算出的结果**（阶梯故意不设 `amountComputed`）——那是当前线上语义，金标用例靠它成立；
  2. **出口闸门 `hitActivityId != null || hitAmount > 0` 会放行负金额**——改成要求 `amount > 0` 会误杀
     阶梯首档 `reward=0` 的**合法** 0 元命中（`NotApplicableCandidateTest#legitimateZeroSurvives` 钉着它）；
  3. **「算不出金额」必须是「本活动不适用」，不是「减 0 元」**——0 元会以 0 参与 MAX 竞争并挤掉真能减钱的活动。
- 要改上述任何一条，**单独立项、单独对拍**，不要混进别的改动里。

## 二、不要重建已经被删掉的东西

- **旧红包算额 DRL 已从代码里删除**（`buildDiscountDrl` / `evalDiscount` / `evalLadder` / `evalEligibility`）。
  仓库里**找不到**第二份算额权威——**别去找，也别为了让某句文档成立而重新造一条**。
  删它的原因是它不认一口价 / 第 N 件折 / 随机红包，翻回去会按错误形态发钱。
- **`java-benefit-eval` / `java-eligibility-eval` 两个属性字段也已删除**：此前是「绑定但从不读取」，
  现在是**根本不绑定**。别按「配个开关就能切回另一套求值器」设计任何预案——那个能力不存在。
- `BenefitMath` 是**唯一**的数学/取整层（`RoundingMode.DOWN`，向下取整是因为这是往外发的钱，
  四舍五入会系统性多发）。取整逻辑只能有一份。

## 三、一个字节都不能改的东西

- **随机红包的种子链**：`randomSeedSpu` / `canonical()` / `randomSeedKey()` / 指纹拼装。
  它进 SHA-256，**改一个字节 = 全量随机红包一次性重抽**：用户刷新页面金额就变、历史对账全部对不上。
- **指标标签的取值**（它们已经是线上时间序列）、**面向用户的中文文案**（前端测试直接断言中文串）、
  **生成的 DRL 文本**（它是 `compileOrGet` 的缓存 key）、**响应 JSON 的字段名**。
  确需变更时，那是一次**有意的契约变更**，要单独提交并写进 BREAKING-CHANGES。

## 四、边界

- **`activity-decision` 连只读数据库账号，物理上写不了库**，且不依赖 `drools-lab`。
  取数层注入的是六个 `*ReadRepository extends Repository<T,ID>`（不是 `JpaRepository`），
  `save`/`delete` 在**类型上**不存在——别把它们换回 `JpaRepository`。
  唯一的例外是 `POST /decision/v1/snapshot/rollback`：它不写库，但**切指针会立刻改变实际发出去的钱**。
- **console 是唯一的 DDL 执行者**；decision 的 `ddl-auto` 必须是 `validate`。
- **`ActivityCandidate.scopedSpuIds` 的 `null` 与空集语义不同**：`null` = 作用域未知（按整单算，
  是给手工构造候选的兼容承诺），空集 / 非空集 = 作用域已知。两条装配路径都必须填，混了就会
  「同一张券在走库与走快照两条路上发不同的钱」。

## 五、构建与测试（不照做会浪费你大量时间）

- **改了 `activity-common` 之后跑下游模块，必须加 `-am` 或先
  `./mvnw -pl activity-common install -DskipTests`。** 否则 Maven 用 `~/.m2` 里的**旧 jar**，
  表现极具迷惑性：common 的单测绿、console 的集成测试红。
- **测试必须串行跑，绝不后台并行。** `FixedPriceAndClaimTest` 吃文件版 H2
  （`activity-console/data/`），并行会整片报 `Database may be already in use` / `Unable to determine Dialect`
  —— 那是**环境冲突不是代码回归**，串行重跑即可。
- **别用「求和 surefire XML」数用例数**，会少数 52 个（`DroolsBenefitGoldenSetTest` 继承
  `DecisionGoldenSetTest`，父类的 `@Nested` 用例被发现两遍并覆盖同名 XML）。
  **以 `./mvnw test` 输出的 `Tests run:` 汇总为准。**
- 快速反馈：`./mvnw -pl <module> test -am -Dtest=<TestClass>`；前端 `cd frontend && npx vitest run`。
- ⚠️ **e2e 不在 `./mvnw test` 与 `vitest` 的闸门里。** 上一轮重构改了四眼的状态码，
  `e2e-validation.mjs` 里那条断言一直是红的却没人知道——**闸门覆盖不到的契约，改了不会有人告诉你**。
  动了对外状态码 / 端点前缀 / 响应契约，要主动去 `frontend/e2e/*.mjs` 里搜一遍。

## 六、写代码时

- **优先复用既有组件与约定**，不要为了模式而模式。本仓库明确拒绝过的抽象：给只有一个实现的接口、
  给 4 个枚举值只对应 2 种行为的策略接口、给三行逻辑套工厂、把数据库 `NOT NULL` 已经在做的事
  再包一层装配器。
- **缺失需求绝不臆造**——标为「假设」或「待澄清」，写进返回值交给调用方。
- 顺路发现的问题**写进返回值，不要动手**——扩大范围会让 diff 无法评审。
