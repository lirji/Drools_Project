# 活动引擎平台生产级后端架构 FINAL PLAN

## 1. 背景、目标与非目标

现有 `com.lrj.drools.activity` 已能演示红包、阶梯、买赠、资格条件、合并策略和商品池，但其发布事实仍是“数据库最新未删行”，决策热路径有多表 N+1、请求冷编译、无 fire/超时护栏、无审核/灰度/回滚、无鉴权与效果日志。目标是在不丢失现有业务语义和 `version+1/requestId/409` 基座的前提下，形成“决策中台 + 运营控制台”，并让 MVP 的单体部署可在二期无协议重写地拆成配置面与决策面。

MVP 目标：可视化配置；`/decision/v1/evaluate` 实时 API 与硬护栏；不可变版本、审核、百分比灰度、影子、全量与回滚；低基数指标和效果分析。

非目标：多租户 SaaS、券/秒杀/拼团等新玩法、库存/预算强一致扣减、发券与赠品履约、运营 DRL、事件溯源、首期 Redis/MQ/Nexus、没有电商回传时的核销/GMV 归因。

## 2. 已确认业务规则

- MVP 只支持现有 `RED_PACKAGE(1)`、`BUY_AND_GET(5)`；其它 `ActivityType` 继续拒绝。
- 运营输入只能是 `ConditionNode`，由 `RuleField`/`RuleOperator` 白名单和 `RuleConditionTranslator` 转成受控约束。
- 时间范围包含开始和结束；阶梯区间 `[min,max)`；空资格条件恒通过。
- `MAX` 最高金额；`MUTEX/PRIORITY` priority 数值最小优先，同优先级金额高者优先；`STACK` 累加。
- 商品池 AUTO 绑定按目标态 diff，不修改手工绑定；发布时冻结绑定结果。
- 库存字段只配置/展示，不在 MVP 扣减。
- 编辑 `version+1`；同预期版本并发只允许一个成功，其他 409；`requestId` 语义保留并由数据库唯一记录加固。
- 引擎故障不阻塞下单。迁移期旧 API 维持当前 legacy MAX；新 API 默认返回 `NO_PROMOTION` 降级结果以避免忽略资格导致超发。该默认值必须由产品/财务在上线前确认，可由受控配置切换。

默认但待业务确认：单级审核且强制职责分离；灰度按 userId；P99 < 50ms、可用性 ≥99.9%；回滚到上一个稳定产物；效果日志留存和身份源见第 12 节。

## 3. 当前代码与调用链分析

### 3.1 配置写路径

`ActivityMarketingController#create` → `ActivityMarketingService#create`：`validateCommon` → 应用层 requestId 查询 → 编辑时 `ActivityManageRepository#softDeleteVersion` → 条件翻译/DRL 写前编译 → 同事务保存 manage/rule/condition/gift/binding/poolRef/strategy。优点是编译失败不落库、version 冲突为 409；缺点是 requestId 没有唯一约束、旧版本仅 manage 行 `is_del=1`、状态 `changeStatus` 可任意跳转、发布与配置编辑没有分离。

### 3.2 决策路径

`ActivityMarketingController#spuDiscount` → `ActivityQueryService#spuDiscount`：SPU 查 binding → 每 activity 查最新 manage → 每候选查 rule/condition → 查 strategy → 分别 `evalEligibility/evalLadder/evalDiscount`。买赠走独立 `buyAndGetGifts`，当前没有执行资格。正常折扣最多 6+ DB 读和 3 次规则执行。

`ActivityRuleRuntimeService` 以 DRL 全文为 key 放入无界 `ConcurrentHashMap`；`safeRun` 在请求中生成/冷编译；`run` 用 `StatelessKieSession.execute`，没有 `fireAllRules(max)`、持有 session 的 watchdog 或 `AgendaFilter`。

### 3.3 可复用资产与必须修正项

复用：`RuleConditionTranslator`、`ActivityDrlBuilder`、`LadderRangeParser`、现有 fact/result、`ActivityPoolMatchService` diff、写前编译、version+1/409、Step 14 stateful 三件套思路、Micrometer/Prometheus 基础设施。

修正：买赠资格；阶梯金额可能被 discount compute 以固定额覆盖；同额 MAX 和同 priority 需稳定 tie-break；条件总节点/列表无上限；activityId 多实例唯一性；绑定查询未按版本；legacy 全局关闭会忽略资格；生成规则名不能作为 metric tag；`saveStrategyIfPresent` 会在保存任一活动时直接 upsert 业务线全局策略，绕开独立版本与审核。

## 4. 候选方案与评分

评分 1～5，风险/复杂度/测试/回滚维度 5 表示更低成本；权重：正确性 25%、改动风险 15%、复杂度 10%、可维护性 15%、扩展性 15%、测试难度 10%、回滚成本 10%。

| 方案 | 核心 | 正确性 | 改动风险 | 复杂度 | 维护 | 扩展 | 测试 | 回滚 | 加权 |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| A | 原表直读加固单体 | 2 | 4 | 5 | 2 | 2 | 4 | 2 | 2.85 |
| B | 内容 artifact + 版本指针，可拆分单体 | 5 | 3 | 3 | 4 | 4 | 3 | 5 | **4.05** |
| C | KJAR/KieScanner 双部署 | 4 | 2 | 2 | 3 | 5 | 2 | 4 | 3.30 |
| D | 事件溯源 + Redis/MQ | 4 | 1 | 1 | 2 | 5 | 1 | 4 | 2.75 |

A 无法保证原子发布和历史回滚；C 的 KJAR 不能单独封装 binding/gift/strategy 且首期需制品基础设施；D 与现有 CRUD/version 基座冲突。选择 B，同时吸收 A 的单体部署、C 的不可变版本/预拉取再激活、D 的 outbox/不可变审计。

## 5. 最终架构

### 5.1 逻辑边界

```text
运营控制台
  -> ConfigController -> ActivityMarketingService(草稿聚合写)
  -> WorkflowService(提交/审核)
  -> ReleaseController -> ArtifactBuilder -> ArtifactRepository
                                      -> ReleaseService(pointer CAS + outbox + audit)
                                                        |
电商 -> DecisionController -> DecisionService -> ReleaseRouter
                                      -> DecisionSnapshotRegistry/cache
                                      -> GuardedRuleExecutor(KieBase复用, session按请求)
                                      -> 非阻塞 decision event / shadow compare
```

配置表不再直接决定线上生效。第一层发布物是指定 `activityId+version` 的不可变内容 artifact，包含规范化后的基础信息、规则、资格条件、冻结绑定、赠品和各场景生成 DRL。第二层发布物是业务线 release manifest：原子冻结有序 artifact 集合与唯一合并策略快照。stable/canary/shadow 指针只指向 manifest。决策注册表从 active manifest 构建 `bizLine + spuId -> activity release handle` 本地只读索引，普通请求不读配置表、不冷编译。

MVP 同 JVM、同 MySQL；包边界与接口禁止 decision 依赖配置 Repository。二期只移动 `decision`、`engine`、release consumer，并把 outbox transport 换成 MQ；API、artifact schema 与 pointer 语义不变。

### 5.2 引擎护栏裁决

生产默认使用 stateful `KieSession`，每个场景/请求新建并 finally dispose；KieBase 按 artifact/scene 复用，不池化 session。

`GuardedRuleExecutor#execute` 固定顺序：

1. 从预热 `ArtifactRuntime` 取 scene KieBase；禁止普通请求编译。
2. `newKieSession`，挂低基数计数 listener，设置 global，插入 ctx/candidates。
3. 创建每请求 `ReleaseAgendaFilter`；共享 ScheduledExecutor 安排 `session.halt()`，不得每请求 new executor。
4. 调用 `fireAllRules(AgendaFilter agendaFilter, int max)`（**Claude 复核已确认，从「待验证」升级**：Drools 8.44.2 的 `GuardService` 已分别实证 `fireAllRules(maxFires)`（`service/GuardService.java:48`）、`fireAllRules(filter)`（`:110`）与 watchdog `session.halt()`（`:84`）；合并重载 `fireAllRules(AgendaFilter,int)` 是 `StatefulRuleSession` 标准 API，可直接使用。且这三件护栏**只存在于 stateful `KieSession`**——决策路径现用 `StatelessKieSession.execute`（`activity/engine/ActivityRuleRuntimeService.java:120`）无任何护栏入口，故「决策路径改用有界 stateful session」是护栏落地的**硬前提，非可选优化**）。保留组合 AgendaFilter + `fireAllRules(max)` 作为等价兜底，任何情况下不得退回无上限。
5. 记录 fired/elapsed/limit/timeout，取消 watchdog，dispose。
6. 单次决策跨场景维护累计 fire budget；任一触限/异常即走降级，不继续消耗预算。

`halt()` 只能在当前 activation 结束后生效，所以还必须限制条件节点、候选、分档、字符串和列表，保持 RHS 为平台受控短操作，并以 engine bulkhead 隔离。stateless + listener 只做压测对照；除非在当前 Drools 版本证明中断、清理、并发和性能全部等价，否则不准切换。

MVP 先保持资格→阶梯→折扣→赠品的分阶段语义，每阶段使用预编译 KieBase；统一资格结果传给折扣和赠品，修复现有买赠绕过资格。单 KieBase 合并执行只有在回归和压测证明收益后另立 ADR。

### 5.3 artifact、manifest、发布和缓存

- ArtifactBuilder 从指定版本读取现有多表，不使用“最新未删”隐式查询；字段按稳定顺序规范化，重新翻译条件树并与存量 generatedDrl 校验。
- Artifact 内容保存 `schemaVersion/templateVersion/checksum/contentJson`；KieBase 不做 Java 序列化持久化，实例按内容受控编译并缓存。
- ManifestBuilder 读取当前 stable manifest，用候选 artifact 替换/新增/移除指定活动，并引用一个显式 APPROVED 的 immutable strategy version；manifest item 稳定排序并计算 checksum。这样同一请求不会出现“活动 v2 + 另一活动 v1 + 未审核/冲突策略”。
- artifact/manifest 状态均为 `PREPARING -> PREPARED -> RETIRED/INVALID`；pointer 只能引用 PREPARED manifest，manifest 只能引用 PREPARED artifact。
- 构建/编译在短事务外；manifest pointer CAS、generation+1、outbox、workflow 发布态和操作审计在一个事务内。
- 缓存 key 为 `artifactId:scene:templateVersion`，容量/权重受限，single-flight；启动和发布预热。缓存恢复失败直接使用最后已加载 stable 或降级，不能把冷编译打进热请求。
- 在途请求持有 immutable runtime 引用，指针切换不打断它；旧 artifact 至少保留回滚窗口和最大请求时长。

### 5.4 多实例一致性

MySQL pointer 是事实源，主键是 `release_key`（MVP 等于请求必填的 `bizLine`）。事务 outbox 发送 `ReleasePointerChangedV1`；实例按 releaseKey+generation 幂等应用，事件丢失由短轮询补偿，旧事件不能覆盖新 generation。响应 `decisionVersion` 使用本次选中 manifest 的 `manifestId:generation`，内部日志另存 manifest item；不再把多个活动版本拼成一个含义模糊的数字。

MVP 承诺发布收敛窗口可监控、可暂停放量，不承诺零毫秒全实例同时切换。二期若要求更严，增加 PREPARING readiness ACK + ACTIVATE 两阶段；仍会有传播窗口，真正零窗口需网关按 generation 路由，届时单独决策。

### 5.5 灰度、影子和 A/B

- pointer 每个 releaseKey 保存 stable/canary/shadow manifest、basisPoints、experimentId、salt、routingAlgoVersion、generation。灰度单个活动时，candidate manifest 与 stable manifest 仅该 item 不同。
- `SHA-256(experimentId|releaseKey|salt|bucketKey)` 的固定测试向量决定 0..9999；bucketKey 优先 userId，其次 requestId，均无则 stable。
- 调整比例不改 salt；0/10000 等价稳定边界；重复请求和跨实例结果一致。
- shadow 始终返回主路线结果，使用有界独立执行器、短超时、采样和 Abort/drop 策略，禁止 CallerRuns；差异事件不包含未脱敏 PII。
- A/B 固定实验双方、salt、窗口与指标；canary 是发布过程，不能把滚动放量数据当严格 A/B 结论。
- `ReleaseAgendaFilter` 只控制 artifact 内带 `@release` 的规则或紧急禁用；用户流量路由在 artifact 层。
- 全局 `activity.marketing.rule-engine.enabled` 保留为 kill switch，但不再承担灰度。

## 6. 精确修改清单

“新增”均为计划目标，当前仓库不存在；执行 Agent 应按下列路径创建，不得把它们误判为现有类。

### 6.1 现有文件/方法

- `pom.xml`：加入 Flyway、Spring Security、缓存（优先 Caffeine）和 Testcontainers；JMH/Gatling 依团队标准二选一（**待验证**）。
- `application*.yml`：生产 `ddl-auto=validate`，配置第 8 节参数；H2 测试可保留 create-drop，MySQL 禁止自动建库/关闭 SSL 默认值。
- `ActivityMarketingController`：旧端点改委托 adapter；`status` 禁止直接任意 set；`fieldDict` 增加 workflow 字典但不泄露 DRL。
- `ActivityMarketingService#create/updateByVersion`：通过 `ActivityIdempotencyService` 领取 requestId；使用全局唯一 activityId；创建 workflow DRAFT；保留 `softDeleteVersion` 409。
- `ActivityMarketingService#changeStatus`：标记 deprecated，仅做旧状态到命令的受控映射；非法边 409。
- `ActivityMarketingService#saveStrategyIfPresent`：停止在活动保存时静默 upsert全局策略；兼容请求交给新 `ActivityStrategyService`，相同策略复用当前 APPROVED 版本，变化则创建独立 DRAFT 并随发布单审核。
- `ActivityQueryService#spuDiscount/buyAndGetGifts`：变成 `ActivityDecisionService` 兼容 adapter；移除热路径 Repository 组装和冷编译；买赠使用资格结果。
- `ActivityPoolMatchService#refreshActivityBinding`：增加 expected version/锁校验；ArtifactBuilder 冻结其结果。
- `ActivityRuleRuntimeService#compileOrGet/safeRun/run`：拆成发布编译和 guarded execute；移除 DRL 全文无界 map。
- `ActivityDrlBuilder`：增加模板版本、稳定排序/tie-break、release metadata；修复 ladder computedAmount 被固定额覆盖。
- `RuleConditionTranslator#translate`：增加节点/列表/字符串/数值硬限制和可测试计数器。
- `LadderRangeParser#parse`：严格发布校验重叠、排序、金额范围；兼容预览可返回结构化错误。
- `ActivityStatus`：保留旧 code；新增工作流不得复用其四个值冒充审核态。
- `ActivityRuleResult`/`SpuDiscountRequest`：旧模型保持兼容，新 API 使用独立 DTO。
- `ActivityManageRepository`：补显式按 `activityId+version` 读取历史的只读方法；`softDeleteVersion` 保留。

### 6.2 新增类与关键方法

- `activity/config/ActivityConfigController`：`createDraft`、`updateDraft`、`getVersionDiff`。
- `activity/config/ActivityWorkflowService`：`submit`、`approve`、`reject`、`assertTransition`。
- `activity/config/ActivityStrategyService`：`createDraft`、`submit`、`approve`、`resolveApprovedVersion`，以 bizLine+scene+version 管理不可变策略。
- `activity/config/ActivityIdempotencyService`：`claim(scope,requestId,payloadHash)`、`complete`、`fail`。
- `activity/domain/ActivityLifecycleState`：工作流枚举。
- `activity/release/ActivityReleaseController`：`prepare`、`startCanary`、`changeTraffic`、`promote`、`rollback`、`offline`。
- `activity/release/ActivityArtifactBuilder#build(activityId,version)`：规范化内容、checksum、compile verify、binding rows。
- `activity/release/ActivityReleaseManifestBuilder#build(releaseKey,changes)`：稳定排序 item、冻结唯一策略、manifest checksum。
- `activity/release/ActivityReleaseService#activate(command)`：状态校验、manifest pointer CAS、outbox/audit；`rollback(command)` 只选 PREPARED 历史 manifest。
- `activity/release/ReleaseRouter#route(pointer,bucketKey)`：stable/canary/AB 选择与固定哈希。
- `activity/decision/ActivityDecisionController#evaluate`：`POST /decision/v1/evaluate`。
- `activity/decision/ActivityDecisionService#evaluate`：固定 registry generation、选候选、资格/阶梯/折扣/赠品、降级、发布事件。
- `activity/decision/DecisionSnapshotRegistry#applyPointerEvent`、`warmUp`、`findBySpuIds`：本地 immutable 索引。
- `activity/engine/ActivityArtifactCompiler#compile`：只供准备/预热。
- `activity/engine/GuardedRuleExecutor#execute`：stateful 护栏。
- `activity/engine/ActivityEngineProperties`：阈值及启动校验。
- `activity/observability/ActivityDecisionMetrics`、`DecisionEventPublisher#tryPublish`、`ShadowCompareService#trySubmit`。
- `activity/effect/ActivityEffectService#aggregate/#query`、`ActivityEffectController#getOverview/#getExperimentComparison`：异步聚合曝光/命中/优惠/灰度差异；核销字段仅在收到电商事件后计算。
- `activity/security/ActivitySecurityConfig`、`ActivityApiKeyFilter`、`ActivityApiClientService`。
- 新增 workflow/artifact/artifact-binding/manifest/manifest-item/pointer/idempotency/outbox/audit/decision-log/effect-daily/api-client Entity 与 Repository。

## 7. 数据库变更与迁移

所有 DDL 用 `src/main/resources/db/migration/` Flyway，先 baseline，再新增；实体只映射，不依赖 Hibernate 自动改表。

| 新表 | 核心字段/约束 |
| --- | --- |
| `activity_version_workflow` | `(activity_id,activity_version)` PK；state、submitted/review actor/time/comment、row_version |
| `activity_strategy_version` | strategy_version_id PK；biz_line/scene/version unique、strategy、state、submitted/review actor/time、row_version；现有每业务线策略回填为 APPROVED v1 |
| `activity_idempotency` | `(scope,request_id)` unique；payload_hash、status、resource_id/version、response_json、expires_at |
| `activity_rule_artifact` | artifact_id PK；activity_id/version、biz_line、schema_version、template_version、checksum unique、content_json LONGTEXT、state、created_by/time |
| `activity_artifact_binding` | `(artifact_id,spu_id,store_id)` unique；store_id 规范化为非空（未知用 0），effective、bind_source、pool_id；spu_id 索引 |
| `activity_release_manifest` | manifest_id PK；release_key/biz_line、schema_version、strategy_version_id FK、strategy_json、checksum unique、state、created_by/time |
| `activity_release_manifest_item` | `(manifest_id,activity_id)` unique；artifact_id FK、stable_order；manifest_id/artifact_id 索引 |
| `activity_release_pointer` | release_key PK；stable/canary/shadow manifest FK、canary_bp、experiment/salt/algo、generation、row_version |
| `activity_release_outbox` | event_id PK；aggregate_id、generation、event_type、payload LONGTEXT、status/retry/available_at；status+available 索引 |
| `activity_operation_audit` | audit_id、actor/role/action、aggregate/version、before/after checksum、request/trace、created_at；只追加 |
| `activity_decision_log` | event_id、time、hashed_user_key、request/trace、decision_version、route/mode/degrade、amount/hits JSON、latency；按时间索引/分区策略待容量确认 |
| `activity_effect_daily` | `(stat_date,release_key,manifest_id,route_group,activity_id)` unique 且 activity_id 非空；exposure/hit/degraded、discount_sum、latency_sum、shadow_diff；总览由明细 SUM，不用 nullable “ALL” 行；异步任务幂等聚合 |
| `activity_api_client` | client_id、secret_hash、status、rate_limit、roles/scopes、rotated/expired time；不存明文 secret |

现有表补数据库约束前必须做重复/孤儿审计：`activity_manage(activity_id,version)` unique；现有 requestId 不直接强加 unique，而由 idempotency 表承接，避免历史 `is_del` 语义冲突。`activity_strategy` 在迁移后仅作兼容投影，线上 manifest 只接受已审核的 `activity_strategy_version`。长文本继续 LONGTEXT/LONGVARCHAR，不用 `@Lob`。

迁移顺序：baseline/新表 → 只写 artifact/workflow 影子数据 → 为每个 bizLine 的当前 ONLINE 集合回填 PREPARED artifact 和 stable manifest → 新旧决策双读影子 → pointer 读切流 → 旧路径保留两个稳定周期 → 再评估清理。历史 `is_del=1` 仅在所有子表完整且编译通过时生成可回滚 artifact；历史 manifest 只能由可验证的完整集合生成，否则报告“不可回滚”，不静默修复。回退只切回旧读路径，不删除新表。

## 8. 接口、配置与消息结构

### 8.1 决策 API

`POST /decision/v1/evaluate` 请求保留现有 `userId/userTags/userDistrictId/spuIdList/orderAmount/quantity`，新增必填 `bizLine` 以及 `requestId/options.explain/options.dryRun`；可选 `scene` 取 `ALL/SPU_DISCOUNT/BUY_AND_GET_GIFT`，默认 `ALL`，因此一次请求可同时返回折扣与赠品。`options.pinVersion` 实际固定 manifestId，只对 Admin sandbox；`timeoutMs` 若保留，只允许降低平台默认值，不能放大。旧接口没有 bizLine，兼容 adapter 在迁移期保留旧选择语义并记录歧义指标，不能猜一个新业务线后静默改变金额。

响应至少包含：`requestId/traceId/decisionVersion/engineMode/degraded/degradeReason/hitActivities[]/discount{finalAmount,strategy,currency}/gifts[]/route{group,experimentId}/timings{totalMs,engineMs}`。外部默认不返回完整 rule trace。

控制面新增版本化 `/activity-config/v1`：草稿创建/编辑、提交、审核、版本 diff、prepare、canary 流量、promote、rollback、offline、效果 overview/experiment comparison；所有命令带 `requestId` 和 expected version/generation。旧 `/activity-marketing/*` 仅兼容，不新增能力。

### 8.2 配置

```yaml
activity:
  engine:
    max-fires-per-request: <压测确定>
    timeout-ms: <结算预算反推>
    bulkhead-concurrency: <容量测试确定>
  artifact-cache:
    maximum-weight: <堆预算确定>
    expire-after-access: <回滚窗口兼容>
  release:
    poll-interval-ms: 1000
    max-generation-lag-ms: <SLO确定>
  shadow:
    enabled: false
    sample-basis-points: 0
    queue-capacity: <容量测试确定>
  decision:
    fallback-mode: NO_PROMOTION
    explain-enabled-for-external: false
```

阈值不得照抄占位符上线；启动时校验范围。密钥不进 YAML/Git，从环境/secret manager 注入。

### 8.3 消息

`ReleasePointerChangedV1`：`eventId,eventType,schemaVersion,occurredAt,releaseKey,bizLine,generation,stableManifestId,canaryManifestId,shadowManifestId,canaryBasisPoints,experimentId,routingAlgoVersion,checksum`。消费者按 releaseKey+generation 幂等。

`DecisionEvaluatedV1`：`eventId,schemaVersion,occurredAt,requestId,traceId,hashedUserKey,decisionVersion,routeGroup,experimentId,selectedArtifacts[],hitActivities[],discountAmount,giftSummary,engineMode,degraded,degradeReason,totalMs,engineMs`。MVP 由有界本地队列批写；二期同 schema 上 MQ。事件发布失败不影响响应，但必须计数。

## 9. 分阶段实施与依赖

### 阶段一：数据结构与领域模型

1. Flyway baseline，生产 `ddl-auto=validate`；建 workflow/strategy-version/idempotency/artifact/binding/manifest/item/pointer/outbox/audit/log/effect/api-client 表。
2. 新增生命周期、artifact、pointer、命令/DTO；实现历史数据审计和回填 dry-run。
3. 为 requestId 建独立唯一幂等服务；保留现有 version+1/409。

完成标准：MySQL 全新库和脱敏存量库迁移成功；重复执行安全；旧 API 回归不变；重复/孤儿/不可回滚报告可审计；无业务流量使用新 pointer。

### 阶段二：核心业务逻辑

依赖阶段一。实现 WorkflowService、StrategyService、ArtifactBuilder/Compiler、ManifestBuilder、ReleaseService CAS/outbox、ReleaseRouter、SnapshotRegistry、GuardedRuleExecutor、统一 DecisionService；修复买赠资格、阶梯覆盖和稳定 tie-break；实现 shadow/bulkhead/降级策略。

完成标准：完整状态机和职责分离；活动或策略非 APPROVED、artifact/manifest 编译校验失败均不能激活；正常决策零配置 DB/N+1/冷编译；max/timeout/filter/dispose 可验证；0～100% 路由固定；回滚只切 pointer；旧业务金钱语义除已批准修复外一致。

### 阶段三：接口与适配层

依赖阶段二。新增 config/release/decision/effect controller、安全过滤器、兼容 adapter；接入低基数指标、异步日志/outbox 轮询和效果日聚合；提供 OpenAPI 和错误码。

完成标准：`/decision/v1/evaluate` 契约稳定；旧端点兼容；401/403/409/429/400/降级 200 语义正确；配置面停止或日志失败不影响 stable 决策；指标无高基数。

### 阶段四：测试

依赖前三阶段。执行现有 H2 回归、MySQL/Testcontainers、状态/并发/契约/安全、artifact/pointer、多实例、迁移、故障注入、护栏选型压测和 API 容量测试；预发双读影子。

完成标准：`test-plan.md` 门禁全绿；stateful ADR 有数据；P99/SLO 达标；同 requestId 100 并发单结果；两实例事件丢失仍收敛；回滚演练 <5 分钟；严重金额差异为 0。

### 阶段五：文档与最终检查

依赖阶段四。固化 ADR（护栏、artifact vs KJAR、降级语义）、ERD、OpenAPI、状态机、Runbook、容量模型、告警和数据字典；完成安全/DBA/SRE/业务评审。

完成标准：占位配置全部关闭；待确认项有负责人/结论；发布、暂停放量、kill switch、回滚、事件补偿均可按 Runbook 演练；二期拆分依赖清单明确。

## 10. 测试方案摘要

- 单元：Translator 限额/转义、Ladder 严格性、DRL 真 build/fire、状态机、固定哈希向量、cache single-flight、guard 全路径。
- 集成：全生命周期；并发幂等/编辑/pointer CAS；统一决策两玩法/四策略/边界；旧 API 兼容；RBAC/API Key；指标 tag。
- MySQL 迁移：存量回填、历史完整性、LONGTEXT/唯一键/时区、双读影子、回退。
- 多实例：重复/乱序/丢失 outbox，轮询收敛，在途旧代次，新请求新代次，lag 暂停放量。
- 性能：stateful vs stateless listener 对照，1～100 candidates；热/冷/发布/回滚/影子/下游失败；CPU/GC/分配/session 和线程泄漏。
- 故障：DB/连接池、pointer 缺 artifact、编译失败、runaway、慢 activation、缓存、日志/影子队列饱和、实例重启。

详细用例与门禁见同目录 `test-plan.md`。

## 11. 风险、监控、灰度与回滚

### 主要风险和控制

- 自建 artifact schema 漂移：schema/template version、checksum、兼容读取测试，未知版本拒绝激活。
- 多实例短时分裂：generation 响应/指标、事件+轮询、lag 阈值自动暂停放量。
- stateful 开销：压测和堆预算；先优化候选/快照/预编译，不能移除硬护栏。
- `halt` 不能杀单 RHS：受控模板、输入限额、bulkhead、外层超时、kill switch。
- 迁移历史不完整：只报告/隔离，不自动猜测；仅完整历史可回滚。
- 决策日志反压：bounded queue、批写、drop metric，主链永不 CallerRuns。
- 降级财务语义：旧/新 API 分配置，上线前产品财务签字并做金额上限告警。
- 安全：控制面 JWT/RBAC，决策 API Key/mTLS（基础设施待确认），secret hash/轮换、限流、审计、脱敏。

### 监控与告警

指标：`decision_requests/latency`、`engine_latency/fires`、`guard_triggered`、`degraded`、`artifact_compile_fail`、`cache_hit/load_fail`、`pointer_generation_lag`、`outbox_backlog`、`shadow_submitted/dropped/diff`、`decision_event_dropped`。tag 仅 scene/strategy/mode/reason/result 等枚举；bizLine 只有在单租户白名单数量设硬上限后才允许作为 tag，超出归一为 `other`。

P0：stable artifact 缺失、降级率/金额异常、guard 连续触发、全实例 generation 长时间分裂。P1：outbox 积压、cache miss/影子丢弃升高、编译失败。activity/user/spu/rule 细节只进结构化日志。

### 发布与回滚运行规则

prepare → shadow（可选）→ 1%/5% → 20% → 50% → 100%，每阶段有最短观察窗和自动停止条件（错误、降级、P99、金额/命中差异）。任何异常 CAS 回 stable；若 stable 自身有问题则 kill switch + NO_PROMOTION/已确认 fallback。回滚先预热旧 artifact，再切 pointer，观察全实例 generation；禁止删除新数据或重编译旧规则。MTTR 目标 <5 分钟。

## 12. 上线前待确认清单

1. ~~单级还是多级审核，是否强制 Operator/Reviewer 分离~~ → **✅ 已定(2026-07-18)：多级审核 + 强制职责分离**。状态机审核态需扩为多级签署链（每级 actor/time/comment + 可退回），workflow 表支持多级审批记录。较原"单级"默认为范围上调，集中在 M2 落地。
2. ~~灰度是否只按 userId，是否加入地域/门店/cohort~~ → **✅ 已定(2026-07-18)：多维灰度(userId + 地域/门店/cohort) + A/B 对照组**。`ReleaseRouter#route` 的 bucketKey 按维度取；pointer/experiment 带 dimension；需固定 A/B 实验框架(experimentId+salt+分臂+窗口+指标)，`activity_effect_daily.route_group` 扩为实验臂。集中在 M2/M3；M0/M1 薄片不含灰度。
3. ~~新 API 降级默认 NO_PROMOTION 还是 legacy MAX~~ → **✅ 已定(2026-07-18)：NO_PROMOTION 降级 + 库存仅展示不扣减**。与 §2/§8.2 既有默认一致，锁定；上线前产品/财务签字 + 金额上限告警。
4. 结算端到端预算、服务端 timeout/maxFires/bulkhead 和目标容量。
5. 核销事件口径（下单/支付）与回传 schema。
6. 控制台 SSO/JWT 与决策 mTLS/API Key 的现有基础设施。
7. 日志留存、用户标识哈希/脱敏、合规删除要求。
8. 多实例允许的 generation 收敛窗口；是否需要二阶段激活。

这些不阻止按默认值开发数据/接口接缝，但阻止生产全量发布。

## 13. 最终验收清单

- [ ] 仅红包/买赠进入 MVP，运营无任何裸 DRL 入口。
- [ ] version+1、requestId、409 被保留并由 DB 唯一/CAS 加固。
- [ ] 状态机、审核、职责分离、diff、审计完整。
- [ ] artifact 不可变且带 schema/template/checksum；编译失败不动 pointer。
- [ ] 普通决策零配置 DB N+1、零冷编译；请求内 generation 固定。
- [ ] stateful max/timeout/filter/dispose 与累计 budget 全部生效。
- [ ] 买赠执行资格；阶梯与 tie-break 修复有回归证据。
- [ ] 0/5/20/100% 跨实例稳定分桶；shadow 不影响主响应。
- [ ] 回滚只切已预热历史 artifact，演练 MTTR <5 分钟。
- [ ] `/decision/v1/evaluate`、旧接口 adapter 和错误/降级语义通过契约测试。
- [ ] MySQL Flyway 存量迁移、双读影子、回退路径通过。
- [ ] 控制台 RBAC、决策认证/限流、secret 轮换、审计通过安全评审。
- [ ] 指标无高基数，日志异步失败不阻塞，面板/告警/Runbook 完成。
- [ ] P99、可用性、容量、GC、线程/session 泄漏达到确认门槛。
- [ ] 第 12 节所有生产阻断项已签字关闭。

## 14. 已知弱点

本方案不是“零分布式复杂度”：它自建 artifact/pointer/outbox 协议，必须长期维护 schema、事件幂等和缓存收敛；MVP 单进程无法充分证明网络分区行为；KieBase 重启后仍需编译预热；三/四阶段规则执行可能有 session 创建成本；MySQL 明细日志不是长期分析终态。选择它是因为这些弱点可以通过接口、测试和二期演进逐步处理，而 A 的发布正确性缺口、C/D 的首期基础设施成本更难逆转。

## 15. Claude 跨模型复核结论（2026-07-18）

对照真实仓库逐条核验 Codex 的承重论断，结论：**类名/方法/调用链/现状缺陷全部命中真实代码，无虚构；一处「待验证」已实证并升级为「已确认」。**

已核验通过（附证据）：

1. **决策路径无护栏** ✅ `ActivityRuleRuntimeService.java:41` 无界 `ConcurrentHashMap` 缓存（key 为 DRL 全文）、`:114` `newStatelessKieSession()`、`:120` `session.execute(facts)`——无 `fireAllRules(max)`/AgendaFilter/watchdog。
2. **三件护栏只在 stateful session** ✅ `GuardService.java:48/84/110` 分别实证 max/halt/filter；`fireAllRules(AgendaFilter,int)` 合并重载为 `StatefulRuleSession` 标准 API。故 §5.2 的 stateless→stateful 切换为硬前提（已在 §5.2 更正 4）。
3. **`changeStatus` 无状态机** ✅ `ActivityMarketingService.java:156-169` 仅校验目标状态码合法，随即 `setActivityStatus` 直写，ANY→ANY 可跳。§6.1「status 禁止任意 set」成立。
4. **requestId 无 DB 唯一约束** ✅ `:88` `findFirstByRequestIdAndIsDel` 为应用层查询；§7 用独立 `activity_idempotency` 唯一表加固方向正确。
5. **买赠不跑资格** ✅ `ActivityQueryService.java:152-171` `buyAndGetGifts` 直接 `evalGift`，未过资格；对照 `:97` `spuDiscount` 走 `eligibilityDef`。§3.3 修正项成立。
6. **埋点基数雷** ✅ `MeteredRuleListener.java` 存在；生成规则名（`elig_reject_N`）若作 `rule` tag 会基数爆炸，§11 用 scene/strategy/mode 稳定 tag 的约束正确。
7. **与未提交改动无冲突** ✅ 工作区未提交改动正是本方案要演进的 `activity` 模块本身，方案为「加新文件 + 改造现有」，不冲突。

复核补充意见（不改方案主线，供落地时权衡）：

- **落地时建议先切一个「MVP-of-MVP」薄片再铺全量**：本方案是**目标态蓝图**（~13 张新表 + artifact/manifest/pointer/outbox/audit 协议 + Flyway/Security/Caffeine/Testcontainers），一次性落地体量大。建议实施第一刀先做「stateful 护栏 + 决策 API `/decision/v1/evaluate` 骨架 + 单活动 artifact + stable pointer（无 canary/shadow）」，把热路径正确性和护栏先立住，再叠加审核/灰度/影子/效果。§9 的分阶段已利于此，落地时按此再细分第一阶段。
- **§12 待确认清单与产品蓝图 5 问、UI 3 问高度收敛**（审核层级 / 灰度维度 / 决策 API 降级默认 / 延迟预算 / 核销口径 / 库存预算 / 鉴权基础设施 / generation 收敛窗口）。这些**不阻塞设计与接缝开发，但阻塞生产全量**，将在 Phase 4 汇总集中请用户拍板。
- **决策 API 已敲定形态**：`POST /decision/v1/evaluate`（scene=ALL 一次返折扣+赠品），旧 `/spu-discount`+`/gifts` 降级为兼容 adapter；`options.explain` 仅 Admin。此形态可直接作为 Phase 3 `/frontend-plan` 的稳定契约输入（Sandbox 用 explain=true 拿全景评估集+decisionVersion）。

## 16. 前端驱动的后端补遗（Phase 3 `/frontend-plan` 回写，2026-07-18）

运营控制台前端规划（6 个只读子代理）对照本 FINAL_PLAN 逐条核验，发现**控制面在读取侧的端点缺口**——它们不改主链路架构，但不补则工作台/审核队列/版本历史等主界面**无数据源**。按优先级列出，纳入阶段三接口层：

1. **【P0 阻断前端主界面】控制面查询端点缺失**：§6.2/§8.1 只定义了 create/update/getVersionDiff + 各发布命令，**没有**列表/队列/详情的读端点。旧 `GET /activity-marketing/list` 返回旧 `activityStatus`(0/1/2)，**不含** DRAFT/PENDING_REVIEW/APPROVED/CANARY 等新生命周期态，无法驱动工作台按态分组与审核队列。**补**：
   - `GET /activity-config/v1/activities?state=&bizLine=&type=`（按生命周期态/业务线/类型分页列活动，返回新 `ActivityLifecycleState`）。
   - `GET /activity-config/v1/activities/{activityId}`（控制面详情，含当前 workflow 态 + 当前版本）。
   - `GET /activity-config/v1/activities/{activityId}/versions`（版本历史列表，供生命周期时间线与回滚目标选择——与 §5.1 不可变 artifact/版本天然对齐，读 `activity_manage`+`activity_version_workflow`）。
   - `GET /activity-config/v1/review-queue?bizLine=`（PENDING_REVIEW 队列，可由上面的 list 加 state 过滤实现，显式列出以明确审核入口）。
2. **【P1】explain 返回候选评估全景**：§8.1 explain=true 仅约定"rule trace"。前端 Sandbox 的"评估全景"（最大增量）需**显式**约定 explain 下返回**全部候选活动 + 每个的命中/淘汰原因 + 结构化分类 trace**（而非只有 winner + 字符串 trace）。仅 Admin/内部授权可开，与 §8.2 `explain-enabled-for-external:false` 一致。
3. **【P1/可降级】field-dict 候选值**：现 `RuleField` 仅 6 字段、`FieldValueType` 仅 NUMBER/STRING/ARRAY（**无 ENUM/BOOLEAN/DATE、无候选值列表**）。前端"可搜索多选下拉"当前无数据源 → **MVP 降级为自由 chip 输入**。若要枚举候选（如 storeId/类目下拉），需 `field-dict` 为对应字段补候选集端点（与 §6.1"fieldDict 增加 workflow 字典但不泄露 DRL"一并规划）。
4. **【P2/前端已补偿】preview 节点级定位**：现 `/preview` 恒 200 返回**单条错误字符串**、无节点定位。前端 MVP 以**客户端预校验**（精确对齐 `RuleConditionTranslator` 规则）补节点级红框，服务端 message 作卡片级兜底。后端若在 preview 响应回**出错节点路径**更佳，列为增强、非阻断。
5. **【确认，非缺口】degraded 歧义已被新契约消解**：旧 `/spu-discount` 的 `mode="legacy"` 三义（开关关/引擎回退/无生效活动）判定脆弱；新 `/decision/v1/evaluate` 的显式 `degraded/degradeReason/hitActivities[]/decisionVersion` 已解决，前端一律读新契约显式字段。**保持新 API 这些字段为必返**。

> 详见 `../activity-engine-platform-0718/30-DECISION_RECORD_FRONTEND.md` 决策 6 与 `31-FRONTEND_CONSOLE_PLAN.md` §6。

## 17. 通用化 + 多租户升级增量（2026-07-18，用户拍板扩范围）

用户推翻"单租户 MVP、SaaS 二期",拍板升级为**元数据驱动全自助接入 + 多租户 SaaS(隔离/配额/计费)**,并用第二业务线**出行(司机激励:多单奖励/阶梯奖励)**验证通用性。产品重定位见 `../activity-engine-platform-0718/01-platform-generalization-product.md`,通用化架构见 `11-generalization-architecture.md`。本节记录对本后端 FINAL_PLAN 的增量。**release/artifact/manifest/pointer/审核/灰度/护栏机器业务线与租户无关,原样复用,只加 key 维度。**

**Claude 复核(已对真实代码核验,通过)**:① ladder 确硬编码 `orderAmount`(`ActivityDrlBuilder.java:165-166`)→ 参数化阈值字段即可,`LadderRangeParser` 不改;② 翻译器确引用 `RuleField` 白名单 + `factField`(`RuleConditionTranslator.java` + `RuleField.java`),约束落进 `ActivityRuleContext(...)`;③ 上下文 fact 单例插入,故"Map 通用 fact 无索引"论证成立。

### 17.1 三大通用抽象（M0 就位接缝,不欠债）
1. **上下文 schema 数据驱动**:硬编码 `RuleField` 枚举 + `FieldValueType`(仅 NUMBER/STRING/ARRAY)→ **每 (tenant,bizLine) 的 schema 注册表**(field key/类型/运算符集/候选值;新增 ENUM+候选值,出行 driverLevel 硬需求)。`RuleConditionTranslator` 改为读 schema 白名单(**仍 fail-closed、仍白名单、运营永不写 DRL**),`field_key`/`<ladderField>` 走正则白名单防注入。
2. **通用 fact + DRL 生成(命门,推荐已定)**:固定 `ActivityRuleContext` POJO → **Map 支撑的通用 `RuleContext` + 强类型访问器**(`numberAttr("completedTrips") >= 10` / `stringAttr` / `listAttr`)。理由:上下文 fact 单例 → 索引无意义,Map 损失被抵消;避开 codegen 的 classloader 泄漏/运行期 javac;RHS 落值是编译期常量(不读上下文)→ CLAUDE.md 坑 6 不触发。**declared-type/`FactType` 记为逃生舱**(将来要 DATE 区间/真索引时切)。
3. **benefit-type 注册表**:红包/买赠硬编码 → `activity_benefit_type` 注册(config schema + 平台内置 DRL 模板 + 结果形状);`ActivityRuleResult` 的 typed `hitAmount/gifts` 泛化为 `List<BenefitOutcome>`。DISCOUNT/GIFT/**CASH_REWARD**(出行现金奖励)只差结果形状与 RHS 落值,LHS 全共用。**模板仅平台可注册,运营只选类型+填值**。

### 17.2 ladder 泛化 + 无状态取向
- ladder 把 `ActivityDrlBuilder:165` 的 `orderAmount` 参数化为校验过的 `<ladderField>` → 同一机器服务 mall(orderAmount) 与 ride(completedTrips)。多单奖励 = 红包"资格门+固定额"同构。
- **平台保持无状态 over caller-provided aggregated context**:出行 `completedTrips` 由司机端**预聚合传入**,平台不引 CEP/Step 8 窗口累积(列为可选高级能力,不进核心)。

### 17.3 多租户隔离（推荐:行级为主线 + 逃生舱）
- **行级隔离(tenant_id + 共享库)为主线**:`release_key` 从 `bizLine` 升为 **`(tenant_id, bizLine)`**,全套 CAS/outbox/pointer 原样复用;**每表 + artifact/manifest/pointer/decision-log/effect 加 tenant_id,每查询强制租户过滤(漏一处=串租户=SaaS 致命事故)**。大客/合规客保留 **schema-per-tenant 逃生舱**,db-per-tenant 仅企业版。
- **决策 API 通用信封**:`tenantId`(须与鉴权主体一致,防越权)+ `bizLine`(定位 schema+release_key)+ `scene` + **`context{任意键值}`**(逐键按 schema 校验、未知键 400、按 value_type 归一化)+ `options`。候选预筛选从 `spuId` 泛化为 schema 声明的 **`is_selector`** 字段(mall=spuId、ride=cityId)。`field-dict` 变 `?tenant=&bizLine=` 数据驱动。
- **内存模型风险(P0 压测项)**:`DecisionSnapshotRegistry` + KieBase 编译缓存 **cache key 必含 tenant**;多租户下常驻租户上限 `max-resident-tenants`、per-tenant 权重淘汰、冷租户 lazy-load 重建尖刺——**OOM 与 P99 双命门,必须容量建模 + 压测定阈值**。tenant_id 作指标 tag 须白名单硬上限。

### 17.4 schema 演进 vs 已冻结 artifact（新增架构决策点，已按评审修正）
产品/架构均标此为"继通用 fact 之后第二大点"。方向:**schema 版本化**,artifact 编译期 pin 其 schema 版本(与现有 `schemaVersion` 概念对齐)。

**⚠ 评审修正(原"取不到键 null 优雅失败/候选淘汰"是错的,会静默超发)**:
- **缺字段对否定运算符是 fail-OPEN**:正向约束(`>=`/`in`/`contains`)缺字段→null→reject=fail-closed✅;但否定(`ne`/`notIn`/`notContains`)缺字段→`null not in(黑名单)`判 true→候选**不淘汰→放行=静默超发**,违背 D1 防超发。→ 翻译器对**所有否定运算符**加存在性护栏 `(field != null && field not in(...))`;归一化区分"键不存在"与"值 null"。
- **信封校验必须按 artifact 的 pinned schema,不是 live schema**:否则旧有/新删的字段在 live-schema 信封期被 400 拒,冻结 artifact 根本跑不到,"自包含"是假的。
- **删字段/改类型 → 硬失效引用它的 artifact**(标记"需重建/退役"),缺字段**显式分类**(拒绝激活 / 显式降级带 reason),**禁止静默淘汰**。加字段友好。
- **改型 ClassCastException**(如 STRING→NUMBER 后 `textAttr` 拿到 BigDecimal)→ `safeRun` 兜成 NO_PROMOTION 全量降级(非静默但需告警)。

### 17.5 落地节奏（架构 Agent 强推 + 评审加闸）
**两个硬骨头不可同时上线**:先在**单租户电商**跑通「schema 驱动 + 通用 fact + benefit 插件」并对齐旧行为(回归证明金额语义不变),**再叠多租户维度**(tenant_id + 隔离 + 配额 + 计费)。

**⚠ 评审修正(M0 别把未证风险塞进地基)**:M0 是否上 Map fact **由 §17.8 的 spike 闸决定,不是无条件**。spike 不绿则 M0 **保留现有 typed `ActivityRuleContext`(可信回归基线,§17.5 的"对齐旧行为"正需要它)**,只立三个便宜接缝:`tenant_id` 列 + `release_key` 二元组 + **把翻译器的"字段来源"抽成接口(枚举实现先顶着)**。fact 表示法切换留到 Track A 通用化阶段用 typed 基线做回归对比。"晚改 10x"的前提是接缝必须是 Map 形状——但上述更薄的接缝拿到同样的可逆性,风险大降。**接缝清单补关键一条:所有缓存/registry/查询 key 从 Day1 带 tenant(单租户时值为常量)**——否则 Track B 要逐个补 tenant 是最高危返工(跨租户串味)。

### 17.6 增量的真实代价（诚实标注）
决策引擎心脏重构(fact/翻译器/DRL 生成)+ 安全白名单从枚举变动态数据(滥用面变大,需每租户 schema/节点/候选硬上限)+ 全链路加租户维度 + 建模面较原单租户电商 MVP **约翻倍**。这是"学习脚手架"到"企业级多租户 SaaS"的定位跃迁,做设计蓝图 OK,落地是另一量级投入。

### 17.7 鉴权/多租户接入既有 auth-platform（2026-07-18,替换 §6.2 自建鉴权）

用户名下已有内部统一 IAM `/Users/liruijun/personal/LLM/auth-platform`(**Casdoor 身份/SSO/OIDC + SpiceDB ReBAC 授权** + Spring Boot Starter SDK)。经只读核验(结论带该仓库文件路径),**活动平台的租户/认证/授权直接接入它,不再自建**——多租户是它的一等公民,且它的架构纪律与本平台热路径约束天生一致。

**契合点(已核验)**:
- **Casdoor organization = 租户**,`token.owner` = 权威 tenant_id;"只信 token owner、不信 header/query 覆盖"是 auth-platform 既有硬约定(`docs/统一登录平台接入手册.md:71`)——直接兑现本平台"tenant 从验证过的凭证取、不信请求体"的越权防线。
- **RS256 JWT + JWKS discovery**(`/.well-known/jwks`),`NimbusJwtDecoder` 缓存公钥离线验签 → **决策热路径零判权网络调用**,与 §17.3 P99 约束一致。
- **SpiceDB ReBAC** 每次 check = 两跳网络调用且平台层刻意不缓存判权结果 → **细粒度判权只用于控制台,决策热路径不碰**(auth-platform 自身《性能规划》即此纪律)。
- 多租户 SaaS 用 **Shared Application** 派生 client_id `<base>-org-<tenant>` + aud allowlist 认 `<base>-org-*` 家族 → 控制台(人,授权码流)新增租户零改代码/零重启。**⚠ 但 M2M 决策平面禁用共享 secret(见下修正)。**

**两个平面的接法**:
- **控制台(人)**:授权码+PKCE 登 Casdoor → 后端 OIDC resource-server 本地验签(照 auth-platform 的 `SecurityConfig.java`)→ 引 SDK `@CheckAccess`/`checkBulk` 调 SpiceDB 判「该运营/审核/发布能否改/批/发某活动」。授权模型照 **`recsys.zed` 作用域继承范式**建 `tenant→bizLine→activity` + operator/reviewer/publisher(职责分离,支撑 D2 多级审核),**用独立 SpiceDB 实例**(同 recsys)。
- **决策 API(机器,热路径)**:**只做本地 JWKS 验签**,`tenant` 从 `owner` 离线取,**不碰 SpiceDB**。M2M 用 **每租户独立注册的 Casdoor Application(独立 client_id + 唯一 secret,非 Shared Application 共享 secret 派生)** → token `owner` = 该租户 → 决策 API 读 `owner` 即得 tenant。**必须自写 audience 校验器(常开、前缀家族匹配 + owner↔aud 绑定),不抄参考 SecurityConfig 的默认空 aud。** 决策信封里的 `tenantId` 只作校验(须 = token.owner,否则 403),**绝不作为租户来源**。

**替换掉的自建件(§6.2)**:
- `ActivityApiKeyFilter`(自管 API Key)→ **OIDC/JWT 验签过滤器**(spring-boot-starter-oauth2-resource-server + 缓存 JWKS)。
- `activity_api_client` 表(存 secret)→ 瘦身成 **租户注册表**(tenant_id[=Casdoor org] → schema/配额/启用 bizLine/状态,**不存凭证**);凭证归 Casdoor。
- `ActivitySecurityConfig` → 抄 auth-platform 的 resource-server 配置。
- D6(鉴权基础设施)、D13(隔离强度)从"自建/待定"改为**复用 auth-platform**,少一大块自建。

**接入必须实测/注意的坑(auth-platform 文档背书)**:
1. **【命脉待实测,评审升级】**两件事一起冒烟,不只测"owner 在不在":①Casdoor 对 **client_credentials token 是否写 `owner`=app 的 org**(决策 API 拿租户前提;Casdoor 本体不在仓库);②**"租户 A 的 secret 能否换出 owner=租户 B 的 token"必须失败**——若走 Shared Application 共享 secret,派生 client_id 可猜(`<base>-org-<victim>`),任一持共享 secret 的租户可为别租户换 token → 在无判权的决策热路径跨租户冒充。故 M2M 强制每租户独立 app 唯一 secret(见上)。若 Casdoor 不写 owner,决策租户维度退到 client→tenant 映射表兜底(不默认放行)。
2. 后端必配 `server.max-http-request-header-size: 64KB`(Casdoor token ~9KB,否则合法 token 报 400 非 401)。
3. 用户主键切 `sub`(UUID 非 username);SDK 的 `SubjectResolver` 必须返回 Casdoor `sub`,否则判权空转。
4. SpiceDB 对象 id 带 `<tenantId>_` 前缀防跨租户串权;资源创建/删除**双写关系元组**(最易漏)。
5. **高频子资源(活动/规则 CRUD)不要逐条写 SpiceDB 元组**——权限锚在作用域对象(activity 继承 bizLine 继承 tenant),照 recsys"反查归属再判",避免元组爆炸。
6. auth-platform SDK 为 `0.1.0-SNAPSHOT` 未发远程仓库,需本地 `install`(双 maven 仓库带 `-Dmaven.repo.local`)——工程摩擦。
7. 生产开 server 写端 `authz.server.security.enabled=true`,否则关系写端裸奔。

> 详见 `../activity-engine-platform-0718/12-auth-platform-integration.md`(接入调研全文)。

### 17.8 独立评审吸收:落地前必过的闸（三视角对抗评审,2026-07-18）

三视角对抗评审(正确性/可行性、安全/多租户隔离、性能/内存/运维)结论:**骨架对、方向对、承重论证经核验站得住且难得诚实,但当前是「策略充分、机制缺位 + 命门写成已核实实则未证」。多租户通用化落地前必须先过下列闸。** 全文与逐条证据见 `../activity-engine-platform-0718/41-REVIEW-FINDINGS-generalization.md`。

**P0 闸(过不了不得开建)**:
1. **Map fact spike** — **正确性 ✅ 已过(2026-07-18)/ 性能 ⏳ 待基准**。
   - ✅ 正确性实证:14/14 编译+执行通过(Drools 8.44.2 KieHelper 真数据)——方法左值 `numberAttr(...)>=`/`textAttr(...) ==,in`/`listAttr(...) contains,not contains`/containsAny/`between`/`not SpikeCtx(...)` 淘汰包裹/出行 `completedTrips` 阶梯全绿。**选型 (a) 成立,不退 declared-type/`attrs[]`。** 顺带实证 COR-2 的 fail-open 属实 + 存在性护栏修复有效。
   - ✅ 性能已基准(2026-07-19,PERF-3 吓人版本证伪):最坏形状下 typed 与 map **都线性 O(M)**(typed 无免费 alpha 索引共享),map 只多付 ~0.12µs/档、200 档 1.15x,**非悬崖**,真实规模可忽略;成本驱动是规则数非 fact 表示法。**Map 选型 (a) 性能上成立,不因性能退 declared-type。** 护栏阈值按 per-artifact 规则数派生(PERF-4)。正式落地补 JMH + in/contains 大规模 + 分配量。
2. **M2M 身份不可伪造**(SEC-1):每租户独立 Casdoor app + 唯一 secret,测"A 的 secret 换不出 owner=B"(见 §17.7)。
3. **租户隔离机制化**(SEC-2/PERF-8):enforcement 下沉 ORM(Hibernate 6 `@TenantId` discriminator 或 DB RLS + `@Filter` 常开)+ 仓库基类 TenantContext 未设即 fail-closed + CI 阻断裸 `findAll()`/跨表查询;**所有缓存/registry/单例 Day1 带 tenant**;`benefit_type` 全局行作显式例外单独测。
4. **aud 校验器自写**(SEC-3):参考 SecurityConfig 默认空 aud 根本不校验;自写常开 + 前缀家族匹配 + owner↔aud 绑定。
5. **多租户内存容量模型**(PERF-1):堆预算表(单 snapshot 项 + 单 KieBase 实测保留堆,JOL/heap dump)+ `heap=f(tenants,activities,tiers)` + 公平份额淘汰 + **Metaspace**(declared-type/编译灌类);独立限速编译线程池,双冷 miss 不阻塞热路径(PERF-9)。

**P1 修正(已就地改文档者标注)**:
- 否定运算符缺字段 **fail-OPEN 静默超发** + 信封须按 **pinned schema** 校验(COR-2/PERF-7)→ **已改 §17.4**。
- `11 §5.1/§6.3` 的 X-Api-Key/TenantContext/`activity_api_client` **已被 §17.7 推翻**,作废、统一指向 §17.7(COR-4)→ 见 `11` 顶部评审吸收注。
- **ReBAC 表达不了 D2 四眼/自审阻断** → 职责分离在**应用层**强制(持久化提交人,`actor==submitter` 拒 approve/publish);ReBAC 只给粗粒度角色分离(SEC-4)。
- **跨异构 benefit 合并**(现金+赠品+折扣同场)MVP **明确不支持**(单场景单 benefit-type);`11` 别当已解(COR-3)。
- **JWKS 轮转/冷启动**是偶发热路径网络依赖 → 启动预热 + 有界超时 + last-good stale 兜底,写进 fail-open(PERF-5)。
- **每租户 QPS 限流**在无状态多实例下需明确基座(网关层近似 或 Redis token bucket 计入延迟预算 + 降级),别用"一张 quota 表 + 429"冒充(PERF-6)。
- **反查归属再判是 IDOR 面**:从资源权威行取作用域 + 带租户过滤 + 不信 body parent/bizLine(SEC-5)。
- **Track B 改 pointer PK** = 热表锁表 DDL,预留 online-DDL(gh-ost/pt-osc)+ 定义 Track B 回滚锚点(PERF-8)。
- **无界 DRL 缓存**(现 `ActivityRuleRuntimeService:41`)替换为有界缓存是通用化**同步前置**,非后续优化(PERF-10);trace 累积按 explain 在**构建期**关,非响应期(PERF-11)。
- **租户注销级联清理 + PII 留存/擦除**GA 前补 runbook(SEC-6);**所有拼进 DRL 的标识符(含 `activityId`)过同一审计正则发射器**(SEC-7)。
- DATE 过早进类型矩阵而 (a) 恰做不好 DATE → **MVP 只加 ENUM**(COR-8);逃生舱 (a)→(c) 迁移"翻译器输出不变"是错的,方法调用 vs 属性访问 emit 必改(COR-9)。

**放行判断**:修掉上述 P0 闸 + P1 后,是一份可落地的好蓝图;**在 Map fact spike 绿之前,先 spike、别开建多租户落地。**
