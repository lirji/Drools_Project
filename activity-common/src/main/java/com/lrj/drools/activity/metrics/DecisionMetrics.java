package com.lrj.drools.activity.metrics;

import com.github.benmanes.caffeine.cache.Cache;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 决策链路业务指标（B0-3 / 计划 P0-1）。
 *
 * <p><b>为什么必须先有它</b>：改造前全仓 {@code activity-*} 主源码里没有一处 {@code MeterRegistry}——
 * Prometheus 抓的全是 JVM / HTTP / CPU 这类进程级指标，**决策本身是黑的**。
 * 最要命的是 {@code ActivityRuleRuntimeService.safeRun} 的 fail-safe：规则编译或执行异常时返回 null，
 * 决策回退到「取最大红包」的旧 Java 逻辑，只打一条 {@code log.warn}。
 * 这条路径会**改变实际发给用户的金额**，却既不计数、也不告警——
 * 唯一的痕迹是响应里 {@code mode} 字段变成 {@code legacy}，而没有人在监控它。
 *
 * <p>本类是后续所有改造的**度量基准**：不先把回退率、耗时、缓存命中率打出来，
 * 性能线做完无法证明变快了，权益线做完无法证明没改坏金额。
 *
 * <p><b>指标清单</b>
 * <table border="1">
 *   <caption>决策链路指标</caption>
 *   <tr><th>名称</th><th>类型</th><th>标签</th><th>用途</th></tr>
 *   <tr><td>{@code activity.decision.duration}</td><td>Timer</td><td>scene, mode</td><td>决策耗时分位（区分引擎 / 回退）</td></tr>
 *   <tr><td>{@code activity.decision.fallback}</td><td>Counter</td><td>scene, reason</td><td><b>回退率——头号告警项，会静默改金额</b></td></tr>
 *   <tr><td>{@code activity.decision.candidates}</td><td>Summary</td><td>scene</td><td>候选数分布（折扣合并是 O(N²)，N 要盯）</td></tr>
 *   <tr><td>{@code activity.rule.compile}</td><td>Timer</td><td>outcome</td><td>KieBase 编译次数与耗时（落在请求线程上的那部分）</td></tr>
 *   <tr><td>{@code activity.rule.fire.ceiling}</td><td>Counter</td><td>scene</td><td>fire 触顶（runaway 护栏被触发）</td></tr>
 *   <tr><td>{@code activity.rule.cache.*}</td><td>Gauge</td><td>—</td><td>KieBase 缓存命中率 / 条目数 / 足迹（Caffeine stats 绑上来）</td></tr>
 * </table>
 *
 * <p><b>建议告警</b>：{@code rate(activity_decision_fallback_total[5m]) / rate(activity_decision_duration_count[5m]) > 0.001}。
 */
@Component
public class DecisionMetrics {

    public static final String DURATION = "activity.decision.duration";
    public static final String FALLBACK = "activity.decision.fallback";
    public static final String CANDIDATES = "activity.decision.candidates";
    public static final String COMPILE = "activity.rule.compile";
    public static final String CEILING = "activity.rule.fire.ceiling";
    public static final String SOURCE = "activity.decision.source";

    private final MeterRegistry registry;

    public DecisionMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * 无采集实例——给**直接 {@code new} 服务的单元测试**用（{@code ActivityWarmTest} /
     * {@code ActivityFireCeilingTest} 都不起 Spring 上下文）。用真的 {@link SimpleMeterRegistry}
     * 而不是 null 对象：调用点无需任何空判，行为也与生产一致，只是没人抓取。
     */
    public static DecisionMetrics noop() {
        return new DecisionMetrics(new SimpleMeterRegistry());
    }

    // ---------------------------------------------------------------- 决策

    /** 计时一次决策。{@code mode} 取 {@code rule-engine} / {@code legacy}，与响应体里的 mode 字段同源。 */
    public <T> T timeDecision(String scene, Supplier<T> body, java.util.function.Function<T, String> modeOf) {
        long t0 = System.nanoTime();
        T out = body.get();
        Timer.builder(DURATION)
                .description("一次决策的端到端耗时")
                .tag("scene", scene)
                .tag("mode", safe(modeOf.apply(out)))
                .publishPercentileHistogram()
                .register(registry)
                .record(System.nanoTime() - t0, TimeUnit.NANOSECONDS);
        return out;
    }

    /**
     * 记一次回退。<b>这是本组指标里最重要的一条</b>——它意味着这次决策的金额由旧 Java 逻辑算出，
     * 而不是运营配置的规则。reason 用有限集合（编译失败 / 执行异常 / 空决策 / 开关关闭），不要塞异常全文，
     * 否则标签基数会爆。
     */
    public void fallback(String scene, String reason) {
        Counter.builder(FALLBACK)
                .description("决策回退到旧 Java 逻辑的次数（会改变实际发放金额）")
                .tag("scene", scene)
                .tag("reason", safe(reason))
                .register(registry)
                .increment();
    }

    /** 候选活动数分布。折扣 MAX/PRIORITY 走 O(N²) 自连接，N 是性能的直接自变量。 */
    public void candidates(String scene, int n) {
        DistributionSummary.builder(CANDIDATES)
                .description("单次决策的候选活动数")
                .tag("scene", scene)
                .publishPercentileHistogram()
                .register(registry)
                .record(n);
    }

    /**
     * 决策物料来自哪里：{@code snapshot}（代际快照，零查询）还是 {@code db}（逐请求查库）。
     *
     * <p>这条是快照包上线后的**首要观测项**：snapshot 占比应当接近 100%，
     * 掉下来就说明发布传播断了（代际没 bump、或轮询挂了），而症状是"变慢"而非"报错"——
     * 没有这个指标就只能等到 P99 告警才发现。
     */
    public void decisionSource(String scene, String source) {
        Counter.builder(SOURCE)
                .description("决策物料来源：snapshot=代际快照（零查询）/ db=逐请求查库")
                .tag("scene", safe(scene))
                .tag("source", safe(source))
                .register(registry)
                .increment();
    }

    // ---------------------------------------------------------------- 规则引擎

    /** 计时一次 KieBase 编译。{@code outcome} = ok / error。**编译落在请求线程上时，这里就是 P99 尖刺的来源**。 */
    public <T> T timeCompile(Supplier<T> body) {
        long t0 = System.nanoTime();
        String outcome = "ok";
        try {
            return body.get();
        } catch (RuntimeException e) {
            outcome = "error";
            throw e;
        } finally {
            Timer.builder(COMPILE)
                    .description("KieBase 编译耗时（DRL → 可执行规则）")
                    .tag("outcome", outcome)
                    .publishPercentileHistogram()
                    .register(registry)
                    .record(System.nanoTime() - t0, TimeUnit.NANOSECONDS);
        }
    }

    /** fire 触顶（runaway 护栏被触发）。正常业务不该出现，出现即需排查规则。 */
    public void fireCeiling(String scene) {
        Counter.builder(CEILING)
                .description("规则 fire 数触顶被 halt 的次数")
                .tag("scene", safe(scene))
                .register(registry)
                .increment();
    }

    /**
     * 把 Caffeine 的 {@code recordStats()} 绑成 Gauge。此前统计一直在采集、但没有任何出口，
     * 等于白采。命中率低于 ~99% 就说明「按候选集拼 DRL」导致的缓存键膨胀正在发生（评估报告 D2）。
     */
    public void bindKieBaseCache(Cache<?, ?> cache) {
        // 三个 Gauge 的**状态对象一律用 cache 本身**：Micrometer 对状态对象持弱引用，
        // 传一个构造期临时创建的 Supplier/lambda 会在构造返回后被 GC，指标随即变成 NaN——
        // 而 NaN 在面板上看起来只是"没数据"，是最难察觉的一种埋点失效（本项目已在 docker 验证中踩到一次）。
        registry.gauge("activity.rule.cache.entries", cache, c -> c.estimatedSize());
        registry.gauge("activity.rule.cache.hit.ratio", cache, c -> {
            var st = c.stats();
            long total = st.requestCount();
            return total == 0 ? 1.0 : (double) st.hitCount() / total;
        });
        registry.gauge("activity.rule.cache.weight.kb", cache, c ->
                c.policy().eviction().map(e -> e.weightedSize().orElse(-1L)).orElse(-1L));
    }

    private static String safe(String s) {
        return s == null || s.isBlank() ? "unknown" : s;
    }
}
