package com.lrj.drools.activity.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.drools.activity.domain.ActivityCandidate;
import com.lrj.drools.activity.domain.ConditionNode;
import com.lrj.drools.activity.domain.ActivityStatus;
import com.lrj.drools.activity.domain.ActivityType;
import com.lrj.drools.activity.domain.GiftResult;
import com.lrj.drools.activity.domain.RuleScene;
import com.lrj.drools.activity.domain.StackStrategy;
import com.lrj.drools.activity.engine.ActivityDrlBuilder.EligibilityRuleDef;
import com.lrj.drools.activity.persistence.ActivityConditionEntity;
import com.lrj.drools.activity.persistence.ActivityConditionRepository;
import com.lrj.drools.activity.persistence.ActivityGiftEntity;
import com.lrj.drools.activity.persistence.ActivityGiftRepository;
import com.lrj.drools.activity.persistence.ActivityManageEntity;
import com.lrj.drools.activity.persistence.ActivityManageRepository;
import com.lrj.drools.activity.persistence.ActivityRuleEntity;
import com.lrj.drools.activity.persistence.ActivityRuleRepository;
import com.lrj.drools.activity.persistence.ActivitySpuBindingEntity;
import com.lrj.drools.activity.persistence.ActivitySpuBindingRepository;
import com.lrj.drools.activity.persistence.ActivityStrategyRepository;
import com.lrj.drools.activity.metrics.DecisionMetrics;
import com.lrj.drools.activity.snapshot.DecisionSnapshot;
import com.lrj.drools.activity.snapshot.DecisionSnapshotStore;
import com.lrj.drools.activity.tenant.TenantContext;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 决策热路径的**取数层**（拍板 D12-2 的接缝拆分 + 计划 P0-3 消灭 N+1）。
 *
 * <p><b>拆出来的原因</b>：{@code ActivityQueryService} 原本一个类里同时干两件事——「从库里把物料捞齐」
 * 和「把物料喂给规则引擎求值」。两条改造线要同时动它：性能线改<em>数据来源</em>（批量化 → 将来换代际快照包），
 * 权益线改<em>求值结构</em>（BenefitSpec）。合在一个类里，两边的 diff 必然打架。
 * 现在沿代码里**本来就存在**的接缝切开，取数归本类，求值留在原处，编排层只剩几十行。
 *
 * <p><b>改掉的性能问题</b>：原实现每次决策要 <code>3N+2</code> 次数据库往返（N = 候选活动数）——
 * 逐个 activityId 查当前版本 N 次、逐个候选查规则 N 次、买赠再查赠品 N 次、逐个候选查资格条件 N 次。
 * 索引全都建对了，但<b>索引救不了 round-trip 次数</b>：N=20 的普通商品一次决策就是 60 多次网络往返，
 * 而 Hikari 是默认 10 连接、无任何调优。吞吐天花板由「连接池 × RTT」决定，和 Drools 快不快无关。
 *
 * <p>现在固定 <b>5 次</b>查询，与 N 无关：
 * <ol>
 *   <li>按 SPU 查绑定</li>
 *   <li>批量查这批活动的全部未删除版本（内存里挑每个活动的最高版本）</li>
 *   <li>批量查规则行</li>
 *   <li>批量查资格条件行</li>
 *   <li>查 bizLine 级合并策略</li>
 * </ol>
 * 买赠场景第 3 步换成批量查赠品行，仍是 5 次。
 *
 * <p><b>行为等价性</b>：本类是纯搬移 + 批量化，判定逻辑逐行照搬（上线状态、时间窗、类型匹配、
 * 取最高版本、条件行取第一条）。等价性由 {@code DecisionGoldenSetTest} 的 39 例金标守住。
 */
@Service
public class DecisionDataLoader {

    private static final int NOT_DEL = 0;
    private static final int EFFECTIVE = 1;
    private static final int ENABLED = 1;

    private final ActivitySpuBindingRepository bindingRepo;
    private final ActivityManageRepository manageRepo;
    private final ActivityRuleRepository ruleRepo;
    private final ActivityConditionRepository conditionRepo;
    private final ActivityGiftRepository giftRepo;
    private final ActivityStrategyRepository strategyRepo;
    private final DecisionSnapshotStore snapshots;
    private final DecisionMetrics metrics;

    public DecisionDataLoader(ActivitySpuBindingRepository bindingRepo,
                              ActivityManageRepository manageRepo,
                              ActivityRuleRepository ruleRepo,
                              ActivityConditionRepository conditionRepo,
                              ActivityGiftRepository giftRepo,
                              ActivityStrategyRepository strategyRepo,
                              DecisionSnapshotStore snapshots,
                              DecisionMetrics metrics) {
        this.bindingRepo = bindingRepo;
        this.manageRepo = manageRepo;
        this.ruleRepo = ruleRepo;
        this.conditionRepo = conditionRepo;
        this.giftRepo = giftRepo;
        this.strategyRepo = strategyRepo;
        this.snapshots = snapshots;
        this.metrics = metrics;
    }

    /**
     * 一次决策所需的全部物料。
     *
     * @param candidates      拍平后的候选事实（已按上线 / 时间窗 / 类型过滤）
     * @param eligibilityDefs 有资格条件的候选对应的受控约束（无条件的活动不出现在这里 = 恒通过）
     */
    public record Materials(List<ActivityCandidate> candidates,
                            List<EligibilityRuleDef> eligibilityDefs,
                            Map<String, ConditionNode> eligibilityTrees) {}

    /**
     * 装齐一次决策的物料。
     *
     * <p><b>优先读代际快照（P1-1）</b>：命中时**零数据库查询**——物料已在发布侧构建、预编译完成。
     * 快照不存在时回落到逐请求查库（固定 5 次，与 N 无关）。
     *
     * <p>这个回落是<b>自调节</b>的，不需要开关：只有 decision 服务带轮询器会构建快照，
     * console 的 legacy 读端点没有构建器、store 恒空，天然走库。
     * 两条路的语义由 {@code SnapshotParityTest} 对拍守住。
     */
    public Materials load(List<Long> spuIds, ActivityType type, boolean withGifts) {
        List<DecisionSnapshot> snaps = snapshots.forTenant(TenantContext.get());
        if (!snaps.isEmpty()) {
            metrics.decisionSource(type.name(), "snapshot");
            Instant now = Instant.now();
            List<ActivityCandidate> cands = new ArrayList<>();
            List<EligibilityRuleDef> defs = new ArrayList<>();
            for (DecisionSnapshot snap : snaps) {
                DecisionSnapshot.Materialized m = snap.materialize(spuIds, type, now, withGifts);
                cands.addAll(m.candidates());
                defs.addAll(m.eligibilityDefs());
            }
            Map<String, ConditionNode> trees = new LinkedHashMap<>();
            for (DecisionSnapshot snap : snaps) trees.putAll(snap.eligibilityTrees());
            return new Materials(cands, defs, trees);
        }
        metrics.decisionSource(type.name(), "db");
        return loadFromDb(spuIds, type, withGifts);
    }

    private Materials loadFromDb(List<Long> spuIds, ActivityType type, boolean withGifts) {
        List<String> activityIds = boundActivityIds(spuIds);
        if (activityIds.isEmpty()) {
            return new Materials(List.of(), List.of(), Map.of());
        }

        List<ActivityManageEntity> valid = currentEffectiveVersions(activityIds, type);
        if (valid.isEmpty()) {
            return new Materials(List.of(), List.of(), Map.of());
        }

        List<ActivityCandidate> candidates = flatten(valid, withGifts);
        Eligibility elig = eligibility(valid);   // 条件行只查一次，defs 与 trees 同源
        return new Materials(candidates, elig.defs(), elig.trees());
    }

    /** 合并策略按 bizLine 解析；候选为空时取默认 MAX。快照命中时直接读快照里的策略，同样零查询。 */
    public StackStrategy resolveStrategy(List<ActivityCandidate> candidates) {
        String bizLine = candidates.isEmpty() ? null : candidates.get(0).getBizLine();
        DecisionSnapshot snap = snapshots.get(TenantContext.get(), bizLine);
        if (snap != null) {
            return snap.strategy();
        }
        return strategyRepo.findFirstByBizLineAndActivityTypeIsNullAndSceneAndIsDel(
                        bizLine, RuleScene.DISCOUNT.code(), NOT_DEL)
                .map(s -> StackStrategy.fromCode(s.getStrategy()))
                .orElse(StackStrategy.MAX);
    }

    // ------------------------------------------------------------------ 内部

    /** ① 按 SPU 查生效绑定 → 活动 id。 */
    private List<String> boundActivityIds(List<Long> spuIds) {
        if (spuIds == null || spuIds.isEmpty()) return List.of();
        List<ActivitySpuBindingEntity> bindings = bindingRepo
                .findBySpuIdInAndEffectiveAndIsDel(spuIds, EFFECTIVE, NOT_DEL);
        return bindings.stream().map(ActivitySpuBindingEntity::getActivityId).distinct().collect(Collectors.toList());
    }

    /**
     * ② 批量取每个活动的**当前版本**，再按「已上线 + 当前时间在窗内 + 类型匹配」过滤。
     *
     * <p><b>P0-4 已修</b>：取「最高的**已上线**版本」，而不是「最高版本再判是否上线」。
     * 后者是「编辑即下线」的成因——编辑产生的草稿版本号更高，会把正在服务的线上版本挤掉，
     * 于是改个错别字活动就从线上消失。配合写平面「编辑不软删线上版本 + 发布时退役旧线上版本」，
     * 线上版本与草稿可以并存，发布才是唯一的切换动作。
     */
    private List<ActivityManageEntity> currentEffectiveVersions(List<String> activityIds, ActivityType type) {
        List<ActivityManageEntity> all = manageRepo.findByActivityIdInAndIsDel(activityIds, NOT_DEL);

        // 先按"已上线"过滤，再取最高版本——顺序不能反（见上）
        Map<String, ActivityManageEntity> highestOnline = new LinkedHashMap<>();
        for (ActivityManageEntity m : all) {
            if (ActivityStatus.ONLINE.code() != m.getActivityStatus()) continue;
            highestOnline.merge(m.getActivityId(), m,
                    (a, b) -> Comparator.comparing(ActivityManageEntity::getVersion,
                            Comparator.nullsFirst(Comparator.naturalOrder())).compare(a, b) >= 0 ? a : b);
        }

        Instant now = Instant.now();
        List<ActivityManageEntity> result = new ArrayList<>();
        for (String id : activityIds) {
            ActivityManageEntity m = highestOnline.get(id);
            if (m == null) continue;
            boolean inRange = !now.isBefore(m.getActivityStartTime()) && !now.isAfter(m.getActivityEndTime());
            boolean typeMatch = type.code() == m.getActivityType();
            if (inRange && typeMatch) result.add(m);
        }
        return result;
    }

    /** ③④ 批量取规则行（买赠时另取赠品行），拍平成候选事实。 */
    private List<ActivityCandidate> flatten(List<ActivityManageEntity> manages, boolean withGifts) {
        List<String> ids = manages.stream().map(ActivityManageEntity::getActivityId).distinct().toList();

        // (activityId, version) → 首条规则行。原实现是 findFirst()，这里保持"取第一条"的语义。
        Map<String, ActivityRuleEntity> ruleByKey = new LinkedHashMap<>();
        for (ActivityRuleEntity r : ruleRepo.findByActivityIdInAndIsDel(ids, NOT_DEL)) {
            ruleByKey.putIfAbsent(key(r.getActivityId(), r.getVersion()), r);
        }

        Map<String, List<GiftResult>> giftsByKey = new LinkedHashMap<>();
        if (withGifts) {
            for (ActivityGiftEntity g : giftRepo.findByActivityIdInAndIsDel(ids, NOT_DEL)) {
                giftsByKey.computeIfAbsent(key(g.getActivityId(), g.getVersion()), k -> new ArrayList<>())
                        .add(new GiftResult(g.getBatchId(), g.getGiftName(), g.getGiftType(),
                                g.getGiftNum(), g.getAbsoluteAmount(), g.getRightType()));
            }
        }

        List<ActivityCandidate> list = new ArrayList<>();
        for (ActivityManageEntity m : manages) {
            ActivityCandidate c = new ActivityCandidate();
            c.setActivityId(m.getActivityId());
            c.setActivityName(m.getActivityName());
            c.setActivityType(m.getActivityType());
            c.setBizLine(m.getBizLine());
            c.setActivityStatus(m.getActivityStatus());
            c.setActivityAreaType(m.getActivityAreaType());
            c.setDistrictIds(m.getDistrictIds());
            c.setInventory(m.getInventory());
            c.setUserInventory(m.getUserInventory());
            c.setVersion(m.getVersion());
            c.setPriority(m.getPriority() == null ? 0 : m.getPriority());

            ActivityRuleEntity r = ruleByKey.get(key(m.getActivityId(), m.getVersion()));
            if (r != null) {
                c.setRedPackageTakeType(r.getRedPackageTakeType());
                c.setRedPackageAmount(r.getRedPackageAmount());
                c.setRedPackageAmountUnit(r.getRedPackageAmountUnit());
                c.setRedPackageMaxDiscount(r.getRedPackageMaxDiscount());
                c.setRedPackageRangeAmount(r.getRedPackageRangeAmount());
            }

            if (withGifts) {
                c.setGifts(giftsByKey.getOrDefault(key(m.getActivityId(), m.getVersion()), List.of()));
            }
            list.add(c);
        }
        return list;
    }



    private record Eligibility(List<EligibilityRuleDef> defs, Map<String, ConditionNode> trees) {}

    /**
     * ⑤ 批量取资格条件——**一次查询同时产出受控约束与条件树**。
     *
     * <p>此前拆成两个方法各查一次，把热路径的查询数从 5 推到了 6，被 {@code DecisionQueryCountTest} 当场抓住。
     * 两者本来就来自同一批行，合并即可。
     *
     * <p>没有条件的活动不产出 def（= 恒通过），与原 DRL「不生成淘汰规则」一致。
     */
    private Eligibility eligibility(List<ActivityManageEntity> manages) {
        List<String> ids = manages.stream().map(ActivityManageEntity::getActivityId).distinct().toList();

        Map<String, ActivityConditionEntity> byKey = new LinkedHashMap<>();
        for (ActivityConditionEntity cond : conditionRepo.findByActivityIdInAndSceneAndEnabledAndIsDel(
                ids, RuleScene.ELIGIBILITY.code(), ENABLED, NOT_DEL)) {
            byKey.putIfAbsent(key(cond.getActivityId(), cond.getVersion()), cond);
        }

        List<EligibilityRuleDef> defs = new ArrayList<>();
        Map<String, ConditionNode> trees = new LinkedHashMap<>();
        for (ActivityManageEntity m : manages) {
            ActivityConditionEntity cond = byKey.get(key(m.getActivityId(), m.getVersion()));
            if (cond == null) continue;
            if (cond.getGeneratedDrl() != null && !cond.getGeneratedDrl().isBlank()) {
                defs.add(new EligibilityRuleDef(m.getActivityId(), cond.getGeneratedDrl()));
            }
            ConditionNode tree = parseTree(cond.getConditionTreeJson());
            if (tree != null) trees.put(m.getActivityId(), tree);
        }
        return new Eligibility(defs, trees);
    }

    /** 解析失败返回 null → 该活动无条件树。**调用方必须把这种情况当成"条件不可判定"处理，见 ActivityQueryService。** */
    public static ConditionNode parseTree(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return MAPPER.readValue(json, ConditionNode.class);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 条件树 JSON 的解析器。
     *
     * <p>必须关掉 {@code FAIL_ON_UNKNOWN_PROPERTIES}：{@code ConditionNode.isGroup()} 是个派生的
     * boolean getter，Jackson 序列化时会额外写出一个 {@code "group"} 字段，而反序列化时它没有对应的 setter。
     * 用默认配置的裸 ObjectMapper 会直接抛 UnrecognizedPropertyException——写得出、读不回。
     * （写侧用的是 Spring 自动配置的 mapper，它默认就是宽容的，所以这个不对称一直没暴露。）
     */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static String key(String activityId, Integer version) {
        return activityId + "#" + version;
    }
}
