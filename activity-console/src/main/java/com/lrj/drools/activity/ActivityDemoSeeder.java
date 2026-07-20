package com.lrj.drools.activity;

import com.lrj.drools.activity.persistence.DemoProductEntity;
import com.lrj.drools.activity.persistence.DemoProductRepository;
import com.lrj.drools.activity.persistence.ProductPoolEntity;
import com.lrj.drools.activity.persistence.ProductPoolRepository;
import com.lrj.drools.activity.persistence.ProductPoolRuleEntity;
import com.lrj.drools.activity.persistence.ProductPoolRuleRepository;
import com.lrj.drools.activity.tenant.TenantContext;
import com.lrj.drools.activity.tenant.TenantProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * demo 种子：给商品池自动圈选造点数据，让前端「商品池」绑定方式在浏览器里也能跑通
 * （否则 {@code demo_product} 空表，autoBoundCount 恒为 0）。
 *
 * 仅当 {@code activity.marketing.seed-demo-data=true} 时启用（application.yml 里开、
 * 测试的 @TestPropertySource 不开 → 不污染 {@code ActivityMarketingFlowTest} 的池断言）。
 * 幂等：只在 {@code demo_product} 空时种一次。运行时会往当前 profile 的库（含 MySQL）写这几条 demo 数据。
 */
@Component
@ConditionalOnProperty(name = "activity.marketing.seed-demo-data", havingValue = "true")
public class ActivityDemoSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(ActivityDemoSeeder.class);

    private final DemoProductRepository productRepo;
    private final ProductPoolRepository poolRepo;
    private final ProductPoolRuleRepository poolRuleRepo;
    private final TenantProperties tenantProps;

    public ActivityDemoSeeder(DemoProductRepository productRepo,
                              ProductPoolRepository poolRepo,
                              ProductPoolRuleRepository poolRuleRepo,
                              TenantProperties tenantProps) {
        this.productRepo = productRepo;
        this.poolRepo = poolRepo;
        this.poolRuleRepo = poolRuleRepo;
        this.tenantProps = tenantProps;
    }

    @Override
    public void run(String... args) {
        // 启动期无请求上下文 → 显式在 dev-default 租户下播种，让 dev 请求（同样 dev-default）能读到这批数据。
        TenantContext.runWith(tenantProps.getDevDefault(), this::seed);
    }

    private void seed() {
        if (productRepo.count() > 0) {
            return; // 已有数据，幂等跳过
        }
        Instant now = Instant.now();

        // demo 商品：电子类 [100,200] 命中；家居 / 低价 不命中
        productRepo.save(new DemoProductEntity(9101L, 1, "蓝牙耳机", "electronics", new BigDecimal("120"), "hot,new", 1));
        productRepo.save(new DemoProductEntity(9102L, 1, "机械键盘", "electronics", new BigDecimal("180"), "hot", 1));
        productRepo.save(new DemoProductEntity(9103L, 1, "布艺沙发", "furniture", new BigDecimal("150"), "hot", 1));
        productRepo.save(new DemoProductEntity(9104L, 1, "数据线", "electronics", new BigDecimal("30"), "cheap", 1));

        ProductPoolEntity pool = new ProductPoolEntity();
        pool.setPoolName("电子促销池"); pool.setBizLine("mall"); pool.setPoolType(1); pool.setStatus(1);
        pool.setRemark("demo 种子：电子类 100~200 元"); pool.setIsDel(0);
        pool.setCreatedStime(now); pool.setModifiedStime(now);
        pool = poolRepo.save(pool);

        ProductPoolRuleEntity rule = new ProductPoolRuleEntity();
        rule.setPoolId(pool.getId());
        rule.setMinPrice(new BigDecimal("100")); rule.setMaxPrice(new BigDecimal("200"));
        rule.setCategories("electronics"); rule.setEnabled(1); rule.setIsDel(0);
        rule.setCreatedStime(now); rule.setModifiedStime(now);
        poolRuleRepo.save(rule);

        log.info("[ActivityDemoSeeder] 已种入 4 个 demo 商品 + 商品池 poolId={}（圈选电子类 9101/9102）。前端「商品池」绑定填此 poolId。", pool.getId());
    }
}
