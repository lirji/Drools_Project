package com.lrj.drools.activity.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ActivityRuleRepository extends JpaRepository<ActivityRuleEntity, Long> {

    List<ActivityRuleEntity> findByActivityIdAndVersionAndIsDel(String activityId, Integer version, Integer isDel);
}
