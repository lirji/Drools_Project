package com.lrj.drools.activity.engine;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.lrj.drools.activity.domain.ActivityRuleContext;
import com.lrj.drools.activity.domain.ActivityRuleResult;
import com.lrj.drools.activity.domain.RuleScene;
import com.lrj.drools.activity.domain.StackStrategy;
import com.lrj.drools.activity.engine.ActivityDrlBuilder.EligibilityRuleDef;
import com.lrj.drools.activity.engine.ActivityDrlBuilder.LadderActivityDef;
import com.lrj.drools.activity.tenant.TenantContext;
import org.kie.api.KieBase;
import org.kie.api.builder.Message;
import org.kie.api.builder.Results;
import org.kie.api.event.rule.AfterMatchFiredEvent;
import org.kie.api.event.rule.DefaultAgendaEventListener;
import org.kie.api.io.ResourceType;
import org.kie.api.runtime.StatelessKieSession;
import org.kie.internal.utils.KieHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 活动规则运行时。对齐来源 {@code ActivityRuleEngine} + {@code KieBaseManager} + {@code DroolsRuleBackend}。
 *
 * - DRL 文本 → KieBase：{@link #compileOrGet} 按 DRL 内容缓存（同一份规则不重复编译，
 *   这是 Step 9 热加载缓存思路的复用；相同策略/相同候选集自然命中缓存）。
 * - 执行：StatelessKieSession（线程安全）+ global {@code result}，insert 上下文 + 候选，fireAllRules。
 * - fail-safe：eval 阶段任何编译/执行异常都返回 null，调用方回退旧 Java 逻辑（对齐来源）。
 *
 * <p><b>P1-6 有界缓存</b>：DRL→KieBase 缓存换成 Caffeine 有界缓存（LRU 淘汰 + 命中率统计），
 * 取代原无界 {@code ConcurrentHashMap}——通用化后 DRL 随 tenant/schema/活动/档位膨胀，无界缓存 = 更快 OOM。
 * cache key = <b>tenant + DRL 全文</b>（显式含 tenant 维度，非仅靠 DRL 因 schema 而异隐式分片）：让同一份规则也按租户分片，
 * 为 per-tenant 容量/淘汰/失效（Track B P0-5）留口，且不跨租户共享 KieBase。
 * <p><b>P1-7 explain</b>：eval 方法可传 {@code explain} 决定 DRL 是否 emit trace（默认 true 保留旧行为）。
 *
 * <p><b>P0-5 足迹加权淘汰（PERF-1，修「系统性误估」）</b>：原 {@code maximumSize}(按 KieBase <em>个数</em>) 把每个 KieBase
 * 当 1 单位——但 {@code ActivityKieBaseSizingTest} 实测证明 KieBase 足迹由**生成的规则数**（≈ ladder 总档位 / eligibility 条件活动数）
 * 主导，<em>不是</em>活动数：「1 活动 × 200 档」(200 规则,~5.4MB) ≈「10 活动 × 20 档」(200 规则,~5.2MB)，而按个数当权重会把前者当 1、
 * 比小 KieBase 低估 ~20×，噪声邻居（大规则集租户）能悄悄吃爆堆。改用 {@link Caffeine#maximumWeight} + {@code weigher}=**按实测足迹**
 * （{@code 堆+Metaspace ≈ 260KB + 37KB×规则数}）加权，预算 {@code cache-max-weight-kb} 配置化（默认 256MB）。每 KieBase 自带 classloader，
 * 生成的 rule 类落 <b>Metaspace</b>（实测 ~12KB/规则），故足迹合并堆+Metaspace 计权，预算同时封顶两个池；生产须配 {@code -XX:MaxMetaspaceSize}
 * ≥（共享 Drools 基础设施 ~80MB + 预算的 Metaspace 份额），淘汰 churn 下 classloader/Metaspace 回收验证属 P0-5 生产尾项（见 docs 容量模型）。
 */
@Service
public class ActivityRuleRuntimeService {

    private static final Logger log = LoggerFactory.getLogger(ActivityRuleRuntimeService.class);

    // P0-5 实测足迹系数（ActivityKieBaseSizingTest，2026-07-19，GC-delta 采样，SAMPLES=40）：
    // 单 KieBase（含自带 classloader）足迹 ≈ BASE + PER_RULE × 生成规则数。堆 ≈ 200KB+25KB×rules、
    // Metaspace ≈ 60KB+12KB×rules，合并 ≈ 260KB+37KB×rules。weigher 用合并值 → 单预算同时封顶堆与 Metaspace。
    private static final int FOOTPRINT_BASE_KB = 260;
    private static final int FOOTPRINT_PER_RULE_KB = 37;

    private final ActivityDrlBuilder drlBuilder;

    /** DRL 文本 → 已编译 KieBase。有界（足迹加权 LRU）+ 内容级 key（无碰撞）+ 命中率统计。 */
    private final Cache<String, KieBase> cache;

    /** P1-17 fire 上界 = base + perRule × 该 KieBase 生成规则数（**per-artifact，非全局常量**）。 */
    private final int maxFiresBase;
    private final int maxFiresPerRule;

    /**
     * P0-5 独立限速编译线程池：承接**发布预热 / 重建**的编译，与决策 tomcat 线程隔离——预热的编译 CPU 不抢决策核；
     * 有界队列 + {@code CallerRunsPolicy} 兜底（队列满时退化为调用线程编译，不丢任务、不无界堆积）。
     */
    private final ThreadPoolExecutor compileExecutor;

    public ActivityRuleRuntimeService(
            ActivityDrlBuilder drlBuilder,
            @Value("${activity.marketing.rule-engine.cache-max-weight-kb:262144}") long cacheMaxWeightKb,
            @Value("${activity.marketing.rule-engine.max-fires-base:2000}") int maxFiresBase,
            @Value("${activity.marketing.rule-engine.max-fires-per-rule:200}") int maxFiresPerRule) {
        this.drlBuilder = drlBuilder;
        this.maxFiresBase = maxFiresBase;
        this.maxFiresPerRule = maxFiresPerRule;
        // 权重 = 该 KieBase 的实测足迹(KB)，非「1 个」；maximumWeight = 堆+Metaspace 预算(KB)。
        this.cache = Caffeine.newBuilder()
                .maximumWeight(cacheMaxWeightKb)
                .weigher((String key, KieBase kb) -> footprintKb(key))
                .recordStats()
                .build();
        int maxThreads = Math.max(1, Runtime.getRuntime().availableProcessors() / 4);
        this.compileExecutor = new ThreadPoolExecutor(1, maxThreads, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(256),
                r -> { Thread t = new Thread(r, "activity-drl-warm"); t.setDaemon(true); return t; },
                new ThreadPoolExecutor.CallerRunsPolicy());
        log.info("[ActivityRuleRuntimeService] KieBase 缓存足迹加权：预算 {} KB (~{} MB)，权重≈{}KB+{}KB×生成规则数；"
                        + "fire 上界≈{}+{}×规则数(per-artifact)；预热编译池 max={} 线程",
                cacheMaxWeightKb, cacheMaxWeightKb / 1024, FOOTPRINT_BASE_KB, FOOTPRINT_PER_RULE_KB,
                maxFiresBase, maxFiresPerRule, maxThreads);
    }

    /**
     * P0-5 发布预热：在**独立编译池**异步把 DRL 编译进缓存（发布/重建时调用），使首个决策请求命中 warm、
     * 冷编译不落决策热路径。因编译线程无请求上下文，须显式传 {@code tenant} 以对齐缓存 key。single-flight：
     * 若决策线程同时首次编译同 key，Caffeine 保证只编一次。
     * @return Future（测试/调用方可 await；生产 fire-and-forget）
     */
    public Future<?> warmAsync(String tenant, String drl) {
        return compileExecutor.submit(() -> TenantContext.callWith(tenant, () -> {
            try {
                compileOrGet(drl);
            } catch (RuntimeException e) {
                log.warn("发布预热编译失败（不影响决策，热路径首请求会再编）: {}", e.toString());
            }
            return null;
        }));
    }

    @PreDestroy
    public void shutdown() {
        compileExecutor.shutdownNow();
    }

    /** 估算某缓存项（key = tenant + DRL）的 KieBase 足迹(KB) = BASE + PER_RULE × 生成规则数。 */
    public static int footprintKb(String key) {
        return FOOTPRINT_BASE_KB + FOOTPRINT_PER_RULE_KB * countRules(key);
    }

    /** 数 DRL 里的生成规则数（{@code rule "…"} 出现次数）——足迹的主导项，非活动数（P0-5 实证）。 */
    public static int countRules(String drl) {
        if (drl == null || drl.isEmpty()) {
            return 0;
        }
        int n = 0;
        for (int i = drl.indexOf("rule \""); i >= 0; i = drl.indexOf("rule \"", i + 6)) {
            n++;
        }
        return n;
    }

    // ------------------------------------------------------------------ 各场景（explain 默认 true 重载）

    /** 资格：淘汰不满足条件的候选，返回通过的 eligibleCandidates。异常返回 null（回退）。 */
    public ActivityRuleResult evalEligibility(ActivityRuleContext ctx, List<EligibilityRuleDef> defs) {
        return evalEligibility(ctx, defs, true);
    }

    public ActivityRuleResult evalEligibility(ActivityRuleContext ctx, List<EligibilityRuleDef> defs, boolean explain) {
        return safeRun(RuleScene.ELIGIBILITY, ctx, () -> drlBuilder.buildEligibilityDrl(defs, explain));
    }

    /** 折扣合并：按策略挑选/累加，返回命中活动与金额。异常返回 null（回退）。 */
    public ActivityRuleResult evalDiscount(ActivityRuleContext ctx, StackStrategy strategy) {
        return evalDiscount(ctx, strategy, true);
    }

    public ActivityRuleResult evalDiscount(ActivityRuleContext ctx, StackStrategy strategy, boolean explain) {
        return safeRun(RuleScene.DISCOUNT, ctx, () -> drlBuilder.buildDiscountDrl(strategy, explain));
    }

    /** 阶梯结算：按订单金额落档。异常返回 null（回退）。 */
    public ActivityRuleResult evalLadder(ActivityRuleContext ctx, List<LadderActivityDef> defs) {
        return evalLadder(ctx, defs, true);
    }

    public ActivityRuleResult evalLadder(ActivityRuleContext ctx, List<LadderActivityDef> defs, boolean explain) {
        return safeRun(RuleScene.LADDER, ctx, () -> drlBuilder.buildLadderDrl(defs, explain));
    }

    /** 买赠：保留有奖品的候选并汇总奖品。异常返回 null（回退）。 */
    public ActivityRuleResult evalGift(ActivityRuleContext ctx) {
        return evalGift(ctx, true);
    }

    public ActivityRuleResult evalGift(ActivityRuleContext ctx, boolean explain) {
        return safeRun(RuleScene.GIFT, ctx, () -> drlBuilder.buildGiftDrl(explain));
    }

    // ------------------------------------------------------------------ 编译 / 执行

    /**
     * DRL → KieBase，内容级缓存。编译失败抛带行号的 IllegalArgumentException
     * （创建/预览时严格校验用；eval 时由 {@link #safeRun} 捕获转 null 回退）。
     */
    public KieBase compileOrGet(String drl) {
        // key 显式带当前租户：同一份 DRL 也按租户分片（per-tenant 容量/淘汰口）。tenant 为空(单租户/无上下文)时用空前缀。
        String tenant = TenantContext.get();
        String key = (tenant == null ? "" : tenant) + " " + drl;
        return cache.get(key, k -> compile(drl));
    }

    /** 清缓存（规则/策略变更后可调，demo 里内容级缓存其实无需显式清）。 */
    public void evictAll() {
        cache.invalidateAll();
    }

    public int cacheSize() {
        return (int) cache.estimatedSize();
    }

    /** 当前缓存总足迹(KB)——加权淘汰下的实际占用近似（可观测/告警用）。maximumWeight 未生效时返回 -1。 */
    public long cacheWeightKb() {
        return cache.policy().eviction()
                .flatMap(e -> { var w = e.weightedSize(); return w.isPresent() ? java.util.Optional.of(w.getAsLong()) : java.util.Optional.empty(); })
                .orElse(-1L);
    }

    private KieBase compile(String drl) {
        KieHelper helper = new KieHelper();
        helper.addContent(drl, ResourceType.DRL);
        Results results = helper.verify();
        if (results.hasMessages(Message.Level.ERROR)) {
            String detail = results.getMessages(Message.Level.ERROR).stream()
                    .map(m -> "line " + m.getLine() + ": " + m.getText())
                    .collect(Collectors.joining("\n"));
            throw new IllegalArgumentException("活动规则编译失败:\n" + detail);
        }
        return helper.build();
    }

    private ActivityRuleResult safeRun(RuleScene scene, ActivityRuleContext ctx, java.util.function.Supplier<String> drlSupplier) {
        try {
            ctx.setScene(scene);
            String drl = drlSupplier.get();
            KieBase kieBase = compileOrGet(drl);
            return run(kieBase, ctx);
        } catch (Exception e) {
            log.warn("规则执行失败, 回退旧逻辑. scene={}, bizLine={}, err={}", scene, ctx.getBizLine(), e.toString());
            return null;
        }
    }

    private ActivityRuleResult run(KieBase kieBase, ActivityRuleContext ctx) {
        StatelessKieSession session = kieBase.newStatelessKieSession();
        ActivityRuleResult result = new ActivityRuleResult();
        session.setGlobal("result", result);

        // P1-17：fire 上界按**该 KieBase 的编译规则数**派生（per-artifact，非全局常量）——大规则集给大预算、
        // 小的给小的，基准已证成本随规则数线性。超界 halt + warn（no-silent-cap），safeRun 兜底回退旧逻辑。
        int rules = compiledRuleCount(kieBase);
        int maxFires = maxFiresBase + maxFiresPerRule * rules;
        FireCeilingListener ceiling = new FireCeilingListener(maxFires);
        session.addEventListener(ceiling);

        List<Object> facts = new ArrayList<>();
        facts.add(ctx);
        facts.addAll(ctx.getCandidates());
        session.execute(facts);

        if (ceiling.hitCeiling) {
            log.warn("规则 fire 触顶 halt：fires>{}（规则数={}，bizLine={}）—疑似 runaway，本次决策由 safeRun 回退旧逻辑",
                    maxFires, rules, ctx.getBizLine());
            throw new IllegalStateException("规则 fire 超上界 " + maxFires + "（runaway 护栏）");
        }
        return result;
    }

    /** 编译后的实际规则数（跨 package 求和）——fire 上界的派生量。 */
    private static int compiledRuleCount(KieBase kieBase) {
        return kieBase.getKiePackages().stream().mapToInt(p -> p.getRules().size()).sum();
    }

    /**
     * P1-17 fire 上界监听器：数 afterMatchFired，超 {@code maxFires} 即 {@code halt()} 停火 + 标记触顶。
     * 活动规则无 loop（生成 DRL 不含 update），正常 fire 数 ≈ 规则数×小常数，上界留足裕量，仅拦真 runaway。
     */
    private static final class FireCeilingListener extends DefaultAgendaEventListener {
        private final int maxFires;
        private int fired = 0;
        private boolean hitCeiling = false;

        FireCeilingListener(int maxFires) { this.maxFires = maxFires; }

        @Override
        public void afterMatchFired(AfterMatchFiredEvent event) {
            if (++fired > maxFires) {
                hitCeiling = true;
                event.getKieRuntime().halt();
            }
        }
    }
}
