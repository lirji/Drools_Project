package com.lrj.drools.activity.engine;

import com.lrj.drools.activity.metrics.DecisionMetrics;
import com.lrj.drools.activity.persistence.ActivityArtifactEntity;
import com.lrj.drools.activity.persistence.ActivityArtifactRepository;
import com.lrj.drools.activity.persistence.ActivityGenerationEntity;
import com.lrj.drools.activity.persistence.ActivityGenerationRepository;
import com.lrj.drools.activity.snapshot.DecisionSnapshot;
import com.lrj.drools.activity.snapshot.DecisionSnapshotBuilder;
import com.lrj.drools.activity.snapshot.DecisionSnapshotStore;
import com.lrj.drools.activity.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;

/**
 * M1.4 · decision 侧发布代际轮询预热。扫 {@link ActivityGenerationEntity}（跨租户、非 @TenantId）比对内存 lastSeen，
 * 对代际增长的 {@code (tenant, bizLine)} 把其全部 ACTIVE artifact 的 DRL 在编译池异步预热——
 * 使物理拆分后 decision 进程无需 console 进程内直调也能在发布后自动 warm（进程内 {@code warmOnPublish→warmAsync} 保留作双保险）。
 *
 * <p><b>无上下文安全</b>：本服务运行在后台调度线程（无 {@link TenantContext}）。generation 表非 @TenantId 故 {@code findAll()}
 * 可见所有租户；读某租户的 ACTIVE artifact（@TenantId 实体）时用 {@code TenantContext.callWith(tenant, …)} 显式套上下文，
 * 让 Hibernate 判别式过滤按该租户生效，不串租户。
 *
 * <p><b>冷启动</b>：lastSeen 空 → 首轮把所有当前已发布 (tenant,bizLine) 的 ACTIVE artifact 全部预热（decision 进程重启后自愈到 warm）。
 * <p><b>幂等</b>：warmAsync 经 Caffeine 内容级 single-flight，重复预热同一 DRL 不重复编译；无新代际的轮询是纯空扫。
 */
@Service
public class GenerationWarmService {

    private static final Logger log = LoggerFactory.getLogger(GenerationWarmService.class);

    private final ActivityGenerationRepository genRepo;
    private final ActivityArtifactRepository artifactRepo;
    private final ActivityRuleRuntimeService ruleRuntime;
    private final DecisionSnapshotBuilder snapshotBuilder;
    private final DecisionSnapshotStore snapshotStore;

    /**
     * 快照兜底重建阈值（毫秒）。超过这个年龄的快照即使代际没动也强制重建。
     *
     * <p><b>它守的是「信号漏发」这一整类故障</b>，而不是某一个已知 bug：代际 bump 因为异常没提交、
     * 轮询线程被拖死后恢复、构建期抛异常导致 lastSeen 没更新……这些都表现为快照静默过期，
     * 而决策照常成功。有了兜底，后果从<b>永久</b>降为<b>一轮</b>。
     * 设为 0 或负数关闭（测试里需要精确控制重建时机时用）。
     */
    @Value("${activity.marketing.snapshot.max-age-ms:60000}")
    private long snapshotMaxAgeMs;

    /** key = tenant|bizLine → 已预热到的 generation。generation 从 1 起，故基线 0L 让首见即预热。 */
    private final Map<String, Long> lastSeen = new ConcurrentHashMap<>();

    public GenerationWarmService(ActivityGenerationRepository genRepo,
                                 ActivityArtifactRepository artifactRepo,
                                 ActivityRuleRuntimeService ruleRuntime,
                                 DecisionSnapshotBuilder snapshotBuilder,
                                 DecisionSnapshotStore snapshotStore,
                                 DecisionMetrics metrics) {
        this.genRepo = genRepo;
        this.artifactRepo = artifactRepo;
        this.ruleRuntime = ruleRuntime;
        this.snapshotBuilder = snapshotBuilder;
        this.snapshotStore = snapshotStore;
        // 快照新鲜度只有 decision 侧有意义（console 无构建器、store 恒空），所以绑在这里而不是 store 自己。
        metrics.bindSnapshotStore(snapshotStore);
    }

    /**
     * 扫全部代际，对增长者提交预热；随后做一轮**陈旧快照兜底重建**。
     *
     * <p>返回本轮提交的预热 {@link Future} 列表（调度线程 fire-and-forget 忽略；
     * 测试可 await 以做确定性断言）。异常隔离到单个 (tenant,bizLine)，不让一个租户的问题拖垮整轮。
     */
    public List<Future<?>> warmDueGenerations() {
        List<Future<?>> futures = new ArrayList<>();
        for (ActivityGenerationEntity gen : genRepo.findAll()) {
            String key = gen.getTenantId() + "|" + gen.getBizLine();
            long seen = lastSeen.getOrDefault(key, 0L);
            if (gen.getGeneration() <= seen) {
                continue;
            }
            try {
                int warmed = warmTenantBizLine(gen.getTenantId(), gen.getBizLine(), gen.getGeneration(), futures);
                lastSeen.put(key, gen.getGeneration());
                log.info("[generation-poll] 预热命中 tenant={} bizLine={} generation {}→{} 提交 {} 个 artifact 预热",
                        gen.getTenantId(), gen.getBizLine(), seen, gen.getGeneration(), warmed);
            } catch (RuntimeException e) {
                // 不更新 lastSeen → 下一轮重试；不影响其它租户。
                log.warn("[generation-poll] 预热 tenant={} bizLine={} 失败（下轮重试）: {}",
                        gen.getTenantId(), gen.getBizLine(), e.toString());
            }
        }
        rebuildStaleSnapshots();
        return futures;
    }

    /**
     * 兜底：把超过 {@link #snapshotMaxAgeMs} 没重建过的快照按数据库真相重建一遍，代际不变。
     *
     * <p><b>为什么值得单独做一轮扫描</b>：代际信号是「有人告诉我配置变了」，而这一轮问的是
     * 「我手上这份物料是不是已经旧到不可信了」。前者依赖每一个写入口都记得发信号——那是一条
     * 需要人持续维护的纪律，本仓库已经在它上面失手过一次（下线不 bump）。后者不依赖任何人记得什么。
     *
     * <p>走 {@link DecisionSnapshotStore#refresh} 而不是 {@code publish}：这不是一次发布，
     * 不能占用回滚槽位（否则 rollback 会退到几十秒前的自己，等于没回滚）。
     */
    void rebuildStaleSnapshots() {
        if (snapshotMaxAgeMs <= 0) return;
        Instant now = Instant.now();
        for (DecisionSnapshot stale : snapshotStore.all()) {
            long ageMs = Duration.between(stale.builtAt(), now).toMillis();
            if (ageMs < snapshotMaxAgeMs) continue;
            String tenant = stale.tenant();
            String bizLine = stale.bizLine();
            try {
                DecisionSnapshot fresh = TenantContext.callWith(tenant,
                        () -> snapshotBuilder.build(tenant, bizLine, stale.generation()));
                snapshotStore.refresh(fresh);
                log.info("[generation-poll] 快照兜底重建 tenant={} bizLine={} 年龄={}ms（阈值 {}ms）",
                        tenant, bizLine, ageMs, snapshotMaxAgeMs);
            } catch (RuntimeException e) {
                // 重建失败保留旧快照（有旧的总比没有强），下一轮继续试；age 指标会持续上涨并触发告警。
                log.warn("[generation-poll] 快照兜底重建失败 tenant={} bizLine={}（保留旧快照，下轮重试）: {}",
                        tenant, bizLine, e.toString());
            }
        }
    }

    /**
     * 该 {@code (tenant, bizLine)} 代际推进后的三步，<b>切指针在最后</b>：
     * <ol>
     *   <li><b>构建</b>决策快照（P1-1）——把整条业务线的决策物料在<b>本后台线程</b>捞齐、
     *       组织成不可变快照。此后该租户的决策请求零数据库查询。</li>
     *   <li>预热 ACTIVE artifact 的资格 DRL（M1.4 既有行为，保留）。</li>
     *   <li><b>发布</b>：前两步都成功了才 {@code publish} 切指针。</li>
     * </ol>
     * <p><b>为什么 publish 必须排在最后</b>（R16）：此前是「先 publish、再查 ACTIVE artifact、再预热」，
     * 中间任何一步抛异常都会被 {@code warmDueGenerations} 吞掉并<b>不更新 lastSeen</b>——于是一次
     * 半完成的推进已经被记成一次发布（占了回滚槽位），下一轮还会对同一代际再来一遍。
     * 把 publish 挪到最后，这三步共享同一个失败边界：要么整体推进，要么整体留在上一代等下轮重试。
     * 「先建好快照再切指针」这条更早的约束当然仍成立——请求线程永远读到自洽的物料。
     *
     * @param generation 数据库里那一行的**真实代际号**。此前这里传的是 {@code lastSeen+1}，
     *                   两次发布挤在一次轮询间隔内时（大促前批量上线很常见）快照会记下一个
     *                   比实际小的代际号，让 rollback 与代际指标都对不上账。
     */
    private int warmTenantBizLine(String tenant, String bizLine, long generation, List<Future<?>> sink) {
        // ① 构建快照（还没切指针；构建期的异常不能让指针指向半成品）
        DecisionSnapshot snap = TenantContext.callWith(tenant,
                () -> snapshotBuilder.build(tenant, bizLine, generation));

        // ② 预热 ACTIVE artifact 的资格 DRL
        List<ActivityArtifactEntity> active = TenantContext.callWith(tenant,
                () -> artifactRepo.findByBizLineAndStatus(bizLine, ActivityArtifactEntity.ACTIVE));
        int warmed = 0;
        for (ActivityArtifactEntity a : active) {
            if (a.getEligDrl() != null && !a.getEligDrl().isBlank()) {
                sink.add(ruleRuntime.warmAsync(tenant, a.getEligDrl()));
                warmed++;
            }
        }

        // ③ 全部前置步骤成功 → 原子切指针（此处之后本轮才算一次真正的发布）
        snapshotStore.publish(snap);
        return warmed;
    }
}
