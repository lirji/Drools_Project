package com.lrj.drools.activity.service;

import com.lrj.drools.activity.persistence.ActivityManageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ActivityLifecycleScheduleCursorTest {

    @Mock ActivityManageRepository manageRepo;
    @Mock ActivityLifecycleTenantScanner tenantScanner;
    @Mock ActivityLifecycleTransitionService transitions;

    private ActivityLifecycleScheduleService schedules;

    @BeforeEach
    void setUp() {
        schedules = new ActivityLifecycleScheduleService(manageRepo, tenantScanner, transitions);
        ReflectionTestUtils.setField(schedules, "batchSize", 2);
    }

    @Test
    void failedFirstPageDoesNotStarveLaterDueActivities() {
        Instant now = Instant.parse("2026-08-18T06:00:00Z");
        when(tenantScanner.findDueTenantIds(now)).thenReturn(List.of("acme"));
        when(manageRepo.findDueLifecycleActivityIds(eq(now), isNull(), any(Pageable.class)))
                .thenReturn(List.of("ACT-001", "ACT-002"));
        when(manageRepo.findDueLifecycleActivityIds(eq(now), eq("ACT-002"), any(Pageable.class)))
                .thenReturn(List.of("ACT-003"));
        when(transitions.transitionDue("ACT-001", now)).thenThrow(new IllegalStateException("broken-1"));
        when(transitions.transitionDue("ACT-002", now)).thenThrow(new IllegalStateException("broken-2"));
        when(transitions.transitionDue("ACT-003", now))
                .thenReturn(new ActivityLifecycleTransitionService.TransitionResult(1, 0));

        ActivityLifecycleScheduleService.RunResult first = schedules.runDueTransitions(now);
        ActivityLifecycleScheduleService.RunResult second = schedules.runDueTransitions(now);

        assertThat(first.failures()).isEqualTo(2);
        assertThat(second.scannedActivities()).isEqualTo(1);
        assertThat(second.activated()).isEqualTo(1);
        verify(transitions).transitionDue("ACT-003", now);
    }
}
