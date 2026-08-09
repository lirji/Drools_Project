package com.lrj.drools.activity;

import com.lrj.drools.activity.domain.ActivityCreateRequest;
import com.lrj.drools.activity.service.ActivityMarketingService;
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
        "activity.marketing.seed-demo-data=false"
})
@DisplayName("折扣型写平面校验：不封顶就不许上")
class BenefitFormValidationTest {

    @Autowired ActivityMarketingService marketing;

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
    @DisplayName("合法的折扣型可以创建；金额型完全不受影响（旧行为零变更）")
    void happyPaths() {
        assertDoesNotThrow(() -> marketing.create(zhe(new BigDecimal("8.5"), new BigDecimal("50"))));
        assertDoesNotThrow(() -> marketing.create(base(new BigDecimal("10"), "元", null, null)));
        assertDoesNotThrow(() -> marketing.create(base(new BigDecimal("10"), null, null, null)));
    }

    // ---- helpers ----

    private ActivityCreateRequest zhe(BigDecimal zheValue, BigDecimal cap) {
        return base(zheValue, "折", null, cap);
    }

    private ActivityCreateRequest base(BigDecimal amount, String unit, String ladder, BigDecimal cap) {
        long now = System.currentTimeMillis();
        return new ActivityCreateRequest(
                null, null, "形态校验-" + (++spu), "benefit-form", 1, null,
                now - 3_600_000L, now + 3_600_000L, 1, null, 1, 100,
                1, amount, unit, ladder, "MAX",
                null, List.of(new ActivityCreateRequest.SpuBinding(1, spu)), null, null,
                cap);
    }
}
