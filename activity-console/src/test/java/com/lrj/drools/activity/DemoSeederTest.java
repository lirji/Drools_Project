package com.lrj.drools.activity;

import com.lrj.drools.activity.persistence.DemoProductRepository;
import com.lrj.drools.activity.persistence.DemoStoreRepository;
import com.lrj.drools.activity.tenant.TenantContext;
import com.lrj.drools.activity.tenant.TenantProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ActivityDemoSeeder} 多租户播种的证据（seeder 显式**开启**，仅此测试）。
 *
 * <p>验：① 启动期给 {@code __dev__} 与 {@code acme} 各造了 store+product；② 未播种的租户目录为空（@TenantId 隔离）；
 * ③ 再跑 {@code run()} 幂等、count 不变。照 {@code DistrictSeederTest} 的手法。
 */
@SpringBootTest
@ActiveProfiles("h2")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:demoseeder;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "activity.marketing.seed-demo-data=true"
})
class DemoSeederTest {

    @Autowired ActivityDemoSeeder seeder;
    @Autowired DemoStoreRepository storeRepo;
    @Autowired DemoProductRepository productRepo;
    @Autowired TenantProperties tenantProps;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void seedsDevDefaultAndAcme() {
        TenantContext.set(tenantProps.getDevDefault());
        assertEquals(2, storeRepo.count(), "__dev__ 造 2 家店");
        assertEquals(6, productRepo.count(), "__dev__ 造 6 个商品（9101-9104 + 9201-9202）");

        TenantContext.set("acme");
        assertEquals(2, storeRepo.count(), "acme 造 2 家店");
        assertEquals(4, productRepo.count(), "acme 造 4 个商品");
    }

    @Test
    void unseededTenantIsEmpty() {
        TenantContext.set("beta-unseeded");
        assertEquals(0, storeRepo.count(), "未播种租户店铺目录为空（@TenantId 隔离）");
        assertEquals(0, productRepo.count());
    }

    @Test
    void rerunIsIdempotent() {
        TenantContext.set(tenantProps.getDevDefault());
        long storesBefore = storeRepo.count();
        long productsBefore = productRepo.count();

        TenantContext.clear();
        seeder.run(); // 再跑一次：每租户 storeRepo.count()>0 → 跳过

        TenantContext.set(tenantProps.getDevDefault());
        assertEquals(storesBefore, storeRepo.count(), "幂等：店铺 count 不变");
        assertEquals(productsBefore, productRepo.count(), "幂等：商品 count 不变");
        assertTrue(storesBefore > 0);
    }
}
