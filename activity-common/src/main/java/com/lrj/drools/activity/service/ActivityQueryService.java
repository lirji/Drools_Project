package com.lrj.drools.activity.service;

import com.lrj.drools.activity.domain.ActivityCandidate;
import com.lrj.drools.activity.domain.ActivityRuleContext;
import com.lrj.drools.activity.domain.ActivityRuleResult;
import com.lrj.drools.activity.domain.ActivityStatus;
import com.lrj.drools.activity.domain.ActivityType;
import com.lrj.drools.activity.domain.GiftResult;
import com.lrj.drools.activity.domain.SpuDiscountRequest;
import com.lrj.drools.activity.domain.StackStrategy;
import com.lrj.drools.activity.engine.ActivityDrlBuilder.LadderActivityDef;
import com.lrj.drools.activity.engine.ActivityDrlBuilder.LadderTier;
import com.lrj.drools.activity.engine.ActivityRuleRuntimeService;
import com.lrj.drools.activity.engine.LadderRangeParser;
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

    private final ActivitySpuBindingRepository bindingRepo;
    private final ActivityManageRepository manageRepo;
    private final ActivityRuleRepository ruleRepo;
    private final ActivityConditionRepository conditionRepo;
    private final ActivityGiftRepository giftRepo;
    private final ActivityStrategyRepository strategyRepo;
    private final ActivityRuleRuntimeService ruleRuntime;

    @Value("${activity.marketing.rule-engine.enabled:true}")
    private boolean ruleEngineEnabled;

    public ActivityQueryService(ActivitySpuBindingRepository bindingRepo,
                                ActivityManageRepository manageRepo,
                                ActivityRuleRepository ruleRepo,
                                ActivityConditionRepository conditionRepo,
                                ActivityGiftRepository giftRepo,
                                ActivityStrategyRepository strategyRepo,
                                ActivityRuleRuntimeService ruleRuntime) {
        this.bindingRepo = bindingRepo;
        this.manageRepo = manageRepo;
        this.ruleRepo = ruleRepo;
        this.conditionRepo = conditionRepo;
        this.giftRepo = giftRepo;
        this.strategyRepo = strategyRepo;
        this.ruleRuntime = ruleRuntime;
    }

    // ------------------------------------------------------------------ SPU 优惠

    public DiscountView spuDiscount(SpuDiscountRequest req) {
        List<String> activityIds = boundActivityIds(req.spuIdList());
        List<ActivityManageEntity> valid = filterBeginActivities(activityIds, ActivityType.RED_PACKAGE);
        List<ActivityCandidate> candidates = flatten(valid, false);

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
            return legacyMax(candidates, "开关关闭，走旧逻辑");
        }

        List<String> traces = new ArrayList<>();

        // 1) 资格淘汰
        var eligDefs = candidates.stream()
                .map(c -> eligibilityDef(c.getActivityId(), findCurrentVersion(valid, c.getActivityId())))
                .filter(java.util.Objects::nonNull)
                .toList();
        ActivityRuleResult elig = ruleRuntime.evalEligibility(ctx, eligDefs);
        if (elig == null) {
            traces.add("资格规则回退：全部候选按生效通过");
        } else {
            traces.addAll(elig.getTraces());
        }

        // 2) 阶梯落档（若有 ladder 配置且给了订单金额）
        List<LadderActivityDef> ladderDefs = ladderDefs(candidates);
        if (!ladderDefs.isEmpty() && ctx.getOrderAmount() != null) {
            ActivityRuleResult ladder = ruleRuntime.evalLadder(ctx, ladderDefs);
            if (ladder != null) traces.addAll(ladder.getTraces());
        }

        // 3) 折扣合并
        StackStrategy strategy = resolveStrategy(candidates);
        ActivityRuleResult disc = ruleRuntime.evalDiscount(ctx, strategy);
        if (disc != null && (disc.getHitActivityId() != null || disc.getHitAmount().signum() > 0)) {
            traces.addAll(disc.getTraces());
            return new DiscountView(true, disc.getHitActivityId(), disc.getHitActivityName(),
                    disc.getHitAmount(), disc.getStrategy().name(), traces, engineMode(true));
        }

        // 引擎空决策 → 回退旧逻辑（仍尊重资格淘汰结果）
        traces.add("折扣规则空决策，回退旧逻辑取最大");
        DiscountView legacy = legacyMax(
                candidates.stream().filter(ActivityCandidate::isEligible).toList(), null);
        return new DiscountView(legacy.hit(), legacy.hitActivityId(), legacy.hitActivityName(),
                legacy.hitAmount(), legacy.strategy(), concat(traces, legacy.traces()), engineMode(true));
    }

    /** 旧逻辑：同批候选取最大红包金额。 */
    private DiscountView legacyMax(List<ActivityCandidate> candidates, String note) {
        ActivityCandidate best = null;
        for (ActivityCandidate c : candidates) {
            BigDecimal amt = c.getRedPackageAmount() == null ? BigDecimal.ZERO : c.getRedPackageAmount();
            if (best == null || amt.compareTo(best.getRedPackageAmount() == null ? BigDecimal.ZERO : best.getRedPackageAmount()) > 0) {
                best = c;
            }
        }
        List<String> traces = new ArrayList<>();
        if (note != null) traces.add(note);
        if (best == null) {
            return new DiscountView(false, null, null, BigDecimal.ZERO, "MAX", traces, engineMode(false));
        }
        BigDecimal amt = best.getRedPackageAmount() == null ? BigDecimal.ZERO : best.getRedPackageAmount();
        traces.add("legacy MAX 命中 " + best.getActivityId() + " amount=" + amt);
        return new DiscountView(true, best.getActivityId(), best.getActivityName(), amt, "MAX", traces, engineMode(false));
    }

    // ------------------------------------------------------------------ 买赠

    public GiftView buyAndGetGifts(SpuDiscountRequest req) {
        List<String> activityIds = boundActivityIds(req.spuIdList());
        List<ActivityManageEntity> valid = filterBeginActivities(activityIds, ActivityType.BUY_AND_GET);
        List<ActivityCandidate> candidates = flatten(valid, true);
        if (candidates.isEmpty()) {
            return new GiftView(List.of(), List.of("无生效买赠活动"), engineMode(false));
        }

        ActivityRuleContext ctx = buildContext(req);
        candidates.forEach(ctx::addCandidate);

        if (ruleEngineEnabled) {
            ActivityRuleResult r = ruleRuntime.evalGift(ctx);
            if (r != null) {
                return new GiftView(new ArrayList<>(r.getGifts()), r.getTraces(), engineMode(true));
            }
        }
        // 回退：直接汇总所有候选奖品
        List<GiftResult> all = candidates.stream().flatMap(c -> c.getGifts().stream()).collect(Collectors.toList());
        return new GiftView(all, List.of("买赠规则回退：汇总全部奖品"), engineMode(false));
    }

    // ------------------------------------------------------------------ 公共 helper

    /** 按 SPU 查生效绑定 → 对应活动 id。 */
    private List<String> boundActivityIds(List<Long> spuIds) {
        if (spuIds == null || spuIds.isEmpty()) return List.of();
        List<ActivitySpuBindingEntity> bindings = bindingRepo
                .findBySpuIdInAndEffectiveAndIsDel(spuIds, EFFECTIVE, NOT_DEL);
        return bindings.stream().map(ActivitySpuBindingEntity::getActivityId).distinct().collect(Collectors.toList());
    }

    /** 旧逻辑 filterBeginActivityIds：已上线 + 当前时间在范围内 + 指定类型，取每个活动当前版本。 */
    private List<ActivityManageEntity> filterBeginActivities(List<String> activityIds, ActivityType type) {
        Instant now = Instant.now();
        List<ActivityManageEntity> result = new ArrayList<>();
        for (String id : activityIds.stream().distinct().toList()) {
            manageRepo.findFirstByActivityIdAndIsDelOrderByVersionDesc(id, NOT_DEL).ifPresent(m -> {
                boolean online = ActivityStatus.ONLINE.code() == m.getActivityStatus();
                boolean inRange = !now.isBefore(m.getActivityStartTime()) && !now.isAfter(m.getActivityEndTime());
                boolean typeMatch = type.code() == m.getActivityType();
                if (online && inRange && typeMatch) result.add(m);
            });
        }
        return result;
    }

    /** 拍平 manage + rule (+ gifts) → 候选 fact。 */
    private List<ActivityCandidate> flatten(List<ActivityManageEntity> manages, boolean withGifts) {
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

            ruleRepo.findByActivityIdAndVersionAndIsDel(m.getActivityId(), m.getVersion(), NOT_DEL)
                    .stream().findFirst().ifPresent(r -> {
                        c.setRedPackageTakeType(r.getRedPackageTakeType());
                        c.setRedPackageAmount(r.getRedPackageAmount());
                        c.setRedPackageAmountUnit(r.getRedPackageAmountUnit());
                        c.setRedPackageRangeAmount(r.getRedPackageRangeAmount());
                    });

            if (withGifts) {
                List<GiftResult> gifts = giftRepo
                        .findByActivityIdAndVersionAndIsDel(m.getActivityId(), m.getVersion(), NOT_DEL)
                        .stream().map(g -> new GiftResult(g.getBatchId(), g.getGiftName(), g.getGiftType(),
                                g.getGiftNum(), g.getAbsoluteAmount(), g.getRightType()))
                        .collect(Collectors.toList());
                c.setGifts(gifts);
            }
            list.add(c);
        }
        return list;
    }

    /** 把请求维度写进 Map fact 的属性袋。putAttr 跳过 null → 缺字段归一成"键不存在"→ fail-closed。 */
    private ActivityRuleContext buildContext(SpuDiscountRequest req) {
        ActivityRuleContext ctx = new ActivityRuleContext();
        ctx.putAttr("userId", req.userId());
        ctx.putAttr("userDistrictId", req.userDistrictId());
        ctx.putAttr("userTags", req.userTags() == null ? null : new ArrayList<>(req.userTags()));
        ctx.putAttr("orderAmount", req.orderAmount());
        ctx.putAttr("quantity", req.quantity());
        if (req.spuIdList() != null && !req.spuIdList().isEmpty()) ctx.putAttr("spuId", req.spuIdList().get(0));
        return ctx;
    }

    private com.lrj.drools.activity.engine.ActivityDrlBuilder.EligibilityRuleDef eligibilityDef(
            String activityId, ActivityManageEntity manage) {
        if (manage == null) return null;
        var conds = conditionRepo.findByActivityIdAndVersionAndSceneAndEnabledAndIsDel(
                activityId, manage.getVersion(),
                com.lrj.drools.activity.domain.RuleScene.ELIGIBILITY.code(), ENABLED, NOT_DEL);
        if (conds.isEmpty() || conds.get(0).getGeneratedDrl() == null || conds.get(0).getGeneratedDrl().isBlank()) {
            return null;
        }
        return new com.lrj.drools.activity.engine.ActivityDrlBuilder.EligibilityRuleDef(
                activityId, conds.get(0).getGeneratedDrl());
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

    private StackStrategy resolveStrategy(List<ActivityCandidate> candidates) {
        String bizLine = candidates.isEmpty() ? null : candidates.get(0).getBizLine();
        return strategyRepo.findFirstByBizLineAndActivityTypeIsNullAndSceneAndIsDel(
                        bizLine, com.lrj.drools.activity.domain.RuleScene.DISCOUNT.code(), NOT_DEL)
                .map(s -> StackStrategy.fromCode(s.getStrategy()))
                .orElse(StackStrategy.MAX);
    }

    private ActivityManageEntity findCurrentVersion(List<ActivityManageEntity> valid, String activityId) {
        return valid.stream().filter(m -> m.getActivityId().equals(activityId)).findFirst().orElse(null);
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
