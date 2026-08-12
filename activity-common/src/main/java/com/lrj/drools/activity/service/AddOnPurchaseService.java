package com.lrj.drools.activity.service;

import com.lrj.drools.activity.domain.ActivityCandidate;
import com.lrj.drools.activity.domain.ActivityType;
import com.lrj.drools.activity.domain.DecisionProvenance;
import com.lrj.drools.activity.domain.GiftResult;
import com.lrj.drools.activity.domain.SpuDiscountRequest;
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

    private static final String SCENE_ADDON = "addon";

    private final DecisionDataLoader loader;
    private final DecisionEligibilityService eligibility;

    public AddOnPurchaseService(DecisionDataLoader loader, DecisionEligibilityService eligibility) {
        this.loader = loader;
        this.eligibility = eligibility;
    }

    /** 一个可换购选项。{@code addOnPrice} 是<b>加多少钱</b>，不是换购品原价。 */
    public record AddOnOption(String activityId, String activityName, Integer version,
                              String itemName, BigDecimal addOnPrice) {}

    /** 第一阶段结果。{@code options} 为空表示这一单没有可换购的东西。 */
    public record AddOnOptions(List<AddOnOption> options, List<String> traces,
                               DecisionProvenance provenance) {
        /** 两参兼容构造：provenance 缺省为「走库」。 */
        public AddOnOptions(List<AddOnOption> options, List<String> traces) {
            this(options, traces, DecisionProvenance.db());
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
                             DecisionProvenance provenance) {
        /** 六参兼容构造：带 traces 但还没接上 provenance 的调用点落在这里，缺省为「走库」。 */
        public AddOnQuote(boolean ok, String activityId, String itemName,
                          BigDecimal addOnPrice, String reason, List<String> traces) {
            this(ok, activityId, itemName, addOnPrice, reason, traces, DecisionProvenance.db());
        }
    }

    /**
     * 第一阶段：这一单能换购什么。
     *
     * <p>只回答「有哪些选项、各加多少钱」，**不替用户挑**。选项为空是正常结果，
     * 不是错误——调用方据此不展示换购入口即可。
     *
     * <p>{@code explain} 与 discount 链路同一分档约定：console 试算传 true（逐候选资格 trace 外显），
     * 决策热路径传 false（只保留结构性 trace）。此前这里写死 true，资格淘汰明细恒随热路径响应外泄。
     */
    public AddOnOptions options(SpuDiscountRequest req) {
        return options(req, true);
    }

    public AddOnOptions options(SpuDiscountRequest req, boolean explain) {
        List<String> traces = new ArrayList<>();
        DecisionDataLoader.Materials materials =
                loader.load(req.spuIdList(), ActivityType.ADD_ON_PURCHASE, true);
        List<ActivityCandidate> candidates = materials.candidates();
        if (candidates.isEmpty()) {
            traces.add("无生效加价购活动");
            return new AddOnOptions(List.of(), traces, materials.provenance());
        }

        var ctx = eligibility.buildContext(req, candidates);
        eligibility.applyJava(ctx, materials, SCENE_ADDON, explain, traces);

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
        return new AddOnOptions(out, traces, materials.provenance());
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
    public AddOnQuote quote(SpuDiscountRequest req, String activityId, String itemName) {
        return quote(req, activityId, itemName, true);
    }

    public AddOnQuote quote(SpuDiscountRequest req, String activityId, String itemName, boolean explain) {
        if (activityId == null || activityId.isBlank() || itemName == null || itemName.isBlank()) {
            // provenance 显式为 null：这条路径**根本没装载过物料**。
            // 填 db() 会声称查过库，而「没查过」与「查了库」是两件不同的事。
            return new AddOnQuote(false, activityId, itemName, null,
                    "缺 activityId 或换购品", List.of("加价购报价拒绝：缺 activityId 或换购品"), null);
        }
        // 第二阶段必须重新装载并重跑资格：不能沿用第一阶段的候选或价格。
        AddOnOptions fresh = options(req, explain);
        for (AddOnOption o : fresh.options()) {
            if (activityId.equals(o.activityId()) && itemName.equals(o.itemName())) {
                List<String> traces = new ArrayList<>(fresh.traces());
                traces.add("加价购权威报价：" + o.activityId() + "/" + o.itemName());
                return new AddOnQuote(true, o.activityId(), o.itemName(), o.addOnPrice(), null, traces,
                        fresh.provenance());
            }
        }
        // 走到这里说明第一阶段给过的选项现在拿不到了。**不能回退到客户端给的价**。
        List<String> traces = new ArrayList<>(fresh.traces());
        traces.add("加价购报价拒绝：选项已失效或资格不满足");
        return new AddOnQuote(false, activityId, itemName, null,
                "选项已失效或不适用于当前订单", traces, fresh.provenance());
    }
}
