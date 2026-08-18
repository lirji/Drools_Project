package com.lrj.drools.activity.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 写平面的活动主表仓库。
 *
 * <p>决策取数层与快照构建期用的四条只读查询（批量取版本 / 按状态取在线活动 /
 * 按业务线取在线活动 / 孤儿 bizLine 计数）已搬到 {@link ActivityManageReadRepository}——
 * 那个接口继承 {@code Repository<T, ID>}，{@code save} / {@code delete} 在类型上不存在（R17）。
 * 库存的两条原子 UPDATE 只能留在这里：它们是写平面独占的（decision 连的是只读账号）。
 */
public interface ActivityManageRepository extends JpaRepository<ActivityManageEntity, Long> {

    /** 定位某活动的指定版本行（isDel 一般传 0）。 */
    Optional<ActivityManageEntity> findFirstByActivityIdAndVersionAndIsDel(String activityId, Integer version, Integer isDel);

    /** 当前生效版本（未删除里 version 最大的）。 */
    Optional<ActivityManageEntity> findFirstByActivityIdAndIsDelOrderByVersionDesc(String activityId, Integer isDel);

    /** P0-4：某活动当前处于某状态的全部版本。发布新版本时用它把旧的线上版本退役（原子指针切换的实现基础）。 */
    List<ActivityManageEntity> findByActivityIdAndActivityStatusAndIsDel(
            String activityId, Integer activityStatus, Integer isDel);

    /**
     * 当前租户本轮需要处理的活动 id。用 activityId 做续扫游标，避免固定第一页中的故障活动
     * 永久阻塞后续到期活动；扫到末尾后由调用方回卷到 null。
     */
    @Query("""
            select distinct e.activityId
              from ActivityManageEntity e
             where e.isDel = 0
               and (:afterActivityId is null or e.activityId > :afterActivityId)
               and ((e.activityStatus = 3 and e.activityStartTime <= :now)
                 or (e.activityStatus = 1 and e.activityEndTime < :now))
             order by e.activityId
            """)
    List<String> findDueLifecycleActivityIds(@Param("now") Instant now,
                                             @Param("afterActivityId") String afterActivityId,
                                             Pageable pageable);

    /**
     * 锁住同一活动的全部未删除版本，再决定“激活哪版、退役哪版”。这样多 console 实例同时扫描时，
     * 第二个事务拿到锁后会看到第一个事务已经完成的状态并幂等跳过。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select e
              from ActivityManageEntity e
             where e.activityId = :activityId and e.isDel = :isDel
             order by e.version asc
            """)
    List<ActivityManageEntity> lockVersionsForLifecycle(@Param("activityId") String activityId,
                                                        @Param("isDel") Integer isDel);

    /** 幂等：同 requestId 首次结果。 */
    Optional<ActivityManageEntity> findFirstByRequestIdAndIsDel(String requestId, Integer isDel);

    /** 列表（未删除，按最近修改倒序）。 */
    List<ActivityManageEntity> findByIsDelOrderByModifiedStimeDesc(Integer isDel);

    /**
     * 逻辑删除旧版本行，返回受影响行数。用于版本化编辑的并发保护：
     * 影响行数为 0 说明旧版本已被别的请求删掉（并发双写），调用方返回 409。
     */
    /**
     * 库存原子扣减——**防超发的唯一正确写法**。
     *
     * <p>把「判断余量」和「减一」压进<b>同一条 UPDATE 的 WHERE 里</b>：
     * {@code set inventory = inventory - :n where inventory >= :n}。数据库对同一行的更新是串行的，
     * 于是并发下最多只有 {@code inventory/n} 次能返回 1，其余全部返回 0。
     *
     * <p><b>绝不能写成「先 SELECT 查余量、判断够不够、再 UPDATE 减掉」</b>——那是教科书级的
     * check-then-act 竞态：两个线程可能同时读到 inventory=1、同时判定"够"、然后各减一次，
     * 库存变成 -1，两个人都拿到了同一件秒杀品。这个 bug 在低并发下测不出来，
     * 上线当天大促流量一到就必现。
     *
     * <p>返回受影响行数：1 = 抢到，0 = 没抢到（余量不足或活动不存在/已删）。
     * <b>调用方必须按 0 处理失败，不能忽略返回值</b>——忽略返回值等于扣减从未生效。
     *
     * @param n 本次扣减数量（正数）
     * @return 受影响行数：1 成功，0 失败
     */
    @Modifying
    @Query("update ActivityManageEntity e set e.inventory = e.inventory - :n, e.modifiedStime = :now "
         + "where e.activityId = :activityId and e.version = :version and e.isDel = 0 "
         + "and e.activityStatus = 1 "
         + "and e.activityStartTime <= :now and e.activityEndTime >= :now "
         + "and e.inventory is not null and e.inventory >= :n")
    int decrementInventory(@Param("activityId") String activityId,
                           @Param("version") Integer version,
                           @Param("n") int n,
                           @Param("now") java.time.Instant now);

    /**
     * 归还库存（退款 / 取消 / 超时释放）。
     *
     * <p><b>刻意不带状态与时间窗谓词</b>——与扣减正好相反：还库存这件事在活动下线之后、
     * 结束之后同样必须成立。一笔在活动期内领走的优惠，用户可能在活动结束之后才退款；
     * 那时若因为「活动已结束」而拒绝归还，库存就永久蒸发了。
     * 防重复归还靠的是流水的 {@code state}（只有非 RELEASED 的记录才会走到这里），不是这条 SQL。
     */
    @Modifying
    @Query("update ActivityManageEntity e set e.inventory = e.inventory + :n, e.modifiedStime = :now "
         + "where e.activityId = :activityId and e.version = :version and e.isDel = 0 "
         + "and e.inventory is not null")
    int incrementInventory(@Param("activityId") String activityId,
                           @Param("version") Integer version,
                           @Param("n") int n,
                           @Param("now") java.time.Instant now);

    @Modifying
    @Query("update ActivityManageEntity e set e.isDel = 1, e.modifiedStime = :now " +
            "where e.activityId = :activityId and e.version = :version and e.isDel = 0")
    int softDeleteVersion(@Param("activityId") String activityId,
                          @Param("version") Integer version,
                          @Param("now") Instant now);
}
