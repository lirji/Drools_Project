package com.lrj.drools.activity.tenant;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.function.LongSupplier;

/**
 * P1-13 每租户限流（进程内 token bucket）。
 *
 * <p>每租户一个令牌桶：稳态 {@code perTenantQps} 补充、桶容量 {@code burst}。{@link #tryAcquire} 取一个令牌，
 * 桶空即拒（调用方转 429）。桶存 Caffeine 有界缓存（{@code maxTenants} + {@code expireAfterAccess}），防租户维度无界增长、
 * 空闲租户桶自动淘汰（复用 P0-5「有界优先」思路）。
 *
 * <p><b>边界（诚实）</b>：仅本实例限流——N 实例总配额 = N×单实例。生产无状态多实例须换 Redis token bucket / 网关限流，
 * 并计入延迟预算 + 定义 Redis 宕机开/闭（见 {@link TenantProperties.Quota} javadoc 与 Track B 收尾 doc）。
 */
@Service
public class TenantQuotaService {

    private final boolean enabled;
    private final double ratePerSec;
    private final double capacity;
    private final LongSupplier nanoClock;
    private final Cache<String, TokenBucket> buckets;

    @Autowired
    public TenantQuotaService(TenantProperties props) {
        this(props, System::nanoTime);
    }

    /** 可注入纳秒时钟（测试确定性验证补充）。 */
    public TenantQuotaService(TenantProperties props, LongSupplier nanoClock) {
        TenantProperties.Quota q = props.getQuota();
        this.enabled = q.isEnabled();
        this.ratePerSec = q.getPerTenantQps();
        this.capacity = q.getBurst() > 0 ? q.getBurst() : q.getPerTenantQps();
        this.nanoClock = nanoClock;
        this.buckets = Caffeine.newBuilder()
                .maximumSize(q.getMaxTenants())
                .expireAfterAccess(java.time.Duration.ofMinutes(10))
                .build();
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 取一个令牌。未启用、或租户为空（不该发生在受限端点）时一律放行（fail-open）。
     * @return true=放行；false=超配额（调用方转 429）
     */
    public boolean tryAcquire(String tenant) {
        if (!enabled || tenant == null || tenant.isBlank()) {
            return true;
        }
        TokenBucket bucket = buckets.get(tenant, t -> new TokenBucket(capacity, capacity));
        return bucket.tryAcquire(ratePerSec, capacity, nanoClock.getAsLong());
    }

    /** 时间驱动的令牌桶（每租户一个，方法级 synchronized 保证并发安全）。 */
    private static final class TokenBucket {
        private double tokens;
        private long lastNanos;

        TokenBucket(double initialTokens, double lastNanosSeedIgnored) {
            this.tokens = initialTokens;
            this.lastNanos = Long.MIN_VALUE; // 首次 tryAcquire 用传入 now 初始化
        }

        synchronized boolean tryAcquire(double ratePerSec, double capacity, long now) {
            if (lastNanos == Long.MIN_VALUE) {
                lastNanos = now;
            }
            double elapsedSec = Math.max(0, (now - lastNanos) / 1_000_000_000.0);
            tokens = Math.min(capacity, tokens + elapsedSec * ratePerSec);
            lastNanos = now;
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }
    }
}
