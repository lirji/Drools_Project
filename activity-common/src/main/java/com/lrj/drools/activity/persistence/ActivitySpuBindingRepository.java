package com.lrj.drools.activity.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 写平面的绑定仓库。
 *
 * <p>决策取数层（{@code DecisionDataLoader} / {@code DecisionSnapshotBuilder}）用的两条批量查询
 * 已搬到 {@link ActivitySpuBindingReadRepository}——那个接口继承 {@code Repository<T, ID>}，
 * {@code save} / {@code delete} 在类型上不存在（R17）。
 */
public interface ActivitySpuBindingRepository extends JpaRepository<ActivitySpuBindingEntity, Long> {

    List<ActivitySpuBindingEntity> findByActivityIdAndVersionAndIsDel(String activityId, Integer version, Integer isDel);

    /** 自动绑定 diff：某活动版本下按绑定来源取行（0 手动 / 1 自动）。 */
    List<ActivitySpuBindingEntity> findByActivityIdAndVersionAndBindSourceAndIsDel(
            String activityId, Integer version, Integer bindSource, Integer isDel);

    /**
     * 详情回显——店铺聚合投影（一次返回，行数 = O(店铺数)，不随该活动的 SPU 总量增长）。
     *
     * <p>口径（D5）：{@code isDel=0} 全计（含失效行，与详情页现有「商品绑定·N」一致），
     * 另给 {@code effectiveCount} 只数 {@code effective=1}，供「N 件·X 生效」。
     * {@code group by b.storeId} 会自然产生 {@code storeId=null} 的「未指定门店」组（D7）。
     *
     * <p>全 JPQL，{@code @TenantId} 自动追加 {@code tenant_id} 谓词（守卫见
     * {@code TenantIsolationTest#bulkUpdateIsTenantScoped}）；<b>严禁</b>改成 native（会绕过租户隔离）。
     */
    @Query("select b.storeId as storeId, count(b) as spuCount, "
            + "sum(case when b.effective = 1 then 1 else 0 end) as effectiveCount "
            + "from ActivitySpuBindingEntity b "
            + "where b.activityId = ?1 and b.version = ?2 and b.isDel = 0 group by b.storeId")
    List<StoreSpuCount> aggregateStoresByVersion(String activityId, Integer version);

    /** {@link #aggregateStoresByVersion} 的接口投影。别名与 getter 名一一对齐。 */
    interface StoreSpuCount {
        Integer getStoreId();   // 可空：null = 未指定门店桶

        long getSpuCount();

        long getEffectiveCount();
    }

    /**
     * 详情回显——某店铺下的绑定明细分页（D4：服务端分页，万级行不全量下发）。
     *
     * <p>{@code storeId} null-safe（D7）：传 null 命中「未指定门店」桶；不加 effective 过滤，
     * 逐行带 effective/bindSource 让运营自查（D5）。查询无 case/distinct/构造表达式，
     * Spring Data 自动 count 派生即可，无需显式 countQuery。同样全 JPQL 保 {@code @TenantId}。
     */
    @Query("select b from ActivitySpuBindingEntity b "
            + "where b.activityId = :aid and b.version = :v and b.isDel = 0 "
            + "and ((:storeId is null and b.storeId is null) or b.storeId = :storeId)")
    Page<ActivitySpuBindingEntity> pageStoreBindings(@Param("aid") String aid, @Param("v") Integer v,
                                                     @Param("storeId") Integer storeId, Pageable pageable);
}
