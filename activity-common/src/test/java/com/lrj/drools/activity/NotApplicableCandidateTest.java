package com.lrj.drools.activity;

import com.lrj.drools.activity.domain.ActivityCandidate;
import com.lrj.drools.activity.domain.ActivityRuleContext;
import com.lrj.drools.activity.domain.ActivityRuleResult;
import com.lrj.drools.activity.domain.BenefitForm;
import com.lrj.drools.activity.domain.DistributionMode;
import com.lrj.drools.activity.domain.StackStrategy;
import com.lrj.drools.activity.engine.ActivityDrlBuilder;
import com.lrj.drools.activity.engine.BenefitEvaluator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「算不出金额」必须等于 <b>不适用</b>，不能等于 <b>减 0 元</b>。
 *
 * <p>四种形态的 fail-closed 分支一直写着「不给优惠，不是给 0 元」，但它只是<b>注释</b>：
 * 候选的 {@code eligible} 默认 true、{@code computedAmount} 默认 ZERO，而 merge 只按
 * {@code isEligible} 过滤 —— 于是被跳过的候选仍以一个「0 元的正常候选」身份留在池子里。
 * 后果有两层，第二层才是真的贵：
 * <ol>
 *   <li>唯一候选时报出 {@code hit=true, amount=0}：运营看到「命中了」，用户一分钱没减；</li>
 *   <li>PRIORITY / MUTEX 下它能凭 priority <b>挤掉</b>一个本来能减钱的活动——
 *       这是在少发钱，且没有任何日志会提。</li>
 * </ol>
 *
 * <p>本类专钉这条边界。用「一个可用 + 一个不可用」的组合，是因为单候选用例只能证明第一层，
 * 而挤掉别人那层才是线上真正会亏钱的形态。
 */
@DisplayName("算不出金额的候选 = 不适用，不参与竞争")
class NotApplicableCandidateTest {

    private final BenefitEvaluator evaluator = new BenefitEvaluator();

    /** 第 N 件折，但上下文里没有订单行 → 算不出来。 */
    private static ActivityCandidate nthWithoutLines(String id, int priority) {
        ActivityCandidate c = base(id, priority);
        c.setRedPackageAmountUnit(BenefitForm.UNIT_NTH_ZHE);
        c.setRedPackageAmount(new BigDecimal("5"));
        c.setRedPackageRangeAmount("{\"nth\":2}");
        return c;
    }

    /** 折扣型，但上下文里没有订单金额 → 算不出来。 */
    private static ActivityCandidate ratioWithoutOrderAmount(String id, int priority) {
        ActivityCandidate c = base(id, priority);
        c.setRedPackageAmountUnit(BenefitForm.UNIT_ZHE);
        c.setRedPackageAmount(new BigDecimal("8"));
        c.setRedPackageMaxDiscount(new BigDecimal("50"));
        return c;
    }

    /** 一口价 100，订单只有 50 → 秒杀价比订单还贵，算不出来。 */
    private static ActivityCandidate pricierThanOrder(String id, int priority) {
        ActivityCandidate c = base(id, priority);
        c.setRedPackageAmountUnit(BenefitForm.UNIT_PRICE);
        c.setRedPackageAmount(new BigDecimal("100"));
        return c;
    }

    /** 随机红包，但区间是脏的 → 抽不出来。 */
    private static ActivityCandidate randomWithBadRange(String id, int priority) {
        ActivityCandidate c = base(id, priority);
        c.setRedPackageTakeType(DistributionMode.RANDOM_AMOUNT.code());
        c.setRedPackageRangeAmount("{\"min\":20,\"max\":5}");
        return c;
    }

    /** 老老实实能算的固定金额红包，用来当「被挤掉的那个」。 */
    private static ActivityCandidate plain(String id, int priority, String amount) {
        ActivityCandidate c = base(id, priority);
        c.setRedPackageAmountUnit(BenefitForm.UNIT_YUAN);
        c.setRedPackageAmount(new BigDecimal(amount));
        return c;
    }

    private static ActivityCandidate base(String id, int priority) {
        ActivityCandidate c = new ActivityCandidate();
        c.setActivityId(id);
        c.setActivityName(id);
        c.setVersion(1);
        c.setEligible(true);
        c.setPriority(priority);
        return c;
    }

    private ActivityRuleResult run(StackStrategy strategy, ActivityRuleContext ctx, ActivityCandidate... cs) {
        List<ActivityCandidate> list = new ArrayList<>(List.of(cs));
        evaluator.computeAmounts(ctx, list);
        return evaluator.merge(list, strategy);
    }

    @Test
    @DisplayName("唯一候选算不出金额 → 不命中，而不是「命中且减 0 元」")
    void loneNotApplicableDoesNotHit() {
        ActivityRuleResult r = run(StackStrategy.MAX, new ActivityRuleContext(), nthWithoutLines("ACT-NTH", 1));

        assertThat(r.getHitActivityId()).as("算不出金额还报命中，运营会以为优惠发出去了").isNull();
        assertThat(r.getHitAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("PRIORITY：不适用的高优先级活动不许挤掉能减钱的低优先级活动")
    void notApplicableCannotWinByPriority() {
        // priority 越小越优先：不适用的那个排在前面，正是最容易吃掉别人的位置
        ActivityRuleResult r = run(StackStrategy.PRIORITY, new ActivityRuleContext(),
                nthWithoutLines("ACT-NTH", 1), plain("ACT-OK", 9, "10"));

        assertThat(r.getHitActivityId()).isEqualTo("ACT-OK");
        assertThat(r.getHitAmount()).as("被挤掉的话这里是 0——那就是实打实少发了 10 元")
                .isEqualByComparingTo(new BigDecimal("10"));
    }

    @Test
    @DisplayName("MAX：不适用的候选不以 0 元参与比大小")
    void notApplicableStaysOutOfMax() {
        ActivityRuleResult r = run(StackStrategy.MAX, new ActivityRuleContext(),
                ratioWithoutOrderAmount("ACT-RATIO", 1), plain("ACT-OK", 1, "10"));

        assertThat(r.getHitActivityId()).isEqualTo("ACT-OK");
        assertThat(r.getHitAmount()).isEqualByComparingTo(new BigDecimal("10"));
    }

    @Test
    @DisplayName("STACK：不适用的候选既不进总额，也不当主活动")
    void notApplicableStaysOutOfStack() {
        ActivityRuleResult r = run(StackStrategy.STACK, new ActivityRuleContext(),
                nthWithoutLines("ACT-NTH", 1), plain("ACT-OK", 9, "10"));

        assertThat(r.getHitAmount()).isEqualByComparingTo(new BigDecimal("10"));
        assertThat(r.getHitActivityId()).as("主活动按 priority 选，不适用的不该当选").isEqualTo("ACT-OK");
    }

    @Test
    @DisplayName("四种形态都要淘汰，且淘汰原因写清楚（试算屏靠它解释「活动怎么没了」）")
    void allFourFormsRejectWithReason() {
        ActivityRuleContext ctx = new ActivityRuleContext();
        ctx.putAttr("orderAmount", new BigDecimal("50"));   // 一口价 100 > 50 → 不适用

        List<ActivityCandidate> list = new ArrayList<>(List.of(
                nthWithoutLines("ACT-NTH", 1),
                pricierThanOrder("ACT-PRICE", 1),
                randomWithBadRange("ACT-RAND", 1)));
        evaluator.computeAmounts(ctx, list);

        assertThat(list).allSatisfy(c -> {
            assertThat(c.isEligible()).as(c.getActivityId() + " 应被淘汰").isFalse();
            assertThat(c.getRejectReason()).as(c.getActivityId() + " 必须写明为什么").contains("不适用");
        });

        // 折扣型单独跑一遍：它的「算不出来」是缺订单金额
        List<ActivityCandidate> ratio = new ArrayList<>(List.of(ratioWithoutOrderAmount("ACT-RATIO", 1)));
        evaluator.computeAmounts(new ActivityRuleContext(), ratio);
        assertThat(ratio.get(0).isEligible()).isFalse();
        assertThat(ratio.get(0).getRejectReason()).contains("不适用");
    }

    @Test
    @DisplayName("阶梯档奖励为负 → 不落档（库里的脏数据也不许变成「负优惠」）")
    void negativeLadderTierIsNotApplied() {
        ActivityCandidate c = base("ACT-LADDER", 1);
        ActivityRuleContext ctx = new ActivityRuleContext();
        ctx.putAttr("orderAmount", new BigDecimal("500"));

        List<ActivityCandidate> list = new ArrayList<>(List.of(c));
        evaluator.applyLadder(ctx, list, List.of(new ActivityDrlBuilder.LadderActivityDef(
                "ACT-LADDER",
                List.of(new ActivityDrlBuilder.LadderTier(
                        new BigDecimal("100"), new BigDecimal("1000"), new BigDecimal("-50"))),
                "orderAmount")));

        assertThat(c.getComputedAmount())
                .as("落档成 -50 的话，决策出口的 `hitActivityId != null ||` 短路会让它照样出门")
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("合法的 0 元优惠不受牵连——判据是「算不出来」，不是「金额为 0」")
    void legitimateZeroSurvives() {
        // 阶梯首档 reward=0 是运营配得出来的合法档位。若改用「金额为 0 就淘汰」，它会被误杀。
        ActivityCandidate zero = plain("ACT-ZERO", 1, "0");
        ActivityRuleResult r = run(StackStrategy.MAX, new ActivityRuleContext(), zero);

        assertThat(zero.isEligible()).isTrue();
        assertThat(r.getHitActivityId()).isEqualTo("ACT-ZERO");
    }
}
