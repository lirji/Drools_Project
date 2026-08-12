package com.lrj.drools.activity;

import com.lrj.drools.activity.domain.ActivityCandidate;
import com.lrj.drools.activity.domain.ActivityType;
import com.lrj.drools.activity.domain.ConditionNode;
import com.lrj.drools.activity.domain.DecisionMode;
import com.lrj.drools.activity.domain.GiftResult;
import com.lrj.drools.activity.domain.SpuDiscountRequest;
import com.lrj.drools.activity.engine.ActivityDrlBuilder.EligibilityRuleDef;
import com.lrj.drools.activity.engine.ConditionTreeEvaluator;
import com.lrj.drools.activity.engine.RuleSchemaRegistry;
import com.lrj.drools.activity.metrics.DecisionMetrics;
import com.lrj.drools.activity.service.AddOnPurchaseService;
import com.lrj.drools.activity.service.DecisionAuditor;
import com.lrj.drools.activity.service.DecisionDataLoader;
import com.lrj.drools.activity.service.DecisionEligibilityService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 加价购两阶段决策。
 *
 * <p>这个玩法此前做不了，卡点不在算钱而在交互形状——既有链路是「一次调用返回最终优惠」，
 * 加价购必须先列选项、等用户挑、再定价。本批测试守两条：
 * ① 第一阶段只列选项不替用户挑；
 * ② <b>第二阶段绝不读客户端传来的价格</b>——这是防改价的根本，也是"两阶段"最容易被做错的地方。
 */
class AddOnPurchaseTest {

    private static ActivityCandidate withGifts(String activityId, GiftResult... gifts) {
        ActivityCandidate c = new ActivityCandidate();
        c.setActivityId(activityId);
        c.setActivityName("加价购-" + activityId);
        c.setVersion(1);
        c.setEligible(true);
        c.setGifts(new ArrayList<>(List.of(gifts)));
        return c;
    }

    private static GiftResult gift(String name, String addOnPrice) {
        GiftResult g = new GiftResult();
        g.setGiftName(name);
        if (addOnPrice != null) g.setAbsoluteAmount(new BigDecimal(addOnPrice));
        return g;
    }

    /** 用桩 loader 隔掉数据库：这批测试要证的是两阶段协议，不是取数。 */
    private static AddOnPurchaseService serviceReturning(List<ActivityCandidate> candidates) {
        return serviceReturning(new DecisionDataLoader.Materials(candidates, List.of(), Map.of()));
    }

    private static AddOnPurchaseService serviceReturning(DecisionDataLoader.Materials materials) {
        DecisionDataLoader loader = mock(DecisionDataLoader.class);
        when(loader.load(any(), any(ActivityType.class), anyBoolean())).thenReturn(materials);
        return service(loader);
    }

    private static AddOnPurchaseService service(DecisionDataLoader loader) {
        DecisionEligibilityService eligibility = new DecisionEligibilityService(
                new ConditionTreeEvaluator(), new RuleSchemaRegistry(), DecisionMetrics.noop());
        return new AddOnPurchaseService(loader, eligibility, DecisionMetrics.noop(), new DecisionAuditor());
    }

    private static SpuDiscountRequest req() {
        return req("200");
    }

    private static SpuDiscountRequest req(String orderAmount) {
        return new SpuDiscountRequest(List.of(990011L), 1001L, null, null,
                new BigDecimal(orderAmount), 1);
    }

    private static DecisionDataLoader.Materials minimumOrderMaterials(String activityId) {
        return minimumOrderMaterials(activityId, 500);
    }

    private static DecisionDataLoader.Materials minimumOrderMaterials(String activityId, int minimum) {
        ActivityCandidate candidate = withGifts(activityId, gift("保温杯", "9.9"));
        ConditionNode threshold = new ConditionNode();
        threshold.setField("orderAmount");
        threshold.setOp("ge");
        threshold.setValue(minimum);
        return new DecisionDataLoader.Materials(
                List.of(candidate),
                List.of(new EligibilityRuleDef(activityId,
                        "numberAttr(\"orderAmount\") >= " + minimum)),
                Map.of(activityId, threshold));
    }

    @Test
    @DisplayName("第一阶段列出全部选项，不替用户挑")
    void phaseOneListsAll() {
        var svc = serviceReturning(List.of(
                withGifts("ACT-A", gift("保温杯", "9.9"), gift("雨伞", "19.9"))));
        var r = svc.options(req(), DecisionMode.EXPLAIN);
        assertThat(r.options()).hasSize(2);
        assertThat(r.options()).extracting(AddOnPurchaseService.AddOnOption::itemName)
                .containsExactly("保温杯", "雨伞");
        assertThat(r.options()).extracting(AddOnPurchaseService.AddOnOption::addOnPrice)
                .containsExactly(new BigDecimal("9.9"), new BigDecimal("19.9"));
    }

    @Test
    @DisplayName("没有生效活动时返回空列表（正常结果，不是错误）")
    void phaseOneEmptyIsNormal() {
        var r = serviceReturning(List.of()).options(req(), DecisionMode.EXPLAIN);
        assertThat(r.options()).isEmpty();
        assertThat(r.traces()).isNotEmpty();
    }

    @Test
    @DisplayName("加价金额缺失/为 0/为负的选项被排除——那不是加价购")
    void nonPositivePriceExcluded() {
        var svc = serviceReturning(List.of(
                withGifts("ACT-A", gift("白送的", "0"), gift("倒贴的", "-5"),
                        gift("没配价的", null), gift("正常的", "9.9"))));
        var r = svc.options(req(), DecisionMode.EXPLAIN);
        assertThat(r.options()).extracting(AddOnPurchaseService.AddOnOption::itemName)
                .containsExactly("正常的");
    }

    @Test
    @DisplayName("加价购资格边界：499 不出选项，500 才出选项，并带资格 trace")
    void optionsRespectEligibilityBoundary() {
        var below = serviceReturning(minimumOrderMaterials("ACT-LIMIT")).options(req("499"), DecisionMode.EXPLAIN);
        assertThat(below.options()).isEmpty();
        assertThat(below.traces()).anyMatch(t -> t.contains("eligibility reject: ACT-LIMIT"));

        var at = serviceReturning(minimumOrderMaterials("ACT-LIMIT")).options(req("500"), DecisionMode.EXPLAIN);
        assertThat(at.options()).hasSize(1);
        assertThat(at.traces()).contains("eligible: ACT-LIMIT");
    }

    @Test
    @DisplayName("第二阶段重新加载后重跑同一资格，资格变化会拒绝旧选项并带 trace")
    void quoteRechecksEligibility() {
        var rejected = serviceReturning(minimumOrderMaterials("ACT-LIMIT"))
                .quote(req("499"), "ACT-LIMIT", "保温杯", DecisionMode.EXPLAIN);
        assertThat(rejected.ok()).isFalse();
        assertThat(rejected.addOnPrice()).isNull();
        assertThat(rejected.traces()).anyMatch(t -> t.contains("eligibility reject: ACT-LIMIT"));

        var accepted = serviceReturning(minimumOrderMaterials("ACT-LIMIT"))
                .quote(req("500"), "ACT-LIMIT", "保温杯", DecisionMode.EXPLAIN);
        assertThat(accepted.ok()).isTrue();
        assertThat(accepted.traces()).contains("eligible: ACT-LIMIT");
    }

    @Test
    @DisplayName("同一服务的两阶段间门槛变更：quote 必须重新 load/requalify 并拒绝旧选项")
    void quoteReloadsAndRejectsWhenEligibilityChangesBetweenPhases() {
        DecisionDataLoader loader = mock(DecisionDataLoader.class);
        when(loader.load(any(), any(ActivityType.class), anyBoolean())).thenReturn(
                minimumOrderMaterials("ACT-LIMIT", 100),
                minimumOrderMaterials("ACT-LIMIT", 500));
        AddOnPurchaseService svc = service(loader);

        var first = svc.options(req("200"), DecisionMode.EXPLAIN);
        assertThat(first.options()).hasSize(1);

        var quote = svc.quote(req("200"), "ACT-LIMIT", "保温杯", DecisionMode.EXPLAIN);
        assertThat(quote.ok()).isFalse();
        assertThat(quote.addOnPrice()).isNull();
        assertThat(quote.traces()).anyMatch(t -> t.contains("eligibility reject: ACT-LIMIT"));
        verify(loader, times(2)).load(any(), any(ActivityType.class), anyBoolean());
    }

    @Test
    @DisplayName("同一服务的两阶段间换购品删除：quote 不得沿用第一阶段价格")
    void quoteReloadsAndRejectsWhenConfigurationChangesBetweenPhases() {
        DecisionDataLoader loader = mock(DecisionDataLoader.class);
        when(loader.load(any(), any(ActivityType.class), anyBoolean())).thenReturn(
                new DecisionDataLoader.Materials(
                        List.of(withGifts("ACT-A", gift("保温杯", "9.9"))), List.of(), Map.of()),
                new DecisionDataLoader.Materials(
                        List.of(withGifts("ACT-A", gift("雨伞", "19.9"))), List.of(), Map.of()));
        AddOnPurchaseService svc = service(loader);

        assertThat(svc.options(req(), DecisionMode.EXPLAIN).options())
                .extracting(AddOnPurchaseService.AddOnOption::itemName)
                .containsExactly("保温杯");
        var quote = svc.quote(req(), "ACT-A", "保温杯", DecisionMode.EXPLAIN);

        assertThat(quote.ok()).isFalse();
        assertThat(quote.addOnPrice()).isNull();
        assertThat(quote.reason()).contains("失效");
        verify(loader, times(2)).load(any(), any(ActivityType.class), anyBoolean());
    }

    @Test
    @DisplayName("第二阶段按「活动+换购品」重新查价，返回权威价格")
    void phaseTwoRequotes() {
        var svc = serviceReturning(List.of(withGifts("ACT-A", gift("保温杯", "9.9"))));
        var q = svc.quote(req(), "ACT-A", "保温杯", DecisionMode.EXPLAIN);
        assertThat(q.ok()).isTrue();
        assertThat(q.addOnPrice()).isEqualByComparingTo(new BigDecimal("9.9"));
    }

    @Test
    @DisplayName("**改价无效**：客户端传什么价都不影响结果——第二阶段根本不读价格入参")
    void clientCannotTamperPrice() {
        var svc = serviceReturning(List.of(withGifts("ACT-A", gift("保温杯", "9.9"))));
        // quote 的签名里压根没有"价格"这个参数，这本身就是防改价的设计。
        // 这条测试钉住这一点：只要接口还这样，改价就无从谈起。
        var q = svc.quote(req(), "ACT-A", "保温杯", DecisionMode.EXPLAIN);
        assertThat(q.addOnPrice()).isEqualByComparingTo(new BigDecimal("9.9"));
    }

    @Test
    @DisplayName("两阶段之间选项失效 → 拒绝，而不是沿用第一阶段的价格")
    void staleOptionRejected() {
        // 第一阶段拿到了选项，第二阶段活动已下线（loader 返回空）
        var stale = serviceReturning(List.of()).quote(req(), "ACT-A", "保温杯", DecisionMode.EXPLAIN);
        assertThat(stale.ok()).isFalse();
        assertThat(stale.addOnPrice()).as("失效时绝不能带出价格").isNull();
        assertThat(stale.reason()).contains("失效");
    }

    @Test
    @DisplayName("选了不存在的换购品 / 缺参数 → 拒绝且不抛异常")
    void guards() {
        var svc = serviceReturning(List.of(withGifts("ACT-A", gift("保温杯", "9.9"))));
        assertThat(svc.quote(req(), "ACT-A", "不存在的", DecisionMode.EXPLAIN).ok()).isFalse();
        assertThat(svc.quote(req(), "别的活动", "保温杯", DecisionMode.EXPLAIN).ok()).isFalse();
        assertThat(svc.quote(req(), null, "保温杯", DecisionMode.EXPLAIN).ok()).isFalse();
        assertThat(svc.quote(req(), "ACT-A", null, DecisionMode.EXPLAIN).ok()).isFalse();
    }
}
