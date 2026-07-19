package com.lrj.drools.activity.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ProductPoolRuleRepository extends JpaRepository<ProductPoolRuleEntity, Long> {

    /** 一个池的启用规则（enabled=1, isDel=0）。 */
    Optional<ProductPoolRuleEntity> findFirstByPoolIdAndEnabledAndIsDel(Long poolId, Integer enabled, Integer isDel);
}
