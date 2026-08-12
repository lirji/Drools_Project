package com.lrj.drools.activity.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 写平面的资格条件行仓库。
 *
 * <p>决策取数层用的批量查询已搬到 {@link ActivityConditionReadRepository}（继承 {@code Repository<T, ID>}，
 * 类型上没有 {@code save} / {@code delete}，R17）。
 */
public interface ActivityConditionRepository extends JpaRepository<ActivityConditionEntity, Long> {

    List<ActivityConditionEntity> findByActivityIdAndVersionAndIsDel(String activityId, Integer version, Integer isDel);

    List<ActivityConditionEntity> findByActivityIdAndVersionAndSceneAndEnabledAndIsDel(
            String activityId, Integer version, String scene, Integer enabled, Integer isDel);
}
