# 活动引擎全栈重构计划

> **2026-08-08 已拍板**：本计划 §8 的待定项已裁决完毕，逐条见 [DECISION_RECORD.md](DECISION_RECORD.md) 的 **D12**。
> 结论摘要——**走 S 档**（玩法只有金额型）· **性能线与权益线并行**（先做共享前置 B0 与接缝拆分）· **库存选 B**（置灰并明示）·
> **`storeId` 加入参不删白名单** · 金额口径 `scale=2, HALF_UP`。

> 五路并行勘察（玩法建模 / 后端权益模型 / 前端控制台 / 契约数据流 / 迁移风险）+ 三视角评审（业务覆盖 / 工程可行 / 前后端一致）的综合定稿。
> 基线 commit `2c1f49d`。所有「已核实」标记的事实均由主会话逐文件读码确认，可复核。

---

## 0. 先说裁决：三份评审互相打架，我站哪边

这是本计划最重要的一节。三个评审视角给出了方向相反的结论：

| 评审 | 核心主张 | 建议规模 |
|---|---|---|
| 工程可行 | 需求基线不存在，整套抽象是为不存在的需求造的 | 砍到 **25–35 人日** |
| 业务覆盖 | 抽象是漏的，第N件M折/秒杀/加价购/裂变一个都配不出来 | **加**行项、行选择、一口价、配额、周期窗、优惠层 |
| 前后端一致 | 五路对权益契约给出了 4 个名字、3 种主键、3 张表名，**后端下发的字典渲染不出前端任何一个表单** | 编码前必须先冻结契约 |

**裁决：工程可行赢在「做多少」，业务覆盖赢在「诚实」，前后端一致是前置条件。**

理由如下，每条都可验证：

1. **需求基线确实不存在。** 玩法建模那一路在自己的 assumptions 里写着「21 个玩法来自行业通行做法，未从代码或文档中找到任何玩法需求清单」。仓库里也确实没有。**为想象中的需求造 14 个正交原子，是这次最贵的错误。**

2. **抽象的回本点很远。** 把 `COUPONS(2)` 按现在的硬编码方式再加一遍，量级是 60–100 行、约 1 人日。一套 PlayTemplate + IR + 编译器要回本，需要 15 个以上新玩法。**在拿到玩法清单之前，这笔账算不出来。**

3. **但业务覆盖指出的不是「能力不够」，是「承诺与实现不符」。** `inventory` / `userInventory` 有字段、有写入、决策零读取（已核实）。运营在控制台配了「每人限领 1 次」「秒杀总量 500」，线上会**无限超发**，且界面不会告诉他。这不是范围问题，是诚实性问题，**必须处理**。

4. **行项模型是入口契约的分叉点，现在决定便宜、以后决定昂贵。** 但「决定」不等于「实现」——可以决定本轮不做单价类玩法，然后**立刻从设计里删掉相关原子**，而不是留一句「以后再说」。留着才是负债。

### 由此确定的计划形状

```
闸门 G ── 成本实测 + 玩法清单（1–2 人日，先于一切编码）
   │
   ├─ 无条件先做：B0 护栏批（不依赖闸门结果，任何一档都要做）
   │
   └─ 闸门后二选一
        S 档：BenefitSpec 最小版 + 前端 schema 表单        25–35 人日
        M 档：S 档 + 行项 + 行选择 + 一口价                 另计，需重新估
```

---

## 1. 闸门 G：先量成本，再决定是否上抽象

**这是整个计划里投入产出比最高的 1–2 人日。** 在写第一行重构代码之前完成。

### G1 · 拿玩法清单（业务输入，我给不出）

向业务要「未来 6 个月确定上线」的玩法清单，每个玩法附它需要的配置字段。**不要行业通行清单，要这个业务真的要上的。**

### G2 · 真实成本实测

用**当前的硬编码方式**把 `COUPONS(2)` 实现一遍并计时：改了几个文件、多少行、几人日。这个数字是后面所有决策的分母。

### G3 · 判据（写死，不许事后解释）

| 清单情况 | 走哪档 | 理由 |
|---|---|---|
| ≤5 个玩法，且都是「金额型 + 固定/阶梯」变体 | **S 档** | 抽象回不了本。BenefitSpec 最小版即可，PlayTemplate / IR / 编译器整套不实施 |
| >5 个，或含单价类（第N件M折、秒杀一口价、加价购） | **M 档** | 必须先加行项模型，否则这些玩法从入口就不可表达 |
| 清单要不到 | **S 档** | 要不到清单本身就是答案：没有明确需求时不造抽象 |

> **诚实标注**：G2 的 1 人日是评审的粗估，不是实测值。G3 的「15 个玩法回本」同理。实测数字出来后请覆盖它们。

---

## 2. B0 护栏批：无条件先做（不等闸门）

这批不依赖闸门结果，走哪档都要做，且每一项都能独立上线、独立回滚。**上限 3 人日。**

### B0-1 · 三条现网 bug（各自独立 commit，先于所有重构合入主干）

**① `storeId` 是一条死条件** — 已核实

`RuleSchemaRegistry` 的默认白名单有 6 个字段，其中 `storeId` 会出现在前端条件下拉里。但：

- `SpuDiscountRequest` 里**根本没有 storeId 字段**
- `ActivityQueryService.buildContext()` 从不写 `storeId`
- `numberAttr("storeId")` 返回 null → 正向比较为 false → `not ActivityRuleContext(...)` 命中 → **候选被淘汰**

结果：**任何配了 storeId 条件的活动永远不会命中**，且因为是 fail-closed，表现为「静默不发」而不是报错。反向也漏：`userId` 被写进属性袋却不在白名单里，两张表在两个方向上都不同步。

修法：`buildContext` 改成**按 `schemaRegistry.resolve(tenant, bizLine)` 遍历白名单填充**，而不是手写六行 `putAttr`。这顺带解掉「新增条件字段还要改 Java」。
加断言：白名单里每个 key 都必须有写入路径，缺一个就测试失败。

> 待澄清：`storeId` 该从哪来？`SpuDiscountRequest` 没有这个入参。要么加字段，要么把它从白名单里删掉——**不能维持现状**。

**② `activity-decision` 的 `ddl-auto` 仍是 `update`** — 已核实

`application.yml:15` 值是 `update`，而同一行上方的注释写着「M2.2 起：decision 连只读账号 + 改 validate」。只有 compose 的环境变量盖住了它。按 CLAUDE.md 里文档化的本地命令 `./mvnw -pl activity-decision spring-boot:run` 起，只读平面是带着 DDL 权限跑的。

修法：改 `validate`，并在 `TenantArchGuardTest` 加一条断言（decision 模块有效 `ddl-auto` 必须是 `validate`）。
**不要**把这条和「是否引入 Flyway」捆绑——那是独立决定，5 分钟能修的事不该等。

**③ `DynRowTable` 用 `:key="i"`** — 已核实

`DynRowTable.vue:31` 是 `v-for="(row, i) in rows" :key="i"`。而 `logic.ts:6` 写着：

> 稳定节点 id：递归组件 `:key` 必须用它，禁 index 作 key（删中间行会串值——30 号决策已实证）

同一个教训在条件树学过并写进了注释，但阶梯档 / 赠品 / SPU 绑定三张行表还在犯。删中间一行，剩余行的输入值会串位。

修法：行对象注入内部 `_rid`（复用 `logic.ts` 的 `nodeId()`），提交前剥离。补一条 Vitest：三行删中间行，剩余两行字段值不变。
**这条必须排在任何动态表单改造之前**——schema 驱动后行表会更多。

### B0-2 · 金标测试集（重构的验证真空）

已核实：**31 个测试类，端到端金额断言只有 7 条**（legacy 取最大 70 / 租户隔离 60 / 阶梯达标 80 / 不达标 50 / 编辑后 60 / 阶梯档 5 / 阶梯档 12）。其余 amount 相关断言都在测翻译出的字符串，不测钱。

**在这个覆盖率下做权益模型重构，等于闭着眼改钱。**

金标集：40 例，`4 策略 × 候选数 0/1/N × 阶梯四个边界`（低于首档 / 恰等 min / 区间内 / 恰等 max / 高于末档）。用 `compareTo` 不用 `equals`（BigDecimal `50` 与 `50.00` 不等，这是最容易漏的一类回归）。

### B0-3 · 三个决策指标

对应已定论的 D7：`activity.decision.duration` / `activity.decision.fallback{reason}` / `activity.decision.candidates`。

回退率必须配告警——它会**改变实际发给用户的金额**，现在只打一条 `log.warn`。

### B0 明确不含

Flyway、影子双跑框架、万级差分测试、OpenAPI 生成链。这些各有各的理由（见 §6 not-now）。

---

## 3. 契约冻结（编码前置，两档通用）

前后端一致那份评审的判断是对的：**后端设计的字典渲染不出前端设计的任何一个表单**。这不是措辞差异，是能力缺失。

编码前产出 `CONTRACT-FREEZE.md`，逐字定死三层：

### 层 1 · 后端权益类型元数据 `BenefitTypeSpec`

```java
// 内置注册表，本轮不落库、不做运维端点（见 §6）
record BenefitTypeSpec(
    String benefitType,        // RED_PACKAGE_CASH / BUY_AND_GET_GIFT
    String label,
    List<ParamField> params,   // ← 就是下发给前端的那个形状，不另造一套
    BenefitKind kind           // 本轮闭集：FIXED | LADDER
) {}
```

### 层 2 · 下发字典 `ParamField`（与条件侧的 `DictField` 同构）

**必须含 `control` / `visibleWhen` / `rowSchema` 三个字段**——缺一个，前端就换不掉 `v-if="dr.activityType===1"` 和阶梯 `DynRowTable`：

```jsonc
{
  "key": "amount",
  "label": "红包金额",
  "control": "MONEY",              // MONEY | PERCENT | SEGMENTED | ROWS | TEXT | SELECT
  "valueType": "NUMBER",
  "required": true,
  "visibleWhen": { "field": "mode", "eq": "FIXED" },   // ← 替掉 dr.redMode 的 v-if
  "rowSchema": null,                                    // ROWS 控件的列定义
  "testid": "form-amount"          // ← 保住 e2e-tablet/phone smoke 不红
}
```

`control` 是**渲染指令**，`valueType` 是**校验类型**——两者不能合并。这正是条件侧 `ConditionLeaf.vue` 已经跑通的模式，照抄即可。

### 层 3 · 提交体

```jsonc
{ "benefitType": "RED_PACKAGE_CASH", "params": { "mode": "FIXED", "amount": 20 } }
```

废弃勘察里出现过的其它三种形状（`activityType + variant + payload` 三元组、`templateKey`、9 字段 `BenefitSpec`）。

落库表名统一 `activity_benefit`，主键 `(tenant_id, activity_id, version, benefit_id)`。

### 必须补的那一步：投影函数

五路无人认领 **`BenefitTypeSpec` → `ParamField[]` 的投影**。不写它，同一份结构就要手工维护两遍，第一次改档位就漂移。
这是一个纯函数，要有单测，位置在 `activity-common`。

---

## 4. S 档：最小版（闸门判定为 S 时执行）

### 4.1 后端

`BenefitKind` 闭集只留 **两支**：

```java
sealed interface BenefitValue permits Fixed, Ladder {}
record Fixed(BigDecimal amount) implements BenefitValue {}
record Ladder(String driverField, List<Tier> tiers) implements BenefitValue {}
```

**明确砍掉**：`Table`（多维查表）、`Expression`（表达式 + AST 白名单 + 指令集缓存）、`RandomRange`。
前两个没有任何现存或计划中的玩法需要；`Expression` 单独就要引一个新运行时依赖加一个注入面。
`sealed` 的好处正是以后加分支时编译期报错——**不需要提前占位**。

`RandomRange` 单独说：`DistributionMode.RANDOM_AMOUNT` 枚举存在但链路不存在，且 `redPackageRangeAmount` 字段已被阶梯语义占用。**先跑一条 SQL 统计 `take_type=2` 的存量行数**：为 0 就把枚举删掉；非 0 则逐行人工裁决，**绝不自动转换**（自动转换会把今天实际发 0 元的行改成发随机金额）。

### 4.2 前端（与后端合并为一批，一次改到位）

- `EditorView.vue` 536 行拆分 + `BenefitForm` 按 `ParamField[]` 动态渲染
- 新 UI 原语**只允许一个**：`DataTable`（`ListView` 已有手写实现要复用，也是保住 tablet/phone smoke 无横向溢出断言的关键）
- **删掉**：`useDraftAutosave`（localStorage 草稿 + 恢复 Banner + 冲突三态）、`draftDiff` 子系统。dirty 判定用 `JSON.stringify(draft) !== JSON.stringify(baseline)` 三行解决

### 4.3 迁移

新表 + 双写 + 一个 backfill runner（dry-run / apply 两态）。

**不做**六阶段治理（LegacyBenefitAdapter + ParityChecker + legacy/shadow/primary 三档开关 + 双写窗口 + 停写窗口 + 删列窗口）——那套是给「存量千万行、不可停机、有外部调用方」设计的。这里存量是 demo 数据，且活动版本行不可变（编辑=新版本），backfill 天然是逐行纯函数。

---

## 5. 诚实性红线：库存与频次二选一（无论哪档）

已核实：`inventory` / `userInventory` 读进 `ActivityCandidate` 后**全仓零读取**，`userInventory` 创建时恒为 0。

**不允许维持现状。** 二选一——**已拍板：选 B**（见 DECISION_RECORD D12-3）。

**A · 真做**
新增 `activity_quota` 表 + 预占接口（RESERVE_THEN_CONFIRM，TTL 15 分钟）。
注意与架构不变量的冲突：**decision 连只读账号，不能扣减**。所以预占写通道必须由 console 提供，决策命中后由调用方回调。这条冲突五路无人给出解法，是本计划新补的。

**B · 不做但说清楚**
控制台的库存 / 每人限领输入框**置灰** + 悬浮提示「本期为声明式，决策不扣减」；创建响应回 `warnings`；活动详情页顶部挂 warn Banner。

> 我的建议是 **B**。理由：A 需要引入预占状态机 + TTL 回收 + 对账，量级接近整个 S 档；而 B 只要半天，且立刻消除「配了以为生效」这个真实的超发风险。等真有秒杀需求时再做 A。

---

## 6. 明确不做（not-now + 重启条件）

推迟不等于否决。每条给重启条件，到点复审。

| 推迟项 | 重启条件 |
|---|---|
| **A/B 实验整块**（3 个页面 + BucketRuler + RampControl + ExposureChart + VariantCompareTable + experimentApi） | 决策曝光与命中埋点上线且有 ≥2 周数据 |
| **发布状态机 UI**（PublishDialog / VersionTimeline / VersionDiff / RollbackDialog） | 后端 D10（编辑即下线）与 D11（发布非原子）修完。现在这五个组件建在假设的双轨版本语义上 |
| **决策沙盘全套**（7 个组件） | 后端返回结构化 `explain`。特别提示：勘察里那个「从现有 traces 按中文前缀正则解析阶段」的降级路径是**负资产**——日志文案一改图就错，且错得静默 |
| **schema 运维子系统**（`schema_revision` 审计表 + impact 影响面预演 + 5–7 个运维端点 + 前端 schema 编辑界面） | 有真实的多租户字段定制需求。现在 `RuleSchemaRegistry.register` 主源码零调用、进程内 Map、无持久化——**它今天没有调用方，也就没有受害者** |
| **API 卫生整包**（ProblemDetail + 20 错误码 + Idempotency-Key header + 分页 envelope + 4 个 authority + oasdiff 门禁） | 拆成独立批次，与权益模型解耦。现有 `requestId in body` 已工作且有测试覆盖，换 header 是纯翻新 |
| **OpenAPI 生成链** | `ActivityMarketingController` 的 `ResponseEntity<?>` + 手拼 `Map.of` 先收敛成具名 record。不然生成物全是 `unknown`。本轮只落 `check-contract.mjs` 运行时哨兵 |
| **表达式引擎 / 多维查表** | 出现真实需求。见 §4.1 |
| **`benefit_expression`**（把权益计算写成表达式存 DB） | **建议永不做**。运营配的一个字符串直接决定发多少钱，无编译期检查、无单测覆盖、出错只能在生产发现。接受「新计算原语 = 一次性 Java 改动」这个边界 |

---

## 7. 与已有架构结论的衔接（修正我之前的排序）

上一份评估报告把 P1-1 快照包、P1-2 阶梯移出规则、P1-3 QLExpress 后端并列排在同一阶段。**这个排序要改：**

> **代际快照包优先于分层引擎，两者都在权益模型之后，且拆成独立路线单独排期。**

理由：
- **快照包不改计算语义**，只改「昂贵动作在哪个线程发生」。它解 D1 / D3 / D8 / D9 / D11，风险可控。
- **分层引擎改计算语义**（Drools → 表达式引擎 / Java 查表），风险最高、收益最不确定，且它的验证依赖金标集——而金标集是 B0 才建的。

另外，影子双跑的形态要降级：**取消生产形态**（`@Primary` 装饰器 + 线程池 + 采样 + 在线比对），改成 **JUnit 级金标对拍**——读 B0 的 40 例 fixture，新旧两条链路各跑一遍比 `hitAmount`（`compareTo`）、`hitActivityId`、`strategy`、`gifts`（排序后比）。零生产风险、零 DB 翻倍。等真有生产流量再评估在线影子。

---

## 8. 待拍板项（**已于 2026-08-08 全部裁决**，保留原文以便追溯）

> 逐条裁决与依据见 [DECISION_RECORD.md](DECISION_RECORD.md) 的 D12。下面是拍板前的原始问题清单。

1. **玩法清单**（闸门 G1）——决定走 S 还是 M 档。
2. **`storeId` 从哪来**——加入参，还是从白名单删掉。
3. **库存红线选 A 还是 B**（§5）。我建议 B。
4. **行项模型 items[]**——本轮做还是不做。选不做就**立刻删掉** Selector / Allocation / UNIT_PRICE 基数三个原子，不留「以后再说」。
5. **异构权益同时命中的默认口径**——今天 `spuDiscount` 只捞红包、`buyAndGetGifts` 只捞买赠，两个接口物理隔离。合并后必须给默认值：全局单选（行为不变）还是按类型分桶全给？**这两个默认值差一个数量级的发放金额**，不拍板不许编码。
6. **金额 scale 与舍入口径**——全局默认（建议 `scale=2, HALF_UP`），且必须锁进金标集的期望值，否则金标集自己就锁不住。
7. **legacy 决策端点还有谁在调**——仓库里没有任何东西能回答。不答这个问题，所有兼容期与下线排期都是拍脑袋。

---

## 9. 工作量与诚实标注

| 范围 | 量级 |
|---|---|
| 闸门 G | 1–2 人日 |
| B0 护栏批 | ≤3 人日 |
| S 档（BenefitSpec 最小版 + 前端一批 + 迁移） | 25–35 人日 |
| M 档 | 需在闸门后重估，不给数 |
| 五路原始提案全量实施 | 150–220 人日 |

**这些都是评审的粗估，不是实测。** 闸门 G2 的实测数字出来后请覆盖。

其它诚实标注：

- 五路合计提出 300+ 文件变更、40+ 个新 Java 类、5 张新表。而平台后端 main 只有 6096 行、测试 3145 行——**新增代码量是被改造系统的数倍**。这个比例本身就是警报。
- 「21 个玩法」「14 个原子」来自行业通行做法，**不是这个业务的需求**。本计划已按此打折。
- 本计划未执行任何压测，未跑任何迁移演练。
- 库存红线 A 方案与「decision 只读账号」的冲突是本计划新补的，五路原始提案里无人处理。
