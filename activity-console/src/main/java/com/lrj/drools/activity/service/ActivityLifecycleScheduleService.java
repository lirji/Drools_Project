package com.lrj.drools.activity.service;

import com.lrj.drools.activity.persistence.ActivityManageRepository;
import com.lrj.drools.activity.tenant.TenantContext;
import com.lrj.drools.activity.tenant.TenantIds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/** 跨租户扫描到期活动，再把每个活动交给独立事务服务处理。 */
@Service
public class ActivityLifecycleScheduleService {

    private static final Logger log = LoggerFactory.getLogger(ActivityLifecycleScheduleService.class);

    private final ActivityManageRepository manageRepo;
    private final ActivityLifecycleTenantScanner tenantScanner;
    private final ActivityLifecycleTransitionService transitions;
    private final ConcurrentMap<String, String> resumeAfterByTenant = new ConcurrentHashMap<>();

    @Value("${activity.marketing.lifecycle-schedule.batch-size:200}")
    private int batchSize;

    public ActivityLifecycleScheduleService(ActivityManageRepository manageRepo,
                                            ActivityLifecycleTenantScanner tenantScanner,
                                            ActivityLifecycleTransitionService transitions) {
        this.manageRepo = manageRepo;
        this.tenantScanner = tenantScanner;
        this.transitions = transitions;
    }

    /**
     * 执行一轮定时上下线。参数化 {@code now} 让边界测试可重复；生产调度器传 UTC {@link Instant#now()}。
     */
    public RunResult runDueTransitions(Instant now) {
        if (now == null) {
            throw new IllegalArgumentException("生命周期调度时间不能为空");
        }
        List<String> tenants = tenantScanner.findDueTenantIds(now);
        // 已无到期活动的租户不保留游标，避免长期运行时按历史租户数增长。
        resumeAfterByTenant.keySet().retainAll(new HashSet<>(tenants));
        AtomicInteger scannedActivities = new AtomicInteger();
        AtomicInteger activated = new AtomicInteger();
        AtomicInteger offlined = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        int limit = Math.max(1, batchSize);

        for (String tenant : tenants) {
            if (!TenantIds.isValidExternal(tenant)) {
                failures.incrementAndGet();
                log.error("[lifecycle] 跳过非法或保留租户 id：{}", tenant);
                continue;
            }
            TenantContext.runWith(tenant, () -> {
                List<String> activityIds;
                try {
                    String resumeAfter = resumeAfterByTenant.get(tenant);
                    activityIds = manageRepo.findDueLifecycleActivityIds(
                            now, resumeAfter, PageRequest.of(0, limit));
                    if (activityIds.isEmpty() && resumeAfter != null) {
                        // 已扫到 id 空间末尾：回卷后再取一页，使之前失败的活动仍会被重试。
                        resumeAfterByTenant.remove(tenant);
                        activityIds = manageRepo.findDueLifecycleActivityIds(
                                now, null, PageRequest.of(0, limit));
                    }
                    if (!activityIds.isEmpty()) {
                        resumeAfterByTenant.put(tenant, activityIds.getLast());
                    }
                } catch (RuntimeException exception) {
                    failures.incrementAndGet();
                    log.error("[lifecycle] 扫描租户 {} 的到期活动失败", tenant, exception);
                    return;
                }
                for (String activityId : activityIds) {
                    scannedActivities.incrementAndGet();
                    try {
                        ActivityLifecycleTransitionService.TransitionResult result =
                                transitions.transitionDue(activityId, now);
                        activated.addAndGet(result.activated());
                        offlined.addAndGet(result.offlined());
                    } catch (RuntimeException exception) {
                        failures.incrementAndGet();
                        log.error("[lifecycle] 定时流转失败 tenant={} activityId={}",
                                tenant, activityId, exception);
                    }
                }
            });
        }
        RunResult result = new RunResult(tenants.size(), scannedActivities.get(), activated.get(),
                offlined.get(), failures.get());
        if (result.activated() > 0 || result.offlined() > 0 || result.failures() > 0) {
            log.info("[lifecycle] 本轮完成 tenants={} activities={} activated={} offlined={} failures={}",
                    result.scannedTenants(), result.scannedActivities(), result.activated(),
                    result.offlined(), result.failures());
        }
        return result;
    }

    public record RunResult(int scannedTenants, int scannedActivities, int activated,
                            int offlined, int failures) {}
}
