package com.lrj.drools.activity.snapshot;

import com.lrj.drools.activity.domain.ActivityCandidate;
import com.lrj.drools.activity.domain.ActivityType;
import com.lrj.drools.activity.domain.ConditionNode;
import com.lrj.drools.activity.domain.OfferSpec;
import com.lrj.drools.activity.domain.StackStrategy;
import com.lrj.drools.activity.engine.ActivityDrlBuilder.EligibilityRuleDef;
import com.lrj.drools.activity.service.Materials;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 某 {@code (tenant, bizLine, generation)} 的**不可变决策物料快照**（计划 P1-1 代际快照包）。
 *
 * <p><b>它解决什么</b>：改造前每次决策都要现查库、现拼 DRL、可能现编译。本快照把这些昂贵动作
 * 全部移到<em>发布侧的后台线程</em>：发布时构建一次，decision 侧预编译好再原子切指针，
 * 请求线程只做「读快照 → 过滤 → 求值」。一次决策的数据库查询从 5 次降到 <b>0 次</b>。
 *
 * <p>它同时解掉评估报告的这几条：
 * <ul>
 *   <li><b>D1</b> 每请求查库 → 快照命中时零查询</li>
 *   <li><b>D3</b> 编译落在请求线程 → 构建期预编译，热路径必然命中缓存</li>
 *   <li><b>D8</b> 冻结的 artifact 只写不读 → 快照本身就是被决策读取的冻结物料</li>
 *   <li><b>D11</b> 发布非原子、无回滚原语 → 指针切换是原子的，切回上一代即回滚</li>
 * </ul>
 *
 * <p><b>刻意不预过滤时间窗</b>：活动的生效窗判定仍在请求时用 {@code Instant.now()} 做。
 * 若在构建期就把「当前不在窗内」的活动剔除，快照会在时间跨过窗边界时**悄悄过期**——
 * 一个 20:00 开始的活动要等下一次发布才会出现。窗判定是一次整数比较，放在热路径不值得省。
 *
 * <p><b>不可变性</b>：所有集合在构造时深拷贝并 {@code unmodifiable}。快照一旦发布就不再改动，
 * 多个代际可以同时存活（老请求读老快照，新请求读新快照），这也是 A/B 与回滚的载体。
 */
public final class DecisionSnapshot {

    private final String tenant;
    private final String bizLine;
    private final long generation;
    private final Instant builtAt;

    /** SPU → 绑定到它的活动 id（倒排索引，取代热路径上的绑定表查询）。 */
    private final Map<Long, Set<String>> activityIdsBySpu;

    /**
     * activityId → <b>不可变权益配置</b>。每次决策直接把它包进一个新的
     * {@link ActivityCandidate}（规则只改候选的计算态，配置本身跨请求共享）。
     *
     * <p>这里此前是一个 19 分量的影子类 {@code CandidateTemplate}：候选把配置与计算态焊在一起，
     * 快照又必须不可变，只能另造一份。R7 把配置剥成 {@link OfferSpec} 之后，影子类连同它那份
     * 手写扇出一起消失——两条装配路径在<b>类型上</b>只能产出同一个配置对象。
     */
    private final Map<String, OfferSpec> candidates;

    /** activityId → 受控资格约束（已由 RuleConditionTranslator 翻译好，构建期完成）。 */
    private final Map<String, String> eligibilityConstraints;

    /** activityId → 已解析的条件树（P1-3 直接求值用）。构建期解析一次，热路径零解析。 */
    private final Map<String, ConditionNode> eligibilityTrees;

    private final StackStrategy strategy;

    public DecisionSnapshot(String tenant, String bizLine, long generation, Instant builtAt,
                            Map<Long, Set<String>> activityIdsBySpu,
                            Map<String, OfferSpec> candidates,
                            Map<String, String> eligibilityConstraints,
                            Map<String, ConditionNode> eligibilityTrees,
                            StackStrategy strategy) {
        this.tenant = tenant;
        this.bizLine = bizLine;
        this.generation = generation;
        this.builtAt = builtAt;
        Map<Long, Set<String>> idx = new LinkedHashMap<>();
        activityIdsBySpu.forEach((k, v) -> idx.put(k, Set.copyOf(v)));
        this.activityIdsBySpu = Map.copyOf(idx);
        this.candidates = Map.copyOf(candidates);
        this.eligibilityConstraints = Map.copyOf(eligibilityConstraints);
        this.eligibilityTrees = Map.copyOf(eligibilityTrees);
        this.strategy = strategy;
    }

    public String tenant() { return tenant; }
    public String bizLine() { return bizLine; }
    public long generation() { return generation; }
    public Instant builtAt() { return builtAt; }
    public int activityCount() { return candidates.size(); }
    // 合并策略与条件树**不再单独暴露**：它们只经 materialize() 随本桶那一份 Materials 出去。
    // 单独的 strategy() 曾是「第二次来源判定」的入口（物料走快照、策略另判一次），
    // 单独的 eligibilityTrees() 则让调用方把整条业务线的树全量拷进每一次决策——
    // 两个 getter 都只有那一个调用方，收敛掉即可，留着只是邀请它们长回来。

    /**
     * <b>这个活动在不在这份快照里。</b>
     *
     * <p>看着像个 getter，实际上它是「决策结果全绿但活动就是不命中」这类故障<b>唯一</b>能说话的出口。
     * 三个 provenance 值（source/generation/buckets）在最要命的那条故障上是全绿的：
     * 活动的 {@code bizLine} 为空（写平面不强制必填）→ 它进不了任何桶
     * （构建期按 bizLine 精确匹配），而兜底重建只遍历<b>已存在的桶</b>、永远建不出不存在的那个。
     * 此时决策照常走快照、代际是别的业务线的正常数、快照也很新——只是这个活动根本不在里面。
     */
    public boolean contains(String activityId) {
        return activityId != null && candidates.containsKey(activityId);
    }

    /** 这份快照收了哪些活动。用于诊断端点列举，热路径不调。 */
    public Set<String> activityIds() { return candidates.keySet(); }

    /**
     * 按 SPU 列表 + 类型 + 当前时刻，产出**本桶那一份**决策物料。
     *
     * <p>与 {@code DecisionDataLoader} 走库的那条路**语义逐条对齐**：已上线（构建期已过滤）、
     * 时间窗内（此处判定）、类型匹配、每活动取当前线上版本（构建期已选定）。
     *
     * <p>返回的就是取数层的出参类型 {@link Materials} 本身——此前这里返回一个自称「与 Materials 同形」
     * 的影子 record，同形靠注释维持，缝合动作（拆开候选与约束、手算最小代际、手拼 provenance、
     * 再单独判一次来源取合并策略）散在 {@code load()} 与 {@code resolveStrategy()} 两处。
     * 多桶合并现在收敛在 {@link Materials#merge}。
     */
    public Materials materialize(List<Long> spuIds, ActivityType type, Instant now, boolean withGifts) {
        if (spuIds == null || spuIds.isEmpty()) {
            return emptyBucket();
        }
        // 保持与绑定表查询一致的顺序语义：按 spu 顺序收集、去重。
        // 同一趟遍历顺便把**作用域**倒排出来：activityId → 本次请求里它圈到的 SPU。
        // 倒排索引本身就是「当前线上版本的生效绑定」，所以这里拿到的与走库路径按版本内连接的结果同源。
        Set<String> ids = new LinkedHashSet<>();
        Map<String, Set<Long>> scope = new LinkedHashMap<>();
        for (Long spu : spuIds) {
            Set<String> bound = activityIdsBySpu.get(spu);
            if (bound == null) continue;
            ids.addAll(bound);
            for (String id : bound) {
                scope.computeIfAbsent(id, k -> new LinkedHashSet<>()).add(spu);
            }
        }
        if (ids.isEmpty()) {
            return emptyBucket();
        }

        List<ActivityCandidate> out = new ArrayList<>();
        List<EligibilityRuleDef> defs = new ArrayList<>();
        // 条件树**只装本次命中的候选**：下游只做 trees.get(candidateId)，把整条业务线的树全量
        // putAll 进去没有任何消费者，只是让每次决策白拷一遍（活动越多拷得越多）。
        Map<String, ConditionNode> trees = new LinkedHashMap<>();
        for (String id : ids) {
            OfferSpec spec = candidates.get(id);
            if (spec == null) continue;
            if (type.code() != spec.activityType()) continue;
            if (now.isBefore(spec.startTime()) || now.isAfter(spec.endTime())) continue;

            // scopedSpuIds 是**逐请求**的交集，所以只能在这里传入、不能冻进配置。
            // 走库那条路给的同样是空集而不是 null——退回 null 会让 AMOUNT 以外五形态的基数变成整单。
            out.add(new ActivityCandidate(spec, scope.getOrDefault(id, Set.of()), withGifts));
            String constraint = eligibilityConstraints.get(id);
            if (constraint != null && !constraint.isBlank()) {
                defs.add(new EligibilityRuleDef(id, constraint));
            }
            ConditionNode tree = eligibilityTrees.get(id);
            if (tree != null) trees.put(id, tree);
        }
        return Materials.snapshotBucket(out, defs, trees, generation, strategy);
    }

    /** 本桶对这次决策没有贡献，但它的代际与策略仍要参与合并（代际取最小、桶数计入）。 */
    private Materials emptyBucket() {
        return Materials.snapshotBucket(List.of(), List.of(), Map.of(), generation, strategy);
    }
}
