# Final Plan

## 背景、目标与非目标

当前 `drools-demo` 已有 Step 18 的轻量营销活动资格判定：`CampaignController` + `CampaignService` + `CampaignEntity`。它能保存一段 DRL 并判断 `UserProfile` 是否产出 `Eligibility`，但不具备来源项目的活动基础层、规则详情层、商品绑定层、版本化编辑、多活动策略、规则条件构建器和报表式创建流程。

目标是在当前项目中新增一份可运行的活动营销 demo 模块，让前端可以走完整活动创建、规则制定、商品优惠验证流程，并为后续服务端改造提供真实落点。

非目标：不复制来源项目全部依赖，不接真实权益系统/钉钉/商品库/用户系统，不迁移生产数据，不在本次任务改业务代码。

## 已确认的业务规则

- 活动基础信息来自来源 `ActivityAdminPlatformManage`：活动 id、名称、业务线、类型、规则说明、开始/结束时间、状态、版本、库存、地域、优先级。
- 规则详情来自 `ActivityDynamicRules`：红包发放方式、金额、区间、版本。
- 商品绑定来自 `ActivityAdminStoreSpuProduct`：按 `spuId` 关联活动，读取侧只取 `isDel=0/effective=1`。
- 创建链路应按“通用校验 -> 类型校验 -> 保存基础层和规则层 -> 保存商品绑定 -> 保存拓展配置 -> 发布资格条件”的顺序。
- 编辑采用版本化：旧版本逻辑删除，新版本 `version+1`。
- 查询生效活动的旧逻辑是“已上线且当前时间在活动时间范围内”。
- 优惠计算旧逻辑是同 SPU 多活动取最大金额。
- 规则引擎可覆盖资格过滤、优惠合并、阶梯、奖品；异常或空决策应回退旧逻辑。

已确认（Claude 复核，读 `mall-common/.../common/enums` 与 `activity/engine/builder`）：

- `ActivityStatusEnums`：`NORMAL(0,待上线)` / `ONLINE(1,已上线)` / `OFFLINE(2,已下线)` / `PENDING_EFFECT(3,待生效)`。
- `ActivityTypeEnums`：`RED_PACKAGE(1)` / `COUPONS(2)` / `CPS(3)` / `RIGHT_COUPON(4)` / `BUY_AND_GET(5)`。**本 demo 首期打通 `RED_PACKAGE` 与 `BUY_AND_GET`**（见文末「范围扩展」；`COUPONS/CPS/RIGHT_COUPON` 暂不做）。规则场景覆盖 eligibility / discount / ladder / gift 四类。
- `ActivityDistributionEnums`（红包发放方式）：`FIXED_AMOUNT(1,固定金额)` / `RANDOM_AMOUNT(2,随机金额)`。
- 资格条件字段白名单在来源是 DB 表 `activity_rule_field_dict` + `FieldDictionary` + `RuleConditionTranslator`：`ConditionNode` → 受控 DRL，字段/运算符不在白名单直接 `ApiException`。**本 demo 采用同款「白名单 + 受控翻译」，绝不接受运营直接提交 DRL 文本。**

仍待验证（对 demo 影响小，可实施期确定）：真实 DDL/唯一键/索引、`districtIds` 粒度、`redPackageRangeAmount` 区间格式。

## 当前代码与调用链分析

当前项目可复用：

- `CampaignService#compile` 和 `HotReloadService#upsert` 的运行时 DRL 编译。
- `ScannerService` 的 KieBase/KJAR 热替换思想。
- `DroolsConfig#kieContainer` 的资源加载方式。
- `GuardService` 的灰度与护栏思路。
- 静态前端 `examples.js` + `app.js` 的声明式请求面板。
- JPA + H2/MySQL profile。

来源项目关键链路：

- `ActivityAdminPlatformManageServiceImpl#create`
- `ActivityAdminPlatformManageServiceImpl#saveByVersion`
- `ActivityAdminPlatformManageServiceImpl#status`
- `ActivityAdminPlatformManageServiceImpl#filterBeginActivityIds`
- `ActivityDynamicRulesServiceImpl#getSpuDiscount`
- `ActivityDynamicRulesServiceImpl#getActivityDynamicRulesBySpuId`
- `ActivityRuleEngine` / `RoutingActivityRuleEngine`
- `DroolsRuleBackend` / `KieBaseManager` / `RuleCompiler`
- `ConditionNode` / `RuleConditionTranslator` / `ActivityRuleConditionPublisher`

## 候选方案对比与评分

| 方案 | 正确性 | 风险 | 复杂度收益 | 维护性 | 扩展性 | 结论 |
|---|---:|---:|---:|---:|---:|---|
| A 收敛移植 | 4 | 4 | 3 | 4 | 4 | 推荐 |
| B 原样镜像 | 5 | 1 | 1 | 2 | 5 | 不推荐作为主线 |
| C 流程模拟器 | 2 | 5 | 5 | 3 | 2 | 只适合 UI 快速验证 |
| D 外部代理 | 4 | 3 | 3 | 3 | 3 | 可作为二期对照 |

最终选择 A，并吸收 B 的分层语义、C 的前端快速反馈、D 的后续对照能力。

已知弱点：不是 100% 还原来源项目；初版不强制引入 QLExpress；商品池自动圈选需要 demo 商品表替代真实商品表。

## 最终方案

新增 `com.lrj.drools.activity` 自包含模块，用 JPA 实体和 Drools 规则引擎复刻来源活动营销核心闭环。

### 精确修改清单

新增文件：

- `src/main/java/com/lrj/drools/activity/controller/ActivityMarketingController.java`
  - 新增 `create(ActivityCreateRequest req)`
  - 新增 `changeStatus(String activityId, ActivityStatusRequest req)`
  - 新增 `list()`
  - 新增 `detail(String activityId)`
  - 新增 `spuDiscount(SpuDiscountRequest req)`
  - 新增 `preview(ActivityCreateRequest req)`
- `src/main/java/com/lrj/drools/activity/service/ActivityMarketingService.java`
  - 新增 `create(ActivityCreateRequest req)`
  - 新增 `updateByVersion(ActivityCreateRequest req)`
  - 新增 `changeStatus(String activityId, Integer version, Integer status)`
  - 新增 `getDetail(String activityId)`
  - 新增内部方法 `validateCommon`、`saveNewVersion`、`saveBindings`、`saveCondition`
- `src/main/java/com/lrj/drools/activity/service/ActivityQueryService.java`
  - 新增 `filterBeginActivityIds(Set<String> activityIds, ActivityUserContext ctx)`
  - 新增 `spuDiscount(SpuDiscountRequest req)`
  - 新增内部方法 `legacyFilterBeginActivityIds`、`legacyMaxDiscount`
- `src/main/java/com/lrj/drools/activity/service/ActivityRuleRuntimeService.java`
  - 新增 `evalEligibility(ActivityRuleContext ctx)`
  - 新增 `evalDiscount(ActivityRuleContext ctx)`
  - 新增 `compileOrGet(String scene, String bizLine, String version, String drl)`
  - 新增 `evict(String scene, String bizLine)`
- `src/main/java/com/lrj/drools/activity/domain/ActivityCreateRequest.java`
- `src/main/java/com/lrj/drools/activity/domain/ActivityStatusRequest.java`
- `src/main/java/com/lrj/drools/activity/domain/SpuDiscountRequest.java`
- `src/main/java/com/lrj/drools/activity/domain/ActivityUserContext.java`
- `src/main/java/com/lrj/drools/activity/domain/ConditionNode.java`
- `src/main/java/com/lrj/drools/activity/domain/ActivityCandidate.java`
- `src/main/java/com/lrj/drools/activity/domain/ActivityRuleContext.java`
- `src/main/java/com/lrj/drools/activity/domain/ActivityRuleResult.java`
- `src/main/java/com/lrj/drools/activity/domain/ActivityStackStrategy.java`
- `src/main/java/com/lrj/drools/activity/persistence/ActivityManageEntity.java`
- `src/main/java/com/lrj/drools/activity/persistence/ActivityRuleEntity.java`
- `src/main/java/com/lrj/drools/activity/persistence/ActivitySpuBindingEntity.java`
- `src/main/java/com/lrj/drools/activity/persistence/ActivityConditionEntity.java`
- `src/main/java/com/lrj/drools/activity/persistence/ActivityStrategyEntity.java`
- 对应 Repository 文件。
- `src/main/resources/rules/activity/eligibility/default.drl`
- `src/main/resources/rules/activity/discount/default.drl`
- `src/test/java/com/lrj/drools/activity/**`

修改文件：

- `src/main/resources/static/assets/examples.js`：新增活动营销 demo 分组和请求样例。
- `src/main/resources/static/assets/app.js`：新增报表式表单渲染、动态行、条件树编辑、活动摘要渲染。
- `src/main/resources/static/assets/styles.css`：新增表单、表格、条件构建器样式。
- `src/main/resources/application.yml`：新增 `activity.marketing.*` 开关。
- `src/main/resources/application-h2.yml`、`application-mysql.yml`：必要时补 JPA 初始化或兼容配置。

保留兼容：

- `CampaignController`、`CampaignService`、`CampaignEntity` 不作为主链路修改对象；可在二期将 Step 18 链接到新模块，但初期保持原 demo 不破。

跨模块兼容约束：

- 不把新规则资源加入现有 `META-INF/kmodule.xml` 的既有 kbase，避免影响 Step 1-18。
- 新模块运行时编译自己的 DRL，优先复用 `KieHelper` 路径；只有在需要 classpath 固定规则时，再新增独立 kbase。
- 不复用 `CampaignEntity` 表存新活动，避免新旧活动状态、DRL 语义混在一个表里。
- 前端新增分组时不改变现有 demo id、path、summary key，避免已有演示入口失效。

### 数据库、接口、配置、消息结构变更

新增 JPA 表：

- `activity_manage`
- `activity_rule`
- `activity_spu_binding`
- `activity_condition`
- `activity_strategy`

建议索引：

- `activity_manage(activity_id, version, is_del)`
- `activity_manage(request_id)`，当启用幂等时唯一。
- `activity_manage(activity_status, activity_start_time, activity_end_time)`
- `activity_spu_binding(spu_id, effective, is_del)`
- `activity_rule(activity_id, version, is_del)`
- `activity_condition(activity_id, version, scene, enabled)`

数据迁移策略：

- 当前项目没有来源活动数据，初次实施只新增空表，不迁移 `campaign` 表。
- 如需要把 Step 18 `campaign` 数据迁到新表，必须单独执行迁移脚本：`campaign_id -> activity_id`、`name -> activity_name`、`eligibility_drl -> activity_condition.raw_drl`，其它字段用默认值；该迁移不进入第一阶段。
- `spring.jpa.hibernate.ddl-auto=update` 只适合 demo；若将来切到共享 MySQL，需改为显式 SQL 脚本并补唯一键、索引、字段默认值。
- 回滚时不删除新增表，避免误删演示数据；仅关闭入口和开关。

新增接口：

- `POST /activity-marketing/create`
- `POST /activity-marketing/{activityId}/status`
- `GET /activity-marketing/list`
- `GET /activity-marketing/{activityId}`
- `POST /activity-marketing/spu-discount`
- `POST /activity-marketing/preview`

新增配置：

- `activity.marketing.rule-engine.enabled`
- `activity.marketing.discount.strategy.default`
- `activity.marketing.cache.enabled`
- `activity.marketing.idempotency.enabled`
- `activity.marketing.preview.compile-timeout-ms`

消息结构：初版不接 MQ。活动变更只写本地审计日志；如后续需要，可新增 after-commit 应用事件，参考来源 `ActivityChangedEvent`。

事务、并发、幂等约束：

- `ActivityMarketingService#create` 和 `updateByVersion` 必须加 `@Transactional(rollbackFor = Exception.class)`。
- 规则编译和条件树校验必须发生在写库前；失败不得落任何表。
- 编辑时用 `(activityId, version, isDel=0)` 查询当前版本；更新旧版本为 `isDel=1` 时检查影响行数，影响行数为 0 返回 409，防并发双写。
- 建议 `activity_manage` 增加 `requestId` 幂等字段；同 `requestId` 重复提交直接返回首次结果。
- 库存扣减不进入创建闭环；如果后续加入领取/占用动作，必须采用类似来源 `ActivityAdminPlatformManageMapper#lock(Long id, int quantity)` 的条件更新：库存剩余不足时影响行数为 0。
- `ActivityRuleRuntimeService` 缓存替换必须原子化；正在执行的无状态 session 使用旧 `KieBase` 跑完，下一请求读取新缓存。

## 分阶段实施步骤

### 阶段 1：数据结构与领域模型

1. 新增 JPA 实体、Repository、request/response、fact 对象。
2. 建立字段映射表，注释标明来源实体字段。
3. 增加基础枚举：状态、发放方式、合并策略。
4. 增加空表启动数据：至少一组字段字典或内置字段白名单，支撑条件树选择。

完成标准：H2 启动自动建表，实体字段能覆盖创建、绑定、规则、策略、条件。

### 阶段 2：核心业务逻辑

1. 实现 `ActivityMarketingService#create`、版本化编辑、上下线。
2. 实现通用校验、类型校验骨架。
3. 实现 `ActivityQueryService#spuDiscount`。
4. 实现规则缓存和 Drools 执行，先支持资格与折扣。
5. 实现异常回退旧逻辑和 trace。
6. 实现请求幂等和并发编辑冲突返回。

完成标准：服务层测试覆盖创建、编辑、上下线、查询、规则回退。

### 阶段 3：接口与适配层（含前端报表页）

服务端接口：

1. 新增 `ActivityMarketingController`（create / status / list / detail / spu-discount / preview 六个端点）。
2. 定义前端需要的 list/detail/preview 响应 DTO。
3. 条件树以 JSON 落库（`activity_condition`），经受控翻译层（字段/运算符白名单）生成 DRL 片段；**不接受前端直接提交 DRL**。
4. 加入幂等键（requestId）或编辑冲突检测（旧版本行 `isDel` 更新影响行数校验）。

前端报表页（用户头号诉求，单列为一块）：

5. 复用现有声明式框架：`examples.js` 增「活动营销」`group` + demo 条目。但**创建/制定规则页不是现有的 JSON textarea 面板**——需在 `app.js` 增一个报表式表单渲染器（分区：活动基础信息 / 红包规则 / 商品绑定明细（可增删行）/ 资格条件树（AND-OR + 字段下拉 + 运算符 + 值，可增删行）/ 合并策略），提交时前端组装成 `ActivityCreateRequest` JSON。
6. 验证视图：填 SPU 优惠查询表单 → 调 `/activity-marketing/spu-discount` → 渲染命中活动、金额、命中策略、trace。
7. 表单下拉选项（活动类型 / 状态 / 发放方式 / 条件字段 / 运算符）由后端字典接口或前端内置常量提供，与后端枚举/字段字典对齐，避免漂移。

> 注：报表式表单 + 条件树构建器属于「中型前端特性」。若实施时想更严谨，可先对这一块走 `/frontend-plan` 出页面级方案（组件树 / 状态 / 边界）；否则按上面轮廓直接在 `app.js` 里用原生 DOM 实现（与现有 `el()` / `renderSummary` 风格一致，不引入前端框架）。

完成标准：curl 可完成完整闭环；浏览器里可用报表表单完成「创建活动 → 上线 → 查 SPU 优惠 → 改规则再查 → 下线不命中」。

### 阶段 4：测试

1. 添加单元测试和 H2 集成测试。
2. 添加规则编译失败、事务回滚、并发编辑测试。
3. 手工或 Playwright 验证前端表单。

完成标准：`./mvnw test` 通过，H2 profile 可启动。

### 阶段 5：文档与最终检查

1. 更新 README 或新增活动营销说明。
2. 补接口样例。
3. 对照来源项目字段，列出未迁移项。

完成标准：另一个开发者可按文档运行、创建、验证活动。

## 测试方案

采用 `test-plan.md`。最低验收是：

- 创建活动成功。
- 编辑版本正确。
- 下线后不命中。
- 多活动 `MAX/PRIORITY/STACK` 至少各有一个测试。
- 条件树非法时事务回滚。
- 前端可跑通创建到查询闭环。

## 风险、监控、灰度与回滚

风险：

- 字段语义与来源未来变更漂移。
- 条件树转规则存在安全边界。
- 并发编辑版本冲突。
- 缓存旧规则未及时刷新。
- H2 与 MySQL 行为差异，尤其时间、唯一键、长文本、条件 SQL。

监控：

- 记录规则编译失败次数。
- 记录规则执行耗时、命中策略、回退次数。
- 复用 Micrometer 风格，避免将 `activityId/spuId` 放入无限基数 tag。

灰度：

- 默认 `activity.marketing.rule-engine.enabled=false` 时走旧 Java 逻辑。
- 开启后规则结果覆盖旧结果，并记录差异。
- 前端保留“预览”接口，正式保存前先验证。

回滚：

- 关闭配置开关恢复旧逻辑。
- 前端隐藏活动营销分组。
- 新增表不影响原 Step 18；无需回滚原 `campaign` 表。
- 若新接口已被前端使用，保留接口但返回维护状态比删除接口更安全；删除入口只做最后清理。

## 最终验收清单

- [ ] 未修改现有 Step 1-18 的行为。
- [ ] H2 profile 完整跑通。
- [ ] 活动创建、编辑、上下线、列表、详情接口可用。
- [ ] SPU 优惠查询返回命中活动、金额、策略、trace。
- [ ] 条件树和规则编译失败不会部分落库。
- [ ] 并发编辑有冲突保护。
- [ ] 前端表单无明显重叠，错误可读。
- [ ] 文档列出来源映射和未迁移项。

## 架构师复核结论

复核后修正两点：

- 不建议初期修改 `CampaignService`，避免 Step 18 教学链路被新模块复杂度污染。
- QLExpress 不进入第一阶段硬依赖；先保留接口和数据结构，等确认“条件树必须落 QL 表”后再引入，降低依赖与安全风险。

## Claude 跨模型复核修正记录（2026-07-12）

对照两个真实仓库逐条核验（`drools-demo` 直接读源码；`mall-shop` 用只读子代理核验，均命中真实文件/行号）。结论：Codex 方案的类名/方法名/调用链**高度准确、无虚构**——rule-engine 相关类（`ActivityRuleEngine`/`RoutingActivityRuleEngine`/`RuleEngineBackend`/`QlExpressRuleBackend`/`DroolsRuleBackend`/`KieBaseManager`/`RuleCompiler`/`ClasspathRuleSource`/`DiscountDbRuleSource`/`ConditionNode`/`RuleConditionTranslator`/`ActivityRuleConditionPublisher`）全部命中真实路径；`drools-demo` 侧 `Campaign*`、`UserProfile`/`Eligibility`、前端 `examples.js`/`app.js`、`ddl-auto=update`、JPA 依赖亦确认无误。据此修正如下（附原因）：

1. **补全并确认枚举取值**（原「待验证」）：见「已确认的业务规则」。原因：真值已读到，阶段 1 领域模型直接用真实 code，减少返工。
2. **明确资格条件走「字段白名单 + 受控翻译」而非运营裸写 DRL**（来源为 DB 表 `activity_rule_field_dict` + `FieldDictionary` + `RuleConditionTranslator`）。原因：安全边界是「制定规则」诉求的核心，须前置为默认设计，不能留成可选项。
3. **前端报表页从 4 个 bullet 提升为阶段 3 的独立一块并给出表单分区轮廓**。原因：「前端走一遍活动创建/制定规则 + 页面重做成报表提交格式」是用户头号诉求，原计划把它折进接口层且承认无前端测试框架，权重明显不足。
4. **新增「来源存在但本次不迁移」清单**（见下）。原因：来源 `activity` 模块远大于红包/折扣主链路；显式列出被排除能力，避免误以为「整包迁移」，也便于用户挑选是否纳入。
5. 记录两处不影响 demo 的细节偏差：`publishEligibilityRule` 是 `conditionTree == null` 空值判断（非 emptiness）；`KieBaseManager` 的 bizLine 为空时用 `_default_` 占位。

## 来源存在但本次不迁移（Claude 复核补充）

以下能力在来源 `activity` 模块**真实存在**，但不在本次移植范围（如需再单独排期）：

- 其它营销玩法：砍价 `Cutprice`、拼团 `Groupbooking`、门店拼团 `GroupbuyStore/PfGroupbuyStore`、抽奖 `Lottery`、`Marketing` 历史链路。
- 订单分润 / CPS：`ActivityAdminOrderProfitRecord*`。
- 红包合伙人签名校验：`AdminActivityPartnerSignkeyRelationService`。

> 原属此列表的「商品池自动圈选」「阶梯 LADDER」「决策表资格」「买赠 BUY_AND_GET / 配置插件 / 履约」已由用户确认**纳入首期**，详见文末「范围扩展」。

## 范围扩展（用户确认 2026-07-12，本节权威覆盖前文的"只做红包/两个场景"表述）

用户在方案 A 批准后追加确认：首期除「红包 eligibility + discount」外，**再纳入以下四项来源能力**。原因：用户核心诉求是"走一遍**制定规则**流程、研究怎么改造"，规则制定面越丰富越有价值；四项全上使 demo 接近来源活动引擎的真实形态。

纳入的四项及其来源落点：

1. **阶梯优惠 LADDER** — 对应来源 `engine/source/LadderRuleSource`（`LadderRangeParser` / `LadderRuleRow`）。新增 `ladder` 规则场景与「满 X 减/折 Y」分档配置。
2. **决策表资格判定** — 对应来源 `engine/source/EligibilityDecisionTableRuleSource`。资格判定可选走 Excel `.xls` 决策表；**复用本项目 Step 7 `drools-decisiontables` + `DTABLE` 加载能力**。
3. **买赠 BUY_AND_GET** — 对应来源 `plugin/impl/BuyAndGetActivityPlugin`、`plugin/config/impl/BuyAndGetConfigPlugin`、`GiftFulfillmentResolver`、`queryBuyAndGetActiveActivityBySpuId` / `evalBuyAndGetGiftsByRule`。新增 `gift` 规则场景与买赠配置/履约。
4. **商品池自动圈选** — 对应来源 `service/ActivityPoolMatchService` / `ActivityProductPool(Item/Rule)Service` / `ActivityPoolRefService` / `ActivityAutoBind{Guard,Refresh}Service`。规则驱动圈选商品并自动绑定活动。

### 扩展后的新增数据表（在原 5 张基础上追加）

- `activity_product_pool` / `activity_product_pool_item` / `activity_product_pool_rule` / `activity_pool_ref`（商品池 + 圈选规则 + 活动关联）。
- `demo_product`（**本项目自带的 demo 商品表**，替代来源真实商品库，供商品池圈选有数据可选；来源用真实商品表，这里用最小字段：spuId / 名称 / 类目 / 价格 / 标签）。
- 买赠配置：优先复用 `activity_rule`（`activity_type` 区分）+ 一张 `activity_gift`（赠品明细：spuId / 数量 / 门槛），避免过度建表。
- 决策表资格：**不新增表**，`.xls` 决策表作为 classpath 资源放 `resources/rules/activity/eligibility/*.xls`。

### 扩展后的规则场景（`ActivityRuleScene`）

`ELIGIBILITY`（DRL 或决策表）/ `DISCOUNT`（合并策略 MAX/MUTEX/STACK/PRIORITY）/ `LADDER`（分档）/ `GIFT`（买赠）——对齐来源 `ActivityRuleEngine` 的四个 eval 方法。

### 扩展后的阶段增量

- **阶段 1** 追加：商品池 4 表 + `demo_product` + `activity_gift` 实体/Repo；`ActivityRuleScene` 增 LADDER/GIFT；阶梯/买赠/圈选相关 fact 与 request 字段。
- **阶段 2** 追加：`ActivityPoolMatchService`（圈选）+ auto-bind 服务；`gift` / `ladder` 场景的规则来源与执行；买赠查询链路 `queryBuyAndGet...` 收敛版。
- **阶段 3（前端）**：**按用户选择，先走 `/frontend-plan`** 对报表页（活动基础 / 红包 / 阶梯分档 / 买赠赠品 / 商品绑定或商品池圈选 / 资格条件树 / 合并策略）单独出页面级方案，评审通过后再在 `app.js` 实现；不引入前端框架。
- **阶段 4** 追加：LADDER 分档、GIFT 买赠、决策表资格、商品池圈选各自的服务/集成测试。

### 扩展的已知代价（如实标注）

- 工作量显著上升（表更多、场景更多、圈选子系统有并发/幂等面），阶段 1、2 会更重；建议每完成一个场景就冒烟一次（DRL 运行时编译，`mvn compile` 不校验规则语法——见 CLAUDE.md 坑 4/6）。
- 决策表 `.xls` 需二进制资源，测试里用 Step 7 已有的 `VipDiscountSheetGenerator` 同款思路生成，避免手工二进制文件。
- `demo_product` 是为圈选造的替身表，非来源真实模型；文档需说明它不代表生产商品域。
