package com.lrj.drools.activity.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * demo 店铺表仓库。picker 侧只用它按 storeId 批量补店名（{@code findAllById}，@TenantId 自动隔离）；
 * seeder 侧用它造数。店铺列表本身由 {@link DemoProductRepository#aggregateStores()} 驱动（Arch B）。
 */
public interface DemoStoreRepository extends JpaRepository<DemoStoreEntity, Integer> {
}
