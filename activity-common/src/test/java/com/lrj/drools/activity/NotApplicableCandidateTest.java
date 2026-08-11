package com.lrj.drools.activity;

import com.lrj.drools.activity.metrics.DecisionMetrics;
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

    private final BenefitEvaluator evaluator = new BenefitEvaluator(DecisionMetrics.noop());

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
        return evaluator.merge(ctx, list, strategy);
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

    // ================================================================ 阶梯：未落档 ≠ 减 0 元
    //
    // 阶梯是「算不出金额」里最隐蔽的一支：它的 redPackageAmount 本来就是 null（金额来自档位），
    // 所以四种形态的 fail-closed 分支一条都拦不到它，一路落到最后那句 continue。
    // 判别不能看金额——落档发 0 元是合法的——只能看有没有落过档。

    /** 纯阶梯活动：金额只来自档位，没有底价。 */
    private static ActivityCandidate ladderOnly(String id, int priority) {
        ActivityCandidate c = base(id, priority);
        c.setRedPackageAmountUnit(BenefitForm.UNIT_YUAN);
        return c;                                  // redPackageAmount 保持 null
    }

    private static ActivityDrlBuilder.LadderActivityDef tier(String id, String min, String max, String reward) {
        return new ActivityDrlBuilder.LadderActivityDef(id,
                List.of(new ActivityDrlBuilder.LadderTier(
                        new BigDecimal(min), max == null ? null : new BigDecimal(max), new BigDecimal(reward))),
                "orderAmount");
    }

    private static ActivityRuleContext orderAmount(String amount) {
        ActivityRuleContext ctx = new ActivityRuleContext();
        if (amount != null) ctx.putAttr("orderAmount", new BigDecimal(amount));
        return ctx;
    }

    /** 与 {@link #run} 同语义，但先跑一遍阶梯落档（决策链路的真实顺序）。 */
    private ActivityRuleResult runWithLadder(StackStrategy strategy, ActivityRuleContext ctx,
                                             List<ActivityDrlBuilder.LadderActivityDef> defs,
                                             ActivityCandidate... cs) {
        List<ActivityCandidate> list = new ArrayList<>(List.of(cs));
        evaluator.applyLadder(ctx, list, defs);
        evaluator.computeAmounts(ctx, list);
        return evaluator.merge(ctx, list, strategy);
    }

    @Test
    @DisplayName("阶梯未落档且无底价 → 不适用，而不是「命中且减 0 元」")
    void ladderMissedTierIsNotApplicable() {
        ActivityCandidate c = ladderOnly("ACT-LADDER", 1);
        // 首档从 300 起，订单 200 落不进任何档
        ActivityRuleResult r = runWithLadder(StackStrategy.MAX, orderAmount("200"),
                List.of(tier("ACT-LADDER", "300", "600", "50")), c);

        assertThat(c.isEligible()).as("算不出金额的候选必须被淘汰，不能留在合并池里").isFalse();
        assertThat(c.getRejectReason()).contains("阶梯未落档");
        assertThat(r.getHitActivityId()).as("未落档还报命中，运营会以为优惠发出去了").isNull();
    }

    @Test
    @DisplayName("PRIORITY：未落档的高优先级阶梯不许挤掉能减钱的活动（这条是真在少发钱）")
    void ladderMissedTierCannotWinByPriority() {
        ActivityCandidate ghost = ladderOnly("ACT-LADDER", 0);   // priority 更优
        ActivityCandidate real = plain("ACT-FIXED", 1, "10");

        ActivityRuleResult r = runWithLadder(StackStrategy.PRIORITY, orderAmount("200"),
                List.of(tier("ACT-LADDER", "300", "600", "50")), ghost, real);

        assertThat(r.getHitActivityId())
                .as("pickByPriority 只比 priority，0 元幽灵会凭 0<1 击败真能减 10 元的活动")
                .isEqualTo("ACT-FIXED");
        assertThat(r.getHitAmount()).isEqualByComparingTo(new BigDecimal("10"));
    }

    @Test
    @DisplayName("MUTEX 同为单选语义，同样不许被未落档的候选占位")
    void ladderMissedTierCannotWinByMutex() {
        ActivityCandidate ghost = ladderOnly("ACT-LADDER", 0);
        ActivityCandidate real = plain("ACT-FIXED", 1, "10");

        ActivityRuleResult r = runWithLadder(StackStrategy.MUTEX, orderAmount("200"),
                List.of(tier("ACT-LADDER", "300", "600", "50")), ghost, real);

        assertThat(r.getHitActivityId()).isEqualTo("ACT-FIXED");
    }

    @Test
    @DisplayName("落档发 0 元是合法优惠，不能被当成「算不出来」误杀")
    void ladderMatchedZeroRewardStillHits() {
        ActivityCandidate c = ladderOnly("ACT-LADDER", 1);
        // 订单 200 正好落进 [100,300) 这一档，档位奖励就是 0
        ActivityRuleResult r = runWithLadder(StackStrategy.MAX, orderAmount("200"),
                List.of(tier("ACT-LADDER", "100", "300", "0")), c);

        assertThat(c.isEligible()).as("判据是「算不算得出来」，不是「金额是不是 0」").isTrue();
        assertThat(r.getHitActivityId()).isEqualTo("ACT-LADDER");
        assertThat(r.getHitAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("阶梯带底价时未落档 → 仍发底价（固定金额覆盖阶梯的既有语义不许被改掉）")
    void ladderWithBaseAmountFallsBackToBase() {
        ActivityCandidate c = plain("ACT-LADDER", 1, "7");      // 有底价 7
        ActivityRuleResult r = runWithLadder(StackStrategy.MAX, orderAmount("200"),
                List.of(tier("ACT-LADDER", "300", "600", "50")), c);

        assertThat(c.isEligible()).isTrue();
        assertThat(r.getHitAmount())
                .as("金标「订单金额缺失 → 阶梯不参与，退回固定金额」靠的就是这条语义")
                .isEqualByComparingTo(new BigDecimal("7"));
    }

    @Test
    @DisplayName("缺订单金额且无底价 → 闸门不开也算不出金额，同样淘汰")
    void ladderWithoutDriverAndWithoutBaseIsNotApplicable() {
        ActivityCandidate c = ladderOnly("ACT-LADDER", 1);
        ActivityRuleResult r = runWithLadder(StackStrategy.MAX, orderAmount(null),
                List.of(tier("ACT-LADDER", "0", null, "50")), c);

        assertThat(c.isEligible()).isFalse();
        assertThat(r.getHitActivityId()).isNull();
    }

    @Test
    @DisplayName("规则行缺失的候选（权益字段全空）也不许留成 0 元幽灵")
    void candidateWithoutAnyBenefitFieldIsNotApplicable() {
        ActivityCandidate orphan = ladderOnly("ACT-ORPHAN", 0);  // 没有任何阶梯 def
        ActivityCandidate real = plain("ACT-FIXED", 1, "10");

        ActivityRuleResult r = runWithLadder(StackStrategy.PRIORITY, orderAmount("200"), List.of(), orphan, real);

        assertThat(orphan.isEligible()).isFalse();
        assertThat(r.getHitActivityId()).isEqualTo("ACT-FIXED");
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
