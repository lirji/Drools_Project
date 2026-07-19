# 活动引擎平台 · 通用化 + 多租户 SaaS 架构增量

> 文档定位：在 `10-architecture-gap-and-options.md`（现状/差距/方案 B）与 `activity-engine-backend-0718-2111/FINAL_PLAN.md`（不可变 artifact + release manifest + CAS pointer + stateful 护栏，**单租户、电商优先**）之上，攻两个硬骨头：
> **① 元数据驱动全自助接入（schema→通用 fact/DRL）**；**② 多租户 SaaS（隔离/配额/计费）**。
> 用第二业务线「出行·司机激励」验证通用性。**只做架构分析与选型，不改任何 Java/JS/pom/配置代码。**
>
> 标注约定：**【已具备】**= 现有代码已实现，直接复用；**【需改造】**= 现有类有雏形，演进它；**【需新建】**= 现在完全没有。
> 承重原则：release/manifest/pointer/outbox/audit/护栏这套机器**业务线无关、租户无关**，全部复用；只把「电商专属的字段/绑定/权益」抽成**数据驱动的 schema + 插件**。
>
> ---
> **⚠ 独立评审吸收注(2026-07-18,读本文前必看,详见 `41-REVIEW-FINDINGS-generalization.md` + 后端 §17.8)**：
> 1. **本文 §5.1/§6.3 的鉴权部分(X-Api-Key + TenantContext 过滤器 + `activity_api_client`)已作废**——鉴权/多租户身份统一接入既有 auth-platform(Casdoor OIDC + SpiceDB),见后端 §17.7 与 `12-auth-platform-integration.md`。信封 `tenantId` 只作校验(须=token.owner),绝不作租户来源。
> 2. **命门选型 (a) Map 支撑通用 fact:正确性 ✅ + 性能 ✅ 已实证(2026-07-18/19)**。正确性:14/14 编译+执行,方法左值 in/contains 全绿。性能:PERF-3 吓人版本证伪——typed 与 map 都线性 O(M),map 只多付 ~15%(200 档极端)、真实规模可忽略,成本驱动是规则数非 fact 表示法。**不因性能退 declared-type。** 见后端 §17.8 P0-1 / `41-REVIEW-FINDINGS`。
> 3. **§17.4「取不到键 null 优雅失败」对否定运算符是错的**(fail-OPEN 静默超发),已在后端 §17.4 修正。
> 4. **跨异构 benefit 合并(§本文称已解)实则悬空**,MVP 明确不支持(单场景单 benefit-type)。
> 5. **DATE 别提前进类型矩阵**(MVP 只加 ENUM);逃生舱 (a)→(c) 迁移"翻译器输出不变"是错的。
> 所有引用的类名/方法/字段均来自真实代码（本文附代码坐标）。严格尊重 `CLAUDE.md` 已踩的坑。

---

## 0. 一页速览（TL;DR）

- **schema 模型**：新增「每租户每业务线」的**上下文 schema 注册表**（`activity_context_schema` + `activity_context_field` + 可选 `activity_field_option`）+ **benefit-type 注册表**（`activity_benefit_type`：配置 schema + DRL 模板引用 + 结果形状）。现有 `RuleField` 6 字段枚举退化为「电商默认 schema 的种子数据」，白名单从**编译期枚举**变成**运行期数据**，但**校验路径、fail-closed、运营永不写 DRL** 三条铁律不变。电商与出行两套 schema 实例证明同一模型能表达二者（见 §1.4）。
- **硬骨头 #1（命门）**：通用 fact 推荐 **(a) Map 支撑 + 强类型访问器方法的通用 `RuleContext`**（`numberAttr("completedTrips") >= 10`），**不做** (b) 按 schema codegen typed 类（classloader 泄漏 + 运行时 javac + 安全面太大），declared-type (c) 作为「未来需要 DATE 区间/多上下文索引」的逃生舱。关键论据：**上下文 fact 是每请求单例**（`not RuleContext(...)` 与 ladder 的 `$ctx` 都只匹配这一个实例），alpha/beta 索引对单例几乎无意义，Map 方案损失的「索引」代价被单例抵消；且 **RHS 从不读上下文字段**（ladder 的 reward 是编译期常量），`CLAUDE.md` 坑 6（RHS 无 record accessor 糖）根本不触发。
- **硬骨头 #2**：多租户推荐 **行级隔离（`tenant_id` + 共享库）** 作为 SaaS 主线（many-tenant、复用全套 CAS/outbox 机器、`release_key` 从 `bizLine` 升为 `(tenant_id, bizLine)`），为少数大客/合规客保留 **schema-per-tenant 逃生舱**；db-per-tenant 仅企业专属版。内存两处是 OOM 命门：`DecisionSnapshotRegistry` 按租户分区 + LRU + 常驻上限；KieBase 编译缓存 key 带 `tenant`，Caffeine 权重淘汰 + 单飞重编译 + 发布预热。
- **ladder 泛化**：把 ladder 模板里**硬编码的 `orderAmount` 参数化为 `<ladderField>`**（校验过的 schema 字段 key）即可同时服务 mall(over orderAmount) 与 ride(over completedTrips)，**同一 ladder 机器，只换阈值字段**。
- **benefit 泛化**：折扣/买赠/现金奖励作为**注册插件**（各自 config schema + DRL 模板片段 + 结果形状）；`ActivityRuleResult` 的 typed `hitAmount/gifts` 泛化为 `List<BenefitOutcome>`（type + 结构化 payload）。
- **无状态取向**：平台**无状态 over caller-provided aggregated context**（司机端预聚合 `completedTrips` 传入），**不引** Step 8 CEP 窗口累积到决策热路径——多租户 + 水平扩展下，CEP 的 stream mode/pseudo clock/驻留事件/跨实例时钟是巨大复杂度与 OOM 面，收益不匹配。
- **决策 API**：固定的 `spuIdList/orderAmount` 换成**通用 context 信封**（schema 校验过的任意键值）+ `tenantId/bizLine`；`field-dict` 变 `?tenant=&bizLine=` 数据驱动。

---

## 1. 元数据 / schema 模型（核心）

### 1.1 现状：硬编码在哪、为什么挡路

| 硬编码点 | 代码坐标 | 挡住通用化的地方 |
| --- | --- | --- |
| 6 字段白名单枚举 | `domain/RuleField.java`（`ORDER_AMOUNT/QUANTITY/USER_DISTRICT/USER_TAGS/SPU_ID/STORE_ID`） | 字段集写死在**编译期枚举**，新业务线加 `driverLevel/completedTrips` 要改 Java 重新发版 → 不是「自助接入」 |
| 值类型仅 3 种 | `domain/FieldValueType.java`（`NUMBER/STRING/ARRAY`） | 出行的 `driverLevel/timeSlot/vehicleType` 是**枚举**、缺 `ENUM/BOOLEAN/DATE`，前端也无「候选值」来源 |
| 固定电商 fact | `domain/ActivityRuleContext.java`（typed `orderAmount/spuId/userDistrictId/...` getter） | fact 的字段是**编译期固定的 bean 属性**，`RuleConditionTranslator` 直接把 `field.factField()` 当访问器 token 拼进 DRL（`(orderAmount == 5)`）；换字段=改 POJO |
| 翻译器白名单源 | `engine/RuleConditionTranslator.java:57` `RuleField.fromKey(...)`、`:65` `field.factField()` | 白名单**来源是枚举**；把字段/运算符/值形状校验都绑死在 `RuleField` |
| ladder 字段写死 | `engine/ActivityDrlBuilder.java:165` `ActivityRuleContext( orderAmount != null, orderAmount >= ... )` | ladder over **orderAmount** 硬编码，出行要 over `completedTrips` 无从表达 |
| 权益形状写死 | `domain/ActivityCandidate.java`（`redPackageAmount/gifts`）+ `ActivityRuleResult.java`（`hitAmount/gifts`） | 权益只有「折扣金额 + 赠品」，出行的「现金奖励」无结果形状 |

**关键洞察（决定整套方案的复杂度）**：真正 schema 驱动的只有**上下文 fact（`ActivityRuleContext`）** 一个——它的字段来自业务线的可配置字段集。而 **`ActivityCandidate`（候选，RHS 可变）** 和 **`ActivityRuleResult`（输出）** 是**平台自有的、结构稳定的**类型，不随租户/业务线变（权益的「品类」变化用 benefit 插件 + 泛化的 `BenefitOutcome` 承接，而不是让 fact 类本身可变）。这把「通用 fact」问题从「三个 fact 都要动态化」收敛到「只有上下文 fact 要动态化」，是硬骨头 #1 能选轻量方案的根本原因（见 §2）。

### 1.2 目标：两张注册表

#### (A) 上下文 schema 注册表 —— 「每租户每业务线」的字段字典

替代 `RuleField` 枚举，把「哪些字段可用、什么类型、允许哪些运算符、可选候选值、是否是选择器字段」变成**数据**。

```
activity_context_schema  (schema 头，一个 tenant×bizLine 一行有效版本)
   ├─ activity_context_field   (每字段：key/label/valueType/allowedOps/selector/必填…)
   │     └─ activity_field_option  (可选：枚举/下拉的候选值，供前端可搜索多选)
   └─ schema_version / state (DRAFT/APPROVED)  —— schema 本身也走审核 + 版本
```

**`activity_context_schema`**

| 列 | 说明 |
| --- | --- |
| `schema_id` PK | |
| `tenant_id` | 租户（行级隔离主键之一） |
| `biz_line` | 业务线（mall / ride …） |
| `schema_version` | schema 版本；`(tenant_id, biz_line, schema_version)` unique |
| `state` | `DRAFT/PENDING_REVIEW/APPROVED/RETIRED`（复用现有 workflow 状态机，schema 变更 = 一次可审核发布） |
| `created_by/at` | 审计 |

**`activity_context_field`**

| 列 | 说明 | 对应现 `RuleField` 属性 |
| --- | --- | --- |
| `field_id` PK | | |
| `schema_id` FK | | |
| `field_key` | 条件树/信封里的 key，正则强校验 `^[a-zA-Z][a-zA-Z0-9_]{0,63}$`（**防 DRL 注入**，见 §7） | `key()` |
| `label` | 前端展示中文名 | `label()` |
| `value_type` | `NUMBER/STRING/ARRAY/ENUM/BOOLEAN/DATE`（扩展 `FieldValueType`） | `valueType()` |
| `allowed_ops` | 允许运算符集合（JSON 数组，元素为 `RuleOperator.code`） | `allowedOps()` |
| `is_selector` | 是否作为「候选活动预筛选键」（mall=spuId、ride=cityId，见 §5.3） | 无（新语义） |
| `required/default` | 信封校验用 | 无 |

> **注意**：现 `RuleField.factField()`（落进 fact 的访问器名）在通用模型里**消失**——通用 fact 用 `field_key` 统一寻址（`numberAttr("<field_key>")`），不再需要「字段 key → bean 属性名」的映射层。这简化了一层。

**`activity_field_option`**（可选，补齐 §16 前端补遗第 3 条「候选值下拉」）

| 列 | 说明 |
| --- | --- |
| `field_id` FK / `option_value` / `label` / `sort` | 枚举/下拉候选（driverLevel: GOLD/SILVER…；timeSlot: PEAK/FLAT…） |

#### (B) benefit-type 注册表 —— 「权益品类」插件

替代「折扣金额 + 赠品」写死，把每类权益的**配置 schema + DRL 模板 + 结果形状**注册成插件。

**`activity_benefit_type`**

| 列 | 说明 |
| --- | --- |
| `benefit_type_id` PK | |
| `tenant_id`（nullable） | null = 平台内置全局类型；非 null = 租户专属（少见） |
| `benefit_code` | `DISCOUNT / GIFT / CASH_REWARD`（可扩 `COUPON/POINTS`） |
| `config_schema_json` | 该类型的运营配置字段定义（折扣：金额/单位/阶梯 JSON；现金奖励：金额/币种；买赠：赠品批次） |
| `drl_template_ref` | **平台内置 DRL 模板的标识**（不是 DRL 全文，也不是租户可填）——决定 RHS 怎么把配置落到 `BenefitOutcome`（见 §4.2） |
| `result_shape_json` | 结果 payload 形状（供决策 API 响应 + 前端渲染契约） |
| `state` | `DRAFT/APPROVED`（平台运维维护，租户只选用不新增模板） |

> **铁律不破**：`drl_template_ref` 指向的模板由**平台工程/运维**注册（等价于把现有 `ActivityDrlBuilder.buildDiscountDrl/buildGiftDrl` 拆成「模板注册表」），**租户/运营只是选一个 benefit type + 填 config schema 定义好的值，永远不写 DRL、不改模板**。这就是 FINAL_PLAN §13「运营无任何裸 DRL 入口」在多租户/多品类下的延续。

### 1.3 与现有 `RuleField` / `RuleConditionTranslator` 的演进关系

```
【硬编码枚举】                              【数据驱动 + 白名单不变】
RuleField (enum, 6 值)          ──►   ContextSchemaRegistry【需新建】
  .fromKey(key)                          .resolve(tenantId, bizLine)  → SchemaView（缓存，见 §3.3）
  .allows(op)                            SchemaView.field(key).allows(op)
  .factField()                           SchemaView.field(key).valueType()  （不再需要 factField）

RuleConditionTranslator【需改造】       RuleConditionTranslator【改造后】
  translateLeaf: RuleField.fromKey  ──►  translateLeaf: schemaView.field(key)  ← 白名单源换成 schema
  emit "(orderAmount == 5)"         ──►  emit "(numberAttr(\"completedTrips\") >= 10)"  ← 访问器换成类型化方法
  MAX_DEPTH=5（保留）                     + MAX_NODES / MAX_LIST_LEN（新增硬上限，见 §7）
```

三条铁律**逐条保留**：
1. **仍白名单**：字段必须命中该 (tenant,bizLine) 的 `activity_context_field`；运算符必须在该字段 `allowed_ops` 内；`ENUM` 值必须命中 `activity_field_option`。命不中 → `IllegalArgumentException`（现 `RuleConditionTranslator:58/62` 的行为，源从枚举换成 schema）。
2. **仍 fail-closed**：翻译产出仍是 `not RuleContext(<约束>) → $c.reject(...)`（现 `ActivityDrlBuilder:55`），上下文不满足即淘汰候选。运行期 schema 加载失败 / 字段解析失败 → 拒绝激活（发布期）或降级 `NO_PROMOTION`（决策期），不放行未校验规则。
3. **运营仍永不写 DRL**：运营提交的仍只是 `ConditionNode` 树（现 `domain/ConditionNode.java`），翻译器仍是唯一的 DRL 生成者；字段 key 经正则白名单后才拼进访问器参数（§7 防注入）。

### 1.4 两套 schema 实例（证明同一模型能表达二者）

#### 电商（mall）context schema（`activity_context_field` 行）

| field_key | value_type | allowed_ops | is_selector | 说明 |
| --- | --- | --- | --- | --- |
| `orderAmount` | NUMBER | gt/ge/lt/le/eq/between | no | 订单金额（**也是 mall ladder 的阈值字段**） |
| `quantity` | NUMBER | gt/ge/lt/le/eq/between | no | 数量 |
| `spuId` | NUMBER | eq/in | **yes** | 商品 SPU（**候选预筛选键**，对应现 `activity_spu_binding`） |
| `storeId` | NUMBER | eq/in | no | 店铺 |
| `userDistrictId` | STRING | eq/in/notIn | no | 地域 |
| `userTags` | ARRAY | contains/notContains/containsAny | no | 用户标签 |

benefit types：`DISCOUNT`（红包/折扣，scene: eligibility/discount 合并 MAX/MUTEX/STACK/PRIORITY/ladder over orderAmount）、`GIFT`（买赠）。

#### 出行（ride，司机侧）context schema

| field_key | value_type | allowed_ops | is_selector | 说明 |
| --- | --- | --- | --- | --- |
| `driverId` | NUMBER | eq | no | 司机 id（进日志/trace，不进指标 tag） |
| `cityId` | STRING | eq/in | **yes** | 城市（**候选预筛选键**，替代 spuId 的角色） |
| `driverLevel` | ENUM | eq/in | no | 司机等级（候选值 GOLD/SILVER/BRONZE，走 `activity_field_option`） |
| `completedTrips` | NUMBER | gt/ge/lt/le/between | no | 窗口累计完单数（**司机端预聚合传入；也是 ride ladder 的阈值字段**） |
| `onlineHours` | NUMBER | gt/ge/lt/le/between | no | 在线时长 |
| `timeSlot` | ENUM | eq/in | no | 时段（PEAK/FLAT/VALLEY） |
| `vehicleType` | ENUM | eq/in | no | 车型（COMFORT/ECONOMY…） |

benefit types：`CASH_REWARD`（现金奖励）；活动：多单奖励（资格 `driverLevel in [...] && cityId == "010"` + 单阈值 `completedTrips >= 10`）、阶梯奖励（**ladder over completedTrips**——同一 ladder 机器，阈值字段从 orderAmount 换成 completedTrips）。

> **同一模型表达二者的证据**：两套都只是 `activity_context_field` 的不同行 + benefit_type 的不同选用 + ladder 的 `is_selector`/阈值字段不同。**没有一行 Java 因业务线不同而分叉**。ride 的「无状态 over 预聚合 completedTrips」正是把窗口累积推给调用方，平台仍是 `completedTrips >= 阈值` 的纯函数（§4.3）。

---

## 2. 硬骨头 #1：schema → 通用 fact + DRL 生成（命门）

### 2.1 问题精确定义

现在链路（`RuleConditionTranslator` → `ActivityDrlBuilder` → `ActivityRuleRuntimeService`）产出的 DRL 直接引用**编译期固定的 bean 属性**：`ActivityRuleContext( orderAmount >= 5 )`、`ActivityCandidate` 是 typed POJO。要 schema 驱动，就得让 fact 的字段**运行期可变**，而 Drools 8.44.2 下要同时满足：property reactivity 语义正确、alpha/beta 索引不塌、编译/缓存可控、不踩 `CLAUDE.md` 坑 6（RHS 无 record accessor）、可测、维护面小。

**边界收窄（承 §1.1 洞察）**：只有**上下文 fact** 需要动态字段；`ActivityCandidate`（RHS 可变的那个）与 `ActivityRuleResult` 保持 typed POJO 不动。所以下面三个选型只针对**上下文 fact**。

### 2.2 三个选型对比（Drools 8.44.2 落地视角）

#### 选型 (a)：Map 支撑 + 强类型访问器方法的通用 `RuleContext`【推荐】

```java
// 【需新建】domain/RuleContext.java（取代 ActivityRuleContext 的电商专属字段）
public class RuleContext {
    private String tenantId, bizLine;
    private RuleScene scene;
    private final Map<String, Object> attrs = new HashMap<>();  // 归一化后的值
    private List<ActivityCandidate> candidates = new ArrayList<>();

    // 类型化访问器：给 Drools 一个"具体返回类型"，避免 Object 比较的强转歧义
    public BigDecimal numberAttr(String k) { return (BigDecimal) attrs.get(k); }
    public String     textAttr(String k)   { return (String) attrs.get(k); }
    public List<?>    listAttr(String k)    { return (List<?>) attrs.get(k); }
    public Boolean    boolAttr(String k)    { return (Boolean) attrs.get(k); }
    // enum 以 textAttr 承接（值域由 activity_field_option 保证）
}
```

翻译器产出（`RuleConditionTranslator` 改造）：`RuleContext( numberAttr("completedTrips") >= 10 )`、`RuleContext( textAttr("cityId") == "010" )`、`RuleContext( listAttr("userTags") contains "vip" )`。

**Drools 8.44.2 评估**（已用 context7 官方文档核实）：
- **访问器合法性**：Drools LHS 约束是 Java/MVEL 表达式，支持方法调用与 `[]` 取值。官方文档：约束里「若找不到 getter，编译器回退用同名方法」，且 `credentialMap["jdoe"].valid` 这类 Map 取值合法。故 `numberAttr("completedTrips") >= 10` **可编译可执行**。选类型化方法而非裸 `attrs["k"]`，是为了让表达式返回**具体类型**（BigDecimal/String），比较/强转/`in`/`contains` 语义清晰，规避 Object 比较的坑。
- **property reactivity**：`RuleContext` **全程只读**（决策期 insert 一次、不 `modify`），property reactivity 对它**无关**——无回归。可变的是 `ActivityCandidate`（typed POJO，property reactivity 照旧）。
- **alpha/beta 索引与性能**：方法调用 `numberAttr(k)` **不会**被 Drools 当作可哈希/可范围索引的字段提取器（不是 bean 属性），所以对上下文的 alpha 约束不走索引。**但上下文 fact 是每请求单例**（`not RuleContext(...)` 存在性检查、ladder 的 `$ctx : RuleContext(...)` 都只匹配这一个实例），单例上「有没有索引」几乎不影响性能。真正需要索引的候选比较（`ActivityCandidate(computedAmount > $c.computedAmount)`，现 `ActivityDrlBuilder:102`）仍在 typed POJO 上，索引不变。**这是 (a) 可行的核心论据**。
- **编译/缓存**：DRL 文本仍由模板生成、按 artifact 版本预编译进 KieBase（沿用 FINAL_PLAN §5.3）。cache key 不再是 DRL 全文，改 `tenant:bizLine:artifactId:scene:templateVersion:schemaVersion`（§3.3）。无运行期 javac、无新 classloader。
- **`CLAUDE.md` 坑 6（RHS 无 record accessor）**：**不触发**。RHS 从不读上下文动态字段——ladder RHS 是 `$c.setComputedAmount(new BigDecimal("<常量>"))`（reward 是编译期常量，现 `ActivityDrlBuilder:169`），eligibility RHS 是 `$c.reject(...)`。即便未来某 benefit 要在 RHS 读上下文（如现金奖励 = f(onlineHours)），`RuleContext` 是**普通 POJO**，`$ctx.numberAttr("onlineHours")` 是真实方法，RHS 直接编成 Java 也能调（坑 6 只咬 record 的 `getX()`，POJO 方法无恙）。
- **可测性**：`RuleContext` 是纯 POJO + Map，单测直接 `attrs.put(...)`；翻译器单测断言输出串——比 codegen/declared-type 好测。
- **维护面**：最小。加值类型只动 `FieldValueType` + 访问器；加字段是数据。

**代价（诚实）**：Object 装箱 + 强转开销（可忽略）；类型一致性**必须在归一化时保证**（NUMBER→BigDecimal、ENUM→String、ARRAY→List），否则 `numberAttr` 强转 ClassCastException——用 schema 的 `value_type` 在信封校验期归一化（§5）兜住。上下文若未来变多实例（不在当前需求）则要重估索引。

#### 选型 (b)：按 schema codegen typed fact 类【不推荐】

每个 (tenant,bizLine,schemaVersion) 生成一个 typed Java 类（`RideContext { BigDecimal completedTrips; String cityId; ... }`）随 DRL 一起编译。

- **引擎保真度最好**：字段是真 bean 属性，alpha/beta 索引、property reactivity、`RideContext(completedTrips >= 10)` 全原生。
- **但代价压过收益**：① 运行期 **javac/janino** 编译 Java 源；② 每 schema 版本一个**新 class + classloader**，多租户多 schema 下 **classloader/Metaspace 泄漏**风险（FINAL_PLAN §14 已忧 KieBase 编译预热，class 生成更重）；③ 生成 Java 源是**新的注入面**（字段名/类型来自租户配置，需极严白名单）；④ 与 artifact 打包/校验/回滚耦合更深。鉴于**上下文是单例、索引收益近乎为零**，为「用不到的索引」付「运行期 codegen + classloader 生命周期」的账，违背 FINAL_PLAN「每个抽象自证复杂度」。**否决。**

#### 选型 (c)：DRL `declare` 类型 + FactType API【逃生舱，暂不上】

在 artifact 的 DRL 里 `declare RuleContext completedTrips : java.math.BigDecimal ... end`，Drools 在 KieBase build 时**自己生成** typed fact 类（无需你跑 javac），运行期用 `kieBase.getFactType(...).newInstance()` + `factType.set(inst, "completedTrips", v)` 造实例插入。

- **优点**：typed 字段 → 正确强转/索引/`@propertyReactive`（官方文档确认 `declare ... @propertyReactive` 支持），DRL 单一真源，无外部 javac，per-artifact KieBase（正好契合现模型）。
- **缺点**：① 造上下文实例要走 **FactType 反射式 API**（`set(name,val)`），比 POJO+Map 啰嗦；② declared 类型**身份属于某个 KieBase**，跨 KieBase 不可共用（我们本就 per-artifact，不痛）；③ RHS 若要读动态字段，得再走 FactType 反射（POJO 方案直接 `numberAttr` 更顺）；④ 多一套「schema→declare 片段」生成器要维护。
- **何时切**：若将来出现 **DATE 区间比较**（typed `Date` 比 String 干净）、**上下文变多实例需要真索引**、或**大量数值范围 alpha 约束**压测显示 Map 方案有瓶颈——那时把上下文从 (a) 平移到 (c)（翻译器输出不变，只换 fact 构造与 header），迁移成本可控。**当前需求下 (a) 足够，(c) 记为 ADR 备选。**

#### 选型评分

| 维度（5=最好） | (a) Map+类型化访问器 | (b) codegen typed 类 | (c) declare + FactType |
| --- | ---: | ---: | ---: |
| 引擎保真（索引/reactivity） | 3（单例下够用） | 5 | 5 |
| 编译/启动开销 | 5 | 2 | 4 |
| classloader/内存安全 | 5 | 2 | 4 |
| 防注入/安全面 | 4 | 2 | 4 |
| `CLAUDE.md` 坑 6 规避 | 5 | 5 | 4 |
| 可测性 | 5 | 3 | 3 |
| 维护面 | 5 | 2 | 3 |
| 多租户多 schema 适配 | 5 | 2 | 4 |
| **结论** | **推荐** | 否决 | 逃生舱 |

### 2.3 `RuleConditionTranslator` / `ActivityDrlBuilder` 具体怎么改

**`RuleConditionTranslator`【需改造】**（`engine/RuleConditionTranslator.java`）
1. 入参加 `SchemaView`（由 `ContextSchemaRegistry.resolve(tenantId,bizLine)` 得到）；`translate(root, schemaView)`。
2. `translateLeaf`：`RuleField.fromKey(key)`（`:57`）→ `schemaView.field(key)`；`field.allows(op)`（`:62`）语义不变，源改 schema；不命中仍抛 `IllegalArgumentException`（**fail-closed 不变**）。
3. **访问器 token 生成**：现在 `field.factField()`（`:65`）直接当访问器（`orderAmount`）→ 改为按 `value_type` 生成类型化方法调用：`numberAttr("<key>")` / `textAttr("<key>")` / `listAttr("<key>")` / `boolAttr("<key>")`。`<key>` **先过正则 `^[a-zA-Z][a-zA-Z0-9_]{0,63}$`**（§7 防注入），字符串字面量仍走现有 `scalar()` 转义（`:107`）。
4. **新增硬上限**（承 `10-...` R6 + FINAL_PLAN §6.1）：`MAX_NODES`（条件树总节点）、`MAX_LIST_LEN`（`in/notIn/containsAny/between` 列表长度），可测计数器；防超长列表放大编译/执行（DoS 边界）。`MAX_DEPTH=5`（`:26`）保留。

**`ActivityDrlBuilder`【需改造】**（`engine/ActivityDrlBuilder.java`）
1. `header()`（`:29`）：`ActivityRuleContext` import 换成通用 `RuleContext`；`FACT` 包名保持（fact 仍在 `com.lrj.drools.activity.domain`）。
2. `buildEligibilityDrl`（`:46`）：`not ActivityRuleContext( <constraint> )` → `not RuleContext( <constraint> )`；约束串已由翻译器给成 `numberAttr(...)` 形式，模板不用感知字段。
3. **ladder 泛化**（`:158-178`，命门）：`ActivityRuleContext( orderAmount != null, orderAmount >= min, orderAmount < max )` → 参数化 `<ladderField>`：
   `RuleContext( numberAttr("<ladderField>") != null, numberAttr("<ladderField>") >= min, numberAttr("<ladderField>") < max )`。
   `<ladderField>` 来自 benefit/scene 配置（mall=`orderAmount`，ride=`completedTrips`），**同样过正则白名单 + 必须是该 schema 里 `value_type=NUMBER` 的字段**。修复 FINAL_PLAN §6.1「ladder computedAmount 被 discount 固定额覆盖」的诉求一并做（阶段化 KieBase 语义不变）。
4. **benefit 模板插件化**（§4.2）：`buildDiscountDrl`（`:78`）/`buildGiftDrl`（`:183`）从「写死两个方法」变成「按 `benefit_code` 从模板注册表取片段」，RHS 落 `BenefitOutcome` 而非 typed `hit/gifts`。

**`ActivityRuleRuntimeService`【需改造】**（`engine/ActivityRuleRuntimeService.java`）
1. `run()`（`:113`）：`facts.add(ctx)` 的 ctx 从 `ActivityRuleContext` 换 `RuleContext`；`facts.addAll(ctx.getCandidates())` 不变。
2. cache（`:41` `Map<String drl全文, KieBase>`）：按 FINAL_PLAN 拆成「发布期编译（`ActivityArtifactCompiler`）+ guarded execute」，cache key 带 tenant（§3.3）。决策期改**有界 stateful `KieSession` + 三件护栏**（FINAL_PLAN §5.2 硬前提），本文不重复。

---

## 3. 硬骨头 #2：多租户隔离模型

### 3.1 三档隔离对比

| 维度 | 行级（`tenant_id`+共享库）【推荐主线】 | schema-per-tenant | db-per-tenant |
| --- | --- | --- | --- |
| 隔离强度 | 弱（逻辑隔离，靠代码纪律） | 中（物理表隔离，共享实例） | 强（物理库隔离） |
| 适配 many-tenant SaaS | 好（一套表容纳全部租户） | 中（schema 数量爆炸） | 差（库数爆炸、成本高） |
| 复用 CAS/outbox/pointer 机器 | **几乎零改**（`release_key` 加 tenant 维度） | 中（连接路由 + per-schema 迁移） | 中（数据源路由） |
| Flyway 迁移 | 一次（共享 DDL） | **N 次 fan-out**（每 schema 跑一遍） | N 次 + 数据源管理 |
| 越权风险 | **高**（漏一个 `tenant_id` 过滤即泄漏，§7） | 低（连接即隔离） | 极低 |
| 噪声邻居 | 高（共享连接池/表/JVM） | 中 | 低 |
| 备份/合规删除/独立扩容 | 难（混在一起） | 中 | 易（按库） |
| 运维成本 | 低 | 中 | 高 |

### 3.2 推荐：行级隔离为主 + 逃生舱

**主线：行级隔离**。理由：本平台是 **many-tenant SaaS**（大量中小租户自助接入，非少数大企业），行级最省表、最省运维，且**能原样复用** FINAL_PLAN 的 artifact/manifest/pointer/outbox/audit 全套机器——只把 `release_key` 从 `bizLine` 升为 **`(tenant_id, biz_line)`** 复合键，CAS/generation/outbox 幂等语义一字不改。

**逃生舱（同一套代码，配置切换）**：
- **schema-per-tenant**：给少数大客/强合规租户用（数据物理隔离、独立备份）。用 Hibernate multi-tenancy（SCHEMA 策略）+ `TenantContext` 路由连接，DDL 走 per-schema Flyway。**决策代码不感知**（仓库层照常带 tenant，只是连接被路由）。
- **db-per-tenant**：仅「企业专属版」，独立数据源。

> 这是「行级默认 + 大客升配」的**池化 + 隔离混合模型**，避免为极少数大客把全体拖进 schema/db 爆炸，也给合规客留了物理隔离的门。

### 3.3 对承重机器的冲击与对策

| 机器 | 现状/FINAL_PLAN | 多租户冲击 | 对策 |
| --- | --- | --- | --- |
| **artifact / manifest / pointer** | `release_key = bizLine`（FINAL_PLAN §5.4） | 需按租户隔离生效指针 | `release_key = (tenant_id, biz_line)`；所有相关表加 `tenant_id`；pointer PK 复合。CAS/outbox/audit 语义不变【复用】 |
| **`DecisionSnapshotRegistry`（内存只读索引）** | 单租户全量常驻，按 `bizLine+spuId → handle`（FINAL_PLAN §6.2） | **OOM 命门**：N 租户 × M 活动 × 索引全常驻 → 堆爆 | ① 按 `tenant_id` **分区**；② 每租户 + 全局**内存预算上限**；③ **冷租户 LRU 淘汰**、首请求 **lazy load + single-flight** 从 artifact 重建；④ 热租户 **pin** 不淘汰；⑤ 常驻租户数**硬上限**，超限拒绝新租户激活并告警（背压而非 OOM） |
| **KieBase 编译缓存** | key = DRL 全文 / `artifactId:scene:templateVersion`（FINAL_PLAN §5.3） | 多租户多 schema → KieBase 数量 ×租户；冷编译尖刺放大 | cache key = **`tenant:bizLine:artifactId:scene:templateVersion:schemaVersion`**；Caffeine **maximumWeight**（权重≈规则数/KieBase 足迹）+ `expireAfterAccess`；**single-flight** 防击穿；**发布/pointer 激活时预热**该 (tenant,bizLine) 的 KieBase，warm 租户不打冷编译进热路径 |
| **噪声邻居** | 无（单租户） | 一个租户 runaway/大 ruleset 拖垮全体 | ① 决策执行 **per-tenant bulkhead**（信号量/线程预算，隔离并发）；② **per-tenant fire budget**（复用 FINAL_PLAN §5.2 `fireAllRules(max)` + watchdog `halt()`，预算按租户）；③ KieBase 权重 **per-tenant 上限/公平份额**，防一个大租户把别人全淘汰；④ per-tenant/per-api-client 限流 |
| **配额执行** | 无 | 需限制每租户资源占用 | `activity_tenant_quota`：#活动、schema #字段、条件树节点数、QPS、KieBase 内存上限。**配置期**校验（建 schema/活动时）+ **决策期**限流（429），超限 fail-closed |
| **计费打点** | 无 | 需按租户计量 | 复用 outbox/decision event：`tenant_id` 维度累计 decision 数、compile 数、活跃活动数，落 `activity_billing_daily`（聚合表，异步幂等）。**指标 tag 基数**：`tenant_id` 仅在租户数设硬上限白名单内才作 Micrometer tag，否则归一 `other`，明细进日志/聚合表（严守 `10-...` R5 + FINAL_PLAN §11 基数约束） |

---

## 4. ladder 泛化 + benefit-type 插件 + 无状态取向

### 4.1 ladder 泛化：阈值字段参数化即通用

现 ladder 把 `orderAmount` 写死（`ActivityDrlBuilder:165`）。泛化只需一步：**把阈值字段从模板常量提升为参数 `<ladderField>`**（校验过的 NUMBER 类 schema 字段 key），`LadderRangeParser`（`engine/LadderRangeParser.java`，解析 `[{min,max,reward}]`）**完全不动**——它只解析档位数值，与「档位比的是哪个字段」无关。

| 场景 | ladderField | LHS（改造后模板） | 证明 |
| --- | --- | --- | --- |
| mall 阶梯红包 | `orderAmount` | `RuleContext( numberAttr("orderAmount") >= min, ... < max )` | 与现语义等价 |
| ride 阶梯奖励 | `completedTrips` | `RuleContext( numberAttr("completedTrips") >= min, ... < max )` | **同一 ladder 机器，只换阈值字段** |

结论：**ladder 场景把阈值字段参数化即可同时服务 orderAmount 与 completedTrips**，无需为出行新写一套阶梯逻辑。`reward` 仍是 RHS 编译期常量（坑 6 不触发）。

### 4.2 benefit-type 插件：结果形状泛化

现结果写死在 `ActivityRuleResult`（typed `hitAmount/gifts`，`domain/ActivityRuleResult.java:22-23`）。泛化为**注册插件 + 泛化 outcome**：

```java
// 【需新建】domain/BenefitOutcome.java —— 泛化的权益结果单元
public class BenefitOutcome {
    private String benefitCode;          // DISCOUNT / GIFT / CASH_REWARD
    private String activityId;
    private Map<String,Object> payload;  // 结构由 benefit_type.result_shape_json 定义
}
// ActivityRuleResult【需改造】：typed hitAmount/gifts → List<BenefitOutcome> outcomes（旧字段留兼容投影）
```

| benefit_code | config schema（运营填） | DRL 模板片段（平台内置，RHS 落 payload） | result payload 形状 |
| --- | --- | --- | --- |
| `DISCOUNT` | 金额/单位/合并策略/阶梯 JSON | 现 `buildDiscountDrl` 的 MAX/MUTEX/STACK/PRIORITY（`ActivityDrlBuilder:78`） | `{finalAmount, strategy, currency}` |
| `GIFT` | 赠品批次/数量 | 现 `buildGiftDrl`（`:183`） | `{items:[{batchId,giftName,giftNum,...}]}` |
| `CASH_REWARD`（ride 新增） | 奖励金额/币种（阶梯走 ladder over completedTrips） | 新模板：命中即 `outcome.payload.amount = <reward常量>` | `{amount, currency}` |

**插件注册 = 把现有 `ActivityDrlBuilder` 的 build 方法拆进「模板注册表」**，key 为 `drl_template_ref`。租户只选 benefit type + 填 config schema 值，模板由平台维护——**「运营不写 DRL」在多品类下延续**。现金奖励与折扣的差异**只在结果形状与 RHS 落值**，LHS 资格/阶梯完全共用（都在通用 `RuleContext` 上）。

### 4.3 无状态 over caller-provided context vs CEP/Step 8 窗口累积

| 取向 | 平台职责 | 状态 | 多租户/水平扩展 | 复杂度 |
| --- | --- | --- | --- | --- |
| **无状态 over 预聚合 context【推荐】** | 纯函数：schema 校验的 context 入 → 决策出。ride 的 `completedTrips` 由**司机端预聚合**传入，平台只判 `completedTrips >= 阈值` | 无跨请求状态 | **天然**：无 session 持久化、无时钟协调、无跨实例内存 | 低；可复现、可缓存、可测 |
| CEP / Step 8 窗口累积 | 平台自持事件流 + `@role(event)` + stream mode + pseudo/realtime clock + window 驻留事件算窗口 | **有状态**：per-key 会话、驻留事件、时钟 | 差：多租户 × per-key 会话 = 巨大内存/时钟协调面，跨实例一致性难 | 高 |

**推荐：无状态 over caller-provided aggregated context**。论据：
1. 需求明确「司机端预聚合 `completedTrips` 传入，平台无状态」——窗口语义归属调用方，平台不该重复持有。
2. 多租户 SaaS + 水平扩展下，CEP 的驻留事件/pseudo clock/跨实例时钟是 OOM 与一致性的双重灾难，与「决策面无状态水平扩展」（`10-...` 方案 B）冲突。
3. 纯函数决策**可复现**（同 context+同 decisionVersion → 同结果），契合 pinVersion/影子/AB（FINAL_PLAN §5.5）。

> **澄清一个易混点**：FINAL_PLAN §5.2 的「stateful `KieSession`」是为了拿护栏三件套（`fireAllRules(max)`/`halt()`/`AgendaFilter`），它**每请求新建、finally dispose、无跨请求状态**——这与 CEP 的「跨请求驻留事件」是两码事。推荐无状态取向**不与** stateful 护栏冲突：决策仍是「每请求 stateful session 跑一次即弃」。

**何时才考虑 CEP**：调用方**无法**预聚合（平台必须自持原始事件流做跨事件关联）、或需要平台侧滑窗风控。届时应做成**独立的、可选的、per-tenant 预聚合旁路服务**（自己管 stream/clock/内存），把聚合结果喂回无状态决策核，**绝不塞进共享决策热路径**。记为独立 ADR。

---

## 5. 决策 API 契约增量：通用 context 信封

### 5.1 从固定字段到通用信封

现 `SpuDiscountRequest`（`domain/SpuDiscountRequest.java`）是固定的 `userId/userTags/userDistrictId/spuIdList/orderAmount/quantity`。通用化后：

```
POST /decision/v1/evaluate
Headers: X-Api-Key（服务间鉴权，绑定 tenant）, X-Request-Id, X-Trace-Id
Body:
{
  "tenantId": "acme",              // 也可由鉴权主体注入；与 header 冲突则 400（防越权，§7）
  "bizLine": "ride",               // 定位 schema + release_key
  "scene": "ALL",                  // ALL / ELIGIBILITY / LADDER / DISCOUNT / GIFT / CASH_REWARD
  "context": {                     // ★ schema 校验过的任意键值（替代固定字段）
     "driverId": 123,
     "cityId": "010",
     "driverLevel": "GOLD",
     "completedTrips": 15,
     "onlineHours": 8,
     "timeSlot": "PEAK",
     "vehicleType": "COMFORT"
  },
  "options": { "explain": false, "dryRun": false, "pinVersion": null, "timeoutMs": null }
}
```

电商同一信封：`context: { orderAmount, quantity, spuId(或 spuIdList), storeId, userDistrictId, userTags }`。

### 5.2 字段级契约

| 字段 | 类型 | 必填 | 校验/语义 |
| --- | --- | --- | --- |
| `tenantId` | string | 是 | 与鉴权主体一致；不一致 → **400/403**（越权防线） |
| `bizLine` | string | 是 | 定位 `(tenant,bizLine)` 的 APPROVED schema + release_key；无生效指针 → 降级 `NO_PROMOTION` |
| `scene` | enum | 否（默认 ALL） | 一次可返多 benefit（折扣+赠品/现金） |
| `context` | object | 是 | **逐键按 schema 校验**：键必须 ∈ `activity_context_field`；类型/值域（ENUM 命中 `activity_field_option`）匹配；**未知键 → 400**（fail-closed，防 schema 注入）；按 `value_type` **归一化**（NUMBER→BigDecimal 等，喂 §2.2 类型化访问器） |
| `context.<selectorField>` | — | 视 schema | `is_selector=true` 的字段用于**候选预筛选**（§5.3）。mall=spuId、ride=cityId |
| `options.explain` | bool | 否 | true 返回候选评估全景（仅 Admin/内部授权，对齐 FINAL_PLAN §16.2 + §8.2 `explain-enabled-for-external:false`） |
| `options.dryRun` | bool | 否 | 影子（FINAL_PLAN §5.5） |
| `options.pinVersion` | string | 否 | 固定 manifestId（Admin sandbox） |
| `options.timeoutMs` | int | 否 | 只能调低平台默认（FINAL_PLAN §8.1） |

**响应增量**（在 FINAL_PLAN §8.1 响应上泛化权益）：
- `decisionVersion` = 本次 `(tenant,bizLine)` 选中 manifest 的 `manifestId:generation`。
- 权益从 typed `discount{finalAmount}` / `gifts[]` 泛化为 **`benefits[]`**：每项 `{benefitCode, activityId, payload}`（payload 形状由 `result_shape_json`）。DISCOUNT/GIFT 保留为「well-known benefit」向后兼容，同时并入 `benefits[]`。
- `degraded/degradeReason/hitActivities[]/route/timings` 沿用 FINAL_PLAN §8.1。

### 5.3 候选预筛选（selector）泛化

现候选选择靠 `activity_spu_binding`（spuId→活动，`persistence/ActivitySpuBindingEntity.java`）。出行无 SPU，需泛化：
- **schema 声明 `is_selector` 字段**：mall=`spuId`、ride=`cityId`。
- 绑定表泛化为 **`activity_selector_binding(tenant_id, biz_line, selector_key, selector_value, activity_id, effective)`**（现 spu_binding 是它的 mall 特例）。`DecisionSnapshotRegistry` 索引从 `spuId→handle` 泛化为 `(selector_value)→handle`。
- **低基数业务线兜底**：若某 (tenant,bizLine) 活动数很少，可跳过绑定、直接载入全部 active 活动，由资格条件（`cityId == "010"`）过滤——`is_selector` 可选。

### 5.4 field-dict 数据驱动

现 `GET /activity-marketing/field-dict`（`ActivityMarketingController.java:108`）遍历 `RuleField.values()` 硬编码返回。改为：

```
GET /activity-config/v1/field-dict?tenant=acme&bizLine=ride
→ 读 activity_context_schema(APPROVED) + activity_context_field(+option)
→ 返回 { fields:[{key,label,valueType,operators,options?,selector}], operators, logics, benefitTypes, ... }
```

前端「可搜索多选下拉」从 `activity_field_option` 拿候选（补齐 FINAL_PLAN §16.3 的降级项）。运算符/逻辑仍来自平台内置枚举（`RuleOperator`/`RuleLogic` 不变）。

---

## 6. 对 FINAL_PLAN 的增量清单（P0/P1/P2）

> 「新增」均为规划目标，当前仓库不存在。**在 FINAL_PLAN 的 ~13 张新表 + 承重机器之上叠加**，不推翻其架构。

### 6.1 新表【需新建】

| 优先级 | 表 | 用途 |
| --- | --- | --- |
| **P0** | `activity_context_schema` | 每 tenant×bizLine 的 schema 头 + 版本 + 审核态 |
| **P0** | `activity_context_field` | schema 字段（key/valueType/allowedOps/selector），替代 `RuleField` |
| **P0** | `activity_benefit_type` | benefit 插件注册（config schema + drl_template_ref + result shape） |
| **P0** | `activity_tenant` | 租户主数据（id/name/tier/status/isolation_mode） |
| **P1** | `activity_tenant_quota` | 每租户配额（#活动/#字段/节点数/QPS/内存） |
| **P1** | `activity_field_option` | 枚举/下拉候选值（前端可搜索多选 + ENUM 值域校验） |
| **P1** | `activity_selector_binding` | 候选预筛选绑定（泛化 `activity_spu_binding`） |
| **P2** | `activity_billing_daily` | 每租户计量聚合（decision/compile/活跃活动数），异步幂等 |

### 6.2 现有表改造【需改造】

| 优先级 | 表 | 改造 |
| --- | --- | --- |
| **P0** | 全部 activity_* 业务表（manage/rule/condition/artifact/manifest/pointer/outbox/audit/api_client/effect_daily…） | 加 `tenant_id` 列 + 复合唯一键/索引（`(tenant_id, ...)`）；行级隔离基座 |
| **P0** | `activity_release_pointer` | `release_key` PK 从 `bizLine` → `(tenant_id, biz_line)`；CAS/generation 语义不变 |
| **P1** | `activity_spu_binding` | 迁移/投影为 `activity_selector_binding` 的 mall 特例（`selector_key='spuId'`） |
| **P1** | `activity_condition` | `generated_drl` 内容改为 `numberAttr(...)` 形态（历史 mall 数据重翻译校验，FINAL_PLAN §7 双读影子路径） |

### 6.3 类改造/新增

| 优先级 | 类 | 状态 | 变更 |
| --- | --- | --- | --- |
| **P0** | `RuleField`（enum） | 【需改造】 | 退化为「mall 默认 schema 种子数据」；白名单源移交 `ContextSchemaRegistry` |
| **P0** | `ContextSchemaRegistry` + `SchemaView` + `ContextField` | 【需新建】 | 数据驱动白名单，按 (tenant,bizLine) 解析 + 缓存 |
| **P0** | `FieldValueType`（enum） | 【需改造】 | + `ENUM/BOOLEAN/DATE` |
| **P0** | `ActivityRuleContext` → `RuleContext` | 【需改造/需新建】 | Map 支撑 + `numberAttr/textAttr/listAttr/boolAttr` 类型化访问器 + `tenantId` |
| **P0** | `RuleConditionTranslator` | 【需改造】 | 白名单源换 schema；访问器 token 类型化；`<key>` 正则白名单；+`MAX_NODES/MAX_LIST_LEN` |
| **P0** | `ActivityDrlBuilder` | 【需改造】 | ladder `<ladderField>` 参数化；header 换 `RuleContext`；benefit 模板插件化 |
| **P0** | `ActivityRuleRuntimeService` | 【需改造】 | 插 `RuleContext`；cache key 带 tenant（与 FINAL_PLAN artifact/compiler 合流） |
| **P0** | `BenefitTypeRegistry` + `BenefitOutcome` + benefit 模板贡献者 | 【需新建】 | 权益插件 + 泛化结果单元 |
| **P0** | `ActivityRuleResult` | 【需改造】 | typed `hitAmount/gifts` → `List<BenefitOutcome>`（旧字段兼容投影） |
| **P0** | `TenantContext` + 租户解析过滤器 | 【需新建】 | 从鉴权主体取 tenant，贯穿仓库层（强制 `tenant_id` 过滤，防越权） |
| **P0** | `ActivityDecisionController#evaluate` / DTO | 【需改造】 | 固定字段 → 通用 context 信封（§5） |
| **P1** | `DecisionSnapshotRegistry` | 【需改造】 | 租户分区 + LRU + 内存上限 + lazy load single-flight |
| **P1** | KieBase 编译缓存（Caffeine） | 【需改造】 | key 带 tenant；maximumWeight + expireAfterAccess + 单飞 + 预热 |
| **P1** | `TenantQuotaService` + `TenantBulkhead` | 【需新建】 | 配额校验（配置期）+ 限流/隔离（决策期） |
| **P1** | `field-dict` 端点 | 【需改造】 | `?tenant=&bizLine=` 数据驱动（§5.4） |
| **P2** | `TenantMeteringService` | 【需新建】 | 计费打点（复用 outbox/decision event） |

### 6.4 配置【需改造】（在 FINAL_PLAN §8.2 上加）

```yaml
activity:
  tenancy:
    isolation-default: ROW          # ROW / SCHEMA / DB（大客逃生舱）
    max-resident-tenants: <压测/堆预算>   # DecisionSnapshotRegistry 常驻上限
  snapshot-registry:
    per-tenant-max-weight: <堆预算>
    cold-tenant-expire: <SLO>
  kiebase-cache:
    per-tenant-max-weight: <堆预算>       # 防噪声邻居把别人淘汰
  quota:
    default-max-activities / max-schema-fields / max-condition-nodes / qps: <各设默认>
```

---

## 7. 风险与真实代价（诚实标注）

### 7.1 复杂度

- **通用化不是免费**：多了 schema 注册表 + benefit 插件 + 通用 fact + 租户维度，**每条热路径都新增 tenant 维度**，认知与测试面显著变大。FINAL_PLAN 已是「目标态蓝图」，本增量再叠一层——**强烈建议延续 FINAL_PLAN §15 的「MVP-of-MVP 薄片」策略**：先用**电商单租户**跑通「schema 驱动 + 通用 fact + benefit 插件」（把 `RuleField`→registry、`ActivityRuleContext`→`RuleContext`、ladder 参数化落地，行为对齐旧路径），**再叠加多租户维度**（tenant_id + 分区 registry + 配额）。两个硬骨头**不要同时上**。
- **两套 schema 只是起点**：`activity_field_option`/ENUM/DATE 的组合校验、schema 版本与 artifact 版本的联动（改 schema 是否要重编所有引用它的 artifact）是新的编排复杂度，需明确「schema 变更 = 触发受影响 artifact 重建/重校验」的规则。

### 7.2 性能

- **Map 通用 fact 的索引损失**：`numberAttr(...)` 不走 alpha/beta 索引。**已论证上下文是单例故影响可忽略**，但这是**假设，必须压测证伪**：多候选 × 多资格约束下，若某 bizLine 的资格规则演化成对上下文的大量约束，需验证无退化；否则切 declared-type (c)。
- **多租户冷编译尖刺**：KieBase 缓存被冷租户挤淘汰后，其首请求触发重编译（FINAL_PLAN §14 已忧）。多租户放大此面 → 预热 + single-flight + per-tenant 权重是必需，且**淘汰策略要压测**（LRU 抖动、大租户独占）。
- **`DecisionSnapshotRegistry` 内存**：常驻上限设太小 → 冷租户频繁重建（延迟）；设太大 → OOM。**这是最该容量建模 + 压测的点**。

### 7.3 安全（最需盯）

- **租户越权（cross-tenant）**：行级隔离下，**任何一处漏掉 `tenant_id` 过滤 = 跨租户数据泄漏**。防线：① 仓库层强制 tenant 参数（`TenantContext` 注入，禁裸查询）；② 决策主体的 tenant 与 body `tenantId` 不一致 → 拒绝；③ **所有缓存 key（KieBase、snapshot、schema）必须含 tenant**，否则跨租户 KieBase/快照串味（最隐蔽的越权）；④ pinVersion/manifestId 必须校验归属租户。
- **schema 注入 / DRL 注入**：字段 `field_key`、ladder 的 `<ladderField>`、benefit `drl_template_ref` 都可能被租户管理员影响，是**新的注入面**。防线：① `field_key`/`<ladderField>` **强正则白名单** `^[a-zA-Z][a-zA-Z0-9_]{0,63}$`，且必须命中该 schema 已注册字段，**永不把用户串当裸 DRL 标识符拼接**；② 值仍走现有 `scalar()` 转义（`RuleConditionTranslator:107`）；③ **benefit DRL 模板由平台运维注册，租户只选不写**（`drl_template_ref` 不接受租户提交模板文本）；④ ENUM 值必须命中 `activity_field_option`；⑤ 列表长度/节点数硬上限防 DoS 放大。
- **`CLAUDE.md` 坑复核**：坑 6（RHS 无 record accessor）——`RuleContext` 是 POJO 非 record，且 RHS 不读上下文，**不触发**；坑 3（`update/modify` 死循环）——通用化不引入新 `update`，ladder 仍 `setComputedAmount` 常量 + 护栏兜底；坑 4/6（DRL 运行时编译）——新增字段/benefit 模板必须**真跑一次冒烟**（`mvn compile` 不校验 DRL 语法），多租户/多 schema 下这条更要在发布期编译校验（FINAL_PLAN artifact compile verify）拦住；坑 7（`@JdbcTypeCode` 大字段）——`config_schema_json`/`generated_drl`/schema JSON 继续 `LONGVARCHAR`，不用 `@Lob`。

### 7.4 一句话代价总结

**通用 fact/DRL 让「加业务线」从「改 Java 发版」变成「配数据」，但代价是把类型安全从编译期挪到运行期校验（schema 必须无懈可击）；多租户让一套机器服务全体，但代价是每处都要防越权、每块内存都要防 OOM。这两笔账都能靠「白名单 + fail-closed + 容量建模 + 压测」还清，但都不是零成本，且必须分两步落地、不可同时上。**

---

## 附：能力矩阵（已具备 / 需改造 / 需新建 速查）

| 能力 | 现状 | 通用化+多租户目标 | 状态 |
| --- | --- | --- | --- |
| release/manifest/pointer/CAS/outbox/audit | FINAL_PLAN 方案 B | `release_key=(tenant,bizLine)`，其余不变 | 【已具备】复用 |
| stateful 护栏三件套 | FINAL_PLAN §5.2 | per-tenant fire budget/bulkhead | 【已具备】复用+加维度 |
| 条件树白名单 fail-closed | `RuleConditionTranslator`+`RuleField` | 源换 schema，行为不变 | 【需改造】 |
| 字段白名单 | `RuleField` 6 枚举 | `activity_context_field` 数据驱动 | 【需改造】 |
| 值类型 | NUMBER/STRING/ARRAY | +ENUM/BOOLEAN/DATE | 【需改造】 |
| 上下文 fact | typed `ActivityRuleContext` | Map+类型化访问器 `RuleContext` | 【需改造】 |
| ladder | over orderAmount 写死 | `<ladderField>` 参数化（orderAmount/completedTrips） | 【需改造】 |
| 权益结果 | typed hitAmount/gifts | `List<BenefitOutcome>` + benefit 插件 | 【需改造】 |
| 候选预筛选 | `activity_spu_binding` | `activity_selector_binding`（spuId/cityId） | 【需改造】 |
| field-dict | 硬编码 `RuleField.values()` | `?tenant=&bizLine=` 数据驱动 | 【需改造】 |
| schema 注册表 | 无 | `activity_context_schema/_field/_option` | 【需新建】 |
| benefit 注册表 | 无（build 方法写死） | `activity_benefit_type` + 模板注册表 | 【需新建】 |
| 多租户隔离 | 无（单租户） | 行级为主 + schema/db 逃生舱 | 【需新建】 |
| 租户内存治理 | 无 | registry 分区+LRU / KieBase 权重淘汰 | 【需新建】 |
| 配额/计费 | 无 | quota 表 + 限流 + 计量聚合 | 【需新建】 |
| CEP 窗口累积 | Step 8 有 demo，activity 未用 | **不引入**（无状态 over 预聚合） | 【刻意不做】 |
```
