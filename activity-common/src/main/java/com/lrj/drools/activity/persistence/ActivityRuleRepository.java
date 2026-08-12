package com.lrj.drools.activity.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 写平面的规则行仓库。
 *
 * <p>决策取数层用的批量查询已搬到 {@link ActivityRuleReadRepository}（继承 {@code Repository<T, ID>}，
 * 类型上没有 {@code save} / {@code delete}，R17）。
 */
public interface ActivityRuleRepository extends JpaRepository<ActivityRuleEntity, Long> {

    List<ActivityRuleEntity> findByActivityIdAndVersionAndIsDel(String activityId, Integer version, Integer isDel);
}
