package com.lrj.drools.activity.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 发放流水仓储。租户维度由 {@code @TenantId} 自动作用域，方法签名里不出现 tenantId。
 */
public interface ActivityGrantRepository extends JpaRepository<ActivityGrantEntity, Long> {

    /** 幂等命中查询：同一单同一活动是否已经领过。 */
    Optional<ActivityGrantEntity> findFirstByOrderIdAndActivityId(String orderId, String activityId);

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
