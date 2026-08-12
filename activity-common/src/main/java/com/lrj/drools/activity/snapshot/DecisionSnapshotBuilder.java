package com.lrj.drools.activity.snapshot;

import com.lrj.drools.activity.domain.ActivityStatus;
import com.lrj.drools.activity.domain.ConditionNode;
import com.lrj.drools.activity.domain.GiftResult;
import com.lrj.drools.activity.domain.OfferSpec;
import com.lrj.drools.activity.domain.RuleScene;
import com.lrj.drools.activity.domain.StackStrategy;
import com.lrj.drools.activity.metrics.DecisionMetrics;
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
 *
 * <p><b>查询数是常数（R15）</b>：一次构建固定 6 次查询——活动 / 规则 / 赠品 / 条件 / 绑定 / 合并策略；
 * 真实桶（{@code bizLine != null}）另加一次孤儿 bizLine 计数，共 7 次。与活动目录规模无关。
 * 此前活动查询捞该租户<b>全部</b>在线活动再用 Java 丢掉非本桶的，绑定查询则在
 * {@code for (活动)} 循环体里逐个发——热路径被 {@code DecisionQueryCountTest} 钉死 5 次，
 * 构建期一道门禁都没有，而它每分钟被兜底重建重跑一遍、全打在只读连接上。
 * <b>再往这里加查询前先想清楚它是不是也随 N 增长。</b>
 *
 * <p><b>注入的是只读仓库</b>（R17）：六个 {@code *ReadRepository} 继承 {@code Repository<T, ID>}
 * 而非 {@code JpaRepository}，{@code save} / {@code delete} 在类型上不存在。构建期跑在
 * decision 的只读连接上，这条保证从「只读账号在运行期拒绝」提前到了编译期。
 */
@Service
public class DecisionSnapshotBuilder {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(DecisionSnapshotBuilder.class);

    private static final int NOT_DEL = 0;
    private static final int EFFECTIVE = 1;
    private static final int ENABLED = 1;

    private final ActivityManageReadRepository manageRepo;
    private final ActivitySpuBindingReadRepository bindingRepo;
    private final ActivityRuleReadRepository ruleRepo;
    private final ActivityConditionReadRepository conditionRepo;
    private final ActivityGiftReadRepository giftRepo;
    private final ActivityStrategyReadRepository strategyRepo;
    private final DecisionMetrics metrics;

    public DecisionSnapshotBuilder(ActivityManageReadRepository manageRepo,
                                   ActivitySpuBindingReadRepository bindingRepo,
                                   ActivityRuleReadRepository ruleRepo,
                                   ActivityConditionReadRepository conditionRepo,
                                   ActivityGiftReadRepository giftRepo,
                                   ActivityStrategyReadRepository strategyRepo,
                                   DecisionMetrics metrics) {
        this.manageRepo = manageRepo;
        this.bindingRepo = bindingRepo;
        this.ruleRepo = ruleRepo;
        this.conditionRepo = conditionRepo;
        this.giftRepo = giftRepo;
        this.strategyRepo = strategyRepo;
        this.metrics = metrics;
    }

    /**
     * 构建某 {@code (tenant, bizLine)} 的快照。调用方须已置好 {@code TenantContext}
     * （@TenantId 判别式过滤依赖它）。
     */
    @Transactional(readOnly = true)
    public DecisionSnapshot build(String tenant, String bizLine, long generation) {
        // ① 本条业务线未删除的已上线活动，取每活动最高已上线版本。
        //    bizLine 过滤**下推到 SQL**（此前是捞该租户全量再 Java 丢，桶越多白工越多）。
        //    bizLine == null 是「不过滤、全收」的既有语义，那一档不能走带 bizLine 的派生查询——
        //    它生成 `biz_line = ?`，绑 null 一行都匹配不上，会把整条业务线的快照建成空的。
        warnOrphanBizLine(bizLine);
        List<ActivityManageEntity> onlineRows = bizLine == null
                ? manageRepo.findByActivityStatusAndIsDel(ActivityStatus.ONLINE.code(), NOT_DEL)
                : manageRepo.findByBizLineAndActivityStatusAndIsDel(bizLine, ActivityStatus.ONLINE.code(), NOT_DEL);
        Map<String, ActivityManageEntity> live = new LinkedHashMap<>();
        for (ActivityManageEntity m : onlineRows) {
            // Java 侧再精确比一次 bizLine。**看似冗余，但删不得**：SQL 下推只是省了传输量，
            // 谓词语义并不等价——生产 MySQL 8 的默认排序规则 utf8mb4_0900_ai_ci 是大小写不敏感、
            // 重音不敏感的（5.7 的 general_ci 还额外忽略尾随空格），于是 `biz_line = 'retail'`
            // 会把 bizLine 为 'Retail' / 'RETAIL' 的在线活动一并收进 retail 这个桶。
            // 而桶归属决定的是「谁在快照里 = 谁能被发钱」：这些活动改造前进不了任何桶，
            // 改造后会命中并按其配置发钱——一次没人声明过的语义放宽。
            //
            // 更麻烦的是它**测不出来**：SnapshotParityTest / SnapshotBuildQueryCountTest 都跑在 H2 上，
            // 而 H2 的字符串比较默认大小写敏感，两条谓词在测试里恒等价，只在生产 MySQL 上分叉。
            // 这一行把判据钉回 Java 的精确相等，成本为零，也不影响 R15 想要的查询次数收敛。
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

        // 绑定行也只查这一次。此前它在下面的 for 循环体里逐活动发一次（N+1），
        // 而仓库接口里根本没有批量方法——N+1 是**接口缺口逼出来的**，不是谁写岔了。
        // version 不能下推（每个活动的线上版本各不相同），配对留在内存里做，判据与
        // DecisionDataLoader.scopeOf 一致：只有 version 与当前线上版本相等的绑定行才算数。
        Map<String, List<ActivitySpuBindingEntity>> bindingsByActivity = new LinkedHashMap<>();
        for (ActivitySpuBindingEntity b : bindingRepo.findByActivityIdInAndIsDel(ids, NOT_DEL)) {
            bindingsByActivity.computeIfAbsent(b.getActivityId(), k -> new ArrayList<>()).add(b);
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

            // version == null 时**一行都不收**：原来那条派生查询发的是 `version = null`，
            // SQL 里恒不成立。用 Objects.equals 会把「绑定行 version 也是 null」的行收进来，
            // 那是行为变更（多发钱），不是等价重构。
            Integer liveVersion = m.getVersion();
            for (ActivitySpuBindingEntity b : liveVersion == null ? List.<ActivitySpuBindingEntity>of()
                    : bindingsByActivity.getOrDefault(m.getActivityId(), List.of())) {
                if (!liveVersion.equals(b.getVersion())) continue;
                if (b.getSpuId() == null) continue;
                if (b.getEffective() == null || b.getEffective() != EFFECTIVE) continue;
                bySpu.computeIfAbsent(b.getSpuId(), s -> new LinkedHashSet<>()).add(m.getActivityId());
            }
        }

        return new DecisionSnapshot(tenant, bizLine, generation, Instant.now(),
                bySpu, specs, constraints, trees, resolveStrategy(bizLine));
    }

    /**
     * 数出并吵一声：有多少在线活动因为 bizLine 为空而**进不了任何快照桶**。
     *
     * <p>这条故障在决策侧的表现是 provenance 三个值<b>全绿</b>——走的是快照、代际是别条业务线的
     * 正常数、快照也很新，活动就是不在里面。回退率、耗时、命中数全都不动。在此之前只有诊断端点
     * {@code GET /decision/v1/snapshot?activityId=} 能照出来，而那要求排查的人已经怀疑到某个具体活动头上。
     * <b>这里把它提前到构建期</b>：数据一进库，下一次构建就会吵。
     *
     * <p>只在真实桶（{@code bizLine != null}）上跑：{@code bizLine == null} 是「不过滤、全收」，
     * 这类活动本来就在本次快照里，没什么可警告的。
     *
     * <p>失败不能拖垮构建：这只是一条观测，抛出去会让整条业务线的快照建不出来，
     * 而决策会「自动回落走库」静默继续（同 {@link #resolveStrategy} 那笔账）。
     */
    private void warnOrphanBizLine(String bizLine) {
        if (bizLine == null) return;
        try {
            long orphans = manageRepo.countOrphanBizLine(ActivityStatus.ONLINE.code(), NOT_DEL);
            if (orphans <= 0) return;
            metrics.snapshotOrphans(orphans);
            LOG.warn("[snapshot] 有 {} 个已上线活动的 bizLine 为空，它们进不了任何决策快照桶（本次构建 bizLine={}）；"
                    + "决策侧对它们表现为「快照很新、代际正常、就是不命中」。补 bizLine 后重新发布即可。",
                    orphans, bizLine);
        } catch (RuntimeException e) {
            LOG.warn("[snapshot] 孤儿 bizLine 计数失败（不影响本次构建）: {}", e.toString());
        }
    }

    /**
     * bizLine 级合并策略。<b>脏策略行必须 fail-safe 回落 {@link StackStrategy#MAX}，不能抛。</b>
     *
     * <p>本方法跑在<b>后台构建线程</b>上（decision 侧的代际轮询预热），
     * {@code @RestControllerAdvice} 那套映射在这里一点用都没有——没有请求，也就没有响应可以承载错误。
     * 一条脏策略行若在此抛出，后果是整条业务线的<b>快照建不出来</b>，
     * 而决策会「自动回落走库」继续正常返回：没有 5xx、没有失败请求、页面一切正常，
     * 只有快照年龄与 {@code provenance.source=db} 会悄悄变——这比一个 500 隐蔽得多。
     *
     * <p>回落值与走库路径的 {@code DecisionDataLoader.mergeStrategy} 的 {@code orElse(MAX)} 对齐：
     * 两条路对同一条脏数据必须得出同一个策略，否则快照/走库会发不同的钱。
     */
    private StackStrategy resolveStrategy(String bizLine) {
        return strategyRepo.findFirstByBizLineAndActivityTypeIsNullAndSceneAndIsDel(
                        bizLine, RuleScene.DISCOUNT.code(), NOT_DEL)
                .map(s -> strategyOrMax(s.getStrategy(), bizLine))
                .orElse(StackStrategy.MAX);
    }

    /** 解析不出来就回落 MAX 并打 warn——静默回落等于让脏数据永远待在库里。 */
    private static StackStrategy strategyOrMax(String code, String bizLine) {
        StackStrategy parsed = StackStrategy.tryFromCode(code);
        if (parsed == null) {
            LOG.warn("[snapshot] bizLine={} 的合并策略读不懂（strategy={}），本次快照按 MAX 构建；请修数据",
                    bizLine, code);
            return StackStrategy.MAX;
        }
        return parsed;
    }

    private static String key(String activityId, Integer version) {
        return activityId + "#" + version;
    }
}
