package com.lrj.drools.activity.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * ISSUE-07 · 幂等登记仓库。租户维度由 {@code @TenantId} 自动隔离，方法只按 {@code requestId} 查即已租户作用域化。
 */
public interface ActivityIdempotencyRepository extends JpaRepository<ActivityIdempotencyEntity, Long> {

    /** 同 requestId 的首次处理登记（当前租户内）。 */
    Optional<ActivityIdempotencyEntity> findFirstByRequestId(String requestId);
}
