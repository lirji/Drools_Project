package com.lrj.drools.activity.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * 发放传播中继唯一的跨租户发现入口（与 {@link ActivityLifecycleTenantScanner} 同款例外）。
 *
 * <p>普通 JPA 查询必须经过 Hibernate {@code @TenantId}，架构测试也禁止仓库写原生 SQL。但后台中继线程
 * 起步时还没有租户上下文，必须先发现「哪些租户有待投递的 outbox 条目」。因此这里只用 JDBC 读取
 * distinct tenant_id，不返回任何事件业务字段；后续拉取、投递、置态全部在逐租户 {@code TenantContext}
 * 内回到 JPA 隔离机制。把例外收在独立组件，避免普通仓库获得绕过隔离的能力。
 */
@Component
public class GrantOutboxTenantScanner {

    private final JdbcTemplate jdbc;

    public GrantOutboxTenantScanner(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 有 PENDING 首投或可补投（FAILED 且 {@code attempt < maxAttempt} 且已过退避 {@code next_attempt_at <= now}）
     * 条目的租户 id 列表。DEAD（触顶死信）不纳入——避免死信条目让租户被空扫（谓词须与 findRetryable 一致）。
     */
    public List<String> findTenantsWithRetryable(int maxAttempt, Instant now) {
        return jdbc.queryForList("""
                select distinct tenant_id
                  from activity_grant_outbox
                 where tenant_id is not null
                   and (status = 'PENDING'
                        or (status = 'FAILED' and attempt < ?
                            and (next_attempt_at is null or next_attempt_at <= ?)))
                 order by tenant_id
                """, String.class, maxAttempt, Timestamp.from(now));
    }

    /** 有 DEAD 死信条目的租户 id 列表——供 {@code GrantOutboxRelay.redriveDeadLetters} 跨租户复活（KI-9）。 */
    public List<String> findTenantsWithDead() {
        return jdbc.queryForList("""
                select distinct tenant_id from activity_grant_outbox
                 where tenant_id is not null and status = 'DEAD'
                 order by tenant_id
                """, String.class);
    }
}
