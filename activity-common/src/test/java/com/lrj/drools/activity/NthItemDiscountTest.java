package com.lrj.drools.activity;

import com.lrj.drools.activity.metrics.DecisionMetrics;
import com.lrj.drools.activity.domain.ActivityCandidate;
import com.lrj.drools.activity.domain.ActivityRuleContext;
import com.lrj.drools.activity.domain.BenefitForm;
import com.lrj.drools.activity.domain.OfferSpec;
import com.lrj.drools.activity.domain.SpuDiscountRequest;
import com.lrj.drools.activity.engine.BenefitEvaluator;
import com.lrj.drools.activity.engine.BenefitMath;
import com.lrj.drools.activity.engine.RandomRangeParser;
import com.lrj.drools.activity.service.ActivityQueryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 第 N 件折（第二件半价）。
 *
 * <p>这个玩法此前做不了，卡点是决策入口没有逐行单价。本批测试守两条：
 * ① 算钱按<b>同款逐行</b>而不是整车摊平；② <b>缺行项时不适用</b>，绝不退化成拿均价瞎算——
 * 后者会在混着贵重与便宜商品的购物车里静默算错钱。
 */
class NthItemDiscountTest {

    private final BenefitEvaluator evaluator = new BenefitEvaluator(DecisionMetrics.noop());

    private static BenefitMath.Line line(String price, int qty) {
        return new BenefitMath.Line(new BigDecimal(price), qty);
    }

    @Nested
    @DisplayName("算钱")
    class Math {

        @Test
        @DisplayName("第二件半价：买 2 件 100 元的，减 50")
        void secondHalfPrice() {
            assertThat(BenefitMath.nthItemDiscount(List.of(line("100", 2)), 2, new BigDecimal("5")))
                    .isEqualByComparingTo(new BigDecimal("50.00"));
        }

        @Test
        @DisplayName("每满 N 件享 1 件：买 5 件按 floor(5/2)=2 件享折")
        void everyNthQualifies() {
            assertThat(BenefitMath.nthItemDiscount(List.of(line("100", 5)), 2, new BigDecimal("5")))
                    .isEqualByComparingTo(new BigDecimal("100.00"));
        }

        @Test
        @DisplayName("买 1 件不够第二件 → 不适用（不是减 0）")
        void singleItemNotApplicable() {
            assertThat(BenefitMath.nthItemDiscount(List.of(line("100", 1)), 2, new BigDecimal("5"))).isNull();
        }

        @Test
        @DisplayName("多行分别计算后求和，**不把不同款混在一起排序**")
        void perLineNotFlattened() {
            // 一款贵的买 1 件（不够第二件）+ 一款便宜的买 2 件（够）
            // 若错误地把整车摊平排序，贵的那件会被算进折扣；正确语义是只有便宜那款享折。
            BigDecimal off = BenefitMath.nthItemDiscount(
                    List.of(line("1000", 1), line("10", 2)), 2, new BigDecimal("5"));
            assertThat(off).isEqualByComparingTo(new BigDecimal("5.00"));
        }

        @Test
        @DisplayName("第三件七折也成立（不是只支持第二件半价这一种）")
        void thirdItemSeventy() {
            assertThat(BenefitMath.nthItemDiscount(List.of(line("100", 3)), 3, new BigDecimal("7")))
                    .isEqualByComparingTo(new BigDecimal("30.00"));
        }

        @Test
        @DisplayName("减免向下取整到分，与全站取整方向一致（不多发）")
        void roundsDown() {
            // 33.33 × (10-5)/10 = 16.665 → 向下 16.66
            assertThat(BenefitMath.nthItemDiscount(List.of(line("33.33", 2)), 2, new BigDecimal("5")))
                    .isEqualByComparingTo(new BigDecimal("16.66"));
        }
    }

    @Nested
    @DisplayName("fail-closed")
    class FailClosed {

        @Test
        @DisplayName("缺行项 / N<2 / 折数越界 → 不适用")
        void guards() {
            assertThat(BenefitMath.nthItemDiscount(null, 2, new BigDecimal("5"))).isNull();
            assertThat(BenefitMath.nthItemDiscount(List.of(), 2, new BigDecimal("5"))).isNull();
            assertThat(BenefitMath.nthItemDiscount(List.of(line("100", 2)), 1, new BigDecimal("5"))).isNull();
            assertThat(BenefitMath.nthItemDiscount(List.of(line("100", 2)), 2, null)).isNull();
            assertThat(BenefitMath.nthItemDiscount(List.of(line("100", 2)), 2, new BigDecimal("0"))).isNull();
            assertThat(BenefitMath.nthItemDiscount(List.of(line("100", 2)), 2, new BigDecimal("10"))).isNull();
        }

        @Test
        @DisplayName("负价 / 零件数的行被跳过，不参与计算")
        void badLinesSkipped() {
            assertThat(BenefitMath.nthItemDiscount(List.of(line("-1", 4), line("0", 4)), 2, new BigDecimal("5")))
                    .isNull();
        }

        @Test
        @DisplayName("nth 解析：缺失 / 非法 / <2 一律 null")
        void nthParsing() {
            assertThat(RandomRangeParser.parseNth("{\"nth\":2}")).isEqualTo(2);
            assertThat(RandomRangeParser.parseNth("{\"nth\":1}")).as("1 等于全场打折，更像配错").isNull();
            assertThat(RandomRangeParser.parseNth("{}")).isNull();
            assertThat(RandomRangeParser.parseNth("[{\"nth\":2}]")).as("数组归阶梯管").isNull();
            assertThat(RandomRangeParser.parseNth(null)).isNull();
        }
    }

    @Nested
    @DisplayName("接进决策链路")
    class Wiring {

        private ActivityCandidate nthCandidate() {
            return new ActivityCandidate(OfferSpec.builder()
                    .activityId("ACT-NTH-1")
                    .version(1)
                    .redPackageAmountUnit(BenefitForm.UNIT_NTH_ZHE)
                    .redPackageAmount(new BigDecimal("5"))    // 半价
                    .redPackageRangeAmount("{\"nth\":2}")
                    .build());
        }

        private BigDecimal compute(ActivityRuleContext ctx) {
            List<ActivityCandidate> list = new ArrayList<>(List.of(nthCandidate()));
            evaluator.computeAmounts(ctx, list);
            return list.get(0).isAmountComputed() ? list.get(0).getComputedAmount() : null;
        }

        @Test
        @DisplayName("带 lines 的请求算得出减免")
        void withLines() {
            SpuDiscountRequest req = new SpuDiscountRequest(
                    List.of(990011L), 1001L, null, null, new BigDecimal("200"), 2, null,
                    List.of(new SpuDiscountRequest.OrderLine(990011L, new BigDecimal("100"), 2)));
            ActivityRuleContext ctx = new ActivityRuleContext();
            ActivityQueryService.requestAttributes(req).forEach(ctx::putAttr);
            assertThat(compute(ctx)).isEqualByComparingTo(new BigDecimal("50.00"));
        }

        @Test
        @DisplayName("**老调用方不传 lines → 本活动不适用**，而不是拿整单均价算")
        void withoutLinesNotApplicable() {
            SpuDiscountRequest legacy = new SpuDiscountRequest(
                    List.of(990011L), 1001L, null, null, new BigDecimal("200"), 2);
            ActivityRuleContext ctx = new ActivityRuleContext();
            ActivityQueryService.requestAttributes(legacy).forEach(ctx::putAttr);
            assertThat(compute(ctx)).as("均价 100 × 2 件也是 50，但那是巧合——混价购物车就会算错").isNull();
        }

        @Test
        @DisplayName("契约兼容：六参 / 七参构造仍可用，lines 归 null")
        void constructorsBackwardCompatible() {
            var six = new SpuDiscountRequest(List.of(1L), 1L, null, null, BigDecimal.TEN, 1);
            var seven = new SpuDiscountRequest(List.of(1L), 1L, null, null, BigDecimal.TEN, 1, 7);
            assertThat(six.lines()).isNull();
            assertThat(seven.lines()).isNull();
            assertThat(seven.storeId()).isEqualTo(7);
        }

        @Test
        @DisplayName("orderLines 不进条件白名单——运营写不出「第 3 行单价 > 100」这种条件")
        void notAConditionField() {
            SpuDiscountRequest req = new SpuDiscountRequest(
                    List.of(1L), 1L, null, null, BigDecimal.TEN, 1, null,
                    List.of(new SpuDiscountRequest.OrderLine(1L, BigDecimal.ONE, 1)));
            Map<String, Object> attrs = ActivityQueryService.requestAttributes(req);
            assertThat(attrs).containsKey("orderLines");
            // 它在袋里只为算额服务；条件白名单的守卫由 DecisionContextFieldsTest 负责
            assertThat(attrs.get("orderLines")).isInstanceOf(List.class);
        }
    }
}
