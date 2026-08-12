package com.lrj.drools.activity.persistence;

import org.springframework.data.repository.Repository;

import java.util.Collection;
import java.util.List;

/**
 * 决策取数层用的**只读**资格条件行仓库。继承 {@link Repository} 的理由见
 * {@link ActivityManageReadRepository} 类注释（R17）。
 */
public interface ActivityConditionReadRepository extends Repository<ActivityConditionEntity, Long> {

    /** P0-3 批量版：一次取回多个活动某场景的条件行，调用方按 (activityId, version) 索引。 */
    List<ActivityConditionEntity> findByActivityIdInAndSceneAndEnabledAndIsDel(
            Collection<String> activityIds, String scene, Integer enabled, Integer isDel);
}
