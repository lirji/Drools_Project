package com.lrj.drools.activity;

import com.lrj.drools.activity.domain.ActivityCreateRequest;
import com.lrj.drools.activity.domain.ActivityStatus;
import com.lrj.drools.activity.persistence.ActivityGenerationRepository;
import com.lrj.drools.activity.persistence.ActivityManageRepository;
import com.lrj.drools.activity.service.ActivityLifecycleScheduleService;
import com.lrj.drools.activity.service.ActivityMarketingService;
import com.lrj.drools.activity.service.ActivityMarketingService.CreateResult;
import com.lrj.drools.activity.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@SpringBootTest
@ActiveProfiles("h2")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:lifecycle;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "activity.marketing.seed-catalog-data=false",
        "activity.marketing.lifecycle-schedule.mode=off",
        "activity.marketing.lifecycle-schedule.batch-size=50",
        "activity.tenant.dev-default-enabled=false"
})
@DisplayName("活动定时上下线")
class ActivityLifecycleScheduleTest {

    @Autowired ActivityMarketingService marketing;
    @Autowired ActivityLifecycleScheduleService schedules;
    @Autowired ActivityGenerationRepository generations;
    @Autowired ActivityManageRepository activities;

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("显式预约后在开始时刻上线、结束时刻之后下线，重复扫描幂等")
    void scheduledLifecycleHonorsClosedWindowAndIsIdempotent() {
        String tenant = "schedule-acme";
        Instant start = Instant.now().plusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        Instant end = start.plusSeconds(3600);
        CreateResult created = create(tenant, "完整生命周期", "schedule-main", 99101L, start, end);

        CreateResult scheduled = TenantContext.callWith(tenant, () -> marketing.changeStatus(
                created.activityId(), created.version(), ActivityStatus.PENDING_EFFECT.code()));
        assertEquals(ActivityStatus.PENDING_EFFECT.code(), scheduled.status());
        long afterSchedule = generation(tenant, "schedule-main");

        assertEquals(0, schedules.runDueTransitions(start.minusMillis(1)).activated(), "开始前不能上线");
        assertEquals(ActivityStatus.PENDING_EFFECT.code(), status(tenant, created.activityId()));

        ActivityLifecycleScheduleService.RunResult online = schedules.runDueTransitions(start);
        assertEquals(1, online.activated());
        assertEquals(ActivityStatus.ONLINE.code(), status(tenant, created.activityId()));
        long afterOnline = generation(tenant, "schedule-main");
        assertEquals(afterSchedule + 1, afterOnline, "到点上线必须推进发布代际");

        ActivityLifecycleScheduleService.RunResult duplicate = schedules.runDueTransitions(start);
        assertEquals(0, duplicate.activated() + duplicate.offlined(), "重复扫描不得重复流转");
        assertEquals(afterOnline, generation(tenant, "schedule-main"), "幂等扫描不得重复 bump");

        assertEquals(0, schedules.runDueTransitions(end).offlined(), "结束时间是闭区间，恰好等于 end 仍在线");
        assertEquals(ActivityStatus.ONLINE.code(), status(tenant, created.activityId()));

        ActivityLifecycleScheduleService.RunResult offline = schedules.runDueTransitions(end.plusMillis(1));
        assertEquals(1, offline.offlined());
        assertEquals(ActivityStatus.OFFLINE.code(), status(tenant, created.activityId()));
        assertEquals(afterOnline + 1, generation(tenant, "schedule-main"), "自动下线必须推进发布代际");
        assertNull(TenantContext.get(), "跨租户调度结束后必须清理线程上下文");
    }

    @Test
    @DisplayName("取消预约后，即使开始时间已到也不会被后台误发布")
    void cancelledScheduleNeverPublishes() {
        String tenant = "schedule-cancel";
        Instant start = Instant.now().plusSeconds(3600);
        CreateResult created = create(tenant, "取消预约", "schedule-cancel", 99102L,
                start, start.plusSeconds(3600));
        TenantContext.runWith(tenant, () -> {
            marketing.changeStatus(created.activityId(), created.version(), ActivityStatus.PENDING_EFFECT.code());
            marketing.changeStatus(created.activityId(), created.version(), ActivityStatus.NORMAL.code());
        });

        ActivityLifecycleScheduleService.RunResult result = schedules.runDueTransitions(start.plusSeconds(1));
        assertEquals(0, result.activated());
        assertEquals(ActivityStatus.NORMAL.code(), status(tenant, created.activityId()));
    }

    @Test
    @DisplayName("一轮扫描能按租户隔离地处理多个租户")
    void scansAllDueTenantsWithoutLeakingContext() {
        Instant start = Instant.now().plusSeconds(3600);
        CreateResult acme = schedule("schedule-tenant-a", "租户 A", "line-a", 99103L, start);
        CreateResult beta = schedule("schedule-tenant-b", "租户 B", "line-b", 99104L, start);

        ActivityLifecycleScheduleService.RunResult result = schedules.runDueTransitions(start);

        assertEquals(2, result.scannedTenants());
        assertEquals(2, result.activated());
        assertEquals(ActivityStatus.ONLINE.code(), status("schedule-tenant-a", acme.activityId()));
        assertEquals(ActivityStatus.ONLINE.code(), status("schedule-tenant-b", beta.activityId()));
        assertNull(TenantContext.get());
    }

    @Test
    @DisplayName("调度停机错过整个窗口时直接下线，不制造瞬时上线")
    void missedWindowGoesStraightOffline() {
        String tenant = "schedule-missed";
        Instant start = Instant.now().plusSeconds(3600);
        Instant end = start.plusSeconds(30);
        CreateResult created = create(tenant, "错过窗口", "schedule-missed", 99105L, start, end);
        TenantContext.runWith(tenant, () -> marketing.changeStatus(
                created.activityId(), created.version(), ActivityStatus.PENDING_EFFECT.code()));

        ActivityLifecycleScheduleService.RunResult result = schedules.runDueTransitions(end.plusSeconds(1));

        assertEquals(0, result.activated());
        assertEquals(1, result.offlined());
        assertEquals(ActivityStatus.OFFLINE.code(), status(tenant, created.activityId()));
    }

    @Test
    @DisplayName("线上旧版与预约新版并存时，到点原子切换服务版本")
    void scheduledVersionAtomicallyReplacesOnlineVersion() {
        String tenant = "schedule-switch";
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        CreateResult v1 = create(tenant, "切版活动", "switch-line", 99106L,
                now.minusSeconds(3600), now.plusSeconds(7200));
        TenantContext.runWith(tenant, () -> marketing.changeStatus(
                v1.activityId(), v1.version(), ActivityStatus.ONLINE.code()));

        Instant v2Start = now.plusSeconds(1800);
        CreateResult v2 = edit(tenant, v1.activityId(), "切版活动 v2", "switch-line", 99106L,
                v2Start, v2Start.plusSeconds(7200));
        TenantContext.runWith(tenant, () -> marketing.changeStatus(
                v2.activityId(), v2.version(), ActivityStatus.PENDING_EFFECT.code()));

        assertEquals(ActivityStatus.ONLINE.code(), status(tenant, v1.activityId(), 1));
        assertEquals(ActivityStatus.PENDING_EFFECT.code(), status(tenant, v1.activityId(), 2));

        ActivityLifecycleScheduleService.RunResult result = schedules.runDueTransitions(v2Start);

        assertEquals(1, result.activated());
        assertEquals(1, result.offlined());
        assertEquals(ActivityStatus.OFFLINE.code(), status(tenant, v1.activityId(), 1));
        assertEquals(ActivityStatus.ONLINE.code(), status(tenant, v1.activityId(), 2));
    }

    private CreateResult schedule(String tenant, String name, String bizLine, long spu, Instant start) {
        CreateResult created = create(tenant, name, bizLine, spu, start, start.plusSeconds(3600));
        TenantContext.runWith(tenant, () -> marketing.changeStatus(
                created.activityId(), created.version(), ActivityStatus.PENDING_EFFECT.code()));
        return created;
    }

    private CreateResult create(String tenant, String name, String bizLine, long spu,
                                Instant start, Instant end) {
        return save(tenant, null, name, bizLine, spu, start, end);
    }

    private CreateResult edit(String tenant, String activityId, String name, String bizLine, long spu,
                              Instant start, Instant end) {
        return save(tenant, activityId, name, bizLine, spu, start, end);
    }

    private CreateResult save(String tenant, String activityId, String name, String bizLine, long spu,
                              Instant start, Instant end) {
        return TenantContext.callWith(tenant, () -> marketing.create(new ActivityCreateRequest(
                null, activityId, name, bizLine, 1, name,
                start.toEpochMilli(), end.toEpochMilli(), 1, null, 1, 100,
                1, new BigDecimal("10"), "元", null, "MAX",
                null, List.of(new ActivityCreateRequest.SpuBinding(1, spu)), null, null)));
    }

    private int status(String tenant, String activityId) {
        return TenantContext.callWith(tenant,
                () -> marketing.getDetail(activityId).manage().getActivityStatus());
    }

    private int status(String tenant, String activityId, int version) {
        return TenantContext.callWith(tenant, () -> activities
                .findFirstByActivityIdAndVersionAndIsDel(activityId, version, 0)
                .orElseThrow()
                .getActivityStatus());
    }

    private long generation(String tenant, String bizLine) {
        return generations.findByTenantIdAndBizLine(tenant, bizLine)
                .map(row -> row.getGeneration())
                .orElse(0L);
    }
}
