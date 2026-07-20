package com.lrj.drools.activity;

import com.lrj.drools.activity.engine.ActivityDrlBuilder;
import com.lrj.drools.activity.engine.ActivityDrlBuilder.LadderActivityDef;
import com.lrj.drools.activity.engine.ActivityDrlBuilder.LadderTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.kie.api.KieBase;
import org.kie.api.io.ResourceType;
import org.kie.internal.utils.KieHelper;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * P0-5 · 多租户内存容量模型——**实测** per-KieBase 保留堆 + Metaspace（杀掉评审「无 sizing 数学」PERF-1）。
 *
 * <p><b>这是测量工具，不是回归断言</b>。默认不进 {@code ./mvnw test}（gated on {@code -Dsizing=true}），
 * 因为它靠 GC-delta 采样、耗时、且绝对值随机器/JVM 波动。跑法：
 * <pre>
 *   ./mvnw test -Dtest=ActivityKieBaseSizingTest -Dsizing=true
 * </pre>
 * 建议加 {@code -DargLine="-Xmx1g -XX:+UseG1GC"} 稳定堆读数。
 *
 * <p><b>要证的核心事实</b>（评审 PERF-1）：KieBase 足迹由**生成的规则数 / alpha 节点数**主导，
 * <em>不是</em>「活动数」——ladder 每个档位 {@link ActivityDrlBuilder#buildLadderDrl} 生成一整条 rule（含 3 个 alpha 约束）。
 * 故「1 活动 × 200 档」与「200 活动 × 1 档」足迹相近，而按「活动数」当 Caffeine weight 会把前者系统性低估 200×。
 * 每个 KieBase 自带一个 classloader → 生成的 rule 类落 <b>Metaspace</b>，必须入模型（不只堆）。
 */
class ActivityKieBaseSizingTest {

    private final ActivityDrlBuilder builder = new ActivityDrlBuilder();

    /** 每种形状测多少个 KieBase 取均值（越多越稳，越慢）。 */
    private static final int SAMPLES = 40;

    @Test
    @EnabledIfSystemProperty(named = "sizing", matches = "true")
    void measureKieBaseFootprint() {
        // 预热：先编译弃掉几个，让 Drools 基础设施类（共享、一次性）先落 Metaspace，不算进 per-KieBase。
        for (int i = 0; i < 3; i++) {
            compile(ladderDrl("warmup" + i, 10, "orderAmount"));
        }

        System.out.println("\n================ P0-5 KieBase 堆/Metaspace 实测 (SAMPLES=" + SAMPLES + ") ================");
        System.out.printf("%-34s | %14s | %14s | %10s%n", "形状 (shape)", "堆/个 (KB)", "Metaspace/个(KB)", "规则数");

        // 形状族：证「档位/规则数主导，活动数不主导」
        Shape[] shapes = {
                new Shape("eligibility  1 活动",        () -> eligDrl(id(), 1),        1),
                new Shape("eligibility 50 活动",        () -> eligDrl(id(), 50),       50),
                new Shape("ladder   1 活动 ×  10 档",   () -> ladderDrl(id(), 10, "orderAmount"),  10),
                new Shape("ladder   1 活动 ×  50 档",   () -> ladderDrl(id(), 50, "orderAmount"),  50),
                new Shape("ladder   1 活动 × 200 档",   () -> ladderDrl(id(), 200, "orderAmount"), 200),
                new Shape("ladder  50 活动 ×   1 档",   () -> ladderMultiDrl(50, 1, "orderAmount"), 50),
                new Shape("ladder  10 活动 ×  20 档",   () -> ladderMultiDrl(10, 20, "orderAmount"), 200),
        };

        List<double[]> rows = new ArrayList<>();
        for (Shape s : shapes) {
            double[] m = measure(s.drl);
            rows.add(new double[]{m[0], m[1], s.rules});
            System.out.printf("%-34s | %14.1f | %14.1f | %10d%n", s.name, m[0] / 1024.0, m[1] / 1024.0, s.rules);
        }

        System.out.println("=====================================================================================");
        System.out.println("读法：比较『1 活动 × 200 档』vs『50 活动 × 1 档』——若足迹随『规则数/档位』而非『活动数』走，");
        System.out.println("      则 Caffeine weight 必须按生成规则数（≈总档位/总约束）计，不能按活动数。堆预算表见 docs。");
        System.out.println("=====================================================================================\n");
    }

    // ---- 测量：hold SAMPLES 个不同 KieBase，GC 前后测 used heap / Metaspace 差，除以 SAMPLES ----
    private double[] measure(java.util.function.Supplier<String> drlSupplier) {
        gc();
        long heap0 = usedHeap();
        long meta0 = usedMeta();
        List<KieBase> hold = new ArrayList<>(SAMPLES);
        for (int i = 0; i < SAMPLES; i++) {
            hold.add(compile(drlSupplier.get()));
        }
        gc();
        long heap1 = usedHeap();
        long meta1 = usedMeta();
        // 防 hold 被优化掉
        if (hold.size() != SAMPLES) throw new IllegalStateException("样本数异常");
        double perHeap = Math.max(0, heap1 - heap0) / (double) SAMPLES;
        double perMeta = Math.max(0, meta1 - meta0) / (double) SAMPLES;
        hold.clear();
        return new double[]{perHeap, perMeta};
    }

    private KieBase compile(String drl) {
        KieHelper helper = new KieHelper();
        helper.addContent(drl, ResourceType.DRL);
        return helper.build(); // 每次新 KieBase + 新 classloader（同 ActivityRuleRuntimeService.compile）
    }

    // ---- DRL 生成（复用真 builder，保证与热路径同形态）----
    private int seq = 0;
    private String id() { return "act" + (seq++); }

    /** N 个资格淘汰规则的 DRL（rule 数 = N）。 */
    private String eligDrl(String base, int activities) {
        List<ActivityDrlBuilder.EligibilityRuleDef> defs = new ArrayList<>();
        for (int i = 0; i < activities; i++) {
            defs.add(new ActivityDrlBuilder.EligibilityRuleDef(
                    base + "_" + i, "numberAttr(\"orderAmount\") != null && numberAttr(\"orderAmount\") >= " + (i + 1)));
        }
        return builder.buildEligibilityDrl(defs, false);
    }

    /** 1 活动 × tiers 档（rule 数 = tiers）。 */
    private String ladderDrl(String actId, int tiers, String field) {
        return builder.buildLadderDrl(List.of(new LadderActivityDef(actId, tiersOf(tiers), field)), false);
    }

    /** activities 活动 × tiersEach 档（rule 数 = activities × tiersEach）。 */
    private String ladderMultiDrl(int activities, int tiersEach, String field) {
        List<LadderActivityDef> defs = new ArrayList<>();
        String base = id();
        for (int a = 0; a < activities; a++) {
            defs.add(new LadderActivityDef(base + "_" + a, tiersOf(tiersEach), field));
        }
        return builder.buildLadderDrl(defs, false);
    }

    private List<LadderTier> tiersOf(int n) {
        List<LadderTier> tiers = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            tiers.add(new LadderTier(new BigDecimal(i * 100), new BigDecimal((i + 1) * 100), new BigDecimal(i + 1)));
        }
        return tiers;
    }

    // ---- 内存读数 ----
    private static long usedHeap() {
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    private static long usedMeta() {
        for (MemoryPoolMXBean p : ManagementFactory.getMemoryPoolMXBeans()) {
            if ("Metaspace".equals(p.getName())) return p.getUsage().getUsed();
        }
        return 0;
    }

    private static void gc() {
        for (int i = 0; i < 4; i++) {
            System.gc();
            try { Thread.sleep(80); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    private record Shape(String name, java.util.function.Supplier<String> drl, int rules) {}
}
