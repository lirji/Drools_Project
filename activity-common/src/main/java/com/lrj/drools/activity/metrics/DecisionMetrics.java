package com.lrj.drools.activity.metrics;

import com.github.benmanes.caffeine.cache.Cache;
import com.lrj.drools.activity.domain.DecisionScene;
import com.lrj.drools.activity.domain.RejectReason;
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
    /** 按活动的命中计数。**带基数上限**，见 {@link #hit}。 */
    public static final String HIT = "activity.decision.hit";
    /** 减免额被订单金额截断的次数。**几乎等价于「有人配错了」**，见 {@link #clamped}。 */
    public static final String CLAMPED = "activity.decision.clamped";
    /** 实际发出的减免金额分布。**运营误配的唯一观测信号**，见 {@link #amount}。 */
    public static final String AMOUNT = "activity.decision.amount";
    /** 候选被淘汰的次数（按原因）。「配了但不发」的唯一信号，见 {@link #reject}。 */
    public static final String REJECT = "activity.decision.reject";
    /** 当前进程持有的决策快照桶数。0 = 全部决策在走库。 */
    public static final String SNAPSHOT_COUNT = "activity.decision.snapshot.count";
    /** 最旧快照的年龄（秒）。**止损可观测性的核心读数**，见 {@link #bindSnapshotStore}。 */
    public static final String SNAPSHOT_AGE = "activity.decision.snapshot.age.seconds";

    /**
     * activityId 标签的基数上限。
     *
     * <p><b>为什么必须有这个上限</b>：把 activityId 直接当 Prometheus 标签是教科书级的
     * 基数爆炸——每个活动一条时间序列，活动是运营随手就能创建的，序列数不受工程控制。
     * 后果不是"图变多"，是 Prometheus 内存打满、抓取超时、**整套监控一起挂**，
     * 而挂掉的时刻恰好是活动最多的大促当天。
     *
     * <p>超出上限后一律打到 {@code __over_cap__} 这个哨兵标签上：总量仍然准确，
     * 只是分不出是哪几个活动。这比"要么没有按活动的指标、要么监控挂掉"好。
     */
    public static final int ACTIVITY_TAG_CAP = 200;
    public static final String OVER_CAP = "__over_cap__";

    private final MeterRegistry registry;

    /** 已经打过标的 activityId。用来判断"再放一个新的会不会超上限"。 */
    private final java.util.Set<String> taggedActivities =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    public DecisionMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * 记一次「某活动被命中」。
     *
     * <p>这是控制台「按活动看命中量」唯一的数据来源——此前没有它，所以工作台上那块
     * 指标卡只能写"尚未接入"。
     *
     * <p>基数由 {@link #ACTIVITY_TAG_CAP} 兜住：前 N 个活动各占一条序列，之后一律并进
     * {@link #OVER_CAP}。**不要因为"我们活动不多"就去掉这个上限**——活动数是运营行为，
     * 不是工程可控量，而基数爆炸的代价是整套监控在大促当天一起挂。
     */
    public void hit(DecisionScene scene, String activityId) {
        if (activityId == null || activityId.isBlank()) return;
        String tag = cappedTag(activityId);
        Counter.builder(HIT)
                .tag("scene", scene == null ? "unknown" : scene.code())
                .tag("activityId", tag)
                .register(registry)
                .increment();
    }

    /** 供只读端点聚合用。返回注册表本身，调用方只读不改。 */
    public MeterRegistry registry() {
        return registry;
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
    public <T> T timeDecision(DecisionScene scene, Supplier<T> body, java.util.function.Function<T, String> modeOf) {
        long t0 = System.nanoTime();
        T out = body.get();
        Timer.builder(DURATION)
                .description("一次决策的端到端耗时")
                .tag("scene", code(scene))
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
    public void fallback(DecisionScene scene, String reason) {
        fallback(code(scene), reason);
    }

    /**
     * 裸 String 档的回退计数。
     *
     * <p>TODO(R4·契约变更，独立提交)：唯一还在用它的调用点是
     * {@code ActivityRuleRuntimeService.safeRun}，它传的是 {@code RuleScene.name()}
     * （{@code ELIGIBILITY}/{@code LADDER}/{@code GIFT}）——<b>又一套与 {@link DecisionScene} 对不上的词汇</b>，
     * 于是「买赠通道一共回退了多少次」按 {@code scene="gifts"} 查会漏掉规则执行失败的那些。
     * 换成通道枚举会改变已有 Prometheus 序列（同 {@link #decisionSource} 那条 TODO），
     * 需与 Grafana 面板/告警同批改，故不在本批次里动。
     */
    public void fallback(String scene, String reason) {
        Counter.builder(FALLBACK)
                .description("决策回退到旧 Java 逻辑的次数（会改变实际发放金额）")
                .tag("scene", safe(scene))
                .tag("reason", safe(reason))
                .register(registry)
                .increment();
    }

    /** 候选活动数分布。折扣 MAX/PRIORITY 走 O(N²) 自连接，N 是性能的直接自变量。 */
    public void candidates(DecisionScene scene, int n) {
        DistributionSummary.builder(CANDIDATES)
                .description("单次决策的候选活动数")
                .tag("scene", code(scene))
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
    // TODO(R4·契约变更，独立提交)：这里的 scene 仍是调用方传进来的 ActivityType.name()
    //  （RED_PACKAGE / BUY_AND_GET / ADD_ON_PURCHASE），与 DecisionScene 的通道词汇表对不上，
    //  于是 activity_decision_source_total{scene="gifts"} 查出来是空的。换成 DecisionScene.code()
    //  会**改变已有 Prometheus 序列**，Grafana 面板与告警必须同批改（deploy/ 下有编排），
    //  所以刻意不在本批次里动——那是有意的契约变更，不该混进「行为等价」的重构里。
    public void decisionSource(String scene, String source) {
        Counter.builder(SOURCE)
                .description("决策物料来源：snapshot=代际快照（零查询）/ db=逐请求查库")
                .tag("scene", safe(scene))
                .tag("source", safe(source))
                .register(registry)
                .increment();
    }

    /**
     * 记一次「减免额超过订单金额、被截断」。
     *
     * <p><b>这条指标的价值不在截断本身，而在它是运营误配的唯一告警信号。</b>
     * 能触发封顶的配置几乎一定是错的（门槛写反、面额多打一个零、多券叠加没上限），
     * 而这类错误在此之前于监控上完全隐形：不走回退、耗时正常、命中数只是稍高。
     * 正常业务下它应当恒为 0，所以告警阈值可以设得极其激进——<b>出现一次就该看一眼</b>。
     *
     * <p>刻意不打 activityId 标签：触发时的活动 id 在 WARN 日志里（含金额、订单金额、策略），
     * 而指标只回答「有没有发生」。基数账见 {@link #ACTIVITY_TAG_CAP}。
     */
    public void clamped() {
        Counter.builder(CLAMPED)
                .description("减免额超过订单金额被截断的次数（正常业务应恒为 0，出现即疑似配置错误）")
                .register(registry)
                .increment();
    }

    /**
     * 记一次「实际发出了多少钱」。
     *
     * <p><b>补它之前，运营误配在监控上是全盘绿灯</b>：把「满 300 减 50」配成「满 3 减 50」，
     * 回退率 0、耗时正常、命中数只是稍高——没有任何一条指标会动，因为
     * <b>金额从来没被记录过</b>。{@code ActivityQueryService} 手上握着 {@code hitAmount}，
     * 却只打了一个「命中了」的计数。
     *
     * <p>有了它，「客单减免均值突然翻倍」「p99 减免额异常」这类查询才成立，
     * 财务问「这个月营销发了多少」也不必再从命中<em>次数</em>去估。
     *
     * <p>activityId 标签复用 {@link #hit} 那套基数保护（{@link #ACTIVITY_TAG_CAP}）。
     */
    public void amount(DecisionScene scene, String activityId, java.math.BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) return;
        DistributionSummary.builder(AMOUNT)
                .description("单次决策实际发出的减免金额（元）")
                .baseUnit("yuan")
                .tag("scene", code(scene))
                .tag("activityId", cappedTag(activityId))
                .publishPercentileHistogram()
                .register(registry)
                .record(amount.doubleValue());
    }

    /**
     * 记一次「候选被淘汰」。
     *
     * <p><b>「配了但不发」此前在监控上完全不可见。</b>淘汰只写在候选对象的 {@code rejectReason} 上，
     * 而热路径是 {@code DecisionMode.HOT_PATH}，那个字段与 trace 两个出口在生产上都不打开。
     * 唯一沾边的是空决策回退——可它只在「这张券是唯一候选」时才会走到，
     * <b>恰恰是多活动并存这种最容易配错的场景观测全黑</b>。
     *
     * <p>{@code reason} 是有限枚举 {@link RejectReason}——码与文案钉在同一行，
     * 标签基数因此天然封闭。<b>这份清单曾经在 javadoc 里手抄过一遍并且抄错了</b>
     * （这里写 {@code price-above-order}，代码实际发 {@code price-above-base}），
     * 所以现在不再复述取值：**以 {@link RejectReason} 枚举为准**。
     * 它的价值与回退率同级：一个回答「算错了吗」，一个回答「为什么没发」。
     */
    public void reject(DecisionScene scene, RejectReason reason) {
        Counter.builder(REJECT)
                .description("候选活动被淘汰的次数（按原因）——「配了但不发」的唯一信号")
                .tag("scene", code(scene))
                .tag("reason", reason == null ? "unknown" : reason.code())
                .register(registry)
                .increment();
    }

    /** activityId 的基数保护：前 N 个各占一条序列，之后并入哨兵。见 {@link #ACTIVITY_TAG_CAP}。 */
    private String cappedTag(String activityId) {
        if (activityId == null || activityId.isBlank()) return "unknown";
        if (taggedActivities.contains(activityId)) return activityId;
        if (taggedActivities.size() >= ACTIVITY_TAG_CAP) return OVER_CAP;
        taggedActivities.add(activityId);
        return activityId;
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

    /**
     * 把决策快照的**新鲜度**绑成 Gauge。只有 decision 侧会调用（console 没有快照构建器，store 恒空）。
     *
     * <p><b>为什么这两个指标必须有</b>：快照陈旧是一种「决策照常成功、只是发的钱是旧配置」的故障——
     * 代际信号漏发、轮询线程卡死、构建持续失败，三种成因在<b>回退率、耗时、命中数上全部看不出来</b>，
     * 因为它压根不走回退。在补这两个 gauge 之前，「下线了但线上还在发钱」在监控上是完全不可见的。
     *
     * <p><b>为什么不按 (tenant,bizLine) 打标签</b>：那是 {@link #ACTIVITY_TAG_CAP} 已经教过一次的
     * 基数账——租户 × 业务线同样不是工程可控量。告警要的是「最旧的那个有多旧」这一个标量，
     * 逐桶的 generation / builtAt 走 {@code GET /decision/v1/metrics} 的响应体（单实例视角、无序列成本）。
     *
     * <p>状态对象一律传 {@code store} 本身：Micrometer 对状态对象持<b>弱引用</b>，传构造期临时 lambda
     * 会在方法返回后被 GC，指标随即变成 NaN——而 NaN 在面板上只表现为「没数据」，是最难察觉的埋点失效
     * （本项目已在 docker 验证中踩过一次，见 {@link #bindKieBaseCache}）。
     */
    public void bindSnapshotStore(com.lrj.drools.activity.snapshot.DecisionSnapshotStore store) {
        registry.gauge(SNAPSHOT_COUNT, store, s -> s.size());
        registry.gauge(SNAPSHOT_AGE, store, s -> s.oldestAgeSeconds(java.time.Instant.now()));
    }

    private static String safe(String s) {
        return s == null || s.isBlank() ? "unknown" : s;
    }

    /** scene 标签取值的唯一出口。枚举保证取值封闭，null 只可能来自编程错误，兜成 unknown 而不是抛。 */
    private static String code(DecisionScene scene) {
        return scene == null ? "unknown" : scene.code();
    }
}
