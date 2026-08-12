# 活动引擎结构性优化方案

> 产出日期：2026-08-12 · 审查范围：`activity-common` / `activity-console` / `activity-decision` 三模块的 `com.lrj.drools.activity.*`
> 方法：六维度并行勘察（求值分派 / 决策编排 / 写平面 / 领域模型 / 取数快照 / 横切关注点）→ 每维度独立对抗验证 → 归并综合
> 原始材料：`AUDIT-FINDINGS.md`（36 条发现的逐条判定，含 12 条被证伪或降级的定性）

---

## 0. 核心论断

**这套代码的问题不是"没用设计模式"，而是"契约只写在注释里，没有落到类型上"。**

它的注释质量在我见过的项目里属于顶格——每一个坑、每一条 fail-closed 取向、每一次"看起来像 bug 但那是线上语义"都写清楚了。但注释不参与编译。于是这套代码的实际结构是：

```
高密度的隐式契约  ×  零编译期约束  =  每一条"必须记得"都是一次未来的静默事故
```

而"静默"是这个领域最贵的失败模式。仓库自己的注释反复在描述同一种事故形状：

> 「不报错、不回退、日志干净，只有对账时才发现」——`ActivityCandidate.scopedSpuIds` 字段注释
> 「漏拷这一行的表现是『快照路径不封顶、DB 路径封顶』——同一张券在两条路上发不同的钱」——`DecisionSnapshot:194`
> 「契约写在注释里、却没有落到数据结构上，是这个 bug 的全部成因」——`BenefitEvaluator.notApplicable:204`

**最后这句是作者自己写的诊断，而这份方案就是把它系统性地执行一遍。**

需要说清楚的另一面。勘察阶段提了 36 条发现，其中 **11 条被定为 P0**；经独立对抗验证后：

| | 数量 |
| --- | --- |
| 维持原定级（CONFIRMED） | 7 |
| 问题成立但定性被夸大 / 落地方式有误（DOWNGRADE） | 29 |
| **验证后仍留在 P0 的** | **0** |

**11 条 P0 全部被推翻**，最常见的三种推翻理由是：作者在注释里已经解释过且理由成立；伤害路径今天实际不可达；提出的抽象会增加而非减少理解成本。

也就是说：**这套代码没有在产的结构性故障，它的问题几乎全部是"未来成本"。** 所以这份方案的排序依据是「这个抽象今天就在阻止一类静默事故复发」，不是「这里可以套一个模式」。被推翻的那些定性记在第 5 节，免得下一个人重走弯路。

---

## 1. 四条根因

### 根因 A：权益形态的知识没有单一分派点，且没有一处是编译期穷尽的

`redPackageAmountUnit` 是"这个数字是什么意思"的判别位，但**判别这件事在 Java 侧做了四遍**，四遍的结构互不相同：

| 位置 | 形式 | 判别轴 |
| --- | --- | --- |
| `BenefitEvaluator.computeAmounts:116-193` | 5 段 `if(form==X){…continue;}` + 无条件兜底 | form + takeType，**靠行序** |
| `ActivityMarketingService.validateBenefitForm:359-420` | `if(form != RATIO_ZHE){ 嵌套 if/else }` 的"非折扣"桶 | form |
| `ActivityMarketingService.validateRangeColumn:488-517` | 按 form 早退链 | form + takeType |
| `BenefitEvaluator.distributionOf:414-426` | 私有宽容判别（`fromCode` 会抛，热路径不敢用） | takeType |

`grep 'switch (form)'` 全仓零命中。而同一个文件里的 `ConditionTreeEvaluator:109` 恰恰用了穷尽 switch 表达式——**范式在仓库里已经存在，只是没用在最该用的地方。**

分派靠行序这件事，作者自己在 `BenefitEvaluator:126-137` 写了三段注释解释「随机必须排在 `redPackageAmount == null` guard 之前」。**一条必须靠注释维持的执行顺序，就是一条随时会被下一次编辑破坏的契约。**

### 根因 B：配置与本轮计算态焊在同一个可变对象上

`ActivityCandidate` 有 19 个配置字段 + 6 个计算态字段（`eligible` / `rejectReason` / `computedAmount` / `amountComputed` / `ladderApplied` / `gifts`）。这一个决定连锁产生了三个结构性后果：

1. **逼出影子类**：快照必须不可变 → 不能复用 `ActivityCandidate` → 造了 19 分量的 `CandidateTemplate`（`DecisionSnapshot:151-157` 的类注释就是在解释这件事）
2. **逼出三份手写字段扇出**：`DecisionDataLoader.flatten:324-346`（17 个 setter）/ `DecisionSnapshotBuilder:117-128`（18 个位置参数）/ `DecisionSnapshot.toCandidate:178-200`（又一遍 setter）。**只有中间那份被编译器守着**
3. **已经漂移过两次**：`scopedSpuIds`（CLAUDE.md 坑 16）与 `redPackageMaxDiscount`（`DecisionSnapshot:194` 那行事故注释）

第 3 条是关键证据：这不是理论风险，**是同一条缝已经裂开两次**。

### 根因 C：分类信息用字符串承载，于是同一个概念长出多份真相

- **淘汰原因两份**：`c.reject("本活动不适用：" + why)` 给人看、`metrics.reject("benefit", reasonCode)` 给告警看，两条独立语句手工配对。`DecisionEligibilityService` 里两处的顺序甚至相反（:110-111 先码后串、:122-125 先串后码且无前缀），`ActivityDrlBuilder:82` 在 DRL 里 emit 了第三份拷贝。**已实证漂移**：`DecisionMetrics` 的 javadoc 写 `price-above-order`，代码实际发 `price-above-base`
- **scene 标签四套词汇**：`spu-discount/gifts` | `addon` | `benefit`（硬编码在求值层）| `RED_PACKAGE/BUY_AND_GET/ADD_ON_PURCHASE`（`decisionSource` 用 `ActivityType.name()`）。`docs/activity-marketing.md:245` 已经自认「按 scene join 两组指标会得到空结果」
- **档位用裸 boolean**：`explain` 穿透 5 个类 13 个方法，而 `ActivityQueryService` 与 `AddOnPurchaseService` 的无参重载**默认值相反**（false vs true）。6 个入口里 4 个靠"默认值恰好对着自己这一侧"才安全
- **错误只有两个桶**：63 处裸抛 `IllegalArgumentException` / `IllegalStateException`（其中 41 处在 `ActivityMarketingService`），controller 按异常类型映射 400/409。后果之一：四眼校验失败返回 **409 而不是 403**

### 根因 D：模块与职责边界靠运行期保证，不靠类型

- `activity-common` 里的 repository 全是 `JpaRepository`，`decrementInventory` 这种 `@Modifying` 与只读方法并排，`DecisionSnapshotBuilder` 注入的正是这个可写接口。「decision 写不了库」今天**只由 MySQL 授权 + 一条 `ddl-auto` 测试**保证，类型上完全写得出来
- `ActivityPoolMatchService` 带 `@Service` + 三处 `bindingRepo.save`，物理上活在只读进程里（唯一调用方在 console）
- `ActivityMarketingService` 977 行 / 15 个构造依赖，同时是配置写入口、代际传播点、发放台账、幂等登记

---

## 2. 改造项

> 每项标注 **effort**（人天量级）与 **安全网**（哪些现有测试守住等价性）。
> 所有项的共同前提：**行为等价**。金标集 `DecisionGoldenSetTest` 52 例、`SnapshotParityTest`、`NotApplicableCandidateTest` 是门禁，不是参考。

### 批次 0 · 纯删除（零行为变更，可当天合入）

先做这批的理由：它们**解开锁死关系**，让后面的批次不必绕着死代码走。

| id | 项 | 依据 |
| --- | --- | --- |
| **R0-1** | 删 `ActivityQueryService:66-76` 两个 `@Value` 死字段（`javaBenefitEval` / `javaEligibilityEval`，带 `@SuppressWarnings("unused")`），同时删 `ActivityQuerySafetyFallbackTest` 里对应的两行 `ReflectionTestUtils.setField` | 该测试真正的断言是 `verifyNoInteractions(runtime)`，与这两个字段被置成什么值无关。**但删字段会让测试炸在 `ReflectionTestUtils` 上而不是断言上**——这是最难察觉的一类固化，必须一起删 |
| **R0-2** | 删 `ActivityRuleResult.benefits` 字段 + `hit()` 里那行 `benefits.add` + `BenefitOutcome` 类型 + 类注释里的「P1-10 前瞻结构」段 | 全仓 grep 零读者（连测试都没有）。留着它让人误以为存在"多权益并存"能力 |
| **R0-3** | 删 `ActivityCandidate.extraConfigType` / `extraDataJson`（无任何装配路径写入）与 `getBenefitForm()`（零调用方，其 javadoc「DRL 的 LHS 用它做判别」已过期——`buildGiftDrl` 的 LHS 只碰 `eligible`/`gifts`） | 同上 |
| **R0-4** | 删 0 调用方的兼容构造器：`GiftView` 四参、`AddOnQuote` 五参 | grep 确认生产与测试都不用 |
| **R0-5** | 订正 `ConditionTreeEvaluator:40` 的 javadoc——它声称「等价性由 `DroolsEligibilityGoldenSetTest` 对拍」，而该文件**不存在**。改写成「本类是唯一生产求值器；`RuleConditionTranslator` 的产物仅用于写入口编译校验，不参与求值」 | `find -name '*EligibilityGoldenSet*'` 零命中 |

**effort**：0.5d｜**安全网**：全量 `./mvnw test`（这批应当零测试变红，除 R0-1 需同步改测试）

---

### 批次 1 · 把分派变成编译期穷尽（零行为变更）

#### R1 · `computeAmounts` 的形态分派改 switch 表达式 〔P1｜根因 A〕

**问题**：5 段 `if(form==X){…continue;}` + `:191-192` 无条件兜底 `setComputedAmount(redPackageAmount)`。加第七种形态而漏了这里，新形态会落到兜底分支被**当成金额原样发出去**——金额是正数、决策成功、日志干净。

**为什么是 switch 表达式而不是策略对象/注册表**：三条理由。① `BenefitForm` 本来就是密封的（枚举），再套一层 `sealed interface` 是纯增量；② 所谓"六形态"里的**随机**（`takeType` 驱动）与**阶梯**（`rangeAmount` 驱动）根本不是 `BenefitForm` 的取值，注册表给不出这两个槽位；③ Java 21 的枚举 switch **表达式**不写 `default` 就强制穷尽——这正是今天缺的那道约束，而它零运行时成本、零新类型。

> ⚠️ 必须是 switch **表达式**。arrow switch **语句**对枚举常量并不强制穷尽，写成语句等于白改。

**两道横切 guard 必须留在 switch 之外并保持原顺序**——这是这一项唯一的雷区：

```java
for (ActivityCandidate c : candidates) {
    if (!c.isEligible() || c.isAmountComputed()) continue;
    BenefitForm form = BenefitForm.of(c.getRedPackageAmountUnit());

    // guard ①：随机型的钱在 range 列里，必须排在 redPackageAmount==null 之前
    if (form == BenefitForm.AMOUNT && DistributionMode.RANDOM_AMOUNT == distributionOf(c)) { … continue; }
    // guard ②：对所有形态生效——没有固定金额时，唯一的金额来源是阶梯
    if (c.getRedPackageAmount() == null) {
        if (!c.isLadderApplied()) notApplicable(c, RejectReason.NO_LADDER_TIER);
        continue;
    }

    Computed r = switch (form) {              // 无 default：加形态漏分支 = 编译失败
        case NTH_ZHE     -> nth(ctx, c);
        case FIXED_PRICE -> fixedPrice(ctx, c);
        case RATIO_ZHE   -> ratio(ctx, c);
        case AMOUNT      -> Computed.of(c.getRedPackageAmount());   // 显式 arm，不是兜底
    };
    apply(c, r);                              // 统一 setComputedAmount / notApplicable
}
private record Computed(BigDecimal amount, RejectReason reason) { … }
```

`AMOUNT` 必须写成**显式 arm**：今天它是"最后一行兜底"，语义上承担了「未知单位回落金额型」这条 fail-safe——但那条 fail-safe 的权威在 `BenefitForm.of()`（有注释、有测试），不该漂到求值器的最后一行。

**effort**：1d｜**安全网**：`DecisionGoldenSetTest` 52 例（Ratio 13 / Ladder 12 覆盖覆盖语义）+ `NotApplicableCandidateTest#legitimateZeroSurvives` + `SnapshotParityTest`

#### R2 · `ConditionTreeEvaluator` 的 ARRAY 兼容块穷尽化 + `RuleLogic` fail-closed 〔P2｜根因 A〕

两处 3 行改动，但守的是**资格结论**：

- `:94-105` 的 ARRAY 兼容块用 `default -> { }` 收尾。新增一个算子时，它会**静默落进下面的标量分支**——对 ARRAY 字段用标量语义求值，直接改变谁能领券。改成穷尽形式（switch 表达式 yield 三态，或显式列全 12 个 case）
- `:60` 直调 `RuleLogic.fromCode`，未知 code 抛 `IllegalArgumentException`，而 `spuDiscountInternal` 一路无 catch → **一条脏 logic 把整次请求打成 500**。包成 fail-closed（当树不可判定，候选淘汰 + `metrics.reject`），与 `ActivityRuleContext:38-45` 给 `numberAttr` 加的护栏同一口径

**effort**：0.5d｜**安全网**：`RuleConditionTranslatorTest` / `RuleSchemaRegistryTest` / `SpuIdConditionCompatTest`

#### R3 · 删 `explain` 无参重载，换 `DecisionMode` 两值枚举 〔P1｜根因 C〕

6 个决策入口里 4 个用默认值，而两个姊妹服务的默认值**方向相反**（`ActivityQueryService` → false，`AddOnPurchaseService` → true）。今天没坏是因为"默认值恰好对着自己这一侧"，读者无法本地推理。

**载重的部分是删掉无参重载、强制每个入口表态**——零行为变化、编译器兜底。枚举只放 `HOT_PATH` / `EXPLAIN` 两个常量；**不要**铺 none/structural/full 三档（无需求依据，且会引入"结构性 trace 含哪些"的新争议）。

> ⚠️ `ActivityDrlBuilder` 的 `explain` **保留为构建期布尔**，不要一起改：它改变生成的 DRL 文本，而 `compileOrGet` 的缓存 key 就是 `tenant + DRL 全文`。把它与运行期档位耦合会让同一份规则被编译两遍。

**effort**：0.5d｜**安全网**：`DecisionOutputContractTest` / `ActivityMarketingLegacyTest` / `e2e:validate`

---

### 批次 2 · 单一真相：原因码与场景

#### R4 · `RejectReason` 枚举 + `DecisionScene` 枚举 〔P1｜根因 C〕

**问题**：淘汰原因今天有三份拷贝（中文串 / reasonCode / DRL 里 emit 的字面量），scene 有四套词汇表。**已实证漂移一处**（`price-above-order` vs `price-above-base`），已实证不可用一处（按 scene join 两组指标得空结果）。

```java
public enum RejectReason {
    NO_LADDER_TIER   ("no-ladder-tier",   "阶梯未落档且无固定金额"),
    BAD_RANDOM_RANGE ("bad-random-range", "随机区间缺失或非法"),
    MISSING_LINES    ("missing-lines",    "第 N 件折缺订单行或 N 非法"),
    PRICE_ABOVE_BASE ("price-above-base", "一口价高于作用域金额或缺订单金额"),
    BAD_RATIO        ("bad-ratio",        "缺订单金额或折数越界"),
    OUT_OF_SCOPE     ("out-of-scope",     "作用域基数不可知（活动只圈了部分商品，但请求未带订单行）"),
    INELIGIBLE       ("ineligible",       "不满足资格条件"),
    CONDITION_UNAVAILABLE("condition-unavailable", "资格条件不可判定");
    // code 进指标标签，message 进 rejectReason 与前端展示——两者从此不可能配错
}
```

**逐字节不变的三条硬要求**：
1. **message 文案一个字不能改**——前端 `ValidateView.test.ts:443/452` 直接断言 `'不满足资格条件'`，`ValidateView.vue` 渲染 `item.rejectReason`
2. **`code` 值一个字不能改**——它们已经是 Prometheus 标签值
3. `baseCodeOr`/`baseUnknownOr` 那对动态配对塌缩成 `OUT_OF_SCOPE` 常量时，文案要对得上今天的动态拼装结果

**`DecisionScene` 顺带解掉一件事**：`BenefitEvaluator:219` 之所以硬编码 `"benefit"`，是因为求值层拿不到 scene。把 scene 作为参数传进 `computeAmounts`/`merge` 即可（这些方法本来就在传 `ctx` 和 `explain`，多一个通道枚举不是新概念）。**不要**为此引入 `DecisionObserver` 接口层次——那是为模式而模式，且 `DecisionMetrics.noop()` 已经把可测试性解决了。

> ⚠️ 把 `decisionSource` 的标签值从 `ActivityType.name()` 改成 `DecisionScene.code()` 会**改变已有 Prometheus 序列**，Grafana 面板与告警必须同批改，并在 `deploy/` 与文档里同步。这是有意的契约变更，不是重构副作用。

**顺带**：让 `ActivityDrlBuilder:82` emit `RejectReason.INELIGIBLE` 常量而不是字面量（header 加一个 import 即可）。该 DRL 在生产只被编译校验、不被执行，改它零风险但能消掉第三份拷贝。

**effort**：1d｜**安全网**：`DecisionMetricsTest` / `DecisionObservabilityTest` / 前端 `ValidateView.test.ts` / `e2e:validate`

#### R5 · 决策属性键常量化 + 键集合钉死测试 〔P2｜根因 C〕

**问题**：`randomSeedSpu` / `orderLines` / `userId` 三个键**不在 `RuleSchemaRegistry` 白名单守卫的覆盖范围内**（白名单只含 6 个可配置字段），而 `DecisionContextFieldsTest` 只断言「白名单 ⊆ 写侧键」。也就是说：**只改写侧的 `randomSeedSpu` 键名，全部测试保持绿，而全量随机红包一次性重抽**——用户刷新页面金额就变、历史对账全部对不上（CLAUDE.md 坑 15 描述的正是这条链）。

两步，零行为变更：
1. 把代码内部硬引用的 4 个键（`userId` / `randomSeedSpu` / `orderLines` / `orderAmount`）提成一处常量类，`DecisionEligibilityService:73-74`、`BenefitEvaluator:449-453`、`ActivityRuleContext:94/104/120`、`ActivityQueryService:402` 全部引用它。**字符串取值一个字节都不能变**
2. `DecisionContextFieldsTest` 补一条用例：断言 `requestAttributes(sample()).keySet()` **恰好等于** 那 9 个键的字面量集合。用例里**写死字面量、不引用常量**——否则常量改名测试跟着改名，照样绿

**不要**引入 `AttrKey<T>` 泛型键：白名单侧本就是租户可配的数据驱动 schema，`T` 除非配套改造三个访问器签名否则纯装饰。

**effort**：0.5d｜**安全网**：`RandomAmountTest` / `DecisionContextFieldsTest`

#### R6 · 加价购埋点补齐 + 买赠 hit 收口 + 审计扩通道 〔P1｜根因 C〕

三通道的横切能力靠复制粘贴对齐，`AddOnPurchaseService` 的构造器里**根本没有 `DecisionMetrics`**，物理上打不了点；`auditLog` 唯一调用点写死 `SCENE_DISCOUNT`，买赠生成了 `decisionId` 却从不落日志，加价购两个 record 连 `decisionId` 分量都没有。

**先做机械修复，别一上来抽基类**：
- 给 `AddOnPurchaseService` 注入 `DecisionMetrics`，用 `timeDecision(SCENE_ADDON, …)` 包住 `options`/`quote`，补 `candidates` / `hit`（quote 命中时）——20 行以内、零语义风险，拿回今天缺的那 80%
- 把买赠的 `metrics.hit` 从两个内部分支收到出口一处。**注意语义**：买赠没有单一赢家，出口要按 `GiftView.gifts` 的来源活动去重计数，不能照抄 discount 的 `hitActivityId` 写法，否则会改变计数口径
- `buyAndGetGifts` 出口加 audit 调用；`AddOnOptions`/`AddOnQuote` 加 `decisionId` 分量并落日志（纯增量，前端对拍表是字段白名单，不会因新字段飘红）
- 审计**保持手工拼单行 JSON**（作者在 :137-138 明确论证过热路径不做序列化框架调用），只把拼装搬进 `DecisionAuditor` 组件、让引号与转义只有一处实现

**不要**抽 `abstract DecisionChannel` + `final decide()`：加价购是两阶段，`quote()` 自己会再调 `options()`，硬塞进模板会产生"一次 quote 触发两层计时"的伪指标。

**effort**：1d｜**安全网**：`DecisionObservabilityTest` / `AddOnPurchaseTest` / `ActivityMarketingAddOnAliasTest`

---

### 批次 3 · 消灭三份手写扇出（本方案的主菜）

#### R7 · `OfferSpec`：把配置从 `ActivityCandidate` 里剥出来 〔P1｜根因 B〕

**这是唯一以原始定级通过对抗验证的大件**，也是唯一一条「同一条缝已经裂开两次」的问题。

```java
/** 活动某版本的不可变权益配置。三条装配路径的唯一目标类型。 */
public record OfferSpec(
        String activityId, String activityName, Integer activityType, String bizLine,
        Integer activityStatus, Integer activityAreaType, String districtIds,
        Integer inventory, Integer userInventory, Integer version, int priority,
        Integer redPackageTakeType, BigDecimal redPackageAmount, String redPackageAmountUnit,
        BigDecimal redPackageMaxDiscount, String redPackageRangeAmount,
        Instant startTime, Instant endTime, List<GiftResult> gifts) {

    static OfferSpec from(ActivityManageEntity m, ActivityRuleEntity r, List<GiftResult> gifts) { … }
}

/** 候选 = 配置（共享不可变） + 本轮计算态（每次决策新建）。 */
public class ActivityCandidate {
    private final OfferSpec spec;
    private Set<Long> scopedSpuIds;      // 逐请求交集，不属于 spec
    private boolean eligible = true; private String rejectReason;
    private BigDecimal computedAmount = ZERO; private boolean amountComputed, ladderApplied;
    // 19 个 getter 委托 spec，字段名与 DRL 访问器保持不变（买赠 evalGift 仍读 gifts/eligible）
}
```

一步到位地解掉四件事：`CandidateTemplate` 影子类消失、三份扇出收敛成一份（`OfferSpec.from`）、快照直接持有 `OfferSpec`（本来就不可变）、走库路径与快照路径**在类型上**只能产出同一个配置对象。

**四条落地约束**（任何一条破了就是改钱）：
1. **`scopedSpuIds` 的 `null` / 空集语义差异必须保留**，且它不进 `OfferSpec`——它是逐请求交集。DB 侧现在给空集、快照侧同理，统一后**不能有任何一条退回 null**，否则 AMOUNT 以外五形态的基数会变
2. **`gifts` 下沉到 spec 后，`ActivityCandidate` 必须保留委托 `getGifts()`**——买赠 DRL 的 LHS 读它，改名/去掉会让规则静默失配
3. **条件行归约统一取走库那份语义**（对整行 `putIfAbsent`，第一行树坏就 fail-closed 淘汰）。**不要**统一成快照那份（`drl` 与 `tree` 各自 `putIfAbsent`，结构上可来自不同行）——后者会让坏数据静默变成"资格通过照常发钱"
4. `OfferSpec` 需额外携带 `startTime`/`endTime`（快照的时间窗过滤用）。走库侧从 `ActivityManageEntity` 取得到，填了不改行为（走库的时间窗判定在上游）

**effort**：3d｜**安全网**：`SnapshotParityTest` 全量（含 `narrowedBindingStopsPayingOnBothPaths`）+ `DecisionGoldenSetTest` 52 例 + `DecisionScopeGoldenTest` + `BenefitScopeTest`

#### R8 · `Materials` 提升为独立值对象，删 `Materialized` 〔P2｜根因 B〕

`Materials` 嵌在 `DecisionDataLoader` 里，于是快照只好另造一个自称"与 Materials 同形"的 `Materialized`；`load()` 手工缝合两者、手算 `minGen`、手拼 provenance。顺带：`trees` 今天按桶全量 `putAll`，而下游只按候选 id 查——只装命中候选即可。

> ⚠️ **不要**用规范构造器强制定序。它会改变所有测试桩的候选顺序，而 `pickByAmount`/`pickByPriority` 打平时是严格 `>`（先到先得），个别断言会翻面。定序保持在 `load` 出口的 `ordered()`。

**顺带做**：把 `resolveStrategy` 里第二次独立的来源判定删掉，改由 `load` 把已解析的 strategy 与 bizLine 一起随 `Materials` 返回（来源判定从此只有一处）；把 `candidates.get(0).getBizLine()` 提成 `Materials.bizLine()` 并**写明「跨业务线时取 activityId 最小者所属业务线」+ 补一个钉住该行为的测试**——这个取值今天没有正确答案，但至少要是有文档、有测试的确定行为。

**effort**：1d｜**安全网**：`DecisionQueryCountTest`（5 次查询上限）+ `SnapshotParityTest`

#### R9 · `RangePayload`：一列三载荷的单一解析出口 〔P2｜根因 A〕

`redPackageRangeAmount` 一列三用途（阶梯数组 / 随机区间 / `{"nth":N}`），同一条约定在 Java 侧实现了三遍。加一个 `RangePayload parse(BenefitForm form, Integer takeType, String json)` 密封返回值，由 `ActivityQueryService.ladderDefs` / `BenefitEvaluator`（nth、random）/ `ActivityMarketingService.validateRangeColumn` 三处共同调用。

> ⚠️ **不要**把解析挪进 `DecisionDataLoader.flatten` 与 `DecisionSnapshot.materialize`。那会把解析失败的发现时机从「每次决策、按候选 fail-closed 并打 `metrics.reject(benefit, bad-random-range)`」挪到「快照后台构建时」——**告警链会断**，同时在 `SnapshotParityTest` 守的等价面上新开一个分歧口（坑 16 的同款失败形状）。

**effort**：1d｜**安全网**：`BenefitFormValidationTest` / `RandomAmountTest` / `NthItemDiscountTest`

---

### 批次 4 · 写平面收敛

#### R10 · `changeStatus` 加迁移表，封死 `PENDING_EFFECT` 〔P1｜根因 C+D〕

今天 `changeStatus` 只做 `fromCode` 不判迁移合法性，副作用（四眼、退役旧线上版）散在两段 `if(target==ONLINE)` 里。`ActivityStatus.PENDING_EFFECT(3)` 是 `fromCode` 认可的合法码，但**全 main 源码只有这一处定义、零生产者零消费者**——一个活动被置成状态 3 之后，代际照常 bump（传播是有的），但它**永远进不了任何读路径**。

```java
private static final Map<ActivityStatus, Set<ActivityStatus>> ALLOWED = Map.of(
    NORMAL,  Set.of(ONLINE, OFFLINE),
    ONLINE,  Set.of(OFFLINE),
    OFFLINE, Set.of());          // 今天 OFFLINE→ONLINE 无条件放行，而编辑 OFFLINE 却被拒——不对称
```

同批：写入口直接拒 `targetStatus=3`（无生产者也无消费者，与其立法不如封口）；`bulk-status` 进循环**之前**先校验一次 `targetStatus`（今天不校验，几十条会各自失败一次）。

> **`create()` 的 `isEdit` 分支保持原样**，最多把版本解析抽成具名私有方法。拆成命令对象 + 模板方法后，读者要跨两个实现类才能拼回「编辑时线上版保留、草稿被顶掉」这条唯一重要的语义——**理解成本上升而非下降**。

**effort**：1d｜**安全网**：`OfflinePropagationTest` / `BulkStatusTest` / `ActivityFourEyesTest`

#### R11 · 版本解析具名化 〔P1｜根因 C〕

「当前是哪一版」在五处各解释一次，且分两套互斥定义：**最高未删除版**（编辑基线 / `changeStatus` 缺省 / `getDetail`）与**最高 ONLINE 版**（`claim` / `DecisionDataLoader`）。`version=null` 是隐式第三义。

把已存在的私有 `currentOnlineVersion` 提升成公开具名出口，另加 `latestDraftVersion()`，让四个调用点各自显式选一个，删掉 `version==null` 的隐式语义。

> ⚠️ **不要**顺手把 `getDetail` 改成返回线上版——`EditorView.vue:421` 依赖它返回草稿（编辑就该编草稿），改了会让编辑器加载到不可编辑的旧配置。要的话是给 detail **加一个显式的 `servingVersion` 字段**，并给 `ListView.vue:297-303` 那条已存在的 `versionMismatch` 提示补一个前端单测（今天它没有测试）。

**effort**：1d｜**安全网**：`ActivityMarketingFlowTest` / `FixedPriceAndClaimTest` / 前端 `ListView.test.ts`

#### R12 · 拆出 `GrantService` 〔P2｜根因 D〕

把 `claimInventory` / `releaseGrant` / `grantsOfOrder` + `ClaimResult` 搬进独立 service——它与 `create`/`changeStatus` **零共享状态**，只共用 `blankToNull` 与 `NOT_DEL` 两个常量。`ActivityMarketingService` 保留同名委派方法（`GrantLedgerTest` 直接调 service 并断言 `.ok()`/`.reason()`，保留签名即可零改测试）。顺手把 `updateByVersion:216` 的自调用改成不经代理的私有实现方法。

> 澄清两条**不成立**的传闻，免得实施时按错误叙事行动：`console-write-authority` 是按 **HTTP 路径**枚举的（`ActivityResourceServerConfig:62-69`），拆 service 根本不碰它；`updateByVersion` 自身也标了同款 `@Transactional`，自调用绕过代理在**行为上零影响**。

**effort**：1d｜**安全网**：`GrantLedgerTest` / `ActivityIdempotencyTest` / `RoleGateConsoleTest`

#### R13 · `ClaimResult` 加 failure kind + 两层 `@MappedSuperclass` 〔P2｜根因 C+D〕

- `ClaimResult` 的七个失败点全靠中文 `reason` 串区分，controller 只能按布尔映射（claim → 409、release → 404）。**保持 record 不换类型**（换 sealed 会让 JSON 变形，且现有测试直接断言 `.reason().contains("每人限领")`），只加一个 `FailureKind` 枚举字段，controller 按 kind 映射 400/404/409。release 的缺参与空串统一 400、真找不到发放记录才 404
- 全仓 66 处重复 `setIsDel/setCreatedStime/setModifiedStime`，`@MappedSuperclass` 零命中。加两层（租户+双时间戳 / 再加 `is_del`，因为 `ActivityGrantEntity` 确实没有 `is_del` 列）

> **不要**造"belongTo 流式装配器让漏填不可表达"：七个实体的这些列全是 `@Column(nullable=false)`，漏填是**响亮失败**（flush 时被打回），数据库已经在做这件事。六段 `save*` 里的显式 setter 保留可读性更好。

**effort**：1d｜**安全网**：`ActivityMarketingEdgeTest` / `GrantLedgerTest` / `FixedPriceAndClaimTest`

---

### 批次 5 · 边界与可靠性

#### R14 · 领域异常 + `@RestControllerAdvice` 〔P1｜根因 C〕

全仓 `@ControllerAdvice` / `@ExceptionHandler` **零命中**；唯一映射是 controller 里手抄三遍的 `try/catch` + `bad()`/`conflict()`。后果：四眼失败是 409 而非 403；`ActivityMarketingService` 用 `msg.contains("uk_am_tenant_request")` 把异常 message 当控制流 key。

```java
public class ActivityException extends RuntimeException {
    private final ActivityErrorCode code;      // ACTIVITY_NOT_FOUND / VERSION_CONFLICT /
                                               // FOUR_EYES_REQUIRED / BENEFIT_FORM_INVALID / …
}
```

**三处必须收紧**（否则修了更糟）：
1. `tryFromCode` 只做**读路径**去异常化（`ConditionTreeEvaluator` / 读侧 `StackStrategy`）。**写平面的 `RuleConditionTranslator` 保持抛**——创建期就该拒
2. **decision 平面不要注册 `IllegalArgumentException → 400` 的兜底**。decision 今天的 IAE 只可能来自脏数据或真 bug，统一 400 会**把 bug 伪装成客户端错误**。decision 侧只注册 `ActivityException` + 一条 500 结构化兜底（带 `code=INTERNAL`，不回显 message）
3. **`DecisionSnapshotBuilder:152` 的 `StackStrategy.fromCode` 跑在后台构建线程上，advice 覆盖不到**，必须 fail-safe 回落 `MAX`（与 `DecisionDataLoader.resolveStrategy` 的 `orElse(MAX)` 对齐）。否则「脏策略行 → 快照建不出来 → 静默走库」，这条比 500 更隐蔽

console 侧现有的 per-endpoint catch **迁移期保留**，避免状态码逐字漂移。

**effort**：2d｜**安全网**：`ActivityMarketingEdgeTest` / `BenefitFormValidationTest` / `DecisionAuthIntegrationTest`

#### R15 · 快照构建期的 N+1 与全表扫描 〔P1｜根因 D〕**（唯一原级 CONFIRMED 的性能项）**

热路径被 `DecisionQueryCountTest` 钉死 5 次查询，**构建期一道门禁都没有**：

- `DecisionSnapshotBuilder:74-79` 用 `findByActivityStatusAndIsDel(ONLINE, 0)` 捞该租户**全部**在线活动，再用 Java `if` 丢掉非本桶的
- `:137-142` 的 `bindingRepo.findByActivityIdAndVersionAndIsDel` **在 `for (m : live.values())` 循环体内**——每活动一次。仓库接口里根本没有 `findByActivityIdInAndIsDel`，N+1 是接口缺口逼出来的
- `GenerationWarmService:116-136` 对每个超龄桶各跑一次完整 build，轮询 3s / 阈值 60s → **每桶约每分钟重跑一遍全套**

这条开销不随请求量增长、只随活动目录规模增长，**压测照不出来**，而且全打在只读账号那条连接上。

第一层（补 `findByActivityIdInAndIsDel`、把 bizLine 下推 SQL）安全且收益直接，先做。第二层 `buildAll(tenant)` 有个**必须钉住的陷阱**：代际号是按 `(tenant, bizLine)` 一行的，poller 只在某条业务线代际前进时 publish 该桶。若 `buildAll` 用触发那条线的 generation 去发布同租户其它桶，会把别的业务线的 `provenance.generation` 写成错的数、并把它们的 `previous` 槽位一起占掉。正确落地：`buildAll` 只用于兜底重建路径（走 `refresh`、每桶沿用自己的 `stale.generation`），发布路径仍只 publish 触发的那一个桶。

**顺带做，收益很高**：分组时数出 `bizLine` 为空的活动并打 warn + 计数器。CLAUDE.md 描述的那条「provenance 三个值全绿、活动就是不在快照里」的故障，今天只能靠诊断端点照出来——**这一行把它提前到构建期**。

**effort**：3d｜**安全网**：`DecisionQueryCountTest` / `GenerationWarmPollerTest` / `SnapshotStaleRebuildTest` / `ActivityCapacityAcceptanceTest`

#### R16 · `SnapshotStore` 槽位原子化 + 接一个真的回滚入口 〔P1｜根因 D〕

`publish` 里 `current.put` 与 `previous.put` 是两条独立语句。更要命的是 `GenerationWarmService:96-100` 捕获 `RuntimeException` 且**不更新 `lastSeen`**——artifactRepo 抖一次，下一轮会对**同一代际**再 publish 一次，`previous` 被覆盖成同代，**rollback 从此是空转**。

改成 `SnapshotSlot(current, previous)` + `ConcurrentHashMap.compute` 单次原子替换，并让 `PUBLISH` 只在**代际前进时**占回滚槽位。同时把 `publish` 挪到预热之后（今天 `:153` 先 publish、`:159` 才查 artifact，中间失败 = 半完成状态被记为一次发布，而 javadoc 声称的顺序恰好相反）。

> **必须一起做的第二件事**：`rollback` 今天**没有任何生产调用方**——decision 侧唯一的 controller 没有回滚端点，全仓只有两个测试在调它。也就是说 CLAUDE.md 里「回滚是 `BenefitEvaluator` 出 bug 的止损手段」这句话**是一张空头支票**。补一个生产可达的入口（只读平面的运维端点或运维命令），否则修好 `previous` 也没人按得下去。

**effort**：1d｜**安全网**：`SnapshotParityTest` / `OfflinePropagationTest` / `GenerationWarmPollerTest`

#### R17 · common 侧只读 Repository + `ActivityPoolMatchService` 上浮 console 〔P2｜根因 D〕

把「decision 写不了库」从运行期保证提前到编译期：

- `ActivityPoolMatchService` 上浮到 console（唯一调用方已在 console，零风险）
- 给 `DecisionDataLoader` / `DecisionSnapshotBuilder` 用的读接口 **`extends Repository<T, ID>`**（不是 `CrudRepository`/`JpaRepository`）——只有这样 `save`/`delete` 才在类型上不存在

> ⚠️ 拆时**别顺手改任何查询方法签名**（`decrementInventory` 的原子 UPDATE 谓词一个字都不能动），并跑 `DecisionTenantHeaderTest` 确认 `@TenantId` 判别式过滤在 `Repository<>` 派生查询上一样生效。

**effort**：1d｜**安全网**：`DecisionDdlGuardTest` / `DecisionTenantHeaderTest` / `TenantIsolationTest` / `TenantArchGuardTest`

#### R18 · `merge` 收敛成单出口 〔P2｜根因 A〕

`merge` 有三个 return，STACK 与单选各自封顶一次。类注释自陈「删掉三参重载就是怕新调用点绕过封顶」，而方法体内部恰好有多个出口。先算出 `(hitId, hitName, amount)` 三元组，再统一写 result、统一 `capToOrderAmount`、统一 return。

> **不要**引入 `MergePolicy` 接口 + 映射表：`StackStrategy` 4 个枚举值只对应 **2 种行为**，为两个分支套策略是为模式而模式。等真出现第三种合并行为时再抽。
> **不要**顺手堵负数：`hitActivityId != null || hitAmount > 0` 这个闸门今天会放行负金额，但改成单事实闸门也堵不住（除非要求 `amount > 0`，而那会误杀阶梯首档 `reward=0` 的**合法** 0 元命中，`NotApplicableCandidateTest#legitimateZeroSurvives` 正是钉这个的）。堵负数要**单独立项、单独对拍**。

**effort**：0.5d｜**安全网**：`DecisionGoldenSetTest` 52 例（Merge 9 例）

---

## 3. 批次与验收

| 批次 | 内容 | effort | 验收标准 |
| --- | --- | --- | --- |
| **0 · 纯删** | R0-1…R0-5 | 0.5d | 全量测试绿；`grep` 确认死结构归零 |
| **1 · 编译期穷尽** | R1 R2 R3 | 2d | **零行为变更**：金标 52 例 + `SnapshotParityTest` + `e2e:validate` 全绿，且这批**不改任何字符串字面量** |
| **2 · 单一真相** | R4 R5 R6 | 2.5d | 指标标签变更已同步 Grafana；前端中文断言零改动；addon 三项指标可见 |
| **3 · 消灭三份扇出** | R7 R8 R9 | 5d | `SnapshotParityTest` 全量绿；`DecisionQueryCountTest` 仍是 5；`OfferSpec.from` 是唯一装配入口（arch 测试钉死） |
| **4 · 写平面** | R10 R11 R12 R13 | 4d | 迁移表拒非法流转；`PENDING_EFFECT` 写入口封死；claim/release 状态码按 kind 分流 |
| **5 · 边界可靠性** | R14 R15 R16 R17 R18 | 7.5d | decision 侧 repository 类型上只读；构建期查询数随活动数**常数级**；回滚入口可从生产调用并有演练记录 |

**合计约 21.5 人天。** 批次 0-2 是纯结构、零行为变更，**建议先合这三批**——它们让金标集在后续批次里当纯粹的安全网用（这批如果金标飘红，一定是重构写错了，不是语义变了）。

**批次间的唯一硬依赖**：R4（`RejectReason`）应在 R1 之前或同批，因为 R1 的 `Computed` record 要装 `RejectReason`。其余批次可并行。

---

## 4. 明确不做的事

| 不做 | 理由 |
| --- | --- |
| 回退/重建任何 DRL 算额路径，或保留"两套求值器对拍" | 硬约束。旧红包算额 DRL 已删，它不认一口价/第 N 件折/随机红包，翻回去按错误形态发钱 |
| 承诺"前端与后端共享形态权威" | 跨语言做不到。`logic.ts` 3 条三元链 + `EditorView.vue` 约 10 条校验分支是 TS，Java 密封类型换不掉它们。只能靠共享 fixture / 契约测试对齐——**别把它写进收益里** |
| `ConditionNode` 拆 DTO + 密封解析模型 + 转换层 | 今天节点形态只有两种、两个消费者的分派各只有 3 行，引入三层是净增理解成本。等出现第三种节点形态（NOT 组 / 命名片段）再上 |
| 算子 `eval` + `emit` 统一成注册表 | 会把一条**明确定性为非生产求值器**的 DRL 翻译路径升格成与求值器对等的第二权威，正是仓库反复警告的方向。翻译产物只用于写入口编译校验，从不参与求值 |
| `MergePolicy` 策略接口 / `BenefitStrategy.usesScope()` 声明 | 前者 4 枚举值只对应 2 行为；后者没有任何消费者，除了"实现接口时必须回答"之外不产生约束。都是为模式而模式 |
| `create()` 拆命令对象 + 模板方法 | 拆完要跨两个实现类才能拼回「编辑时线上版保留、草稿被顶掉」这条语义 |
| "belongTo" 流式实体装配器 | 数据库 `NOT NULL` 已经在强制这件事，再加一层只是把同样的强制搬得更远 |
| `ClaimResult` 换 sealed 变体层次 | 变体字段集不同 → Jackson 输出必然变形，与"响应体一字节不变"自相矛盾；现有测试直接调 `.ok()`/`.reason()` |
| 反射断言"计算态字段清单 ⊆ reset 实现" | 它守的那条路径（`safeFallback` 的 reset 循环）今天在两条调用路径上都是**可证明的空操作**（`eligible` 恒空，循环第一行全跳过）。等于给一段无效代码加一个脆的元测试 |
| 审计日志换 Jackson 序列化 | 作者已论证：单行拼接是刻意的热路径取舍。只把拼装收进一个组件、让引号与转义有单一实现即可 |
| 给 `duration` timer 增加 fallback 标签 | 回退**已经**可观测（`DecisionMetrics.fallback(scene, reason)` 带 reason）。加标签会让现有 Prometheus 序列分裂、面板查询失配 |
| `AttrKey<T>` 泛型属性键 | 白名单侧本就是数据驱动 schema；不配套改三个访问器签名的话，`T` 纯装饰 |
| 引入 `DecisionObserver` 观察者层次 | 真正成立的那一半（求值层拿不到 scene）用 R4 的 `DecisionScene` 参数就解掉了 |

---

## 5. 附：审查过程中被证伪的定性

记下来，免得下一个人重新走一遍弯路：

- **「`safeFallback` 漏清计算态字段会发错钱」** — 不成立。该 reset 循环在今天两条调用路径上都是空操作：`engine-disabled` 路径在算额之前就进入，候选全是初值；`empty-decision` 路径只在 `eligible` 集合为空时可达，而循环第一行 `if (!c.isEligible()) continue` 恰好全跳过。`a0ec639` 那 2 行是纯防御性补丁，不是踩过的坑
- **「审计日志转义不全会产出非法 JSON」** — 当前不可触发。reject 原因全是代码里的固定中文字面量，`userId` 是 Long
- **「六段 `save*` 漏填 `isDel` 会静默入库」** — 不成立。七个实体这些列全是 `@Column(nullable=false)`，漏填是响亮失败
- **「`updateByVersion` 自调用让 `create` 的 `@Transactional` 失效」** — 行为上零影响，它自己标了同款注解
- **「加价购快照回退在监控上完全不存在」** — `decisionSource` 在 loader 层无条件打点，三通道都有
- **「tenant 名含 `|` 会跨租户串桶」** — `TenantIds` 的 grammar 是 `^[A-Za-z0-9_-]{1,64}$`，进不来
- **「两个线程并发 publish 同一桶」** — 不可达，唯一调用方挂在单线程 `@Scheduled` 上（但**同一代际重复 publish 覆盖 `previous`** 是真的，见 R16）
- **「`getDetail` 取最高版是 bug」** — 半错。`EditorView` 要的恰恰是草稿；它只是同一个出口服务了两种意图
