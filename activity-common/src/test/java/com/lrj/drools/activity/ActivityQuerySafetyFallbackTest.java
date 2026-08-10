package com.lrj.drools.activity;

import com.lrj.drools.activity.domain.ActivityCandidate;
import com.lrj.drools.activity.domain.ActivityRuleContext;
import com.lrj.drools.activity.domain.ActivityRuleResult;
import com.lrj.drools.activity.domain.ActivityType;
import com.lrj.drools.activity.domain.ConditionNode;
import com.lrj.drools.activity.domain.GiftResult;
import com.lrj.drools.activity.domain.SpuDiscountRequest;
import com.lrj.drools.activity.domain.StackStrategy;
import com.lrj.drools.activity.engine.ActivityDrlBuilder.EligibilityRuleDef;
import com.lrj.drools.activity.engine.ActivityRuleRuntimeService;
import com.lrj.drools.activity.engine.BenefitEvaluator;
import com.lrj.drools.activity.engine.ConditionTreeEvaluator;
import com.lrj.drools.activity.engine.RuleSchemaRegistry;
import com.lrj.drools.activity.metrics.DecisionMetrics;
import com.lrj.drools.activity.service.ActivityQueryService;
import com.lrj.drools.activity.service.DecisionDataLoader;
import com.lrj.drools.activity.service.DecisionEligibilityService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 回退路径与买赠资格的资金安全回归。全部用内存 fact，不依赖数据库。 */
class ActivityQuerySafetyFallbackTest {

    private record BenefitCase(String name, Supplier<ActivityCandidate> candidate,
                               SpuDiscountRequest request, String expected) {}

    @Test
    @DisplayName("买赠 Drools 路径先判资格：499 不赠，500 才赠")
    void giftEnginePathRespects499And500() {
        assertThat(giftDecision(true, "499").gifts()).isEmpty();
        assertThat(giftDecision(true, "500").gifts()).extracting(GiftResult::getGiftName)
                .containsExactly("门槛赠品");
    }

    @Test
    @DisplayName("买赠 fallback 同样先判资格：499 不赠，500 才赠")
    void giftFallbackRespects499And500() {
        ActivityQueryService.GiftView below = giftDecision(false, "499");
        assertThat(below.gifts()).isEmpty();
        assertThat(below.mode()).isEqualTo("legacy");

        ActivityQueryService.GiftView at = giftDecision(false, "500");
        assertThat(at.gifts()).extracting(GiftResult::getGiftName).containsExactly("门槛赠品");
        assertThat(at.mode()).isEqualTo("legacy");
    }

    @Test
    @DisplayName("总引擎关闭仍执行资格，门槛不能随开关一起消失")
    void engineDisabledStillAppliesEligibility() {
        ActivityCandidate belowCandidate = fixed("ACT-LIMIT", "10");
        ActivityQueryService belowQuery = query(false, false,
                thresholdMaterials(belowCandidate, 500), mock(ActivityRuleRuntimeService.class));
        ActivityQueryService.DiscountView below = belowQuery.spuDiscount(request("499"), true);
        assertThat(below.hit()).isFalse();
        assertThat(below.traces()).anyMatch(t -> t.contains("eligibility reject: ACT-LIMIT"));

        ActivityCandidate atCandidate = fixed("ACT-LIMIT", "10");
        ActivityQueryService atQuery = query(false, false,
                thresholdMaterials(atCandidate, 500), mock(ActivityRuleRuntimeService.class));
        assertThat(atQuery.spuDiscount(request("500"), true).hitAmount())
                .isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("Java 主求值无命中时不能复活资格未通过的候选")
    void emptyDecisionFallbackKeepsRejectedCandidateOut() {
        ActivityCandidate candidate = fixed("ACT-LIMIT", "10");
        ActivityQueryService query = query(true, false,
                thresholdMaterials(candidate, 500), mock(ActivityRuleRuntimeService.class));

        ActivityQueryService.DiscountView view = query.spuDiscount(request("499"), true);

        assertThat(view.hit()).isFalse();
        assertThat(view.hitAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(view.traces()).anyMatch(t -> t.contains("eligibility reject: ACT-LIMIT"));
    }

    @Test
    @DisplayName("活动声明受控资格但条件树不可用时 fail-closed")
    void constrainedActivityWithoutTreeFailsClosed() {
        ActivityCandidate candidate = fixed("ACT-BROKEN-TREE", "10");
        DecisionDataLoader.Materials broken = new DecisionDataLoader.Materials(
                List.of(candidate),
                List.of(new EligibilityRuleDef(candidate.getActivityId(),
                        "numberAttr(\"orderAmount\") >= 500")),
                Map.of());
        ActivityQueryService query = query(false, false, broken, mock(ActivityRuleRuntimeService.class));

        ActivityQueryService.DiscountView view = query.spuDiscount(request("999"), true);

        assertThat(view.hit()).isFalse();
        assertThat(candidate.getRejectReason()).isEqualTo("资格条件不可判定");
        assertThat(view.traces()).contains(
                "eligibility reject: ACT-BROKEN-TREE（条件树不可用）");
    }

    @Test
    @DisplayName("总引擎关闭的安全回退覆盖固定/随机/阶梯/折扣/一口价/第 N 件折")
    void engineDisabledFallbackCoversEveryBenefitForm() {
        for (BenefitCase c : benefitCases()) {
            ActivityQueryService query = query(false, false, materials(c.candidate().get()),
                    mock(ActivityRuleRuntimeService.class));
            ActivityQueryService.DiscountView view = query.spuDiscount(c.request(), true);

            assertThat(view.mode()).as(c.name()).isEqualTo("legacy");
            assertThat(view.hit()).as(c.name()).isTrue();
            assertThat(view.hitAmount()).as(c.name()).isEqualByComparingTo(c.expected());
        }
    }

    @Test
    @DisplayName("Java 主求值无可用决策的安全重算也覆盖全部六种权益形态")
    void emptyDecisionFallbackCoversEveryBenefitForm() {
        for (BenefitCase c : benefitCases()) {
            ActivityRuleRuntimeService runtime = mock(ActivityRuleRuntimeService.class);
            // 第一次 merge 模拟无可用决策，安全重算的第二次 merge 走真实 BenefitEvaluator。
            ActivityQueryService query = query(true, false, materials(c.candidate().get()), runtime,
                    StackStrategy.MAX, new EmptyOnceBenefitEvaluator());
            ActivityQueryService.DiscountView view = query.spuDiscount(c.request(), true);

            assertThat(view.mode()).as(c.name()).isEqualTo("rule-engine");
            assertThat(view.hit()).as(c.name()).isTrue();
            assertThat(view.hitAmount()).as(c.name()).isEqualByComparingTo(c.expected());
            assertThat(view.traces()).as(c.name())
                    .anyMatch(t -> t.contains("无可用决策") && t.contains("安全 Java 算额"));
        }
    }

    @Test
    @DisplayName("旧的两个 Java 开关即使都为 false，生产仍用共享资格与六形态求值")
    void legacyFalseFlagsCannotSwitchProductionBackToDrools() {
        for (BenefitCase c : benefitCases()) {
            ActivityRuleRuntimeService runtime = mock(ActivityRuleRuntimeService.class);
            ActivityQueryService query = query(true, false, materials(c.candidate().get()), runtime);

            ActivityQueryService.DiscountView view = query.spuDiscount(c.request(), true);

            assertThat(view.hitAmount()).as(c.name()).isEqualByComparingTo(c.expected());
            verifyNoInteractions(runtime);
        }

        ActivityRuleRuntimeService runtime = mock(ActivityRuleRuntimeService.class);
        ActivityCandidate limited = fixed("ACT-FLAG-LIMIT", "10");
        ActivityQueryService query = query(true, false, thresholdMaterials(limited, 500), runtime);
        assertThat(query.spuDiscount(request("499"), true).hit()).isFalse();
        verifyNoInteractions(runtime);
    }

    @Test
    @DisplayName("安全回退保留 STACK：两张通过候选累加，不退化为 MAX")
    void fallbackKeepsStackStrategy() {
        ActivityQueryService query = query(false, false,
                materials(List.of(fixed("ACT-STACK-10", "10"), fixed("ACT-STACK-20", "20"))),
                mock(ActivityRuleRuntimeService.class), StackStrategy.STACK, new BenefitEvaluator());

        ActivityQueryService.DiscountView view = query.spuDiscount(request("100"), true);

        assertThat(view.hitAmount()).isEqualByComparingTo("30");
        assertThat(view.strategy()).isEqualTo("STACK");
        assertThat(view.mode()).isEqualTo("legacy");
    }

    @Test
    @DisplayName("安全回退保留 PRIORITY：优先级胜者不被更大金额挤掉")
    void fallbackKeepsPriorityStrategy() {
        ActivityCandidate preferred = fixed("ACT-PRIORITY-5", "5");
        preferred.setPriority(0);
        ActivityCandidate larger = fixed("ACT-PRIORITY-20", "20");
        larger.setPriority(1);
        ActivityQueryService query = query(false, false, materials(List.of(preferred, larger)),
                mock(ActivityRuleRuntimeService.class), StackStrategy.PRIORITY, new BenefitEvaluator());

        ActivityQueryService.DiscountView view = query.spuDiscount(request("100"), true);

        assertThat(view.hitActivityId()).isEqualTo("ACT-PRIORITY-5");
        assertThat(view.hitAmount()).isEqualByComparingTo("5");
        assertThat(view.strategy()).isEqualTo("PRIORITY");
    }

    private static ActivityQueryService.GiftView giftDecision(boolean engineEnabled, String orderAmount) {
        ActivityCandidate candidate = base("ACT-GIFT");
        candidate.setGifts(new ArrayList<>(List.of(gift("门槛赠品"))));
        DecisionDataLoader.Materials materials = thresholdMaterials(candidate, 500);

        ActivityRuleRuntimeService runtime = mock(ActivityRuleRuntimeService.class);
        when(runtime.evalGift(any(ActivityRuleContext.class), anyBoolean())).thenAnswer(invocation -> {
            ActivityRuleContext ctx = invocation.getArgument(0);
            ActivityRuleResult result = new ActivityRuleResult();
            for (ActivityCandidate c : ctx.getCandidates()) {
                if (c.isEligible()) result.getGifts().addAll(c.getGifts());
            }
            return result;
        });
        return query(engineEnabled, true, materials, runtime).buyAndGetGifts(request(orderAmount), true);
    }

    private static ActivityQueryService query(boolean engineEnabled, boolean javaBenefitEval,
                                              DecisionDataLoader.Materials materials,
                                              ActivityRuleRuntimeService runtime) {
        return query(engineEnabled, javaBenefitEval, materials, runtime,
                StackStrategy.MAX, new BenefitEvaluator());
    }

    private static ActivityQueryService query(boolean engineEnabled, boolean javaBenefitEval,
                                              DecisionDataLoader.Materials materials,
                                              ActivityRuleRuntimeService runtime,
                                              StackStrategy strategy,
                                              BenefitEvaluator benefitEvaluator) {
        DecisionDataLoader loader = mock(DecisionDataLoader.class);
        when(loader.load(any(), any(ActivityType.class), anyBoolean())).thenReturn(materials);
        when(loader.resolveStrategy(anyList())).thenReturn(strategy);

        DecisionMetrics metrics = DecisionMetrics.noop();
        DecisionEligibilityService eligibility = new DecisionEligibilityService(
                new ConditionTreeEvaluator(), new RuleSchemaRegistry(), metrics);
        ActivityQueryService query = new ActivityQueryService(
                loader, runtime, metrics, benefitEvaluator, eligibility);
        ReflectionTestUtils.setField(query, "ruleEngineEnabled", engineEnabled);
        ReflectionTestUtils.setField(query, "javaBenefitEval", javaBenefitEval);
        ReflectionTestUtils.setField(query, "javaEligibilityEval", javaBenefitEval);
        return query;
    }

    /** 只对第一次合并模拟「执行完但无决策」，安全重算阶段继续用真实六形态语义。 */
    private static final class EmptyOnceBenefitEvaluator extends BenefitEvaluator {
        private boolean empty = true;

        @Override
        public ActivityRuleResult merge(List<ActivityCandidate> candidates, StackStrategy strategy, boolean explain) {
            if (empty) {
                empty = false;
                ActivityRuleResult result = new ActivityRuleResult();
                result.setStrategy(strategy);
                return result;
            }
            return super.merge(candidates, strategy, explain);
        }
    }

    private static List<BenefitCase> benefitCases() {
        return List.of(
                new BenefitCase("固定金额", () -> fixed("ACT-FIXED", "12"), request("100"), "12"),
                new BenefitCase("随机金额", () -> {
                    ActivityCandidate c = base("ACT-RANDOM");
                    c.setRedPackageTakeType(2);
                    c.setRedPackageAmountUnit("元");
                    c.setRedPackageRangeAmount("{\"min\":7,\"max\":7}");
                    return c;
                }, request("100"), "7.00"),
                new BenefitCase("阶梯金额", () -> {
                    ActivityCandidate c = base("ACT-LADDER");
                    c.setRedPackageTakeType(1);
                    c.setRedPackageAmountUnit("元");
                    c.setRedPackageRangeAmount("[{\"min\":0,\"max\":1000,\"reward\":30}]");
                    return c;
                }, request("500"), "30"),
                new BenefitCase("整单折扣", () -> {
                    ActivityCandidate c = base("ACT-RATIO");
                    c.setRedPackageAmountUnit("折");
                    c.setRedPackageAmount(new BigDecimal("8"));
                    c.setRedPackageMaxDiscount(new BigDecimal("50"));
                    return c;
                }, request("100"), "20.00"),
                new BenefitCase("一口价", () -> {
                    ActivityCandidate c = base("ACT-PRICE");
                    c.setRedPackageAmountUnit("价");
                    c.setRedPackageAmount(new BigDecimal("9.9"));
                    return c;
                }, request("100"), "90.10"),
                new BenefitCase("第 N 件折", () -> {
                    ActivityCandidate c = base("ACT-NTH");
                    c.setRedPackageAmountUnit("件折");
                    c.setRedPackageAmount(new BigDecimal("5"));
                    c.setRedPackageRangeAmount("{\"nth\":2}");
                    return c;
                }, nthRequest(), "50.00")
        );
    }

    private static DecisionDataLoader.Materials materials(ActivityCandidate candidate) {
        return materials(List.of(candidate));
    }

    private static DecisionDataLoader.Materials materials(List<ActivityCandidate> candidates) {
        return new DecisionDataLoader.Materials(candidates, List.of(), Map.of());
    }

    private static DecisionDataLoader.Materials thresholdMaterials(ActivityCandidate candidate, int threshold) {
        ConditionNode tree = new ConditionNode();
        tree.setField("orderAmount");
        tree.setOp("ge");
        tree.setValue(threshold);
        return new DecisionDataLoader.Materials(
                List.of(candidate),
                List.of(new EligibilityRuleDef(candidate.getActivityId(),
                        "numberAttr(\"orderAmount\") >= " + threshold)),
                Map.of(candidate.getActivityId(), tree));
    }

    private static ActivityCandidate fixed(String id, String amount) {
        ActivityCandidate c = base(id);
        c.setRedPackageTakeType(1);
        c.setRedPackageAmountUnit("元");
        c.setRedPackageAmount(new BigDecimal(amount));
        return c;
    }

    private static ActivityCandidate base(String id) {
        ActivityCandidate c = new ActivityCandidate();
        c.setActivityId(id);
        c.setActivityName(id);
        c.setBizLine("safety-test");
        c.setVersion(1);
        return c;
    }

    private static GiftResult gift(String name) {
        GiftResult gift = new GiftResult();
        gift.setGiftName(name);
        gift.setGiftNum(1);
        return gift;
    }

    private static SpuDiscountRequest request(String orderAmount) {
        return new SpuDiscountRequest(List.of(990011L), 1001L, "110000", List.of("vip"),
                new BigDecimal(orderAmount), 1);
    }

    private static SpuDiscountRequest nthRequest() {
        return new SpuDiscountRequest(
                List.of(990011L), 1001L, "110000", List.of("vip"),
                new BigDecimal("200"), 2, null,
                List.of(new SpuDiscountRequest.OrderLine(990011L, new BigDecimal("100"), 2)));
    }
}
