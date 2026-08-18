package com.lrj.drools.activity;

import com.lrj.drools.activity.persistence.ActivitySpuBindingEntity;
import com.lrj.drools.activity.persistence.ActivitySpuBindingRepository;
import com.lrj.drools.activity.persistence.CatalogProductEntity;
import com.lrj.drools.activity.persistence.CatalogProductRepository;
import com.lrj.drools.activity.service.ActivityMarketingService;
import com.lrj.drools.activity.tenant.TenantContext;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 详情下钻的**无 N+1 证据**：一页明细的 SQL 语句数与该页 SPU 数无关。
 *
 * <p>商品名/价用 {@code findAllById(本页 spuId 集合)} 一次批量补（PK IN + @TenantId 自动）。
 * 若哪天有人改成逐行查 {@code catalog_product}，M=5 的语句数就会比 M=1 多，本测试立刻红。
 * 手法照 {@link DecisionQueryCountTest}：用 Hibernate {@link Statistics} 数真实语句数，
 * 并断言 {@code count>0} 防「统计没开→空断言假绿」。
 */
@SpringBootTest
@ActiveProfiles("h2")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:bindingqcount;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "activity.marketing.seed-catalog-data=false"
})
@DisplayName("下钻查询次数：与该页 SPU 数无关（商品名批量补，无 N+1）")
class BindingViewQueryCountTest {

    private static final String TENANT = "t-bv-qcount";

    @Autowired ActivityMarketingService marketing;
    @Autowired ActivitySpuBindingRepository bindingRepo;
    @Autowired CatalogProductRepository catalogProductRepo;
    @Autowired EntityManagerFactory emf;

    @BeforeEach
    void setTenant() {
        TenantContext.set(TENANT);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private Statistics stats() {
        return emf.unwrap(SessionFactory.class).getStatistics();
    }

    @Test
    void drillDownQueryCountDoesNotGrowWithPageSize() {
        seedStore("ACT-Q1", 91, 1);   // 单店 1 个 SPU
        seedStore("ACT-Q5", 95, 5);   // 单店 5 个 SPU

        long one = countFor("ACT-Q1", 91, 10);
        long five = countFor("ACT-Q5", 95, 10);
        System.out.println("[binding-drilldown] M=1 → " + one + " 条；M=5 → " + five + " 条");

        assertTrue(one > 0, "Hibernate 统计未生效（计数 0）——检查 generate_statistics 配置");
        assertEquals(one, five,
                "一页明细的语句数随 SPU 数增长了（M=1 用 " + one + "，M=5 用 " + five + "）——商品名 N+1 回来了");
    }

    private long countFor(String activityId, int storeId, int size) {
        Statistics st = stats();
        st.clear();
        var page = marketing.bindingSpus(activityId, 1, storeId, 0, size);
        // 触发商品名字段访问，确保补名真的发生（懒加载防线，本例是即时 map 已发生，这里仅稳妥）
        page.items().forEach(r -> r.spuName());
        return st.getPrepareStatementCount();
    }

    private void seedStore(String activityId, int storeId, int spuCount) {
        for (int i = 0; i < spuCount; i++) {
            long spu = (long) storeId * 1000 + i;
            ActivitySpuBindingEntity e = new ActivitySpuBindingEntity();
            e.setActivityId(activityId);
            e.setStoreId(storeId);
            e.setSpuId(spu);
            e.setVersion(1);
            e.setBindSource(1);
            e.setPoolId(1L);
            e.setEffective(1);
            e.setIsDel(0);
            Instant now = Instant.now();
            e.setCreatedStime(now);
            e.setModifiedStime(now);
            bindingRepo.save(e);
            catalogProductRepo.save(new CatalogProductEntity(spu, storeId, "商品" + spu, "c", new BigDecimal("9.9"), null, 1));
        }
    }
}
