package com.lrj.drools.activity.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.TenantId;

import java.time.Instant;

/**
 * 多活动合并策略。收敛自来源 {@code ActivityRuleStrategy}。
 *
 * 按 (bizLine, activityType, scene) 定位；{@code activityType} 为 null 表示该业务线兜底。
 * {@code strategy} 存 MAX/MUTEX/STACK/PRIORITY 字符串（见 StackStrategy）。
 * {@code version} 变化触发 KieBase 重建。
 */
@Entity
@Table(name = "activity_strategy", indexes = {
        @Index(name = "idx_st_biz_type_scene", columnList = "tenant_id,biz_line,activity_type,scene,is_del")
})
public class ActivityStrategyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 租户隔离列（P0-4）：Hibernate @TenantId 自动为每条 SQL 追加 tenant_id 谓词、insert 自动落值，业务代码不手动 set。 */
    @TenantId
    @Column(name = "tenant_id", length = 64)
    private String tenantId;

    @Column(name = "biz_line", length = 64)
    private String bizLine;

    /** null = 该业务线兜底策略。 */
    @Column(name = "activity_type")
    private Integer activityType;

    @Column(name = "scene", length = 32, nullable = false)
    private String scene;

    @Column(name = "strategy", length = 16, nullable = false)
    private String strategy;

    @Column(name = "version", nullable = false)
    private Integer version;

    @Column(name = "is_del", nullable = false)
    private Integer isDel;

    @Column(name = "created_stime", nullable = false)
    private Instant createdStime;

    @Column(name = "modified_stime", nullable = false)
    private Instant modifiedStime;

    public ActivityStrategyEntity() {}

    public Long getId() { return id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getBizLine() { return bizLine; }
    public void setBizLine(String bizLine) { this.bizLine = bizLine; }

    public Integer getActivityType() { return activityType; }
    public void setActivityType(Integer activityType) { this.activityType = activityType; }

    public String getScene() { return scene; }
    public void setScene(String scene) { this.scene = scene; }

    public String getStrategy() { return strategy; }
    public void setStrategy(String strategy) { this.strategy = strategy; }

    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }

    public Integer getIsDel() { return isDel; }
    public void setIsDel(Integer isDel) { this.isDel = isDel; }

    public Instant getCreatedStime() { return createdStime; }
    public void setCreatedStime(Instant createdStime) { this.createdStime = createdStime; }

    public Instant getModifiedStime() { return modifiedStime; }
    public void setModifiedStime(Instant modifiedStime) { this.modifiedStime = modifiedStime; }
}
