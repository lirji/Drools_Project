package com.lrj.drools.activity.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

/** console 写平面的发布代际原子写接缝。 */
@Repository
public class GenerationWriteStore {

    private final JdbcTemplate jdbcTemplate;

    public GenerationWriteStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** MySQL 与测试环境的 H2 MySQL 模式都支持该原子 upsert。 */
    public long upsertAndIncrement(String tenantId, String bizLine, Instant updatedStime) {
        jdbcTemplate.update("""
                        INSERT INTO activity_generation (tenant_id, biz_line, generation, updated_stime)
                        VALUES (?, ?, 1, ?)
                        ON DUPLICATE KEY UPDATE
                            generation = generation + 1,
                            updated_stime = ?
                        """,
                tenantId, bizLine, Timestamp.from(updatedStime), Timestamp.from(updatedStime));
        Long generation = jdbcTemplate.queryForObject("""
                        SELECT generation
                          FROM activity_generation
                         WHERE tenant_id = ? AND biz_line = ?
                        """,
                Long.class, tenantId, bizLine);
        if (generation == null) {
            throw new IllegalStateException("发布代际写入后未找到记录");
        }
        return generation;
    }
}
