package com.lrj.drools.activity;

import com.lrj.drools.activity.persistence.CatalogProductEntity;
import com.lrj.drools.activity.persistence.CatalogProductRepository;
import com.lrj.drools.activity.persistence.CatalogStoreEntity;
import com.lrj.drools.activity.persistence.CatalogStoreRepository;
import com.lrj.drools.activity.service.StorePickerQueryService;
import com.lrj.drools.activity.service.StorePickerQueryService.PickerProductPage;
import com.lrj.drools.activity.service.StorePickerQueryService.PickerStore;
import com.lrj.drools.activity.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「选店铺→勾商品」picker 的目录浏览读路径证据（列店铺 + 列某店商品），H2 内存库。
 *
 * <p>各用例用<b>独立租户</b>相互隔离（@TenantId 让 count/list 按租户计），不依赖删除顺序。
 * seeder 显式关闭（本测试自造目录数据，不吃启动种子）。
 */
@SpringBootTest
@ActiveProfiles("h2")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:storepicker;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "activity.marketing.seed-catalog-data=false"
})
class StorePickerViewTest {

    @Autowired StorePickerQueryService picker;
    @Autowired CatalogStoreRepository storeRepo;
    @Autowired CatalogProductRepository productRepo;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    /** 列店铺：只列有在架商品的店，含店名 + 商品数；顺带证店名 join 不到时回退 null。 */
    @Test
    void listStoresReturnsAll() {
        TenantContext.set("t-stores");
        storeRepo.save(new CatalogStoreEntity(1, "旗舰店", 1));
        // store 2 故意不建店名行 → 聚合仍列出（有商品），店名回退 null
        product(11L, 1, "耳机", "electronics", "120", 1);
        product(12L, 1, "键盘", "electronics", "180", 1);
        product(21L, 2, "跑鞋", "sports", "260", 1);

        List<PickerStore> stores = picker.stores();
        assertEquals(2, stores.size(), "两家有在架商品的店");
        PickerStore s1 = find(stores, 1);
        assertEquals("旗舰店", s1.storeName());
        assertEquals(2, s1.productCount());
        PickerStore s2 = find(stores, 2);
        assertNull(s2.storeName(), "catalog_store 里没有 store 2 → 店名回退 null（前端显示「店铺 #2」）");
        assertEquals(1, s2.productCount());
    }

    /** 列某店商品：只回该店，不含别店。 */
    @Test
    void listStoreProductsFiltersByStore() {
        TenantContext.set("t-filter");
        product(101L, 1, "A", "c", "10", 1);
        product(102L, 1, "B", "c", "10", 1);
        product(201L, 2, "C", "c", "10", 1);

        PickerProductPage p = picker.products(1, null, 0, 20);
        assertEquals(2, p.total());
        assertTrue(p.items().stream().allMatch(i -> i.spuId() == 101L || i.spuId() == 102L), "只回 store 1 的商品");
    }

    /** keyword 命中子集；分页切片正确、跨页无重叠。 */
    @Test
    void keywordAndPagination() {
        TenantContext.set("t-page");
        for (long i = 0; i < 5; i++) product(300L + i, 1, "蓝牙耳机" + i, "c", "10", 1);
        product(400L, 1, "机械键盘", "c", "10", 1);

        PickerProductPage kw = picker.products(1, "蓝牙", 0, 20);
        assertEquals(5, kw.total(), "keyword「蓝牙」命中 5 个耳机、不含键盘");

        PickerProductPage p0 = picker.products(1, "蓝牙", 0, 2);
        PickerProductPage p1 = picker.products(1, "蓝牙", 1, 2);
        assertEquals(5, p0.total());
        assertEquals(2, p0.items().size());
        assertEquals(2, p1.items().size());
        long distinct = List.of(p0, p1).stream().flatMap(pp -> pp.items().stream()).map(i -> i.spuId()).distinct().count();
        assertEquals(4, distinct, "两页 4 条不重叠");
    }

    /** 空目录租户返回空（不是 500、不跨租户回退）。 */
    @Test
    void emptyTenantReturnsEmpty() {
        TenantContext.set("t-empty");
        assertTrue(picker.stores().isEmpty());
        assertEquals(0, picker.products(1, null, 0, 20).total());
    }

    /** 下架商品(on_shelf=0)不进店铺聚合、也不进商品列表。 */
    @Test
    void offShelfExcluded() {
        TenantContext.set("t-shelf");
        product(501L, 1, "在架", "c", "10", 1);
        product(502L, 1, "下架", "c", "10", 0);

        assertEquals(1, find(picker.stores(), 1).productCount(), "只数在架");
        PickerProductPage p = picker.products(1, null, 0, 20);
        assertEquals(1, p.total());
        assertEquals(501L, p.items().get(0).spuId());
    }

    // ------------------------------------------------------------------ helper

    private void product(long spuId, int storeId, String name, String category, String price, int onShelf) {
        productRepo.save(new CatalogProductEntity(spuId, storeId, name, category, new BigDecimal(price), null, onShelf));
    }

    private PickerStore find(List<PickerStore> stores, int storeId) {
        return stores.stream().filter(s -> s.storeId() == storeId).findFirst()
                .orElseThrow(() -> new AssertionError("未找到店铺 " + storeId));
    }
}
