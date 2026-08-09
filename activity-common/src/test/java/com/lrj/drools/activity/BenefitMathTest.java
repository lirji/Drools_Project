package com.lrj.drools.activity;

import com.lrj.drools.activity.domain.BenefitForm;
import com.lrj.drools.activity.engine.BenefitMath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 折扣数学的纯单测。
 *
 * <p>金标集证明的是「端到端两条路给出同样的钱」，这里证明的是**边界上到底该给多少钱**——
 * 那些不会在正常用例里出现、但一旦出现就会静默发错钱的输入：折数越界、封顶为 0、除不尽。
 */
@DisplayName("折扣数学：折数 → 减免额")
class BenefitMathTest {

    private static BigDecimal d(String v) { return new BigDecimal(v); }
    /** 「大到不会触发」的封顶。封顶是折扣型的必要条件，测试里不能再传 null 表示不封顶 */
    private static final BigDecimal NO_LIMIT = new BigDecimal("99999999");

    @ParameterizedTest(name = "{0} 元打 {1} 折 → 减 {2}（{3}）")
    @CsvSource({
            "100,    8,    20.00, 基准",
            "100,    5,    50.00, 五折减一半",
            "100,    9.9,  1.00,  接近不打折",
            "100,    0.1,  99.00, 接近白送",
            "333.33, 8,    66.66, 除不尽向下取整（66.666 不进位成 66.67）",
            "0.04,   9.9,  0.00,  减免不足一分时给 0 而不是凑成 0.01",
    })
    void computesDiscount(String order, String zhe, String expected, String why) {
        assertEquals(0, BenefitMath.ratioDiscount(d(order), d(zhe), NO_LIMIT).compareTo(d(expected)), why);
    }

    @Test
    @DisplayName("向下取整是**系统性**的：同一笔金额反复算都不会多发一分")
    void roundingNeverFavoursPayout() {
        // 0.005 的尾数在四舍五入下会进位；这里必须被截掉
        BigDecimal off = BenefitMath.ratioDiscount(d("99.99"), d("9.5"), NO_LIMIT);
        assertEquals(0, off.compareTo(d("4.99")), "99.99 × 5% = 4.9995，向下取整应为 4.99 而不是 5.00");
    }

    @ParameterizedTest(name = "折数 {0} 越界 → 不可计算")
    @ValueSource(strings = {"0", "10", "10.01", "-1", "99"})
    void rejectsZheOutOfRange(String zhe) {
        assertNull(BenefitMath.ratioDiscount(d("100"), d(zhe), NO_LIMIT),
                "10 折=不打折、0 折=白送，都更像配错了而不是本意，一律不算");
    }

    @ParameterizedTest
    @NullSource
    @DisplayName("订单金额缺失 → 不可计算（绝不能退回把折数当元发）")
    void nullOrderAmount(BigDecimal order) {
        assertNull(BenefitMath.ratioDiscount(order, d("8"), NO_LIMIT));
    }

    @Test
    @DisplayName("订单金额为 0 或负 → 不可计算")
    void nonPositiveOrderAmount() {
        assertNull(BenefitMath.ratioDiscount(d("0"), d("8"), NO_LIMIT));
        assertNull(BenefitMath.ratioDiscount(d("-5"), d("8"), NO_LIMIT));
    }

    @Test
    @DisplayName("封顶截断")
    void capTruncates() {
        assertEquals(0, BenefitMath.ratioDiscount(d("10000"), d("8"), d("50")).compareTo(d("50")));
    }

    @Test
    @DisplayName("未触及封顶时原样返回")
    void capNotReached() {
        assertEquals(0, BenefitMath.ratioDiscount(d("100"), d("8"), d("50")).compareTo(d("20")));
    }

    @Test
    @DisplayName("**封顶为 0 必须封成 0**，不能落进「不封顶」分支把全额发出去")
    void zeroCapIsNotNoCap() {
        BigDecimal off = BenefitMath.ratioDiscount(d("10000"), d("8"), BigDecimal.ZERO);
        assertEquals(0, off.compareTo(BigDecimal.ZERO),
                "配置里最保守的值反而产生最激进的结果，是标准的 fail-open");
    }

    @Test
    @DisplayName("负封顶按 0 处理，绝不返回负减免（负减免 = 反向加价）")
    void negativeCapClampsToZero() {
        BigDecimal off = BenefitMath.ratioDiscount(d("10000"), d("8"), d("-100"));
        assertEquals(0, off.compareTo(BigDecimal.ZERO));
    }

    @Test
    @DisplayName("**没有封顶 = 不可计算**，不是「不封顶」——写平面只管新写入，读路径必须自己挡住")
    void missingCapIsNotUnlimited() {
        assertNull(BenefitMath.ratioDiscount(d("10000"), d("8"), null),
                "把「没配封顶」解释成「不封顶」是 fail-open：越是数据有问题的时候发得越多");
    }

    @Test
    @DisplayName("形态判别：只有「折」是折扣型，其余（含 null / 拼错）一律回落金额型")
    void formFallsBackToAmount() {
        assertSame(BenefitForm.RATIO_ZHE, BenefitForm.of("折"));
        assertSame(BenefitForm.AMOUNT, BenefitForm.of("元"));
        assertSame(BenefitForm.AMOUNT, BenefitForm.of(null));
        assertSame(BenefitForm.AMOUNT, BenefitForm.of("摺"), "拼错的单位必须按旧行为发钱，不能猜");
        assertSame(BenefitForm.AMOUNT, BenefitForm.of("%"));
    }

    @Test
    @DisplayName("单位白名单：只放行 null / 元 / 折")
    void unitWhitelist() {
        assertEquals(true, BenefitForm.isSupportedUnit(null));
        assertEquals(true, BenefitForm.isSupportedUnit("元"));
        assertEquals(true, BenefitForm.isSupportedUnit("折"));
        assertEquals(false, BenefitForm.isSupportedUnit("%"));
        assertEquals(false, BenefitForm.isSupportedUnit("摺"));
    }
}
