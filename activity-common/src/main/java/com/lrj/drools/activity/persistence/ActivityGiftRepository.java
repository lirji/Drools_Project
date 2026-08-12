package com.lrj.drools.activity.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 写平面的赠品行仓库。
 *
 * <p>决策取数层用的批量查询已搬到 {@link ActivityGiftReadRepository}（继承 {@code Repository<T, ID>}，
 * 类型上没有 {@code save} / {@code delete}，R17）。
 */
public interface ActivityGiftRepository extends JpaRepository<ActivityGiftEntity, Long> {

    List<ActivityGiftEntity> findByActivityIdAndVersionAndIsDel(String activityId, Integer version, Integer isDel);
}
