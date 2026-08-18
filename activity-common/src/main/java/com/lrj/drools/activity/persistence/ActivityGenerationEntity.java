package com.lrj.drools.activity.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.Comment;

import java.time.Instant;

/**
 * M1.4 · 发布代际（generation）传播信号。每个 {@code (tenant, bizLine)} 一行，console 每次**发布(上线)**时 +1。
 *
 * <p><b>为什么这张表刻意<em>不</em>加 {@code @TenantId}</b>（与其它活动实体相反，这是本类命门）：
 * 它是**跨租户的发布传播信号**，供 decision 侧一个<b>无请求上下文</b>的后台轮询线程扫描。判别式多租户下，
 * 后台线程没有 {@link com.lrj.drools.activity.tenant.TenantContext}，{@link com.lrj.drools.activity.tenant.TenantIdentifierResolver}
 * 会回落 {@code NO_TENANT} 哨兵 → 若本表带 {@code @TenantId}，poller 的 {@code findAll()} 将被自动追加
 * {@code tenant_id = NO_TENANT} 谓词而**恒空**，什么都扫不到。故本表用**显式 {@code tenant_id} 列 + 不加 {@code @TenantId}}，
 * poller 得以看到所有租户的代际；真正读某租户的 ACTIVE artifact 预热时再用 {@code TenantContext.callWith(tenant, …)} 显式套上下文。
 *
 * <p>物理拆分（M2.2）后：console(写)是唯一 bump 者，decision(只读)只 poll 本表 + 读 artifact 预热——
 * 无分布式事务、artifact 不可变兜底，延迟期语义安全（decision 慢一个轮询周期看到新代际，期间用旧 warm 或首请求冷编译）。
 */
@Entity
@Comment("活动发布代际表")
@Table(name = "activity_generation",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_gen_tenant_biz", columnNames = {"tenant_id", "biz_line"})
        })
public class ActivityGenerationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 显式租户列（非 @TenantId，见类注释）。 */
    @Column(name = "tenant_id", length = 64, nullable = false)
    private String tenantId;

    @Column(name = "biz_line", length = 64, nullable = false)
    private String bizLine;

    /** 发布代际：首次发布=1，之后每次上线 +1。poller 比对本值与内存 lastSeen 决定是否预热。 */
    @Column(name = "generation", nullable = false)
    private long generation;

    @Column(name = "updated_stime", nullable = false)
    private Instant updatedStime;

    public ActivityGenerationEntity() {}

    public ActivityGenerationEntity(String tenantId, String bizLine, long generation, Instant updatedStime) {
        this.tenantId = tenantId;
        this.bizLine = bizLine;
        this.generation = generation;
        this.updatedStime = updatedStime;
    }

    public Long getId() { return id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getBizLine() { return bizLine; }
    public void setBizLine(String bizLine) { this.bizLine = bizLine; }

    public long getGeneration() { return generation; }
    public void setGeneration(long generation) { this.generation = generation; }

    public Instant getUpdatedStime() { return updatedStime; }
    public void setUpdatedStime(Instant updatedStime) { this.updatedStime = updatedStime; }
}
