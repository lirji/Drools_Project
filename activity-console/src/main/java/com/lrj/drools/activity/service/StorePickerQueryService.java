package com.lrj.drools.activity.service;

import com.lrj.drools.activity.persistence.CatalogProductEntity;
import com.lrj.drools.activity.persistence.CatalogProductRepository;
import com.lrj.drools.activity.persistence.CatalogProductRepository.StoreProductCount;
import com.lrj.drools.activity.persistence.CatalogStoreEntity;
import com.lrj.drools.activity.persistence.CatalogStoreRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 「选店铺→勾商品」picker 的**只读目录浏览**服务（编辑态用）。平级 {@link DistrictQueryService}，
 * 不塞进臃肿的 {@link ActivityMarketingService}。
 *
 * <p>与 decision 侧的「活动已绑定视图」（{@code /binding-stores} /{@code /binding-spus}）语义不同：
 * 这里回答「当前租户有哪些店 / 某店有哪些在架商品可勾选」，数据源是 {@code catalog_product}(+{@code catalog_store} 供名)。
 * 全走 JpaRepository 的 JPQL/派生查询，{@code @TenantId} 自动隔离（守卫 {@code TenantIsolationTest#storePickerIsolation}）。
 */
@Service
public class StorePickerQueryService {

    private final CatalogProductRepository productRepo;
    private final CatalogStoreRepository storeRepo;

    public StorePickerQueryService(CatalogProductRepository productRepo, CatalogStoreRepository storeRepo) {
        this.productRepo = productRepo;
        this.storeRepo = storeRepo;
    }

    /**
     * 店铺列表（Arch B）：由 catalog_product 的 group by store_id 驱动（只列有在架商品的店），
     * 再用 {@code findAllById} 一次批量补店名（无 N+1，join 不到店名回退 null → 前端显示「店铺 #id」）。
     */
    public List<PickerStore> stores() {
        List<StoreProductCount> agg = productRepo.aggregateStores();
        List<Integer> storeIds = agg.stream().map(StoreProductCount::getStoreId).collect(Collectors.toList());
        Map<Integer, CatalogStoreEntity> byId = storeIds.isEmpty() ? Map.of()
                : storeRepo.findAllById(storeIds).stream()
                    .collect(Collectors.toMap(CatalogStoreEntity::getStoreId, s -> s, (a, b) -> a));
        return agg.stream()
                .map(r -> {
                    CatalogStoreEntity s = byId.get(r.getStoreId());
                    return new PickerStore(r.getStoreId(), s != null ? s.getStoreName() : null, r.getProductCount());
                })
                .collect(Collectors.toList());
    }

    /** 某店铺下的在架商品分页（服务端 keyword+分页）。keyword 空白视作不过滤。 */
    public PickerProductPage products(Integer storeId, String keyword, int page, int size) {
        String kw = (keyword == null || keyword.isBlank()) ? null : keyword.trim();
        Pageable pageable = PageRequest.of(Math.max(0, page), size <= 0 ? 20 : size);
        Page<CatalogProductEntity> pageResult = productRepo.pageStoreProducts(storeId, kw, pageable);
        List<PickerProduct> items = pageResult.getContent().stream()
                .map(p -> new PickerProduct(p.getSpuId(), p.getSpuName(), p.getPrice(), p.getOnShelf()))
                .collect(Collectors.toList());
        return new PickerProductPage(pageResult.getTotalElements(), pageResult.getNumber(), pageResult.getSize(), items);
    }

    /** 店铺一行：{@code storeName} 可空（catalog_store 里查不到时回退，前端显示「店铺 #id」）。 */
    public record PickerStore(Integer storeId, String storeName, long productCount) {}

    /** 商品一行。 */
    public record PickerProduct(Long spuId, String spuName, BigDecimal price, Integer onShelf) {}

    /** 商品分页。 */
    public record PickerProductPage(long total, int page, int size, List<PickerProduct> items) {}
}
