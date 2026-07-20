package com.lrj.drools.activity.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PoolRefRepository extends JpaRepository<PoolRefEntity, Long> {

    List<PoolRefEntity> findByActivityIdAndVersionAndIsDel(String activityId, Integer version, Integer isDel);

    List<PoolRefEntity> findByPoolIdAndIsDel(Long poolId, Integer isDel);
}
