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

/**
 * <b>发放传播 Outbox</b>（transactional outbox，推模式）——把带 {@code grant_no} 的发放/冲正事件与
 * 发放状态机、分录台账<b>同事务</b>落库，再由 {@code GrantOutboxRelay} 异步经可插拔 dispatcher 推给
 * 下游账务/渠道系统，使其能按 {@code grant_no}（= recon 的 {@code issue_id}）做三方 join。
 *
 * <p><b>为什么要 outbox（而不是发确认时直接 HTTP 推）</b>：drools 侧没有 MQ，若在 {@code confirmGrant}
 * 里直接调下游 HTTP，一旦 HTTP 失败或超时就会出现「钱发了、事件丢了」——下游对不上账。把事件与发放
 * <b>原子写进同一张库表</b>（同 @Transactional，要么全成要么全滚），再由中继 at-least-once 重投，
 * 就把「可靠地把事件发出去」和「可靠地记账」解耦成两段，各自可重试。这与 recon 的
 * {@code AlertOutbox + AlertRelayService} 同构。
 *
 * <p><b>幂等硬保证</b>：{@code uk_outbox_grant_event(grant_no, event_type)} —— 一次发放的
 * {@code GRANT_ISSUED}/{@code GRANT_REVERSED} 事件各至多一条。confirm/release 的幂等重放路径本就不重复
 * 写事件（由 CAS 保证首次只发生一次），这条唯一约束是最后兜底。<b>跨系统投递语义是至少一次</b>：
 * 下游必须按 {@code (grant_no, event_type)} 幂等消费（payload 内带该幂等键）。
 *
 * <p><b>它只在写平面被追加</b>（console）。confirm 追 {@code GRANT_ISSUED}、release(CONFIRMED) 追
 * {@code GRANT_REVERSED}，均与状态机迁移<b>同事务</b>；decision 连只读账号，物理上写不了。
 *
 * <p><b>新列全部追加在子类字段末尾</b>（保 {@code EntityJsonOrderTest} 的身份前缀不变）。
 */
@Entity
@Comment("活动发放跨系统传播 outbox（transactional outbox，GRANT_ISSUED/GRANT_REVERSED）")
@Table(name = "activity_grant_outbox",
        uniqueConstraints = {
                // 事件幂等键：一次发放（grant_no）最多一条 GRANT_ISSUED + 一条 GRANT_REVERSED。
                // **这是防重复发布的唯一硬保证**——confirm/release 的幂等重放靠它兜底，不靠应用层判重。
                // ⚠️ 生产靠显式迁移建，不依赖 ddl-auto:update（对既有表补 uk 不可靠）。
                @UniqueConstraint(name = "uk_outbox_grant_event",
                        columnNames = {"grant_no", "event_type"})
        },
        indexes = {
                // 中继按状态取可投递条目（PENDING 首投 + FAILED 补投）的扫描路径。
                @Index(name = "idx_outbox_status", columnList = "status,id"),
                // 客服 / 对账按发放号回溯该笔发出过哪些事件。
                @Index(name = "idx_outbox_grant_no", columnList = "grant_no")
        })
public class ActivityGrantOutboxEntity extends TenantScopedEntity {

    /** 确认发放（支付成功）事件：{@code amount_minor = +ISSUE 分额}。 */
    public static final String EVENT_GRANT_ISSUED = "GRANT_ISSUED";
    /** 退款冲正（对已 CONFIRMED 的发放）事件：{@code amount_minor = −(对应 ISSUE 分额)}。 */
    public static final String EVENT_GRANT_REVERSED = "GRANT_REVERSED";

    /** 待投递（首投）。 */
    public static final String STATUS_PENDING = "PENDING";
    /** 已成功投递给下游（dispatcher 返回成功）。 */
    public static final String STATUS_SENT = "SENT";
    /** 投递失败，等待退避后补投（attempt < maxAttempt 且到 nextAttemptAt 时可重试）。 */
    public static final String STATUS_FAILED = "FAILED";
    /** 死信：达 maxAttempt 仍失败，中继不再自动补投，需经 redrive 重置回 PENDING（人工/管理端）。 */
    public static final String STATUS_DEAD = "DEAD";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 发放号——下游 join 键（= recon {@code issue_id}）；与 {@link ActivityGrantEntity#getGrantNo()} 同源。 */
    @Column(name = "grant_no", length = 64, nullable = false)
    private String grantNo;

    @Column(name = "order_id", length = 64, nullable = false)
    private String orderId;

    @Column(name = "activity_id", length = 64, nullable = false)
    private String activityId;

    /** 事件类型：{@link #EVENT_GRANT_ISSUED} / {@link #EVENT_GRANT_REVERSED}。与 {@code entry_type} 一一对应。 */
    @Column(name = "event_type", length = 24, nullable = false)
    private String eventType;

    /** 分录类型（与分录台账一致）：{@link ActivityGrantEntryEntity#ISSUE} / {@link ActivityGrantEntryEntity#REVERSAL}。 */
    @Column(name = "entry_type", length = 16, nullable = false)
    private String entryType;

    /** 带符号最小单位（分）。ISSUE 事件为正、REVERSAL 事件为负（取对应 ISSUE 分额之负，不重算）。 */
    @Column(name = "amount_minor", nullable = false)
    private Long amountMinor;

    @Column(name = "currency", length = 8, nullable = false)
    private String currency;

    /**
     * 事件全文 JSON（供下游消费，含 grant_no/order_id/activity_id/event_type/entry_type/amount_minor/
     * currency/biz_time 与幂等键）。长文本用 {@code LONGVARCHAR}（MySQL longtext / H2 大字符对象），
     * 与 {@code ActivityArtifactEntity.eligDrl} 同款，避开 64KB TEXT 截断。
     */
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "payload")
    private String payload;

    /** 投递状态：{@link #STATUS_PENDING} / {@link #STATUS_SENT} / {@link #STATUS_FAILED}。 */
    @Column(name = "status", length = 16, nullable = false)
    private String status;

    /** 已投递尝试次数——每次失败 +1，达到 maxAttempt 后中继不再补投（进死信人工处理）。 */
    @Column(name = "attempt", nullable = false)
    private int attempt;

    /** 事件产生时间（confirm / release 时刻）。 */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** 成功投递时间（{@code SENT} 时落）；未成功为 {@code null}。 */
    @Column(name = "sent_at")
    private Instant sentAt;

    /**
     * 下次可补投时间（退避）：PENDING 首投为 {@code null}（立即）；每次失败置 {@code now + 指数退避}，中继只捞
     * {@code next_attempt_at <= now} 的 FAILED——避免一次可恢复下游故障在 tick 间隔内耗尽 maxAttempt 次重试（KI-9）。
     */
    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    public ActivityGrantOutboxEntity() {}

    public ActivityGrantOutboxEntity(String grantNo, String orderId, String activityId, String eventType,
                                     String entryType, Long amountMinor, String currency, String payload,
                                     Instant now) {
        this.grantNo = grantNo;
        this.orderId = orderId;
        this.activityId = activityId;
        this.eventType = eventType;
        this.entryType = entryType;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.payload = payload;
        this.status = STATUS_PENDING;
        this.attempt = 0;
        this.createdAt = now;
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

    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }

    public String getEntryType() { return entryType; }
    public void setEntryType(String entryType) { this.entryType = entryType; }

    public Long getAmountMinor() { return amountMinor; }
    public void setAmountMinor(Long amountMinor) { this.amountMinor = amountMinor; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public int getAttempt() { return attempt; }
    public void setAttempt(int attempt) { this.attempt = attempt; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getSentAt() { return sentAt; }
    public void setSentAt(Instant sentAt) { this.sentAt = sentAt; }

    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(Instant nextAttemptAt) { this.nextAttemptAt = nextAttemptAt; }
}
