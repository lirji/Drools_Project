package com.lrj.drools.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * Step 10: KieSession 序列化快照。
 *
 * 一行 = 一个会话的 working memory + agenda state, 用 sessionId (业务自带 id, 如 "alice")
 * 做主键。每次 fireAllRules 后整个 session 重新 marshall 覆盖写入, 简单粗暴但教学清晰。
 *
 * 跟 Drools 官方 drools-persistence-jpa 的区别:
 *   - 那个用 SessionInfo / WorkItemInfo 等多张表 + JTA 事务, 自动在每次 fire 后落地;
 *     代价是必须配 Bitronix/Atomikos JTA, Spring Boot 3 集成繁琐。
 *   - 本表只用 Spring Data 一个 entity, 自己手动 marshall, 没有 JTA 依赖; 缺点是
 *     缺少"事务边界内自动持久化"的便利, 但对单 endpoint 的场景够用。
 *
 * data 存 byte[]; 小会话几 KB, 大会话可能 MB 级。用 @JdbcTypeCode(LONGVARBINARY) 而不是
 * @Lob: MySQL 下 @Lob byte[] 默认建成 64KB 的 blob, 大会话会被截断; LONGVARBINARY 映射成
 * MySQL longblob / H2 大对象, 两个 profile 都够装。
 */
@Entity
@Comment("Drools 会话状态快照表")
@Table(name = "session_snapshot")
public class SessionSnapshot {

    @Id
    @Column(name = "session_id", length = 64, nullable = false)
    private String sessionId;

    @JdbcTypeCode(SqlTypes.LONGVARBINARY)
    @Column(name = "data", nullable = false)
    private byte[] data;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SessionSnapshot() {} // JPA 必需的无参构造

    public SessionSnapshot(String sessionId, byte[] data, Instant updatedAt) {
        this.sessionId = sessionId;
        this.data = data;
        this.updatedAt = updatedAt;
    }

    public String getSessionId() { return sessionId; }
    public byte[] getData() { return data; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setData(byte[] data) { this.data = data; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
