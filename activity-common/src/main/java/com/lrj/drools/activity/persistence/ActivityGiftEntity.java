package com.lrj.drools.activity.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/**
 * 买赠赠品配置。收敛自来源 {@code BuyAndGetConfig.GiftConfig}（来源存 extraData JSON，
 * 这里直接落结构化行，前端报表更好填）。字段对齐 {@code GiftResult}。
 */
@Entity
@Table(name = "activity_gift", indexes = {
        @Index(name = "idx_gift_aid_ver_del", columnList = "tenant_id,activity_id,version,is_del")
})
public class ActivityGiftEntity extends SoftDeletableTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "activity_id", length = 64, nullable = false)
    private String activityId;

    @Column(name = "version", nullable = false)
    private Integer version;

    @Column(name = "batch_id", length = 64)
    private String batchId;

    @Column(name = "gift_name", length = 128)
    private String giftName;

    @Column(name = "gift_type", length = 32)
    private String giftType;

    @Column(name = "gift_num")
    private Integer giftNum;

    @Column(name = "absolute_amount", precision = 12, scale = 2)
    private BigDecimal absoluteAmount;

    @Column(name = "right_type", length = 32)
    private String rightType;

    public ActivityGiftEntity() {}

    public Long getId() { return id; }

    public String getActivityId() { return activityId; }
    public void setActivityId(String activityId) { this.activityId = activityId; }

    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }

    public String getBatchId() { return batchId; }
    public void setBatchId(String batchId) { this.batchId = batchId; }

    public String getGiftName() { return giftName; }
    public void setGiftName(String giftName) { this.giftName = giftName; }

    public String getGiftType() { return giftType; }
    public void setGiftType(String giftType) { this.giftType = giftType; }

    public Integer getGiftNum() { return giftNum; }
    public void setGiftNum(Integer giftNum) { this.giftNum = giftNum; }

    public BigDecimal getAbsoluteAmount() { return absoluteAmount; }
    public void setAbsoluteAmount(BigDecimal absoluteAmount) { this.absoluteAmount = absoluteAmount; }

    public String getRightType() { return rightType; }
    public void setRightType(String rightType) { this.rightType = rightType; }
}
