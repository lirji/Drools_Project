package com.lrj.drools.activity;

import com.lrj.drools.activity.tenant.TenantProperties;
import com.lrj.drools.activity.tenant.TenantQuotaService;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-13 每租户限流：令牌桶正确性 + 按租户隔离 + 随时间补充 + 未启用放行。注入纳秒时钟确定性验证。
 */
class TenantQuotaServiceTest {

    private final AtomicLong nanos = new AtomicLong(1_000_000_000L);

    private TenantQuotaService svc(boolean enabled, double qps, double burst) {
        TenantProperties props = new TenantProperties();
        props.getQuota().setEnabled(enabled);
        props.getQuota().setPerTenantQps(qps);
        props.getQuota().setBurst(burst);
        return new TenantQuotaService(props, nanos::get);
    }

    @Test
    void burstThenDeny_perTenant() {
        TenantQuotaService quota = svc(true, 2, 2); // 桶容量 2
        assertTrue(quota.tryAcquire("acme"), "第 1 个令牌放行");
        assertTrue(quota.tryAcquire("acme"), "第 2 个令牌放行（用满突发）");
        assertFalse(quota.tryAcquire("acme"), "桶空 → 第 3 个拒（429）");
        // 另一租户独立满桶，不受 acme 影响
        assertTrue(quota.tryAcquire("beta"), "beta 独立桶，不受 acme 限流影响");
        assertTrue(quota.tryAcquire("beta"));
        assertFalse(quota.tryAcquire("beta"));
    }

    @Test
    void refillsOverTime() {
        TenantQuotaService quota = svc(true, 2, 2);
        assertTrue(quota.tryAcquire("acme"));
        assertTrue(quota.tryAcquire("acme"));
        assertFalse(quota.tryAcquire("acme"), "桶空");
        // 过 1 秒 → 补 2 个令牌（2 QPS）
        nanos.addAndGet(1_000_000_000L);
        assertTrue(quota.tryAcquire("acme"), "1s 后补充，可再取");
        assertTrue(quota.tryAcquire("acme"));
        assertFalse(quota.tryAcquire("acme"), "补充上限=容量，第 3 个仍拒");
    }

    @Test
    void disabled_alwaysAllows() {
        TenantQuotaService quota = svc(false, 1, 1);
        for (int i = 0; i < 100; i++) {
            assertTrue(quota.tryAcquire("acme"), "未启用限流一律放行");
        }
    }

    @Test
    void nullTenant_allows() {
        TenantQuotaService quota = svc(true, 1, 1);
        assertTrue(quota.tryAcquire(null), "租户为空 fail-open 放行（不该发生在受限端点，防御式）");
        assertTrue(quota.tryAcquire(" "));
    }
}
