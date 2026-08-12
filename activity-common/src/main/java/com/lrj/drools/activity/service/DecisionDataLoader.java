package com.lrj.drools.activity.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.drools.activity.domain.ActivityCandidate;
import com.lrj.drools.activity.domain.ConditionNode;
import com.lrj.drools.activity.domain.ActivityStatus;
import com.lrj.drools.activity.domain.ActivityType;
import com.lrj.drools.activity.domain.GiftResult;
import com.lrj.drools.activity.domain.OfferSpec;
import com.lrj.drools.activity.domain.RuleScene;
import com.lrj.drools.activity.domain.StackStrategy;
import com.lrj.drools.activity.engine.ActivityDrlBuilder.EligibilityRuleDef;
import com.lrj.drools.activity.persistence.ActivityConditionEntity;
import com.lrj.drools.activity.persistence.ActivityConditionReadRepository;
import com.lrj.drools.activity.persistence.ActivityGiftEntity;
import com.lrj.drools.activity.persistence.ActivityGiftReadRepository;
import com.lrj.drools.activity.persistence.ActivityManageEntity;
import com.lrj.drools.activity.persistence.ActivityManageReadRepository;
import com.lrj.drools.activity.persistence.ActivityRuleEntity;
import com.lrj.drools.activity.persistence.ActivityRuleReadRepository;
import com.lrj.drools.activity.persistence.ActivitySpuBindingEntity;
import com.lrj.drools.activity.persistence.ActivitySpuBindingReadRepository;
import com.lrj.drools.activity.persistence.ActivityStrategyReadRepository;
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
 *   <li>查 bizLine 级合并策略（<b>仅红包通道</b>，见 {@link #mergeStrategy}）</li>
 * </ol>
 * 买赠场景第 3 步换成批量查赠品行、且不查第 5 步（不合并权益），故不超过 5 次。
 *
 * <p><b>行为等价性</b>：本类是纯搬移 + 批量化，判定逻辑逐行照搬（上线状态、时间窗、类型匹配、
 * 取最高版本、条件行取第一条）。等价性由 {@code DecisionGoldenSetTest} 的 39 例金标守住。
 *
 * <p><b>注入的是只读仓库</b>（R17）：六个 {@code *ReadRepository} 都继承
 * {@code Repository<T, ID>} 而非 {@code JpaRepository}，于是 {@code save} / {@code delete}
 * <b>在类型上不存在</b>。此前「取数层不写库」只靠 decision 的只读数据库账号在运行期兜住——
 * 读路径上一次手滑的 {@code save(...)} 能编译、能过全部单测（测试库可写），只在生产炸。
 */
@Service
public class DecisionDataLoader {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(DecisionDataLoader.class);

    private static final int NOT_DEL = 0;
    private static final int EFFECTIVE = 1;
    private static final int ENABLED = 1;

    private final ActivitySpuBindingReadRepository bindingRepo;
    private final ActivityManageReadRepository manageRepo;
    private final ActivityRuleReadRepository ruleRepo;
    private final ActivityConditionReadRepository conditionRepo;
    private final ActivityGiftReadRepository giftRepo;
    private final ActivityStrategyReadRepository strategyRepo;
    private final DecisionSnapshotStore snapshots;
    private final DecisionMetrics metrics;

    public DecisionDataLoader(ActivitySpuBindingReadRepository bindingRepo,
                              ActivityManageReadRepository manageRepo,
                              ActivityRuleReadRepository ruleRepo,
                              ActivityConditionReadRepository conditionRepo,
                              ActivityGiftReadRepository giftRepo,
                              ActivityStrategyReadRepository strategyRepo,
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
     * 装齐一次决策的物料。
     *
     * <p><b>优先读代际快照（P1-1）</b>：命中时**零数据库查询**——物料已在发布侧构建、预编译完成。
     * 快照不存在时回落到逐请求查库（固定 5 次，与 N 无关）。
     *
     * <p>这个回落是<b>自调节</b>的，不需要开关：只有 decision 服务带轮询器会构建快照，
     * console 的 legacy 读端点没有构建器、store 恒空，天然走库。
     * 两条路的语义由 {@code SnapshotParityTest} 对拍守住。
     *
     * <p><b>来源判定只有这一处</b>（{@code snaps.isEmpty()}）。此前合并策略在一个独立的
     * {@code resolveStrategy} 里用<b>另一个判据</b>（{@code snapshots.get(tenant, bizLine) != null}）
     * 又判了一次来源：两个判据不同步时会出现「物料走快照、策略却回去查库」的混合结论，
     * 而这种混合在响应里是看不见的——provenance 只声明物料那一半。现在策略随物料一起从
     * 同一个来源出来。
     */
    public Materials load(List<Long> spuIds, ActivityType type, boolean withGifts) {
        List<DecisionSnapshot> snaps = snapshots.forTenant(TenantContext.get());
        if (!snaps.isEmpty()) {
            metrics.decisionSource(type.name(), "snapshot");
            Instant now = Instant.now();
            List<Materials> buckets = new ArrayList<>(snaps.size());
            for (DecisionSnapshot snap : snaps) {
                buckets.add(snap.materialize(spuIds, type, now, withGifts));
            }
            // 代际取最小 / 桶数取份数 / 策略取 bizLine 所属那个桶——三条都收敛在 Materials.merge。
            return Materials.merge(buckets).ordered();
        }
        metrics.decisionSource(type.name(), "db");
        return loadFromDb(spuIds, type, withGifts).ordered();
    }

    private Materials loadFromDb(List<Long> spuIds, ActivityType type, boolean withGifts) {
        // ① 绑定行只查这一次：它同时回答「哪些活动是候选」和「每个活动圈到了哪些 SPU」。
        // 后者就是权益作用域——此前这批行被 .distinct() 成一列 id 后就丢掉了，
        // 于是求值层只剩 orderAmount 一个标量基数可用（商品级活动按整单算钱的根因）。
        List<ActivitySpuBindingEntity> bindings = bindingRows(spuIds);
        List<String> activityIds = bindings.stream()
                .map(ActivitySpuBindingEntity::getActivityId).distinct().collect(Collectors.toList());
        if (activityIds.isEmpty()) {
            return Materials.empty();
        }

        List<ActivityManageEntity> valid = currentEffectiveVersions(activityIds, type);
        if (valid.isEmpty()) {
            return Materials.empty();
        }

        // 作用域先算，因为它同时决定「这个活动还算不算候选」——见下。
        Map<String, java.util.Set<Long>> scope = scopeOf(bindings, valid);

        // **候选身份也必须按版本收窄**（P1-9 的另一半）。绑定查询不带 version、旧版本的绑定行也不软删，
        // 所以「v1 绑 A/B → 编辑成 v2 只绑 A」之后单查 B，这个活动依然会出现在 activityIds 里，
        // 只是作用域为空。而空作用域**拦不住 AMOUNT 形态**——{@code BenefitEvaluator} 的直减/满减分支
        // 不调 baseAmount，直接把 redPackageAmount 发出去。于是走库照发 50 元、走快照根本不是候选
        // （快照侧按 (activityId, version) 取绑定，见 DecisionSnapshotBuilder），两条路发不同的钱。
        //
        // 判据与快照侧对齐：**当前线上版本的绑定 ∩ 本次请求的 SPU 为空 ⇒ 不是候选**。
        // 这里不会误伤「全场券」：走库路径的候选身份本来就只从绑定行推出来，
        // 没有任何绑定的活动压根进不了 activityIds。
        List<ActivityManageEntity> inScope = valid.stream()
                .filter(m -> !scope.getOrDefault(m.getActivityId(), java.util.Set.of()).isEmpty())
                .collect(Collectors.toList());
        if (inScope.isEmpty()) {
            return Materials.empty();
        }

        List<ActivityCandidate> candidates = flatten(inScope, withGifts, scope);
        Eligibility elig = eligibility(inScope);   // 条件行只查一次，defs 与 trees 同源
        Materials materials = new Materials(candidates, elig.defs(), elig.trees());
        return materials.withStrategy(mergeStrategy(type, materials.bizLine()));
    }

    /**
     * 每个活动在这一次请求里圈到的 SPU＝「请求的 SPU」∩「该活动**当前线上版本**的生效绑定」。
     *
     * <p><b>纯内存聚合，零额外查询</b>——数据全部来自第 ① 步已经查回的绑定行。
     * 这一点是硬约束：为了拿「活动的全部绑定」再查一次绑定表，会把固定 5 次查询破掉
     * （{@code DecisionQueryCountTest} 会当场抓住），而作用域要的本来就是交集、不是全集。
     *
     * <p><b>按版本配对</b>：绑定查询没有 version 条件（一个 SPU 可能同时匹配到某活动 v1、v2 的绑定行，
     * 旧版本的绑定行不会被软删）。这里用「已解析出的当前线上版本」做内连接，
     * 于是「v1 绑了 A/B、v2 只绑 A」时 B 不会再落进 v2 的作用域——
     * 否则运营缩小圈选范围的编辑会在走库路径上悄悄失效，而快照路径是对的，两条路发不同的钱。
     */
    private static Map<String, java.util.Set<Long>> scopeOf(List<ActivitySpuBindingEntity> bindings,
                                                            List<ActivityManageEntity> live) {
        Map<String, Integer> versionOf = new LinkedHashMap<>();
        for (ActivityManageEntity m : live) versionOf.put(m.getActivityId(), m.getVersion());

        Map<String, java.util.Set<Long>> scope = new LinkedHashMap<>();
        for (ActivitySpuBindingEntity b : bindings) {
            Integer v = versionOf.get(b.getActivityId());
            if (v == null || !v.equals(b.getVersion())) continue;   // 不是当前线上版本的绑定，不进作用域
            scope.computeIfAbsent(b.getActivityId(), k -> new java.util.LinkedHashSet<>()).add(b.getSpuId());
        }
        return scope;
    }

    /**
     * ⑤ 走库路径的 bizLine 级合并策略。业务线取值规则见 {@link Materials#bizLine()}。
     *
     * <p><b>只有红包通道会发这条查询</b>：合并策略行本身就是按 {@link RuleScene#DISCOUNT} 存的，
     * 而买赠与加价购不合并权益、也从不读 {@code Materials.strategy()}——为它们查一次
     * 等于给两条热路径白加一次数据库往返。
     *
     * <p>候选为空时压根走不到这里（{@link Materials#empty()} 已经带着默认 MAX 返回），
     * 于是「没有活动」的请求不会为了一个没人用的策略再查一次库。
     *
     * <p>快照路径不经过本方法：策略随桶一起来，零查询（见 {@link Materials#merge}）。
     */
    private StackStrategy mergeStrategy(ActivityType type, String bizLine) {
        if (type != ActivityType.RED_PACKAGE) return StackStrategy.MAX;
        return strategyRepo.findFirstByBizLineAndActivityTypeIsNullAndSceneAndIsDel(
                        bizLine, RuleScene.DISCOUNT.code(), NOT_DEL)
                .map(s -> strategyOrMax(s.getStrategy(), bizLine))
                .orElse(StackStrategy.MAX);
    }

    /**
     * 脏策略行 fail-safe 回落 MAX——与快照构建侧 {@code DecisionSnapshotBuilder.resolveStrategy}
     * 同口径。两条路对同一条脏数据必须得出同一个合并策略，否则快照与走库会发不同的钱。
     *
     * <p>「查不到策略行」本来就是 {@code orElse(MAX)}，而脏策略行给决策的可用信息量与查不到完全相同，
     * 却会让整次请求 500——这条不对称没有任何道理。
     */
    private static StackStrategy strategyOrMax(String code, String bizLine) {
        StackStrategy parsed = StackStrategy.tryFromCode(code);
        if (parsed == null) {
            LOG.warn("[decision] bizLine={} 的合并策略读不懂（strategy={}），本次决策按 MAX 合并；请修数据",
                    bizLine, code);
            return StackStrategy.MAX;
        }
        return parsed;
    }

    // ------------------------------------------------------------------ 内部

    /** ① 按 SPU 查生效绑定。返回**绑定行本身**（不再只返回 id）——作用域要用到 spuId 与 version。 */
    private List<ActivitySpuBindingEntity> bindingRows(List<Long> spuIds) {
        if (spuIds == null || spuIds.isEmpty()) return List.of();
        return bindingRepo.findBySpuIdInAndEffectiveAndIsDel(spuIds, EFFECTIVE, NOT_DEL);
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

    /** ③④ 批量取规则行（买赠时另取赠品行），拍平成候选事实。{@code scope} 由 {@link #scopeOf} 内存聚合而来。 */
    private List<ActivityCandidate> flatten(List<ActivityManageEntity> manages, boolean withGifts,
                                            Map<String, java.util.Set<Long>> scope) {
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
                        .add(new GiftResult(g.getActivityId(), g.getVersion(),
                                g.getBatchId(), g.getGiftName(), g.getGiftType(),
                                g.getGiftNum(), g.getAbsoluteAmount(), g.getRightType()));
            }
        }

        List<ActivityCandidate> list = new ArrayList<>();
        for (ActivityManageEntity m : manages) {
            String k = key(m.getActivityId(), m.getVersion());
            // 「行 → 配置」只有 OfferSpec.from 这一个入口，快照构建走的也是它。
            // 此前这里是 17 个手写 setter、快照那边是 18 个位置参数，两份各自演化——
            // scopedSpuIds 与 redPackageMaxDiscount 就是这么各漏过一次的。
            OfferSpec spec = OfferSpec.from(m, ruleByKey.get(k),
                    giftsByKey.getOrDefault(k, List.of()));
            // 作用域：决定这个活动的钱算在哪些商品上。两条装配路径都必须填，
            // 只填一边的表现是「同一张券在走库与走快照两条路上发不同的钱」（SnapshotParityTest 守这条）。
            list.add(new ActivityCandidate(spec,
                    scope.getOrDefault(m.getActivityId(), java.util.Set.of()), withGifts));
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
