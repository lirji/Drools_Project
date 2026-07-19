# 02 Codebase Analysis

## codebase-explorer 视角

### 当前仓库结构

- 入口：`src/main/java/com/lrj/drools/DroolsDemoApplication.java`
- Drools 配置：`src/main/java/com/lrj/drools/config/DroolsConfig.java`
- REST 控制器：`src/main/java/com/lrj/drools/controller/*Controller.java`
- 业务服务：`src/main/java/com/lrj/drools/service/*Service.java`
- 领域对象：`src/main/java/com/lrj/drools/domain/*.java`
- JPA 持久化：`src/main/java/com/lrj/drools/persistence/*.java`
- 规则资源：`src/main/resources/rules/**`
- 静态前端：`src/main/resources/static/index.html`、`src/main/resources/static/assets/app.js`、`src/main/resources/static/assets/examples.js`、`src/main/resources/static/assets/styles.css`
- 配置：`src/main/resources/application.yml`、`application-h2.yml`、`application-mysql.yml`
- 测试：仅 `src/test/java/com/lrj/drools/tools/VipDiscountSheetGenerator.java`

### 当前活动相关实现

- `CampaignController`
  - `create(CreateRequest)` -> `CampaignService#create`
  - `check(String, UserProfile)` -> `CampaignService#check`
  - `end(String)` -> `CampaignService#end`
  - `list()` -> `CampaignService#list`
- `CampaignService`
  - `create(String campaignId, String name, String drl)`：先 `compile`，再 upsert `CampaignEntity`，最后更新内存 `registry`。
  - `check(String campaignId, UserProfile user)`：查库 -> 状态校验 -> `computeIfAbsent` 懒编译 -> `newKieSession` -> 插入 `UserProfile` -> 收集 `Eligibility`。
  - `end(String campaignId)`：状态置 `ENDED`，移除缓存。
  - `compile(String drl)`：`KieHelper.addContent` + `verify` + `build`。
- `CampaignEntity`
  - 表名 `campaign`。
  - 字段：`campaign_id`、`name`、`eligibility_drl`、`status`、`created_at`、`updated_at`。
  - `eligibilityDrl` 用 `@JdbcTypeCode(SqlTypes.LONGVARCHAR)`。
- `CampaignRepository`
  - `JpaRepository<CampaignEntity, String>`。
- `UserProfile`
  - 字段：`userId`、`age`、`vipLevel`、`registrationDays`、`totalSpent`、`city`。
- `Eligibility`
  - 字段：`eligible`、`reason`。

### 当前可复用能力

- 运行时 DRL 编译：`HotReloadService#upsert` 和 `CampaignService#compile`。
- KieBase 热替换与缓存：`HotReloadService`、`ScannerService`、`CampaignService.registry`。
- KJAR/KieScanner 教学实现：`ScannerService#deploy`、`#run`、`#startPolling`。
- 决策表加载：`DroolsConfig#kieContainer` 显式读取 `.drl`、`.xls`、`.dmn`。
- 指标：`MeteredDiscountService` 和 `MeteredRuleListener`。
- 护栏：`GuardService` 已演示 `AgendaFilter` 灰度、最大 fire 次数、超时 `halt`。
- 静态前端 demo 框架：`examples.js` 声明 demo，`app.js` 根据声明渲染请求面板、发起请求、渲染摘要。
- JPA + H2/MySQL 双 profile：适合快速新增活动 demo 表。

### 来源项目活动主链路

#### 管理创建链路

- `ActivityAdminPlatformManageController` 位于 `activity/controller/admin`，当前只有注入 `ActivityAdminPlatformManageService` 的空壳。
- 真正创建逻辑在 `ActivityAdminPlatformManageServiceImpl#create(ActivityAdminPlatformManageBo)`：
  - `ActivityCommonValidator#validate`
  - `ActivityTypeRegistry#getPlugin`
  - `ActivityTypePlugin#validateCreate`
  - `saveByVersion`
  - `saveActivityRelations`
  - `persistConfigs`
  - `publishEligibilityRule`
  - 发布 `ActivityChangedEvent`
- `saveByVersion(ActivityAdminPlatformManageBo)`：
  - 新活动用 `ShopNumberSeqService#createNextSeq(OrderPrefixConstant.activity_contract)` 生成 `activityId`。
  - 编辑活动查同 `activityId/version/isDel=0` 旧行；已下线不可修改；已上线不可修改开始时间。
  - 旧行逻辑删除，新行版本号加 1。
  - 保存 `ActivityDynamicRulesBo` 到 `ActivityDynamicRulesService#saveActivityDynamicRules`。
- `saveActivityRelations(ActivityAdminPlatformManageBo)`：
  - 将 `spuStoreProducts` 交给 `ActivityAdminStoreSpuProductService#saveSpuStoreProduct`。
- `publishEligibilityRule(ActivityAdminPlatformManageBo)`：
  - 如果 `eligibilityConditionTree` 非空，调用 `ActivityRuleConditionPublisher#publishEligibility`。

#### 查询与决策链路

- `ActivityAdminPlatformManageServiceImpl#filterBeginActivityIds`
  - 旧逻辑：`legacyFilterBeginActivityIds`，过滤已上线且时间范围内活动。
  - 新逻辑：开关 `activity.rule-engine.eligibility.enabled` 开启后调用 `ruleFilterBeginActivityIds`。
  - 规则异常或空决策回退旧逻辑，并打印灰度对照。
- `ActivityDynamicRulesServiceImpl#getSpuDiscount`
  - 入参 `SpuDiscountBo`，含 `spuIdList`、`userId`、`districtId`。
  - 查 `ActivityAdminStoreSpuProduct`，按 `isDel=0/effective=1/spuId in (...)`。
  - 提取活动 id，调用 `filterBeginActivityIds`。
  - 查 `ActivityDynamicRules`，按活动 id 分组保留最大红包金额。
  - 旧逻辑逐 SPU 取最大优惠金额。
  - 开关 `activity.rule-engine.discount.enabled` 开启后经 `applyDiscountEngineOverride` 调用 `evalSpuDiscountByRule`。
- `ActivityDynamicRulesServiceImpl#getActivityDynamicRulesBySpuId`
  - 单 SPU 查询，与批量类似。
- `ActivityDynamicRulesServiceImpl#queryBuyAndGetActiveActivityBySpuId`
  - 买赠场景，开关 `activity.rule-engine.gift.enabled` 开启后调用 `evalBuyAndGetGiftsByRule`，否则 `legacyBuyAndGetGifts`。

#### 规则引擎链路

- `ActivityRuleEngine`
  - `evalEligibility`
  - `evalDiscount`
  - `evalLadder`
  - `evalGift`
- `RoutingActivityRuleEngine`
  - `evalDiscount` 是两段式：先尝试 `DISCOUNT_COMPUTE`，再执行 `DISCOUNT`。
  - `pick` 根据 `RuleEngineBackend#supports` 与 `getOrder` 选后端。
- `QlExpressRuleBackend`
  - 依赖 `ExpressRunner activityExpressRunner` 和 `ActivityRuleExpressionService`。
  - 处理 `ELIGIBILITY`、`DISCOUNT_COMPUTE`、`LADDER`、`GIFT`。
  - 资格表达式异常 fail-closed，金额表达式异常跳过，奖品表达式异常保留奖品。
- `DroolsRuleBackend`
  - 依赖 `KieBaseManager`。
  - 使用 `StatelessKieSession`，global 名是 `result`。
  - 将 `ActivityRuleContext` 和所有 `ActivityCandidate` 作为 facts 执行。
- `KieBaseManager`
  - 按 `scene.getCode() + ":" + bizLine` 缓存 `KieBase`。
  - 通过 `RuleSource#version` 判断是否重建。
- `RuleCompiler`
  - 支持 `DRL`、`DECISION_TABLE`、`TEMPLATE` 三种 `RuleDefinition`。
  - 最终统一转 DRL，再用 `KieHelper` 编译。
- `ClasspathRuleSource`
  - 加载 `rules/{scene}/{bizLine}.drl`，再 fallback 到 `rules/{scene}/default.drl`。
- `DiscountDbRuleSource`
  - 只接管 `DISCOUNT`。
  - 通过 `ActivityRuleStrategyService#getStrategy` 或 yml `activity.rule-engine.discount.strategy.*` 生成 `MAX/MUTEX/STACK/PRIORITY` DRL。

### 来源数据模型

已确认实体：

- `ActivityAdminPlatformManage` -> `activity_admin_platform_manage`
- `ActivityDynamicRules` -> `activity_dynamic_rules`
- `ActivityAdminStoreSpuProduct` -> `activity_admin_store_spu_product`
- `ActivityAdminPlatformManageConfigs` -> `activity_admin_platform_manage_configs`
- `ActivityRuleExpression` -> `activity_rule_expression`
- `ActivityRuleStrategy` -> `activity_rule_strategy`
- `ActivityRuleFieldDict` -> `activity_rule_field_dict`
- `ActivityProductPool` -> `activity_product_pool`
- `ActivityProductPoolItem` -> `activity_product_pool_item`
- `ActivityProductPoolRule` -> `activity_product_pool_rule`
- `ActivityPoolRef` -> `activity_pool_ref`

待验证：

- 表唯一键、索引、默认值、历史数据规模。
- 枚举 `ActivityTypeEnums`、`ActivityStatusEnums`、`ActivityDistributionEnums` 的完整 code/name。
- `activity_product_pool_item` 与真实商品表的增量刷新机制。

### 当前项目受影响文件清单

若实施最终方案，预计受影响文件如下：

- 新增：`src/main/java/com/lrj/drools/activity/**`
- 新增：`src/main/resources/rules/activity/**`
- 修改：`src/main/resources/static/index.html`
- 修改：`src/main/resources/static/assets/app.js`
- 修改：`src/main/resources/static/assets/examples.js`
- 修改：`src/main/resources/static/assets/styles.css`
- 修改：`src/main/resources/application.yml`
- 修改：`src/main/resources/application-h2.yml`
- 修改：`src/main/resources/application-mysql.yml`
- 修改或保留并兼容：`src/main/java/com/lrj/drools/controller/CampaignController.java`
- 修改或保留并兼容：`src/main/java/com/lrj/drools/service/CampaignService.java`
- 修改或保留并兼容：`src/main/java/com/lrj/drools/persistence/CampaignEntity.java`
- 新增测试：`src/test/java/com/lrj/drools/activity/**`

本次规划未修改上述业务文件。
