# Track B 多租户最终测试蓝图

## 1. 被测面与目标

本计划覆盖根模块 `drools-demo` 的租户入口、JWT audience 解析、安全链边界、Hibernate 判别式隔离、服务幂等、per-tenant schema 与 Drools 缓存分片。精确类、方法、现有测试位置与命令见 [01-scope.md](./01-scope.md)。

目标不是重复已有 55 绿主链路，而是锁定这些高风险交互：

1. ThreadLocal 在嵌套、异常、线程池复用和所有 filter 出口都不串味。
2. header、dev default、aud map/pattern 对租户 ID 使用一致的 fail-closed 语义，保留哨兵不可达。
3. 多 aud 只有“不同可解析 tenant 集合恰为 1”才可信；validator 与 filter 使用同一 resolver 结论。
4. auth 开/关装配互斥；活动路径与开放路径不互相污染；console write authority 只拦运营写。
5. 幂等竞态真正由 `(tenant_id,request_id)` 唯一约束选出单一 winner，且跨租户不冲突。
6. schema override 的 tenant/bizLine 优先级对 field-dict、preview、create 一致；缓存不跨 tenant 复用 KieBase。

## 2. 测试策略、分层与验收标准

### 分层

- L0 纯单元：直接 `new`，协作者全部 `Mockito.mock(...)`；覆盖上下文、resolver、filter、validator、properties/config 元数据、registry、controller 和 service 分支。不使用 Mockito 注解或扩展。
- L1 真实库/规则组件：真实 `RuleConditionTranslator`、`ActivityDrlBuilder`、`ActivityRuleRuntimeService`；验证 schema 影响和 KieBase cache 行为，不起 Spring context。
- L2 门控 IT：仅两类无法用 mock 诚实证明的事实——Spring Security 实际 filter chain，以及 Hibernate/H2 唯一约束并发。类名 `*IT`，有 `@Tag` 和环境变量门控，默认 `mvn test` 不执行。
- 架构回归：保留现有 `TenantArchGuardTest` 和 `TenantIsolationTest`，新增唯一约束注解精确断言。

### 可验证验收标准

- 任意 `callWith/runWith/filter` body 抛异常后，调用线程 `TenantContext` 恢复到进入前值或 null。
- 同一个单线程 executor 先处理 acme 的异常请求、再处理无认证请求，第二次不得观察到 acme。
- 64 位 tenant 通过、65 位和 `__no_tenant__` 拒绝；aud 解析到两个不同 tenant 必须 empty/`invalid_token`。
- map/pattern 同 tenant 可通过；未知 aud 不会制造第二 tenant；map/pattern 不同 tenant 必须拒绝。
- `auth.enabled` 三个 conditional 配置互斥，安全链顺序固定为活动 1、开放 2。
- auth IT 中：活动无 token=401；health、静态页、`/hello` 无 token=200；无 write scope 的活动读=200、create/status=403；有 `activity.write` scope 的 create=200。
- schema 测试必须同时断言“acme+travel 能创建”和“beta 同字段在任何 repository write 前失败”，不能只断返回非空。
- cache 测试必须断言同 tenant 对象复用、跨 tenant 对象不复用及 size 变化，不能只断“没有异常”。
- 幂等 IT 必须用 barrier 让两个事务都先通过预读；结果必须恰好一个成功、一个明确冲突；随后重试命中首结果，beta 同 key 可成功。
- 所有已发现疑点都在 [03-suspected-issues.md](./03-suspected-issues.md)；修复前对应测试 `@Disabled(TODO)`，不得断言当前错误行为。

## 3. 覆盖矩阵与本轮缺口

完整逐方法/分支矩阵见 [02-coverage-matrix.md](./02-coverage-matrix.md)。本轮 P0/P1 必补项是：

- `TenantContext` 全 API 与线程池恢复。
- resolver 的 context/default/sentinel 顺序与 filter 格式边界、保留值、异常清理。
- audience null/blank/known+unknown/三 aud 歧义、validator error code。
- JWT filter 的下游异常、歧义、SecurityContext TOCTOU、worker 复用。
- feature flag 条件注解、Hibernate customizer、filter registration、安全链 order。
- health/其它 Step/活动链边界与 console write authority（门控 IT）。
- schema exact/fallback/isolation、field-dict tenant override、create 的 tenant+bizLine 生效。
- cache key 包含 tenant 的可观察行为。
- 唯一约束注解、服务冲突分支、controller 409、H2 并发单 winner。

## 4. 疑似问题摘要

详细证据与复现见 [03-suspected-issues.md](./03-suspected-issues.md)：

| ID | 预期 vs 现状 | 最小复现 | 建议 |
|---|---|---|---|
| ISSUE-01 | 所有租户来源都校验且 resolver 非 null；dev default 当前绕过校验 | default=true，值 null/非法/哨兵，无 header | 配置 validation + 共用 validator |
| ISSUE-02 | filter 所有出口 clear；header filter 早退不 clear stale | 先 set stale，再发缺失/非法 header | 入口 clear 或全方法 finally |
| ISSUE-03 | map value 合法且内部占位不可达；当前只排除 `__no_tenant__` | map client→`bad tenant`/`__single__` | 构造时验证复制 + 保留集合 |
| ISSUE-04 | field-dict/preview/create 同 schema 维度；前两者固定 bizLine null | register acme/travel custom field | API 携带 bizLine，共用解析 |
| ISSUE-05 | blank requestId 等价 null；当前跳过查询却写入唯一列 | 同 tenant 两个 requestId=`" "` 创建 | 入口 normalize |
| ISSUE-06 | 仅目标唯一冲突报幂等 409；当前任何 integrity error 都误报 | 超长 activityName + null requestId | 按 constraint 分类并补长度校验 |
| ISSUE-07 | 原幂等键生命周期语义稳定；编辑后重放变 409 | create(K)→edit→replay create(K) | 独立幂等记录或明确契约 |
| ISSUE-08 | warmup 失败策略明确；当前“fail-fast”只 WARN | JWKS 指向未监听端口 | 决定 hard/readiness/warn-only |
| ISSUE-09 | null template 有诊断；当前裸 NPE | list 中含 null | 配置 validation/过滤 |

## 5. 测试代码草案

以下路径是交付给落地 Agent 的目标路径；本次没有把代码写进 `src/`。

### 5.1 `TenantContextTest`

放置路径：`src/test/java/com/lrj/drools/activity/tenant/TenantContextTest.java`

```java
package com.lrj.drools.activity.tenant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TenantContextTest {

    @BeforeEach
    void setUp() {
        TenantContext.set("baseline");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void setGetAndClearUseCurrentThread() {
        TenantContext.set("acme");
        assertThat(TenantContext.get()).isEqualTo("acme");
        TenantContext.clear();
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    void callWithRemovesValueWhenThereWasNoPreviousTenant() {
        TenantContext.clear();
        String result = TenantContext.callWith("acme", TenantContext::get);
        assertThat(result).isEqualTo("acme");
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    void nestedCallWithRestoresPreviousTenant() {
        String result = TenantContext.callWith("acme", () ->
                TenantContext.callWith("beta", () -> TenantContext.get() + ":done"));
        assertThat(result).isEqualTo("beta:done");
        assertThat(TenantContext.get()).isEqualTo("baseline");
    }

    @Test
    void callWithRestoresAfterException() {
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                TenantContext.callWith("acme", () -> {
                    throw new IllegalStateException("boom");
                }));
        assertThat(ex).hasMessage("boom");
        assertThat(TenantContext.get()).isEqualTo("baseline");
    }

    @Test
    void runWithRestoresAfterException() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                TenantContext.runWith("acme", () -> {
                    assertThat(TenantContext.get()).isEqualTo("acme");
                    throw new IllegalArgumentException("stop");
                }));
        assertThat(ex).hasMessage("stop");
        assertThat(TenantContext.get()).isEqualTo("baseline");
    }

    @Test
    void workerThreadDoesNotInheritOrRetainTenant() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<String> first = worker.submit(() -> {
                assertThat(TenantContext.get()).isNull();
                return TenantContext.callWith("acme", TenantContext::get);
            });
            Future<String> second = worker.submit(TenantContext::get);
            assertThat(first.get()).isEqualTo("acme");
            assertThat(second.get()).isNull();
            assertThat(TenantContext.get()).isEqualTo("baseline");
        } finally {
            worker.shutdownNow();
        }
    }
}
```

关键断言：不仅检查 body 里的值，还检查调用后的原值/空值；单线程 executor 强制复用同一 worker，真正验证 ThreadLocal 不残留。

### 5.2 `TenantIdentifierResolverTest`

放置路径：`src/test/java/com/lrj/drools/activity/tenant/TenantIdentifierResolverTest.java`

```java
package com.lrj.drools.activity.tenant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TenantIdentifierResolverTest {

    private TenantProperties props;

    @BeforeEach
    void setUp() {
        TenantContext.set("fixture");
        props = new TenantProperties();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void contextWinsOverDevDefault() {
        props.setDevDefaultEnabled(true);
        props.setDevDefault("dev");
        TenantContext.set("acme");
        assertThat(new TenantIdentifierResolver(props).resolveCurrentTenantIdentifier())
                .isEqualTo("acme");
    }

    @Test
    void blankContextFallsBackToDevDefault() {
        props.setDevDefaultEnabled(true);
        props.setDevDefault("dev");
        TenantContext.set("   ");
        assertThat(new TenantIdentifierResolver(props).resolveCurrentTenantIdentifier())
                .isEqualTo("dev");
    }

    @Test
    void missingContextFailsClosedToSentinel() {
        TenantContext.clear();
        props.setDevDefaultEnabled(false);
        String resolved = new TenantIdentifierResolver(props).resolveCurrentTenantIdentifier();
        assertThat(resolved).isNotNull().isEqualTo(TenantIdentifierResolver.NO_TENANT);
    }

    @Test
    void doesNotValidateExistingSessions() {
        assertThat(new TenantIdentifierResolver(props).validateExistingCurrentSessions()).isFalse();
    }

    @Disabled("TODO(issue-01): 修复 dev default validation 后启用，禁止锁定当前 null 行为")
    @Test
    void invalidDevDefaultMustNeverEscapeResolver() {
        TenantContext.clear();
        props.setDevDefaultEnabled(true);
        props.setDevDefault(null);
        assertThat(new TenantIdentifierResolver(props).resolveCurrentTenantIdentifier())
                .isEqualTo(TenantIdentifierResolver.NO_TENANT);
    }
}
```

关键断言：`isNotNull().isEqualTo(NO_TENANT)`直接锁定 ORM 不能获得 root/null tenant；TODO 使用预期安全行为而非当前错误结果。

### 5.3 `TenantIngressFilterBoundaryTest`

放置路径：`src/test/java/com/lrj/drools/activity/tenant/TenantIngressFilterBoundaryTest.java`

```java
package com.lrj.drools.activity.tenant;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TenantIngressFilterBoundaryTest {

    @BeforeEach
    void setUp() {
        TenantContext.set("fixture");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void sixtyFourCharactersPassAndSixtyFiveFail() throws Exception {
        TenantContextFilter filter = new TenantContextFilter(properties(false, "dev"));
        String max = "a".repeat(64);
        AtomicReference<String> seen = new AtomicReference<>();
        MockHttpServletRequest accepted = request(max);
        MockHttpServletResponse acceptedResponse = new MockHttpServletResponse();
        filter.doFilter(accepted, acceptedResponse, (r, s) -> seen.set(TenantContext.get()));

        AtomicBoolean rejectedChain = new AtomicBoolean();
        MockHttpServletResponse rejectedResponse = new MockHttpServletResponse();
        filter.doFilter(request("b".repeat(65)), rejectedResponse,
                (r, s) -> rejectedChain.set(true));

        assertThat(seen.get()).isEqualTo(max);
        assertThat(acceptedResponse.getStatus()).isEqualTo(200);
        assertThat(rejectedResponse.getStatus()).isEqualTo(400);
        assertThat(rejectedChain).isFalse();
    }

    @Test
    void reservedSentinelIsRejected() throws Exception {
        TenantContextFilter filter = new TenantContextFilter(properties(false, "dev"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chained = new AtomicBoolean();
        filter.doFilter(request(TenantIdentifierResolver.NO_TENANT), response,
                (r, s) -> chained.set(true));
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getErrorMessage()).contains("保留值");
        assertThat(chained).isFalse();
    }

    @Test
    void downstreamExceptionStillClearsContext() {
        TenantContextFilter filter = new TenantContextFilter(properties(false, "dev"));
        FilterChain failing = (r, s) -> {
            assertThat(TenantContext.get()).isEqualTo("acme");
            throw new IOException("downstream");
        };
        IOException ex = assertThrows(IOException.class, () ->
                filter.doFilter(request("acme"), new MockHttpServletResponse(), failing));
        assertThat(ex).hasMessage("downstream");
        assertThat(TenantContext.get()).isNull();
    }

    @Disabled("TODO(issue-02): 早退分支必须防御式清理 stale ThreadLocal")
    @Test
    void earlyRejectionMustClearStaleTenant() throws Exception {
        TenantContext.set("stale");
        TenantContextFilter filter = new TenantContextFilter(properties(false, "dev"));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/activity-marketing/list");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (r, s) -> { });
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(TenantContext.get()).isNull();
    }

    @Disabled("TODO(issue-01): dev default 必须与 header 共用格式/保留值校验")
    @Test
    void invalidDevDefaultMustBeRejected() throws Exception {
        TenantContextFilter filter = new TenantContextFilter(
                properties(true, TenantIdentifierResolver.NO_TENANT));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/activity-marketing/list");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (r, s) -> { });
        assertThat(response.getStatus()).isEqualTo(400);
    }

    private static MockHttpServletRequest request(String tenant) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/activity-marketing/list");
        request.addHeader(TenantContextFilter.TENANT_HEADER, tenant);
        return request;
    }

    private static TenantProperties properties(boolean enabled, String devDefault) {
        TenantProperties props = new TenantProperties();
        props.setDevDefaultEnabled(enabled);
        props.setDevDefault(devDefault);
        return props;
    }
}
```

关键断言：边界用同时验证 status、chain 是否调用、chain 内实际 tenant；异常测试检查 finally 后状态。两个 bug 用例禁用，避免当前实现“通过即正确”的确认偏差。

### 5.4 `AudienceTenantResolverBoundaryTest`

放置路径：`src/test/java/com/lrj/drools/activity/tenant/AudienceTenantResolverBoundaryTest.java`

```java
package com.lrj.drools.activity.tenant;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AudienceTenantResolverBoundaryTest {

    private final AudienceTenantResolver resolver =
            new AudienceTenantResolver(Map.of(), List.of("activity-{tenant}-cid"));

    @Test
    void nullAndBlankAudiencesAreIgnored() {
        assertThat(resolver.resolve((List<String>) null)).isEmpty();
        assertThat(resolver.resolve(Arrays.asList(null, "", "  "))).isEmpty();
    }

    @Test
    void oneKnownPlusUnknownAudiencesResolvesKnownTenant() {
        assertThat(resolver.resolve(List.of("unknown", "activity-acme-cid", "other")))
                .contains("acme");
    }

    @Test
    void duplicatesDoNotHideASecondTenant() {
        assertThat(resolver.resolve(List.of(
                "activity-acme-cid", "activity-acme-cid", "activity-beta-cid")))
                .isEmpty();
    }

    @Test
    void mapAndPatternForSameTenantAreNotAmbiguous() {
        AudienceTenantResolver mixed = new AudienceTenantResolver(
                Map.of("legacy", "acme"), List.of("activity-{tenant}-cid"));
        assertThat(mixed.resolve(List.of("legacy", "activity-acme-cid"))).contains("acme");
    }

    @Test
    void reservedSentinelNeverCountsAsTenant() {
        assertThat(resolver.resolve(List.of("activity-__no_tenant__-cid"))).isEmpty();
        assertThat(resolver.resolve(List.of(
                "activity-__no_tenant__-cid", "activity-acme-cid"))).contains("acme");
    }

    @Test
    void templateLiteralsAreQuotedAndTemplatesWithoutPlaceholderIgnored() {
        AudienceTenantResolver quoted = new AudienceTenantResolver(
                Map.of(), List.of("client.+{tenant}[x]", "constant-client"));
        assertThat(quoted.resolve(List.of("client.+acme[x]"))).contains("acme");
        assertThat(quoted.resolve(List.of("clientZZacmex"))).isEmpty();
        assertThat(quoted.resolve(List.of("constant-client"))).isEmpty();
    }

    @Test
    void validatorUsesInvalidTokenForAmbiguousAudience() {
        AudienceTenantValidator validator = new AudienceTenantValidator(resolver);
        OAuth2TokenValidatorResult result = validator.validate(jwt(List.of(
                "activity-acme-cid", "activity-beta-cid")));
        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors()).singleElement()
                .extracting(error -> error.getErrorCode())
                .isEqualTo("invalid_token");
    }

    @Disabled("TODO(issue-03): map value 必须使用统一 tenant grammar 并拒绝内部占位")
    @Test
    void invalidMappedTenantMustBeRejected() {
        AudienceTenantResolver mapped = new AudienceTenantResolver(
                Map.of("client-x", "bad tenant"), List.of());
        assertThat(mapped.resolve(List.of("client-x"))).isEmpty();
    }

    @Disabled("TODO(issue-09): 用配置诊断替代 template.indexOf 的裸 NPE")
    @Test
    void nullTemplateNeedsDiagnosticValidation() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                new AudienceTenantResolver(Map.of(), Arrays.asList(null, "activity-{tenant}-cid")));
        assertThat(ex).hasMessageContaining("audienceTemplates");
    }

    private static Jwt jwt(List<String> audiences) {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .audience(audiences)
                .claim("owner", "admin")
                .build();
    }
}
```

关键断言：多 aud 测试断言的是“解析后不同 tenant 集合”而非 audience 数量；validator 还检查 OAuth2 error code，确保最终会被 resource server 当作无效 token。

### 5.5 `JwtTenantFilterBoundaryTest`

放置路径：`src/test/java/com/lrj/drools/activity/tenant/JwtTenantFilterBoundaryTest.java`

```java
package com.lrj.drools.activity.tenant;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtTenantFilterBoundaryTest {

    private final JwtTenantFilter filter = new JwtTenantFilter(
            new AudienceTenantResolver(Map.of(), List.of("activity-{tenant}-cid")));

    @BeforeEach
    void setUp() {
        TenantContext.set("fixture");
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void ambiguousAudienceFailsClosedAndClearsStaleTenant() throws Exception {
        authenticate(List.of("activity-acme-cid", "activity-beta-cid"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request(), response, (r, s) -> {
            throw new AssertionError("ambiguous token must not reach chain");
        });
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    void downstreamExceptionStillClearsTenant() {
        authenticate(List.of("activity-acme-cid"));
        FilterChain failing = (r, s) -> {
            assertThat(TenantContext.get()).isEqualTo("acme");
            throw new IOException("downstream");
        };
        IOException ex = assertThrows(IOException.class, () ->
                filter.doFilter(request(), new MockHttpServletResponse(), failing));
        assertThat(ex).hasMessage("downstream");
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    void securityContextMutationDownstreamCannotChangeResolvedTenant() throws Exception {
        authenticate(List.of("activity-acme-cid"));
        AtomicReference<String> before = new AtomicReference<>();
        AtomicReference<String> after = new AtomicReference<>();
        filter.doFilter(request(), new MockHttpServletResponse(), (r, s) -> {
            before.set(TenantContext.get());
            authenticate(List.of("activity-beta-cid"));
            after.set(TenantContext.get());
        });
        assertThat(before.get()).isEqualTo("acme");
        assertThat(after.get()).isEqualTo("acme");
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    void singleWorkerDoesNotLeakTenantAcrossRequests() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<String> first = worker.submit(() -> {
                authenticate(List.of("activity-acme-cid"));
                try {
                    assertThrows(IOException.class, () -> filter.doFilter(
                            request(), new MockHttpServletResponse(), (r, s) -> {
                                throw new IOException("first failed");
                            }));
                    return TenantContext.get();
                } finally {
                    SecurityContextHolder.clearContext();
                }
            });
            Future<WorkerResult> second = worker.submit(() -> {
                MockHttpServletResponse response = new MockHttpServletResponse();
                filter.doFilter(request(), response, (r, s) -> {
                    throw new AssertionError("unauthenticated request must not reach chain");
                });
                return new WorkerResult(response.getStatus(), TenantContext.get());
            });
            assertThat(first.get()).isNull();
            assertThat(second.get()).isEqualTo(new WorkerResult(403, null));
        } finally {
            worker.shutdownNow();
        }
    }

    private static void authenticate(List<String> audiences) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .audience(audiences)
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    private static MockHttpServletRequest request() {
        return new MockHttpServletRequest("GET", "/activity-marketing/list");
    }

    private record WorkerResult(int status, String tenant) { }
}
```

关键断言：TOCTOU 用例在 chain 内换掉 Authentication 后再次读取 tenant，确认一次请求不会中途换租户；worker 用例强制同一线程先异常后无认证，覆盖两个清理路径。

### 5.6 `MultiTenancyConfigTest`

放置路径：`src/test/java/com/lrj/drools/activity/tenant/MultiTenancyConfigTest.java`

```java
package com.lrj.drools.activity.tenant;

import jakarta.servlet.FilterRegistration;
import jakarta.servlet.ServletContext;
import org.hibernate.cfg.MultiTenancySettings;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MultiTenancyConfigTest {

    @Test
    void customizerInstallsExactResolverUnderHibernateConstant() {
        TenantIdentifierResolver resolver = new TenantIdentifierResolver(new TenantProperties());
        HibernatePropertiesCustomizer customizer =
                new MultiTenancyConfig().tenantIdentifierResolverCustomizer(resolver);
        Map<String, Object> properties = new HashMap<>();
        customizer.customize(properties);
        assertThat(properties)
                .containsEntry(MultiTenancySettings.MULTI_TENANT_IDENTIFIER_RESOLVER, resolver)
                .hasSize(1);
    }

    @Test
    void headerFilterRegistrationHasNarrowScopeAndStableOrder() throws Exception {
        TenantProperties props = new TenantProperties();
        FilterRegistrationBean<TenantContextFilter> registration =
                new MultiTenancyConfig().tenantContextFilter(props);
        assertThat(registration.getFilter()).isInstanceOf(TenantContextFilter.class);
        assertThat(registration.getUrlPatterns()).containsExactly("/activity-marketing/*");
        assertThat(registration.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 10);

        ServletContext servletContext = mock(ServletContext.class);
        FilterRegistration.Dynamic dynamic = mock(FilterRegistration.Dynamic.class);
        when(servletContext.addFilter("tenantContextFilter", registration.getFilter()))
                .thenReturn(dynamic);
        registration.onStartup(servletContext);
        verify(servletContext).addFilter("tenantContextFilter", registration.getFilter());
    }

    @Test
    void featureFlagAnnotationsAreMutuallyExclusive() throws Exception {
        Method headerBean = MultiTenancyConfig.class
                .getDeclaredMethod("tenantContextFilter", TenantProperties.class);
        ConditionalOnProperty headerCondition =
                headerBean.getAnnotation(ConditionalOnProperty.class);
        ConditionalOnProperty permitCondition =
                PermitAllSecurityConfig.class.getAnnotation(ConditionalOnProperty.class);
        ConditionalOnProperty authCondition =
                ActivityResourceServerConfig.class.getAnnotation(ConditionalOnProperty.class);

        assertThat(headerCondition.name()).containsExactly("activity.tenant.auth.enabled");
        assertThat(headerCondition.havingValue()).isEqualTo("false");
        assertThat(headerCondition.matchIfMissing()).isTrue();
        assertThat(permitCondition.havingValue()).isEqualTo("false");
        assertThat(permitCondition.matchIfMissing()).isTrue();
        assertThat(authCondition.havingValue()).isEqualTo("true");
        assertThat(authCondition.matchIfMissing()).isFalse();
    }

    @Test
    void securityChainsKeepRequiredOrdering() {
        Method activity = method(ActivityResourceServerConfig.class,
                "activitySecurityFilterChain");
        Method open = method(ActivityResourceServerConfig.class,
                "activityOpenFilterChain");
        assertThat(activity.getAnnotation(Order.class).value()).isEqualTo(1);
        assertThat(open.getAnnotation(Order.class).value()).isEqualTo(2);
    }

    @Test
    void propertiesHaveFailClosedDefaultsExceptExplicitWarmup() {
        TenantProperties props = new TenantProperties();
        TenantProperties.Auth auth = props.getAuth();
        assertThat(props.isDevDefaultEnabled()).isFalse();
        assertThat(props.getDevDefault()).isEqualTo("__dev__");
        assertThat(auth.isEnabled()).isFalse();
        assertThat(auth.isWarmupEnabled()).isTrue();
        assertThat(auth.getJwksFetchTimeoutMs()).isEqualTo(2000);
        assertThat(auth.getConsoleWriteAuthority()).isEmpty();
        assertThat(auth.getClientTenantMap()).isEmpty();
        assertThat(auth.getAudienceTemplates()).containsExactly("activity-{tenant}-cid");
    }

    private static Method method(Class<?> type, String name) {
        return java.util.Arrays.stream(type.getDeclaredMethods())
                .filter(method -> method.getName().equals(name))
                .findFirst()
                .orElseThrow();
    }
}
```

关键断言：使用 Hibernate 编译期常量而不是字符串副本；条件注解同时检查 `havingValue` 与 `matchIfMissing`，防止 feature flag 缺省方向反转。结构测试不能替代实际链行为，因此另有门控 IT。

### 5.7 `JwksWarmupRunnerTest`

放置路径：`src/test/java/com/lrj/drools/activity/tenant/JwksWarmupRunnerTest.java`

```java
package com.lrj.drools.activity.tenant;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class JwksWarmupRunnerTest {

    @Test
    void warmupFlagControlsFetch() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/jwks", exchange -> {
            calls.incrementAndGet();
            byte[] body = "{\"keys\":[{\"kid\":\"one\"}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        try {
            TenantProperties props = new TenantProperties();
            props.getAuth().setJwkSetUri(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/jwks");
            props.getAuth().setJwksFetchTimeoutMs(500);
            ApplicationArguments args = mock(ApplicationArguments.class);

            props.getAuth().setWarmupEnabled(false);
            new JwksWarmupRunner(props).run(args);
            assertThat(calls).hasValue(0);

            props.getAuth().setWarmupEnabled(true);
            new JwksWarmupRunner(props).run(args);
            assertThat(calls).hasValue(1);
        } finally {
            server.stop(0);
        }
    }
}
```

关键断言：用本地确定性 HTTP server 统计真实 GET 次数，不以日志文本或耗时作为脆弱代理。失败时是否阻止启动留给 ISSUE-08 的产品决策。

### 5.8 `RuleSchemaRegistryTenantTest`

放置路径：`src/test/java/com/lrj/drools/activity/engine/RuleSchemaRegistryTenantTest.java`

```java
package com.lrj.drools.activity.engine;

import com.lrj.drools.activity.domain.FieldValueType;
import com.lrj.drools.activity.domain.RuleOperator;
import com.lrj.drools.activity.domain.SchemaField;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RuleSchemaRegistryTenantTest {

    private RuleSchemaRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new RuleSchemaRegistry();
    }

    @Test
    void bizOverrideWinsThenTenantFallbackThenDefault() {
        SchemaField tenantField = numberField("loyaltyScore");
        SchemaField bizField = numberField("completedTrips");
        registry.register("acme", null, List.of(tenantField));
        registry.register("acme", "travel", List.of(bizField));

        assertThat(registry.resolve("acme", "travel")).containsOnlyKeys("completedTrips");
        assertThat(registry.resolve("acme", "retail")).containsOnlyKeys("loyaltyScore");
        assertThat(registry.resolve("beta", "travel"))
                .containsKeys("orderAmount", "quantity", "userDistrictId", "userTags", "spuId", "storeId")
                .doesNotContainKeys("completedTrips", "loyaltyScore");
    }

    @Test
    void registrationsAreTenantIsolated() {
        registry.register("acme", null, List.of(numberField("acmeOnly")));
        registry.register("beta", null, List.of(numberField("betaOnly")));
        assertThat(registry.resolve("acme", null)).containsOnlyKeys("acmeOnly");
        assertThat(registry.resolve("beta", null)).containsOnlyKeys("betaOnly");
    }

    @Test
    void registeredSchemaIsImmutableAndReplaceable() {
        ArrayList<SchemaField> input = new ArrayList<>(List.of(numberField("first")));
        registry.register("acme", null, input);
        input.add(numberField("lateMutation"));
        Map<String, SchemaField> first = registry.resolve("acme", null);
        assertThat(first).containsOnlyKeys("first");
        assertThrows(UnsupportedOperationException.class,
                () -> first.put("x", numberField("x")));

        registry.register("acme", null, List.of(numberField("replacement")));
        assertThat(registry.resolve("acme", null)).containsOnlyKeys("replacement");
    }

    @Test
    void resolveFieldsReflectsSelectedSchema() {
        registry.register("acme", null, List.of(numberField("custom")));
        assertThat(registry.resolveFields("acme", null))
                .extracting(SchemaField::key)
                .containsExactly("custom");
        assertThat(registry.defaultFields())
                .extracting(SchemaField::key)
                .containsExactly("orderAmount", "quantity", "userDistrictId",
                        "userTags", "spuId", "storeId");
    }

    private static SchemaField numberField(String key) {
        return new SchemaField(key, key, FieldValueType.NUMBER,
                EnumSet.of(RuleOperator.GE), List.of());
    }
}
```

关键断言：优先级测试同时包含 exact、tenant fallback 和共享 default；隔离测试使用互斥 key，能发现 key 拼接或 fallback 串租户，而不只是检查集合非空。

### 5.9 `ActivityMarketingControllerFieldDictTest`

放置路径：`src/test/java/com/lrj/drools/activity/controller/ActivityMarketingControllerFieldDictTest.java`

```java
package com.lrj.drools.activity.controller;

import com.lrj.drools.activity.domain.FieldValueType;
import com.lrj.drools.activity.domain.RuleOperator;
import com.lrj.drools.activity.domain.SchemaField;
import com.lrj.drools.activity.engine.RuleSchemaRegistry;
import com.lrj.drools.activity.service.ActivityMarketingService;
import com.lrj.drools.activity.service.ActivityQueryService;
import com.lrj.drools.activity.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ActivityMarketingControllerFieldDictTest {

    private RuleSchemaRegistry registry;
    private ActivityMarketingController controller;

    @BeforeEach
    void setUp() {
        TenantContext.set("acme");
        registry = new RuleSchemaRegistry();
        controller = new ActivityMarketingController(
                mock(ActivityMarketingService.class), mock(ActivityQueryService.class), registry);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void fieldDictionaryUsesCurrentTenantOverride() {
        registry.register("acme", null, List.of(numberField("loyaltyScore")));
        assertThat(fieldKeys(controller.fieldDict())).containsExactly("loyaltyScore");

        TenantContext.set("beta");
        assertThat(fieldKeys(controller.fieldDict()))
                .contains("orderAmount")
                .doesNotContain("loyaltyScore");
    }

    @Disabled("TODO(issue-04): field-dict API 需接收 bizLine 后启用")
    @Test
    void bizLineOverrideMustBeVisibleToFieldDictionary() {
        registry.register("acme", "travel", List.of(numberField("completedTrips")));
        assertThat(fieldKeys(controller.fieldDict())).contains("completedTrips");
    }

    @SuppressWarnings("unchecked")
    private static List<String> fieldKeys(ResponseEntity<?> response) {
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        List<Map<String, Object>> fields = (List<Map<String, Object>>) body.get("fields");
        return fields.stream().map(field -> (String) field.get("key")).toList();
    }

    private static SchemaField numberField(String key) {
        return new SchemaField(key, key, FieldValueType.NUMBER,
                EnumSet.of(RuleOperator.GE), List.of());
    }
}
```

关键断言：同一个 controller/registry 实例中切换 TenantContext，acme 只能看到 override，beta 回到默认且不能看到 acme 字段；这是实际 response body，而非只测 registry。

### 5.10 `ActivityMarketingTenantSchemaTest`

放置路径：`src/test/java/com/lrj/drools/activity/service/ActivityMarketingTenantSchemaTest.java`

```java
package com.lrj.drools.activity.service;

import com.lrj.drools.activity.domain.ActivityCreateRequest;
import com.lrj.drools.activity.domain.ConditionNode;
import com.lrj.drools.activity.domain.FieldValueType;
import com.lrj.drools.activity.domain.RuleOperator;
import com.lrj.drools.activity.domain.SchemaField;
import com.lrj.drools.activity.engine.ActivityDrlBuilder;
import com.lrj.drools.activity.engine.ActivityRuleRuntimeService;
import com.lrj.drools.activity.engine.RuleConditionTranslator;
import com.lrj.drools.activity.engine.RuleSchemaRegistry;
import com.lrj.drools.activity.persistence.ActivityConditionRepository;
import com.lrj.drools.activity.persistence.ActivityGiftRepository;
import com.lrj.drools.activity.persistence.ActivityManageRepository;
import com.lrj.drools.activity.persistence.ActivityRuleRepository;
import com.lrj.drools.activity.persistence.ActivitySpuBindingRepository;
import com.lrj.drools.activity.persistence.ActivityStrategyRepository;
import com.lrj.drools.activity.persistence.PoolRefRepository;
import com.lrj.drools.activity.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class ActivityMarketingTenantSchemaTest {

    private ActivityManageRepository manageRepo;
    private ActivityRuleRepository ruleRepo;
    private ActivityConditionRepository conditionRepo;
    private ActivityMarketingService service;

    @BeforeEach
    void setUp() {
        TenantContext.set("acme");
        manageRepo = mock(ActivityManageRepository.class);
        ruleRepo = mock(ActivityRuleRepository.class);
        conditionRepo = mock(ActivityConditionRepository.class);

        RuleSchemaRegistry registry = new RuleSchemaRegistry();
        registry.register("acme", "travel", List.of(new SchemaField(
                "loyaltyScore", "忠诚度", FieldValueType.NUMBER,
                EnumSet.of(RuleOperator.GE), List.of())));
        ActivityDrlBuilder drlBuilder = new ActivityDrlBuilder();
        service = new ActivityMarketingService(
                manageRepo,
                ruleRepo,
                conditionRepo,
                mock(ActivitySpuBindingRepository.class),
                mock(ActivityGiftRepository.class),
                mock(PoolRefRepository.class),
                mock(ActivityStrategyRepository.class),
                new RuleConditionTranslator(),
                registry,
                drlBuilder,
                new ActivityRuleRuntimeService(drlBuilder),
                mock(ActivityPoolMatchService.class));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void createUsesTenantAndBizLineSchemaBeforeAnyWrite() {
        ActivityCreateRequest request = request(condition("loyaltyScore", "ge", 100));

        ActivityMarketingService.CreateResult acme = service.create(request);
        assertThat(acme.version()).isEqualTo(1);
        assertThat(acme.idempotentHit()).isFalse();
        verify(manageRepo).saveAndFlush(any());
        verify(conditionRepo).save(any());

        clearInvocations(manageRepo, ruleRepo, conditionRepo);
        TenantContext.set("beta");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(request));
        assertThat(ex).hasMessageContaining("loyaltyScore");
        verifyNoInteractions(manageRepo, ruleRepo, conditionRepo);
    }

    private static ActivityCreateRequest request(ConditionNode condition) {
        return new ActivityCreateRequest(
                null, null, "tenant schema", "travel", 1, "rule",
                1_000L, 2_000L, 1, null, 1, 100,
                1, BigDecimal.TEN, "元", null, null,
                condition, null, null, null);
    }

    private static ConditionNode condition(String field, String op, Object value) {
        ConditionNode node = new ConditionNode();
        node.setField(field);
        node.setOp(op);
        node.setValue(value);
        return node;
    }
}
```

关键断言：acme 路径真实翻译并编译 DRL，beta 路径不仅抛未知字段，还验证三个写 repository 零交互，证明 fail-closed 发生在任何持久化之前。

### 5.11 `ActivityRuleRuntimeTenantCacheTest`

放置路径：`src/test/java/com/lrj/drools/activity/engine/ActivityRuleRuntimeTenantCacheTest.java`

```java
package com.lrj.drools.activity.engine;

import com.lrj.drools.activity.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kie.api.KieBase;

import static org.assertj.core.api.Assertions.assertThat;

class ActivityRuleRuntimeTenantCacheTest {

    private static final String DRL = """
            package com.lrj.drools.activity.cachetest;
            rule "noop"
            when
            then
            end
            """;

    private ActivityRuleRuntimeService runtime;

    @BeforeEach
    void setUp() {
        TenantContext.set("acme");
        runtime = new ActivityRuleRuntimeService(new ActivityDrlBuilder());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void sameTenantAndDrlReuseCompiledKieBase() {
        KieBase first = runtime.compileOrGet(DRL);
        KieBase second = runtime.compileOrGet(DRL);
        assertThat(second).isSameAs(first);
        assertThat(runtime.cacheSize()).isEqualTo(1);
    }

    @Test
    void sameDrlIsPartitionedByTenant() {
        KieBase acme = runtime.compileOrGet(DRL);
        TenantContext.set("beta");
        KieBase beta = runtime.compileOrGet(DRL);
        assertThat(beta).isNotSameAs(acme);
        assertThat(runtime.cacheSize()).isEqualTo(2);
    }

    @Test
    void noContextHasItsOwnCachePartition() {
        KieBase acme = runtime.compileOrGet(DRL);
        TenantContext.clear();
        KieBase noTenant = runtime.compileOrGet(DRL);
        assertThat(noTenant).isNotSameAs(acme);
        assertThat(runtime.cacheSize()).isEqualTo(2);
    }

    @Test
    void evictAllForcesRecompile() {
        KieBase before = runtime.compileOrGet(DRL);
        runtime.evictAll();
        KieBase after = runtime.compileOrGet(DRL);
        assertThat(after).isNotSameAs(before);
        assertThat(runtime.cacheSize()).isEqualTo(1);
    }
}
```

关键断言：对象 identity + cache size 双重验证命中/分片；仅断 size 会漏掉错误替换，仅断非 null 则完全不能证明 cache key。

### 5.12 `ActivityManageEntityConstraintTest`

放置路径：`src/test/java/com/lrj/drools/activity/persistence/ActivityManageEntityConstraintTest.java`

```java
package com.lrj.drools.activity.persistence;

import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class ActivityManageEntityConstraintTest {

    @Test
    void idempotencyConstraintIsTenantScoped() {
        Table table = ActivityManageEntity.class.getAnnotation(Table.class);
        UniqueConstraint constraint = Arrays.stream(table.uniqueConstraints())
                .filter(candidate -> candidate.name().equals("uk_am_tenant_request"))
                .findFirst()
                .orElseThrow();
        assertThat(constraint.columnNames())
                .containsExactly("tenant_id", "request_id");
    }
}
```

关键断言：精确检查 constraint name 和列顺序，避免只证明“某个 unique constraint 存在”；真正执行效果由 H2 并发 IT 证明。

### 5.13 `ActivityMarketingIdempotencyTest`

放置路径：`src/test/java/com/lrj/drools/activity/service/ActivityMarketingIdempotencyTest.java`

```java
package com.lrj.drools.activity.service;

import com.lrj.drools.activity.domain.ActivityCreateRequest;
import com.lrj.drools.activity.engine.ActivityDrlBuilder;
import com.lrj.drools.activity.engine.ActivityRuleRuntimeService;
import com.lrj.drools.activity.engine.RuleConditionTranslator;
import com.lrj.drools.activity.engine.RuleSchemaRegistry;
import com.lrj.drools.activity.persistence.ActivityConditionRepository;
import com.lrj.drools.activity.persistence.ActivityGiftRepository;
import com.lrj.drools.activity.persistence.ActivityManageEntity;
import com.lrj.drools.activity.persistence.ActivityManageRepository;
import com.lrj.drools.activity.persistence.ActivityRuleRepository;
import com.lrj.drools.activity.persistence.ActivitySpuBindingRepository;
import com.lrj.drools.activity.persistence.ActivityStrategyRepository;
import com.lrj.drools.activity.persistence.PoolRefRepository;
import com.lrj.drools.activity.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ActivityMarketingIdempotencyTest {

    private ActivityManageRepository manageRepo;
    private ActivityMarketingService service;

    @BeforeEach
    void setUp() {
        TenantContext.set("acme");
        manageRepo = mock(ActivityManageRepository.class);
        ActivityDrlBuilder builder = new ActivityDrlBuilder();
        service = new ActivityMarketingService(
                manageRepo,
                mock(ActivityRuleRepository.class),
                mock(ActivityConditionRepository.class),
                mock(ActivitySpuBindingRepository.class),
                mock(ActivityGiftRepository.class),
                mock(PoolRefRepository.class),
                mock(ActivityStrategyRepository.class),
                new RuleConditionTranslator(),
                new RuleSchemaRegistry(),
                builder,
                new ActivityRuleRuntimeService(builder),
                mock(ActivityPoolMatchService.class));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void existingRequestIdReturnsStoredResultWithoutWriting() {
        ActivityManageEntity existing = new ActivityManageEntity();
        existing.setActivityId("ACT-existing");
        existing.setVersion(3);
        existing.setActivityStatus(1);
        when(manageRepo.findFirstByRequestIdAndIsDel("key-1", 0))
                .thenReturn(Optional.of(existing));

        ActivityMarketingService.CreateResult result = service.create(request("key-1"));
        assertThat(result).isEqualTo(new ActivityMarketingService.CreateResult(
                "ACT-existing", 3, 1, true, 0));
        verify(manageRepo, never()).saveAndFlush(any());
    }

    @Test
    void uniqueConstraintViolationBecomesRetryableConflict() {
        when(manageRepo.findFirstByRequestIdAndIsDel("key-1", 0))
                .thenReturn(Optional.empty());
        when(manageRepo.saveAndFlush(any())).thenThrow(
                new DataIntegrityViolationException("uk_am_tenant_request"));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.create(request("key-1")));
        assertThat(ex).hasMessageContaining("并发重复请求").hasMessageContaining("key-1");
    }

    @Disabled("TODO(issue-05): blank requestId 入口标准化为 null 后启用")
    @Test
    void blankRequestIdMustBeNormalizedToNull() {
        when(manageRepo.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        service.create(request("   "));
        ArgumentCaptor<ActivityManageEntity> captor =
                ArgumentCaptor.forClass(ActivityManageEntity.class);
        verify(manageRepo).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getRequestId()).isNull();
    }

    @Disabled("TODO(issue-06): 非唯一约束完整性异常不得伪装成幂等冲突")
    @Test
    void unrelatedIntegrityViolationMustNotBeReportedAsDuplicate() {
        when(manageRepo.saveAndFlush(any())).thenThrow(
                new DataIntegrityViolationException("activity_name value too long"));
        DataIntegrityViolationException ex = assertThrows(DataIntegrityViolationException.class,
                () -> service.create(request(null)));
        assertThat(ex).hasMessageContaining("activity_name");
    }

    private static ActivityCreateRequest request(String requestId) {
        return new ActivityCreateRequest(
                requestId, null, "idempotent", "retail", 1, "rule",
                1_000L, 2_000L, 1, null, 1, 100,
                1, BigDecimal.TEN, "元", null, null,
                null, null, null, null);
    }
}
```

关键断言：幂等 hit 断言完整 record 且 verify 无写；冲突断言同时检查类型和 key。TODO 用 ArgumentCaptor 检查真正写入实体的规范值，不用 mock 一个“看似成功”的返回值替代数据库语义。

### 5.14 `ActivityMarketingControllerIdempotencyTest`

放置路径：`src/test/java/com/lrj/drools/activity/controller/ActivityMarketingControllerIdempotencyTest.java`

```java
package com.lrj.drools.activity.controller;

import com.lrj.drools.activity.domain.ActivityCreateRequest;
import com.lrj.drools.activity.engine.RuleSchemaRegistry;
import com.lrj.drools.activity.service.ActivityMarketingService;
import com.lrj.drools.activity.service.ActivityQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ActivityMarketingControllerIdempotencyTest {

    @Test
    void concurrentDuplicateBecomes409() {
        ActivityMarketingService marketing = mock(ActivityMarketingService.class);
        when(marketing.create(any(ActivityCreateRequest.class)))
                .thenThrow(new IllegalStateException("并发重复请求(requestId)，请重试: key-1"));
        ActivityMarketingController controller = new ActivityMarketingController(
                marketing, mock(ActivityQueryService.class), new RuleSchemaRegistry());

        ResponseEntity<?> response = controller.create(mock(ActivityCreateRequest.class));
        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody())
                .isEqualTo(new ActivityMarketingController.ErrorResponse(
                        "并发重复请求(requestId)，请重试: key-1"));
    }
}
```

关键断言：直接 new controller，断言精确 HTTP code 和响应体；仅断“抛了异常”不能证明 API 的 409 契约。

### 5.15 `ActivitySecurityChainsIT`（默认排除）

放置路径：`src/test/java/com/lrj/drools/activity/tenant/ActivitySecurityChainsIT.java`

理由：`SecurityFilterChain` 的 matcher、AuthorizationFilter 顺序、JWT decoder 与 authority DSL 只有在真实 servlet security chain 中才可诚实验证。该类不使用 `@SpringBootTest`/MockMvc；手动启动应用，本地提供 JWKS，使用随机端口和 `DriverManagerDataSource`。`*IT` 不匹配 Surefire 默认 `*Test`，且额外由环境变量门控。

```java
package com.lrj.drools.activity.tenant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.drools.DroolsDemoApplication;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.sql.DataSource;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("security-integration")
@EnabledIfEnvironmentVariable(named = "RUN_ACTIVITY_SECURITY_IT", matches = "true")
class ActivitySecurityChainsIT {

    private static final String ISSUER = "https://tenant-security-it";
    private static final String DATABASE = "security_" + UUID.randomUUID().toString().replace("-", "");
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper JSON = new ObjectMapper();

    private static RSAKey rsa;
    private static HttpServer jwks;
    private static ConfigurableApplicationContext context;
    private static URI baseUri;

    @TestConfiguration(proxyBeanMethods = false)
    static class H2DataSourceConfig {
        @Bean
        @Primary
        DataSource testDataSource() {
            return new DriverManagerDataSource(
                    "jdbc:h2:mem:" + DATABASE + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        }
    }

    @BeforeAll
    static void start() throws Exception {
        rsa = new RSAKeyGenerator(2048).keyID("security-it-key").generate();
        byte[] jwksBody = JSON.writeValueAsBytes(
                new JWKSet(rsa.toPublicJWK()).toJSONObject());
        jwks = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        jwks.createContext("/jwks", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, jwksBody.length);
            try (var output = exchange.getResponseBody()) {
                output.write(jwksBody);
            }
        });
        jwks.start();

        SpringApplication app = new SpringApplication(
                DroolsDemoApplication.class, H2DataSourceConfig.class);
        app.setAdditionalProfiles("h2");
        // 必须用 command-line properties 覆盖 application.yml；defaultProperties 优先级更低，
        // 会被仓库中的 server.port=8081 / auth.enabled=false 反向覆盖。
        context = app.run(
                "--server.port=0",
                "--spring.jpa.hibernate.ddl-auto=create-drop",
                "--activity.marketing.seed-demo-data=false",
                "--activity.tenant.dev-default-enabled=false",
                "--activity.tenant.auth.enabled=true",
                "--activity.tenant.auth.warmup-enabled=false",
                "--activity.tenant.auth.issuer=" + ISSUER,
                "--activity.tenant.auth.jwk-set-uri=http://127.0.0.1:"
                        + jwks.getAddress().getPort() + "/jwks",
                "--activity.tenant.auth.audience-templates[0]=activity-{tenant}-cid",
                "--activity.tenant.auth.console-write-authority=SCOPE_activity.write");
        int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
        baseUri = URI.create("http://127.0.0.1:" + port);
    }

    @BeforeEach
    void setUpTestThreadContext() {
        TenantContext.set("test-thread-fixture");
    }

    @AfterEach
    void clearTestThreadContext() {
        TenantContext.clear();
    }

    @AfterAll
    static void stop() {
        if (context != null) {
            context.close();
        }
        if (jwks != null) {
            jwks.stop(0);
        }
    }

    @Test
    void nonActivityEndpointsRemainOpenWhileActivityEndpointRequiresJwt() throws Exception {
        assertThat(send("GET", "/activity-marketing/list", null, null).statusCode())
                .isEqualTo(401);
        assertThat(send("GET", "/actuator/health", null, null).statusCode())
                .isEqualTo(200);
        assertThat(send("GET", "/", null, null).statusCode()).isEqualTo(200);
        assertThat(send("POST", "/hello", null,
                "{\"name\":\"tester\",\"age\":20,\"vipLevel\":0,\"yearsSinceRegistration\":1}")
                .statusCode()).isEqualTo(200);
    }

    @Test
    void consoleWriteAuthorityProtectsWritesButNotReads() throws Exception {
        String readOnly = mint("activity-acme-cid", null);
        String writer = mint("activity-acme-cid", "activity.write");

        assertThat(send("GET", "/activity-marketing/list", readOnly, null).statusCode())
                .isEqualTo(200);
        assertThat(send("POST", "/activity-marketing/create", readOnly, createBody("read-only"))
                .statusCode()).isEqualTo(403);
        assertThat(send("POST", "/activity-marketing/ACT-missing/status", readOnly,
                "{\"version\":1,\"targetStatus\":1}").statusCode()).isEqualTo(403);

        HttpResponse<String> created = send(
                "POST", "/activity-marketing/create", writer, createBody("writer"));
        assertThat(created.statusCode()).isEqualTo(200);
        assertThat(created.body()).contains("\"activityId\":\"ACT");
    }

    private static HttpResponse<String> send(
            String method, String path, String token, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(baseUri.resolve(path))
                .header("Accept", "application/json");
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String mint(String audience, String scope) {
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject("admin/" + audience)
                .audience(List.of(audience))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300));
        if (scope != null) {
            claims.claim("scope", scope);
        }
        JwtEncoder encoder = new NimbusJwtEncoder(
                new ImmutableJWKSet<>(new JWKSet(rsa)));
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256)
                .keyId(rsa.getKeyID()).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims.build()))
                .getTokenValue();
    }

    private static String createBody(String name) {
        return """
                {"activityName":"%s","bizLine":"security-it","activityType":1,
                 "activityRule":"rule","activityStartTime":1000,"activityEndTime":2000,
                 "activityAreaType":1,"priority":1,"inventory":100,
                 "redPackageTakeType":1,"redPackageAmount":10,"redPackageAmountUnit":"元"}
                """.formatted(name);
    }
}
```

关键断言：读 token 能访问 list 却不能 create/status，证明不是 token 本身无效；write scope 最终创建出活动，证明 `scope` converter 与 endpoint matcher 都生效。开放链同时验证 actuator、静态页和真实 Step 1。

### 5.16 `ActivityIdempotencyConcurrencyIT`（默认排除）

放置路径：`src/test/java/com/lrj/drools/activity/service/ActivityIdempotencyConcurrencyIT.java`

理由：仓库没有用户铁律中提到的 `Jdbc*Store`，被测实现是 Spring Data JPA + Hibernate `@TenantId`。为了不手写表且真正执行实体声明的唯一约束，本 IT 手动启动应用，让 Hibernate 创建表；DataSource 仍严格使用每类唯一名称的 `DriverManagerDataSource`。barrier 只包围真实 repository 的幂等预读，写入和约束判断都由真实 JPA/H2 完成。

```java
package com.lrj.drools.activity.service;

import com.lrj.drools.DroolsDemoApplication;
import com.lrj.drools.activity.domain.ActivityCreateRequest;
import com.lrj.drools.activity.engine.ActivityDrlBuilder;
import com.lrj.drools.activity.engine.ActivityRuleRuntimeService;
import com.lrj.drools.activity.engine.RuleConditionTranslator;
import com.lrj.drools.activity.engine.RuleSchemaRegistry;
import com.lrj.drools.activity.persistence.ActivityConditionRepository;
import com.lrj.drools.activity.persistence.ActivityGiftRepository;
import com.lrj.drools.activity.persistence.ActivityManageEntity;
import com.lrj.drools.activity.persistence.ActivityManageRepository;
import com.lrj.drools.activity.persistence.ActivityRuleRepository;
import com.lrj.drools.activity.persistence.ActivitySpuBindingRepository;
import com.lrj.drools.activity.persistence.ActivityStrategyRepository;
import com.lrj.drools.activity.persistence.PoolRefRepository;
import com.lrj.drools.activity.tenant.TenantContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

@Tag("idempotency-integration")
@EnabledIfEnvironmentVariable(named = "RUN_ACTIVITY_IDEMPOTENCY_IT", matches = "true")
class ActivityIdempotencyConcurrencyIT {

    private static final String DATABASE = "idempotency_" +
            UUID.randomUUID().toString().replace("-", "");
    private static ConfigurableApplicationContext context;

    @TestConfiguration(proxyBeanMethods = false)
    static class H2DataSourceConfig {
        @Bean
        @Primary
        DataSource testDataSource() {
            return new DriverManagerDataSource(
                    "jdbc:h2:mem:" + DATABASE + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        }
    }

    @BeforeAll
    static void start() {
        SpringApplication app = new SpringApplication(
                DroolsDemoApplication.class, H2DataSourceConfig.class);
        app.setAdditionalProfiles("h2");
        context = app.run(
                "--server.port=0",
                "--spring.jpa.hibernate.ddl-auto=create-drop",
                "--activity.marketing.seed-demo-data=false",
                "--activity.tenant.dev-default-enabled=false",
                "--activity.tenant.auth.enabled=false");
    }

    @BeforeEach
    void setUpTestThreadContext() {
        TenantContext.set("test-thread-fixture");
    }

    @AfterEach
    void clearTestThreadContext() {
        TenantContext.clear();
    }

    @AfterAll
    static void stop() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void databaseConstraintSelectsSingleWinnerAndRetryBecomesHit() throws Exception {
        ActivityManageRepository realRepo = context.getBean(ActivityManageRepository.class);
        ActivityManageRepository gateRepo = mock(ActivityManageRepository.class);
        CountDownLatch bothChecked = new CountDownLatch(2);

        when(gateRepo.findFirstByRequestIdAndIsDel("race-key", 0)).thenAnswer(invocation -> {
            bothChecked.countDown();
            if (!bothChecked.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("both transactions did not reach idempotency pre-read");
            }
            return Optional.empty();
        });
        when(gateRepo.saveAndFlush(any(ActivityManageEntity.class))).thenAnswer(invocation ->
                realRepo.saveAndFlush(invocation.getArgument(0)));

        ActivityMarketingService service = service(gateRepo);
        TransactionTemplate transactions = new TransactionTemplate(
                context.getBean(PlatformTransactionManager.class));
        ActivityCreateRequest request = request("race-key");
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<Object> first = workers.submit(() -> outcome(() ->
                    inTransaction(transactions, "acme", () -> service.create(request))));
            Future<Object> second = workers.submit(() -> outcome(() ->
                    inTransaction(transactions, "acme", () -> service.create(request))));
            Object one = first.get(15, TimeUnit.SECONDS);
            Object two = second.get(15, TimeUnit.SECONDS);

            assertThat(Stream.of(one, two)
                    .filter(ActivityMarketingService.CreateResult.class::isInstance).count())
                    .isEqualTo(1);
            assertThat(Stream.of(one, two).filter(Throwable.class::isInstance).count())
                    .isEqualTo(1);
            Throwable loser = (Throwable) Stream.of(one, two)
                    .filter(Throwable.class::isInstance).findFirst().orElseThrow();
            assertThat(loser).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("并发重复请求")
                    .hasMessageContaining("race-key");

            ActivityMarketingService.CreateResult winner =
                    (ActivityMarketingService.CreateResult) Stream.of(one, two)
                            .filter(ActivityMarketingService.CreateResult.class::isInstance)
                            .findFirst().orElseThrow();

            reset(gateRepo);
            when(gateRepo.findFirstByRequestIdAndIsDel("race-key", 0)).thenAnswer(invocation ->
                    realRepo.findFirstByRequestIdAndIsDel("race-key", 0));
            when(gateRepo.saveAndFlush(any(ActivityManageEntity.class))).thenAnswer(invocation ->
                    realRepo.saveAndFlush(invocation.getArgument(0)));

            ActivityMarketingService.CreateResult retry = inTransaction(
                    transactions, "acme", () -> service.create(request));
            assertThat(retry.idempotentHit()).isTrue();
            assertThat(retry.activityId()).isEqualTo(winner.activityId());

            ActivityMarketingService.CreateResult beta = inTransaction(
                    transactions, "beta", () -> service.create(request));
            assertThat(beta.idempotentHit()).isFalse();
            assertThat(beta.activityId()).isNotEqualTo(winner.activityId());
        } finally {
            workers.shutdownNow();
        }
    }

    private static ActivityMarketingService service(ActivityManageRepository manageRepo) {
        RuleConditionTranslator translator = mock(RuleConditionTranslator.class);
        RuleSchemaRegistry schemaRegistry = mock(RuleSchemaRegistry.class);
        ActivityDrlBuilder drlBuilder = mock(ActivityDrlBuilder.class);
        ActivityRuleRuntimeService runtime = mock(ActivityRuleRuntimeService.class);
        return new ActivityMarketingService(
                manageRepo,
                mock(ActivityRuleRepository.class),
                mock(ActivityConditionRepository.class),
                mock(ActivitySpuBindingRepository.class),
                mock(ActivityGiftRepository.class),
                mock(PoolRefRepository.class),
                mock(ActivityStrategyRepository.class),
                translator,
                schemaRegistry,
                drlBuilder,
                runtime,
                mock(ActivityPoolMatchService.class));
    }

    private static Object outcome(Supplier<Object> operation) {
        try {
            return operation.get();
        } catch (Throwable failure) {
            return failure;
        }
    }

    private static <T> T inTransaction(
            TransactionTemplate transactions, String tenant, Supplier<T> body) {
        // 必须在 TransactionTemplate 开事务/创建 EntityManager 之前 set：
        // Hibernate 在 Session 创建时解析并固定 tenant identifier。
        TenantContext.set(tenant);
        try {
            return transactions.execute(status -> body.get());
        } finally {
            TenantContext.clear();
        }
    }

    private static ActivityCreateRequest request(String requestId) {
        return new ActivityCreateRequest(
                requestId, null, "concurrent idempotency", "retail", 1, "rule",
                1_000L, 2_000L, 1, null, 1, 100,
                1, BigDecimal.TEN, "元", null, null,
                null, null, null, null);
    }
}
```

关键断言：barrier 保证两事务都看见“无重复”，因此 loser 只能来自真实唯一约束而不是顺序预读命中；重试与跨 tenant 断言同时验证业务恢复语义和 composite key，而非只数数据库行。

## 6. edge-case-hunter 清单

| 边缘 | 触发 | 期望 |
|---|---|---|
| 空/blank header | 不发送、`""`、纯空格 | default off 403；default on 先校验 default 再决定 |
| tenant 长度 | 1、64、65 字符 | 前两者合法、65 为 400/配置失败 |
| tenant 注入 | 空格、`;`、换行、`.`、Unicode | header/map 均拒绝；模板 literal 不被 regex 解释 |
| 内部值 | `__no_tenant__`、`__single__` | 外部来源不可解析或 400；内部 fallback 仅内部可用 |
| aud collection | null、空、null item、blank、重复、known+unknown、2/3 tenant | 只按不同有效 tenant 数判断，0/≥2 fail closed |
| JWT auth 类型 | null、非 `JwtAuthenticationToken`、歧义 JWT | 403，不调用下游且 clear |
| 下游异常 | IOException/ServletException/runtime | 原异常传播，finally clear |
| requestId | null、`""`、空白、同/异 payload、跨 tenant、编辑后重放 | null/blank 无幂等；同 tenant 稳定结果；跨 tenant 独立；生命周期先定契约 |
| DB 异常 | 目标 unique、长度、not-null、连接异常 | 仅目标 unique 转幂等 409，其余保留类别 |
| registry | null tenant/bizLine、exact/fallback、替换、输入 list 后改、重复 field key | 优先级确定、snapshot 不变；null/duplicate 策略待补 validation |
| cache | tenant A/B/null + 同 DRL、evict、并发同 key | tenant 分片、同 key 原子命中、evict 后重编译 |
| JWKS | warmup off/on、空 body、无 keys、坏 JSON、不可达、慢响应 | off 零访问；on 有界尝试；失败策略按 ISSUE-08 决策 |

## 7. flaky-risk-reviewer：风险与稳健写法

- ThreadLocal/安全上下文：每个相关类 `@BeforeEach` 使用仓库实际 `TenantContext.set(String)`；`@AfterEach` 无条件 clear。JWT 测试还 clear `SecurityContextHolder`。executor 任务自身也 finally clear，不能依赖测试线程 teardown 清 worker。
- 线程池复用：使用 `Executors.newSingleThreadExecutor()` 明确复用；并发唯一约束使用固定 2 线程和 `CountDownLatch`，禁止 `Thread.sleep`。所有 `Future#get` 带上界，finally `shutdownNow()`。
- H2 污染：每个 IT 以 UUID 生成独立库名，使用 `MODE=MySQL;DB_CLOSE_DELAY=-1`，context 在 `@AfterAll` 关闭；不复用现有 `tenantiso/authiso` 名称。
- Spring context 顺序：IT 手动拥有并关闭 context；不用静态 context cache，不依赖测试类顺序。随机 server/JWKS port，避免 8081/固定端口冲突。
- 时间：POJO 请求用固定 epoch；JWT 只给 5 分钟宽窗口，不断言精确秒数；不以 JWKS timeout 的 wall-clock 毫秒作为默认断言。
- RSA/JWKS：每 IT 类只生成一次 key，encoder/decoder 共用；JWKS 是进程内 server，无网络和登录依赖；server 必须 stop。
- Caffeine：每测试 new runtime，断言同步 get 后的 identity/size；不读取命中率时间窗口，不依赖淘汰顺序。若未来测 500 上限，需显式 `cleanUp()` 接缝后再做，避免 estimated size 最终一致性脆弱。
- RuleSchemaRegistry：每测试 new 实例，避免 overrides 无 clear API 导致顺序依赖；输入使用不可变 `SchemaField`。
- Mockito：无 static 全局 mock；并发 gate mock 只代理两个确定方法；落地时若升级 Mockito，先确认同一 mock 的并发调用支持仍成立。
- 现有 Spring 测试：已有类使用固定 H2 名和 context cache，新增 IT 不与其共享名称；不要并行运行历史 `@SpringBootTest`，除非 Maven 已配置 fork/库隔离。
- 错误断言：不匹配完整中文堆栈或 Hibernate vendor SQL；只断稳定业务 code/status、constraint 语义、tenant/result identity。

## 8. 运行与验证

```bash
# 默认回归；新 *IT 不进入 Surefire 默认命名模式
./mvnw -pl . -am test

# 一次运行全部新增纯单测（落地后）
./mvnw -pl . -Dtest='TenantContextTest,TenantIdentifierResolverTest,TenantIngressFilterBoundaryTest,AudienceTenantResolverBoundaryTest,JwtTenantFilterBoundaryTest,MultiTenancyConfigTest,JwksWarmupRunnerTest,RuleSchemaRegistryTenantTest,ActivityMarketingControllerFieldDictTest,ActivityMarketingTenantSchemaTest,ActivityRuleRuntimeTenantCacheTest,ActivityManageEntityConstraintTest,ActivityMarketingIdempotencyTest,ActivityMarketingControllerIdempotencyTest' test

# 聚焦单类必须带 -pl
./mvnw -pl . -Dtest=ActivityMarketingTenantSchemaTest test

# 门控 IT
RUN_ACTIVITY_SECURITY_IT=true ./mvnw -pl . -Dtest=ActivitySecurityChainsIT test
RUN_ACTIVITY_IDEMPOTENCY_IT=true ./mvnw -pl . -Dtest=ActivityIdempotencyConcurrencyIT test
```

本批草案不使用内部 JWT，`INTERNAL_JWT_SECRET` 无需设置。若未来复用 platform 内部 JWT 路径，运行前设置 ≥32 字节。

验证顺序：先纯单测；再安全 IT；最后并发 IT 单独运行 3 次检查稳定性。禁止用重复运行掩盖偶发失败；任何超时都按资源泄漏/锁等待调查。

## 9. 待验证与暂不覆盖

- 待验证：Casdoor 实际 `groups` claim 是 collection 还是其他形态；在真 IdP 样本确认前，不把字符串 groups 转换写成契约。
- 待验证：JWKS warmup 的失败策略是阻止启动、影响 readiness，还是只告警（ISSUE-08）。
- 待验证：幂等键在活动版本编辑后的业务有效期，以及同 key 不同 payload 应返回首结果还是 409（ISSUE-07）。
- 待验证：H2 对并发 unique loser 的异常 constraint name 与 MySQL 驱动差异；业务断言只依赖服务稳定消息，生产还需 MySQL profile 的受控验证。
- 暂不新增真实 Casdoor IT：需要 secret/登录/联网，超出本次默认纯单测范围；现有本地 RSA 测试与本计划本地 JWKS IT 覆盖应用侧逻辑。
- 暂不重复 10 entity 的每表 CRUD：现有架构守卫 + Hibernate 真实跨租户主链路已覆盖机制；新增价值应放在漏掉的入口、事务和状态交互。
- 已用 Java 21 `JavaCompiler` 对 16 个 Java 代码块逐块做内存编译（classpath 取自仓库现有 Surefire 报告，class 输出写入内存），结果 `total=16, failed=0`；仍需落地 Agent 在真实目标路径运行 Maven，验证测试发现、运行期装配和现行源码重新编译。本次未运行 Maven，以避免在蓝图目录之外改写 `target/`。

## 10. test-judge 最终复核与验收清单

已按资深测试架构视角二次复核并修正：

- [x] 所有测试类与生产类同包，类名为 `*Test`；真实链/库类为 `*IT`。
- [x] 无 `@Mock`、`@MockBean`、Mockito extension、`@WebMvcTest`、`@DataJpaTest`；controller 均 direct new。
- [x] 默认单测不使用 `@SpringBootTest`/MockMvc；两个不可替代 IT 手动启动且双重门控。
- [x] 断言使用 AssertJ；异常捕获使用 JUnit 5 `assertThrows`。
- [x] 所有涉及 TenantContext 的草案都有 `@BeforeEach` 与 `@AfterEach clear()`；并记录实际 API 与铁律示例的冲突。
- [x] H2 IT 使用 `DriverManagerDataSource`、唯一库名、不手写 DDL；表由真实 Hibernate 实体创建，因为仓库不存在 `Jdbc*Store`。
- [x] 无 sleep、固定端口、公共 executor、测试顺序依赖或外部网络。
- [x] 强断言覆盖 status + chain、tenant + cleanup、对象 identity + size、成功 + 零写入、竞态 winner + retry + 跨租户。
- [x] ISSUE-01~09 已单列；会锁定错误行为的草案均 `@Disabled(TODO)`。
- [x] 16 个完整 Java 草案经 Java 21 内存编译零错误。
- [ ] 落地后新增纯单测全绿。
- [ ] 两个门控 IT 在显式环境变量下全绿且无资源泄漏。
- [ ] `02-coverage-matrix.md` 的 P0/P1 缺口闭合；待验证项有明确 owner/决策。
- [ ] 疑似 bug 修复后逐个启用 TODO，用预期安全行为验收，不修改断言迎合实现。
- [ ] 全量 `./mvnw -pl . -am test` 继续保持基线全绿。
