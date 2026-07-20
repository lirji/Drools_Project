package com.lrj.drools.activity;

import com.lrj.drools.activity.tenant.TenantContext;
import com.lrj.drools.activity.tenant.TenantIdentifierResolver;
import com.lrj.drools.activity.tenant.TenantIds;
import com.lrj.drools.activity.tenant.TenantProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Hibernate 租户解析器（codex-test ISSUE-01）：**永不返回 null**——即使 dev-default 配置非法/null，
 * 也回落哨兵而非 null（否则 Hibernate isRoot 把 null 当 root 看所有租户）。
 */
class TenantIdentifierResolverTest {

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private TenantIdentifierResolver resolver(boolean devEnabled, String devDefault) {
        TenantProperties p = new TenantProperties();
        p.setDevDefaultEnabled(devEnabled);
        p.setDevDefault(devDefault);
        return new TenantIdentifierResolver(p);
    }

    @Test
    void contextTenantWins() {
        TenantContext.set("acme");
        assertEquals("acme", resolver(false, "__dev__").resolveCurrentTenantIdentifier());
    }

    @Test
    void validDevDefaultUsed() {
        assertEquals("__dev__", resolver(true, "__dev__").resolveCurrentTenantIdentifier());
    }

    @Test
    void invalidDevDefault_sentinelNotNull() {
        assertEquals(TenantIds.NO_TENANT, resolver(true, "bad tenant").resolveCurrentTenantIdentifier(), "非法 dev-default → 哨兵");
        assertEquals(TenantIds.NO_TENANT, resolver(true, null).resolveCurrentTenantIdentifier(), "null dev-default → 哨兵(非 null)");
        assertEquals(TenantIds.NO_TENANT, resolver(true, TenantIds.NO_TENANT).resolveCurrentTenantIdentifier(), "保留值 dev-default → 哨兵");
    }

    @Test
    void noContextNoDevDefault_sentinel() {
        assertEquals(TenantIds.NO_TENANT, resolver(false, "__dev__").resolveCurrentTenantIdentifier());
    }

    @Test
    void neverReturnsNull() {
        assertNotNull(resolver(true, null).resolveCurrentTenantIdentifier(), "任何配置下都不得返回 null");
    }
}
