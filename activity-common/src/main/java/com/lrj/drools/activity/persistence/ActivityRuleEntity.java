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

import java.math.BigDecimal;

/**
 * 红包规则详情层。收敛自来源 {@code ActivityDynamicRules}。
 *
 * {@code redPackageRangeAmount} 既存随机红包区间，也存 LADDER 阶梯分档 JSON（长文本，用 LONGVARCHAR）。
 */
@Entity
@Comment("活动权益规则配置表")
@Table(name = "activity_rule", indexes = {
        @Index(name = "idx_ar_aid_ver_del", columnList = "tenant_id,activity_id,version,is_del")
})
public class ActivityRuleEntity extends SoftDeletableTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "activity_id", length = 64, nullable = false)
    private String activityId;

    @Column(name = "activity_type", nullable = false)
    private Integer activityType;

    /** 现金红包类型 1-固定 2-随机（见 DistributionMode）。 */
    @Column(name = "red_package_take_type")
    private Integer redPackageTakeType;

    @Column(name = "red_package_amount", precision = 12, scale = 2)
    private BigDecimal redPackageAmount;

    /** 权益形态判别位：元 = 金额型，折 = 折扣型（此时 redPackageAmount 是折数）。见 BenefitForm */
    @Column(name = "red_package_amount_unit", length = 8)
    private String redPackageAmountUnit;

    /**
     * 折扣型的封顶减免额（元）。null = 不封顶。
     *
     * <p>金额型用不到它；折扣型**必须**有——「打 8 折」在一笔 10 万的订单上就是 2 万，
     * 没有封顶等于给出一个无上限的支出口子。写平面对折扣型强制要求非空。
     */
    @Column(name = "red_package_max_discount", precision = 12, scale = 2)
    private BigDecimal redPackageMaxDiscount;

    /** 随机红包范围值 / LADDER 分档 JSON。 */
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "red_package_range_amount")
    private String redPackageRangeAmount;

    @Column(name = "version", nullable = false)
    private Integer version;

    public ActivityRuleEntity() {}

    public Long getId() { return id; }

    public String getActivityId() { return activityId; }
    public void setActivityId(String activityId) { this.activityId = activityId; }

    public Integer getActivityType() { return activityType; }
    public void setActivityType(Integer activityType) { this.activityType = activityType; }

    public Integer getRedPackageTakeType() { return redPackageTakeType; }
    public void setRedPackageTakeType(Integer redPackageTakeType) { this.redPackageTakeType = redPackageTakeType; }

    public BigDecimal getRedPackageAmount() { return redPackageAmount; }
    public void setRedPackageAmount(BigDecimal redPackageAmount) { this.redPackageAmount = redPackageAmount; }

    public String getRedPackageAmountUnit() { return redPackageAmountUnit; }
    public void setRedPackageAmountUnit(String redPackageAmountUnit) { this.redPackageAmountUnit = redPackageAmountUnit; }

    public BigDecimal getRedPackageMaxDiscount() { return redPackageMaxDiscount; }
    public void setRedPackageMaxDiscount(BigDecimal redPackageMaxDiscount) { this.redPackageMaxDiscount = redPackageMaxDiscount; }

    public String getRedPackageRangeAmount() { return redPackageRangeAmount; }
    public void setRedPackageRangeAmount(String redPackageRangeAmount) { this.redPackageRangeAmount = redPackageRangeAmount; }

    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
}
