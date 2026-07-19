# 04 Solution B - 原样镜像来源项目活动包并补齐适配层

## architecture-designer 视角

### 架构

将来源项目 `com.suisung.mall.shop.activity` 包及相关 `mall-common` 活动实体、BO、DTO、Mapper XML 尽量原样复制到当前仓库，再为缺失依赖建立兼容层：

- MyBatis-Plus 或等价 Repository。
- `CommonNewResult`、`ApiException`、枚举、`ContextUtil` 等公共类。
- `ShopNumberSeqService`、`CouponSystemProvider`、`ActivityNotifier` 等外部依赖 mock/stub。
- QLExpress、Drools 7/8 兼容处理。

### 模块职责

- `com.suisung.mall.*` 兼容包：最大程度保留来源代码路径。
- `com.lrj.drools.adapter`：将当前项目配置、数据源、用户上下文适配到来源代码。
- 前端新增管理页面，但接口路径尽量沿用来源项目 `/admin/shop/...` 和 `/mobile/shop/...`。

### 核心流程

基本复刻来源项目：

1. 前端调用 `/admin/shop/activity-admin-platform-manage` 的新增 create/list/status 接口。
2. 原服务链路执行 `ActivityAdminPlatformManageServiceImpl#create`。
3. 规则引擎仍用 `RoutingActivityRuleEngine`、`QlExpressRuleBackend`、`DroolsRuleBackend`。
4. 查询侧沿用 `ActivityDynamicRulesServiceImpl#getSpuDiscount`。

### 改动范围

很大。来源活动代码依赖 `mall-common`、`mall-core`、MyBatis-Plus、Hutool、Fastjson、Swagger、QLExpress、业务枚举、商品表、用户上下文、权益系统。

### 扩展性

对还原来源项目最强，但对当前 `drools-demo` 的学习型代码风格侵入大，维护成本高。

### 实施成本

高。主要成本不在活动逻辑本身，而在补齐公共基础设施和修复依赖版本冲突。

## risk-reviewer 视角

- 兼容性：Spring Boot 3 + Java 21 与来源项目可能使用的 `javax.*`、Drools 7、MyBatis-Plus 版本存在冲突风险。
- 事务：可保留来源 `@Transactional` 语义，但需要确认 MyBatis-Plus/JPA 是否混用。
- 并发：最大程度继承来源风险；`saveByVersion` 无明显唯一约束，仍需补。
- 性能：可继承 mapper 查询，但当前 H2 不支持 MySQL `FIND_IN_SET` 等 SQL，需要分支适配。
- 安全：如果直接保留 QLExpress，需要评估表达式执行白名单。
- 数据迁移：需要建大量表，DDL 待验证。
- 回滚：代码侵入大，回滚成本最高。

## test-designer 视角

除方案 A 测试外，还必须加：

- 兼容层 mock 行为测试。
- MyBatis XML 在 H2/MySQL 双库下的兼容测试。
- 来源服务方法迁移前后行为对照测试。
- 依赖升级后的启动测试。

## 结论

适合目标是“最大还原来源系统”的场景，不适合当前“在 drools-demo 中快速走流程、探索改造方案”的目标。
