package com.lrj.drools.activity.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ActivityGiftRepository extends JpaRepository<ActivityGiftEntity, Long> {

    List<ActivityGiftEntity> findByActivityIdAndVersionAndIsDel(String activityId, Integer version, Integer isDel);
}
