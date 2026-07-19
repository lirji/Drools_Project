package com.lrj.drools.activity.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ActivityConditionRepository extends JpaRepository<ActivityConditionEntity, Long> {

    List<ActivityConditionEntity> findByActivityIdAndVersionAndIsDel(String activityId, Integer version, Integer isDel);

    List<ActivityConditionEntity> findByActivityIdAndVersionAndSceneAndEnabledAndIsDel(
            String activityId, Integer version, String scene, Integer enabled, Integer isDel);
}
