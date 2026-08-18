package com.lrj.drools.activity.service;

import com.xxl.job.core.handler.annotation.XxlJob;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;

/** XXL-JOB 活动生命周期扫描入口；业务一致性仍由 lifecycle service 负责。 */
@Component
@ConditionalOnProperty(name = "activity.marketing.lifecycle-schedule.mode", havingValue = "xxl")
public class ActivityLifecycleXxlJobHandler {

    private final ActivityLifecycleScheduleService schedules;

    public ActivityLifecycleXxlJobHandler(ActivityLifecycleScheduleService schedules) {
        this.schedules = schedules;
    }

    @XxlJob("activityLifecycleSweep")
    public void activityLifecycleSweep() {
        ActivityLifecycleScheduleService.RunResult result = schedules.runDueTransitions(Instant.now());
        if (result.failures() > 0) {
            throw new IllegalStateException("活动生命周期扫描存在失败：" + result.failures());
        }
    }
}
