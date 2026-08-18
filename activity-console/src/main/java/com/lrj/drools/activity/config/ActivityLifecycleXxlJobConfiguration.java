package com.lrj.drools.activity.config;

import com.xxl.job.core.executor.impl.XxlJobSpringExecutor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/** 仅在 XXL 模式下启动 console 写平面的 XXL-JOB 执行器。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "activity.marketing.lifecycle-schedule.mode", havingValue = "xxl")
@EnableConfigurationProperties(XxlJobExecutorProperties.class)
public class ActivityLifecycleXxlJobConfiguration {

    @Bean
    public XxlJobSpringExecutor xxlJobExecutor(XxlJobExecutorProperties properties) {
        validate(properties);
        XxlJobExecutorProperties.Executor executor = properties.getExecutor();
        XxlJobSpringExecutor springExecutor = new XxlJobSpringExecutor();
        springExecutor.setAdminAddresses(properties.getAdmin().getAddresses());
        springExecutor.setAppname(executor.getAppname());
        springExecutor.setAddress(executor.getAddress());
        springExecutor.setIp(executor.getIp());
        springExecutor.setPort(executor.getPort());
        springExecutor.setAccessToken(properties.getAccessToken());
        springExecutor.setLogPath(executor.getLogPath());
        springExecutor.setLogRetentionDays(executor.getLogRetentionDays());
        return springExecutor;
    }

    private static void validate(XxlJobExecutorProperties properties) {
        XxlJobExecutorProperties.Executor executor = properties.getExecutor();
        if (!StringUtils.hasText(properties.getAdmin().getAddresses())) {
            throw new IllegalStateException("xxl.job.admin.addresses 不能为空");
        }
        if (!StringUtils.hasText(executor.getAppname())) {
            throw new IllegalStateException("xxl.job.executor.appname 不能为空");
        }
        if (!StringUtils.hasText(properties.getAccessToken())) {
            throw new IllegalStateException("xxl.job.access-token 不能为空");
        }
        if (executor.getPort() < 1 || executor.getPort() > 65535) {
            throw new IllegalStateException("xxl.job.executor.port 必须在 1..65535 之间");
        }
        if (!StringUtils.hasText(executor.getLogPath())) {
            throw new IllegalStateException("xxl.job.executor.log-path 不能为空");
        }
        if (executor.getLogRetentionDays() < 0) {
            throw new IllegalStateException("xxl.job.executor.log-retention-days 不能小于 0");
        }
    }
}
