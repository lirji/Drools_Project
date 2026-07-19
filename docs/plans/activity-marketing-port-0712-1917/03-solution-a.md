# 03 Solution A - 收敛移植为当前仓库原生活动营销模块

## architecture-designer 视角

### 架构

在 `drools-demo` 中新增一个自包含的 `com.lrj.drools.activity` 模块，用 JPA 实体复刻来源项目活动营销的核心五层语义，但不复制 `mall-common`、MyBatis-Plus、权益系统、钉钉、商品真实表依赖。

核心保留：

- 活动基础层：对应 `ActivityAdminPlatformManage`。
- 规则详情层：对应 `ActivityDynamicRules`。
- 商品绑定层：对应 `ActivityAdminStoreSpuProduct`。
- 活动级资格条件层：对应 `ActivityRuleExpression` 和 `ConditionNode`。
- 多活动合并策略：对应 `ActivityRuleStrategy` 和 `DiscountDbRuleSource` 的 `MAX/MUTEX/STACK/PRIORITY`。
- Drools 执行链路：保留 `ActivityRuleEngine`、`KieBaseManager`、`RuleCompiler` 的思想，优先使用当前项目已有 Drools 8 依赖。

暂不保留：

- QLExpress 运行时，除非后续确认要引入依赖。先将 `ConditionNode` 直接翻译为受控 DRL 条件片段，或存储 JSON 条件树后由 Java/Drools adapter 评估。
- 真实权益系统校验、钉钉通知、真实登录态、真实商品表。

### 模块职责

- `activity.domain`：活动入参/出参、条件树、候选 fact、结果 fact。
- `activity.persistence`：JPA 实体与 Repository。
- `activity.engine`：规则引擎门面、KieBase 缓存、规则来源、规则编译。
- `activity.service`：创建、版本化编辑、上下线、商品绑定、资格过滤、优惠计算。
- `activity.controller`：面向前端的活动管理与模拟查询接口。
- `static/assets`：新增报表式活动创建页面、规则条件编辑、商品绑定明细、运行验证视图。

### 核心流程

1. 前端报表式表单提交活动基础信息、红包规则、商品绑定、资格条件树、合并策略。
2. `ActivityMarketingController#create` 调用 `ActivityMarketingService#create`。
3. 服务端校验基础字段、金额、时间、条件树字段白名单。
4. 新活动生成 `activityId`，编辑活动则旧版本逻辑删除并插入 `version+1`。
5. 保存规则详情、商品绑定、策略、条件树。
6. 发布后刷新规则缓存或依赖版本指纹懒刷新。
7. 前端调用商品优惠查询接口，服务端查绑定 -> 过滤生效活动 -> 组装 `ActivityCandidate` -> 调用规则引擎 -> 返回命中活动、金额、trace。

### 改动范围

中等。新增模块较多，但主要在独立包内；对现有 `Campaign*` 可只做兼容入口或保留不动。

### 扩展性

较好。后续可逐步补：

- QLExpress 后端。
- 商品池规则圈选。
- 买赠奖品。
- 库存扣减。
- 真实商品/用户上下文 adapter。

### 实施成本

中等偏高。预计需要 4 到 6 个开发阶段，但能保证当前仓库内独立跑通。

## risk-reviewer 视角

- 兼容性：不直接引入来源项目依赖，编译风险较低；但字段名需与来源语义对齐。
- 事务：创建活动必须同事务保存基础层、规则层、绑定层、条件树；编辑采用版本化插入，失败需整体回滚。
- 并发：同一 `activityId/version` 并发编辑可能产生双新版本，需要乐观锁或唯一约束。
- 幂等：创建接口应支持客户端 `requestId` 或明确 `activityId` 编辑语义；否则重复提交会生成多个活动。
- 性能：toC 查询需要按 `spuId`、`activityId`、`effective` 建索引；H2 demo 可先简化，但文档需提示 MySQL 索引。
- 安全：条件树必须字段白名单，不能接受任意 DRL 文本作为运营规则。
- 回滚：独立模块可通过配置开关隐藏新前端入口；数据库新增表不影响旧 Step 18。

## test-designer 视角

重点测试：

- 创建活动成功后四层数据一致。
- 编辑生成新版本，旧版本 `isDel=1`。
- 下线活动不参与优惠计算。
- 资格条件命中/不命中。
- 多活动 `MAX/MUTEX/STACK/PRIORITY` 结果。
- 规则编译失败不落库。
- 并发创建/编辑同一活动不产生不一致版本。

## 结论

这是最适合当前仓库定位的主方案：能演示真实活动营销改造形态，又不会被来源项目外部依赖拖垮。
