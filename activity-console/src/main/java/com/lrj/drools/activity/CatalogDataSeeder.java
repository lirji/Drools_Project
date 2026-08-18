package com.lrj.drools.activity;

import com.lrj.drools.activity.persistence.CatalogProductEntity;
import com.lrj.drools.activity.persistence.CatalogProductRepository;
import com.lrj.drools.activity.persistence.CatalogStoreEntity;
import com.lrj.drools.activity.persistence.CatalogStoreRepository;
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
import java.util.List;

/**
 * 可选的商品与店铺目录初始化器。用于本地开发或验收环境快速准备「商品池自动圈选」和
 * 「选店铺→勾商品」所需的最小目录数据；正式环境应由商品、门店主数据同步链路负责写入。
 *
 * <p>仅当 {@code activity.marketing.seed-catalog-data=true} 时启用。默认关闭，防止应用启动时
 * 向正式数据库写入初始化目录；需要该数据的测试会显式开启。
 *
 * <p><b>多租户</b>：本地前端默认租户是 {@code acme}，而后端 dev-default 是 {@code __dev__}——两者都需要目录数据，
 * 否则默认打开编辑器的 picker 空目录。故遍历 {@code [__dev__, acme]} 逐租户播种，每租户各自幂等
 * （{@code storeRepo.count()} 因 {@code @TenantId} 按租户计）。{@code e2ev-*} 临时 e2e 租户是运行期动态生成的，
 * 启动期 {@code CommandLineRunner} 抓不到，也无需——那类租户下靠「手填兜底」录入。
 *
 * <p><b>幂等 + 可重入</b>：用 {@code save}（按 PK upsert），对已存在的 {@code __dev__} 9101-9104 是无害更新；
 * 商品的 {@code store_id} 保持不变（9101-9104 仍在 store 1，e2e/池断言依赖），多店铺靠<b>新增</b> store 2。
 * 商品池只在 {@code __dev__} 造（{@code acme} 的活动是库里既有数据，不叠 pool）。
 */
@Component
@ConditionalOnProperty(name = "activity.marketing.seed-catalog-data", havingValue = "true")
public class CatalogDataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(CatalogDataSeeder.class);

    private final CatalogProductRepository productRepo;
    private final CatalogStoreRepository storeRepo;
    private final ProductPoolRepository poolRepo;
    private final ProductPoolRuleRepository poolRuleRepo;
    private final TenantProperties tenantProps;

    public CatalogDataSeeder(CatalogProductRepository productRepo,
                             CatalogStoreRepository storeRepo,
                             ProductPoolRepository poolRepo,
                             ProductPoolRuleRepository poolRuleRepo,
                             TenantProperties tenantProps) {
        this.productRepo = productRepo;
        this.storeRepo = storeRepo;
        this.poolRepo = poolRepo;
        this.poolRuleRepo = poolRuleRepo;
        this.tenantProps = tenantProps;
    }

    @Override
    public void run(String... args) {
        // 启动期无请求上下文 → 逐个预置租户显式初始化，让请求能读到本租户目录数据。
        for (String tenant : List.of(tenantProps.getDevDefault(), "acme")) {
            TenantContext.runWith(tenant, () -> seedTenant(tenant));
        }
    }

    private void seedTenant(String tenant) {
        if (storeRepo.count() > 0) {
            return; // 该租户已有店铺目录，幂等跳过
        }
        // store_id 与 catalog_product.spu_id 一样是全局业务键（单列主键），跨租户不能复用同一个 id，
        // 否则 merge 撞主键、后播的租户一行落不下。故各租户用不同 store_id 段：__dev__ 用 1/2、acme 用 3/4。
        if (tenant.equals(tenantProps.getDevDefault())) {
            // __dev__：保留原 9101-9104 全在 store 1（e2e / 商品池圈选断言依赖），新增 store 2 两个商品
            storeRepo.save(new CatalogStoreEntity(1, "旗舰店", 1));
            storeRepo.save(new CatalogStoreEntity(2, "折扣店", 1));
            productRepo.save(new CatalogProductEntity(9101L, 1, "蓝牙耳机", "electronics", new BigDecimal("120"), "hot,new", 1));
            productRepo.save(new CatalogProductEntity(9102L, 1, "机械键盘", "electronics", new BigDecimal("180"), "hot", 1));
            productRepo.save(new CatalogProductEntity(9103L, 1, "布艺沙发", "furniture", new BigDecimal("150"), "hot", 1));
            productRepo.save(new CatalogProductEntity(9104L, 1, "数据线", "electronics", new BigDecimal("30"), "cheap", 1));
            productRepo.save(new CatalogProductEntity(9201L, 2, "跑步鞋", "sports", new BigDecimal("260"), "hot", 1));
            productRepo.save(new CatalogProductEntity(9202L, 2, "瑜伽垫", "sports", new BigDecimal("90"), "new", 1));

            ProductPoolEntity pool = new ProductPoolEntity();
            pool.setPoolName("电子促销池"); pool.setBizLine("mall"); pool.setPoolType(1); pool.setStatus(1);
            pool.setRemark("目录初始化：电子类 100~200 元"); pool.setIsDel(0);
            Instant now = Instant.now();
            pool.setCreatedStime(now); pool.setModifiedStime(now);
            pool = poolRepo.save(pool);

            ProductPoolRuleEntity rule = new ProductPoolRuleEntity();
            rule.setPoolId(pool.getId());
            rule.setMinPrice(new BigDecimal("100")); rule.setMaxPrice(new BigDecimal("200"));
            rule.setCategories("electronics"); rule.setEnabled(1); rule.setIsDel(0);
            rule.setCreatedStime(now); rule.setModifiedStime(now);
            poolRuleRepo.save(rule);
            log.info("[CatalogDataSeeder] __dev__: 2 店(1/2) + 6 商品 + 商品池 poolId={}", pool.getId());
        } else {
            // acme（auth 档主租户）：store_id 用 3/4（全局唯一），造 2 店各 2 商品，不叠 pool
            storeRepo.save(new CatalogStoreEntity(3, "acme 旗舰店", 1));
            storeRepo.save(new CatalogStoreEntity(4, "acme 折扣店", 1));
            productRepo.save(new CatalogProductEntity(8101L, 3, "智能手表", "electronics", new BigDecimal("899"), "hot", 1));
            productRepo.save(new CatalogProductEntity(8102L, 3, "保温杯", "home", new BigDecimal("59"), "new", 1));
            productRepo.save(new CatalogProductEntity(8201L, 4, "蓝牙音箱", "electronics", new BigDecimal("320"), "hot", 1));
            productRepo.save(new CatalogProductEntity(8202L, 4, "双肩包", "bags", new BigDecimal("199"), "cheap", 1));
            log.info("[CatalogDataSeeder] {}: 2 店(3/4) + 4 商品（无商品池）", tenant);
        }
    }
}
