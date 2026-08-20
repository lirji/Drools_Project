package com.lrj.drools.activity.service;

import com.xxl.job.core.handler.annotation.XxlJob;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * XXL-JOB 发放传播中继入口（xxl 模式）；投递一致性仍由 {@link GrantOutboxRelay} 负责。
 *
 * <p>仅在 {@code activity.grant-outbox.relay-mode=xxl} 时装配（另需 {@code enabled=true} 才真正扫库投递，
 * 由 {@code relayOnce} 内部门控保证）。与 {@code ActivityLifecycleXxlJobHandler} 同款范式。
 */
@Component
@ConditionalOnProperty(name = "activity.grant-outbox.relay-mode", havingValue = "xxl")
public class GrantOutboxRelayXxlJobHandler {

    private final GrantOutboxRelay relay;

    public GrantOutboxRelayXxlJobHandler(GrantOutboxRelay relay) {
        this.relay = relay;
    }

    @XxlJob("grantOutboxRelaySweep")
    public void grantOutboxRelaySweep() {
        relay.relayOnce();
    }
}
