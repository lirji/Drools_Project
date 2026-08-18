package com.lrj.drools.activity.config;

import com.lrj.drools.activity.service.ActivityLifecycleScheduleService;
import com.lrj.drools.activity.service.ActivityLifecycleScheduler;
import com.lrj.drools.activity.service.ActivityLifecycleXxlJobHandler;
import com.xxl.job.core.executor.impl.XxlJobSpringExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class ActivityLifecycleScheduleModeConditionTest {

    private final ApplicationContextRunner triggerContext = new ApplicationContextRunner()
            .withBean(ActivityLifecycleScheduleService.class,
                    () -> mock(ActivityLifecycleScheduleService.class))
            .withUserConfiguration(ActivityLifecycleScheduler.class,
                    ActivityLifecycleXxlJobHandler.class,
                    ActivityLifecycleScheduleModeValidator.class);

    @Test
    void defaultsToLocalScheduler() {
        triggerContext.run(context -> {
            assertThat(context).hasSingleBean(ActivityLifecycleScheduler.class);
            assertThat(context).doesNotHaveBean(ActivityLifecycleXxlJobHandler.class);
        });
    }

    @Test
    void xxlModeCreatesOnlyXxlTrigger() {
        triggerContext
                .withPropertyValues("activity.marketing.lifecycle-schedule.mode=xxl")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(ActivityLifecycleScheduler.class);
                    assertThat(context).hasSingleBean(ActivityLifecycleXxlJobHandler.class);
                });
    }

    @Test
    void offModeCreatesNoTrigger() {
        triggerContext
                .withPropertyValues("activity.marketing.lifecycle-schedule.mode=off")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(ActivityLifecycleScheduler.class);
                    assertThat(context).doesNotHaveBean(ActivityLifecycleXxlJobHandler.class);
                });
    }

    @Test
    void invalidModeFailsFastInsteadOfSilentlyDisablingScheduling() {
        triggerContext
                .withPropertyValues("activity.marketing.lifecycle-schedule.mode=xxl-job")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("activity.marketing.lifecycle-schedule");
                });
    }

    @Test
    void executorConfigurationIsConditionalOnXxlMode() {
        ApplicationContextRunner executorContext = new ApplicationContextRunner()
                .withInitializer(context -> context.addBeanFactoryPostProcessor(beanFactory -> {
                    if (beanFactory.containsBeanDefinition("xxlJobExecutor")) {
                        beanFactory.getBeanDefinition("xxlJobExecutor").setLazyInit(true);
                    }
                }))
                .withUserConfiguration(ActivityLifecycleXxlJobConfiguration.class)
                .withPropertyValues(
                        "xxl.job.admin.addresses=http://scheduler:8080",
                        "xxl.job.executor.appname=activity-console-test",
                        "xxl.job.executor.address=http://console:19999",
                        "xxl.job.executor.ip=10.0.0.2",
                        "xxl.job.executor.port=19999",
                        "xxl.job.access-token=test-token",
                        "xxl.job.executor.log-path=./target/xxl-job-test",
                        "xxl.job.executor.log-retention-days=7");

        executorContext
                .withPropertyValues("activity.marketing.lifecycle-schedule.mode=off")
                .run(context -> assertThat(context).doesNotHaveBean(XxlJobSpringExecutor.class));

        executorContext
                .withPropertyValues("activity.marketing.lifecycle-schedule.mode=xxl")
                .run(context -> {
                    assertThat(context).hasSingleBean(XxlJobExecutorProperties.class);
                    XxlJobExecutorProperties properties = context.getBean(XxlJobExecutorProperties.class);
                    assertThat(properties.getAccessToken()).isEqualTo("test-token");
                    assertThat(properties.getExecutor().getLogRetentionDays()).isEqualTo(7);
                    assertThat(context.getBeanFactory().containsBeanDefinition("xxlJobExecutor")).isTrue();
                    assertThat(context.getBeanFactory().getBeanDefinition("xxlJobExecutor").isLazyInit()).isTrue();
                });
    }

    @Test
    void mapsAllRequiredSettingsToXxlJob342Api() {
        XxlJobExecutorProperties properties = validProperties();

        XxlJobSpringExecutor executor =
                new ActivityLifecycleXxlJobConfiguration().xxlJobExecutor(properties);

        assertThat(ReflectionTestUtils.getField(executor, "adminAddresses"))
                .isEqualTo("http://scheduler:8080");
        assertThat(executor.getAppname()).isEqualTo("activity-console-test");
        assertThat(executor.getAddress()).isEqualTo("http://console:19999");
        assertThat(ReflectionTestUtils.getField(executor, "ip")).isEqualTo("10.0.0.2");
        assertThat(executor.getPort()).isEqualTo(19999);
        assertThat(executor.getAccessToken()).isEqualTo("test-token");
        assertThat(ReflectionTestUtils.getField(executor, "logPath"))
                .isEqualTo("./target/xxl-job-test");
        assertThat(ReflectionTestUtils.getField(executor, "logRetentionDays")).isEqualTo(7);
    }

    @Test
    void rejectsXxlModeWithoutAccessToken() {
        XxlJobExecutorProperties properties = validProperties();
        properties.setAccessToken(" ");

        assertThatThrownBy(() -> new ActivityLifecycleXxlJobConfiguration()
                .xxlJobExecutor(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("xxl.job.access-token");
    }

    private static XxlJobExecutorProperties validProperties() {
        XxlJobExecutorProperties properties = new XxlJobExecutorProperties();
        properties.getAdmin().setAddresses("http://scheduler:8080");
        properties.setAccessToken("test-token");
        properties.getExecutor().setAppname("activity-console-test");
        properties.getExecutor().setAddress("http://console:19999");
        properties.getExecutor().setIp("10.0.0.2");
        properties.getExecutor().setPort(19999);
        properties.getExecutor().setLogPath("./target/xxl-job-test");
        properties.getExecutor().setLogRetentionDays(7);
        return properties;
    }
}
