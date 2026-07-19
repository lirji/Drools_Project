# 01 Requirements

## requirements-analyst 视角

### 任务目标

把来源项目 `/Users/liruijun/personal/work/autohome/autolife/mall-shop/src/main/java/com/suisung/mall/shop` 中“活动营销”相关逻辑，在当前 `drools-demo` 项目中放一份可运行、可演示、可改造的版本，使前端可以完整走一遍：

- 创建活动。
- 以报表/表单提交方式配置活动基础信息、奖励规则、商品绑定、资格条件。
- 制定规则并验证规则效果。
- 查询商品命中的活动、优惠金额、资格过滤结果。
- 对比不同改造方向对服务端模型、接口、前端交互的影响。

本次只做分析与规划，不改业务代码。本文档及同目录其它文档均仅写入 `docs/plans/activity-marketing-port-0712-1917/`。

### 已确认的来源业务规则

结论均来自实际代码阅读，未能从代码确认的点标记为“待验证”。

- 当前仓库已有活动资格 demo：`CampaignController` 暴露 `/campaign/create`、`/campaign/{id}/check`、`/campaign/{id}/end`、`/campaign/list`；`CampaignService` 将活动 DRL 存入 `CampaignEntity`，并用 `KieHelper` 编译到 `ConcurrentHashMap<String, KieBase>`。
- 来源项目活动基础表实体是 `ActivityAdminPlatformManage`，表名 `activity_admin_platform_manage`，字段包含 `activityId`、`activityName`、`bizLine`、`activityType`、`activityRule`、`activityPartnerId`、`activityRelationProfitNo`、`activityStartTime`、`activityEndTime`、`activityStatus`、`version`、`isDel`、`inventory`、`userInventory`、`activityAreaType`、`districtIds`、`priority` 等。
- 来源项目规则详情实体是 `ActivityDynamicRules`，表名 `activity_dynamic_rules`，字段包含 `activityId`、`activityType`、`redPackageTakeType`、`redPackageAmount`、`redPackageAmountUnit`、`redPackageRangeAmount`、`version`、`isDel`。
- 来源项目活动与商品绑定实体是 `ActivityAdminStoreSpuProduct`，表名 `activity_admin_store_spu_product`，读取侧按 `isDel=0` 且 `effective=1` 过滤。
- 来源项目创建活动入口实际在 `ActivityAdminPlatformManageServiceImpl#create`，不是控制器方法直接暴露；现有 `ActivityAdminPlatformManageController` 只有空控制器壳。
- 来源项目创建活动链路：`ActivityCommonValidator.validate` 通用校验 -> `ActivityTypeRegistry.getPlugin` 类型校验 -> `saveByVersion` 保存基础层与规则层 -> `saveActivityRelations` 保存商品绑定 -> `persistConfigs` 保存拓展配置 -> `publishEligibilityRule` 将 `eligibilityConditionTree` 写入 `activity_rule_expression` -> 发布 `ActivityChangedEvent`。
- 来源项目编辑活动采用版本化策略：`saveByVersion` 对同 `activityId/version` 的旧行置 `isDel=1`，再插入 `version+1` 新行；已下线活动不可修改；已上线活动禁止修改开始时间。
- 来源项目活动上线/下线在 `ActivityAdminPlatformManageServiceImpl#status`，会校验状态枚举、业务线权限、红包 `signKey`、活动时间与权益系统红包有效期。权益系统相关 provider 在本仓库不存在，移植时必须替换为 demo adapter 或标记跳过。
- 来源项目活动过滤入口是 `ActivityAdminPlatformManageServiceImpl#filterBeginActivityIds`：默认旧逻辑为 `activityStatus == ONLINE` 且当前时间在活动时间范围内；开启 `activity.rule-engine.eligibility.enabled` 后走 `ActivityRuleEngine#evalEligibility`，异常或空决策回退旧逻辑。
- 来源项目商品优惠查询入口是 `ActivityDynamicRulesServiceImpl#getSpuDiscount` 与 `#getActivityDynamicRulesBySpuId`：先查商品绑定，再过滤生效活动，再查规则详情，旧逻辑取最大优惠金额；开启 `activity.rule-engine.discount.enabled` 后规则引擎结果覆盖旧结果，异常或无命中回退旧逻辑。
- 来源项目买赠奖品入口是 `ActivityDynamicRulesServiceImpl#queryBuyAndGetActiveActivityBySpuId`：开关 `activity.rule-engine.gift.enabled` 控制是否走 `ActivityRuleEngine#evalGift`；无决策或异常回退旧逻辑。
- 来源项目规则引擎门面是 `ActivityRuleEngine`，实现是 `RoutingActivityRuleEngine`，按 `RuleEngineBackend` 路由：`QlExpressRuleBackend` 高优先处理单点决策，`DroolsRuleBackend` 作为兜底处理多活动合并和未迁移场景。
- 来源项目 Drools 规则缓存由 `KieBaseManager` 负责，按 `scene + bizLine` 缓存 `KieBase`，规则版本变化时用 `ConcurrentHashMap.compute` 原子重建。
- 来源项目规则可视化构建器输入是 `ConditionNode`，由 `RuleConditionTranslator` 通过 `FieldDictionary` 白名单翻译成 QLExpress 表达式；`ActivityRuleConditionPublisher#publishEligibility` 将表达式按 `(bizLine, scene, activityType, activityId, code)` upsert 到 `activity_rule_expression`。
- 来源项目默认 DRL 包含 `rules/eligibility/default.drl`、`rules/discount/default.drl`、`rules/gift/default.drl`、`rules/ladder/ladder-template.drt`。

### 边界条件

- 必须保持当前 `drools-demo` 可学习、可运行的轻量定位，不能把来源项目的商城全链路依赖直接当作必需项。
- 现有默认 profile 是 MySQL，备用 H2；规划应能让演示优先用 H2 跑通。
- 当前前端是静态单页 demo：`index.html`、`assets/app.js`、`assets/examples.js`、`assets/styles.css`，不是 Vue/React 工程。
- 当前后端用 Spring Boot 3.3.5、Java 21、JPA；来源项目用 MyBatis-Plus、`javax.annotation.Resource`、`mall-common` 实体、`CommonNewResult`、`ApiException`、`ContextUtil` 等依赖。直接复制不可编译。
- 来源项目实际数据库 DDL 和索引未在已读文件中发现，表结构只能基于实体与 mapper XML 推断，索引、唯一键、事务隔离级别标为待验证。
- 来源项目外部系统包括权益系统 `CouponSystemProvider`、钉钉通知、当前登录用户、序列号服务、商品/车辆表、用户地区来源。当前项目没有这些系统。

### 非目标

- 不在本次规划中实现代码。
- 不要求还原来源项目全部活动类型、全部移动端活动、全部订单/权益/商品/用户依赖。
- 不迁移真实生产数据。
- 不保证来源项目线上行为 100% 等价，除非选择“原样镜像方案”并补齐外部依赖。
- 不建设正式后台权限、审批、审计、消息通知体系；可在 demo 中留 adapter 和审计日志。

### 歧义与易遗漏点

- “活动营销部分”范围待确认：来源项目除 `activity` 包外，`plantform`、`store`、`user voucher`、`order coupon` 也有活动/优惠相关接口。本计划默认优先迁移 `activity` 包中的平台活动、动态规则、商品绑定、规则引擎闭环。
- “前端报表提交格式”待确认：可理解为后台运营表单、分段报表式录入、或类似 Excel/表格批量提交。本计划按“单页内分区表单 + 条件树/表格明细 + JSON 预览/提交”设计。
- 活动类型枚举值来自 `ActivityTypeEnums`，但枚举源码未在本次读取输出中完整展开；具体 code 与中文名待执行阶段读取并确认。
- `districtIds` 的粒度待验证：来源默认 DRL 假设是城市 id CSV；若实际是省/市/区路径，需要调整匹配口径。
- `redPackageRangeAmount` 既可表示随机红包区间，也可用于阶梯配置；格式待验证。
- 库存扣减只看到 `ActivityAdminPlatformManageMapper#lock(Long id, int quantity)`，未看到完整调用链；是否需要在 demo 中模拟领券/占用库存待业务确认。

### 验收标准

- 规划文档能让另一个开发 Agent 直接执行，不需要重新阅读需求。
- 最终方案明确新增/修改文件、类、方法、接口、配置、数据结构、测试范围、灰度与回滚。
- 演示闭环至少覆盖：活动创建、版本化编辑、上下线、规则条件配置、商品绑定、资格过滤、优惠计算、规则命中追踪、列表/详情查询。
- 所有不确定项明确标“待验证”，不伪造来源项目不存在的接口、字段、表。
