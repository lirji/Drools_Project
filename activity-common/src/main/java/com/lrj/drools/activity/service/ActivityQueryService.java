package com.lrj.drools.activity.service;

import com.lrj.drools.activity.domain.ActivityCandidate;
import com.lrj.drools.activity.domain.ActivityRuleContext;
import com.lrj.drools.activity.domain.ActivityRuleResult;
import com.lrj.drools.activity.domain.ActivityType;
import com.lrj.drools.activity.domain.BenefitForm;
import com.lrj.drools.activity.domain.DecisionProvenance;
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
    /**
     * 决策审计日志。**独立 logger 名**，好让日志采集按名字单独路由到长保留期的索引——
     * 与本类的运行日志混在一起时，要么审计跟着 DEBUG 一起被丢掉，要么运行日志跟着审计一起长期留存。
     */
    private static final Logger audit = LoggerFactory.getLogger("activity.decision.audit");
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
        if (v != null && v.hit()) {
            metrics.hit(SCENE_DISCOUNT, v.hitActivityId());
            // 金额也必须打点。此前这里手上握着 hitAmount 却只计了「命中了」——
            // 于是「满 300 减 50」被配成「满 3 减 50」时监控全盘绿灯：
            // 回退率 0、耗时正常、命中数只是稍高，没有任何一条指标会动。
            metrics.amount(SCENE_DISCOUNT, v.hitActivityId(), v.hitAmount());
        }
        auditLog(SCENE_DISCOUNT, req, v);
        return v;
    }

    /**
     * <b>决策留痕</b>——一条结构化日志，让客服查得到单、财务归得了因。
     *
     * <p>此前决策路径<b>零 repository 写入、零业务日志</b>（类内唯一的 log 是候选数超限 warn）：
     * 用户投诉「该减 50 只减了 20」，系统里能查到的只有「活动配置<em>现在</em>长什么样」——
     * 查不到当时问了什么、返回了什么、命中哪个活动、按哪一版算的。
     *
     * <p><b>为什么是日志而不是表</b>：decision 服务连的是只读账号，物理上写不了库
     * （{@code DecisionDdlGuardTest} 钉死这条边界）。让热路径去写库会同时毁掉
     * 「只读副本可扩」与「写面独占 DDL」两条边界。落库版本（{@code activity_decision_log}）
     * 需要给 decision 配一个只对流水表有 INSERT 权限的第二数据源，属于独立决策；
     * 在那之前，结构化日志 + 集中采集是零架构变更就能拿到的那 80%。
     *
     * <p>格式刻意是**单行 JSON**：日志系统能直接按 {@code decisionId} 检索，
     * 而人也能在终端里一眼读完。热路径开销是一次字符串拼接，不做序列化框架调用。
     */
    private void auditLog(String scene, SpuDiscountRequest req, DiscountView v) {
        if (v == null || !audit.isInfoEnabled()) return;
        StringBuilder items = new StringBuilder("[");
        for (int i = 0; i < v.items().size(); i++) {
            DiscountItem it = v.items().get(i);
            if (i > 0) items.append(',');
            items.append("{\"activityId\":\"").append(it.activityId())
                 .append("\",\"version\":").append(it.version())
                 .append(",\"form\":\"").append(it.benefitForm())
                 .append("\",\"amount\":").append(it.amount())
                 .append(",\"applied\":").append(it.applied())
                 .append(",\"reject\":").append(it.rejectReason() == null
                         ? "null" : "\"" + it.rejectReason().replace("\"", "'") + "\"")
                 .append('}');
        }
        items.append(']');
        // source/generation 必须一起落：只有 hitVersion（活动版本）而没有代际时，
        // 「活动版本对、但快照是旧代」这类事故在日志里查不出来——而它恰恰是客服最难缠的那类工单。
        DecisionProvenance p = v.provenance() == null ? DecisionProvenance.db() : v.provenance();
        audit.info("{\"decisionId\":\"{}\",\"scene\":\"{}\",\"userId\":{},\"spuIds\":{},"
                        + "\"orderAmount\":{},\"hit\":{},\"hitActivityId\":{},\"hitVersion\":{},"
                        + "\"amount\":{},\"strategy\":\"{}\",\"clamped\":{},\"mode\":\"{}\","
                        + "\"source\":\"{}\",\"generation\":{},\"items\":{}}",
                v.decisionId(), scene, req.userId(), req.spuIdList(),
                req.orderAmount(), v.hit(),
                v.hitActivityId() == null ? null : "\"" + v.hitActivityId() + "\"",
                v.hitVersion(), v.hitAmount(), v.strategy(), v.clamped(), v.mode(),
                p.source(), p.generation(), items);
    }

    private DiscountView spuDiscountInternal(SpuDiscountRequest req, boolean explain) {
        // 每次决策一个 id：它不落库，只是让「响应 / 结构化日志 / 下游账」三者能对上同一次决策。
        // 客服拿着用户给的这一串就能在日志里定位到当时的入参与逐候选结果。
        String decisionId = newDecisionId();

        // 取数固定 5 次查询（此前 3N+2 次，评估报告 D1）
        DecisionDataLoader.Materials materials = loader.load(req.spuIdList(), ActivityType.RED_PACKAGE, false);
        List<ActivityCandidate> candidates = materials.candidates();
        metrics.candidates(SCENE_DISCOUNT, candidates.size());

        if (candidates.isEmpty()) {
            // **这条路径上 provenance 是唯一的信息**：候选空、items 空、traces 只有一句套话，
            // 而「照着一份快照算出来没活动」与「刚查完库确实没活动」是两个完全不同的结论。
            return DiscountView.miss("MAX", List.of("无生效红包活动"), engineMode(false), decisionId, List.of(),
                    materials.provenance());
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
            DiscountView legacy = safeFallback(ctx, candidates, strategy, "开关关闭，走安全 Java 回退", decisionId,
                    materials.provenance());
            return legacy.withTraces(concat(traces, legacy.traces()));
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
        ActivityRuleResult disc = benefits.merge(ctx, candidates, strategy, explain);
        if (disc != null && (disc.getHitActivityId() != null || disc.getHitAmount().signum() > 0)) {
            traces.addAll(disc.getTraces());
            return new DiscountView(true, disc.getHitActivityId(), disc.getHitActivityName(),
                    disc.getHitAmount(), disc.getStrategy().name(), traces, engineMode(true),
                    versionOf(candidates, disc.getHitActivityId()), disc.isClamped(), decisionId,
                    items(candidates, disc.getHitActivityId(), strategy), materials.provenance());
        }

        // Java 主求值无可用决策 → 走一遍 safeFallback。**诚实声明**：它与主路径是同一个
        // BenefitEvaluator、同样的输入，重算必然得到同样的空结果——这里的价值只剩两件事：
        // fallback 指标计数 + mode=legacy 标签（让「空决策」在监控上与「正常未命中」可区分）。
        // 它不是另一套算法，别指望它救回任何决策；若嫌这轮白算，可直接构造空 DiscountView 返回。
        metrics.fallback(SCENE_DISCOUNT, "empty-decision");
        traces.add("折扣求值无可用决策，回退安全 Java 算额并保留合并策略");
        DiscountView legacy = safeFallback(ctx, candidates, strategy, null, decisionId, materials.provenance());
        return legacy.withTraces(concat(traces, legacy.traces())).withMode(engineMode(true));
    }

    /**
     * 逐活动明细。**数据一直都在，只是过去被编排层丢掉了。**
     *
     * <p>此前响应只有一个 {@code hitActivityId} + 一个总额：STACK 下三张券各减 10/20/30，
     * 调用方拿到的是 {@code amount=60, activityId=A}（A 只是 priority 最小的那个），
     * B 和 C 在响应里彻底不存在。下游想记一笔「哪张券出了多少钱」的账都无从记起，
     * 客服想回答「我的另外两张券用掉了吗」也答不上来。
     *
     * <p>连**被淘汰**的候选也一起给出（带 {@code rejectReason}）——「为什么我没享受到」
     * 和「我享受了多少」是同一个问题的两面，而前者才是客服工单里的多数。
     *
     * @param hitActivityId 单选策略下的赢家；STACK 下是主活动（不代表只有它出了钱）
     */
    private static List<DiscountItem> items(List<ActivityCandidate> candidates,
                                            String hitActivityId, StackStrategy strategy) {
        List<DiscountItem> out = new ArrayList<>(candidates.size());
        for (ActivityCandidate c : candidates) {
            // STACK 下每个合格候选都真金白银地出了钱；单选策略下只有赢家算数。
            boolean applied = c.isEligible()
                    && (strategy == StackStrategy.STACK || c.getActivityId().equals(hitActivityId));
            out.add(new DiscountItem(
                    c.getActivityId(), c.getActivityName(), c.getVersion(),
                    BenefitForm.of(c.getRedPackageAmountUnit()).name(),
                    c.getComputedAmount() == null ? BigDecimal.ZERO : c.getComputedAmount(),
                    applied, c.getRejectReason()));
        }
        return out;
    }

    /** 命中活动的版本号——「这笔钱按哪一版算的」是对账与客服回溯的第一个问题。 */
    private static Integer versionOf(List<ActivityCandidate> candidates, String activityId) {
        if (activityId == null) return null;
        for (ActivityCandidate c : candidates) {
            if (activityId.equals(c.getActivityId())) return c.getVersion();
        }
        return null;
    }

    private static String newDecisionId() {
        return java.util.UUID.randomUUID().toString();
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
                                      StackStrategy strategy, String note, String decisionId,
                                      DecisionProvenance provenance) {
        for (ActivityCandidate c : candidates) {
            if (!c.isEligible()) continue;
            c.setComputedAmount(BigDecimal.ZERO);
            c.setAmountComputed(false);
            // 落档留痕也是计算态，必须一起清：留着上一轮的 true，本轮没落档的候选就淘汰不掉了。
            c.setLadderApplied(false);
        }
        benefits.applyLadder(ctx, candidates, ladderDefs(candidates));
        benefits.computeAmounts(ctx, candidates);
        ActivityRuleResult result = benefits.merge(ctx, candidates, strategy, false);

        List<String> traces = new ArrayList<>();
        if (note != null) traces.add(note);
        List<DiscountItem> items = items(candidates, result.getHitActivityId(), strategy);
        if (result.getHitActivityId() == null) {
            return DiscountView.miss(strategy.name(), traces, engineMode(false), decisionId, items, provenance);
        }
        traces.add("legacy " + strategy.name() + " 命中 " + result.getHitActivityId()
                + " amount=" + result.getHitAmount());
        return new DiscountView(true, result.getHitActivityId(), result.getHitActivityName(),
                result.getHitAmount(), strategy.name(), traces, engineMode(false),
                versionOf(candidates, result.getHitActivityId()), result.isClamped(), decisionId,
                items, provenance);
    }

    // ------------------------------------------------------------------ 买赠

    public GiftView buyAndGetGifts(SpuDiscountRequest req) {
        return buyAndGetGifts(req, false);
    }

    public GiftView buyAndGetGifts(SpuDiscountRequest req, boolean explain) {
        return metrics.timeDecision(SCENE_GIFT, () -> buyAndGetGiftsInternal(req, explain), GiftView::mode);
    }

    private GiftView buyAndGetGiftsInternal(SpuDiscountRequest req, boolean explain) {
        String decisionId = newDecisionId();
        DecisionDataLoader.Materials materials = loader.load(req.spuIdList(), ActivityType.BUY_AND_GET, true);
        List<ActivityCandidate> candidates = materials.candidates();
        metrics.candidates(SCENE_GIFT, candidates.size());
        if (candidates.isEmpty()) {
            return new GiftView(List.of(), List.of("无生效买赠活动"), engineMode(false), decisionId,
                    materials.provenance());
        }

        ActivityRuleContext ctx = eligibility.buildContext(req, candidates);
        List<String> traces = new ArrayList<>();
        // 买赠和红包共享同一份资格语义；满额、数量、人群、门店、地域条件都先在这里淘汰。
        eligibility.applyJava(ctx, materials, SCENE_GIFT, explain, traces);

        if (ruleEngineEnabled) {
            ActivityRuleResult r = ruleRuntime.evalGift(ctx, explain);
            if (r != null) {
                traces.addAll(r.getTraces());
                // 命中的买赠活动也要计数：此前 metrics.hit 的唯一调用点在红包出口，
                // 于是「按活动看命中量」在买赠通道上恒为 0——不是没人用，是根本没埋。
                for (ActivityCandidate c : r.getEligibleCandidates()) {
                    metrics.hit(SCENE_GIFT, c.getActivityId());
                }
                return new GiftView(new ArrayList<>(r.getGifts()), traces, engineMode(true), decisionId,
                        materials.provenance());
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
        candidates.stream().filter(ActivityCandidate::isEligible)
                .forEach(c -> metrics.hit(SCENE_GIFT, c.getActivityId()));
        traces.add("买赠规则回退：汇总资格通过候选的奖品");
        return new GiftView(all, traces, engineMode(false), decisionId, materials.provenance());
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

    /**
     * 折扣决策出参。
     *
     * <p>前七个字段是历史契约，位置与语义一律不变（现有客户端、e2e 与指标面板都读它）。
     * 后四个是本轮新增的**纯增量**：
     * <ul>
     *   <li>{@code hitVersion}——这笔钱按活动的哪一版算的；对账与客服回溯的第一个问题</li>
     *   <li>{@code clamped}——减免额是否被订单金额截断过；true 基本等价于「这个活动配错了」</li>
     *   <li>{@code decisionId}——本次决策的对账锚点，不落库，与结构化日志同值</li>
     *   <li>{@code items}——逐活动明细（含被淘汰的与原因）。STACK 下另外 N−1 个活动此前
     *       在响应里完全不存在，下游连自建流水都建不对</li>
     *   <li>{@code provenance}——这次的物料是快照还是走库（见 {@link DecisionProvenance}）。
     *       没有它，控制台「优惠验证」页照出来的永远是它自己那条走库路径的结论</li>
     * </ul>
     *
     * <p><b>三个 helper 是全分量重列式</b>（{@code miss}/{@code withTraces}/{@code withMode}）：
     * 漏传一个分量不会编译失败，只会静默丢值。加分量时必须逐个核对——这也正是 provenance
     * 用一个 record 而不是平铺三个字段的原因（平铺后 source/strategy/mode/decisionId
     * 四个相邻同类型 String 可以换位而编译通过）。
     */
    public record DiscountView(boolean hit, String hitActivityId, String hitActivityName,
                               BigDecimal hitAmount, String strategy, List<String> traces, String mode,
                               Integer hitVersion, boolean clamped, String decisionId,
                               List<DiscountItem> items, DecisionProvenance provenance) {

        /** 未命中。金额恒 0、无版本、未截断——把这套「空值组合」收敛到一处，免得各构造点各写一遍。 */
        public static DiscountView miss(String strategy, List<String> traces, String mode,
                                        String decisionId, List<DiscountItem> items,
                                        DecisionProvenance provenance) {
            return new DiscountView(false, null, null, BigDecimal.ZERO, strategy, traces, mode,
                    null, false, decisionId, items, provenance);
        }

        public DiscountView withTraces(List<String> newTraces) {
            return new DiscountView(hit, hitActivityId, hitActivityName, hitAmount, strategy, newTraces, mode,
                    hitVersion, clamped, decisionId, items, provenance);
        }

        public DiscountView withMode(String newMode) {
            return new DiscountView(hit, hitActivityId, hitActivityName, hitAmount, strategy, traces, newMode,
                    hitVersion, clamped, decisionId, items, provenance);
        }
    }

    /**
     * 单个候选活动对本次决策的贡献。{@code applied=false} 时 {@code rejectReason} 说明为什么没生效
     * （资格不满足 / 阶梯未落档 / 一口价高于订单金额 / 缺订单行 …）。
     */
    public record DiscountItem(String activityId, String activityName, Integer version,
                               String benefitForm, BigDecimal amount, boolean applied, String rejectReason) {}

    /**
     * 买赠决策出参。买赠与红包跑在**同一个** loader 上，快照陈旧对它的影响与红包完全一样，
     * 所以 provenance 必须同样带出来。
     */
    public record GiftView(List<GiftResult> gifts, List<String> traces, String mode, String decisionId,
                           DecisionProvenance provenance) {}
}
