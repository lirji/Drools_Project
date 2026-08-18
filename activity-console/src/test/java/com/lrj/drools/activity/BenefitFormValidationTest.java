package com.lrj.drools.activity;

import com.lrj.drools.activity.domain.ActivityCreateRequest;
import com.lrj.drools.activity.engine.BenefitMath;
import com.lrj.drools.activity.persistence.ActivityRuleEntity;
import com.lrj.drools.activity.persistence.ActivityRuleRepository;
import com.lrj.drools.activity.service.ActivityMarketingService;
import com.lrj.drools.activity.service.ActivityMarketingService.CreateResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 折扣型的**写平面闸门**。
 *
 * <p>背景：`redPackageAmountUnit` 在此之前是零校验的自由文本——因为从来没有任何计算读过它。
 * 引擎开始按它判别形态之后，这个字段就变成了「决定发多少钱」的开关，
 * 于是写入侧必须同时收紧，否则一个拼错的单位或一张没有封顶的折扣券就能造成静默超发。
 *
 * <p>这些用例守的都是<b>钱的上界</b>，不是表单体验。
 */
@SpringBootTest
@ActiveProfiles("h2")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:actbenefitform;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "activity.marketing.seed-catalog-data=false"
})
@DisplayName("折扣型写平面校验：不封顶就不许上")
class BenefitFormValidationTest {

    @Autowired ActivityMarketingService marketing;
    @Autowired ActivityRuleRepository ruleRepo;

    private static long spu = 700_000L;

    @Test
    @DisplayName("折扣型必须带封顶——不封顶等于无上限支出")
    void ratioRequiresCap() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> marketing.create(zhe(new BigDecimal("8"), null)));
        assertTrue(e.getMessage().contains("封顶"), e.getMessage());
    }

    @Test
    @DisplayName("封顶为 0 或负同样拒绝（0 不是「不发」而是配错了）")
    void ratioRejectsNonPositiveCap() {
        assertThrows(IllegalArgumentException.class, () -> marketing.create(zhe(new BigDecimal("8"), BigDecimal.ZERO)));
        assertThrows(IllegalArgumentException.class, () -> marketing.create(zhe(new BigDecimal("8"), new BigDecimal("-1"))));
    }

    @Test
    @DisplayName("折数必须在 (0,10)：10 折=不打折、0 折=白送，都按配错拒绝")
    void ratioRejectsZheOutOfRange() {
        for (String bad : new String[]{"0", "10", "10.5", "-1"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> marketing.create(zhe(new BigDecimal(bad), new BigDecimal("50"))),
                    "折数 " + bad + " 应被拒");
        }
    }

    @Test
    @DisplayName("单位白名单：拼错的单位一律拒，不许静默当成金额型")
    void rejectsUnknownUnit() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> marketing.create(base(new BigDecimal("8"), "%", null, null)));
        assertTrue(e.getMessage().contains("单位"), e.getMessage());
    }

    @Test
    @DisplayName("折扣型不许同时配阶梯——阶梯的 reward 是元，两种形态打架")
    void ratioRejectsLadder() {
        String tiers = "[{\"min\":0,\"max\":100,\"reward\":5}]";
        assertThrows(IllegalArgumentException.class,
                () -> marketing.create(base(new BigDecimal("8"), "折", tiers, new BigDecimal("50"))));
    }

    @Test
    @DisplayName("金额型不许填封顶——填了说明配的人以为它有用")
    void amountRejectsCap() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> marketing.create(base(new BigDecimal("10"), "元", null, new BigDecimal("50"))));
        assertTrue(e.getMessage().contains("折扣型"), e.getMessage());
    }

    @Test
    @DisplayName("折数 9.995 校验能过但列是 scale=2 —— 落库后必须仍然安全（fail-closed，不是多发）")
    void scaleTruncationLandsFailClosed() {
        // 9.995 < 10 所以校验放行，但 red_package_amount 是 DECIMAL(12,2)，落库会被规整成 10.00。
        // 关键不是「能不能存」，而是**存完之后引擎怎么算**：10 折已越界 → ratioDiscount 返回 null → 不给优惠。
        // 若哪天改成了向下取整或放宽越界判定，这条会红——那时要重新想清楚该发多少钱。
        CreateResult r = marketing.create(zhe(new BigDecimal("9.995"), new BigDecimal("50")));
        ActivityRuleEntity rule = ruleRepo
                .findByActivityIdAndVersionAndIsDel(r.activityId(), r.version(), 0).get(0);

        BigDecimal stored = rule.getRedPackageAmount();
        BigDecimal off = BenefitMath.ratioDiscount(new BigDecimal("100"), stored, new BigDecimal("50"));
        assertTrue(off == null || off.compareTo(new BigDecimal("100")) <= 0,
                "落库规整后若折数越界应算不出优惠（null），绝不能变成一个更大的减免。实际 stored=" + stored + " off=" + off);
    }

    @Test
    @DisplayName("合法的折扣型可以创建；金额型完全不受影响（旧行为零变更）")
    void happyPaths() {
        assertDoesNotThrow(() -> marketing.create(zhe(new BigDecimal("8.5"), new BigDecimal("50"))));
        assertDoesNotThrow(() -> marketing.create(base(new BigDecimal("10"), "元", null, null)));
        assertDoesNotThrow(() -> marketing.create(base(new BigDecimal("10"), null, null, null)));
    }

    // ================================================================ range 列的三用途分叉
    //
    // redPackageRangeAmount 是三用途列：数组=阶梯、{"min","max"}=随机区间、{"nth":N}=第 N 件折。
    // 读侧（LadderRangeParser 只认数组 / RandomRangeParser 只认对象）早就是这么分的，
    // 写侧却一度无条件按阶梯解析 —— 于是「第二件半价」和「随机金额红包」这两种合法配置
    // 在写入口 100% 被判成「阶梯分档 JSON 无有效档位」。下面这组就是钉住这条分叉。

    @Test
    @DisplayName("第 N 件折可以创建——{\"nth\":2} 不是「无效的阶梯」")
    void nthCanBeCreated() {
        assertDoesNotThrow(() -> marketing.create(nth(new BigDecimal("5"), "{\"nth\":2}")));
    }

    @Test
    @DisplayName("第 N 件折的 N 必须 ≥2：缺 nth / N=1 / 写成数组都拒")
    void nthRejectsBadN() {
        for (String bad : new String[]{"{\"nth\":1}", "{\"nth\":0}", "{}", "[{\"min\":0,\"reward\":5}]"}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> marketing.create(nth(new BigDecimal("5"), bad)), "nth JSON " + bad + " 应被拒");
            assertTrue(e.getMessage().contains("第 N 件折"), e.getMessage());
        }
    }

    @Test
    @DisplayName("第 N 件折的折数是折数不是钱：必填且须在 (0,10)")
    void nthValidatesZhe() {
        assertThrows(IllegalArgumentException.class, () -> marketing.create(nth(null, "{\"nth\":2}")));
        for (String bad : new String[]{"0", "10", "-1"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> marketing.create(nth(new BigDecimal(bad), "{\"nth\":2}")), "折数 " + bad + " 应被拒");
        }
    }

    @Test
    @DisplayName("随机金额红包可以创建——{\"min\",\"max\"} 不是「无效的阶梯」")
    void randomRangeCanBeCreated() {
        assertDoesNotThrow(() -> marketing.create(random(new BigDecimal("10"), "{\"min\":5,\"max\":20}")));
    }

    @Test
    @DisplayName("随机区间非法一律拒：min>max / 负数 / 缺字段（决策侧算不出就是不发，不许静默上线）")
    void randomRangeRejectsBad() {
        for (String bad : new String[]{"{\"min\":20,\"max\":5}", "{\"min\":-1,\"max\":5}", "{\"min\":5}"}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> marketing.create(random(new BigDecimal("10"), bad)), "区间 " + bad + " 应被拒");
            assertTrue(e.getMessage().contains("随机金额"), e.getMessage());
        }
    }

    @Test
    @DisplayName("阶梯仍按老规矩校验：数组解析不出档位照样拒（本次分叉不放松旧闸门）")
    void ladderStillGuarded() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> marketing.create(base(null, "元", "[{\"min\":0,\"max\":100}]", null)));
        assertTrue(e.getMessage().contains("阶梯分档"), e.getMessage());
    }

    @Test
    @DisplayName("阶梯每一档的奖励都要过 [0, 999999]——负奖励等于负优惠，会一路发到下游")
    void ladderTierRewardGuarded() {
        // 一档合法一档为负：护栏必须逐档看，不能只看第一档
        String negative = "[{\"min\":0,\"max\":100,\"reward\":5},{\"min\":100,\"max\":null,\"reward\":-50}]";
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> marketing.create(base(null, "元", negative, null)));
        assertTrue(e.getMessage().contains("阶梯档奖励"), e.getMessage());

        String tooBig = "[{\"min\":0,\"max\":null,\"reward\":1000000}]";
        assertThrows(IllegalArgumentException.class, () -> marketing.create(base(null, "元", tooBig, null)));

        // 0 元档是合法配置（首档不减），不能被一起误杀
        assertDoesNotThrow(() -> marketing.create(base(null, "元",
                "[{\"min\":0,\"max\":100,\"reward\":0},{\"min\":100,\"max\":null,\"reward\":20}]", null)));
    }

    @Test
    @DisplayName("一口价：卖价必须 >0、库存至少 1，且不许配 range")
    void fixedPriceGuards() {
        assertDoesNotThrow(() -> marketing.create(base(new BigDecimal("9.9"), "价", null, null)));
        assertThrows(IllegalArgumentException.class, () -> marketing.create(base(BigDecimal.ZERO, "价", null, null)));
        for (Integer inventory : new Integer[]{null, 0, -1}) {
            IllegalArgumentException inventoryError = assertThrows(IllegalArgumentException.class,
                    () -> marketing.create(base(new BigDecimal("9.9"), "价", null, null, 1, inventory)));
            assertTrue(inventoryError.getMessage().contains("库存"), inventoryError.getMessage());
        }
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> marketing.create(base(new BigDecimal("9.9"), "价", "[{\"min\":0,\"reward\":5}]", null)));
        assertTrue(e.getMessage().contains("一口价"), e.getMessage());
    }

    // ---- helpers ----

    private ActivityCreateRequest zhe(BigDecimal zheValue, BigDecimal cap) {
        return base(zheValue, "折", null, cap);
    }

    /** 第 N 件折：单位=件折，amount 是折数，N 在 range 列。takeType 与它无关，传 null（前端也是）。 */
    private ActivityCreateRequest nth(BigDecimal zheValue, String rangeJson) {
        return base(zheValue, "件折", rangeJson, null, null);
    }

    /** 随机金额红包：单位=元 + takeType=2 + 区间对象。 */
    private ActivityCreateRequest random(BigDecimal amount, String rangeJson) {
        return base(amount, "元", rangeJson, null, 2);
    }

    private ActivityCreateRequest base(BigDecimal amount, String unit, String ladder, BigDecimal cap) {
        return base(amount, unit, ladder, cap, 1);
    }

    private ActivityCreateRequest base(BigDecimal amount, String unit, String ladder, BigDecimal cap, Integer takeType) {
        return base(amount, unit, ladder, cap, takeType, 100);
    }

    private ActivityCreateRequest base(BigDecimal amount, String unit, String ladder, BigDecimal cap,
                                       Integer takeType, Integer inventory) {
        long now = System.currentTimeMillis();
        return new ActivityCreateRequest(
                null, null, "形态校验-" + (++spu), "benefit-form", 1, null,
                now - 3_600_000L, now + 3_600_000L, 1, null, 1, inventory,
                takeType, amount, unit, ladder, "MAX",
                null, List.of(new ActivityCreateRequest.SpuBinding(1, spu)), null, null,
                cap);
    }
}
