package com.lrj.drools.activity.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Instant;

/** console 写平面的活动定时上线/下线触发器。 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "activity.marketing.lifecycle-schedule.mode",
        havingValue = "local", matchIfMissing = true)
public class ActivityLifecycleScheduler {

    private static final Logger log = LoggerFactory.getLogger(ActivityLifecycleScheduler.class);

    private final ActivityLifecycleScheduleService schedules;

    public ActivityLifecycleScheduler(ActivityLifecycleScheduleService schedules) {
        this.schedules = schedules;
    }

    @Scheduled(fixedDelayString = "${activity.marketing.lifecycle-schedule.interval-ms:5000}",
            initialDelayString = "${activity.marketing.lifecycle-schedule.initial-delay-ms:5000}")
    public void tick() {
        try {
            schedules.runDueTransitions(Instant.now());
        } catch (RuntimeException exception) {
            // 一轮全局扫描失败不能杀死后续调度；逐活动失败已在 service 内隔离并记录。
            log.error("[lifecycle] 本轮活动定时上下线扫描失败", exception);
        }
    }
}
