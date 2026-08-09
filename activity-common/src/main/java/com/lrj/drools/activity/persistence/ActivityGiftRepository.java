package com.lrj.drools.activity.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface ActivityGiftRepository extends JpaRepository<ActivityGiftEntity, Long> {

    List<ActivityGiftEntity> findByActivityIdAndVersionAndIsDel(String activityId, Integer version, Integer isDel);

    /** P0-3 批量版：一次取回多个活动的赠品行，调用方按 (activityId, version) 索引。 */
    List<ActivityGiftEntity> findByActivityIdInAndIsDel(Collection<String> activityIds, Integer isDel);
}
