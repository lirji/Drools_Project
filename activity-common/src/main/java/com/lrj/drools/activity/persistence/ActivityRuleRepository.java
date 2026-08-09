package com.lrj.drools.activity.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface ActivityRuleRepository extends JpaRepository<ActivityRuleEntity, Long> {

    List<ActivityRuleEntity> findByActivityIdAndVersionAndIsDel(String activityId, Integer version, Integer isDel);

    /** P0-3 批量版：一次取回多个活动的规则行，调用方按 (activityId, version) 索引。 */
    List<ActivityRuleEntity> findByActivityIdInAndIsDel(Collection<String> activityIds, Integer isDel);
}
