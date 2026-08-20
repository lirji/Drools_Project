package com.lrj.drools.activity.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 发放流水仓储。租户维度由 {@code @TenantId} 自动作用域，方法签名里不出现 tenantId。
 */
public interface ActivityGrantRepository extends JpaRepository<ActivityGrantEntity, Long> {

    /** 幂等命中查询：同一单同一活动是否已经领过。confirm 的 CAS 后回读也复用它。 */
    Optional<ActivityGrantEntity> findFirstByOrderIdAndActivityId(String orderId, String activityId);

    /**
     * 支付回调确认发放——<b>幂等硬保证 = 条件 UPDATE {@code WHERE state='HELD'}</b>（复刻
     * {@link ActivityManageRepository#decrementInventory} 的 CAS 范式，{@code @TenantId} 自动追加租户谓词）。
     *
     * <p><b>受影响行数是唯一写决策</b>：返回 1 = 本次首次确认（HELD→CONFIRMED，同事务追 ISSUE 分录）；
     * 返回 0 = 没有 HELD 行可确认，由调用方回读一次<b>仅用于响应分流</b>（无行 404 / 已 CONFIRMED 幂等重放 /
     * 已 RELEASED 冲突 409）——不是 check-then-act。<b>绝不把 RELEASED 改回 CONFIRMED</b>：谓词只认 HELD。
     *
     * <p>只写 {@code state/amount/decision_id/modified_stime}——符号金额与 {@code entry_type} 落在分录台账
     * （{@code activity_grant_entry}），不在本行。{@code clearAutomatically=true} 保证同事务回读见到新态。
     *
     * @return 受影响行数：1 确认成功，0 无 HELD 行（未 claim / 已确认 / 已释放）
     */
    @Modifying(clearAutomatically = true)
    @Query("update ActivityGrantEntity g set g.state = 'CONFIRMED', g.amount = :amount, "
         + "g.decisionId = :decisionId, g.modifiedStime = :now "
         + "where g.orderId = :orderId and g.activityId = :activityId and g.state = 'HELD'")
    int confirmIfHeld(@Param("orderId") String orderId,
                      @Param("activityId") String activityId,
                      @Param("amount") BigDecimal amount,
                      @Param("decisionId") String decisionId,
                      @Param("now") Instant now);

    /**
     * 释放 HELD 发放（未付即取消/超时）——CAS {@code WHERE state='HELD'}，复刻 {@link #confirmIfHeld} 范式。
     *
     * <p><b>为什么必须 CAS</b>：原 {@code releaseGrant} 是「读快照→算 wasConfirmed→save 全行」，
     * 与并发 {@code confirmGrant} 有丢失更新竞态——confirm 先提交 HELD→CONFIRMED 并追 +ISSUE，
     * 而 release 的过期读仍判 wasConfirmed=false、save 覆写成 RELEASED 却不追 REVERSAL，
     * 台账留下孤儿 ISSUE、组内不守恒；两个并发 release 也会各自还一次库存。改成 CAS 后受影响行数是唯一写决策。
     *
     * @return 1 释放成功（原为 HELD，无冲正分录），0 无 HELD 行（已确认 / 已释放 / 不存在）
     */
    @Modifying(clearAutomatically = true)
    @Query("update ActivityGrantEntity g set g.state = 'RELEASED', g.modifiedStime = :now "
         + "where g.orderId = :orderId and g.activityId = :activityId and g.state = 'HELD'")
    int releaseIfHeld(@Param("orderId") String orderId,
                      @Param("activityId") String activityId,
                      @Param("now") Instant now);

    /**
     * 释放 CONFIRMED 发放（退款冲正）——CAS {@code WHERE state='CONFIRMED'}。归还库存并追一条 REVERSAL 分录。
     *
     * <p>与 {@link #releaseIfHeld} 配合：{@code releaseGrant} 先试 HELD 再试 CONFIRMED。状态机无回边
     * （HELD→CONFIRMED→RELEASED），故两次 CAS 都落空即已被并发释放置为 RELEASED（幂等重放）。
     * 只有 CAS 命中者才追 REVERSAL，天然避开并发双写导致的重复 REVERSAL。
     *
     * @return 1 释放成功（原为 CONFIRMED，需追 REVERSAL），0 无 CONFIRMED 行（仍 HELD / 已释放 / 不存在）
     */
    @Modifying(clearAutomatically = true)
    @Query("update ActivityGrantEntity g set g.state = 'RELEASED', g.modifiedStime = :now "
         + "where g.orderId = :orderId and g.activityId = :activityId and g.state = 'CONFIRMED'")
    int releaseIfConfirmed(@Param("orderId") String orderId,
                           @Param("activityId") String activityId,
                           @Param("now") Instant now);

    /**
     * 某用户在某活动上**已占用**的份数（不含已释放的）。每人限领的判据。
     *
     * <p>RELEASED 不计入是刻意的：退款之后那一份应该还给用户，
     * 否则「买了又退」会永久占掉他的领取额度。
     */
    @Query("select coalesce(sum(g.quantity), 0) from ActivityGrantEntity g "
         + "where g.activityId = :activityId and g.userId = :userId and g.state <> 'RELEASED'")
    int claimedQuantityByUser(@Param("activityId") String activityId, @Param("userId") String userId);

    /** 按订单查全部发放记录——客服「这一单用了哪些优惠」的数据源。 */
    List<ActivityGrantEntity> findByOrderId(String orderId);

    /** 按活动查发放记录（对账 / 效果分析）。 */
    List<ActivityGrantEntity> findByActivityIdAndState(String activityId, String state);
}
