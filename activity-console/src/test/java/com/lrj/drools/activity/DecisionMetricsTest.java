package com.lrj.drools.activity;

import com.lrj.drools.activity.domain.ActivityCreateRequest;
import com.lrj.drools.activity.domain.ActivityStatus;
import com.lrj.drools.activity.domain.SpuDiscountRequest;
import com.lrj.drools.activity.metrics.DecisionMetrics;
import com.lrj.drools.activity.service.ActivityMarketingService;
import com.lrj.drools.activity.service.ActivityMarketingService.CreateResult;
import com.lrj.drools.activity.service.ActivityQueryService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.search.RequiredSearch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B0-3：决策链路指标真的被打出来了。
 *
 * <p><b>为什么值得单独测</b>：指标最常见的失效方式不是「值不准」，而是**根本没注册**——
 * 代码里写了 `Counter.builder(...)` 但那条分支从没走到，或者名字/标签拼错，
 * 结果 Grafana 上是一条永远为 0 的曲线，看起来像「系统很健康」。
 * 尤其 {@code activity.decision.fallback}：它为 0 到底是「没有回退」还是「压根没埋上」，
 * 光看面板分辨不出来——这正是本轮要消灭的那种静默。
 *
 * <p>本 context 刻意把 {@code rule-engine.enabled=false}，让**每一次决策都必然回退**，
 * 从而把回退计数器逼到一个确定的、可断言的值。
 */
@SpringBootTest
@ActiveProfiles("h2")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:actmetrics;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "activity.marketing.rule-engine.enabled=false",
        "activity.marketing.seed-demo-data=false"
})
@DisplayName("决策链路指标：耗时 / 回退 / 候选数")
class DecisionMetricsTest {

    @Autowired ActivityMarketingService marketing;
    @Autowired ActivityQueryService query;
    @Autowired MeterRegistry registry;

    private long hAgo() { return System.currentTimeMillis() - 3_600_000L; }
    private long hLater() { return System.currentTimeMillis() + 3_600_000L; }

    @Test
    void decisionEmitsDurationCandidatesAndFallback() {
        CreateResult a = marketing.create(red("指标用红包", new BigDecimal("40"), 7701L));
        marketing.changeStatus(a.activityId(), a.version(), ActivityStatus.ONLINE.code());

        var view = query.spuDiscount(new SpuDiscountRequest(
                List.of(7701L), 1L, null, List.of(), new BigDecimal("200"), 1));
        assertEquals("legacy", view.mode(), "本 context 关了引擎，应走 legacy");

        // ① 耗时：Timer 必须存在，且 mode 标签与响应体里的 mode 同源（面板才能按 legacy/rule-engine 分开看）
        RequiredSearch duration = registry.get(DecisionMetrics.DURATION)
                .tag("scene", "spu-discount").tag("mode", "legacy");
        assertEquals(1, duration.timer().count(), "一次决策应记一次耗时");
        assertTrue(duration.timer().totalTime(java.util.concurrent.TimeUnit.NANOSECONDS) > 0);

        // ② 回退：**头号告警项**。引擎关闭时每次决策必回退，计数必须涨
        double fallback = registry.get(DecisionMetrics.FALLBACK)
                .tag("scene", "spu-discount").tag("reason", "engine-disabled")
                .counter().count();
        assertEquals(1.0, fallback, "开关关闭的回退必须被计数，否则线上无法区分『没回退』与『没埋点』");

        // ③ 候选数：折扣合并是 O(N²) 自连接，N 是性能自变量，必须可观测
        assertEquals(1, registry.get(DecisionMetrics.CANDIDATES)
                .tag("scene", "spu-discount").summary().count());
        assertEquals(1.0, registry.get(DecisionMetrics.CANDIDATES)
                .tag("scene", "spu-discount").summary().max());
    }

    @Test
    void kieBaseCacheGaugesAreBound() {
        // Caffeine 一直在 recordStats()，但改造前没有任何出口。绑上之后这三个 Gauge 必须存在，
        // 否则「缓存命中率」这条判断 D2 组合爆炸是否发生的依据就不可观测。
        assertTrue(registry.find("activity.rule.cache.entries").gauge() != null,
                "KieBase 缓存条目数未绑定");
        assertTrue(registry.find("activity.rule.cache.hit.ratio").gauge() != null,
                "KieBase 缓存命中率未绑定");
        assertTrue(registry.find("activity.rule.cache.weight.kb").gauge() != null,
                "KieBase 缓存足迹未绑定");

        // 光断言"Gauge 存在"不够：Micrometer 对状态对象持弱引用，状态对象被 GC 后
        // Gauge 仍然存在、但取值变成 NaN，面板上看起来只是"没数据"。必须断言取值有效。
        registry.find("activity.rule.cache.entries").gauges().forEach(g ->
                assertTrue(!Double.isNaN(g.value()), "entries 变成 NaN——状态对象被 GC 了"));
        registry.find("activity.rule.cache.hit.ratio").gauges().forEach(g ->
                assertTrue(!Double.isNaN(g.value()), "hit.ratio 变成 NaN——状态对象被 GC 了"));
        registry.find("activity.rule.cache.weight.kb").gauges().forEach(g ->
                assertTrue(!Double.isNaN(g.value()), "weight.kb 变成 NaN——状态对象被 GC 了"));
    }

    // ---- helpers ----

    private ActivityCreateRequest red(String name, BigDecimal amount, long spuId) {
        return new ActivityCreateRequest(
                null, null, name, "mall", 1, null, hAgo(), hLater(), 1, null, 1, 100,
                1, amount, "元", null, "MAX", null,
                List.of(new ActivityCreateRequest.SpuBinding(1, spuId)), null, null);
    }
}
