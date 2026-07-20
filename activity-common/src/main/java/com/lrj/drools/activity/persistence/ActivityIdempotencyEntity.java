package com.lrj.drools.activity.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.TenantId;

import java.time.Instant;

/**
 * ISSUE-07 · 独立幂等表：把「requestId → 首次处理结果」与**版本化业务行**解耦。
 *
 * <p><b>为什么单独一张表</b>：原幂等只挂在 {@code activity_manage} 的 {@code (tenant_id, request_id)} 唯一约束上，
 * 而编辑走「旧版本逻辑删、新版本 version+1」，新版本行**不能带 requestId**（否则撞 v1 的唯一约束）——于是**编辑重放不幂等**，
 * 重复提交同一 requestId 的编辑会不断 version+1。本表让 create 与 edit **统一**在此登记，重放（顺序重试）返回首次结果。
 *
 * <p><b>并发语义</b>：{@code (tenant_id, request_id)} 唯一约束 + 记录发生在 create 的同一 {@code @Transactional} 内 →
 * 并发相同 requestId 时后到者 flush 撞唯一约束 → 整事务回滚（含刚建的业务行，**无孤儿**）→ 转 409（at-most-once，同 ISSUE-06）。
 * 顺序重放（客户端超时重试的常见场景）由顶部查表命中短路。租户维度由 {@link TenantId} 机制自动隔离。
 */
@Entity
@Table(name = "activity_idempotency",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_idem_tenant_request", columnNames = {"tenant_id", "request_id"})
        })
public class ActivityIdempotencyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 租户隔离列（P0-4）：@TenantId 自动追加谓词 + insert 自动落值。 */
    @TenantId
    @Column(name = "tenant_id", length = 64)
    private String tenantId;

    @Column(name = "request_id", length = 64, nullable = false)
    private String requestId;

    /** 首次处理产出的活动 id（create 新建 / edit 命中版本）。 */
    @Column(name = "activity_id", length = 64, nullable = false)
    private String activityId;

    @Column(name = "version", nullable = false)
    private Integer version;

    @Column(name = "activity_status", nullable = false)
    private Integer activityStatus;

    @Column(name = "created_stime", nullable = false)
    private Instant createdStime;

    public ActivityIdempotencyEntity() {}

    public ActivityIdempotencyEntity(String requestId, String activityId, Integer version,
                                     Integer activityStatus, Instant createdStime) {
        this.requestId = requestId;
        this.activityId = activityId;
        this.version = version;
        this.activityStatus = activityStatus;
        this.createdStime = createdStime;
    }

    public Long getId() { return id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }

    public String getActivityId() { return activityId; }
    public void setActivityId(String activityId) { this.activityId = activityId; }

    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }

    public Integer getActivityStatus() { return activityStatus; }
    public void setActivityStatus(Integer activityStatus) { this.activityStatus = activityStatus; }

    public Instant getCreatedStime() { return createdStime; }
    public void setCreatedStime(Instant createdStime) { this.createdStime = createdStime; }
}
