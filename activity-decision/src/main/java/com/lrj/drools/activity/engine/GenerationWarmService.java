package com.lrj.drools.activity.engine;

import com.lrj.drools.activity.persistence.ActivityArtifactEntity;
import com.lrj.drools.activity.persistence.ActivityArtifactRepository;
import com.lrj.drools.activity.persistence.ActivityGenerationEntity;
import com.lrj.drools.activity.persistence.ActivityGenerationRepository;
import com.lrj.drools.activity.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

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

    /** key = tenant|bizLine → 已预热到的 generation。generation 从 1 起，故基线 0L 让首见即预热。 */
    private final Map<String, Long> lastSeen = new ConcurrentHashMap<>();

    public GenerationWarmService(ActivityGenerationRepository genRepo,
                                 ActivityArtifactRepository artifactRepo,
                                 ActivityRuleRuntimeService ruleRuntime) {
        this.genRepo = genRepo;
        this.artifactRepo = artifactRepo;
        this.ruleRuntime = ruleRuntime;
    }

    /**
     * 扫全部代际，对增长者提交预热。返回本轮提交的预热 {@link Future} 列表（调度线程 fire-and-forget 忽略；
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
                int warmed = warmTenantBizLine(gen.getTenantId(), gen.getBizLine(), futures);
                lastSeen.put(key, gen.getGeneration());
                log.info("[generation-poll] 预热命中 tenant={} bizLine={} generation {}→{} 提交 {} 个 artifact 预热",
                        gen.getTenantId(), gen.getBizLine(), seen, gen.getGeneration(), warmed);
            } catch (RuntimeException e) {
                // 不更新 lastSeen → 下一轮重试；不影响其它租户。
                log.warn("[generation-poll] 预热 tenant={} bizLine={} 失败（下轮重试）: {}",
                        gen.getTenantId(), gen.getBizLine(), e.toString());
            }
        }
        return futures;
    }

    /** 读该租户 bizLine 下全部 ACTIVE artifact，对有 DRL 的提交异步预热，返回提交数。 */
    private int warmTenantBizLine(String tenant, String bizLine, List<Future<?>> sink) {
        List<ActivityArtifactEntity> active = TenantContext.callWith(tenant,
                () -> artifactRepo.findByBizLineAndStatus(bizLine, ActivityArtifactEntity.ACTIVE));
        int warmed = 0;
        for (ActivityArtifactEntity a : active) {
            if (a.getEligDrl() != null && !a.getEligDrl().isBlank()) {
                sink.add(ruleRuntime.warmAsync(tenant, a.getEligDrl()));
                warmed++;
            }
        }
        return warmed;
    }
}
