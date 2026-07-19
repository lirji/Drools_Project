# Track B 多租户测试被测面

## 仓库与模块

- Maven 坐标：`com.lrj:drools-demo:0.0.1-SNAPSHOT`，单模块工程，Java 21、Spring Boot 3.3.5、Hibernate 6、Drools 8.44.2.Final。
- 生产源码：`src/main/java`；现有测试：`src/test/java`。
- 本蓝图只写入 `docs/tests/tenant-multitenancy-0719-0252/`，未修改生产代码或现有测试。
- 仓库根目录不存在 `CODEX_PROGRESS.md`；工作树原有修改很多，均视为用户现有工作并保持不动。

## 受影响生产类与公共入口

### `com.lrj.drools.activity.tenant`

| 类 | 本次审查的公共/框架入口 | 关键状态或分支 |
|---|---|---|
| `TenantContext` | `set(String)`、`get()`、`clear()`、`callWith(String,Supplier)`、`runWith(String,Runnable)` | ThreadLocal 线程隔离、嵌套恢复、异常恢复 |
| `TenantProperties` / `Auth` | 全部 getter/setter | 安全默认值、auth/warmup/timeout/write-authority/map/template on-off |
| `TenantIdentifierResolver` | 构造器、`resolveCurrentTenantIdentifier()`、`validateExistingCurrentSessions()` | context → dev default → `__no_tenant__`，不得返回 null |
| `TenantContextFilter` | 构造器、Servlet `doFilter` 入口（覆盖 `doFilterInternal`） | header 缺失/空白、dev default、格式边界、保留值、下游异常、清理 |
| `AudienceTenantResolver` | 构造器、`resolve(Collection<String>)`、`resolve(Jwt)` | map 优先、模板回落、0/1/多租户、多 aud、哨兵剔除 |
| `AudienceTenantValidator` | 构造器、`validate(Jwt)` | 可解析成功；未知/缺失/歧义 aud 返回 `invalid_token` |
| `JwtTenantFilter` | 构造器、Servlet `doFilter` 入口 | JWT authentication 类型、aud、信封一致/不一致、异常与线程清理 |
| `ActivityResourceServerConfig` | 两个 `SecurityFilterChain` bean、`audienceTenantResolver`、`activityJwtDecoder` | Order 1 活动链、Order 2 开放链、scope/groups authority、写权限、JWKS 超时与 validators |
| `PermitAllSecurityConfig` | `permitAllFilterChain` | auth 关闭/缺省时全放行 |
| `MultiTenancyConfig` | `tenantIdentifierResolverCustomizer`、`tenantContextFilter` | Hibernate resolver 注入、auth=false 时 header filter 注册及 URL/order |
| `JwksWarmupRunner` | 构造器、`run(ApplicationArguments)` | warmupEnabled false/true、成功/空 keys/网络或 JSON 异常 |

### persistence 隔离面

实际读取确认以下 10 个实体均有 `@TenantId private String tenantId`：

- `ActivityManageEntity`
- `ActivityRuleEntity`
- `ActivityConditionEntity`
- `ActivitySpuBindingEntity`
- `ActivityGiftEntity`
- `ActivityStrategyEntity`
- `PoolRefEntity`
- `ProductPoolEntity`
- `ProductPoolRuleEntity`
- `DemoProductEntity`

`ActivityManageEntity` 的 `@Table` 明确声明 `uk_am_tenant_request(tenant_id, request_id)`；热点索引的声明由 `TenantArchGuardTest` 与实体注解共同审查。Hibernate `@TenantId` 的真实 SQL 隔离已有 `TenantIsolationTest` 证明，本计划不重复六条已绿主链路。

### 服务与控制器联动

| 类 | 方法 | 多租户相关行为 |
|---|---|---|
| `ActivityMarketingService` | `create`、`updateByVersion`、`changeStatus`、`list`、`getDetail`、`previewEligibility` | 幂等预读与唯一约束冲突；按当前租户和 bizLine 解析 schema；事务/版本并发 |
| `RuleSchemaRegistry` | `resolve`、`resolveFields`、`register`、`defaultFields` | `(tenant,bizLine)` → `(tenant,*)` → 默认 schema |
| `ActivityRuleRuntimeService` | 四组 eval 重载、`compileOrGet`、`evictAll`、`cacheSize` | 缓存 key 为当前 tenant + NUL + DRL；执行异常回退 null |
| `ActivityMarketingController` | `create`、`status`、`fieldDict`、`preview` | `IllegalStateException` → 409；field-dict 读取当前 TenantContext |

## 已有相关测试及已证明行为

现有测试都位于根模块 `src/test/java/com/lrj/drools/activity/`：

- `TenantIsolationTest`：6 个隔离主题（写标签、列表/详情、优惠路径、跨租户编辑、bulk update、状态变更）。该类实际有 6 个 `@Test`。
- `AudienceTenantResolutionTest`：10 个 resolver/validator 场景，包括 map、pattern、未知、同/异租户多 aud、哨兵。
- `JwtTenantFilterTest`：4 个 filter 场景，包括 aud 落上下文、匹配/不匹配信封、无认证。
- `TenantContextFilterTest`：4 个 header/dev-default 场景。
- `ActivityAuthIntegrationTest`：4 个核心 HTTP 场景（类内辅助配置也含 `@TestConfiguration`，不是额外用例）：无 token 401、未知 aud 401、HTTP 隔离、信封 403。
- `TenantArchGuardTest`：2 个架构守卫：实体 `@TenantId` 与 repository 禁止 native query。
- `ActivityMarketingEdgeTest`：已有顺序幂等重复，但没有并发唯一约束竞态。
- `docs/qa/activity-multitenant-0719/QA_REPORT.md`：黑盒记录了保留 header 400、health/`/hello`/静态页在 auth 档放行；这些尚未全部成为默认自动化断言。

说明：用户给出的“55 绿”是当前基线事实；本次未运行 Maven，因为硬性约束只允许向蓝图目录写文件，而 Maven 会写 `target/`。现有测试中存在 `@SpringBootTest`、MockMvc 和 JUnit 原生断言，这是历史现状；新增草案不照搬，只有无法脱离真实安全链/JPA 唯一约束的隔离 `*IT` 明确门控。

## 运行命令

本工程是 reactor 根自身的单模块，模块选择写作 `-pl .`：

```bash
# 全量默认测试（*IT 不被 Surefire 默认模式选中）
./mvnw -pl . -am test

# 纯单测聚焦运行
./mvnw -pl . -Dtest=TenantContextTest test
./mvnw -pl . -Dtest=TenantIngressFilterBoundaryTest test
./mvnw -pl . -Dtest=AudienceTenantResolverBoundaryTest test
./mvnw -pl . -Dtest=RuleSchemaRegistryTenantTest test
./mvnw -pl . -Dtest=ActivityMarketingTenantSchemaTest test
./mvnw -pl . -Dtest=ActivityRuleRuntimeTenantCacheTest test

# 显式运行门控 IT
RUN_ACTIVITY_SECURITY_IT=true ./mvnw -pl . -Dtest=ActivitySecurityChainsIT test
RUN_ACTIVITY_IDEMPOTENCY_IT=true ./mvnw -pl . -Dtest=ActivityIdempotencyConcurrencyIT test
```

这些草案不走内部 JWT，`INTERNAL_JWT_SECRET` 不参与；若未来引入对应路径，运行前再设置不少于 32 字节的值。

## 约束冲突说明

测试铁律示例要求 `TenantContext.set(new TenantContext.Tenant(...))`，但仓库实际 API 只有 `TenantContext.set(String)`，不存在 `TenantContext.Tenant`、userId 或 scopes 字段。为满足“不得虚构签名”与“草案可编译”，本蓝图统一使用实际的 `TenantContext.set("tenant")`，并在每个涉及上下文的测试中保留 `@BeforeEach` 初始化和 `@AfterEach TenantContext.clear()`。
