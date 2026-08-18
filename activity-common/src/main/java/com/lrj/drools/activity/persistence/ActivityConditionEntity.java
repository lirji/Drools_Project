package com.lrj.drools.activity.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 活动级资格条件层。收敛自来源 {@code ActivityRuleExpression} + 可视化条件树。
 *
 * 存两份：{@code conditionTreeJson}（前端条件树原文，回显/再编辑用）+
 * {@code generatedDrl}（翻译出的受控 Drools 约束片段，运行时编译用）。
 * 两者都可能较长，用 LONGVARCHAR。
 */
@Entity
@Comment("活动资格条件表")
@Table(name = "activity_condition", indexes = {
        @Index(name = "idx_ac_aid_ver_scene", columnList = "tenant_id,activity_id,version,scene,enabled")
})
public class ActivityConditionEntity extends SoftDeletableTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "activity_id", length = 64, nullable = false)
    private String activityId;

    @Column(name = "version", nullable = false)
    private Integer version;

    /** 规则场景 code：eligibility / discount / ladder / gift（见 RuleScene）。 */
    @Column(name = "scene", length = 32, nullable = false)
    private String scene;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "condition_tree_json")
    private String conditionTreeJson;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "generated_drl")
    private String generatedDrl;

    @Column(name = "enabled", nullable = false)
    private Integer enabled;

    public ActivityConditionEntity() {}

    public Long getId() { return id; }

    public String getActivityId() { return activityId; }
    public void setActivityId(String activityId) { this.activityId = activityId; }

    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }

    public String getScene() { return scene; }
    public void setScene(String scene) { this.scene = scene; }

    public String getConditionTreeJson() { return conditionTreeJson; }
    public void setConditionTreeJson(String conditionTreeJson) { this.conditionTreeJson = conditionTreeJson; }

    public String getGeneratedDrl() { return generatedDrl; }
    public void setGeneratedDrl(String generatedDrl) { this.generatedDrl = generatedDrl; }

    public Integer getEnabled() { return enabled; }
    public void setEnabled(Integer enabled) { this.enabled = enabled; }
}
