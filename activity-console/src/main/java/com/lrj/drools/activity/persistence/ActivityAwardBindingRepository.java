package com.lrj.drools.activity.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ActivityAwardBindingRepository extends JpaRepository<ActivityAwardBindingEntity, Long> {
    List<ActivityAwardBindingEntity> findByActivityIdAndVersionOrderByIdAsc(String activityId, Integer version);
    void deleteByActivityIdAndVersion(String activityId, Integer version);
}
