package com.lrj.drools.activity.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface ActivitySpuBindingRepository extends JpaRepository<ActivitySpuBindingEntity, Long> {

    /** 读取侧：按 SPU 批量查生效绑定（effective=1, isDel=0）。 */
    List<ActivitySpuBindingEntity> findBySpuIdInAndEffectiveAndIsDel(Collection<Long> spuIds, Integer effective, Integer isDel);

    List<ActivitySpuBindingEntity> findByActivityIdAndVersionAndIsDel(String activityId, Integer version, Integer isDel);

    /** 自动绑定 diff：某活动版本下按绑定来源取行（0 手动 / 1 自动）。 */
    List<ActivitySpuBindingEntity> findByActivityIdAndVersionAndBindSourceAndIsDel(
            String activityId, Integer version, Integer bindSource, Integer isDel);
}
