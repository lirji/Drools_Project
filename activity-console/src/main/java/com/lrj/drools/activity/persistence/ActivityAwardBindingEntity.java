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
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/** 活动不可变版本到企业权益 SKU 的绑定；只存在于 console 写平面。 */
@Entity
@Comment("活动版本与企业权益SKU绑定")
@Table(name = "activity_award_binding",
        uniqueConstraints = @UniqueConstraint(name = "uk_award_binding_source",
                columnNames = {"tenant_id", "activity_id", "version", "source_kind", "source_ref", "benefit_sku_id"}),
        indexes = @Index(name = "idx_award_binding_activity_version",
                columnList = "tenant_id,activity_id,version"))
public class ActivityAwardBindingEntity extends TenantScopedEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "activity_id", length = 64, nullable = false)
    private String activityId;

    @Column(name = "version", nullable = false)
    private Integer version;

    @Column(name = "source_kind", length = 32, nullable = false)
    private String sourceKind;

    @Column(name = "source_ref", length = 128, nullable = false)
    private String sourceRef;

    @Column(name = "benefit_sku_id", length = 128, nullable = false)
    private String benefitSkuId;

    @Column(name = "delivery_mode", length = 16, nullable = false)
    private String deliveryMode;

    @Column(name = "amount_mode", length = 16, nullable = false)
    private String amountMode;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "item_template_json")
    private String itemTemplateJson;

    protected ActivityAwardBindingEntity() {}

    public ActivityAwardBindingEntity(String activityId, Integer version, String sourceKind, String sourceRef,
                                      String benefitSkuId, String deliveryMode, String amountMode,
                                      String itemTemplateJson, Instant now) {
        this.activityId = activityId;
        this.version = version;
        this.sourceKind = sourceKind;
        this.sourceRef = sourceRef;
        this.benefitSkuId = benefitSkuId;
        this.deliveryMode = deliveryMode;
        this.amountMode = amountMode;
        this.itemTemplateJson = itemTemplateJson;
        setCreatedStime(now);
        setModifiedStime(now);
    }

    public Long getId() { return id; }
    public String getActivityId() { return activityId; }
    public Integer getVersion() { return version; }
    public String getSourceKind() { return sourceKind; }
    public String getSourceRef() { return sourceRef; }
    public String getBenefitSkuId() { return benefitSkuId; }
    public String getDeliveryMode() { return deliveryMode; }
    public String getAmountMode() { return amountMode; }
    public String getItemTemplateJson() { return itemTemplateJson; }
}
