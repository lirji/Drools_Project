# 02 · codebase-explorer：现状、调用链与影响面

## 1. 代码结构

`com.lrj.drools.activity` 当前是同一 Spring Boot 进程内的 controller/service/engine/persistence/domain 分层。生产主链真实入口只有 `ActivityMarketingController`，共 8 个端点。Step 18 的 `CampaignController`/`CampaignService` 是并行教学链，不在 activity 调用链中。

| 层 | 真实代码 | 当前职责 | 可复用判断 |
| --- | --- | --- | --- |
| REST | `activity/controller/ActivityMarketingController.java` | 创建、状态、列表、详情、折扣、赠品、预览、字典；手工映射 400/409 | 保留兼容入口；新增独立 config/decision controller |
| 配置服务 | `activity/service/ActivityMarketingService.java` | `create`、`updateByVersion`、`changeStatus`、`getDetail`、`previewEligibility` | 复用校验/翻译/多表写基座；拆出工作流和发布职责 |
| 决策服务 | `activity/service/ActivityQueryService.java` | `spuDiscount`、`buyAndGetGifts`；DB 组装候选；调用规则；legacy 回退 | 复用业务语义，热路径改为快照读取和统一响应 |
| 圈选 | `activity/service/ActivityPoolMatchService.java` | 池规则过滤、AUTO 绑定目标态 diff | 可复用，但发布快照必须冻结圈选结果 |
| 规则 | `ActivityDrlBuilder`、`ActivityRuleRuntimeService`、`RuleConditionTranslator`、`LadderRangeParser` | 受控 DRL 生成、内容缓存、stateless 执行、白名单翻译 | Builder/Translator/Parser 复用并加限额；Runtime 重构 |
| 数据 | `activity/persistence/*` | 10 个实体与 Repository | 源配置表保留；新增工作流/产物/指针/outbox/审计/决策日志 |
| 测试 | 3 个 `ActivityMarketing*Test` | H2 集成覆盖主流程、边界、全局 legacy 开关 | 作为回归基线扩展；目前没有 controller/并发/性能/安全测试 |

## 2. 当前真实调用链

### 2.1 写路径

`POST /activity-marketing/create` → `ActivityMarketingController#create` → `ActivityMarketingService#create`：

1. `validateCommon` 校验名称、类型、时间、金额、赠品、合并策略。
2. `findFirstByRequestIdAndIsDel` 做应用层幂等。
3. 编辑时 `softDeleteVersion(activityId,currentVersion,now)`；影响行数为 0 抛 `IllegalStateException`，controller 返回 409；成功后 `version+1`。
4. `RuleConditionTranslator#translate` 生成约束；`ActivityDrlBuilder#buildEligibilityDrl` 生成完整 DRL；`ActivityRuleRuntimeService#compileOrGet` 写前试编译。
5. 同一 `@Transactional` 内顺序写 manage/rule/condition/gift/manual binding/pool ref/auto binding/strategy。
6. `ActivityPoolMatchService#refreshActivityBinding` 在同事务做 AUTO 行 diff。

事实限制：`request_id` 只有普通索引；activityId 由时间戳和进程 `AtomicInteger` 生成；旧版本只有 manage 行被 `is_del=1`，子表没有同步删除；`changeStatus` 直接 set，不验证来源状态或操作者。

### 2.2 折扣决策路径

`POST /activity-marketing/spu-discount` → `ActivityQueryService#spuDiscount`：

1. `boundActivityIds` 按 SPU 查 `activity_spu_binding(effective=1,is_del=0)`。
2. `filterBeginActivities` 对每个 id 查询最新未删 manage，过滤 ONLINE、时间和类型。
3. `flatten` 对每个活动再查 rule；`eligibilityDef` 对每个活动再查 condition；`resolveStrategy` 再查 strategy。
4. 顺序执行 `evalEligibility`、可选 `evalLadder`、`evalDiscount`，每个方法分别生成 DRL、查/建 KieBase、执行一次 session。
5. 引擎关闭、异常或空结果时进入 `legacyMax`。全局关闭时 legacy 忽略所有资格；引擎空决策时只对仍 eligible 的候选取最大。

因此热路径存在 N+1 查询、最多三趟引擎、请求时冷编译和版本视图不显式的问题。

### 2.3 买赠路径

`POST /activity-marketing/gifts` → `ActivityQueryService#buyAndGetGifts`：绑定→ONLINE/时间/type 过滤→rule/gift 组装→`evalGift`；失败则直接汇总候选全部赠品。该路径当前**没有执行资格规则**，与折扣路径不一致，是生产化前必须修复的兼容性缺口。

### 2.4 引擎路径

`ActivityRuleRuntimeService#safeRun` 修改传入 ctx 的 scene；`compileOrGet` 用 `ConcurrentHashMap<String,KieBase>` 且 key 为 DRL 全文；`run` 创建 `StatelessKieSession`，设置 global `result`，执行 ctx + candidates。`safeRun` 捕获所有异常并返回 null。

现有 Step 14 的 `GuardService` 使用 stateful `KieSession`，分别证明了 `fireAllRules(maxFires)`、跨线程 `halt()`、`fireAllRules(AgendaFilter)`；`ReleaseAgendaFilter` 按 `@release` 元数据放行。它们没有接入 activity。

## 3. 当前数据模型

| 表/实体 | 关键字段与索引 | 决策用途/问题 |
| --- | --- | --- |
| `activity_manage` | activity_id/version/is_del，status/time，request_id 普通索引 | 当前版本与状态源；无 DB 唯一幂等、无审核信息 |
| `activity_rule` | activity_id/version，红包固定额/阶梯 JSON | 决策组装候选 |
| `activity_condition` | condition_tree_json/generated_drl LONGVARCHAR，scene/enabled | 可视化源与生成约束；不是完整发布产物 |
| `activity_spu_binding` | `(spu_id,effective,is_del)`、`(activity_id,version)` | 查询不带 version，历史行可能进入首轮候选 |
| `activity_gift` | activity_id/version + 赠品结构化字段 | 买赠快照源 |
| `activity_strategy` | biz_line/activity_type/scene/version | 业务线级 upsert，不与活动版本原子发布 |
| `activity_product_pool` / `_rule` / `activity_pool_ref` / `demo_product` | 池及 demo 商品 | 控制面圈选源，不应在决策请求中实时扫描 |

配置现状：默认 MySQL profile；`spring.jpa.hibernate.ddl-auto=update`；MySQL URL 可自动建库且关闭 SSL；Actuator/Prometheus 已依赖并暴露；没有 Spring Security、Flyway、缓存库、消息库。`kie-ci` 已因 Step 16 存在于 `pom.xml`，但 production activity 不应因此默认采用 KJAR。

## 4. 已验证可复用资产

- `RuleField` 六字段白名单及每字段运算符白名单。
- `RuleConditionTranslator` 的字符串转义、值形状校验与深度 5 限制；需补总节点、列表、字符串和数值范围限制。
- `ActivityDrlBuilder` 的四场景模板；必须增加确定性 tie-break、模板版本和死循环回归。
- `LadderRangeParser` 的字段别名与 `[min,max)` 约定；需增加分档重叠/排序/负值校验。
- `ActivityMarketingService#create` 的事务边界、写前编译、version+1 与 409 模式。
- `ActivityPoolMatchService` 的 AUTO diff 幂等算法。
- `ActivityRuleContext`、`ActivityCandidate`、`ActivityRuleResult`、`GiftResult` 作为规则 fact/结果模型。
- `GuardService`/`ReleaseAgendaFilter` 的 stateful 护栏模式；复用思路和 filter，不能直接把 demo service 接入生产。
- Micrometer 与 Prometheus 基础设施；`MeteredRuleListener` 的 rule tag 不可直接复用。
- `HotReloadService` 的“新 KieBase 原子替换且在途 session 继续使用旧引用”机制。

## 5. 当前测试事实

- `ActivityMarketingFlowTest` 真正执行资格、MAX、上下线、版本编辑、阶梯、买赠、商品池。
- `ActivityMarketingEdgeTest` 覆盖非法字段不落库、串行 requestId 幂等、旧版逻辑删除。
- `ActivityMarketingLegacyTest` 明确断言全局开关关闭时忽略资格并取最大红包。
- 三者均为 H2 `create-drop` SpringBootTest；未覆盖 MySQL DDL、REST 契约、状态机、同键并发、多实例、缓存、护栏、灰度、回滚、影子、RBAC、指标基数和性能。
- 本轮按“只写规划目录”约束未运行 Maven，避免生成 `target/` 或改动非授权路径。

## 6. 规划实施时的受影响文件

以下分为“现有需修改”和“计划新增”。计划新增名称是执行契约，不代表仓库当前已有。

### 6.1 现有需修改

- `pom.xml`：Flyway、Spring Security、缓存实现及测试/压测依赖；二期再决定是否保留 activity 对 `kie-ci` 的运行依赖。
- `src/main/resources/application.yml`、`application-mysql.yml`、`application-h2.yml`：禁用生产 ddl-auto、护栏/缓存/灰度/outbox/security/日志参数。
- `activity/controller/ActivityMarketingController.java`：兼容 API 委托、鉴权、废弃任意状态接口。
- `activity/service/ActivityMarketingService.java`：保留配置聚合写，接入 DB 唯一幂等与工作流，不直接发布。
- `activity/service/ActivityQueryService.java`：改为统一决策编排和快照读取；兼容方法做 adapter。
- `activity/service/ActivityPoolMatchService.java`：发布时冻结圈选结果并加并发校验。
- `activity/engine/ActivityRuleRuntimeService.java`：版本缓存、stateful 有界执行、超时/filter/指标、无冷编译。
- `activity/engine/ActivityDrlBuilder.java`：模板版本、稳定排序/tie-break、release 元数据与组合产物。
- `activity/engine/RuleConditionTranslator.java`、`LadderRangeParser.java`：输入复杂度和业务合法性上限。
- `activity/domain/ActivityStatus.java`、`ActivityStatusRequest.java`、`SpuDiscountRequest.java`、`ActivityRuleResult.java`：兼容映射和新契约所需字段。
- 所有现有 activity Repository/Entity：仅补必要查询、约束映射；不把发布状态继续塞进“最新未删”查询。
- 三个现有 `ActivityMarketing*Test.java`：保持旧行为回归并逐步迁到新 API。

### 6.2 计划新增（精确到责任）

- `activity/config/ActivityConfigController.java`：草稿/详情/提交审核。
- `activity/config/ActivityWorkflowService.java`：状态机、审核、职责分离。
- `activity/config/ActivityStrategyService.java`：把现有活动保存时的业务线策略 upsert 改成独立不可变版本与审核。
- `activity/release/ActivityReleaseController.java`：灰度、放量、全量、回滚命令。
- `activity/release/ActivityArtifactBuilder.java`：从现有多表读取指定版本并生成规范化不可变内容。
- `activity/release/ActivityReleaseManifestBuilder.java`：把业务线内有序 artifact 集合与唯一策略快照冻结为 manifest。
- `activity/release/ActivityReleaseService.java`：PREPARED manifest→指针 CAS→outbox。
- `activity/release/ReleaseRouter.java`：stable/canary/AB/pin 权限化路由。
- `activity/decision/ActivityDecisionController.java`、`ActivityDecisionService.java`：`/decision/v1/evaluate` 与主编排。
- `activity/decision/DecisionSnapshotCache.java`：有界 cache、single-flight、AtomicReference 代次。
- `activity/engine/GuardedRuleExecutor.java`、`ActivityEngineProperties.java`：有界 stateful 会话与配置。
- `activity/observability/ActivityDecisionMetrics.java`、`DecisionEventPublisher.java`、`ShadowCompareService.java`：低基数指标与非阻塞事件。
- `activity/effect/ActivityEffectService.java`、`ActivityEffectController.java`：曝光/命中/优惠/灰度差异聚合与查询。
- `activity/security/ActivitySecurityConfig.java`、`ActivityApiKeyFilter.java`：控制面 RBAC 与决策面认证。
- `activity/persistence/` 下新增 workflow/artifact/manifest/manifest-item/pointer/outbox/audit/decision-log/effect-aggregate 对应 Entity/Repository。
- `src/main/resources/db/migration/`：baseline、生产约束、新表、回填和索引 Flyway 脚本。
- `src/test/java/com/lrj/drools/activity/` 下新增 workflow/release/guard/router/security/contract/concurrency/MySQL 集成与性能测试。

二期物理拆分时再新增 Maven 模块或独立应用；当前仓库没有多模块结构，具体模块名必须在二期实施前验证构建与部署规范。
