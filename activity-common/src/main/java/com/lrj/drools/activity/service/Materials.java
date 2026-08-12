package com.lrj.drools.activity.service;

import com.lrj.drools.activity.domain.ActivityCandidate;
import com.lrj.drools.activity.domain.ConditionNode;
import com.lrj.drools.activity.domain.DecisionProvenance;
import com.lrj.drools.activity.domain.StackStrategy;
import com.lrj.drools.activity.engine.ActivityDrlBuilder.EligibilityRuleDef;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次决策所需的**全部物料**——取数层的唯一出参。
 *
 * <p><b>为什么是独立的顶层类型</b>：它此前嵌在 {@code DecisionDataLoader} 里，于是快照那条路
 * 没法产出同一个类型，只好另造一个自称「与 Materials 同形」的影子 record（{@code DecisionSnapshot.Materialized}），
 * 再由 {@code load()} 手工把两者缝起来：拆开候选与约束、手算最小代际、手拼 provenance、
 * 最后还要在另一个方法里**第二次判定来源**才能取到合并策略。同形靠注释维持，
 * 缝合逻辑散在三处——这正是「同一张券在两条路上发不同的钱」那类故障的温床。
 * 现在两条路在**类型上**只能产出同一个值对象，缝合动作收敛成 {@link #merge} 与 {@link #ordered}。
 *
 * <p><b>不做的事</b>：规范构造器<b>不定序</b>。定序只发生在 {@link #ordered()}，且只在取数层出口调用一次。
 * 在构造器里强制定序看着更保险，实际会改变所有手工构造物料的测试桩的候选顺序，
 * 而 {@code BenefitEvaluator} 的 {@code pickByAmount}/{@code pickByPriority} 打平时是严格 {@code >}
 * （先到先得）——一批断言会因此静默翻面。
 *
 * @param candidates       拍平后的候选事实（已按上线 / 时间窗 / 类型过滤）
 * @param eligibilityDefs  有资格条件的候选对应的受控约束（无条件的活动不出现在这里 = 恒通过）
 * @param eligibilityTrees 候选 id → 已解析的条件树。<b>只装本次命中的候选</b>：下游
 *                         {@code DecisionEligibilityService} 只做 {@code trees.get(candidateId)}，
 *                         按桶全量塞进来的那些树没有任何消费者，只是让每次决策白拷一遍整条业务线
 * @param provenance       这次物料是快照还是走库（见 {@link DecisionProvenance}）
 * @param strategy         bizLine 级权益合并策略。<b>只有红包通道读它</b>——买赠与加价购不合并权益，
 *                         走库时不会为它们发这一条查询（那等于给两条热路径白加一次往返），
 *                         故这两条通道在走库路径上恒为默认的 {@link StackStrategy#MAX}；
 *                         快照路径上它是随桶白来的，仍是该 bizLine 的真值
 */
public record Materials(List<ActivityCandidate> candidates,
                        List<EligibilityRuleDef> eligibilityDefs,
                        Map<String, ConditionNode> eligibilityTrees,
                        DecisionProvenance provenance,
                        StackStrategy strategy) {

    /**
     * 候选定序 / 择首的唯一比较器：按 activityId 升序，null 排最后。
     *
     * <p>{@link #ordered()} 与 {@link #bizLine()} 必须用同一个，否则「排第一的候选」与
     * 「bizLine 的来源候选」会是两个不同的活动。
     */
    private static final Comparator<ActivityCandidate> BY_ACTIVITY_ID =
            Comparator.comparing(ActivityCandidate::getActivityId,
                    Comparator.nullsLast(Comparator.naturalOrder()));

    /**
     * 三参兼容构造：provenance 缺省为「走库」、策略缺省为 {@link StackStrategy#MAX}。
     *
     * <p>这不是为了少改几行——它让所有<b>手工构造物料</b>的测试桩与旧装配路径默认落在
     * 「db」这个**保守且真实**的取值上。缺省成 snapshot 才是危险的：那会让一条从没碰过快照的路径
     * 在响应里自称走了快照。
     */
    public Materials(List<ActivityCandidate> candidates,
                     List<EligibilityRuleDef> eligibilityDefs,
                     Map<String, ConditionNode> eligibilityTrees) {
        this(candidates, eligibilityDefs, eligibilityTrees, DecisionProvenance.db(), StackStrategy.MAX);
    }

    /** 没有任何候选的走库结果。**不查合并策略**——没有候选就没有要合并的东西，MAX 即可。 */
    public static Materials empty() {
        return new Materials(List.of(), List.of(), Map.of());
    }

    /**
     * 单个快照桶物化出来的那一份。{@code generation} 与 {@code strategy} 都是**这个桶自己的**，
     * 合并成一次决策的物料时由 {@link #merge} 收敛。
     */
    public static Materials snapshotBucket(List<ActivityCandidate> candidates,
                                           List<EligibilityRuleDef> eligibilityDefs,
                                           Map<String, ConditionNode> eligibilityTrees,
                                           long generation,
                                           StackStrategy strategy) {
        return new Materials(candidates, eligibilityDefs, eligibilityTrees,
                DecisionProvenance.snapshot(generation, 1), strategy);
    }

    /**
     * 把该租户各业务线桶物化出的多份物料合成一次决策的物料。
     *
     * <p>三条收敛规则：
     * <ul>
     *   <li><b>代际取最小</b>：一次决策会合并该租户所有业务线的桶，任何一个桶落后都意味着
     *       「刚发布的那次还没全进去」。取最大值会把落后的那个桶藏起来。**没有候选的桶也参与取最小**，
     *       与合并前逐字节一致</li>
     *   <li><b>桶数取份数</b>：即参与本次决策的桶数，不是「出了候选的桶数」</li>
     *   <li><b>策略取 {@link #bizLine()} 所属那一份</b>：即 activityId 最小的候选所在的桶。
     *       它与走库路径的取值规则同源（见 {@link #bizLine()}），所以两条路在跨业务线时也一致</li>
     * </ul>
     */
    public static Materials merge(List<Materials> buckets) {
        List<ActivityCandidate> cands = new ArrayList<>();
        List<EligibilityRuleDef> defs = new ArrayList<>();
        Map<String, ConditionNode> trees = new LinkedHashMap<>();
        Long minGeneration = null;
        ActivityCandidate lead = null;
        StackStrategy strategy = StackStrategy.MAX;

        for (Materials bucket : buckets) {
            cands.addAll(bucket.candidates());
            defs.addAll(bucket.eligibilityDefs());
            trees.putAll(bucket.eligibilityTrees());

            Long generation = bucket.provenance() == null ? null : bucket.provenance().generation();
            if (generation != null && (minGeneration == null || generation < minGeneration)) {
                minGeneration = generation;
            }
            ActivityCandidate bucketLead = bucket.lead();
            if (bucketLead != null && (lead == null || BY_ACTIVITY_ID.compare(bucketLead, lead) < 0)) {
                lead = bucketLead;
                strategy = bucket.strategy();
            }
        }
        return new Materials(cands, defs, trees,
                DecisionProvenance.snapshot(minGeneration, buckets.size()), strategy);
    }

    /**
     * <b>这次决策算在哪条业务线上。</b>
     *
     * <p>取值规则：<b>跨业务线时取 activityId 最小者所属的业务线</b>。
     *
     * <p>这个取值<b>没有正确答案</b>——一次请求的 SPU 可以同时命中多条业务线的活动，而合并策略、
     * 条件 schema 都是按 bizLine 配的，「这次算哪条线」本就无法从入参推出来。所以这里的目标不是选对，
     * 而是让它**有文档、有测试、且两条取数路径一致**：走库与走快照都按同一条规则取，
     * 于是同一次请求在两条路上不会解析出不同的业务线（进而不会用上不同的合并策略）。
     * 规则本身与改造前逐字节等价——改造前是「已按 activityId 定序的候选列表取第 0 个」，
     * 这里换成直接取最小值，只是不再依赖调用点是否已经定序。
     *
     * <p>并列（同一个 activityId 出现两次）时取列表里靠前的那个，与稳定排序后取第 0 个一致。
     *
     * @return 候选为空时返回 null（走库路径据此按 null 查默认策略行，与改造前一致）
     */
    public String bizLine() {
        ActivityCandidate lead = lead();
        return lead == null ? null : lead.getBizLine();
    }

    /** activityId 最小的候选（null id 排最后，并列取靠前者）；无候选时返回 null。 */
    private ActivityCandidate lead() {
        if (candidates == null || candidates.isEmpty()) return null;
        ActivityCandidate lead = null;
        for (ActivityCandidate c : candidates) {
            if (lead == null || BY_ACTIVITY_ID.compare(c, lead) < 0) lead = c;
        }
        return lead;
    }

    /**
     * <b>候选定序：合并的赢家不能由迭代顺序决定。</b>
     *
     * <p>{@code BenefitEvaluator} 的 {@code pickByAmount} / {@code pickByPriority} 在**打平**时是
     * 严格 {@code >} 比较，即先到先得。而两条装配路径的天然顺序都不可靠：
     * <ul>
     *   <li><b>快照侧</b>：倒排索引的值是 {@code Set.copyOf}（{@code DecisionSnapshot}），
     *       迭代序由 JDK 的 SALT 决定——<b>同一进程内稳定，每次 JVM 启动改变</b>。
     *       表现是 decision 重启后一整片决策的赢家可能翻面，比逐请求抖动更难被认出来。</li>
     *   <li><b>走库侧</b>：跟着 SQL 返回顺序走，没有 order by 就没有承诺。</li>
     * </ul>
     *
     * <p>于是「金额并列的两张券谁赢」既不稳定、也在两条路上不一致。这里按 activityId 定序，
     * 把它变成一个<b>确定且可解释</b>的结果。**只在取数层出口调用**（{@code DecisionDataLoader.load}）——
     * 那是两条路唯一的合流点，加价购也走同一个出口。
     */
    public Materials ordered() {
        if (candidates == null || candidates.size() < 2) return this;
        List<ActivityCandidate> sorted = new ArrayList<>(candidates);
        sorted.sort(BY_ACTIVITY_ID);
        return new Materials(sorted, eligibilityDefs, eligibilityTrees, provenance, strategy);
    }

    /** 换一个合并策略（取数层解析出来之后回填）。其余分量原样保留。 */
    public Materials withStrategy(StackStrategy newStrategy) {
        return newStrategy == strategy ? this
                : new Materials(candidates, eligibilityDefs, eligibilityTrees, provenance, newStrategy);
    }
}
