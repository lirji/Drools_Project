package com.lrj.drools.activity.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

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
}
