package com.lrj.drools.activity.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * P1-9 artifact 仓库。租户维度由 {@code @TenantId} 自动隔离。
 */
public interface ActivityArtifactRepository extends JpaRepository<ActivityArtifactEntity, Long> {

    Optional<ActivityArtifactEntity> findFirstByActivityIdAndVersion(String activityId, Integer version);

    /** 某业务线下指定状态的 artifact（schema 变更时批量复检 ACTIVE 的）。 */
    List<ActivityArtifactEntity> findByBizLineAndStatus(String bizLine, String status);
}
