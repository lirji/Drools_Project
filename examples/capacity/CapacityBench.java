import com.lrj.drools.activity.domain.ActivityCandidate;
import com.lrj.drools.activity.domain.ActivityRuleContext;
import com.lrj.drools.activity.domain.ActivityRuleResult;
import com.lrj.drools.activity.domain.ConditionNode;
import com.lrj.drools.activity.domain.FieldValueType;
import com.lrj.drools.activity.domain.RuleOperator;
import com.lrj.drools.activity.domain.SchemaField;
import com.lrj.drools.activity.engine.ActivityDrlBuilder;
import com.lrj.drools.activity.engine.ActivityDrlBuilder.EligibilityRuleDef;
import com.lrj.drools.activity.engine.ActivityDrlBuilder.LadderActivityDef;
import com.lrj.drools.activity.engine.ActivityDrlBuilder.LadderTier;
import com.lrj.drools.activity.engine.ActivityRuleRuntimeService;
import com.lrj.drools.activity.engine.ConditionTreeEvaluator;
import com.lrj.drools.activity.engine.RuleConditionTranslator;
import com.ql.util.express.DefaultContext;
import com.ql.util.express.ExpressRunner;
import com.ql.util.express.InstructionSet;
import org.kie.api.KieBase;
import org.kie.api.io.ResourceType;
import org.kie.api.runtime.StatelessKieSession;
import org.kie.internal.utils.KieHelper;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 活动容量基准：**同一份活动负载**分别用 Drools / QLExpress / 纯 Java 三条路承载，
 * 测「常驻足迹」「准备(编译)耗时」「单次决策延迟」三条约束线，回答
 * 「一个进程能扛多少个活动」。
 *
 * <p>刻意放在 Maven 源码根之外（沿用 {@code examples/aviator/} 的先例）：
 * 它引 QLExpress，而生产四个模块都不该有这个依赖。跑法见同目录 {@code run.sh}。
 *
 * <p><b>负载定义</b>（三条路完全等价，靠 §correctness 交叉校验钉死）：每个活动 =
 * 一棵 3 叶资格条件树 {@code AND(orderAmount>=x, vipLevel>=y, channel IN [APP,H5])}
 * + 一条 T 档阶梯（按 orderAmount 落档，{@code [min,max)}）。
 * 一次决策 = 对 N 个候选活动做资格判定，通过的再落档取金额。
 *
 * <p><b>为什么这么比才公平</b>：Drools 侧用的是生产同款 {@link ActivityDrlBuilder} 生成的 DRL，
 * 纯 Java 侧用的是生产同款 {@link ConditionTreeEvaluator}，QLExpress 侧把同一棵条件树翻译成
 * 等价脚本并**预编译成 InstructionSet 常驻**（对应它在生产里的用法：ExpressRunner 单例 + 指令集缓存）。
 * 三条路都不含数据库、不含网络，测的是纯引擎成本。
 */
public final class CapacityBench {

    /** 每个活动的阶梯档位数。运营侧「档位」是足迹主导项，故单独拎成参数。 */
    private static final int TIERS = Integer.getInteger("bench.tiers", 10);
    /** 决策延迟采样次数（大 N 时自动降采样，见 {@link #iterationsFor}）。 */
    private static final int SAMPLES = Integer.getInteger("bench.samples", 300);
    /** 单个引擎单个规模的准备耗时上限(ms)；超了就不再往更大规模跑，并**显式记为跳过**（no-silent-cap）。 */
    private static final long PREPARE_BUDGET_MS = Long.getLong("bench.prepareBudgetMs", 90_000L);

    /** 见 {@link #tiersOf}：true = 每个活动的档位边界各不相同，打掉 Drools 的 alpha 节点复用。 */
    private static final boolean DISTINCT_TIERS = Boolean.getBoolean("bench.distinctTiers");

    private static final String[] SCALES_DEFAULT = {"10", "50", "100", "200", "500", "1000", "2000", "5000"};

    // ------------------------------------------------------------------ 共享负载定义

    private static final Map<String, SchemaField> SCHEMA = schema();

    private static Map<String, SchemaField> schema() {
        Map<String, SchemaField> m = new LinkedHashMap<>();
        m.put("orderAmount", new SchemaField("orderAmount", "订单金额", FieldValueType.NUMBER,
                EnumSet.of(RuleOperator.GE, RuleOperator.GT, RuleOperator.LE, RuleOperator.LT,
                        RuleOperator.EQ, RuleOperator.BETWEEN), List.of()));
        m.put("vipLevel", new SchemaField("vipLevel", "会员等级", FieldValueType.NUMBER,
                EnumSet.of(RuleOperator.GE, RuleOperator.GT, RuleOperator.EQ), List.of()));
        m.put("channel", new SchemaField("channel", "渠道", FieldValueType.ENUM,
                EnumSet.of(RuleOperator.EQ, RuleOperator.IN, RuleOperator.NOT_IN),
                List.of("APP", "H5", "MINI", "PC")));
        return m;
    }

    /** 第 i 个活动的资格门槛金额：0,20,40,… 循环到 400，保证不同活动落在不同门槛上。 */
    private static int minAmountOf(int i) { return (i % 21) * 20; }

    /** 第 i 个活动的 VIP 门槛：0..3。 */
    private static int vipOf(int i) { return i % 4; }

    private static ConditionNode conditionTree(int i) {
        ConditionNode amt = leaf("orderAmount", "ge", new BigDecimal(minAmountOf(i)));
        ConditionNode vip = leaf("vipLevel", "ge", new BigDecimal(vipOf(i)));
        ConditionNode ch = leaf("channel", "in", List.of("APP", "H5"));
        ConditionNode root = new ConditionNode();
        root.setLogic("AND");
        root.setChildren(List.of(amt, vip, ch));
        return root;
    }

    private static ConditionNode leaf(String field, String op, Object value) {
        ConditionNode n = new ConditionNode();
        n.setField(field);
        n.setOp(op);
        n.setValue(value);
        return n;
    }

    /**
     * 第 i 个活动的阶梯：[0,100) → 5, [100,200) → 10, … 每档奖励 = (档序+1)×5。
     *
     * <p><b>{@code -Dbench.distinctTiers=true} 是这份基准里最关键的一个开关</b>：默认(false)所有活动
     * 共用同一组档位边界，这会让 Drools 的 alpha 网络**跨活动复用节点**——同一个
     * {@code numberAttr("orderAmount") >= 100} 约束，1000 个活动也只建一个 alpha 节点。
     * 开成 true 后每个活动的边界都错开，节点复用被打掉，测出的才是「运营各配各的档位」时的真实代价。
     * 纯 Java / QLExpress 两条路不存在节点共享这回事，两种模式下的数字应当几乎不变——
     * <b>这也正好是一条自校验</b>：如果它们也大幅变化，说明负载生成本身出了问题。
     */
    private static List<LadderTier> tiersOf(int i) {
        int offset = DISTINCT_TIERS ? i : 0;
        List<LadderTier> tiers = new ArrayList<>(TIERS);
        for (int t = 0; t < TIERS; t++) {
            tiers.add(new LadderTier(new BigDecimal(t * 100 + offset), new BigDecimal((t + 1) * 100 + offset),
                    new BigDecimal((t + 1) * 5)));
        }
        return tiers;
    }

    /** 决策上下文：第 s 次采样用不同订单金额，避免只打中同一档。 */
    private static ActivityRuleContext contextFor(int s) {
        ActivityRuleContext ctx = new ActivityRuleContext();
        ctx.putAttr("orderAmount", new BigDecimal((s % TIERS) * 100 + 55));
        ctx.putAttr("vipLevel", new BigDecimal(3));
        ctx.putAttr("channel", "APP");
        return ctx;
    }

    // ------------------------------------------------------------------ 结果模型

    record Result(String engine, int activities, int rules, long prepareMs,
                  long heapKb, long metaKb, double p50Micros, double p99Micros,
                  long checksum, String note) {}

    private static final List<Result> RESULTS = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        String[] scales = args.length > 0 ? args : SCALES_DEFAULT;
        System.out.printf(Locale.ROOT,
                "%n活动容量基准 | 每活动 %d 档阶梯 + 3 叶资格树 | 采样 %d 次 | 准备预算 %d ms%n",
                TIERS, SAMPLES, PREPARE_BUDGET_MS);
        System.out.println("JVM: " + System.getProperty("java.version")
                + " | maxHeap=" + (Runtime.getRuntime().maxMemory() >> 20) + "MB");

        boolean droolsAlive = true, qlAlive = true, javaAlive = true;
        warmupInfra();

        for (String s : scales) {
            int n = Integer.parseInt(s.trim());
            System.out.printf(Locale.ROOT, "%n===== N = %d 个活动 (阶梯规则 %d 条) =====%n", n, n * TIERS);
            if (javaAlive) javaAlive = record(benchJava(n));
            if (qlAlive) qlAlive = record(benchQlExpress(n));
            if (droolsAlive) droolsAlive = record(benchDrools(n));
            else System.out.printf(Locale.ROOT, "  %-10s 跳过（上一规模准备耗时已超预算 %d ms）%n", "DROOLS", PREPARE_BUDGET_MS);
        }

        printTable();
        printCorrectnessNote();
    }

    /** @return 该引擎是否继续跑更大规模（准备耗时未超预算） */
    private static boolean record(Result r) {
        RESULTS.add(r);
        System.out.printf(Locale.ROOT,
                "  %-10s 准备 %6d ms | 常驻 堆 %8d KB + Meta %7d KB = %7d KB | 决策 p50 %8.1f µs p99 %8.1f µs | checksum=%d%n",
                r.engine(), r.prepareMs(), r.heapKb(), r.metaKb(), r.heapKb() + r.metaKb(),
                r.p50Micros(), r.p99Micros(), r.checksum());
        return r.prepareMs() < PREPARE_BUDGET_MS;
    }

    // ------------------------------------------------------------------ 引擎 1：纯 Java（生产现状路径）

    private static Result benchJava(int n) {
        ConditionTreeEvaluator evaluator = new ConditionTreeEvaluator();

        gc();
        long h0 = usedHeap(), m0 = usedMeta();
        long t0 = System.nanoTime();
        List<ConditionNode> trees = new ArrayList<>(n);
        List<List<LadderTier>> ladders = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            trees.add(conditionTree(i));
            ladders.add(tiersOf(i));
        }
        long prepareMs = (System.nanoTime() - t0) / 1_000_000;
        gc();
        long heapKb = (usedHeap() - h0) / 1024, metaKb = (usedMeta() - m0) / 1024;

        java.util.function.IntToLongFunction decide = s -> {
            ActivityRuleContext ctx = contextFor(s);
            long sum = 0;
            for (int i = 0; i < n; i++) {
                if (!evaluator.matches(trees.get(i), ctx, SCHEMA)) continue;
                BigDecimal amount = ctx.numberAttr("orderAmount");
                for (LadderTier tier : ladders.get(i)) {
                    if (amount.compareTo(tier.min()) >= 0 && amount.compareTo(tier.max()) < 0) {
                        sum += tier.reward().longValue();
                        break;
                    }
                }
            }
            return sum;
        };
        double[] pct = measure(decide, n);
        long checksum = decide.applyAsLong(0);
        trees.size();   // keep alive
        return new Result("JAVA", n, n, prepareMs, heapKb, metaKb, pct[0], pct[1], checksum, "");
    }

    // ------------------------------------------------------------------ 引擎 2：QLExpress（预编译指令集常驻）

    private static Result benchQlExpress(int n) throws Exception {
        // isPrecise=true：金额场景走 BigDecimal（与本项目 BenefitMath 的精度立场一致）；isTrace=false 关轨迹
        ExpressRunner runner = new ExpressRunner(true, false);

        List<String> eligScripts = new ArrayList<>(n), ladderScripts = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            eligScripts.add(eligScript(i));
            ladderScripts.add(ladderScript(i));
        }

        gc();
        long h0 = usedHeap(), m0 = usedMeta();
        long t0 = System.nanoTime();
        List<InstructionSet> elig = new ArrayList<>(n), ladder = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            elig.add(runner.parseInstructionSet(eligScripts.get(i)));
            ladder.add(runner.parseInstructionSet(ladderScripts.get(i)));
        }
        long prepareMs = (System.nanoTime() - t0) / 1_000_000;
        gc();
        long heapKb = (usedHeap() - h0) / 1024, metaKb = (usedMeta() - m0) / 1024;

        java.util.function.IntToLongFunction decide = s -> {
            try {
                DefaultContext<String, Object> ctx = new DefaultContext<>();
                ctx.put("orderAmount", new BigDecimal((s % TIERS) * 100 + 55));
                ctx.put("vipLevel", new BigDecimal(3));
                ctx.put("channel", "APP");
                long sum = 0;
                for (int i = 0; i < n; i++) {
                    Object ok = runner.execute(elig.get(i), ctx, null, false, false);
                    if (!Boolean.TRUE.equals(ok)) continue;
                    Object reward = runner.execute(ladder.get(i), ctx, null, false, false);
                    if (reward instanceof Number num) sum += num.longValue();
                }
                return sum;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
        double[] pct = measure(decide, n);
        long checksum = decide.applyAsLong(0);
        return new Result("QLEXPRESS", n, n * 2, prepareMs, heapKb, metaKb, pct[0], pct[1], checksum,
                "指令集 " + (n * 2) + " 份常驻");
    }

    /** 资格脚本：与条件树语义等价（含存在性护栏，缺字段 fail-closed）。 */
    private static String eligScript(int i) {
        return "orderAmount != null && orderAmount >= " + minAmountOf(i)
                + " && vipLevel != null && vipLevel >= " + vipOf(i)
                + " && channel != null && (channel == \"APP\" || channel == \"H5\")";
    }

    /** 阶梯脚本：if 链落档，[min,max) 半开区间，与 DRL / Java 侧一致；未落档返回 null。 */
    private static String ladderScript(int i) {
        StringBuilder sb = new StringBuilder();
        for (LadderTier t : tiersOf(i)) {
            sb.append("if (orderAmount >= ").append(t.min().toPlainString())
                    .append(" && orderAmount < ").append(t.max().toPlainString())
                    .append(") { return ").append(t.reward().toPlainString()).append("; }\n");
        }
        sb.append("return null;");
        return sb.toString();
    }

    // ------------------------------------------------------------------ 引擎 3：Drools（生产同款 DRL 生成器）

    private static Result benchDrools(int n) {
        ActivityDrlBuilder builder = new ActivityDrlBuilder();
        RuleConditionTranslator translator = new RuleConditionTranslator();

        List<EligibilityRuleDef> eligDefs = new ArrayList<>(n);
        List<LadderActivityDef> ladderDefs = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            eligDefs.add(new EligibilityRuleDef("A" + i, translator.translate(conditionTree(i), SCHEMA)));
            ladderDefs.add(new LadderActivityDef("A" + i, tiersOf(i), "orderAmount"));
        }
        // explain=false：生产热路径同款（构建期就不 emit trace）
        String eligDrl = builder.buildEligibilityDrl(eligDefs, false);
        String ladderDrl = builder.buildLadderDrl(ladderDefs, false);
        int rules = ActivityRuleRuntimeService.countRules(eligDrl) + ActivityRuleRuntimeService.countRules(ladderDrl);

        gc();
        long h0 = usedHeap(), m0 = usedMeta();
        long t0 = System.nanoTime();
        KieBase eligBase = compile(eligDrl);
        KieBase ladderBase = compile(ladderDrl);
        long prepareMs = (System.nanoTime() - t0) / 1_000_000;
        gc();
        long heapKb = (usedHeap() - h0) / 1024, metaKb = (usedMeta() - m0) / 1024;

        java.util.function.IntToLongFunction decide = s -> {
            ActivityRuleContext ctx = contextFor(s);
            for (int i = 0; i < n; i++) {
                ctx.addCandidate(new ActivityCandidate(
                        com.lrj.drools.activity.domain.OfferSpec.builder().activityId("A" + i).build()));
            }
            // 资格：not ActivityRuleContext(约束) → reject；低 salience 收集 eligible
            fire(eligBase, ctx);
            // 阶梯：命中档位 setComputedAmount
            fire(ladderBase, ctx);
            long sum = 0;
            for (ActivityCandidate c : ctx.getCandidates()) {
                if (c.isEligible() && c.getComputedAmount() != null) sum += c.getComputedAmount().longValue();
            }
            return sum;
        };
        double[] pct = measure(decide, n);
        long checksum = decide.applyAsLong(0);
        eligBase.getKiePackages();   // keep alive
        ladderBase.getKiePackages();
        return new Result("DROOLS", n, rules, prepareMs, heapKb, metaKb, pct[0], pct[1], checksum,
                "KieBase 2 份（资格/阶梯），生成规则 " + rules + " 条");
    }

    private static void fire(KieBase kieBase, ActivityRuleContext ctx) {
        StatelessKieSession session = kieBase.newStatelessKieSession();
        session.setGlobal("result", new ActivityRuleResult());
        List<Object> facts = new ArrayList<>();
        facts.add(ctx);
        facts.addAll(ctx.getCandidates());
        session.execute(facts);
    }

    private static KieBase compile(String drl) {
        KieHelper helper = new KieHelper();
        helper.addContent(drl, ResourceType.DRL);
        return helper.build();
    }

    // ------------------------------------------------------------------ 测量基础设施

    /** 大 N 时降采样：单次决策本身就贵，跑满 SAMPLES 次没有额外信息量，只是拖时间。 */
    private static int iterationsFor(int n) {
        if (n >= 2000) return Math.max(20, SAMPLES / 20);
        if (n >= 500) return Math.max(50, SAMPLES / 5);
        return SAMPLES;
    }

    /** @return {p50, p99}，单位微秒 */
    private static double[] measure(java.util.function.IntToLongFunction decide, int n) {
        int iters = iterationsFor(n);
        int warmup = Math.max(5, iters / 3);
        for (int s = 0; s < warmup; s++) decide.applyAsLong(s);
        long[] nanos = new long[iters];
        for (int s = 0; s < iters; s++) {
            long t = System.nanoTime();
            decide.applyAsLong(s);
            nanos[s] = System.nanoTime() - t;
        }
        Arrays.sort(nanos);
        return new double[]{nanos[(int) (iters * 0.50)] / 1000.0,
                nanos[Math.min(iters - 1, (int) (iters * 0.99))] / 1000.0};
    }

    /**
     * 先热身共享基础设施，免得**一次性**成本被算进第一个规模的常驻足迹。
     *
     * <p>这一步不是可有可无的礼貌动作：Drools 的编译器 + 生成类机制约占 Metaspace 数 MB，
     * QLExpress 的解析器约占几十 KB，都是**整进程一份**、不随活动数增长。不剥离的话
     * N=10 那一行会把这些一次性开销摊在 10 个活动头上，得出「每活动 675KB」这种假结论。
     * 所以这里用**与正式负载同形状**的小样本把三条路都走一遍（不只是 {@code 1+1}），
     * 确保正式测量时该加载的类都已加载。
     */
    private static void warmupInfra() throws Exception {
        ActivityDrlBuilder b = new ActivityDrlBuilder();
        RuleConditionTranslator translator = new RuleConditionTranslator();
        compile(b.buildGiftDrl(false));
        compile(b.buildEligibilityDrl(
                List.of(new EligibilityRuleDef("warm0", translator.translate(conditionTree(0), SCHEMA))), false));
        compile(b.buildLadderDrl(
                List.of(new LadderActivityDef("warm0", tiersOf(0), "orderAmount")), false));

        ExpressRunner r = new ExpressRunner(true, false);
        DefaultContext<String, Object> ctx = new DefaultContext<>();
        ctx.put("orderAmount", new BigDecimal(155));
        ctx.put("vipLevel", new BigDecimal(3));
        ctx.put("channel", "APP");
        r.execute(r.parseInstructionSet(eligScript(0)), ctx, null, false, false);
        r.execute(r.parseInstructionSet(ladderScript(0)), ctx, null, false, false);

        new ConditionTreeEvaluator().matches(conditionTree(0), contextFor(0), SCHEMA);
        gc();
    }

    private static void gc() {
        for (int i = 0; i < 3; i++) {
            System.gc();
            try { Thread.sleep(120); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
    }

    private static long usedHeap() {
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    private static long usedMeta() {
        for (MemoryPoolMXBean p : ManagementFactory.getMemoryPoolMXBeans()) {
            if ("Metaspace".equals(p.getName())) return p.getUsage().getUsed();
        }
        return 0L;
    }

    // ------------------------------------------------------------------ 汇总输出

    private static void printTable() {
        System.out.printf(Locale.ROOT, "%n%n===================== 汇总（markdown，可直接贴文档） =====================%n%n");
        System.out.println("| 引擎 | 活动数 N | 生成规则/指令集 | 准备耗时 (ms) | 常驻堆 (KB) | 常驻 Metaspace (KB) | 合计 (KB) | 每活动 (KB) | 决策 p50 (µs) | 决策 p99 (µs) |");
        System.out.println("| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |");
        for (Result r : RESULTS) {
            long total = r.heapKb() + r.metaKb();
            System.out.printf(Locale.ROOT, "| %s | %d | %d | %d | %d | %d | %d | %.1f | %.1f | %.1f |%n",
                    r.engine(), r.activities(), r.rules(), r.prepareMs(), r.heapKb(), r.metaKb(),
                    total, total / (double) r.activities(), r.p50Micros(), r.p99Micros());
        }
    }

    /**
     * 三条路的 checksum 必须一致——不一致说明负载不等价，上面所有数字都不可比。
     * 这是本基准唯一的正确性闸门，不能省。
     */
    private static void printCorrectnessNote() {
        System.out.printf(Locale.ROOT, "%n===================== 等价性校验 =====================%n");
        Map<Integer, Map<String, Long>> byScale = new LinkedHashMap<>();
        for (Result r : RESULTS) {
            byScale.computeIfAbsent(r.activities(), k -> new LinkedHashMap<>()).put(r.engine(), r.checksum());
        }
        boolean allMatch = true;
        for (Map.Entry<Integer, Map<String, Long>> e : byScale.entrySet()) {
            long distinct = e.getValue().values().stream().distinct().count();
            boolean ok = distinct == 1;
            allMatch &= ok;
            System.out.printf(Locale.ROOT, "  N=%-6d %s  %s%n", e.getKey(),
                    ok ? "✅ 三引擎结果一致" : "❌ 结果不一致（负载不等价，数字不可比）", e.getValue());
        }
        System.out.println(allMatch
                ? "\n结论：三条路在同一负载上算出同一笔钱，上表的足迹/延迟可横向比较。"
                : "\n⚠️ 有规模下结果不一致，请先修负载定义再解读上表。");
    }
}
