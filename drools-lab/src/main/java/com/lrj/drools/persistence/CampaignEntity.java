package com.lrj.drools.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * Step 18: 营销活动 + 绑定的资格规则, 持久化到 H2。
 *
 * 跟 Step 10 的 SessionSnapshot 是同一套"复用 JPA + H2"思路, 但存的东西不同:
 *   - SessionSnapshot 存的是 marshall 出来的 KieSession byte[] (运行时状态)
 *   - CampaignEntity 存的是 DRL **源文本** (规则定义本身)
 *
 * 为什么要持久化 DRL: 这正是"Step 9 热加载 + 持久化"相比纯 Step 9 的价值 ——
 * 运营创建的活动规则不再是进程内 Map 的临时态, 应用重启后还在;
 * CampaignService 在 check 时若发现内存缓存里没有该活动的 KieBase,
 * 会从这张表把 DRL 捞出来重新编译 (懒重建), 所以重启不丢活动。
 *
 * eligibilityDrl 存长文本; 一段 DRL 通常几百到几千字符。用 @JdbcTypeCode(LONGVARCHAR)
 * 而不是 @Lob: MySQL 下映射成 longtext (而非可能被截断的 64KB text), H2 下是大字符对象,
 * 两个 profile 都够装, 且 DRL 里的中文在 application-mysql.yml 配了 UTF-8 不乱码。
 */
@Entity
@Comment("Drools 规则能力活动配置表")
@Table(name = "campaign")
public class CampaignEntity {

    @Id
    @Column(name = "campaign_id", length = 64, nullable = false)
    private String campaignId;

    @Column(name = "name", length = 128, nullable = false)
    private String name;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "eligibility_drl", nullable = false)
    private String eligibilityDrl;

    /** ACTIVE / ENDED。ENDED 的活动 check 直接拒绝, 不再编译跑规则。 */
    @Column(name = "status", length = 16, nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CampaignEntity() {} // JPA 必需的无参构造

    public CampaignEntity(String campaignId, String name, String eligibilityDrl,
                          String status, Instant createdAt, Instant updatedAt) {
        this.campaignId = campaignId;
        this.name = name;
        this.eligibilityDrl = eligibilityDrl;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getCampaignId() { return campaignId; }
    public String getName() { return name; }
    public String getEligibilityDrl() { return eligibilityDrl; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setName(String name) { this.name = name; }
    public void setEligibilityDrl(String eligibilityDrl) { this.eligibilityDrl = eligibilityDrl; }
    public void setStatus(String status) { this.status = status; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
