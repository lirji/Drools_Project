package com.lrj.drools.activity;

import com.lrj.drools.activity.domain.BenefitForm;
import com.lrj.drools.activity.engine.BenefitMath;
import com.lrj.drools.activity.persistence.ActivityManageEntity;
import com.lrj.drools.activity.persistence.ActivityManageRepository;
import com.lrj.drools.activity.service.ActivityMarketingService;
import com.lrj.drools.activity.tenant.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 一口价（秒杀）＋库存抢占。
 *
 * <p>这批测试真正要证的只有一条：**并发下不超发**。其余（算钱、边界）是配套。
 * 超发这类 bug 的特征是低并发下永远测不出来、上线当天大促流量一到就必现，
 * 所以必须用真并发压一遍，而不是"看代码觉得对"。
 */
@SpringBootTest
@ActiveProfiles("h2")
// 本测试只压库存、不需要目录数据，显式关掉以固定测试意图，避免未来配置默认值变化带来副作用。
@org.springframework.test.context.TestPropertySource(properties = "activity.marketing.seed-catalog-data=false")
class FixedPriceAndClaimTest {

    @Autowired ActivityMarketingService service;
    @Autowired ActivityManageRepository manageRepo;

    @BeforeEach
    void setTenant() {
        TenantContext.set("acme");
    }

    private String seedActivity(int inventory) {
        String id = "ACT-SECKILL-" + System.nanoTime();
        ActivityManageEntity e = new ActivityManageEntity();
        e.setActivityId(id);
        e.setActivityName("秒杀-" + id);
        e.setActivityType(1);
        e.setBizLine("mall");
        e.setActivityStatus(1);
        e.setVersion(1);
        e.setIsDel(0);
        e.setInventory(inventory);
        e.setActivityStartTime(Instant.now().minusSeconds(60));
        e.setActivityEndTime(Instant.now().plusSeconds(3600));
        e.setCreatedStime(Instant.now());
        e.setModifiedStime(Instant.now());
        manageRepo.saveAndFlush(e);
        return id;
    }

    private int remaining(String id) {
        return manageRepo.findFirstByActivityIdAndVersionAndIsDel(id, 1, 0)
                .map(ActivityManageEntity::getInventory).orElse(-1);
    }

    @Nested
    @DisplayName("防超发（这个功能的全部意义）")
    class NoOversell {

        @Test
        @DisplayName("100 线程抢 10 件：恰好 10 个成功，余量归零，绝不为负")
        void concurrentClaimNeverOversells() throws Exception {
            String id = seedActivity(10);
            int threads = 100;
            ExecutorService pool = Executors.newFixedThreadPool(32);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            AtomicInteger ok = new AtomicInteger();

            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        TenantContext.set("acme");
                        if (service.claimInventory(id, 1, 1).ok()) ok.incrementAndGet();
                    } catch (Exception ignored) {
                        // 抢不到不算错；这里只统计成功数
                    } finally {
                        TenantContext.clear();
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(60, TimeUnit.SECONDS)).as("并发任务应在超时前跑完").isTrue();
            pool.shutdownNow();

            assertThat(ok.get()).as("成功数必须恰好等于库存，多一个就是超发").isEqualTo(10);
            assertThat(remaining(id)).as("余量必须归零且绝不为负").isZero();
        }

        @Test
        @DisplayName("余量不足时整单失败，不做部分扣减")
        void partialClaimRejected() {
            String id = seedActivity(3);
            assertThat(service.claimInventory(id, 1, 5).ok()).isFalse();
            assertThat(remaining(id)).as("失败不应改动余量").isEqualTo(3);
            assertThat(service.claimInventory(id, 1, 3).ok()).isTrue();
            assertThat(remaining(id)).isZero();
        }
    }

    @Nested
    @DisplayName("claim 的边界与契约")
    class ClaimContract {

        @Test
        @DisplayName("不存在的活动 / 非正数量 / 空 id 一律失败且不抛异常")
        void guards() {
            assertThat(service.claimInventory("NOPE-404", 1, 1).ok()).isFalse();
            assertThat(service.claimInventory(null, 1, 1).ok()).isFalse();
            assertThat(service.claimInventory(seedActivity(5), 1, 0).ok()).isFalse();
            assertThat(service.claimInventory(seedActivity(5), 1, -1).ok()).isFalse();
        }

        @Test
        @DisplayName("不传版本时打到最高版")
        void defaultsToLatestVersion() {
            String id = seedActivity(2);
            var r = service.claimInventory(id, null, 1);
            assertThat(r.ok()).isTrue();
            assertThat(r.version()).isEqualTo(1);
        }

        @Test
        @DisplayName("**不幂等**：同一调用连做两次会扣两次——调用方必须知道这一点")
        void notIdempotentByDesign() {
            String id = seedActivity(2);
            assertThat(service.claimInventory(id, 1, 1).ok()).isTrue();
            assertThat(service.claimInventory(id, 1, 1).ok()).isTrue();
            assertThat(remaining(id)).isZero();
        }
    }

    @Nested
    @DisplayName("一口价算钱")
    class FixedPriceMath {

        @Test
        @DisplayName("减免 = 订单金额 − 一口价")
        void basic() {
            assertThat(BenefitMath.fixedPriceDiscount(new BigDecimal("100"), new BigDecimal("9.9")))
                    .isEqualByComparingTo(new BigDecimal("90.10"));
            assertThat(BenefitMath.fixedPriceDiscount(new BigDecimal("500"), new BigDecimal("9.9")))
                    .isEqualByComparingTo(new BigDecimal("490.10"));
        }

        @Test
        @DisplayName("订单比秒杀价还便宜 → 不适用（不是减 0，更不是负减免）")
        void cheaperOrderNotApplicable() {
            assertThat(BenefitMath.fixedPriceDiscount(new BigDecimal("5"), new BigDecimal("9.9"))).isNull();
            assertThat(BenefitMath.fixedPriceDiscount(new BigDecimal("9.9"), new BigDecimal("9.9"))).isNull();
        }

        @Test
        @DisplayName("缺订单金额 / 负价 → 不可计算")
        void guards() {
            assertThat(BenefitMath.fixedPriceDiscount(null, new BigDecimal("9.9"))).isNull();
            assertThat(BenefitMath.fixedPriceDiscount(new BigDecimal("100"), null)).isNull();
            assertThat(BenefitMath.fixedPriceDiscount(new BigDecimal("100"), new BigDecimal("-1"))).isNull();
        }

        @Test
        @DisplayName("「价」是受控单位，且与「元」「折」互不混淆")
        void unitDiscrimination() {
            assertThat(BenefitForm.of("价")).isEqualTo(BenefitForm.FIXED_PRICE);
            assertThat(BenefitForm.of("元")).isEqualTo(BenefitForm.AMOUNT);
            assertThat(BenefitForm.of("折")).isEqualTo(BenefitForm.RATIO_ZHE);
            assertThat(BenefitForm.of("块")).as("拼错的单位回落金额型，不猜").isEqualTo(BenefitForm.AMOUNT);
            assertThat(BenefitForm.isSupportedUnit("价")).isTrue();
            assertThat(BenefitForm.isSupportedUnit("块")).isFalse();
        }
    }
}
