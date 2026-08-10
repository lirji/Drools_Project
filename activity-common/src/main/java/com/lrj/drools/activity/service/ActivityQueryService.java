package com.lrj.drools.activity.service;

import com.lrj.drools.activity.domain.ActivityCandidate;
import com.lrj.drools.activity.domain.ActivityRuleContext;
import com.lrj.drools.activity.domain.ActivityRuleResult;
import com.lrj.drools.activity.domain.ActivityType;
import com.lrj.drools.activity.domain.GiftResult;
import com.lrj.drools.activity.domain.SpuDiscountRequest;
import com.lrj.drools.activity.domain.StackStrategy;
import com.lrj.drools.activity.engine.ActivityDrlBuilder.LadderActivityDef;
import com.lrj.drools.activity.engine.ActivityDrlBuilder.LadderTier;
import com.lrj.drools.activity.engine.ActivityRuleRuntimeService;
import com.lrj.drools.activity.engine.BenefitEvaluator;
import com.lrj.drools.activity.engine.LadderRangeParser;
import com.lrj.drools.activity.metrics.DecisionMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 活动查询/决策读路径。收敛自来源 {@code ActivityDynamicRulesServiceImpl#getSpuDiscount} +
 * {@code filterBeginActivityIds} + 买赠查询。
 *
 * 决策链：SPU 绑定 → 过滤生效活动（已上线 + 时间范围内）→ 拍平候选 →
 * 共享 Java 资格树 → {@link BenefitEvaluator} 六形态算额与合并 → 命中活动+金额。
 * 主求值无可用决策时自动走同语义的安全 Java 重算，不改变资格或合并策略。
 * 规则运行时仍服务买赠；折扣资格与算额不再受两个旧 Java 灰度开关分支。
 */
@Service
public class ActivityQueryService {

    private static final Logger log = LoggerFactory.getLogger(ActivityQueryService.class);
    /** 候选数保护告警（P2-22，不静默截断）：过大候选集仍会拉高取数、trace 与算额成本。 */
    private static final int MAX_CANDIDATES = 200;
    /** 指标 scene 标签（有限集合，防标签基数膨胀）。 */
    private static final String SCENE_DISCOUNT = "spu-discount";
    private static final String SCENE_GIFT = "gifts";

    private final DecisionDataLoader loader;
    private final ActivityRuleRuntimeService ruleRuntime;
    private final DecisionMetrics metrics;
    private final BenefitEvaluator benefits;
    private final DecisionEligibilityService eligibility;

    @Value("${activity.marketing.rule-engine.enabled:true}")
    private boolean ruleEngineEnabled;

    /**
     * 旧灰度属性仅保留配置兼容，不再改变生产求值器。
     * 随机/一口价/第 N 件折必须由 {@link BenefitEvaluator} 解释；旧 DRL 不能成为线上回退。
     */
    @Value("${activity.marketing.rule-engine.java-benefit-eval:true}")
    @SuppressWarnings("unused")
    private boolean javaBenefitEval;

    /**
     * 同上：生产 discount/gift/addon 资格均由 {@link DecisionEligibilityService}
     * 的条件树求值，不允许翻回 generated DRL 这份第二权威。
     */
    @Value("${activity.marketing.rule-engine.java-eligibility-eval:true}")
    @SuppressWarnings("unused")
    private boolean javaEligibilityEval;

    public ActivityQueryService(DecisionDataLoader loader,
                                ActivityRuleRuntimeService ruleRuntime,
                                DecisionMetrics metrics,
                                BenefitEvaluator benefits,
                                DecisionEligibilityService eligibility) {
        this.loader = loader;
        this.ruleRuntime = ruleRuntime;
        this.metrics = metrics;
        this.benefits = benefits;
        this.eligibility = eligibility;
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
        DiscountView v = metrics.timeDecision(SCENE_DISCOUNT,
                () -> spuDiscountInternal(req, explain), DiscountView::mode);
        // 按活动的命中计数打在**唯一出口**上，而不是引擎命中的那个分支里。
        // 打在分支里会漏掉回退路径（safeFallback 也会命中活动），于是「按活动命中量」在
        // 引擎回退时系统性少计——**少计的指标比没有指标更危险**，因为它看起来是权威的，
        // 而回退恰恰是最需要盯着的时刻。基数上限由 DecisionMetrics.hit 兜住。
        if (v != null && v.hit()) metrics.hit(SCENE_DISCOUNT, v.hitActivityId());
        return v;
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
            log.warn("候选活动数 {} 超上限 {}：取数、trace 与六形态算额成本上升，建议收窄 SPU 或加 selector",
                    candidates.size(), MAX_CANDIDATES);
        }

        ActivityRuleContext ctx = eligibility.buildContext(req, candidates);
        List<String> traces = new ArrayList<>();
        // 执行器可回退，业务合并策略不能回退：STACK/PRIORITY/MAX 始终取当前配置。
        StackStrategy strategy = loader.resolveStrategy(candidates);

        if (!ruleEngineEnabled) {
            // 总开关关闭只切换算额实现，绝不能顺带把资格条件关闭。
            eligibility.applyJava(ctx, materials, SCENE_DISCOUNT, explain, traces);
            metrics.fallback(SCENE_DISCOUNT, "engine-disabled");
            DiscountView legacy = safeFallback(ctx, candidates, strategy, "开关关闭，走安全 Java 回退");
            return new DiscountView(legacy.hit(), legacy.hitActivityId(), legacy.hitActivityName(),
                    legacy.hitAmount(), legacy.strategy(), concat(traces, legacy.traces()), legacy.mode());
        }

        // 1) 资格淘汰：线上只有这一份条件树语义。
        eligibility.applyJava(ctx, materials, SCENE_DISCOUNT, explain, traces);

        // 2) 阶梯落档 + 3) 折扣合并
        List<LadderActivityDef> ladderDefs = ladderDefs(candidates);
        // 六形态共用 BenefitEvaluator；旧 DRL 可留作隔离对拍，不再是生产切换项。
        benefits.applyLadder(ctx, candidates, ladderDefs);
        List<String> applicableBefore = explain ? eligibleIds(candidates) : List.of();
        benefits.computeAmounts(ctx, candidates);
        if (explain) {
            for (ActivityCandidate c : candidates) {
                if (!c.isEligible() && applicableBefore.contains(c.getActivityId())) {
                    traces.add("not applicable: " + c.getActivityId() + "（" + c.getRejectReason() + "）");
                }
            }
        }
        ActivityRuleResult disc = benefits.merge(candidates, strategy, explain);
        if (disc != null && (disc.getHitActivityId() != null || disc.getHitAmount().signum() > 0)) {
            traces.addAll(disc.getTraces());
            return new DiscountView(true, disc.getHitActivityId(), disc.getHitActivityName(),
                    disc.getHitAmount(), disc.getStrategy().name(), traces, engineMode(true));
        }

        // Java 主求值无可用决策 → 走一遍 safeFallback。**诚实声明**：它与主路径是同一个
        // BenefitEvaluator、同样的输入，重算必然得到同样的空结果——这里的价值只剩两件事：
        // fallback 指标计数 + mode=legacy 标签（让「空决策」在监控上与「正常未命中」可区分）。
        // 它不是另一套算法，别指望它救回任何决策；若嫌这轮白算，可直接构造空 DiscountView 返回。
        metrics.fallback(SCENE_DISCOUNT, "empty-decision");
        traces.add("折扣求值无可用决策，回退安全 Java 算额并保留合并策略");
        DiscountView legacy = safeFallback(ctx, candidates, strategy, null);
        return new DiscountView(legacy.hit(), legacy.hitActivityId(), legacy.hitActivityName(),
                legacy.hitAmount(), legacy.strategy(), concat(traces, legacy.traces()), engineMode(true));
    }

    private static List<String> eligibleIds(List<ActivityCandidate> candidates) {
        return candidates.stream().filter(ActivityCandidate::isEligible)
                .map(ActivityCandidate::getActivityId).toList();
    }

    /**
     * 安全回退：保持 {@code mode=legacy}，但算额与合并都复用当前业务语义。
     * {@link BenefitEvaluator} 保证固定/随机/阶梯/折扣/一口价/第 N 件折不会在回退时换语义。
     *
     * <p>规则执行可能在抛异常前已改过候选的算额字段，故对仍 eligible 的候选先清理计算态再重算；
     * 资格淘汰态不能清，否则回退会把不满足门槛的活动重新放进来。
     */
    private DiscountView safeFallback(ActivityRuleContext ctx, List<ActivityCandidate> candidates,
                                      StackStrategy strategy, String note) {
        for (ActivityCandidate c : candidates) {
            if (!c.isEligible()) continue;
            c.setComputedAmount(BigDecimal.ZERO);
            c.setAmountComputed(false);
        }
        benefits.applyLadder(ctx, candidates, ladderDefs(candidates));
        benefits.computeAmounts(ctx, candidates);
        ActivityRuleResult result = benefits.merge(candidates, strategy, false);

        List<String> traces = new ArrayList<>();
        if (note != null) traces.add(note);
        if (result.getHitActivityId() == null) {
            return new DiscountView(false, null, null, BigDecimal.ZERO, strategy.name(), traces, engineMode(false));
        }
        traces.add("legacy " + strategy.name() + " 命中 " + result.getHitActivityId()
                + " amount=" + result.getHitAmount());
        return new DiscountView(true, result.getHitActivityId(), result.getHitActivityName(),
                result.getHitAmount(), strategy.name(), traces, engineMode(false));
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

        ActivityRuleContext ctx = eligibility.buildContext(req, candidates);
        List<String> traces = new ArrayList<>();
        // 买赠和红包共享同一份资格语义；满额、数量、人群、门店、地域条件都先在这里淘汰。
        eligibility.applyJava(ctx, materials, SCENE_GIFT, explain, traces);

        if (ruleEngineEnabled) {
            ActivityRuleResult r = ruleRuntime.evalGift(ctx, explain);
            if (r != null) {
                traces.addAll(r.getTraces());
                return new GiftView(new ArrayList<>(r.getGifts()), traces, engineMode(true));
            }
        }
        // 回退也只能汇总资格通过的候选。直接汇总全部候选会把「满 500 赠品」发给 499 元订单。
        if (ruleEngineEnabled) {
            metrics.fallback(SCENE_GIFT, "empty-decision");
        } else {
            metrics.fallback(SCENE_GIFT, "engine-disabled");
        }
        List<GiftResult> all = candidates.stream().filter(ActivityCandidate::isEligible)
                .flatMap(c -> c.getGifts().stream()).collect(Collectors.toList());
        traces.add("买赠规则回退：汇总资格通过候选的奖品");
        return new GiftView(all, traces, engineMode(false));
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
        return DecisionEligibilityService.requestAttributes(req);
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

    /**
     * 兼容响应档位：{@code rule-engine} 表示总开关开启，不声明 discount 的标量算额由 Drools 执行。
     * discount 生产算额已统一为 {@link BenefitEvaluator}；保留旧字符值避免破坏客户端与指标面板。
     */
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
