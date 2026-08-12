package com.lrj.drools.activity.service;

import com.lrj.drools.activity.domain.ActivityCandidate;
import com.lrj.drools.activity.domain.ActivityType;
import com.lrj.drools.activity.domain.DecisionMode;
import com.lrj.drools.activity.domain.DecisionProvenance;
import com.lrj.drools.activity.domain.DecisionScene;
import com.lrj.drools.activity.domain.GiftResult;
import com.lrj.drools.activity.domain.SpuDiscountRequest;
import com.lrj.drools.activity.metrics.DecisionMetrics;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 加价购的**两阶段决策**。
 *
 * <p>加价购此前做不了，卡点不在算钱而在<b>交互形状</b>：既有决策链路是「一次调用返回最终优惠」，
 * 而加价购必须<b>先返回可换购清单 → 等用户挑一个 → 再二次定价</b>。硬塞进一次性接口的做法
 * （比如直接返回最便宜的那个换购品）会替用户做主，那不是这个玩法的意思。
 *
 * <h3>为什么第二阶段不发 token、而是重新查一遍</h3>
 * 常见做法是第一阶段签一个 quoteToken 带着价格，第二阶段验签后直接用。那需要引入密钥管理、
 * 过期策略与重放窗口，而收益为零——因为<b>服务端本来就能重新算出权威价格</b>。
 * 本实现第二阶段<b>完全不信任客户端传来的价格</b>，只接受「选了哪个换购品」，价格一律重新查。
 * 这样既没有密钥要管，也从根上杜绝了改价：客户端把 9.9 改成 0.01 是没用的，服务端根本不读它。
 *
 * <h3>与库存的关系</h3>
 * 报价不等于抢到。换购品的库存扣减同样要走写平面的 claim 端点——
 * 详见 {@code ActivityMarketingService.claimInventory}。决策服务连只读账号，写不了库。
 */
@Service
public class AddOnPurchaseService {

    private static final DecisionScene SCENE_ADDON = DecisionScene.ADDON;

    /**
     * {@code activity.decision.duration} 的 {@code mode} 标签取值——加价购这里是<b>阶段名</b>。
     *
     * <p>另外两条通道那里 {@code mode} 取 {@code rule-engine}/{@code legacy}，与响应体的 mode
     * 字段同源。加价购<b>没有</b>那个字段：它压根不进规则引擎，也就无所谓「回退」，
     * 硬填一个 {@code legacy} 会让面板读成「加价购一直在回退」。
     *
     * <p>改用两个阶段名更有信息量：{@code quote} 自己会重跑一遍装载与资格，它的耗时天然高于
     * {@code options}，混在一个序列里看分位数只会互相污染。这两个取值都是<b>新序列</b>
     * （此前加价购一个点都没埋），不改动任何既有面板。
     */
    static final String PHASE_OPTIONS = "options";
    static final String PHASE_QUOTE = "quote";

    private final DecisionDataLoader loader;
    private final DecisionEligibilityService eligibility;
    private final DecisionMetrics metrics;
    private final DecisionAuditor auditor;

    public AddOnPurchaseService(DecisionDataLoader loader, DecisionEligibilityService eligibility,
                                DecisionMetrics metrics, DecisionAuditor auditor) {
        this.loader = loader;
        this.eligibility = eligibility;
        this.metrics = metrics;
        this.auditor = auditor;
    }

    /** 一个可换购选项。{@code addOnPrice} 是<b>加多少钱</b>，不是换购品原价。 */
    public record AddOnOption(String activityId, String activityName, Integer version,
                              String itemName, BigDecimal addOnPrice) {}

    /**
     * 第一阶段结果。{@code options} 为空表示这一单没有可换购的东西。
     *
     * <p>{@code decisionId} 是<b>纯增量</b>分量：另外两条通道早就有它，加价购此前没有——
     * 于是客服拿着加价购的工单在审计日志里什么也查不到，因为这条通道既没有 id、也从不落日志。
     */
    public record AddOnOptions(List<AddOnOption> options, List<String> traces,
                               DecisionProvenance provenance, String decisionId) {
        /** 两参兼容构造：provenance 缺省为「走库」，无对账锚点。 */
        public AddOnOptions(List<AddOnOption> options, List<String> traces) {
            this(options, traces, DecisionProvenance.db(), null);
        }

        /** 三参兼容构造：带 provenance 但还没接上对账锚点的调用点落在这里。 */
        public AddOnOptions(List<AddOnOption> options, List<String> traces, DecisionProvenance provenance) {
            this(options, traces, provenance, null);
        }
    }

    /**
     * 第二阶段结果。{@code ok=false} 时 {@code reason} 说明为什么不能换购
     * （选项已失效 / 活动已下线 / 参数对不上）。
     *
     * <p>{@code provenance} 必须来自 quote <b>自己那次</b>重新装载，而不是第一阶段的——
     * 两阶段之间快照可能已经换代，而「这个价是按哪一代报的」正是这个端点最该自证的事。
     */
    public record AddOnQuote(boolean ok, String activityId, String itemName,
                             BigDecimal addOnPrice, String reason, List<String> traces,
                             DecisionProvenance provenance, String decisionId) {
        /** 六参兼容构造：带 traces 但还没接上 provenance 的调用点落在这里，缺省为「走库」。 */
        public AddOnQuote(boolean ok, String activityId, String itemName,
                          BigDecimal addOnPrice, String reason, List<String> traces) {
            this(ok, activityId, itemName, addOnPrice, reason, traces, DecisionProvenance.db(), null);
        }

        /** 七参兼容构造：带 provenance 但还没接上对账锚点的调用点落在这里。 */
        public AddOnQuote(boolean ok, String activityId, String itemName,
                          BigDecimal addOnPrice, String reason, List<String> traces,
                          DecisionProvenance provenance) {
            this(ok, activityId, itemName, addOnPrice, reason, traces, provenance, null);
        }
    }

    /**
     * 第一阶段：这一单能换购什么。
     *
     * <p>只回答「有哪些选项、各加多少钱」，**不替用户挑**。选项为空是正常结果，
     * 不是错误——调用方据此不展示换购入口即可。
     *
     * <p>{@code mode} 与 discount 链路同一分档约定：console 试算传 {@link DecisionMode#EXPLAIN}
     * （逐候选资格 trace 外显），决策热路径传 {@link DecisionMode#HOT_PATH}（只保留结构性 trace）。
     * 此前这里写死 true，资格淘汰明细恒随热路径响应外泄。
     *
     * <p><b>档位必须显式传</b>：便捷重载已删。它此前默认的是 {@code true}，
     * 而姊妹服务 {@code ActivityQueryService} 的同类重载默认 {@code false}——两个默认值方向相反，
     * 而调用点上看不出来。今天没出事只是因为 console 恰好调这一侧、decision 恰好调那一侧。
     */
    public AddOnOptions options(SpuDiscountRequest req, DecisionMode mode) {
        AddOnOptions o = metrics.timeDecision(SCENE_ADDON,
                () -> optionsInternal(req, mode, newDecisionId()), r -> PHASE_OPTIONS);
        auditor.addOnOptions(SCENE_ADDON, req, o);
        return o;
    }

    /**
     * 不计时的第一阶段本体。
     *
     * <p><b>{@link #quote} 必须走这一个、不能走公开的 {@link #options}</b>：那会让一次 quote
     * 产生两层 Timer 计时（外层 quote 的耗时把内层 options 整个包在里面，两条序列各记一次，
     * 分位数与 QPS 全被重复计入）。计时只挂在两个公开入口上，内部复用一律走这里。
     */
    private AddOnOptions optionsInternal(SpuDiscountRequest req, DecisionMode mode, String decisionId) {
        List<String> traces = new ArrayList<>();
        Materials materials = loader.load(req.spuIdList(), ActivityType.ADD_ON_PURCHASE, SCENE_ADDON, true);
        List<ActivityCandidate> candidates = materials.candidates();
        // 候选数分布：加价购与另外两条通道共用取数层，N 同样是成本自变量，此前这条通道一个点都没埋。
        metrics.candidates(SCENE_ADDON, candidates.size());
        if (candidates.isEmpty()) {
            traces.add("无生效加价购活动");
            return new AddOnOptions(List.of(), traces, materials.provenance(), decisionId);
        }

        var ctx = eligibility.buildContext(req, candidates);
        eligibility.applyJava(ctx, materials, SCENE_ADDON, mode, traces);

        List<AddOnOption> out = new ArrayList<>();
        for (ActivityCandidate c : candidates) {
            if (!c.isEligible()) continue;
            for (GiftResult g : c.getGifts()) {
                // 加价金额必须是正数：0 或负数意味着"白送"或"倒贴"，那不是加价购。
                // 与其猜运营想干什么，不如把这条选项排除掉——fail-closed。
                if (g.getAbsoluteAmount() == null || g.getAbsoluteAmount().signum() <= 0) continue;
                out.add(new AddOnOption(c.getActivityId(), c.getActivityName(), c.getVersion(),
                        g.getGiftName(), g.getAbsoluteAmount()));
            }
        }
        traces.add("加价购选项 " + out.size() + " 个");
        return new AddOnOptions(out, traces, materials.provenance(), decisionId);
    }

    /**
     * 第二阶段：用户选定后的权威报价。
     *
     * <p><b>客户端传来的价格一律不读</b>——只接受「哪个活动的哪个换购品」，
     * 价格重新从配置查。这是防改价的根本手段：不信任的输入不参与计算。
     *
     * <p>选项在两阶段之间可能失效（活动下线、配置改了、换购品被删），
     * 此时返回 {@code ok=false} 而不是沿用第一阶段的价格——那等于按已经作废的配置卖货。
     */
    public AddOnQuote quote(SpuDiscountRequest req, String activityId, String itemName, DecisionMode mode) {
        AddOnQuote q = metrics.timeDecision(SCENE_ADDON,
                () -> quoteInternal(req, activityId, itemName, mode), r -> PHASE_QUOTE);
        // 命中计数打在**唯一出口**上，且只打 quote：options 只是列清单，没有替用户选定任何东西，
        // 把它也算成「命中」会让加价购的命中量恒等于曝光量，指标随即失去意义。
        if (q.ok()) {
            metrics.hit(SCENE_ADDON, q.activityId());
        }
        auditor.addOnQuote(SCENE_ADDON, req, q);
        return q;
    }

    private AddOnQuote quoteInternal(SpuDiscountRequest req, String activityId, String itemName,
                                     DecisionMode mode) {
        // 两阶段共用**同一个** decisionId：一次 quote 就是一次决策，内部那次重新装载是它的一部分，
        // 不是另一次决策。分成两个 id 会让「按 decisionId 检索」在这条通道上查出半截。
        String decisionId = newDecisionId();
        if (activityId == null || activityId.isBlank() || itemName == null || itemName.isBlank()) {
            // provenance 显式为 null：这条路径**根本没装载过物料**。
            // 填 db() 会声称查过库，而「没查过」与「查了库」是两件不同的事。
            return new AddOnQuote(false, activityId, itemName, null,
                    "缺 activityId 或换购品", List.of("加价购报价拒绝：缺 activityId 或换购品"), null, decisionId);
        }
        // 第二阶段必须重新装载并重跑资格：不能沿用第一阶段的候选或价格。
        AddOnOptions fresh = optionsInternal(req, mode, decisionId);
        for (AddOnOption o : fresh.options()) {
            if (activityId.equals(o.activityId()) && itemName.equals(o.itemName())) {
                List<String> traces = new ArrayList<>(fresh.traces());
                traces.add("加价购权威报价：" + o.activityId() + "/" + o.itemName());
                return new AddOnQuote(true, o.activityId(), o.itemName(), o.addOnPrice(), null, traces,
                        fresh.provenance(), decisionId);
            }
        }
        // 走到这里说明第一阶段给过的选项现在拿不到了。**不能回退到客户端给的价**。
        List<String> traces = new ArrayList<>(fresh.traces());
        traces.add("加价购报价拒绝：选项已失效或资格不满足");
        return new AddOnQuote(false, activityId, itemName, null,
                "选项已失效或不适用于当前订单", traces, fresh.provenance(), decisionId);
    }

    /** 每次决策一个 id：不落库，只是让「响应 / 审计日志 / 下游账」三者能对上同一次决策。 */
    private static String newDecisionId() {
        return java.util.UUID.randomUUID().toString();
    }
}
