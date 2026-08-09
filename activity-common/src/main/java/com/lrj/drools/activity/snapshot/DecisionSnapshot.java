package com.lrj.drools.activity.snapshot;

import com.lrj.drools.activity.domain.ActivityCandidate;
import com.lrj.drools.activity.domain.ActivityType;
import com.lrj.drools.activity.domain.ConditionNode;
import com.lrj.drools.activity.domain.GiftResult;
import com.lrj.drools.activity.domain.StackStrategy;
import com.lrj.drools.activity.engine.ActivityDrlBuilder.EligibilityRuleDef;

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

    /** activityId → 候选模板。每次决策拷贝一份成可变的 {@link ActivityCandidate}（规则会改它的字段）。 */
    private final Map<String, CandidateTemplate> candidates;

    /** activityId → 受控资格约束（已由 RuleConditionTranslator 翻译好，构建期完成）。 */
    private final Map<String, String> eligibilityConstraints;

    /** activityId → 已解析的条件树（P1-3 直接求值用）。构建期解析一次，热路径零解析。 */
    private final Map<String, ConditionNode> eligibilityTrees;

    private final StackStrategy strategy;

    public DecisionSnapshot(String tenant, String bizLine, long generation, Instant builtAt,
                            Map<Long, Set<String>> activityIdsBySpu,
                            Map<String, CandidateTemplate> candidates,
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
    public StackStrategy strategy() { return strategy; }
    public Map<String, ConditionNode> eligibilityTrees() { return eligibilityTrees; }
    public int activityCount() { return candidates.size(); }

    /**
     * 按 SPU 列表 + 类型 + 当前时刻，产出候选事实与资格约束。
     *
     * <p>与 {@code DecisionDataLoader} 走库的那条路**语义逐条对齐**：已上线（构建期已过滤）、
     * 时间窗内（此处判定）、类型匹配、每活动取当前线上版本（构建期已选定）。
     */
    public Materialized materialize(List<Long> spuIds, ActivityType type, Instant now, boolean withGifts) {
        if (spuIds == null || spuIds.isEmpty()) {
            return new Materialized(List.of(), List.of());
        }
        // 保持与绑定表查询一致的顺序语义：按 spu 顺序收集、去重
        Set<String> ids = new LinkedHashSet<>();
        for (Long spu : spuIds) {
            Set<String> bound = activityIdsBySpu.get(spu);
            if (bound != null) ids.addAll(bound);
        }
        if (ids.isEmpty()) {
            return new Materialized(List.of(), List.of());
        }

        List<ActivityCandidate> out = new ArrayList<>();
        List<EligibilityRuleDef> defs = new ArrayList<>();
        for (String id : ids) {
            CandidateTemplate t = candidates.get(id);
            if (t == null) continue;
            if (type.code() != t.activityType()) continue;
            if (now.isBefore(t.startTime()) || now.isAfter(t.endTime())) continue;

            out.add(t.toCandidate(withGifts));
            String constraint = eligibilityConstraints.get(id);
            if (constraint != null && !constraint.isBlank()) {
                defs.add(new EligibilityRuleDef(id, constraint));
            }
        }
        return new Materialized(out, defs);
    }

    /** 物化结果，与 {@code DecisionDataLoader.Materials} 同形。 */
    public record Materialized(List<ActivityCandidate> candidates, List<EligibilityRuleDef> eligibilityDefs) {}

    /**
     * 单个活动的不可变候选模板。
     *
     * <p>不能直接复用 {@link ActivityCandidate}——规则执行期会 {@code modify} 它的
     * {@code eligible} / {@code computedAmount} / {@code amountComputed} 字段。
     * 快照必须是只读的，所以每次决策从模板拷一份新的可变事实出来。
     */
    public record CandidateTemplate(
            String activityId, String activityName, Integer activityType, String bizLine,
            Integer activityStatus, Integer activityAreaType, String districtIds,
            Integer inventory, Integer userInventory, Integer version, int priority,
            Integer redPackageTakeType, java.math.BigDecimal redPackageAmount,
            String redPackageAmountUnit, String redPackageRangeAmount,
            java.math.BigDecimal redPackageMaxDiscount,
            Instant startTime, Instant endTime,
            List<GiftResult> gifts) {

        public ActivityCandidate toCandidate(boolean withGifts) {
            ActivityCandidate c = new ActivityCandidate();
            c.setActivityId(activityId);
            c.setActivityName(activityName);
            c.setActivityType(activityType);
            c.setBizLine(bizLine);
            c.setActivityStatus(activityStatus);
            c.setActivityAreaType(activityAreaType);
            c.setDistrictIds(districtIds);
            c.setInventory(inventory);
            c.setUserInventory(userInventory);
            c.setVersion(version);
            c.setPriority(priority);
            c.setRedPackageTakeType(redPackageTakeType);
            c.setRedPackageAmount(redPackageAmount);
            c.setRedPackageAmountUnit(redPackageAmountUnit);
            // 漏拷这一行的表现是「快照路径不封顶、DB 路径封顶」——同一张券在两条路上发不同的钱
            c.setRedPackageMaxDiscount(redPackageMaxDiscount);
            c.setRedPackageRangeAmount(redPackageRangeAmount);
            if (withGifts) {
                c.setGifts(new ArrayList<>(gifts));
            }
            return c;
        }
    }
}
