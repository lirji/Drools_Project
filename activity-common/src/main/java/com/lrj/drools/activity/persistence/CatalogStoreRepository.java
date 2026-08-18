package com.lrj.drools.activity.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 店铺目录仓库。选择器按 storeId 批量补店名（{@code findAllById}，@TenantId 自动隔离）；
 * 初始化器可按需写入目录数据。店铺列表由 {@link CatalogProductRepository#aggregateStores()} 驱动。
 */
public interface CatalogStoreRepository extends JpaRepository<CatalogStoreEntity, Integer> {
}
