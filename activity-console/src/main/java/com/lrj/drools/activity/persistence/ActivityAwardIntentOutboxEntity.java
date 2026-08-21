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

/** 独立于 legacy GrantEvent 的 AwardIntent transactional outbox。 */
@Entity
@Comment("活动平台向权益中台投递AwardIntent的outbox")
@Table(name = "activity_award_intent_outbox",
        uniqueConstraints = @UniqueConstraint(name = "uk_award_intent_source",
                columnNames = {"tenant_id", "source_system", "source_request_id"}),
        indexes = {
                @Index(name = "idx_award_intent_outbox_due",
                        columnList = "tenant_id,status,next_attempt_at,id"),
                @Index(name = "idx_award_intent_outbox_lease",
                        columnList = "status,lease_until,tenant_id")
        })
public class ActivityAwardIntentOutboxEntity extends TenantScopedEntity {
    public static final String PENDING = "PENDING";
    public static final String SENDING = "SENDING";
    public static final String SENT = "SENT";
    public static final String FAILED = "FAILED";
    public static final String DEAD = "DEAD";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source_system", length = 64, nullable = false)
    private String sourceSystem;

    @Column(name = "source_request_id", length = 128, nullable = false)
    private String sourceRequestId;

    @Column(name = "activity_id", length = 64, nullable = false)
    private String activityId;

    @Column(name = "activity_version", nullable = false)
    private Integer activityVersion;

    @Column(name = "payload_hash", length = 64, nullable = false)
    private String payloadHash;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "payload", nullable = false)
    private String payload;

    @Column(name = "status", length = 16, nullable = false)
    private String status;

    @Column(name = "attempt", nullable = false)
    private int attempt;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "lease_owner", length = 128)
    private String leaseOwner;

    @Column(name = "lease_until")
    private Instant leaseUntil;

    protected ActivityAwardIntentOutboxEntity() {}

    public ActivityAwardIntentOutboxEntity(String sourceSystem, String sourceRequestId, String activityId,
                                           Integer activityVersion, String payloadHash, String payload,
                                           Instant now) {
        this.sourceSystem = sourceSystem;
        this.sourceRequestId = sourceRequestId;
        this.activityId = activityId;
        this.activityVersion = activityVersion;
        this.payloadHash = payloadHash;
        this.payload = payload;
        this.status = PENDING;
        this.attempt = 0;
        setCreatedStime(now);
        setModifiedStime(now);
    }

    public Long getId() { return id; }
    public String getSourceSystem() { return sourceSystem; }
    public String getSourceRequestId() { return sourceRequestId; }
    public String getActivityId() { return activityId; }
    public Integer getActivityVersion() { return activityVersion; }
    public String getPayloadHash() { return payloadHash; }
    public String getPayload() { return payload; }
    public String getStatus() { return status; }
    public int getAttempt() { return attempt; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public Instant getSentAt() { return sentAt; }
    public String getLeaseOwner() { return leaseOwner; }
    public Instant getLeaseUntil() { return leaseUntil; }
}
