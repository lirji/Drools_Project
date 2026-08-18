package com.lrj.drools.activity.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.TenantId;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * P1-9 · 活动决策 artifact（manifest 快照）：把某活动版本**冻结时**的决策规则 + **pin 的 schema 版本 / 引用字段**
 * 固化成不可变行。决策按 artifact 的 pin 跑，不受 live schema 漂移影响；schema 删字段/改类型 → 硬失效引用它的 artifact。
 *
 * <p><b>不可变</b>：每个 {@code (tenant, activity_id, version)} 一行，冻结后只允许改 {@code status}
 * （ACTIVE → NEEDS_REBUILD / RETIRED），规则/pin 不改。发布时按此 DRL **异步预热**（P0-5）。
 */
@Entity
@Comment("活动决策规则制品表")
@Table(name = "activity_artifact",
        indexes = {
                @Index(name = "idx_art_biz_status", columnList = "tenant_id,biz_line,status"),
                @Index(name = "idx_art_aid_ver", columnList = "tenant_id,activity_id,version")
        },
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_art_tenant_aid_ver", columnNames = {"tenant_id", "activity_id", "version"})
        })
public class ActivityArtifactEntity {

    public static final String ACTIVE = "ACTIVE";
    public static final String NEEDS_REBUILD = "NEEDS_REBUILD";
    public static final String RETIRED = "RETIRED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "tenant_id", length = 64)
    private String tenantId;

    @Column(name = "activity_id", length = 64, nullable = false)
    private String activityId;

    @Column(name = "version", nullable = false)
    private Integer version;

    @Column(name = "biz_line", length = 64)
    private String bizLine;

    /** 冻结时的 schema 版本（{@code RuleSchemaRegistry.schemaVersion}）。 */
    @Column(name = "schema_version", length = 32, nullable = false)
    private String schemaVersion;

    /** 冻结时引用的字段及类型，形如 {@code orderAmount:NUMBER,userTags:ARRAY}（硬失效判定用）。 */
    @Column(name = "referenced_fields", length = 2048)
    private String referencedFields;

    /** 冻结的资格 DRL（发布预热的编译输入）。无资格条件时可空。 */
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "elig_drl")
    private String eligDrl;

    @Column(name = "status", length = 24, nullable = false)
    private String status;

    @Column(name = "created_stime", nullable = false)
    private Instant createdStime;

    public ActivityArtifactEntity() {}

    public Long getId() { return id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getActivityId() { return activityId; }
    public void setActivityId(String activityId) { this.activityId = activityId; }

    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }

    public String getBizLine() { return bizLine; }
    public void setBizLine(String bizLine) { this.bizLine = bizLine; }

    public String getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(String schemaVersion) { this.schemaVersion = schemaVersion; }

    public String getReferencedFields() { return referencedFields; }
    public void setReferencedFields(String referencedFields) { this.referencedFields = referencedFields; }

    public String getEligDrl() { return eligDrl; }
    public void setEligDrl(String eligDrl) { this.eligDrl = eligDrl; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Instant getCreatedStime() { return createdStime; }
    public void setCreatedStime(Instant createdStime) { this.createdStime = createdStime; }
}
