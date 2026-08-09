package com.lrj.drools.activity.service;

import com.lrj.drools.activity.domain.ActivityCandidate;
import com.lrj.drools.activity.domain.ActivityRuleContext;
import com.lrj.drools.activity.domain.ActivityRuleResult;
import com.lrj.drools.activity.domain.ActivityStatus;
import com.lrj.drools.activity.domain.ActivityType;
import com.lrj.drools.activity.domain.BenefitForm;
import com.lrj.drools.activity.domain.GiftResult;
import com.lrj.drools.activity.domain.SpuDiscountRequest;
import com.lrj.drools.activity.domain.StackStrategy;
import com.lrj.drools.activity.engine.ActivityDrlBuilder.LadderActivityDef;
import com.lrj.drools.activity.engine.ActivityDrlBuilder.LadderTier;
import com.lrj.drools.activity.engine.ActivityRuleRuntimeService;
import com.lrj.drools.activity.engine.BenefitEvaluator;
import com.lrj.drools.activity.engine.BenefitMath;
import com.lrj.drools.activity.engine.ConditionTreeEvaluator;
import com.lrj.drools.activity.engine.RuleSchemaRegistry;
import com.lrj.drools.activity.domain.ConditionNode;
import com.lrj.drools.activity.tenant.TenantContext;
import com.lrj.drools.activity.engine.LadderRangeParser;
import com.lrj.drools.activity.metrics.DecisionMetrics;
import com.lrj.drools.activity.persistence.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 活动查询/决策读路径。收敛自来源 {@code ActivityDynamicRulesServiceImpl#getSpuDiscount} +
 * {@code filterBeginActivityIds} + 买赠查询。
 *
 * 决策链：SPU 绑定 → 过滤生效活动（已上线 + 时间范围内）→ 拍平候选 →
 * 规则引擎（资格淘汰 → 阶梯落档 → 折扣合并）→ 命中活动+金额。
 * 引擎异常/空决策自动回退旧 Java 逻辑（取最大红包金额），并记录 trace。
 * 开关 {@code activity.marketing.rule-engine.enabled} 关闭时直接走旧逻辑。
 */
@Service
public class ActivityQueryService {

    private static final Logger log = LoggerFactory.getLogger(ActivityQueryService.class);
    private static final int NOT_DEL = 0;
    private static final int EFFECTIVE = 1;
    private static final int ENABLED = 1;
    /** 折扣 MAX/PRIORITY 走 O(N²) 自连接；候选数超此上限则告警（P2-22，不静默截断）。 */
    private static final int MAX_CANDIDATES = 200;
    /** 指标 scene 标签（有限集合，防标签基数膨胀）。 */
    private static final String SCENE_DISCOUNT = "spu-discount";
    private static final String SCENE_GIFT = "gifts";

    private final DecisionDataLoader loader;
    private final ActivityRuleRuntimeService ruleRuntime;
    private final DecisionMetrics metrics;
    private final BenefitEvaluator benefits;
    private final ConditionTreeEvaluator conditions;
    private final RuleSchemaRegistry schemaRegistry;

    @Value("${activity.marketing.rule-engine.enabled:true}")
    private boolean ruleEngineEnabled;

    /**
     * P1-2：阶梯落档与折扣合并走**纯 Java**（默认）还是走 Drools。
     *
     * <p>这两件事是标量计算与 reduce，不需要「其它规则的结论」——用规则引擎的代价是
     * 每档一条规则（200 档 = 200 条规则 ≈ 7.6MB KieBase）与 O(N²) 自连接。
     * Drools 路径保留作对照，{@code BenefitEvaluatorParityTest} 逐场景对拍两条路的金额。
     * 出问题时把这个开关翻回 false 即可退回旧实现。
     */
    @Value("${activity.marketing.rule-engine.java-benefit-eval:true}")
    private boolean javaBenefitEval;

    /**
     * P1-3：资格判定直接解释条件树（默认），还是走 Drools 编译 DRL。
     *
     * <p>条件树是<b>单事实布尔谓词</b>，活动之间零交互——不需要「其它规则的结论」，
     * 也就不需要产生式引擎。直接解释树还顺带干掉了「按候选集拼 DRL」这个缓存键爆炸的根源（D2）：
     * 资格是最后一类会随候选组合生成新 DRL 的场景，它一走，KieBase 缓存基本就静止了。
     * Drools 路径保留作对照，翻 false 即回退。
     */
    @Value("${activity.marketing.rule-engine.java-eligibility-eval:true}")
    private boolean javaEligibilityEval;

    public ActivityQueryService(DecisionDataLoader loader,
                                ActivityRuleRuntimeService ruleRuntime,
                                DecisionMetrics metrics,
                                BenefitEvaluator benefits,
                                ConditionTreeEvaluator conditions,
                                RuleSchemaRegistry schemaRegistry) {
        this.loader = loader;
        this.ruleRuntime = ruleRuntime;
        this.metrics = metrics;
        this.benefits = benefits;
        this.conditions = conditions;
        this.schemaRegistry = schemaRegistry;
    }

    // ------------------------------------------------------------------ SPU 优惠

    /**
     * 决策热路径默认 {@code explain=false}——不 emit trace。
     *
     * <p>{@code ActivityDrlBuilder} 早就支持「构建期就不生成 {@code result.trace(...)} 语句」，
     * 但四处调用一直走的是默认 true 重载，于是线上每一次决策都在拼 trace 字符串、装进 List、
     * 序列化进响应体。大租户大规则集下这是纯粹的浪费，还顺带把规则内部结构
     * （命中活动、命中策略、金额推导）暴露给了下游调用方。
     *
     * <p>控制台的试算入口显式传 {@code explain=true}——运营需要看链路；决策平面不传，走 false。
     */
    public DiscountView spuDiscount(SpuDiscountRequest req) {
        return spuDiscount(req, false);
    }

    public DiscountView spuDiscount(SpuDiscountRequest req, boolean explain) {
        return metrics.timeDecision(SCENE_DISCOUNT, () -> spuDiscountInternal(req, explain), DiscountView::mode);
    }

    private DiscountView spuDiscountInternal(SpuDiscountRequest req, boolean explain) {
        // 取数固定 5 次查询（此前 3N+2 次，评估报告 D1）
        DecisionDataLoader.Materials materials = loader.load(req.spuIdList(), ActivityType.RED_PACKAGE, false);
        List<ActivityCandidate> candidates = materials.candidates();
        metrics.candidates(SCENE_DISCOUNT, candidates.size());

        if (candidates.isEmpty()) {
            return new DiscountView(false, null, null, BigDecimal.ZERO, "MAX",
                    List.of("无生效红包活动"), engineMode(false));
        }
        if (candidates.size() > MAX_CANDIDATES) {
            log.warn("候选活动数 {} 超上限 {}：折扣自连接 O(N²) 有性能风险，建议收窄 SPU 或加 selector",
                    candidates.size(), MAX_CANDIDATES);
        }

        ActivityRuleContext ctx = buildContext(req);
        candidates.forEach(ctx::addCandidate);

        if (!ruleEngineEnabled) {
            metrics.fallback(SCENE_DISCOUNT, "engine-disabled");
            return legacyMax(ctx, candidates, "开关关闭，走旧逻辑");
        }

        List<String> traces = new ArrayList<>();

        // 1) 资格淘汰
        if (javaEligibilityEval) {
            applyEligibility(ctx, candidates, materials, explain, traces);
        } else {
            ActivityRuleResult elig = ruleRuntime.evalEligibility(ctx, materials.eligibilityDefs(), explain);
            if (elig == null) {
                traces.add("资格规则回退：全部候选按生效通过");
            } else {
                traces.addAll(elig.getTraces());
            }
        }
        if (explain) {
            for (ActivityCandidate c : candidates) {
                if (c.isEligible()) traces.add("eligible: " + c.getActivityId());
            }
        }

        // 2) 阶梯落档 + 3) 折扣合并
        List<LadderActivityDef> ladderDefs = ladderDefs(candidates);
        StackStrategy strategy = loader.resolveStrategy(candidates);
        ActivityRuleResult disc;

        if (javaBenefitEval) {
            // P1-2：标量计算与 reduce 不进规则引擎。语义逐条复制自原 DRL，见 BenefitEvaluator。
            benefits.applyLadder(ctx, candidates, ladderDefs);
            benefits.computeAmounts(ctx, candidates);
            disc = benefits.merge(candidates, strategy, explain);
        } else {
            if (!ladderDefs.isEmpty() && ctx.getOrderAmount() != null) {
                ActivityRuleResult ladder = ruleRuntime.evalLadder(ctx, ladderDefs, explain);
                if (ladder != null) traces.addAll(ladder.getTraces());
            }
            disc = ruleRuntime.evalDiscount(ctx, strategy, explain);
        }
        if (disc != null && (disc.getHitActivityId() != null || disc.getHitAmount().signum() > 0)) {
            traces.addAll(disc.getTraces());
            // 按活动的命中计数——控制台「按活动看命中量」的唯一数据来源。
            // 基数上限在 DecisionMetrics.hit 里兜住，这里不需要额外判断。
            metrics.hit(SCENE_DISCOUNT, disc.getHitActivityId());
            return new DiscountView(true, disc.getHitActivityId(), disc.getHitActivityName(),
                    disc.getHitAmount(), disc.getStrategy().name(), traces, engineMode(true));
        }

        // 引擎空决策 → 回退旧逻辑（仍尊重资格淘汰结果）。
        // 注意不与 safeRun 里的计数重复：那边计的是「抛异常」，这里计的是「跑通了但没给出结论」。
        metrics.fallback(SCENE_DISCOUNT, "empty-decision");
        traces.add("折扣规则空决策，回退旧逻辑取最大");
        DiscountView legacy = legacyMax(ctx,
                candidates.stream().filter(ActivityCandidate::isEligible).toList(), null);
        return new DiscountView(legacy.hit(), legacy.hitActivityId(), legacy.hitActivityName(),
                legacy.hitAmount(), legacy.strategy(), concat(traces, legacy.traces()), engineMode(true));
    }

    /**
     * 直接解释条件树做资格淘汰（P1-3）。对应 DRL 的 {@code elig_reject_i} + {@code elig_collect}：
     * 有条件的活动，上下文不满足即 reject；没有条件的活动恒通过。
     *
     * <p><b>fail-closed 的关键一处</b>：活动<b>有</b>受控约束（{@code eligibilityDefs} 里有它）
     * 却<b>没有</b>可用的条件树时，绝不能当成"无条件通过"——那是 fail-open，会直接超发。
     * 这种情况只可能来自条件树 JSON 损坏或 schema 漂移，一律按"条件不可判定"淘汰并计数。
     */
    private void applyEligibility(ActivityRuleContext ctx, List<ActivityCandidate> candidates,
                                  DecisionDataLoader.Materials materials,
                                  boolean explain, List<String> traces) {
        Map<String, ConditionNode> trees = materials.eligibilityTrees();
        Set<String> constrained = materials.eligibilityDefs().stream()
                .map(com.lrj.drools.activity.engine.ActivityDrlBuilder.EligibilityRuleDef::activityId)
                .collect(Collectors.toSet());

        String tenant = TenantContext.get();
        for (ActivityCandidate c : candidates) {
            ConditionNode tree = trees.get(c.getActivityId());
            if (tree == null) {
                if (constrained.contains(c.getActivityId())) {
                    // 有约束但树不可用 → 不可判定 → 淘汰（宁可不发，不可超发）
                    metrics.fallback(SCENE_DISCOUNT, "condition-tree-unavailable");
                    c.reject("资格条件不可判定");
                    if (explain) traces.add("eligibility reject: " + c.getActivityId() + "（条件树不可用）");
                }
                continue;
            }
            if (!conditions.matches(tree, ctx, schemaRegistry.resolve(tenant, c.getBizLine()))) {
                c.reject("不满足资格条件");
                if (explain) traces.add("eligibility reject: " + c.getActivityId());
            }
        }
    }

    /** 旧逻辑：同批候选取最大红包金额。 */
    private DiscountView legacyMax(ActivityRuleContext ctx, List<ActivityCandidate> candidates, String note) {
        ActivityCandidate best = null;
        BigDecimal bestAmt = BigDecimal.ZERO;
        for (ActivityCandidate c : candidates) {
            BigDecimal amt = legacyAmount(ctx, c);
            if (best == null || amt.compareTo(bestAmt) > 0) {
                best = c;
                bestAmt = amt;
            }
        }
        List<String> traces = new ArrayList<>();
        if (note != null) traces.add(note);
        if (best == null) {
            return new DiscountView(false, null, null, BigDecimal.ZERO, "MAX", traces, engineMode(false));
        }
        traces.add("legacy MAX 命中 " + best.getActivityId() + " amount=" + bestAmt);
        return new DiscountView(true, best.getActivityId(), best.getActivityName(), bestAmt, "MAX", traces, engineMode(false));
    }

    /**
     * 旧逻辑下某个候选值多少钱。
     *
     * <p><b>折扣型必须在这里也走 {@link BenefitMath}</b>：旧逻辑原来直接把 {@code redPackageAmount}
     * 当成元，而折扣型的这个字段是**折数**——不作区分的话，「打 8 折」在回退路径上会被当成「减 8 元」发出去。
     * 而回退不是罕见分支：引擎开关关闭、规则空决策、规则执行异常，三种情况都会走到这里。
     *
     * <p>算不出来（缺订单金额 / 折数越界）时按 0 计，不参与竞争——旧逻辑是降级路径，
     * 宁可这张券这次不生效，也不能发一个来路不明的数。
     */
    private static BigDecimal legacyAmount(ActivityRuleContext ctx, ActivityCandidate c) {
        if (BenefitForm.of(c.getRedPackageAmountUnit()) == BenefitForm.RATIO_ZHE) {
            BigDecimal off = BenefitMath.ratioDiscount(
                    ctx == null ? null : ctx.getOrderAmount(),
                    c.getRedPackageAmount(),
                    c.getRedPackageMaxDiscount());
            return off == null ? BigDecimal.ZERO : off;
        }
        return c.getRedPackageAmount() == null ? BigDecimal.ZERO : c.getRedPackageAmount();
    }

    // ------------------------------------------------------------------ 买赠

    public GiftView buyAndGetGifts(SpuDiscountRequest req) {
        return buyAndGetGifts(req, false);
    }

    public GiftView buyAndGetGifts(SpuDiscountRequest req, boolean explain) {
        return metrics.timeDecision(SCENE_GIFT, () -> buyAndGetGiftsInternal(req, explain), GiftView::mode);
    }

    private GiftView buyAndGetGiftsInternal(SpuDiscountRequest req, boolean explain) {
        DecisionDataLoader.Materials materials = loader.load(req.spuIdList(), ActivityType.BUY_AND_GET, true);
        List<ActivityCandidate> candidates = materials.candidates();
        metrics.candidates(SCENE_GIFT, candidates.size());
        if (candidates.isEmpty()) {
            return new GiftView(List.of(), List.of("无生效买赠活动"), engineMode(false));
        }

        ActivityRuleContext ctx = buildContext(req);
        candidates.forEach(ctx::addCandidate);

        if (ruleEngineEnabled) {
            ActivityRuleResult r = ruleRuntime.evalGift(ctx, explain);
            if (r != null) {
                return new GiftView(new ArrayList<>(r.getGifts()), r.getTraces(), engineMode(true));
            }
        }
        // 回退：直接汇总所有候选奖品
        if (ruleEngineEnabled) {
            metrics.fallback(SCENE_GIFT, "empty-decision");
        } else {
            metrics.fallback(SCENE_GIFT, "engine-disabled");
        }
        List<GiftResult> all = candidates.stream().flatMap(c -> c.getGifts().stream()).collect(Collectors.toList());
        return new GiftView(all, List.of("买赠规则回退：汇总全部奖品"), engineMode(false));
    }

    // ------------------------------------------------------------------ 公共 helper

    /**
     * 请求维度 → 属性袋的**唯一映射表**（拍板 D12-4）。
     *
     * <p>此前这里是手写的六行 {@code putAttr}，与 {@code RuleSchemaRegistry} 的条件白名单**两处独立维护**，
     * 于是两个方向都漏了：白名单有 {@code storeId} 而这里不写（配了该条件的活动永远不命中，静默不发）；
     * 这里写 {@code userId} 而白名单没有（写了也没人能引用）。
     *
     * <p>现在收敛成一张表，并由 {@code DecisionContextFieldsTest} 钉死不变量
     * 「白名单里的每个 key 都必须在这里有来源」——新增条件字段时若忘了补来源，测试立刻红，
     * 而不是等到线上表现为「这个活动怎么永远不命中」。
     *
     * <p>值可以为 null；{@link ActivityRuleContext#putAttr} 跳过 null，
     * 故「键不存在」与「值为 null」统一表现为访问器返回 null → fail-closed，语义不变。
     *
     * @return key → 值，**必须覆盖当前 schema 白名单的全部字段**
     */
    public static Map<String, Object> requestAttributes(SpuDiscountRequest req) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("orderAmount", req.orderAmount());
        attrs.put("quantity", req.quantity());
        attrs.put("userDistrictId", req.userDistrictId());
        attrs.put("userTags", req.userTags() == null ? null : new ArrayList<>(req.userTags()));
        attrs.put("spuId", req.spuIdList() == null || req.spuIdList().isEmpty() ? null : req.spuIdList().get(0));
        attrs.put("storeId", req.storeId());
        // userId 不在条件白名单里（运营无法用它写条件），但历史上就写入属性袋，保留以免行为漂移。
        attrs.put("userId", req.userId());
        // 订单行同样不进条件白名单——运营写不出「第 3 行单价 > 100」这种条件，也不该能写。
        // 它只服务于「第 N 件折」的算额，故以原始对象入袋，由 BenefitEvaluator 直接取用。
        attrs.put("orderLines", req.lines() == null || req.lines().isEmpty() ? null : new ArrayList<>(req.lines()));
        return attrs;
    }

    /** 把请求维度写进 Map fact 的属性袋。putAttr 跳过 null → 缺字段归一成"键不存在"→ fail-closed。 */
    private ActivityRuleContext buildContext(SpuDiscountRequest req) {
        ActivityRuleContext ctx = new ActivityRuleContext();
        requestAttributes(req).forEach(ctx::putAttr);
        return ctx;
    }

    private List<LadderActivityDef> ladderDefs(List<ActivityCandidate> candidates) {
        List<LadderActivityDef> defs = new ArrayList<>();
        for (ActivityCandidate c : candidates) {
            if (c.getRedPackageRangeAmount() == null || c.getRedPackageRangeAmount().isBlank()) continue;
            List<LadderTier> tiers = LadderRangeParser.parse(c.getRedPackageRangeAmount());
            // 电商阶梯落档比订单金额；出行等其它 bizLine 由 schema 决定字段（Track A 固定 orderAmount）
            if (!tiers.isEmpty()) defs.add(new LadderActivityDef(c.getActivityId(), tiers, "orderAmount"));
        }
        return defs;
    }

    private String engineMode(boolean engine) { return engine ? "rule-engine" : "legacy"; }

    private List<String> concat(List<String> a, List<String> b) {
        List<String> r = new ArrayList<>(a);
        r.addAll(b);
        return r;
    }

    // ------------------------------------------------------------------ 返回结构

    public record DiscountView(boolean hit, String hitActivityId, String hitActivityName,
                               BigDecimal hitAmount, String strategy, List<String> traces, String mode) {}

    public record GiftView(List<GiftResult> gifts, List<String> traces, String mode) {}
}
