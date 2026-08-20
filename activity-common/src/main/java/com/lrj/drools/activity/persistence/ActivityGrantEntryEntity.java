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

import java.time.Instant;

/**
 * <b>发放分录台账</b>——不可变的红蓝字分录，一次确认发放/一次退款冲正各追加一条。
 *
 * <p><b>为什么与 {@link ActivityGrantEntity} 分家</b>：发放主记录（{@code activity_grant}）是
 * <b>状态机</b>（HELD→CONFIRMED→RELEASED），一行会被反复改写；分录台账是<b>账</b>，
 * 与会计红蓝字、recon ADR-7「账务事实不删不改」同构——只追加、绝不删改。
 * 若把符号金额写在可变的 grant 行上，一条 {@code claim→confirm(+X)→release(-X)} 会把 {@code +X}
 * 覆写成 {@code -X}，原 ISSUE 分录消失，退款场景下与账务/渠道的追加式台账<b>逐条勾兑不上</b>。
 * 分录台账让「发放 +X」与「退款 -X」两条记录并存，recon 按 {@code grant_no} 分组汇总为 0，对得平。
 *
 * <p><b>幂等硬保证</b>：{@code uk_entry_grant_type(grant_no, entry_type)} —— 一次发放最多一条 ISSUE
 * + 一条 REVERSAL。分录防重不靠应用层「先查后插」，靠这条唯一约束。
 *
 * <p><b>它只在写平面被追加</b>（console）。confirm 追 ISSUE、release(CONFIRMED) 追 REVERSAL，
 * 均与状态机迁移<b>同事务</b>；decision 连只读账号，物理上写不了。
 *
 * <p><b>对账口径</b>：{@code recon_src_marketing} 视图从本表出（单租户，不切 tenant），
 * 列别名对齐 recon 营销三方 SEG1 营销侧描述符（{@code grant_no→issue_id}、{@code order_id→order_no}、
 * {@code currency→ccy}、{@code amount_minor}、{@code entry_type}、{@code biz_time}）。
 * HELD 占用从不产生分录，天然不进对账。
 */
@Entity
@Comment("活动权益发放分录台账（不可变红蓝字，ISSUE/REVERSAL）")
@Table(name = "activity_grant_entry",
        uniqueConstraints = {
                // 分录幂等键：一次发放（grant_no）最多一条 ISSUE + 一条 REVERSAL。
                // **这是分录防重的唯一硬保证**——confirm/release 的重复回调靠它兜底，不靠应用层判重。
                @UniqueConstraint(name = "uk_entry_grant_type",
                        columnNames = {"grant_no", "entry_type"})
        },
        indexes = {
                // 对账 / 客服按发放号取该笔的全部分录（ISSUE+REVERSAL）。
                @Index(name = "idx_entry_grant_no", columnList = "grant_no"),
                // 按活动做发放效果分析 / 对账切片。
                @Index(name = "idx_entry_tenant_activity", columnList = "tenant_id,activity_id")
        })
public class ActivityGrantEntryEntity extends TenantScopedEntity {

    /** 确认发放（支付成功）：{@code amount_minor = +amount×100}。 */
    public static final String ISSUE = "ISSUE";
    /** 退款冲正（对已 CONFIRMED 的发放）：{@code amount_minor = −(对应 ISSUE 分额)}。 */
    public static final String REVERSAL = "REVERSAL";
    /**
     * 主动退款（<b>保留字</b>）。尊重需求列出的 {@code entry_type} 字段域，但本迭代状态机只产出
     * ISSUE/REVERSAL；未来区分「主动退款 vs 系统冲正」时再接线，届时不改表结构。
     */
    public static final String REFUND = "REFUND";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 关联 {@link ActivityGrantEntity#getGrantNo()}——对账的 {@code issue_id}（match_key）。 */
    @Column(name = "grant_no", length = 64, nullable = false)
    private String grantNo;

    /** 冗余订单号——对账的 {@code order_no}（group_key）；同一单多笔发放按它归组。 */
    @Column(name = "order_id", length = 64, nullable = false)
    private String orderId;

    @Column(name = "activity_id", length = 64, nullable = false)
    private String activityId;

    /** 分录类型：{@link #ISSUE} / {@link #REVERSAL}（{@link #REFUND} 保留）。 */
    @Column(name = "entry_type", length = 16, nullable = false)
    private String entryType;

    /**
     * 带符号最小单位（分）。ISSUE 为正（{@code +amount×100}）、REVERSAL 为负（取对应 ISSUE 分额之负，
     * 不重算——杜绝漂移、天然避开 amount 为 null）。recon 读的就是这一列（红蓝字口径）。
     */
    @Column(name = "amount_minor", nullable = false)
    private Long amountMinor;

    /** 币种，继承自 {@link ActivityGrantEntity#getCurrency()}（对账按币种分桶，兜底 CNY）。 */
    @Column(name = "currency", length = 8, nullable = false)
    private String currency;

    /** 分录业务时间（confirm / release 时刻）。视图同时投影为 {@code biz_time} 与 {@code posting_time}。 */
    @Column(name = "biz_time", nullable = false)
    private Instant bizTime;

    public ActivityGrantEntryEntity() {}

    public ActivityGrantEntryEntity(String grantNo, String orderId, String activityId, String entryType,
                                    Long amountMinor, String currency, Instant bizTime, Instant now) {
        this.grantNo = grantNo;
        this.orderId = orderId;
        this.activityId = activityId;
        this.entryType = entryType;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.bizTime = bizTime;
        setCreatedStime(now);
        setModifiedStime(now);
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getGrantNo() { return grantNo; }
    public void setGrantNo(String grantNo) { this.grantNo = grantNo; }

    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }

    public String getActivityId() { return activityId; }
    public void setActivityId(String activityId) { this.activityId = activityId; }

    public String getEntryType() { return entryType; }
    public void setEntryType(String entryType) { this.entryType = entryType; }

    public Long getAmountMinor() { return amountMinor; }
    public void setAmountMinor(Long amountMinor) { this.amountMinor = amountMinor; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public Instant getBizTime() { return bizTime; }
    public void setBizTime(Instant bizTime) { this.bizTime = bizTime; }
}
