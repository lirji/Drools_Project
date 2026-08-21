package com.lrj.drools.activity.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@Component
public class AwardIntentOutboxTenantScanner {
    private final JdbcTemplate jdbc;
    public AwardIntentOutboxTenantScanner(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<String> findTenants(Instant now) {
        return jdbc.queryForList("""
                SELECT DISTINCT tenant_id FROM activity_award_intent_outbox
                WHERE status='PENDING'
                   OR (status='FAILED' AND (next_attempt_at IS NULL OR next_attempt_at<=?))
                   OR (status='SENDING' AND (lease_until IS NULL OR lease_until<=?))
                ORDER BY tenant_id
                """, String.class, Timestamp.from(now), Timestamp.from(now));
    }
}
