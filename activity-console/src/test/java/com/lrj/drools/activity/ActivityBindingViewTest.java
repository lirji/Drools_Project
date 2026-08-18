package com.lrj.drools.activity;

import com.lrj.drools.activity.persistence.ActivitySpuBindingEntity;
import com.lrj.drools.activity.persistence.ActivitySpuBindingRepository;
import com.lrj.drools.activity.persistence.CatalogProductEntity;
import com.lrj.drools.activity.persistence.CatalogProductRepository;
import com.lrj.drools.activity.service.ActivityMarketingService;
import com.lrj.drools.activity.service.ActivityMarketingService.SpuBindingPage;
import com.lrj.drools.activity.service.ActivityMarketingService.SpuBindingRow;
import com.lrj.drools.activity.service.ActivityMarketingService.StoreBindingView;
import com.lrj.drools.activity.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 详情回显「绑定商品」两级读路径的行为证据（店铺聚合 + 店铺下钻分页），H2 内存库。
 *
 * <p>这些场景 create 路径造不出——create 只落手动绑定（bindSource=0），而爆炸源与失效行来自
 * 自动绑定（bindSource=1，商品池物化）与商品下架翻 effective=0。故直接 seed 绑定行覆盖：
 * 聚合口径（含失效计 spuCount、只数生效计 effectiveCount，D5）、null 店铺桶（D7）、
 * isDel 过滤、下钻分页切片、商品名一页批量补（join 不到回退 null）。
 *
 * <p>各用例用<b>独立 activityId</b>相互隔离：同一 @SpringBootTest 上下文共享一个 H2 库、
 * 用例间不回滚，靠 activityId 谓词天然分隔，不依赖删除顺序。
 */
@SpringBootTest
@ActiveProfiles("h2")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:bindingview;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "activity.marketing.seed-catalog-data=false"
})
class ActivityBindingViewTest {

    private static final String TENANT = "t-binding-view";
    private static final int V = 1;

    @Autowired ActivityMarketingService marketing;
    @Autowired ActivitySpuBindingRepository bindingRepo;
    @Autowired CatalogProductRepository catalogProductRepo;

    @BeforeEach
    void setTenant() {
        TenantContext.set(TENANT);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    /** 店铺聚合：含失效计 spuCount，只数生效计 effectiveCount；isDel=1 不计；null 店铺自成一桶。 */
    @Test
    void storeAggregationCounts() {
        String act = "ACT-AGG";
        // 店铺 10：3 条未删（其中 1 条失效）→ spuCount=3, effectiveCount=2
        bind(act, 10, 1001L, 0, 1);
        bind(act, 10, 1002L, 1, 1);
        bind(act, 10, 1003L, 1, 0);   // 失效
        // 店铺 20：2 条全生效 → spuCount=2, effectiveCount=2
        bind(act, 20, 2001L, 0, 1);
        bind(act, 20, 2002L, 1, 1);
        // 店铺 null（未指定门店）：1 条 → spuCount=1
        bind(act, null, 3001L, 0, 1);
        // 一条已软删：不计入任何口径
        ActivitySpuBindingEntity del = row(act, 10, 1009L, 1, 1);
        del.setIsDel(1);
        bindingRepo.save(del);

        List<StoreBindingView> stores = marketing.bindingStores(act, V);
        assertEquals(3, stores.size(), "应聚合出 店铺10 / 店铺20 / 未指定门店 三组");

        StoreBindingView s10 = find(stores, 10);
        assertEquals(3, s10.spuCount(), "店铺10 含失效共 3 条");
        assertEquals(2, s10.effectiveCount(), "店铺10 生效 2 条");

        StoreBindingView s20 = find(stores, 20);
        assertEquals(2, s20.spuCount());
        assertEquals(2, s20.effectiveCount());

        StoreBindingView sNull = find(stores, null);
        assertEquals(1, sNull.spuCount(), "null 店铺自成一桶");
        assertEquals(1, sNull.effectiveCount());
    }

    /** 下钻分页：切片正确、total 正确、跨页无重叠。 */
    @Test
    void drillDownPagination() {
        String act = "ACT-PAGE";
        for (long spu = 5001L; spu <= 5005L; spu++) bind(act, 10, spu, 1, 1);   // 单店 5 个 SPU

        SpuBindingPage p0 = marketing.bindingSpus(act, V, 10, 0, 2);
        SpuBindingPage p1 = marketing.bindingSpus(act, V, 10, 1, 2);
        SpuBindingPage p2 = marketing.bindingSpus(act, V, 10, 2, 2);

        assertEquals(5, p0.total());
        assertEquals(2, p0.items().size());
        assertEquals(2, p1.items().size());
        assertEquals(1, p2.items().size(), "最后一页只剩 1 条");

        long distinct = List.of(p0, p1, p2).stream()
                .flatMap(p -> p.items().stream())
                .map(SpuBindingRow::spuId).distinct().count();
        assertEquals(5, distinct, "跨页不应有重叠");
    }

    /** 商品名/价一页批量补：catalog_product 有的填名+价，没有的回退 null（前端显示裸 SPU 编号）。 */
    @Test
    void drillDownEnrichesNamesAndFallsBack() {
        String act = "ACT-NAME";
        catalogProductRepo.save(new CatalogProductEntity(6001L, 10, "蓝牙耳机", "electronics", new BigDecimal("120"), null, 1));
        bind(act, 10, 6001L, 1, 1);   // 有商品档
        bind(act, 10, 6002L, 1, 1);   // catalog_product 里没有 → 回退

        SpuBindingPage page = marketing.bindingSpus(act, V, 10, 0, 20);
        SpuBindingRow named = page.items().stream().filter(r -> r.spuId() == 6001L).findFirst().orElseThrow();
        SpuBindingRow bare = page.items().stream().filter(r -> r.spuId() == 6002L).findFirst().orElseThrow();

        assertEquals("蓝牙耳机", named.spuName());
        assertEquals(0, new BigDecimal("120").compareTo(named.price()));
        assertNull(bare.spuName(), "查不到商品档时回退 null，由前端显示裸 SPU 编号");
        assertNull(bare.price());
    }

    /** 下钻不过滤 effective：失效行照样出现在明细里，逐行带 effective 让运营自查（D5）。 */
    @Test
    void drillDownShowsFailedRows() {
        String act = "ACT-FAIL";
        bind(act, 10, 7001L, 1, 1);
        bind(act, 10, 7002L, 1, 0);   // 失效
        SpuBindingPage page = marketing.bindingSpus(act, V, 10, 0, 20);
        assertEquals(2, page.total(), "失效行也在下钻明细里");
        SpuBindingRow failed = page.items().stream().filter(r -> r.spuId() == 7002L).findFirst().orElseThrow();
        assertEquals(0, failed.effective());
    }

    /** null 店铺桶可下钻：storeId 传 null 命中「未指定门店」行，不误命中有 store 的行。 */
    @Test
    void nullStoreBucketDrillDown() {
        String act = "ACT-NULLBK";
        bind(act, null, 8001L, 0, 1);
        bind(act, 10, 8002L, 0, 1);
        SpuBindingPage page = marketing.bindingSpus(act, V, null, 0, 20);
        assertEquals(1, page.total(), "null 桶只应含未指定门店的那一行");
        assertEquals(8001L, page.items().get(0).spuId());
    }

    /** 空活动（有版本无绑定）聚合返回空；不存在的活动按 latestDraftVersion 解析走 IAE→400。 */
    @Test
    void emptyAndMissing() {
        String act = "ACT-EMPTY";
        assertTrue(marketing.bindingStores(act, V).isEmpty(), "无绑定 → 聚合空");
        assertEquals(0, marketing.bindingSpus(act, V, 10, 0, 20).total());

        assertThrows(IllegalArgumentException.class,
                () -> marketing.bindingStores("NO-SUCH", null),
                "version 缺省时按 latestDraftVersion 解析，活动不存在应抛 IAE（controller 兜成 400）");
        assertThrows(IllegalArgumentException.class,
                () -> marketing.bindingSpus("NO-SUCH", null, 10, 0, 20));
    }

    // ------------------------------------------------------------------ helper

    private void bind(String activityId, Integer storeId, long spuId, int bindSource, int effective) {
        bindingRepo.save(row(activityId, storeId, spuId, bindSource, effective));
    }

    private ActivitySpuBindingEntity row(String activityId, Integer storeId, long spuId, int bindSource, int effective) {
        ActivitySpuBindingEntity e = new ActivitySpuBindingEntity();
        e.setActivityId(activityId);
        e.setStoreId(storeId);
        e.setSpuId(spuId);
        e.setVersion(V);
        e.setBindSource(bindSource);
        e.setPoolId(bindSource == 1 ? 1L : null);
        e.setEffective(effective);
        e.setIsDel(0);
        Instant now = Instant.now();
        e.setCreatedStime(now);
        e.setModifiedStime(now);
        return e;
    }

    private StoreBindingView find(List<StoreBindingView> stores, Integer storeId) {
        return stores.stream()
                .filter(s -> storeId == null ? s.storeId() == null : storeId.equals(s.storeId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到店铺聚合组 storeId=" + storeId));
    }
}
