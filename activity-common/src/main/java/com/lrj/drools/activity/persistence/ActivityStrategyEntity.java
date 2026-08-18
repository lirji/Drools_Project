package com.lrj.drools.activity.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.Comment;

/**
 * 多活动合并策略。收敛自来源 {@code ActivityRuleStrategy}。
 *
 * 按 (bizLine, activityType, scene) 定位；{@code activityType} 为 null 表示该业务线兜底。
 * {@code strategy} 存 MAX/MUTEX/STACK/PRIORITY 字符串（见 StackStrategy）。
 * {@code version} 变化触发 KieBase 重建。
 */
@Entity
@Comment("多活动权益合并策略表")
@Table(name = "activity_strategy", indexes = {
        @Index(name = "idx_st_biz_type_scene", columnList = "tenant_id,biz_line,activity_type,scene,is_del")
})
public class ActivityStrategyEntity extends SoftDeletableTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

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

    public ActivityStrategyEntity() {}

    public Long getId() { return id; }

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
}
