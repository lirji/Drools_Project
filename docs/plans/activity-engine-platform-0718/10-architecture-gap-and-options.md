# 活动引擎平台 · 架构现状分析 + 目标架构选项

> 文档定位：从「学习/探索脚手架」演进到「生产级活动引擎平台」（决策中台 + 运营控制台）的**架构现状 → 差距 → 目标选项 → 决策 API 契约 → 风险预研**。
> 只做架构分析与选型，不改任何 Java/JS/配置代码。所有类名/文件均引用真实代码。
> 标注约定：【已具备】= 现有代码已实现；【需改造】= 现有代码有雏形需升级；【需新建】= 现在完全没有。
>
> 范围前提（来自任务背书）：**单租户为主，多租户 SaaS 放二期**。MVP 四项能力：① 可视化配置控制台 ② 实时决策 API + 引擎护栏 ③ 版本化·审核·灰度·回滚 ④ 可观测 + 效果分析。

---

## 0. 一页速览（TL;DR）

- **现状**：`com.lrj.drools.activity` 已是一个结构清晰的分层单体：写路径（`ActivityMarketingService`）+ 读/决策路径（`ActivityQueryService`）+ 运行时 DRL 编译引擎（`ActivityRuleRuntimeService` + `ActivityDrlBuilder` + `RuleConditionTranslator`）+ 10 张 JPA 表。已具备版本化、幂等、并发冲突保护、fail-safe 回退、灰度开关、条件树白名单——**平台化的地基已经打好，但都是"单机 demo 级"实现**。
- **最大的 P0 差距**：决策路径**没有引擎护栏**（`StatelessKieSession.execute()` 无 `fireAllRules(max)` / `halt()` / `AgendaFilter`，与 Step 14 `GuardService` 已验证的护栏脱节）；**没有编译产物版本仓库**（缓存 key 是 DRL 全文的 `ConcurrentHashMap`，无界、无预热、无原子发布/回滚）；**没有鉴权**；**没有可观测**（`MeteredRuleListener` 现成却没接进来）。
- **推荐目标架构**：**方案 B（配置面 / 决策面分离）为最终形态，但分两步落地**——MVP 先做「模块化单体 + 规则产物仓库接缝」（方案 A 的部署形态 + B 的模块边界），二期按 QPS 再把决策服务物理拆出。理由：决策 API 和运营控制台的**负载画像、可用性等级、发布节奏完全不同**，必须能独立扩缩容；但单团队 MVP 阶段先物理拆分是过度设计，用"可拆分的接缝"换取可逆性。

---

## 1. 现状架构速写

### 1.1 分层与职责

现有 `com.lrj.drools.activity` 是一个**六边形味道的分层单体**（controller → service → engine → persistence），职责边界干净：

| 层 | 类 | 职责 |
| --- | --- | --- |
| **入口层** | `controller/ActivityMarketingController`（8 个接口） | REST 入口；参数非法 400、并发冲突 409；`/field-dict` 输出白名单给前端防漂移 |
| **写服务** | `service/ActivityMarketingService`（399 行） | 创建/版本化编辑/上下线/详情/预览；`@Transactional`；幂等 + 并发保护 |
| **读/决策服务** | `service/ActivityQueryService`（297 行） | toC 决策读路径：SPU→绑定→生效活动→候选→规则引擎→命中；fail-safe 回退 |
| **圈选服务** | `service/ActivityPoolMatchService`（169 行） | 商品池规则圈选 + 自动绑定物化（目标态 diff，幂等） |
| **规则引擎** | `engine/ActivityRuleRuntimeService`（运行时编译执行）+ `ActivityDrlBuilder`（DRL 模板生成）+ `RuleConditionTranslator`（条件树→受控 DRL）+ `LadderRangeParser`（阶梯 JSON） | 规则即数据的核心 |
| **领域 fact** | `domain/ActivityRuleContext`（输入）/ `ActivityCandidate`（候选，可变 POJO）/ `ActivityRuleResult`（`global result` 输出）/ `ConditionNode`（条件树）/ `RuleField`（字段白名单枚举） | Drools 事实类型 |
| **持久化** | `persistence/`（10 实体 + 10 Repo） | `activity_manage` / `activity_rule` / `activity_condition` / `activity_spu_binding` / `activity_gift` / `activity_strategy` / `activity_product_pool(_item/_rule)` / `activity_pool_ref` / `demo_product` |

### 1.2 数据流：写路径（创建/编辑）

```
POST /activity-marketing/create  (ActivityCreateRequest)
        │
        ▼  ActivityMarketingService.create()  @Transactional(rollbackFor=Exception)
  ┌───────────────────────────────────────────────────────────────────┐
  │ 1. validateCommon()          类型/时间/金额/策略枚举校验             │
  │ 2. 幂等：findFirstByRequestIdAndIsDel → 命中直接返回首次结果         │
  │ 3. 版本化：isEdit ? softDeleteVersion(旧版本)                       │
  │        └─ affected==0 → IllegalStateException(409 并发冲突)         │
  │           version = current+1；否则 version=1 + 生成 activityId     │
  │ 4. translator.translate(条件树) → 受控 Drools 约束串                 │
  │ 5. ruleRuntime.compileOrGet(drl)  ← 严格编译校验，失败带行号抛出      │
  │        └─ 抛异常 → 整个事务回滚，一张表都不落  (写前编译校验)         │
  │ 6. LadderRangeParser.parse() 校验阶梯 JSON                          │
  │ 7. 落库：manage→rule→condition→gift→手动绑定→池引用                  │
  │ 8. poolMatchService.refreshActivityBinding()  同事务物化自动圈选     │
  │ 9. saveStrategyIfPresent()  业务线级合并策略 upsert                  │
  └───────────────────────────────────────────────────────────────────┘
        ▼
  CreateResult(activityId, version, status, idempotentHit, autoBoundCount)
```

**关键点**：规则翻译 + 编译校验发生在**写库前**（第 4/5 步），编译失败 → 整体回滚 → 不落任何脏数据。这是"制定规则"的正确性护栏，已经很到位。

### 1.3 数据流：读/决策路径（toC 优惠查询）

```
POST /activity-marketing/spu-discount  (SpuDiscountRequest)
        │
        ▼  ActivityQueryService.spuDiscount()
  ┌───────────────────────────────────────────────────────────────────┐
  │ boundActivityIds()      SPU → activity_spu_binding(effective=1)     │  DB read #1
  │ filterBeginActivities() 已上线 + 当前时间在范围 + 类型匹配 + 当前版本 │  DB read #2 (每 id 一次)
  │ flatten()               manage + rule(+gifts) → List<ActivityCandidate>│ DB read #3/#4 (每候选查 rule)
  │        │                                                            │
  │        ▼   if (!rule-engine.enabled) → legacyMax(取最大红包)  ◄──────┼── 灰度开关（全局布尔）
  │        ▼                                                            │
  │  ① evalEligibility(ctx, eligDefs)                                   │
  │        DRL: not ActivityRuleContext(<约束>) → $c.reject()  fail-closed│  DB read #5 (每候选查 condition)
  │        └─ 返回 null → trace("资格回退：全部按生效通过")               │
  │  ② evalLadder(ctx, ladderDefs)   订单金额落档 setComputedAmount      │
  │  ③ evalDiscount(ctx, strategy)   MAX/MUTEX/STACK/PRIORITY 合并        │  DB read #6 (查 strategy)
  │        └─ 命中 → DiscountView(hit, amount, strategy, traces, mode)   │
  │        └─ 空决策/null → legacyMax(仅 eligible 候选) 回退              │
  └───────────────────────────────────────────────────────────────────┘
        ▼
  DiscountView(hit, hitActivityId, hitActivityName, hitAmount, strategy, traces[], mode)
```

**关键点**：一次决策 = **6+ 次 DB 读 + 3 趟独立的规则引擎调用**（资格/阶梯/折扣各一趟），每趟都要 `drlBuilder.buildXxx()` 现拼 DRL 字符串再查缓存。`mode` 字段（`rule-engine`/`legacy`）和 `traces[]` 是天然的灰度对照 + 排障素材。

### 1.4 规则编译执行链路（引擎核心）

```
ActivityDrlBuilder.buildEligibilityDrl/buildDiscountDrl/buildLadderDrl/buildGiftDrl
        │  (拼 DRL 文本，header 里声明 global result)
        ▼
ActivityRuleRuntimeService.compileOrGet(drl)
        │  cache: ConcurrentHashMap<String drl全文, KieBase>     ← 内容级缓存
        │  miss → KieHelper.addContent(drl, DRL).verify()
        │         有 ERROR → IllegalArgumentException("编译失败:\nline N: ...")
        │         无 ERROR → helper.build() → KieBase
        ▼
ActivityRuleRuntimeService.run(kieBase, ctx)
        │  StatelessKieSession session = kieBase.newStatelessKieSession()  ← 线程安全，可复用 KieBase
        │  session.setGlobal("result", new ActivityRuleResult())
        │  session.execute([ctx, ...candidates])     ← ⚠ 无 fireAllRules(max)/halt/AgendaFilter
        ▼
safeRun 包裹：任何编译/执行异常 → log.warn + return null → 调用方回退旧 Java 逻辑
```

### 1.5 已具备的平台化要素（这是好底子）

| 平台化要素 | 现有实现 | 位置 |
| --- | --- | --- |
| **版本化** | 同 `activityId` 多行，`version` 区分，编辑时旧行 `isDel=1` + 新行 `version+1` | `ActivityManageEntity` + `ActivityMarketingService.create()` |
| **幂等** | `requestId` 首次结果返回；`activity_manage(request_id)` 索引 | `create()` 第 2 步 |
| **并发冲突保护** | `softDeleteVersion(activityId, version)` 影响行数为 0 → 409 | `create()` 第 3 步 |
| **灰度开关** | `activity.marketing.rule-engine.enabled`（布尔，全局） | `ActivityQueryService` `@Value` |
| **fail-safe 回退** | 引擎异常/空决策 → `legacyMax()` 取最大红包；`mode` 标记来源 | `ActivityQueryService.spuDiscount()` |
| **条件树白名单（安全边界）** | 字段白名单（`RuleField` 枚举）+ 每字段允许运算符 + 值形状校验 + 深度限制（MAX_DEPTH=5）+ 转义；**绝不接受裸 DRL** | `RuleConditionTranslator` + `RuleField` |
| **写前编译校验** | 条件树翻译 + `compileOrGet` 试编译在写库前，失败整体回滚 | `create()` 第 4/5 步 |
| **预览（不落库）** | `previewEligibility()` 翻译 + 试编译，前端"保存前先验证" | `ActivityMarketingService.previewEligibility()` |
| **规则驱动圈选 + 自动绑定** | 池规则圈选 `demo_product` → 物化绑定，按目标态 diff（幂等可重跑） | `ActivityPoolMatchService` |
| **规则即数据 + rehydrate** | 条件树 JSON + 翻译后 DRL 落 `activity_condition`，决策时 rehydrate 编译 | Step 18 血统 |
| **字典防漂移** | `/field-dict` 输出字段/运算符/枚举，前端下拉唯一来源 | `ActivityMarketingController.fieldDict()` |

### 1.6 可直接复用的"平台积木"（base 项目 Step 9/10/14/15/16，真实类名）

这些不在 activity 模块里，但同一仓库已验证，是平台化的现成零件：

| Step | 类 | 能力 | 对平台的用途 |
| --- | --- | --- | --- |
| 9 | `service/HotReloadService` | `KieHelper` 编译 DRL → `Map<String,KieBase>` 缓存；老 session 不受换 KieBase 影响 | activity 的 `ActivityRuleRuntimeService` 就是它的变体；发布热切换基础 |
| 10 | `service/LoyaltyService` + `persistence/SessionSnapshot` | `MarshallerFactory.newMarshaller(kieBase)` → `byte[]` → JPA（`@JdbcTypeCode(LONGVARBINARY)`） | 有状态会话持久化（本平台决策是无状态，暂不需要，但审计快照可借鉴） |
| 14 | `service/GuardService` + `guard/ReleaseAgendaFilter` | `fireAllRules(maxFires)` 熔断 / watchdog `session.halt()` 超时 / `AgendaFilter` 按 `@release` 元数据灰度放行 | **决策护栏的现成正解**（见下 P0 差距） |
| 15 | `metrics/MeteredRuleListener` + `audit/RuleAuditListener` | `AgendaEventListener`+`RuleRuntimeEventListener` → Micrometer counter（`drools.rules.fired{session,rule}` 等） | **可观测的现成正解**（见下 P1 差距） |
| 16 | `service/ScannerService` | KJAR + `ReleaseId` + `KieScanner` + `KieMavenRepository`，同 GAV 滚动热替换 | 规则跟代码独立发版的工业路径（重依赖 `kie-ci`，取舍见下） |

---

## 2. 生产化差距清单（现状 vs 生产要求）

分级：**P0** = 阻断上线 / **P1** = 重要（上线后近期必补）/ **P2** = 增强。

### 2.1 规则治理（P0/P1）

| # | 现状 | 生产要求 | 差距 | 级别 |
| --- | --- | --- | --- | --- |
| G1 | 缓存 = `ConcurrentHashMap<String drl全文, KieBase>`，key 是**整段 DRL 文本**，`evictAll()` 从不在热路径调用 | 编译产物按 **(bizLine/activityId, version)** 版本化寻址，有界、可淘汰 | 缓存**无界增长**（每种候选组合/策略产生一份新 DRL 文本→新 KieBase 常驻）；无 LRU/TTL；无按活动/版本定位 | **P0** |
| G2 | 决策首次遇到某 DRL 文本 → **请求线路上冷编译**（`KieHelper` 建 KieBase） | 发布即预热编译，决策路径只查已编译产物 | 冷启动/发布后**首请求编译尖刺**打在 P99 上 | **P0** |
| G3 | 无"发布产物"概念；"当前生效版本"= 最新未删的 online 行 | 草稿→审核→发布→灰度→回滚，发布是**原子切换编译产物** | 无原子发布、无一键回滚到旧编译产物；改规则=改数据行，多实例间无一致的"生效版本"视图 | **P0** |
| G4 | 每次决策 `drlBuilder.buildXxx()` **现拼 DRL 字符串**再查缓存 | 规则产物应"编译一次，多次执行" | 拼串本身有开销，且拼串结果作为 cache key 使 G1 更糟 | **P1** |
| G5 | Step 16 `ScannerService`（KJAR+KieScanner）已存在但 activity 模块未用 | 多实例规则一致性、独立发版 | 需权衡：`kie-ci` 是重依赖（拉 maven-core/aether，`installArtifact` 真写 `~/.m2`，见 CLAUDE.md），MVP 单租户是否值得 | **P1** |

> **取舍：KieScanner+KJAR（Step 16）vs 内容缓存+版本指针（Step 9 风格）**
> - KJAR 路线：规则跟代码独立发版、多实例经 Maven 仓库天然一致、`KieContainer.updateToVersion()` 原子切换、可回滚到任意 release GAV。**代价**：`kie-ci` 重依赖、需要一个制品仓库（Nexus/本地 m2）、KJAR 打包/部署链路、SNAPSHOT 滚动 vs release 递增的运维认知成本。
> - 内容缓存+版本指针路线（推荐 MVP 用）：`activity_condition` 已存翻译后 DRL；新增一张"编译产物/发布指针"逻辑（DRL 版本 → 预编译 KieBase，缓存 key 用 `activityId:version` 而非全文），发布时预热 + 原子换指针。**代价**：多实例一致性要自己做（发布事件广播/版本号轮询），但对单租户单/少实例足够，且不引重依赖。
> - **结论**：MVP 走内容缓存+版本指针（改造 `ActivityRuleRuntimeService`），把"发布=切版本指针"做成显式接缝；二期若决策服务水平扩到多实例且规则发版频繁，再评估切 KJAR。

### 2.2 决策 API 的性能与高可用（P0/P1）

| # | 现状 | 生产要求 | 差距 | 级别 |
| --- | --- | --- | --- | --- |
| P1 | `StatelessKieSession` 复用 KieBase（✅ 线程安全，方向对） | 保持 | 无差距，**已具备**（Step 11 验证的复用） | — |
| P2 | 一次决策 **6+ 次 DB 读**（绑定/manage/rule/condition/strategy），活动配置不缓存 | 活动配置快照缓存，决策热路径少打库或不打库 | 决策 QPS 上来 DB 成瓶颈；无本地/分布式缓存层 | **P0** |
| P3 | 3 趟独立引擎调用（eligibility/ladder/discount），每趟 build+compile-lookup+execute | 尽量合并趟数或让每趟命中预编译产物 | 单请求引擎往返 ×3 + 拼串 ×3 | **P1** |
| P4 | 冷编译 + 缓存击穿：新 DRL 文本首请求同步编译，`computeIfAbsent` 对同 key 串行 | 发布预热；缓存 miss 有单飞（single-flight）保护 | 发布后并发首请求可能都卡在编译；无预热 | **P0** |
| P5 | 无超时、无限流、无主动降级（只有 catch 异常后回退） | P99 延迟目标（如 ≤50ms）；超时 halt；入口限流；主动降级到 legacy | 无 SLA 保障机制 | **P0** |
| P6 | 无冷启动预热钩子 | 启动/发布后预热常用活动的编译产物 | 首屏尖刺 | **P1** |

### 2.3 引擎护栏（P0）—— 最该先补的一块

| # | 现状 | 生产要求 | 差距 | 级别 |
| --- | --- | --- | --- | --- |
| E1 | 决策用 `StatelessKieSession.execute(facts)`，**无 fire 上限** | `fireAllRules(maxFires)` 硬熔断，防跑飞 | ⚠ `execute()` **不接受 maxFires 参数**——要拿 Step 14 的熔断能力，得改用有界 stateful `KieSession.fireAllRules(max)`，或挂一个"计数到阈值就 `halt()`"的 `AgendaEventListener`。这是决策路径与 `GuardService` 脱节的核心 | **P0** |
| E2 | 无挂钟超时打断 | watchdog 线程 `session.halt()`（Step 14 已验证，`halt()` 可跨线程调） | 无单请求超时兜底；慢规则会拖住线程 | **P0** |
| E3 | 无 `AgendaFilter` | `ReleaseAgendaFilter` 按 `@release` 元数据灰度/紧急下线（Step 14） | 无法运行时"编译进 KieBase 但不放行"，灰度只能靠全局开关 | **P1** |
| E4 | 生成的 DRL 用 `modify($c){...}`（discount-compute-amount 用 `amountComputed` 防重复触发）、ladder 用普通 `setComputedAmount` | 严守 CLAUDE.md 坑 3：不引入让 LHS 恒满足的 `update`/`modify` 死循环 | 当前生成模板受控、循环风险低，但**新增字段/新场景时无自动化死循环回归**；且无硬上限做最后一道兜底 | **P0**（护栏兜底）/ P1（回归测试） |
| E5 | 幂等键仅用于**写路径**（create 的 requestId） | 决策 API 也需要幂等键（若决策带副作用如库存占用） | 决策侧无 requestId 语义（纯查询可放宽，但要显式定义） | **P1** |

> **护栏落地要点**：决策路径要么改用**有界 stateful session**（`newKieSession` + `fireAllRules(maxFires)` + watchdog `halt()` + `AgendaFilter`，然后 `dispose()`），要么给 stateless session 挂一个 `AgendaEventListener` 在 `afterMatchFired` 计数超阈值时抛断路异常。前者能直接复用 Step 14 `GuardService` 三件套，代价是失去 stateless 的复用便利（但 KieBase 仍可复用）。**这是 codex-plan 必须先定的技术选择**。

### 2.4 发布与灰度（P0/P1）

| # | 现状 | 生产要求 | 差距 | 级别 |
| --- | --- | --- | --- | --- |
| R1 | 状态机只有 `ActivityStatus`：0 待上线/1 上线/2 下线/3 待生效 | 草稿→**审核**→发布→灰度→回滚完整状态机 | **无审核态、无审核人、无发布单**；上下线是直接改 status | **P0** |
| R2 | 灰度 = 单个全局布尔 `rule-engine.enabled`（整模块 all-or-nothing） | 按活动/版本灰度、按流量百分比放量、AB 分桶 | 无法"只灰度某活动的新版本"；无百分比放量 | **P0** |
| R3 | `traces[]` + `mode` 已能记录引擎/legacy 双结果 | 影子对比（shadow）：引擎与 legacy 同时算、比对、记差异、不影响线上返回 | 有**素材**（双路径都在），但无 shadow 执行框架 + 差异落库统计 | **P1** |
| R4 | 版本表已有（version/isDel），可定位历史版本 | 一键回滚到指定版本（切编译产物 + 切生效指针） | 数据能回溯，但**无"回滚编译产物"动作**（G3 关联） | **P0** |
| R5 | `resolveStrategy` 按 (bizLine, activityType=null, scene) 取兜底策略，`version` 变化触发 KieBase 重建（注释语义） | 策略版本与活动版本联动，发布可原子切 | 策略变更与活动发布未编排在同一发布单 | **P2** |

### 2.5 可观测（P1）

| # | 现状 | 生产要求 | 差距 | 级别 |
| --- | --- | --- | --- | --- |
| O1 | activity 模块**没接** `MeteredRuleListener`（它挂在 base 的 stateful demo 上；activity 用裸 `StatelessKieSession.execute`） | 决策执行打点：编译失败数、执行耗时、命中策略、回退次数、命中率 | **零业务指标**（只有 `traces[]` 随响应返回，无聚合） | **P1** |
| O2 | `pom` 已有 `spring-boot-actuator` + `micrometer-registry-prometheus`，`/actuator/prometheus` 已开 | 复用即可 | **基础设施已具备**，缺的是埋点 | — |
| O3 | `traces[]` 是 `List<String>`，随每次响应返回 | 结构化审计日志落库（谁/何时/命中什么/回退原因），供效果分析 | 无持久化审计、无决策快照、无效果分析数据源 | **P1** |
| O4 | 无 trace id / 跨边界追踪 | requestId/traceId 贯穿决策 → 引擎 → 回退 | 无链路追踪 | **P2** |
| O5 | CLAUDE.md/FINAL_PLAN 已警示：勿把 `activityId/spuId` 塞进无限基数 tag | 指标 tag 基数受控：`session`/`scene`/`strategy`/`mode` 可做 tag，`activityId/userId/spuId` **不进 tag**（进日志/trace） | 需在埋点设计时守住（`MeteredRuleListener` 现有 `rule` tag 基数=规则数，可控；但生成规则名如 `elig_reject_0` 会随活动数膨胀，**需注意**） | **P1** |

> ⚠ **基数陷阱（新发现）**：`ActivityDrlBuilder` 生成的规则名带序号（`elig_reject_0`、`ladder_3`、`discount-pick-max`）。若直接复用 `MeteredRuleListener` 的 `drools.rules.fired{rule}` tag，规则名会随活动/档位数量膨胀 → **tag 基数失控**。平台化埋点应改用**稳定的 scene/strategy 维度**做 tag，规则级明细进 trace/日志。

### 2.6 数据层（P1/P2）

| # | 现状 | 生产要求 | 差距 | 级别 |
| --- | --- | --- | --- | --- |
| D1 | `spring.jpa.hibernate.ddl-auto=update`（application.yml） | 显式 DDL + Flyway/Liquibase 版本化迁移 | **无迁移工具**；生产 ddl-auto=update 有漂移/误改风险 | **P1** |
| D2 | 索引已在实体 `@Index` 声明（`idx_am_aid_ver_del`/`idx_am_status_time`/`idx_am_request`/`idx_ac_aid_ver_scene`/绑定 `spu_id,effective,is_del` 等） | 生产按真实查询验证/补索引 | 索引设计**已具备雏形**，需按决策热查询（`findBySpuIdInAndEffectiveAndIsDel`）压测校验 | P2 |
| D3 | 长字段用 `@JdbcTypeCode(SqlTypes.LONGVARCHAR)`（`activity_condition.generated_drl`/`condition_tree_json`/`activity_manage.activity_rule`），已避开 CLAUDE.md 坑 7（`@Lob` MySQL 64KB 截断） | 保持；长 DRL/条件树用 longtext | **已正确规避**，扩展时继续用 `@JdbcTypeCode` 不用 `@Lob` | — |
| D4 | HikariCP 默认池，无调优；无读写分离 | 连接池按 QPS 调优；决策读走只读副本预留 | 无池调优、无读写分离接缝 | **P1** |
| D5 | H2/MySQL 双 profile，`createDatabaseIfNotExist=true` | 生产预建库 + 收紧账号权限（CLAUDE.md 已提示） | H2/MySQL 行为差异（时间/唯一键/长文本）需回归 | **P2** |
| D6 | 幂等 `request_id` 有索引但**未加唯一约束**（FINAL_PLAN 提到"当启用幂等时唯一"） | 幂等键唯一约束防并发双写落两行 | 高并发下同 requestId 可能落两行（`findFirst` 读时序） | **P1** |

### 2.7 安全与权限（P0）

| # | 现状 | 生产要求 | 差距 | 级别 |
| --- | --- | --- | --- | --- |
| S1 | **完全无鉴权**（controller 无任何 auth） | 控制台 RBAC（运营/审核/管理员）；决策 API 服务间鉴权（API Key/mTLS） | 无 authn/authz；`pom` 无 spring-security | **P0** |
| S2 | 条件树白名单（`RuleField` 6 字段 + 每字段允许运算符 + fail-closed）+ 深度限制 + 转义 | 保持并评估覆盖度 | **安全边界设计优秀**，但字段仅 6 个（orderAmount/quantity/userDistrictId/userTags/spuId/storeId），生产字段面会扩，扩时须守住"白名单 + 值形状 + 转义"三件套，**且注意 `IN/CONTAINS` 列表长度无上限**（可能被超长列表放大编译成本） | **P1**（覆盖度/DoS 边界） |
| S3 | 绝不接受裸 DRL（原则已确立，翻译层强制） | 保持 | **已具备**，是核心资产 | — |
| S4 | 无操作审计（谁改了活动/发布/回滚） | 全量操作审计（actor/action/before/after/time） | 无审计表 | **P0**（合规/追责） |
| S5 | 决策 API 入参无鉴权/无配额 | 每调用方鉴权 + 配额 + 限流 | 无（与 P5 关联） | **P1** |

---

## 3. 目标架构选项（3 个方案对比）

评分：1（差）～5（好）。维度：**正确性风险控制 / 复杂度（越低越好，用"简洁性"评分）/ 性能与可扩展 / 可维护 / 演进性**。

### 方案 A：单体演进（模块化单体，最小改动）

在现有单 Spring Boot 应用内，把 activity 模块内部切成三个清晰子模块（**配置服务 / 决策服务 / 规则编译服务**）+ 补齐护栏/可观测/RBAC/Flyway，**部署形态不变**（一个进程一份部署）。

```
┌──────────────────────── 单进程 Spring Boot ────────────────────────┐
│  运营控制台 API ─┐                    ┌─ 电商决策 API                 │
│  (create/status) │                    │  (spu-discount/gifts)        │
│        ▼         │                    │        ▼                     │
│  ConfigService   │   共享             │  DecisionService             │
│  (写/版本/审核)   │   ┌────────────┐   │  (读/护栏/回退)               │
│        └─────────┼──▶│ RuleCompile │◀──┼────────┘                    │
│                  │   │  Service    │   │                             │
│                  │   │ (产物+版本)  │   │                             │
│                  │   └────────────┘   │                             │
│              共享 MySQL（10+ 表）+ 进程内缓存                          │
└─────────────────────────────────────────────────────────────────────┘
```

| 维度 | 分 | 说明 |
| --- | --- | --- |
| 正确性风控 | 4 | 复用现有事务/回退，改动面小，回归风险低 |
| 简洁性 | 5 | 部署最简，运维认知负担最低，最快上线 |
| 性能可扩展 | 2 | 配置面和决策面共享进程/DB/JVM，**无法独立扩缩容**；决策 QPS 会被控制台大事务/GC 干扰 |
| 可维护 | 4 | 单代码库，边界靠包结构约束（需纪律） |
| 演进性 | 3 | 若模块边界干净，二期可拆；否则易退化成大泥球 |
| **适用** | | MVP 早期、单团队、QPS 不高、要快速验证业务闭环 |

### 方案 B：配置面 / 决策面分离（两个部署单元）

**控制台/配置 API**（写重、低 QPS、面向运营）与**高可用决策服务**（读重、高 QPS、面向电商）拆成两个部署单元。规则产物通过**发布仓库**同步（KieScanner/KJAR 思路，或"编译产物表 + 版本指针 + 发布事件"）。

```
┌─── 配置面（控制台）───┐       发布产物       ┌─── 决策面（水平扩展）───┐
│ ConfigService        │   ┌─────────────┐   │  DecisionService × N     │
│ 创建/版本/审核/发布   │──▶│ 规则产物仓库  │◀──│  护栏(max/halt/filter)   │
│ (写 MySQL 主库)       │   │ (版本指针/    │   │  fail-safe 回退          │
│                      │   │  KJAR/表)    │   │  (读 MySQL 从库/缓存)     │
└──────────────────────┘   └─────────────┘   └──────────────────────────┘
        低 QPS，可用性一般              高 QPS，5 个 9，独立扩缩容
```

| 维度 | 分 | 说明 |
| --- | --- | --- |
| 正确性风控 | 4 | 决策面隔离，控制台故障不影响线上决策；但引入"产物同步一致性"新正确性面 |
| 简洁性 | 3 | 两个部署单元 + 发布同步机制，运维复杂度上升 |
| 性能可扩展 | 5 | 决策服务无状态水平扩展，独立扩缩容，负载隔离 |
| 可维护 | 4 | 职责物理隔离，边界强制；但两库/两部署 |
| 演进性 | 5 | 天然对齐"决策中台"形态，二期多租户/多业务线易扩 |
| **适用** | | 决策 QPS 上量、要独立 SLA、发布节奏与代码解耦 |

### 方案 C：规则产物仓库 + 决策服务水平扩展 + 缓存层（B 加强版）

在 B 基础上，加**专用规则产物仓库**（KJAR 制品库或编译产物表）+ **Redis/多级缓存**（活动配置快照 + 预编译 KieBase 暖池）+ 决策服务 LB 后水平扩展 + 发布事件驱动缓存失效。

```
控制台 → 配置API → [MySQL主] → 发布 → [规则产物仓库 + 发布事件(MQ/轮询)]
                                              │
                    ┌─────────────────────────┼─────────────────────────┐
                 决策A ← Redis(配置快照+暖池)  决策B                     决策C
                    └──────────── LB / 网关(限流·鉴权·配额) ─────────────┘
                                              │
                                      [MySQL 只读副本]
```

| 维度 | 分 | 说明 |
| --- | --- | --- |
| 正确性风控 | 3 | 缓存一致性/失效、产物同步是新的 bug 温床，需强测试 |
| 简洁性 | 2 | 组件最多（缓存/MQ/制品库/网关），运维最重 |
| 性能可扩展 | 5 | P99 最优，抗击穿，横向无上限 |
| 可维护 | 3 | 分布式复杂度高，排障跨组件 |
| 演进性 | 5 | 直达"生产级决策中台"终态，支撑多租户 SaaS |
| **适用** | | 高并发、强 SLA、二期多租户；**MVP 阶段是过度设计** |

### 3.1 推荐

> **推荐：以方案 B 为最终目标形态，MVP 分两步落地——先做"方案 A 的部署 + B 的模块边界与规则产物接缝"，二期按 QPS 物理拆出决策服务。**

**一句话理由**：决策 API（高 QPS、5 个 9、发布解耦）和运营控制台（低 QPS、写重、可用性一般）的负载画像与可用性要求根本不同，最终必须能独立扩缩容（=B）；但单团队 MVP 阶段一上来就物理拆分是过度设计（=C 的坑），所以先用**可拆分的接缝**（规则产物仓库 + 版本指针 + 决策服务无 DB 写依赖）换取可逆性，把"拆"降级成一次部署变更而非重写。

**落地路径**：
1. **MVP（模块化单体，形态 A）**：activity 模块内切 `ConfigService`（现 `ActivityMarketingService`）/ `DecisionService`（现 `ActivityQueryService`）/ `RuleCompileService`（改造 `ActivityRuleRuntimeService`：缓存 key 改 `activityId:version`、加发布预热、原子换版本指针）。补齐：**决策护栏（复用 Step 14）、Micrometer 埋点（复用 Step 15，注意基数）、RBAC + 操作审计、Flyway、幂等唯一约束**。决策服务对外契约（第 4 节）从第一天就设计成"可独立部署"。
2. **二期（拆分，形态 B）**：把 `DecisionService` + 决策 controller 抽成独立部署单元，只依赖"规则产物仓库 + MySQL 只读"；配置面继续持有写库。产物同步先用"版本指针表 + 发布事件（应用事件/轮询）"，规则发版频繁再评估切 Step 16 KJAR/KieScanner。按需加 C 的缓存层。

**为什么不直接上 C**：C 的缓存/MQ/制品库/网关一致性面会淹没单团队 MVP，违背"每个抽象必须自证复杂度"。缓存层、只读副本、MQ 都应在**压测暴露出 DB/延迟瓶颈后**按需引入，而不是预置。

---

## 4. 决策 API 契约草案（面向电商的实时决策接口）

> 目标：给电商系统一个稳定、可版本化、带护栏语义的实时决策接口。以现有 `SpuDiscountRequest` / `DiscountView` / `GiftView` 为基线**收敛升级**，字段级具体化。这份契约同时喂给前端与后续架构规划。

### 4.1 端点

```
POST /decision/v1/evaluate        统一决策入口（资格 + 折扣 + 阶梯 + 买赠一次算完）
POST /decision/v1/spu-discount    (兼容现 /activity-marketing/spu-discount，红包/折扣)
POST /decision/v1/gifts           (兼容现 /activity-marketing/gifts，买赠)
```

MVP 可先保留现有 `/activity-marketing/spu-discount` 与 `/gifts` 形态，新增 `/decision/v1/evaluate` 作为统一契约演进目标。

### 4.2 请求

**Headers**
| Header | 必填 | 说明 |
| --- | --- | --- |
| `X-Api-Key` / `Authorization` | 是 | 调用方（电商系统）身份，服务间鉴权（S1/S5） |
| `X-Request-Id` | 建议 | 幂等/去重 + 日志关联；纯查询下用于同请求稳定返回 |
| `X-Trace-Id` | 建议 | 跨服务链路追踪（O4） |

**Body（`application/json`）**
| 字段 | 类型 | 必填 | 说明 | 现有对应 |
| --- | --- | --- | --- | --- |
| `scene` | string enum | 是 | `SPU_DISCOUNT` / `BUY_AND_GET_GIFT`（预留 `COUPON`…） | 现按接口区分 |
| `bizLine` | string | 是 | 业务线，定位策略/活动范围 | `ActivityRuleContext.bizLine` |
| `user` | object | 是 | 用户上下文（资格条件来源） | — |
| `user.userId` | long | 是 | 用户 id（进日志/trace，**不进指标 tag**） | `SpuDiscountRequest.userId` |
| `user.districtId` | string | 否 | 用户地域（`USER_DISTRICT` 条件） | `userDistrictId` |
| `user.tags` | string[] | 否 | 用户标签（`USER_TAGS` 条件，`contains/containsAny`） | `userTags` |
| `cart` | object | 是 | 购物车/订单上下文 | — |
| `cart.spuIdList` | long[] | 是* | 商品 SPU 列表（*与 `items` 二选一） | `spuIdList` |
| `cart.items` | object[] | 否 | 明细行 `{spuId, skuId, storeId, price, quantity, categoryId}`（为将来按行计价预留） | 现无（扩展点） |
| `cart.orderAmount` | decimal | 否 | 订单金额（阶梯落档 + `ORDER_AMOUNT` 条件） | `orderAmount` |
| `cart.quantity` | int | 否 | 数量（`QUANTITY` 条件） | `quantity` |
| `options` | object | 否 | 决策选项 | — |
| `options.explain` | bool | 否 | 是否返回 `trace`（默认 false，减负载） | 现恒返回 traces |
| `options.dryRun` | bool | 否 | 影子模式：算但标记"不作数"，用于灰度对比（R3） | 现无 |
| `options.pinVersion` | string | 否 | 指定生效规则集版本（灰度/回归复现，默认取当前发布版本） | 现无 |
| `options.timeoutMs` | int | 否 | 单请求超时上限（默认平台配置，触发 `halt()` 降级，P5/E2） | 现无 |

### 4.3 响应

**成功（HTTP 200）**
| 字段 | 类型 | 说明 | 现有对应 |
| --- | --- | --- | --- |
| `requestId` / `traceId` | string | 回带，便于对账/追踪 | — |
| `decisionVersion` | string | **本次决策使用的规则集/配置快照版本号**（回滚/复现/AB 关键） | 现无（P0 需新建） |
| `engineMode` | string enum | `rule-engine` / `legacy` / `degraded` | `DiscountView.mode`（现仅前两者） |
| `degraded` | bool | 是否降级（超时/编译失败/空决策回退） | 现无显式字段 |
| `degradeReason` | string? | 降级原因（`timeout`/`compile_error`/`empty_decision`/`switch_off`） | 现在 traces 里 |
| `hit` | bool | 是否命中任一活动 | `DiscountView.hit` |
| `hitActivities` | object[] | 命中活动明细 | 现仅单个 hit* 字段 |
| `hitActivities[].activityId` | string | 活动 id | `hitActivityId` |
| `hitActivities[].activityName` | string | 活动名 | `hitActivityName` |
| `hitActivities[].activityType` | int | 类型（1 红包/5 买赠…） | — |
| `hitActivities[].version` | int | 命中活动版本 | `ActivityCandidate.version`（已有，未透出） |
| `hitActivities[].strategy` | string enum | `MAX/MUTEX/STACK/PRIORITY` | `DiscountView.strategy` |
| `hitActivities[].computedAmount` | decimal | 该活动优惠金额 | `ActivityCandidate.computedAmount` |
| `hitActivities[].priority` | int | 碰撞优先级 | `ActivityCandidate.priority` |
| `discount` | object | 优惠汇总 | — |
| `discount.finalAmount` | decimal | 最终优惠金额（策略合并后） | `DiscountView.hitAmount` |
| `discount.strategy` | string enum | 生效的合并策略 | `DiscountView.strategy` |
| `discount.currency` | string | 币种（默认 CNY，预留） | 现无 |
| `gifts` | object[] | 赠品（买赠场景） | `GiftView.gifts` |
| `gifts[]` | object | `{activityId, batchId, giftName, giftType, giftNum, absoluteAmount, rightType}` | `GiftResult` |
| `trace` | string[]? | 规则诊断（`explain=true` 时返回） | `DiscountView.traces` |
| `timings` | object | `{totalMs, engineMs}` 便于调用方观测 | 现无 |

### 4.4 语义约定

- **幂等**：纯查询语义下同 `X-Request-Id + 同入参` 应返回**稳定结果**（同一 `decisionVersion` 下确定性）。若未来决策带副作用（库存占用/发券），幂等键必须去重防重复发放（参考写路径 `requestId` 机制 + 唯一约束 D6）。
- **超时/降级**：引擎受 `options.timeoutMs`（或平台默认）约束，超时经 watchdog `halt()`（E2）→ 降级到 `legacy`（取最大红包），`degraded=true` + `degradeReason=timeout`，**HTTP 仍 200（fail-open 保可用）**。可按调用方配置成 fail-closed（返回空优惠而非旧逻辑）——**这条要在 codex-plan 明确默认策略**。
- **护栏**：`fireAllRules(maxFires)`/`halt()`/`AgendaFilter` 保护每次决策（E1–E3）；跑飞/编译失败 → 降级而非 500。
- **限流**：网关/入口按 `X-Api-Key` 配额限流，超限 **429**（P5）。
- **鉴权**：无效凭证 **401/403**（S1）。
- **错误码**：入参非法 **400**（沿用现有约定）；限流 429；鉴权 401/403；**引擎故障不返回 5xx，一律降级 200 + `degraded`**（决策 API 的可用性优先于精确性，与现有 fail-safe 一致）。

---

## 5. 关键技术风险与预研点（喂给 codex-plan 深挖）

按"最该先攻"排序：

### R1（P0）决策路径引擎护栏落地方式 —— 首要攻坚
- **问题**：决策现用 `StatelessKieSession.execute(facts)`，**无 `fireAllRules(max)` 入口**，与 Step 14 `GuardService`（stateful `fireAllRules(maxFires)` + watchdog `halt()` + `ReleaseAgendaFilter`）脱节。
- **预研**：(a) 改用有界 stateful `KieSession`（`newKieSession` + `fireAllRules(maxFires)` + watchdog `halt()` + `dispose()`，KieBase 仍复用）——直接吃到 Step 14 三件套，代价是失去 stateless 复用便利、每请求建/销 session；vs (b) 给 stateless session 挂 `AgendaEventListener`，`afterMatchFired` 计数超阈值抛断路异常。**要压测两者延迟差**。
- **关联**：CLAUDE.md 坑 3（`modify/update` 死循环）——护栏是最后兜底，还需 R6 的模板死循环回归。

### R2（P0）规则产物仓库 + 原子发布/回滚 + 版本指针缓存
- **问题**：缓存无界（key=DRL 全文）、冷编译在请求线路、无原子发布、无回滚编译产物。
- **预研**：设计"编译产物/发布指针"模型——缓存 key 改 `bizLine:activityId:version`（不用全文）；发布动作 = 预编译 + 原子换指针 + 旧产物留存可回滚；缓存 miss 用 single-flight 防击穿。**KJAR/KieScanner（Step 16，重依赖 `kie-ci`）vs 内容缓存+版本指针（Step 9 风格）的取舍**（见 2.1）：MVP 倾向后者，需论证多实例一致性方案（发布事件 vs 版本号轮询）。

### R3（P0/P1）灰度/影子/AB 发布机制
- **问题**：现只有全局布尔开关，无按活动/版本灰度、无百分比放量、无影子对比。
- **预研**：把开关升级为**版本指针路由 + 百分比分桶 + 影子执行**：`options.pinVersion` + 分桶哈希（userId/requestId）决定走哪个规则版本；影子模式（`dryRun`）同时跑新版本与线上版本、比对 `hitActivities/finalAmount`、差异落审计（复用现有 `traces[]`/`mode` 双路径素材）。评估 `ReleaseAgendaFilter`（`@release` 元数据）能否承接"编译进 KieBase 但不放行"的紧急下线。审核态状态机（R1）。

### R4（P1）决策热路径性能与 P99 预算
- **问题**：一次决策 6+ DB 读 + 3 趟引擎，无配置缓存、无预热。
- **预研**：活动配置快照缓存（进程内→按需 Redis）；合并/减少引擎趟数；发布预热常用活动编译产物；定 P99 目标（如 ≤50ms）并压测冷/热启动尖刺；连接池调优 + 只读副本接缝（D4）。

### R5（P1）可观测埋点与指标基数控制
- **问题**：`MeteredRuleListener` 未接入；直接复用会因**生成规则名（`elig_reject_N`/`ladder_N`）导致 `rule` tag 基数爆炸**。
- **预研**：埋点用稳定维度（`scene`/`strategy`/`mode`/`bizLine` 有限枚举）做 tag，`activityId/userId/spuId` 只进 trace/日志；补指标：编译失败数、决策耗时 Timer、命中策略分布、回退次数、命中率；设计决策审计表（效果分析数据源，可借鉴 Step 10 快照思路）。

### R6（P1）生成 DRL 模板的死循环/正确性回归 + 白名单 DoS 边界
- **问题**：新增字段/场景时 `ActivityDrlBuilder` 模板可能触发 CLAUDE.md 坑 3；`RuleConditionTranslator` 的 `IN/CONTAINS_ANY` 列表无长度上限。
- **预研**：为生成模板建自动化冒烟（DRL 运行时编译，`mvn compile` 不校验语法——坑 4/6，必须真跑一次）；给 `IN/BETWEEN/CONTAINS_ANY` 列表长度与条件树节点总数设上限，防超长条件放大编译/执行成本（DoS 边界，关联 S2）。

### R7（P0，横切）鉴权/RBAC/审计接入
- **问题**：零鉴权、零操作审计。
- **预研**：控制台 RBAC（运营/审核/管理员三角色，对齐发布状态机 R1）；决策 API 服务间鉴权（API Key/mTLS）+ 配额；全量操作审计（actor/action/before/after）。评估引入 spring-security 的改动面（现 `pom` 无）。

---

## 附：现状 → 目标 能力矩阵（速查）

| 能力 | 现状 | 目标（MVP） | 分级 |
| --- | --- | --- | --- |
| 版本化编辑 | 【已具备】version+isDel | 保持 + 审核态 | P0(审核) |
| 幂等（写） | 【已具备】requestId | + 唯一约束 | P1 |
| 并发保护（写） | 【已具备】affected 行数 | 保持 | — |
| 条件树白名单 | 【已具备】RuleField+翻译层 | + 列表长度/节点上限 | P1 |
| fail-safe 回退 | 【已具备】legacyMax | + 显式 degraded 语义 | P0 |
| 灰度 | 【需改造】全局布尔 | 版本路由+百分比+影子 | P0 |
| 引擎护栏 | 【需新建】决策路径无 | 复用 Step 14 三件套 | P0 |
| 编译产物仓库 | 【需改造】无界内容缓存 | 版本指针+预热+原子发布/回滚 | P0 |
| 决策性能 | 【需改造】6+读/3趟/无缓存 | 配置快照缓存+预热+P99预算 | P0/P1 |
| 可观测 | 【需新建】仅 traces | 复用 Step 15 + 审计落库 | P1 |
| 迁移工具 | 【需新建】ddl-auto=update | Flyway | P1 |
| 鉴权/RBAC/审计 | 【需新建】零鉴权 | RBAC + 服务间鉴权 + 操作审计 | P0 |
| 决策 API 契约 | 【需改造】DiscountView | /decision/v1 版本化契约 | P0 |

> 备注：本文所有结论基于对 `com.lrj.drools.activity` 全量源码（controller/service/engine/persistence/domain）、`config/DroolsConfig`、`META-INF/kmodule.xml`、`application.yml`、`pom.xml` 及 base 项目 Step 9/10/14/15/16 复用类（`HotReloadService`/`LoyaltyService`/`GuardService`/`ReleaseAgendaFilter`/`MeteredRuleListener`/`ScannerService`）的直接阅读。已严格对齐 CLAUDE.md 已踩的坑（update 死循环、DRL 运行时编译、`@Lob` MySQL 截断、kmodule 约定、kie-ci 重依赖）。
</content>
</invoke>
