package com.lrj.drools.activity.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * M1.4 发布代际仓库。**非 @TenantId 实体**（见 {@link ActivityGenerationEntity} 命门）：
 * {@code findAll()} 返回所有租户的代际，供无上下文的 poller 扫描。
 */
public interface ActivityGenerationRepository extends JpaRepository<ActivityGenerationEntity, Long> {

    Optional<ActivityGenerationEntity> findByTenantIdAndBizLine(String tenantId, String bizLine);
}
