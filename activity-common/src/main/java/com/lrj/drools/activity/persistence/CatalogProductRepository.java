package com.lrj.drools.activity.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CatalogProductRepository extends JpaRepository<CatalogProductEntity, Long> {

    List<CatalogProductEntity> findByOnShelf(Integer onShelf);

    /**
     * 「选店铺→勾商品」picker 的店铺列表：按 store_id 聚合出「有在架商品的店 + 商品数」。
     * 只列在架商品所在的店（选空店无意义）；{@code store_id is not null} 防 nullable 列产出 null 组。
     * 接口投影，全 JPQL，@TenantId 自动隔离（与 {@code ActivitySpuBindingRepository.aggregateStoresByVersion} 同构）。
     */
    @Query("select p.storeId as storeId, count(p) as productCount from CatalogProductEntity p "
            + "where p.onShelf = 1 and p.storeId is not null group by p.storeId")
    List<StoreProductCount> aggregateStores();

    /** {@link #aggregateStores()} 的接口投影。别名与 getter 名一一对齐。 */
    interface StoreProductCount {
        Integer getStoreId();

        long getProductCount();
    }

    /**
     * picker 下钻：某店铺下的在架商品分页（服务端 keyword+分页）。
     * {@code keyword} 为 null/空时不过滤；无 case/distinct/构造表达式 → Spring Data 自动 count 派生。
     * 全 JPQL 保 @TenantId，禁 native。
     */
    @Query("select p from CatalogProductEntity p where p.storeId = :sid and p.onShelf = 1 "
            + "and (:kw is null or lower(p.spuName) like lower(concat('%', :kw, '%')))")
    Page<CatalogProductEntity> pageStoreProducts(@Param("sid") Integer storeId, @Param("kw") String keyword, Pageable pageable);
}
