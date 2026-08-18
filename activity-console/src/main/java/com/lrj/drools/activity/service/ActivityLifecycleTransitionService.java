package com.lrj.drools.activity.service;

import com.lrj.drools.activity.domain.ActivityStatus;
import com.lrj.drools.activity.persistence.ActivityManageEntity;
import com.lrj.drools.activity.persistence.ActivityManageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 单个活动的定时生命周期事务：到点激活预约版本、到期下线在线版本。
 *
 * <p>同一活动的全部未删除版本先按版本号顺序加数据库悲观锁。多 console 实例可以重复扫描，
 * 但只有第一个拿到锁的事务会真正改状态；后续事务会基于新状态幂等跳过。
 */
@Service
public class ActivityLifecycleTransitionService {

    private static final Logger log = LoggerFactory.getLogger(ActivityLifecycleTransitionService.class);
    private static final int NOT_DEL = 0;

    private final ActivityManageRepository manageRepo;
    private final ArtifactService artifactService;

    public ActivityLifecycleTransitionService(ActivityManageRepository manageRepo,
                                              ArtifactService artifactService) {
        this.manageRepo = manageRepo;
        this.artifactService = artifactService;
    }

    @Transactional(rollbackFor = Exception.class)
    public TransitionResult transitionDue(String activityId, Instant now) {
        if (activityId == null || activityId.isBlank()) {
            return TransitionResult.NONE;
        }
        List<ActivityManageEntity> rows = manageRepo.lockVersionsForLifecycle(activityId, NOT_DEL);
        if (rows.isEmpty()) {
            return TransitionResult.NONE;
        }

        List<ActivityManageEntity> duePending = rows.stream()
                .filter(row -> ActivityStatus.PENDING_EFFECT.code() == row.getActivityStatus())
                .filter(row -> row.getActivityStartTime() != null && !row.getActivityStartTime().isAfter(now))
                .toList();
        ActivityManageEntity activation = duePending.stream()
                .filter(row -> row.getActivityEndTime() != null && !row.getActivityEndTime().isBefore(now))
                .max(Comparator.comparing(ActivityManageEntity::getVersion))
                .orElse(null);

        Set<ActivityManageEntity> changed = new LinkedHashSet<>();
        int activated = 0;
        int offlined = 0;

        if (activation != null) {
            // 到点发布最新的预约版本；同活动旧线上版和同时到点的低版本预约一并退役。
            for (ActivityManageEntity row : rows) {
                boolean oldOnline = ActivityStatus.ONLINE.code() == row.getActivityStatus();
                boolean supersededPending = row != activation && duePending.contains(row);
                if (oldOnline || supersededPending) {
                    offlined += changeStatus(row, ActivityStatus.OFFLINE, now, changed);
                }
            }
            activated += changeStatus(activation, ActivityStatus.ONLINE, now, changed);
        } else {
            // 调度停机期间整个预约窗口都错过了：直接记为已下线，不制造一次瞬时上线。
            for (ActivityManageEntity row : duePending) {
                offlined += changeStatus(row, ActivityStatus.OFFLINE, now, changed);
            }
            // ONLINE 的结束时间采用闭区间语义；只有 now 严格晚于 end 才下线。
            for (ActivityManageEntity row : rows) {
                if (ActivityStatus.ONLINE.code() == row.getActivityStatus()
                        && row.getActivityEndTime() != null
                        && row.getActivityEndTime().isBefore(now)) {
                    offlined += changeStatus(row, ActivityStatus.OFFLINE, now, changed);
                }
            }
        }

        if (changed.isEmpty()) {
            return TransitionResult.NONE;
        }
        manageRepo.saveAll(changed);
        // 状态与发布代际共享本事务：decision 只会在状态真正提交之后看到重建信号。
        for (ActivityManageEntity row : changed) {
            artifactService.onStatusChanged(row.getActivityId(), row.getVersion(),
                    row.getBizLine(), row.getTenantId());
        }
        log.info("[lifecycle] 活动 {} 定时流转完成：activated={} offlined={} at={}",
                activityId, activated, offlined, now);
        return new TransitionResult(activated, offlined);
    }

    private static int changeStatus(ActivityManageEntity row, ActivityStatus target, Instant now,
                                    Set<ActivityManageEntity> changed) {
        if (target.code() == row.getActivityStatus()) {
            return 0;
        }
        row.setActivityStatus(target.code());
        row.setModifiedStime(now);
        changed.add(row);
        return 1;
    }

    public record TransitionResult(int activated, int offlined) {
        private static final TransitionResult NONE = new TransitionResult(0, 0);
    }
}
