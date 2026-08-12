package com.lrj.drools.activity.persistence;

import org.springframework.data.repository.Repository;

import java.util.Collection;
import java.util.List;

/**
 * 决策取数层用的**只读**绑定仓库。继承 {@link Repository} 的理由见
 * {@link ActivityManageReadRepository} 类注释（R17：把「读路径写不了库」提前到编译期）。
 */
public interface ActivitySpuBindingReadRepository extends Repository<ActivitySpuBindingEntity, Long> {

    /** 读取侧：按 SPU 批量查生效绑定（effective=1, isDel=0）。 */
    List<ActivitySpuBindingEntity> findBySpuIdInAndEffectiveAndIsDel(Collection<Long> spuIds, Integer effective, Integer isDel);

    /**
     * 快照构建侧：一次取回多个活动的全部未删除绑定行，由调用方在内存里按「当前线上版本」配对。
     *
     * <p><b>为什么是接口缺口逼出来的 N+1</b>：在它存在之前，{@code DecisionSnapshotBuilder}
     * 只能在 {@code for (活动)} 循环体里逐个调 {@code findByActivityIdAndVersionAndIsDel}——
     * 构建期查询数随活动目录规模线性增长。这条开销**不随请求量增长**，压测照不出来，
     * 却全打在 decision 那条只读连接上，且每分钟由兜底重建重跑一遍。
     *
     * <p>刻意<b>不带 version</b>：一次发布里每个活动的线上版本各不相同，无法用一个标量收窄；
     * 版本配对留在内存做（与 {@code DecisionDataLoader.scopeOf} 同一套判据）。
     */
    List<ActivitySpuBindingEntity> findByActivityIdInAndIsDel(Collection<String> activityIds, Integer isDel);
}
