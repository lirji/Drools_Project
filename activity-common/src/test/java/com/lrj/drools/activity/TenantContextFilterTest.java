package com.lrj.drools.activity;

import com.lrj.drools.activity.tenant.TenantContext;
import com.lrj.drools.activity.tenant.TenantContextFilter;
import com.lrj.drools.activity.tenant.TenantProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-4 面向用户的 fail-closed 闸（{@link TenantContextFilter}）的行为锁定。
 *
 * 直接用真实的 Mock 请求/响应对象跑 {@code doFilterInternal}，断言：
 *   - 无 header + dev-default 关 → 403，chain 不放行；
 *   - 无 header + dev-default 开 → 放行且上下文=dev-default；
 *   - 合法 header → 放行且上下文=header 值，之后清除（防线程池串租户）；
 *   - 非法 header → 400，chain 不放行。
 */
class TenantContextFilterTest {

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private TenantProperties props(boolean devDefaultEnabled) {
        TenantProperties p = new TenantProperties();
        p.setDevDefaultEnabled(devDefaultEnabled);
        p.setDevDefault("__dev__");
        return p;
    }

    @Test
    void noHeaderDevDefaultDisabled_forbidden() throws Exception {
        TenantContextFilter filter = new TenantContextFilter(props(false));
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/activity-marketing/spu-discount");
        MockHttpServletResponse res = new MockHttpServletResponse();
        AtomicBoolean chained = new AtomicBoolean(false);
        FilterChain chain = (r, s) -> chained.set(true);

        filter.doFilter(req, res, chain);

        assertEquals(HttpServletResponse.SC_FORBIDDEN, res.getStatus(), "无租户 + dev-default 关 应 403");
        assertFalse(chained.get(), "403 时不得放行到下游");
        assertNull(TenantContext.get(), "拒绝后不应残留上下文");
    }

    @Test
    void noHeaderDevDefaultEnabled_fallsBackToDevDefault() throws Exception {
        TenantContextFilter filter = new TenantContextFilter(props(true));
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/activity-marketing/list");
        MockHttpServletResponse res = new MockHttpServletResponse();
        AtomicReference<String> seen = new AtomicReference<>();
        FilterChain chain = (r, s) -> seen.set(TenantContext.get());

        filter.doFilter(req, res, chain);

        assertEquals(200, res.getStatus());
        assertEquals("__dev__", seen.get(), "dev-default 开时无 header 应回落 dev-default");
        assertNull(TenantContext.get(), "请求结束应清除上下文");
    }

    @Test
    void validHeader_usedAndCleared() throws Exception {
        TenantContextFilter filter = new TenantContextFilter(props(false));
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/activity-marketing/list");
        req.addHeader(TenantContextFilter.TENANT_HEADER, "acme-01");
        MockHttpServletResponse res = new MockHttpServletResponse();
        AtomicReference<String> seen = new AtomicReference<>();
        FilterChain chain = (r, s) -> seen.set(TenantContext.get());

        filter.doFilter(req, res, chain);

        assertEquals("acme-01", seen.get(), "有合法 header 应用 header 值作为租户");
        assertNull(TenantContext.get(), "请求结束应清除上下文（防线程池串租户）");
    }

    @Test
    void illegalHeader_badRequest() throws Exception {
        TenantContextFilter filter = new TenantContextFilter(props(true));
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/activity-marketing/list");
        req.addHeader(TenantContextFilter.TENANT_HEADER, "a b;drop");
        MockHttpServletResponse res = new MockHttpServletResponse();
        AtomicBoolean chained = new AtomicBoolean(false);
        FilterChain chain = (r, s) -> chained.set(true);

        filter.doFilter(req, res, chain);

        assertEquals(HttpServletResponse.SC_BAD_REQUEST, res.getStatus(), "非法字符租户 id 应 400");
        assertFalse(chained.get(), "400 时不得放行到下游");
    }

    // ---- codex-test 补：早退清理 + dev-default 校验（ISSUE-02/01）----

    @Test
    void staleContextClearedOnReject() throws Exception {
        // 线程残留了上个请求的租户；本请求缺 header + dev-default 关 → 403，但残留必须被清掉
        TenantContext.set("stale");
        TenantContextFilter filter = new TenantContextFilter(props(false));
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/activity-marketing/list");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = (r, s) -> { };

        filter.doFilter(req, res, chain);

        assertEquals(HttpServletResponse.SC_FORBIDDEN, res.getStatus());
        assertNull(TenantContext.get(), "拒绝分支也应清掉线程残留租户");
    }

    @Test
    void invalidDevDefault_badRequest() throws Exception {
        // dev-default 配置成非法值：无 header 回落到它时应 400，而不是把非法租户放进上下文
        TenantProperties p = new TenantProperties();
        p.setDevDefaultEnabled(true);
        p.setDevDefault("bad tenant");
        TenantContextFilter filter = new TenantContextFilter(p);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/activity-marketing/list");
        MockHttpServletResponse res = new MockHttpServletResponse();
        AtomicBoolean chained = new AtomicBoolean(false);
        FilterChain chain = (r, s) -> chained.set(true);

        filter.doFilter(req, res, chain);

        assertEquals(HttpServletResponse.SC_BAD_REQUEST, res.getStatus(), "非法 dev-default 应 400");
        assertFalse(chained.get());
    }
}
