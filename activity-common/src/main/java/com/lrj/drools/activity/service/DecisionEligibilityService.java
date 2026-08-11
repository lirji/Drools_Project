package com.lrj.drools.activity.service;

import com.lrj.drools.activity.domain.ActivityCandidate;
import com.lrj.drools.activity.domain.ActivityRuleContext;
import com.lrj.drools.activity.domain.ConditionNode;
import com.lrj.drools.activity.domain.SpuDiscountRequest;
import com.lrj.drools.activity.engine.ActivityDrlBuilder.EligibilityRuleDef;
import com.lrj.drools.activity.engine.ConditionTreeEvaluator;
import com.lrj.drools.activity.engine.RuleSchemaRegistry;
import com.lrj.drools.activity.metrics.DecisionMetrics;
import com.lrj.drools.activity.tenant.TenantContext;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 决策请求上下文构造 + Java 资格求值的唯一实现。
 *
 * <p>红包、买赠、加价购必须先过同一套资格判断。过去只有红包调用
 * {@link ConditionTreeEvaluator}，买赠和加价购把候选的默认
 * {@code eligible=true} 直接当成通过，导致「满 500 赠品」在 499 元订单上也会发。
 * 把上下文映射和候选淘汰放到同一个服务后，新增请求字段或资格语义只需改一处。
 *
 * <p><b>fail-closed</b>：物料声明某活动有受控约束，却拿不到可解释的条件树时，
 * 一律淘汰。条件 JSON 损坏或 schema 漂移不能被解释成「无条件通过」。
 */
@Service
public class DecisionEligibilityService {

    private final ConditionTreeEvaluator conditions;
    private final RuleSchemaRegistry schemaRegistry;
    private final DecisionMetrics metrics;

    public DecisionEligibilityService(ConditionTreeEvaluator conditions,
                                      RuleSchemaRegistry schemaRegistry,
                                      DecisionMetrics metrics) {
        this.conditions = conditions;
        this.schemaRegistry = schemaRegistry;
        this.metrics = metrics;
    }

    /**
     * 请求维度 → 属性袋的唯一映射表。
     *
     * <p>值允许为 null；{@link ActivityRuleContext#putAttr} 会跳过 null，缺字段因此统一
     * fail-closed。订单行只服务于第 N 件折算额，不进入运营可配置的条件白名单。
     */
    public static Map<String, Object> requestAttributes(SpuDiscountRequest req) {
        boolean noSpu = req.spuIdList() == null || req.spuIdList().isEmpty();
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("orderAmount", req.orderAmount());
        attrs.put("quantity", req.quantity());
        attrs.put("userDistrictId", req.userDistrictId());
        attrs.put("userTags", req.userTags() == null ? null : new ArrayList<>(req.userTags()));
        // spuId 是**整个购物车的 SPU 列表**，不再是「第一件」。
        //
        // 旧写法 `req.spuIdList().get(0)` 让「商品 SPU」这个条件的含义变成「购物车里排在第一位的商品是 X」——
        // 同样两件商品换个加购顺序，同一个活动的资格结论就不一样，而运营配这个条件时想说的
        // 从来都是「买了 X」。它同时也是作用域改造的兜底路：连「用条件树 spuId == A 兜一下」都不成立。
        // 配套地 RuleSchemaRegistry 把 spuId 声明为 ARRAY，存量的 eq/in 由求值器映射成集合语义（见 ConditionTreeEvaluator）。
        attrs.put("spuId", noSpu ? null : new ArrayList<>(req.spuIdList()));
        attrs.put("storeId", req.storeId());
        // userId 不在条件白名单里，但随机红包的确定性种子依赖它，保留历史映射。
        attrs.put("userId", req.userId());
        // 随机红包种子专用：**必须继续是「第一件」的那个标量**。
        // 它不在条件白名单里、也不该被任何条件引用，唯一职责是让确定性随机的指纹在
        // spuId 改成列表之后保持不变——否则全量随机红包会一次性重抽（见 BenefitEvaluator.drawRandom）。
        attrs.put("randomSeedSpu", noSpu ? null : req.spuIdList().get(0));
        attrs.put("orderLines", req.lines() == null || req.lines().isEmpty() ? null : new ArrayList<>(req.lines()));
        return attrs;
    }

    /** 构造上下文并挂入同一批候选。调用方之后的资格、算额和规则执行必须复用这个对象。 */
    public ActivityRuleContext buildContext(SpuDiscountRequest req, List<ActivityCandidate> candidates) {
        ActivityRuleContext ctx = new ActivityRuleContext();
        requestAttributes(req).forEach(ctx::putAttr);
        if (candidates != null) candidates.forEach(ctx::addCandidate);
        return ctx;
    }

    /**
     * 用结构化条件树淘汰不满足资格的候选，并按 explain 追加稳定 trace。
     *
     * @param scene 有限集合的指标标签（如 spu-discount / gifts / addon）
     */
    public void applyJava(ActivityRuleContext ctx,
                          DecisionDataLoader.Materials materials,
                          String scene,
                          boolean explain,
                          List<String> traces) {
        List<ActivityCandidate> candidates = materials.candidates() == null
                ? List.of() : materials.candidates();
        Map<String, ConditionNode> trees = materials.eligibilityTrees() == null
                ? Map.of() : materials.eligibilityTrees();
        List<EligibilityRuleDef> defs = materials.eligibilityDefs() == null
                ? List.of() : materials.eligibilityDefs();
        Set<String> constrained = defs.stream().map(EligibilityRuleDef::activityId).collect(Collectors.toSet());

        String tenant = TenantContext.get();
        for (ActivityCandidate candidate : candidates) {
            ConditionNode tree = trees.get(candidate.getActivityId());
            if (tree == null) {
                if (constrained.contains(candidate.getActivityId())) {
                    metrics.fallback(scene, "condition-tree-unavailable");
                    metrics.reject(scene, "condition-unavailable");
                    candidate.reject("资格条件不可判定");
                    if (explain) {
                        traces.add("eligibility reject: " + candidate.getActivityId() + "（条件树不可用）");
                    }
                } else if (explain && candidate.isEligible()) {
                    traces.add("eligible: " + candidate.getActivityId());
                }
                continue;
            }

            if (!conditions.matches(tree, ctx, schemaRegistry.resolve(tenant, candidate.getBizLine()))) {
                candidate.reject("不满足资格条件");
                // 「配了但不发」的观测出口。与 condition-tree-unavailable 分开计数是关键：
                // 「用户不符合门槛」是正常业务，「你的活动坏了」是故障，两者的处理方式完全相反。
                metrics.reject(scene, "ineligible");
                if (explain) traces.add("eligibility reject: " + candidate.getActivityId());
            } else if (explain && candidate.isEligible()) {
                traces.add("eligible: " + candidate.getActivityId());
            }
        }
    }
}
