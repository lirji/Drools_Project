package com.lrj.drools.activity.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * 生命周期调度唯一的跨租户发现入口。
 *
 * <p>普通 JPA 查询必须经过 Hibernate {@code @TenantId}，架构测试也禁止仓库写原生 SQL。
 * 但后台线程起步时还没有租户上下文，必须先发现“哪些租户有到期动作”。因此这里只用 JDBC
 * 读取 distinct tenant_id，不返回任何活动业务字段；后续读取、加锁和修改全部在逐租户
 * {@code TenantContext} 内回到 JPA 隔离机制。把例外收在独立组件，避免普通仓库获得绕过隔离的能力。
 */
@Component
public class ActivityLifecycleTenantScanner {

    private final JdbcTemplate jdbc;

    public ActivityLifecycleTenantScanner(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<String> findDueTenantIds(Instant now) {
        return jdbc.queryForList("""
                select distinct tenant_id
                  from activity_manage
                 where is_del = 0
                   and tenant_id is not null
                   and ((activity_status = 3 and activity_start_time <= ?)
                     or (activity_status = 1 and activity_end_time < ?))
                 order by tenant_id
                """, String.class, Timestamp.from(now), Timestamp.from(now));
    }
}
