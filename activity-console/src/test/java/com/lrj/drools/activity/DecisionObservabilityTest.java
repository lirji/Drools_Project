package com.lrj.drools.activity;

import com.lrj.drools.activity.domain.ActivityCreateRequest;
import com.lrj.drools.activity.domain.ActivityStatus;
import com.lrj.drools.activity.domain.ConditionNode;
import com.lrj.drools.activity.domain.DecisionMode;
import com.lrj.drools.activity.domain.SpuDiscountRequest;
import com.lrj.drools.activity.metrics.DecisionMetrics;
import com.lrj.drools.activity.service.ActivityMarketingService;
import com.lrj.drools.activity.service.ActivityMarketingService.CreateResult;
import com.lrj.drools.activity.service.ActivityQueryService;
import com.lrj.drools.activity.tenant.TenantContext;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>决策可观测性</b>：钱发多了、活动配错了、候选被淘汰了——这三件事必须在监控上看得见。
 *
 * <p>补这组指标之前，运营误配在 Grafana 上是<b>全盘绿灯</b>：把「满 300 减 50」配成
 * 「满 3 减 50」，回退率 0、耗时正常、命中数只是稍高——没有任何一条指标会动，
 * 因为**金额从来没被记录过**，而候选淘汰只写在一个热路径上根本不打开的字段里。
 *
 * <p>文档把「回退率」称作头号告警项，但回退只是改变金额的众多方式之一，
 * 且恰好是唯一被埋了点的那个。
 */
@SpringBootTest
@ActiveProfiles("h2")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:decobs;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "activity.marketing.seed-catalog-data=false"
})
@DisplayName("决策可观测性：金额 / 淘汰 / 封顶都要能被看见")
class DecisionObservabilityTest {

    private static final AtomicLong SPU = new AtomicLong(880_000L);

    @Autowired ActivityMarketingService marketing;
    @Autowired ActivityQueryService query;
    @Autowired MeterRegistry registry;

    @BeforeEach
    void bindTenant() { TenantContext.set("__dev__"); }

    @AfterEach
    void clear() { TenantContext.clear(); }

    @Test
    @DisplayName("发出去的金额被记录——「这个月发了多少钱」不必再从命中次数去估")
    void amountIsRecorded() {
        long spu = nextSpu();
        CreateResult a = online(red("金额观测", new BigDecimal("30"), spu, null));

        query.spuDiscount(req(spu, "500"), DecisionMode.HOT_PATH);

        double total = registry.find(DecisionMetrics.AMOUNT)
                .tag("activityId", a.activityId())
                .summaries().stream().mapToDouble(s -> s.totalAmount()).sum();
        assertEquals(30.0, total, 0.001,
                "决策出口手上握着 hitAmount 却只计了「命中了」—— 金额必须一起打点");
    }

    @Test
    @DisplayName("候选被淘汰要计数——「配了但不发」的唯一信号")
    void rejectIsCounted() {
        long spu = nextSpu();
        online(red("满100可用", new BigDecimal("20"), spu, leaf("orderAmount", "ge", 100)));

        double before = counter(DecisionMetrics.REJECT, "reason", "ineligible");
        query.spuDiscount(req(spu, "99"), DecisionMode.HOT_PATH);   // 99 < 100 → 资格淘汰
        double after = counter(DecisionMetrics.REJECT, "reason", "ineligible");

        assertEquals(before + 1, after, 0.001,
                "热路径 explain=false，rejectReason 与 trace 两个出口都不打开；"
                        + "没有这个计数器，「活动上线了但用户说没优惠」在监控上完全不可见");
    }

    @Test
    @DisplayName("算额阶段的淘汰按原因分开计数（排查方向完全不同）")
    void benefitRejectCarriesReason() {
        long spu = nextSpu();
        // 一口价 999，订单只有 10 元 → 一口价高于基数，不适用
        online(flash("贵秒杀", new BigDecimal("999"), spu));

        double before = counter(DecisionMetrics.REJECT, "reason", "price-above-base");
        query.spuDiscount(req(spu, "10"), DecisionMode.HOT_PATH);
        double after = counter(DecisionMetrics.REJECT, "reason", "price-above-base");

        assertEquals(before + 1, after, 0.001,
                "「一口价比订单还贵」与「用户不满足门槛」必须能分开——前者查配置，后者查人群");
    }

    @Test
    @DisplayName("金额被封顶要计数——正常业务应恒为 0，出现一次就该看一眼")
    void clampIsCounted() {
        long spu = nextSpu();
        online(red("超额券", new BigDecimal("50"), spu, null));

        double before = counter(DecisionMetrics.CLAMPED, null, null);
        query.spuDiscount(req(spu, "30"), DecisionMode.HOT_PATH);   // 50 元券打在 30 元订单上
        double after = counter(DecisionMetrics.CLAMPED, null, null);

        assertEquals(before + 1, after, 0.001,
                "能触发封顶的配置几乎一定是错的，而这在补这个计数器之前完全不可见");
    }

    @Test
    @DisplayName("买赠通道也计命中——此前 metrics.hit 只在红包出口打过")
    void giftChannelIsCounted() {
        long spu = nextSpu();
        CreateResult a = online(gift("满额赠", spu));

        query.buyAndGetGifts(req(spu, "500"), DecisionMode.HOT_PATH);

        double hits = registry.find(DecisionMetrics.HIT)
                .tag("scene", "gifts")
                .tag("activityId", a.activityId())
                .counters().stream().mapToDouble(c -> c.count()).sum();
        assertTrue(hits >= 1,
                "「按活动看命中量」在买赠通道上此前恒为 0 —— 不是没人用，是根本没埋");
    }

    // ---- helpers ----

    private double counter(String name, String tagKey, String tagValue) {
        var search = registry.find(name);
        if (tagKey != null) search = search.tag(tagKey, tagValue);
        return search.counters().stream().mapToDouble(c -> c.count()).sum();
    }

    private static long nextSpu() { return SPU.incrementAndGet(); }

    private CreateResult online(ActivityCreateRequest req) {
        CreateResult r = marketing.create(req);
        marketing.changeStatus(r.activityId(), r.version(), ActivityStatus.ONLINE.code());
        return r;
    }

    private static SpuDiscountRequest req(long spu, String amount) {
        return new SpuDiscountRequest(List.of(spu), 1001L, "110000", List.of("vip"),
                new BigDecimal(amount), 1, null);
    }

    private static ConditionNode leaf(String field, String op, Object value) {
        ConditionNode n = new ConditionNode();
        n.setField(field); n.setOp(op); n.setValue(value);
        return n;
    }

    private ActivityCreateRequest red(String name, BigDecimal amount, long spu, ConditionNode cond) {
        return base(name, 1, amount, "元", spu, cond, null);
    }

    private ActivityCreateRequest flash(String name, BigDecimal price, long spu) {
        return base(name, 1, price, "价", spu, null, null);
    }

    private ActivityCreateRequest gift(String name, long spu) {
        return base(name, 5, null, "元", spu, null,
                List.of(new ActivityCreateRequest.GiftInput("B1", "赠品", "GOODS", 1, BigDecimal.ZERO, "GIFT")));
    }

    private ActivityCreateRequest base(String name, int type, BigDecimal amount, String unit, long spu,
                                       ConditionNode cond, List<ActivityCreateRequest.GiftInput> gifts) {
        long now = System.currentTimeMillis();
        return new ActivityCreateRequest(
                null, null, name, "obs-biz", type, name,
                now - 3_600_000L, now + 3_600_000L, 1, null, 1, 100,
                1, amount, unit, null, "MAX",
                cond, List.of(new ActivityCreateRequest.SpuBinding(1, spu)), null, gifts);
    }
}
