package com.lrj.drools.activity;

import com.lrj.drools.activity.domain.ActivityCandidate;
import com.lrj.drools.activity.domain.ActivityRuleContext;
import com.lrj.drools.activity.domain.ActivityRuleResult;
import com.lrj.drools.activity.domain.OfferSpec;
import com.lrj.drools.activity.domain.SpuDiscountRequest;
import com.lrj.drools.activity.domain.StackStrategy;
import com.lrj.drools.activity.engine.BenefitEvaluator;
import com.lrj.drools.activity.metrics.DecisionMetrics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>权益作用域</b>：商品级活动的钱只能算在它自己圈到的商品上。
 *
 * <p>修的是这样一笔钱：绑定 SPU=B 的「9.9 一口价」，用户车里是 A(5000) + B，
 * 减免被算成 {@code 5009.9 − 9.9 = 5000}——<b>整车按 9.9 成交</b>。
 * 「指定商品 8 折」同理变成整单 8 折；第 N 件折更彻底，活动只绑 B，买 2 件 A 也享折。
 * 全程无报错、无 warning：金额是正数、决策成功、日志干净。
 *
 * <p>根因是绑定关系只被当成<em>候选筛选器</em>：查出「哪些活动可能适用」之后，
 * 绑定信息就被 {@code .distinct()} 丢掉了，求值层手上只剩 {@code orderAmount} 一个标量。
 *
 * <p><b>本类同时守住修复的边界</b>——不能为了修多发而把能算对的场景一起关掉：
 * 作用域覆盖整单时（今天绝大多数流量：单 SPU 查询、全场券）{@code orderAmount} 仍是合法基数。
 */
@DisplayName("权益作用域：钱只算在活动自己的商品上")
class BenefitScopeTest {

    private final BenefitEvaluator evaluator = new BenefitEvaluator(DecisionMetrics.noop());

    private static final long SPU_A = 1001L;   // 贵重商品，1000 元
    private static final long SPU_B = 1002L;   // 活动商品，10 元

    @Nested
    @DisplayName("真子集作用域：按作用域小计算，不按整单")
    class ProperSubset {

        @Test
        @DisplayName("指定商品 8 折：只打自己那 20 块，不是整车 1020")
        void ratioUsesScopedSubtotal() {
            ActivityRuleContext ctx = cart();
            ActivityCandidate c = ratio("ACT-RATIO", new BigDecimal("8"), Set.of(SPU_B));

            evaluator.computeAmounts(ctx, List.of(c));

            // B 的小计 = 10 × 2 = 20，8 折减 20%，即 4.00
            assertEquals(0, c.getComputedAmount().compareTo(new BigDecimal("4.00")),
                    "只绑 B 的 8 折券应按 B 的小计(20) 算，实际 " + c.getComputedAmount());
            assertTrue(c.getComputedAmount().compareTo(new BigDecimal("200")) < 0,
                    "**这就是被修的那笔钱**：按整单 1020 算会减 204，相当于拿 B 的折扣把 A 也打了折");
        }

        @Test
        @DisplayName("一口价：减免 = 作用域小计 − 一口价，不是整车 − 一口价")
        void fixedPriceUsesScopedSubtotal() {
            ActivityRuleContext ctx = cart();
            ActivityCandidate c = fixedPrice("ACT-FLASH", new BigDecimal("5"), Set.of(SPU_B));

            evaluator.computeAmounts(ctx, List.of(c));

            assertEquals(0, c.getComputedAmount().compareTo(new BigDecimal("15.00")),
                    "B 小计 20 − 一口价 5 = 15；实际 " + c.getComputedAmount());
            assertTrue(c.getComputedAmount().compareTo(new BigDecimal("1000")) < 0,
                    "按整单算会减 1015 —— 整车按 5 元成交");
        }

        @Test
        @DisplayName("第 N 件折：车里的 A 不许替 B 凑出「第二件」")
        void nthFiltersOutOfScopeLines() {
            // A 买 2 件（1000 元/件）、B 买 2 件（10 元/件）；活动只绑 B，第 2 件 5 折
            ActivityRuleContext ctx = ctx(new BigDecimal("2020"),
                    List.of(SPU_A, SPU_B),
                    List.of(line(SPU_A, "1000", 2), line(SPU_B, "10", 2)));
            ActivityCandidate c = nth("ACT-NTH", new BigDecimal("5"), 2, Set.of(SPU_B));

            evaluator.computeAmounts(ctx, List.of(c));

            assertEquals(0, c.getComputedAmount().compareTo(new BigDecimal("5.00")),
                    "只有 B 的第二件享折：10 × 50% = 5.00；实际 " + c.getComputedAmount());
            assertTrue(c.getComputedAmount().compareTo(new BigDecimal("500")) < 0,
                    "不限定作用域会把 A 的第二件也算进来，多减 500 元");
        }

        @Test
        @DisplayName("作用域是真子集但没传订单行 → 不适用，绝不拿整单顶替")
        void partialScopeWithoutLinesIsNotApplicable() {
            // 请求含 A、B 两个 SPU，活动只绑 B，但调用方没给逐行信息 → 无从知道 B 值多少钱
            ActivityRuleContext ctx = ctx(new BigDecimal("1020"), List.of(SPU_A, SPU_B), null);
            ActivityCandidate ratio = ratio("ACT-R", new BigDecimal("8"), Set.of(SPU_B));
            ActivityCandidate price = fixedPrice("ACT-P", new BigDecimal("5"), Set.of(SPU_B));
            List<ActivityCandidate> all = new ArrayList<>(List.of(ratio, price));

            evaluator.computeAmounts(ctx, all);

            assertFalse(ratio.isEligible(), "算不出作用域基数就必须淘汰，而不是按整单发钱");
            assertFalse(price.isEligible(), "一口价同理");
            assertNotNull(ratio.getRejectReason());
            assertTrue(ratio.getRejectReason().contains("作用域基数不可知"),
                    "拒绝理由要说清是「算不出基数」而不是「基数不够」——两者排查方向完全相反，实际："
                            + ratio.getRejectReason());

            ActivityRuleResult merged = evaluator.merge(ctx, all, StackStrategy.MAX);
            assertEquals(0, merged.getHitAmount().compareTo(BigDecimal.ZERO));
            assertEquals(null, merged.getHitActivityId(),
                    "必须是「不命中」，不能是「命中且减 0 元」——后者会挤掉别的能减钱的活动");
        }

        @Test
        @DisplayName("订单行没带 SPU 归属 → 该行不参与；全都没带 → 不适用")
        void lineWithoutSpuIdIsExcludedWhenScoped() {
            ActivityRuleContext ctx = ctx(new BigDecimal("1020"), List.of(SPU_A, SPU_B),
                    List.of(line(null, "1000", 1), line(null, "10", 2)));
            ActivityCandidate c = ratio("ACT-NOSPU", new BigDecimal("8"), Set.of(SPU_B));

            evaluator.computeAmounts(ctx, List.of(c));

            assertFalse(c.isEligible(), "归属不明的行不猜——一行都归不进作用域就是算不出基数");
        }
    }

    @Nested
    @DisplayName("兼容边界：不能为了修多发把能算的场景一起关掉")
    class Compatibility {

        @Test
        @DisplayName("作用域覆盖整个请求 → 仍用订单金额当基数（今天绝大多数流量）")
        void scopeCoveringWholeRequestKeepsOrderAmountBase() {
            // 单 SPU 查询：请求 [B]、活动绑 B → 「整单」与「活动的商品」是同一批东西
            ActivityRuleContext ctx = ctx(new BigDecimal("100"), List.of(SPU_B), null);
            ActivityCandidate c = ratio("ACT-FULL", new BigDecimal("8"), Set.of(SPU_B));

            evaluator.computeAmounts(ctx, List.of(c));

            assertEquals(0, c.getComputedAmount().compareTo(new BigDecimal("20.00")),
                    "作用域等于整单时 orderAmount 是完全合法的基数；"
                            + "把这一档也 fail-closed 会让线上所有不传订单行的折扣券/秒杀券当场失效");
        }

        @Test
        @DisplayName("作用域未知（老装配路径 / 手工候选）→ 行为与改造前逐字节一致")
        void unknownScopeFallsBackToOrderAmount() {
            ActivityRuleContext ctx = ctx(new BigDecimal("100"), List.of(SPU_A, SPU_B), null);
            ActivityCandidate ratio = ratio("ACT-UNK-R", new BigDecimal("8"), null);
            ActivityCandidate price = fixedPrice("ACT-UNK-P", new BigDecimal("9.9"), null);

            evaluator.computeAmounts(ctx, new ArrayList<>(List.of(ratio, price)));

            assertEquals(0, ratio.getComputedAmount().compareTo(new BigDecimal("20.00")),
                    "scopedSpuIds=null 表示作用域未知，必须退回整单语义（兼容承诺）");
            assertEquals(0, price.getComputedAmount().compareTo(new BigDecimal("90.10")));
        }

        @Test
        @DisplayName("普通红包不受作用域影响——面额本就与订单金额无关")
        void amountFormIsUnaffectedByScope() {
            ActivityRuleContext ctx = ctx(new BigDecimal("1020"), List.of(SPU_A, SPU_B), null);
            // 真子集作用域，且没有订单行
            ActivityCandidate c = candidate(OfferSpec.builder().activityId("ACT-AMOUNT")
                    .redPackageAmount(new BigDecimal("30"))
                    .redPackageAmountUnit("元"), Set.of(SPU_B));

            evaluator.computeAmounts(ctx, List.of(c));

            assertEquals(0, c.getComputedAmount().compareTo(new BigDecimal("30")),
                    "「减 30 元」这句话不依赖任何基数。把它一起 fail-closed 会改掉金标里"
                            + "阶梯/固定金额/随机红包的既有语义——那些形态的面额与订单金额无关");
        }

        @Test
        @DisplayName("无作用域时第 N 件折仍按全部行算（旧语义）")
        void nthWithoutScopeCountsAllLines() {
            ActivityRuleContext ctx = ctx(new BigDecimal("2020"), List.of(SPU_A, SPU_B),
                    List.of(line(SPU_A, "1000", 2), line(SPU_B, "10", 2)));
            ActivityCandidate c = nth("ACT-NTH-ALL", new BigDecimal("5"), 2, null);

            evaluator.computeAmounts(ctx, List.of(c));

            assertEquals(0, c.getComputedAmount().compareTo(new BigDecimal("505.00")),
                    "作用域未知 = 不限定：A 第二件 500 + B 第二件 5 = 505.00");
        }
    }

    // ---- helpers ----

    /** A(1000×1) + B(10×2)，整单 1020，请求同时含 A 与 B。 */
    private static ActivityRuleContext cart() {
        return ctx(new BigDecimal("1020"), List.of(SPU_A, SPU_B),
                List.of(line(SPU_A, "1000", 1), line(SPU_B, "10", 2)));
    }

    private static ActivityRuleContext ctx(BigDecimal orderAmount, List<Long> spuIds,
                                           List<SpuDiscountRequest.OrderLine> lines) {
        ActivityRuleContext ctx = new ActivityRuleContext();
        ctx.putAttr("orderAmount", orderAmount);
        ctx.putAttr("spuId", spuIds);
        if (lines != null) ctx.putAttr("orderLines", lines);
        return ctx;
    }

    private static SpuDiscountRequest.OrderLine line(Long spuId, String unitPrice, int qty) {
        return new SpuDiscountRequest.OrderLine(spuId, new BigDecimal(unitPrice), qty);
    }

    /** {@code scope} 为 null 表示「作用域未知」，与空集语义不同——两条分支这里都要能造出来。 */
    private static ActivityCandidate candidate(OfferSpec.Builder spec, Set<Long> scope) {
        return new ActivityCandidate(spec.build(), scope, false);
    }

    private static ActivityCandidate ratio(String id, BigDecimal zhe, Set<Long> scope) {
        return candidate(OfferSpec.builder().activityId(id)
                .redPackageAmount(zhe)
                .redPackageAmountUnit("折")
                .redPackageMaxDiscount(new BigDecimal("99999")), scope);
    }

    private static ActivityCandidate fixedPrice(String id, BigDecimal price, Set<Long> scope) {
        return candidate(OfferSpec.builder().activityId(id)
                .redPackageAmount(price)
                .redPackageAmountUnit("价"), scope);
    }

    private static ActivityCandidate nth(String id, BigDecimal zhe, int n, Set<Long> scope) {
        return candidate(OfferSpec.builder().activityId(id)
                .redPackageAmount(zhe)
                .redPackageAmountUnit("件折")
                .redPackageRangeAmount("{\"nth\":" + n + "}"), scope);
    }
}
