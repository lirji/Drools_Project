package com.lrj.drools.activity.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.TenantId;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * <b>发放流水</b>——「谁、在哪一单、从哪个活动、领走了多少」。
 *
 * <p><b>一张表解四件事</b>，这也是它值得单独存在的全部理由：
 * <ol>
 *   <li><b>claim 幂等</b>：{@code (tenant, order_id, activity_id)} 唯一约束。此前 claim 明确「不幂等」，
 *       用户连点两次就扣两次库存——因为没有任何东西记得「这一单已经领过了」。</li>
 *   <li><b>每人限领</b>：按 {@code (tenant, activity_id, user_id)} 计数即可。此前 {@code userInventory}
 *       是条彻底的死路：写入口硬编码 0、决策侧零读取，运营连填都填不了。</li>
 *   <li><b>退款冲正</b>：{@code state} 从 {@code CONFIRMED} 改成 {@code RELEASED} 并把库存加回去。
 *       此前订单取消后库存永久蒸发，没有任何路径能还回来。</li>
 *   <li><b>发放对账</b>：财务问「这个月营销发了多少钱」，此前只能从 Prometheus 的命中<em>次数</em>估——
 *       金额从来没被记录过。</li>
 * </ol>
 *
 * <p><b>为什么不复用 {@code activity_idempotency}</b>：那张表的键是 {@code requestId}（客户端生成的
 * 请求去重键），语义是「这个请求处理过没有」；这张表的键是<b>业务事实</b>（哪一单、哪个用户、哪个活动），
 * 语义是「这份优惠发出去没有」。前者可以随重试策略变化，后者是账。混在一起的后果是
 * 换个客户端重试实现就能把同一单领两次。
 *
 * <p><b>它只在写平面被写入</b>（console）。decision 连只读账号，物理上写不了——
 * 决策留痕是另一条路（结构化日志 + 指标），不要试图在热路径上写这张表。
 */
@Entity
@Table(name = "activity_grant",
        uniqueConstraints = {
                // 幂等键：同一单里同一个活动只能领一次。**这是防重复发放的唯一硬保证**，
                // 不能靠应用层「先查再插」——那是 check-then-act，低并发测不出、大促必现。
                @UniqueConstraint(name = "uk_grant_tenant_order_activity",
                        columnNames = {"tenant_id", "order_id", "activity_id"})
        },
        indexes = {
                // 每人限领的计数路径
                @Index(name = "idx_grant_tenant_activity_user", columnList = "tenant_id,activity_id,user_id"),
                // 对账 / 客服按单查
                @Index(name = "idx_grant_order", columnList = "order_id")
        })
public class ActivityGrantEntity {

    /** 已占用但未确认（下单未支付）。库存已扣。 */
    public static final String HELD = "HELD";
    /** 已确认（支付完成）。库存已扣。 */
    public static final String CONFIRMED = "CONFIRMED";
    /** 已释放（取消/退款/超时）。库存已还。 */
    public static final String RELEASED = "RELEASED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 租户隔离列：@TenantId 自动追加谓词 + insert 自动落值。 */
    @TenantId
    @Column(name = "tenant_id", length = 64)
    private String tenantId;

    @Column(name = "activity_id", length = 64, nullable = false)
    private String activityId;

    /** 领取时活动的版本——「这份优惠按哪一版发的」，对账与客服回溯的第一个问题。 */
    @Column(name = "version", nullable = false)
    private Integer version;

    /** 领取人。每人限领按它计数；为空表示调用方没提供身份（此时限领无从执行，见 claim 的校验）。 */
    @Column(name = "user_id", length = 64)
    private String userId;

    /** 订单号。与 activityId 组成幂等键——同一单重复提交只算一次。 */
    @Column(name = "order_id", length = 64, nullable = false)
    private String orderId;

    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    /** 本次实际发出的减免金额（元）。财务对账的数据源；决策报价与实际发放可能不同，这里记的是**发放**。 */
    @Column(name = "amount", precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(name = "state", length = 16, nullable = false)
    private String state;

    /** 对应决策的锚点（{@code DiscountView.decisionId}）。把「报价」和「发放」两条记录串起来。 */
    @Column(name = "decision_id", length = 64)
    private String decisionId;

    @Column(name = "created_stime", nullable = false)
    private Instant createdStime;

    @Column(name = "modified_stime", nullable = false)
    private Instant modifiedStime;

    public ActivityGrantEntity() {}

    public ActivityGrantEntity(String activityId, Integer version, String userId, String orderId,
                               Integer quantity, BigDecimal amount, String state, String decisionId,
                               Instant now) {
        this.activityId = activityId;
        this.version = version;
        this.userId = userId;
        this.orderId = orderId;
        this.quantity = quantity;
        this.amount = amount;
        this.state = state;
        this.decisionId = decisionId;
        this.createdStime = now;
        this.modifiedStime = now;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getActivityId() { return activityId; }
    public void setActivityId(String activityId) { this.activityId = activityId; }

    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }

    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getDecisionId() { return decisionId; }
    public void setDecisionId(String decisionId) { this.decisionId = decisionId; }

    public Instant getCreatedStime() { return createdStime; }
    public void setCreatedStime(Instant createdStime) { this.createdStime = createdStime; }

    public Instant getModifiedStime() { return modifiedStime; }
    public void setModifiedStime(Instant modifiedStime) { this.modifiedStime = modifiedStime; }
}
