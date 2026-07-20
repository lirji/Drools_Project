package com.lrj.drools.activity.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ActivityStrategyRepository extends JpaRepository<ActivityStrategyEntity, Long> {

    /** 精确匹配 (bizLine, activityType, scene)。 */
    Optional<ActivityStrategyEntity> findFirstByBizLineAndActivityTypeAndSceneAndIsDel(
            String bizLine, Integer activityType, String scene, Integer isDel);

    /** 业务线兜底（activityType 为 null）。 */
    Optional<ActivityStrategyEntity> findFirstByBizLineAndActivityTypeIsNullAndSceneAndIsDel(
            String bizLine, String scene, Integer isDel);
}
