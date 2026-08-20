package com.lrj.drools.activity.service;

import com.lrj.drools.activity.config.GrantOutboxProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * console 写平面的发放传播中继触发器（local 模式，Spring {@code @Scheduled}）。
 *
 * <p><b>仅在门控开启时装配</b>（{@code activity.grant-outbox.enabled=true}）——默认关时本 bean 不创建、
 * 无任何定时任务，故对既有测试与线上零影响。{@code relay-mode != local}（如 xxl）时 tick 直接跳过，
 * 把中继让给 {@code GrantOutboxRelayXxlJobHandler}，避免双触发。
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "activity.grant-outbox.enabled", havingValue = "true")
public class GrantOutboxRelayScheduler {

    private static final Logger log = LoggerFactory.getLogger(GrantOutboxRelayScheduler.class);

    private final GrantOutboxRelay relay;
    private final GrantOutboxProperties props;

    public GrantOutboxRelayScheduler(GrantOutboxRelay relay, GrantOutboxProperties props) {
        this.relay = relay;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${activity.grant-outbox.relay-interval-ms:5000}",
            initialDelayString = "${activity.grant-outbox.relay-initial-delay-ms:5000}")
    public void tick() {
        if (!"local".equals(props.getRelayMode())) {
            return; // xxl / 其它模式：不由本地调度驱动。
        }
        try {
            relay.relayOnce();
        } catch (RuntimeException exception) {
            // 一轮全局中继失败不能杀死后续调度；逐租户/逐条失败已在 relay 内隔离并记录。
            log.error("[grant-outbox] 本轮发放事件中继失败", exception);
        }
    }
}
