package com.lrj.drools.activity.service;

import com.xxl.job.core.handler.annotation.XxlJob;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ActivityLifecycleXxlJobHandlerTest {

    @Mock
    private ActivityLifecycleScheduleService schedules;

    @Test
    void delegatesToLifecycleServiceWithCurrentTime() {
        ActivityLifecycleXxlJobHandler handler = new ActivityLifecycleXxlJobHandler(schedules);
        when(schedules.runDueTransitions(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new ActivityLifecycleScheduleService.RunResult(2, 3, 1, 2, 0));
        Instant before = Instant.now();

        assertDoesNotThrow(handler::activityLifecycleSweep);

        Instant after = Instant.now();
        ArgumentCaptor<Instant> now = ArgumentCaptor.forClass(Instant.class);
        verify(schedules).runDueTransitions(now.capture());
        assertTrue(!now.getValue().isBefore(before) && !now.getValue().isAfter(after));
    }

    @Test
    void propagatesPartialFailuresToXxlJob() {
        ActivityLifecycleXxlJobHandler handler = new ActivityLifecycleXxlJobHandler(schedules);
        when(schedules.runDueTransitions(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new ActivityLifecycleScheduleService.RunResult(2, 3, 1, 1, 1));

        IllegalStateException exception = assertThrows(
                IllegalStateException.class, handler::activityLifecycleSweep);

        assertTrue(exception.getMessage().contains("1"));
    }

    @Test
    void exposesExpectedBeanHandlerName() throws NoSuchMethodException {
        Method method = ActivityLifecycleXxlJobHandler.class.getMethod("activityLifecycleSweep");

        assertEquals("activityLifecycleSweep", method.getAnnotation(XxlJob.class).value());
    }
}
