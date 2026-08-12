package com.lrj.drools.activity.persistence;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/**
 * 决策取数层（{@code DecisionDataLoader} / {@code DecisionSnapshotBuilder}）用的**只读**活动仓库。
 *
 * <p><b>为什么是 {@link Repository} 而不是 {@code JpaRepository}</b>（R17）：
 * 「decision 进程写不了库」此前只靠<b>运行期</b>兜住——只读数据库账号 + {@code ddl-auto: validate}。
 * 也就是说，读路径上任何一次手滑的 {@code save(...)} 都能编译通过、能通过全部单测
 * （测试库是可写的 H2），只在生产的只读连接上炸。继承 {@code Repository<T, ID>} 之后，
 * {@code save} / {@code delete} / {@code flush} <b>在类型上根本不存在</b>，
 * 这条保证就从运行期提前到了编译期。
 *
 * <p><b>方法签名与写侧一字不差地保持原样</b>：这些查询是从 {@link ActivityManageRepository}
 * 原样搬过来的（读路径是它们唯一的调用方），不是新写的。改任何一个参数或谓词都不是本次重构的范围。
 *
 * <p>@TenantId 判别式过滤由 Hibernate 在 SQL 层加，与仓库接口继承谁无关——
 * 派生查询在 {@code Repository<>} 上一样带租户谓词（{@code DecisionTenantHeaderTest} 守这条）。
 */
public interface ActivityManageReadRepository extends Repository<ActivityManageEntity, Long> {

    /**
     * P0-3 批量版：一次取回多个活动的**全部未删除版本**，由调用方在内存里挑每个活动的最高版本。
     * 取代决策热路径上「逐个 activityId 查当前版本」的 N 次往返（评估报告 D1）。
     */
    List<ActivityManageEntity> findByActivityIdInAndIsDel(Collection<String> activityIds, Integer isDel);

    List<ActivityManageEntity> findByActivityStatusAndIsDel(Integer activityStatus, Integer isDel);

    /**
     * 快照构建侧：某条业务线的在线活动。<b>bizLine 过滤下推到 SQL</b>。
     *
     * <p>此前构建器捞该租户**全部**在线活动再用 Java {@code if} 丢掉非本桶的：
     * 桶越多，每个桶的构建就越是在做「读全表、扔掉 (M-1)/M」的白工，而这批读全打在
     * decision 的只读连接上。
     *
     * <p>bizLine 为 null 时**不要**调它——派生查询生成的是 {@code biz_line = ?}，
     * 绑 null 一行都匹配不上，而构建器对 {@code bizLine == null} 的既有语义是「不过滤，全收」。
     * 那一档仍走 {@link #findByActivityStatusAndIsDel}。
     */
    List<ActivityManageEntity> findByBizLineAndActivityStatusAndIsDel(
            String bizLine, Integer activityStatus, Integer isDel);

    /**
     * 数出「bizLine 为空（null 或全空白）」的在线活动个数——**它们进不了任何决策快照桶**。
     *
     * <p>快照按 bizLine 精确匹配收活动，所以这类活动在决策侧的表现是：
     * provenance 三个值全绿（走的是快照、代际是别条业务线的正常数、快照也很新），
     * 活动就是不在里面。在补这条计数之前，它只能靠诊断端点
     * {@code GET /decision/v1/snapshot?activityId=} 一个一个照出来——也就是说，
     * <b>得先怀疑到某个具体活动头上，才查得到</b>。这条把它提前到构建期。
     *
     * <p>按 activityId 去重：同一个活动的多个版本行是同一处配置错误，不该数成好几笔。
     */
    @Query("select count(distinct e.activityId) from ActivityManageEntity e "
         + "where e.activityStatus = :activityStatus and e.isDel = :isDel "
         + "and (e.bizLine is null or trim(e.bizLine) = '')")
    long countOrphanBizLine(@Param("activityStatus") Integer activityStatus, @Param("isDel") Integer isDel);
}
