package com.lrj.drools.activity;

import com.lrj.drools.activity.tenant.AudienceTenantResolver;
import com.lrj.drools.activity.tenant.JwtTenantFilter;
import com.lrj.drools.activity.tenant.TenantContext;
import com.lrj.drools.activity.tenant.TenantContextFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * P0-3 决策平面来源过滤器（{@link JwtTenantFilter}）行为锁定（命脉修正版：租户从 aud 解析）：
 * aud→TenantContext、信封 X-Tenant-Id 只校验（≠租户→403）、请求结束清除上下文。
 */
class JwtTenantFilterTest {

    private final AudienceTenantResolver resolver =
            new AudienceTenantResolver(Map.of(), List.of("activity-{tenant}-cid"));
    private final JwtTenantFilter filter = new JwtTenantFilter(resolver);

    @AfterEach
    void clear() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    /** 用 aud=activity-<tenant>-cid 的 token 认证（owner 故意=admin，证明不靠 owner）。 */
    private void authenticateTenant(String tenant) {
        Jwt jwt = Jwt.withTokenValue("x").header("alg", "RS256")
                .claim("owner", "admin").audience(List.of("activity-" + tenant + "-cid")).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @Test
    void audBecomesTenant_andClearedAfter() throws Exception {
        authenticateTenant("acme");
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/activity-marketing/list");
        MockHttpServletResponse res = new MockHttpServletResponse();
        AtomicReference<String> seen = new AtomicReference<>();
        FilterChain chain = (r, s) -> seen.set(TenantContext.get());

        filter.doFilter(req, res, chain);

        assertEquals("acme", seen.get(), "租户应来自 token 的 aud（owner=admin 不参与）");
        assertNull(TenantContext.get(), "请求结束应清除上下文");
    }

    @Test
    void envelopeMatchingTenant_ok() throws Exception {
        authenticateTenant("acme");
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/activity-marketing/list");
        req.addHeader(TenantContextFilter.TENANT_HEADER, "acme");
        MockHttpServletResponse res = new MockHttpServletResponse();
        AtomicReference<String> seen = new AtomicReference<>();
        FilterChain chain = (r, s) -> seen.set(TenantContext.get());

        filter.doFilter(req, res, chain);

        assertEquals("acme", seen.get(), "信封与解析出的租户一致应放行");
    }

    @Test
    void envelopeMismatchingTenant_forbidden() throws Exception {
        authenticateTenant("acme");
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/activity-marketing/list");
        req.addHeader(TenantContextFilter.TENANT_HEADER, "beta"); // 冒充别租户
        MockHttpServletResponse res = new MockHttpServletResponse();
        AtomicBoolean chained = new AtomicBoolean(false);
        FilterChain chain = (r, s) -> chained.set(true);

        filter.doFilter(req, res, chain);

        assertEquals(HttpServletResponse.SC_FORBIDDEN, res.getStatus(), "信封 tenantId≠token 租户 应 403");
        assertFalse(chained.get(), "403 时不得放行到下游");
    }

    @Test
    void noAuthentication_forbidden() throws Exception {
        // 无认证/解析不出租户 → fail-closed 拒绝(非放行)，且不落租户上下文
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/activity-marketing/list");
        MockHttpServletResponse res = new MockHttpServletResponse();
        AtomicBoolean chained = new AtomicBoolean(false);
        FilterChain chain = (r, s) -> chained.set(true);

        filter.doFilter(req, res, chain);

        assertEquals(HttpServletResponse.SC_FORBIDDEN, res.getStatus(), "解析不出租户应 403 fail-closed");
        assertFalse(chained.get(), "拒绝时不得放行");
        assertNull(TenantContext.get(), "拒绝后不应残留上下文");
    }
}
