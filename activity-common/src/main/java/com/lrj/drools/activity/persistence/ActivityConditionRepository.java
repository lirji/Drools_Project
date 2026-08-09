package com.lrj.drools.activity.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface ActivityConditionRepository extends JpaRepository<ActivityConditionEntity, Long> {

    List<ActivityConditionEntity> findByActivityIdAndVersionAndIsDel(String activityId, Integer version, Integer isDel);

    List<ActivityConditionEntity> findByActivityIdAndVersionAndSceneAndEnabledAndIsDel(
            String activityId, Integer version, String scene, Integer enabled, Integer isDel);

    /** P0-3 批量版：一次取回多个活动某场景的条件行，调用方按 (activityId, version) 索引。 */
    List<ActivityConditionEntity> findByActivityIdInAndSceneAndEnabledAndIsDel(
            Collection<String> activityIds, String scene, Integer enabled, Integer isDel);
}
