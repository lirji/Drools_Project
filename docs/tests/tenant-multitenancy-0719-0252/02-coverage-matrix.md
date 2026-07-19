# 覆盖矩阵

标记：`已有`=仓库现有自动化已直接证明；`缺口`=本蓝图新增草案；`TODO`=会暴露疑似 bug，修复前禁用；`IT`=默认套件不运行的门控集成验证；`人工`=当前只有 QA 记录或需真实 IdP。

## TenantContext / resolver / properties

| 类/方法 | 分支或输入 | 状态 | 对应测试 |
|---|---|---:|---|
| `TenantContext.set/get/clear` | 同线程读写清除 | 缺口 | `TenantContextTest#setGetAndClearUseCurrentThread` |
| `callWith` | 无前值，成功后 remove | 缺口 | `TenantContextTest#callWithRemovesValueWhenThereWasNoPreviousTenant` |
| `callWith` | 有前值，嵌套成功后恢复 | 缺口 | `TenantContextTest#nestedCallWithRestoresPreviousTenant` |
| `callWith` | body 抛异常仍恢复 | 缺口 | `TenantContextTest#callWithRestoresAfterException` |
| `runWith` | Runnable 异常仍恢复 | 缺口 | `TenantContextTest#runWithRestoresAfterException` |
| ThreadLocal | 父线程与单线程池隔离、worker 不残留 | 缺口 | `TenantContextTest#workerThreadDoesNotInheritOrRetainTenant` |
| `TenantProperties` | 默认 auth=false、devDefault=false、warmup=true、timeout=2000、默认 template、空 write authority | 缺口 | `MultiTenancyConfigTest#propertiesHaveFailClosedDefaultsExceptExplicitWarmup` |
| Properties setters | map/list/issuer/JWKS/authority 值可绑定 | 缺口（低价值） | 由 config bean 草案间接使用；不逐 getter 重复 |
| `TenantIdentifierResolver.resolve…` | 非 blank context 优先 | 缺口 | `TenantIdentifierResolverTest#contextWinsOverDevDefault` |
| 同上 | blank context + valid dev default | 缺口 | `TenantIdentifierResolverTest#blankContextFallsBackToDevDefault` |
| 同上 | 无 context + default off → `NO_TENANT`，非 null | 缺口 | `TenantIdentifierResolverTest#missingContextFailsClosedToSentinel` |
| 同上 | default on 但 null/blank/非法/保留 | TODO | `TenantIdentifierResolverTest#invalidDevDefaultMustNeverEscapeResolver`，ISSUE-01 |
| `validateExistingCurrentSessions` | false | 缺口 | `TenantIdentifierResolverTest#doesNotValidateExistingSessions` |

## header/auth 租户入口

| 类/方法 | 分支或输入 | 状态 | 对应测试 |
|---|---|---:|---|
| `TenantContextFilter` | 无 header + default off →403，不调用 chain | 已有 | `TenantContextFilterTest#noHeaderDevDefaultDisabled_forbidden` |
| 同上 | 无 header + default on → default，结束清理 | 已有 | `TenantContextFilterTest#noHeaderDevDefaultEnabled_fallsBackToDevDefault` |
| 同上 | 合法 header → context，结束清理 | 已有 | `TenantContextFilterTest#validHeader_usedAndCleared` |
| 同上 | 非法字符 →400 | 已有 | `TenantContextFilterTest#illegalHeader_badRequest` |
| 同上 | 1/64 位通过、65 位拒绝 | 缺口 | `TenantIngressFilterBoundaryTest#sixtyFourCharactersPassAndSixtyFiveFail` |
| 同上 | header=`__no_tenant__` →400、chain 不调用 | QA 已有，自动化缺口 | `TenantIngressFilterBoundaryTest#reservedSentinelIsRejected` |
| 同上 | chain 抛 IOException/ServletException 仍 clear | 缺口 | `TenantIngressFilterBoundaryTest#downstreamExceptionStillClearsContext` |
| 同上 | 早退时已有 stale context | TODO | `TenantIngressFilterBoundaryTest#earlyRejectionMustClearStaleTenant`，ISSUE-02 |
| 同上 | invalid/reserved/null dev default | TODO | `TenantIngressFilterBoundaryTest#invalidDevDefaultMustBeRejected`，ISSUE-01 |
| `AudienceTenantResolver` | pattern、map priority、unknown、0 aud | 已有 | `AudienceTenantResolutionTest` |
| 同上 | 两个不同 tenant → empty；同 tenant 重复 → tenant | 已有 | `AudienceTenantResolutionTest#multiAud*` |
| 同上 | map tenant + pattern tenant 歧义 | 已有 | `mapAndPatternAcrossAudsDifferentTenants_reject` |
| 同上 | collection=null、null/blank 元素 | 缺口 | `AudienceTenantResolverBoundaryTest#nullAndBlankAudiencesAreIgnored` |
| 同上 | known + unknown → known | 缺口 | `AudienceTenantResolverBoundaryTest#oneKnownPlusUnknownAudiencesResolvesKnownTenant` |
| 同上 | 三个 aud，两个同 tenant + 一个异 tenant | 缺口 | `AudienceTenantResolverBoundaryTest#duplicatesDoNotHideASecondTenant` |
| 同上 | sentinel + known → known；只有 sentinel →empty | 部分已有/缺口 | `AudienceTenantResolverBoundaryTest#reservedSentinelNeverCountsAsTenant` |
| 同上 | pattern 特殊字符被 quote；无 placeholder template 被忽略 | 缺口 | `AudienceTenantResolverBoundaryTest#templateLiteralsAreQuotedAndTemplatesWithoutPlaceholderIgnored` |
| 同上 | map value 非法/超长/`__single__` | TODO | `AudienceTenantResolverBoundaryTest#invalidMappedTenantMustBeRejected`，ISSUE-03 |
| 同上 | templates 内 null | TODO | `AudienceTenantResolverBoundaryTest#nullTemplateNeedsDiagnosticValidation`，ISSUE-09 |
| `resolve(Jwt)` | 只从 `jwt.getAudience`，owner 不参与 | 已有 | `ownerIsNotUsedForTenant` |
| `AudienceTenantValidator.validate` | known success；unknown/empty error | 已有 | `validatorPassesKnownTenantAud`、`validatorRejectsUnknownAud` |
| 同上 | 歧义错误 code=`invalid_token` | 缺口 | `AudienceTenantResolverBoundaryTest#validatorUsesInvalidTokenForAmbiguousAudience` |
| `JwtTenantFilter` | aud→context，finally clear | 已有 | `JwtTenantFilterTest#audBecomesTenant_andClearedAfter` |
| 同上 | envelope 相同放行；不同 403 | 已有 | `JwtTenantFilterTest#envelope*` |
| 同上 | 无 authentication →403 | 已有 | `JwtTenantFilterTest#noAuthentication_forbidden` |
| 同上 | 歧义 aud →403 且清 stale | 缺口 | `JwtTenantFilterBoundaryTest#ambiguousAudienceFailsClosedAndClearsStaleTenant` |
| 同上 | 下游异常 →clear | 缺口 | `JwtTenantFilterBoundaryTest#downstreamExceptionStillClearsTenant` |
| 同上 | chain 内 SecurityContext 改变不改变本次 TenantContext | 缺口 | `JwtTenantFilterBoundaryTest#securityContextMutationDownstreamCannotChangeResolvedTenant` |
| 同上 | 单线程 executor 连续异常/无认证请求不串租户 | 缺口 | `JwtTenantFilterBoundaryTest#singleWorkerDoesNotLeakTenantAcrossRequests` |

## 装配、安全链、JWKS

| 类/feature flag | 分支或场景 | 状态 | 对应测试 |
|---|---|---:|---|
| `MultiTenancyConfig` | Hibernate customizer 使用常量 key 注入同一 resolver | 缺口 | `MultiTenancyConfigTest#customizerInstallsExactResolverUnderHibernateConstant` |
| 同上 | header filter URL `/activity-marketing/*`、name、order | 缺口 | `MultiTenancyConfigTest#headerFilterRegistrationHasNarrowScopeAndStableOrder` |
| 同上 | header bean conditional auth=false/missing | 缺口（结构） | `MultiTenancyConfigTest#featureFlagAnnotationsAreMutuallyExclusive` |
| `PermitAllSecurityConfig` | auth=false/missing 选 permit-all config | 缺口（结构） | 同上 |
| `ActivityResourceServerConfig` | auth=true 选 resource config | 缺口（结构） | 同上 |
| Security chain order | activity chain Order 1、open chain Order 2 | 缺口（结构） | `MultiTenancyConfigTest#securityChainsKeepRequiredOrdering` |
| 活动链 | `/activity-marketing/**` 无 token 401 | 已有 | `ActivityAuthIntegrationTest#noToken_unauthorized` |
| 活动链 | unknown aud 401；信封 mismatch 403；tenant HTTP 隔离 | 已有 | `ActivityAuthIntegrationTest` |
| 链边界 | `/actuator/health`、静态页、`POST /hello` 无 token 放行 | 人工 QA，自动化缺口 IT | `ActivitySecurityChainsIT#nonActivityEndpointsRemainOpenWhileActivityEndpointRequiresJwt` |
| console authority 空 | 写端点只需 authenticated | 未单独自动化；当前已有 create with token 间接覆盖 | 基线由 `ActivityAuthIntegrationTest#tenantIsolationOverHttp` |
| console authority 非空 | 无权限 token：create/status 403；读/决策仍放行 | 缺口 IT | `ActivitySecurityChainsIT#consoleWriteAuthorityProtectsWritesButNotReads` |
| scope converter | `scope=activity.write` → `SCOPE_activity.write` | 缺口 IT | 同上（能成功 create 是强断言） |
| groups converter | collection path 取末段；非 collection 忽略 | 缺口，次优先 | 后续 `ActivitySecurityChainsIT` 参数化扩展，待验证 Casdoor claim 形态 |
| decoder | signature + iss + exp + aud validator | 已有部分 | `ActivityAuthIntegrationTest.TestDecoderConfig` 覆盖同款 validators；生产 JWKS builder 未自动化 |
| decoder timeout | connect/read 使用 `jwksFetchTimeoutMs` | 缺口，时间断言易 flaky | 配置审查 + 真故障演练；不写基于 wall-clock 的默认单测 |
| `JwksWarmupRunner` | warmup=false 不访问；true 访问一次 keys | 缺口 | `JwksWarmupRunnerTest#warmupFlagControlsFetch` |
| 同上 | 网络/JSON 异常的启动语义 | 待验证/TODO | ISSUE-08；产品先决定 hard-fail/readiness/warn-only |

## ORM 隔离与幂等/事务

| 行为 | 状态 | 对应测试 |
|---|---:|---|
| 10 个 entity 均带 `@TenantId` | 已有 | `TenantArchGuardTest#everyEntityHasTenantId` |
| repository 无 native query | 已有 | `TenantArchGuardTest#noNativeQueryInRepositories` |
| insert 自动 tenant、列表/详情/决策隔离 | 已有 | `TenantIsolationTest` |
| JPQL bulk update 自动 tenant 谓词 | 已有 | `TenantIsolationTest#bulkUpdateIsTenantScoped` |
| 跨租户编辑/状态更新 fail closed | 已有 | `TenantIsolationTest` |
| `uk_am_tenant_request` 注解列顺序和名称 | 缺口 | `ActivityManageEntityConstraintTest#idempotencyConstraintIsTenantScoped` |
| 顺序重复 requestId 返回首结果、不新增 | 已有 | `ActivityMarketingEdgeTest#idempotentSameRequestId` |
| 两事务均通过预读时，DB 唯一约束仅允许一个 winner | 缺口 IT | `ActivityIdempotencyConcurrencyIT#databaseConstraintSelectsSingleWinnerAndRetryBecomesHit` |
| 相同 requestId 跨 tenant 可各成功 | 缺口 IT | 同上末段断言 |
| `DataIntegrityViolationException` 转服务冲突 | 缺口单测 | `ActivityMarketingIdempotencyTest#uniqueConstraintViolationBecomesRetryableConflict` |
| controller 把服务冲突转 HTTP 409 且保留消息 | 缺口单测 | `ActivityMarketingControllerIdempotencyTest#concurrentDuplicateBecomes409` |
| blank requestId 不启用幂等 | TODO | `ActivityMarketingIdempotencyTest#blankRequestIdMustBeNormalizedToNull`，ISSUE-05 |
| 非幂等完整性错误不得伪装为重复 | TODO | `ActivityMarketingIdempotencyTest#unrelatedIntegrityViolationMustNotBeReportedAsDuplicate`，ISSUE-06 |
| 编辑后重放原 requestId | TODO/需产品确认 | ISSUE-07，拟补 `ActivityIdempotencyLifecycleIT` |
| 并发编辑 softDelete 影响 0 → conflict | 代码分支未做确定性并发测试 | 后续独立 barrier IT；现有 `versionEditIntegrity` 只覆盖顺序编辑 |
| 事务中后续 rule/condition 保存失败时 manage 回滚 | 现有非法条件证明写前失败；写后失败缺口 | 后续事务故障注入 IT，不用 mock 假装事务 |

## schema 与缓存

| 类/方法 | 分支或场景 | 状态 | 对应测试 |
|---|---|---:|---|
| `RuleSchemaRegistry.resolve` | exact `(tenant,biz)` 优先 tenant fallback | 缺口 | `RuleSchemaRegistryTenantTest#bizOverrideWinsThenTenantFallbackThenDefault` |
| 同上 | tenant A/B 隔离 | 缺口 | `RuleSchemaRegistryTenantTest#registrationsAreTenantIsolated` |
| `register` | 返回不可变 snapshot、重复注册替换 | 缺口 | `RuleSchemaRegistryTenantTest#registeredSchemaIsImmutableAndReplaceable` |
| `resolveFields/defaultFields` | 与 resolve 内容一致；默认 6 字段 | 缺口 | `RuleSchemaRegistryTenantTest#resolveFieldsReflectsSelectedSchema` |
| field-dict | tenant-level register 后只显示该 tenant 字段 | 缺口 | `ActivityMarketingControllerFieldDictTest#fieldDictionaryUsesCurrentTenantOverride` |
| create | tenant+biz custom 字段可用，另一 tenant 同字段 fail closed 且不写 manage | 缺口 | `ActivityMarketingTenantSchemaTest#createUsesTenantAndBizLineSchemaBeforeAnyWrite` |
| field-dict/preview vs bizLine schema | 三路径一致 | TODO | `ActivityMarketingControllerFieldDictTest#bizLineOverrideMustBeVisibleToFieldDictionary`，ISSUE-04 |
| `compileOrGet` | 同 tenant+同 DRL 命中同 KieBase | 缺口 | `ActivityRuleRuntimeTenantCacheTest#sameTenantAndDrlReuseCompiledKieBase` |
| 同上 | 不同 tenant+同 DRL 不共享，cacheSize=2 | 缺口 | `ActivityRuleRuntimeTenantCacheTest#sameDrlIsPartitionedByTenant` |
| 同上 | 无 context 单独 key | 缺口 | `ActivityRuleRuntimeTenantCacheTest#noContextHasItsOwnCachePartition` |
| `evictAll` | 全量失效 | 缺口 | `ActivityRuleRuntimeTenantCacheTest#evictAllForcesRecompile` |
| 四组 eval | explain true/false、异常回退 null | 已有业务 flow 部分覆盖，非本轮核心 | 不为 cache key 测试重复整套规则语义 |

## 边缘与注入清单摘要

- null/blank：auds、header、dev default、requestId、tenant context、template item、schema fields/list。
- 长度：tenant 1/64/65；map tenant 超长；数据库字符串长度（尤其 activityName/requestId）。
- 非法/注入：tenant 空格、分号、换行、Unicode、`__no_tenant__`、`__single__`；template 前后缀按 `Pattern.quote`；DRL 字段仍由 translator 白名单负责。
- 并发：单线程池复用导致 ThreadLocal 串味；幂等 check-then-insert；版本 soft-delete 竞态；Caffeine 同 key 原子编译。
- 资源：executor、应用 context、本地 JWKS server 都必须 finally/AfterAll 关闭；不使用 sleep 或公共 ForkJoinPool。
