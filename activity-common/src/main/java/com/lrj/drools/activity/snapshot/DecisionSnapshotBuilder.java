package com.lrj.drools.activity.snapshot;

import com.lrj.drools.activity.domain.ActivityStatus;
import com.lrj.drools.activity.domain.ConditionNode;
import com.lrj.drools.activity.domain.GiftResult;
import com.lrj.drools.activity.domain.OfferSpec;
import com.lrj.drools.activity.domain.RuleScene;
import com.lrj.drools.activity.domain.StackStrategy;
import com.lrj.drools.activity.persistence.*;
import com.lrj.drools.activity.service.DecisionDataLoader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 从库里构建 {@link DecisionSnapshot}（计划 P1-1）。
 *
 * <p><b>在哪个线程跑</b>：发布代际变化后的**后台线程**（decision 侧的轮询预热线程），
 * 绝不在请求线程上。构建期把整条业务线的物料一次捞齐——比每请求捞一遍贵，但一次发布只做一次。
 *
 * <p><b>与逐请求取数的语义对齐</b>：这里的过滤逻辑必须与 {@code DecisionDataLoader} 逐条一致，
 * 否则快照路径与走库路径会算出不同的钱。对齐点：
 * <ul>
 *   <li>只收 {@code is_del=0} 且状态为 ONLINE 的版本，且取每个活动<b>最高的已上线版本</b>（P0-4 的语义）</li>
 *   <li>规则行 / 条件行按 {@code (activityId, version)} 取第一条（原实现是 {@code findFirst()}）。
 *       条件行取的是<b>整行</b>——约束与条件树必须来自同一行，见下面 {@code condByKey} 处的说明</li>
 *   <li>「行 → 配置」走 {@link com.lrj.drools.activity.domain.OfferSpec#from}，与走库路径同一个入口</li>
 *   <li>生效时间窗**不在此处过滤**——留给请求时判定，理由见 {@link DecisionSnapshot}</li>
 * </ul>
 * 等价性由 {@code SnapshotParityTest} 对拍守住。
 */
@Service
public class DecisionSnapshotBuilder {

    private static final int NOT_DEL = 0;
    private static final int EFFECTIVE = 1;
    private static final int ENABLED = 1;

    private final ActivityManageRepository manageRepo;
    private final ActivitySpuBindingRepository bindingRepo;
    private final ActivityRuleRepository ruleRepo;
    private final ActivityConditionRepository conditionRepo;
    private final ActivityGiftRepository giftRepo;
    private final ActivityStrategyRepository strategyRepo;

    public DecisionSnapshotBuilder(ActivityManageRepository manageRepo,
                                   ActivitySpuBindingRepository bindingRepo,
                                   ActivityRuleRepository ruleRepo,
                                   ActivityConditionRepository conditionRepo,
                                   ActivityGiftRepository giftRepo,
                                   ActivityStrategyRepository strategyRepo) {
        this.manageRepo = manageRepo;
        this.bindingRepo = bindingRepo;
        this.ruleRepo = ruleRepo;
        this.conditionRepo = conditionRepo;
        this.giftRepo = giftRepo;
        this.strategyRepo = strategyRepo;
    }

    /**
     * 构建某 {@code (tenant, bizLine)} 的快照。调用方须已置好 {@code TenantContext}
     * （@TenantId 判别式过滤依赖它）。
     */
    @Transactional(readOnly = true)
    public DecisionSnapshot build(String tenant, String bizLine, long generation) {
        // ① 该租户全部未删除的已上线活动，按 bizLine 收敛，取每活动最高已上线版本
        Map<String, ActivityManageEntity> live = new LinkedHashMap<>();
        for (ActivityManageEntity m : manageRepo.findByActivityStatusAndIsDel(ActivityStatus.ONLINE.code(), NOT_DEL)) {
            if (bizLine != null && !bizLine.equals(m.getBizLine())) continue;
            live.merge(m.getActivityId(), m,
                    (a, b) -> Comparator.comparing(ActivityManageEntity::getVersion,
                            Comparator.nullsFirst(Comparator.naturalOrder())).compare(a, b) >= 0 ? a : b);
        }
        if (live.isEmpty()) {
            return new DecisionSnapshot(tenant, bizLine, generation, Instant.now(),
                    Map.of(), Map.of(), Map.of(), Map.of(), resolveStrategy(bizLine));
        }

        List<String> ids = new ArrayList<>(live.keySet());

        // ② 规则行 / 赠品行 / 条件行，各一次批量查
        Map<String, ActivityRuleEntity> ruleByKey = new LinkedHashMap<>();
        for (ActivityRuleEntity r : ruleRepo.findByActivityIdInAndIsDel(ids, NOT_DEL)) {
            ruleByKey.putIfAbsent(key(r.getActivityId(), r.getVersion()), r);
        }
        Map<String, List<GiftResult>> giftsByKey = new LinkedHashMap<>();
        for (ActivityGiftEntity g : giftRepo.findByActivityIdInAndIsDel(ids, NOT_DEL)) {
            giftsByKey.computeIfAbsent(key(g.getActivityId(), g.getVersion()), k -> new ArrayList<>())
                    .add(new GiftResult(g.getActivityId(), g.getVersion(),
                            g.getBatchId(), g.getGiftName(), g.getGiftType(),
                            g.getGiftNum(), g.getAbsoluteAmount(), g.getRightType()));
        }
        // 条件行按 (activityId, version) 取**整行**的第一条——与 DecisionDataLoader.eligibility 同语义。
        // 此前这里是 drl 与 tree **各自** putIfAbsent，结构上允许两者来自不同的行：
        // 第一行的树是脏的（parseTree 返回 null）时，快照会拿第一行的约束 + 第二行的树凑成一个候选，
        // 而走库那条路会因为「树不可判定」把候选 fail-closed 淘汰。坏数据在快照侧静默变成
        // 「资格通过、照常发钱」，是两条路里更贵的那个方向。
        Map<String, ActivityConditionEntity> condByKey = new LinkedHashMap<>();
        for (ActivityConditionEntity c : conditionRepo.findByActivityIdInAndSceneAndEnabledAndIsDel(
                ids, RuleScene.ELIGIBILITY.code(), ENABLED, NOT_DEL)) {
            condByKey.putIfAbsent(key(c.getActivityId(), c.getVersion()), c);
        }

        // ③ SPU 倒排：只收当前线上版本的生效绑定
        Map<Long, Set<String>> bySpu = new LinkedHashMap<>();
        Map<String, OfferSpec> specs = new LinkedHashMap<>();
        Map<String, String> constraints = new LinkedHashMap<>();
        Map<String, ConditionNode> trees = new LinkedHashMap<>();

        for (ActivityManageEntity m : live.values()) {
            String k = key(m.getActivityId(), m.getVersion());
            // 「行 → 配置」只有 OfferSpec.from 这一个入口，走库路径走的也是它。
            specs.put(m.getActivityId(), OfferSpec.from(m, ruleByKey.get(k),
                    giftsByKey.getOrDefault(k, List.of())));

            ActivityConditionEntity cond = condByKey.get(k);
            if (cond != null) {
                String constraint = cond.getGeneratedDrl();
                if (constraint != null && !constraint.isBlank()) {
                    constraints.put(m.getActivityId(), constraint);
                }
                ConditionNode tree = DecisionDataLoader.parseTree(cond.getConditionTreeJson());
                if (tree != null) trees.put(m.getActivityId(), tree);
            }

            for (ActivitySpuBindingEntity b : bindingRepo.findByActivityIdAndVersionAndIsDel(
                    m.getActivityId(), m.getVersion(), NOT_DEL)) {
                if (b.getSpuId() == null) continue;
                if (b.getEffective() == null || b.getEffective() != EFFECTIVE) continue;
                bySpu.computeIfAbsent(b.getSpuId(), s -> new LinkedHashSet<>()).add(m.getActivityId());
            }
        }

        return new DecisionSnapshot(tenant, bizLine, generation, Instant.now(),
                bySpu, specs, constraints, trees, resolveStrategy(bizLine));
    }

    private StackStrategy resolveStrategy(String bizLine) {
        return strategyRepo.findFirstByBizLineAndActivityTypeIsNullAndSceneAndIsDel(
                        bizLine, RuleScene.DISCOUNT.code(), NOT_DEL)
                .map(s -> StackStrategy.fromCode(s.getStrategy()))
                .orElse(StackStrategy.MAX);
    }

    private static String key(String activityId, Integer version) {
        return activityId + "#" + version;
    }
}
