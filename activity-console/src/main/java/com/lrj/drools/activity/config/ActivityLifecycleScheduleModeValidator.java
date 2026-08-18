package com.lrj.drools.activity.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/** 调度模式启动校验，避免配置拼写错误时静默关闭全部生命周期触发器。 */
@Component
public class ActivityLifecycleScheduleModeValidator implements InitializingBean {

    private static final Set<String> SUPPORTED_MODES = Set.of("local", "xxl", "off");

    private final Environment environment;

    public ActivityLifecycleScheduleModeValidator(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        String mode = environment.getProperty(
                "activity.marketing.lifecycle-schedule.mode", "local").trim().toLowerCase(Locale.ROOT);
        if (!SUPPORTED_MODES.contains(mode)) {
            throw new IllegalStateException(
                    "activity.marketing.lifecycle-schedule.mode 只支持 local、xxl、off，当前值：" + mode);
        }
    }
}
