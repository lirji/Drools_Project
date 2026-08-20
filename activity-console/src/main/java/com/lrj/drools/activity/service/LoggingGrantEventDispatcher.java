package com.lrj.drools.activity.service;

import com.lrj.drools.activity.spi.GrantEvent;
import com.lrj.drools.activity.spi.GrantEventDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 默认 {@link GrantEventDispatcher}：<b>只记日志、不真发</b>（dev / 未配置 webhook）。
 *
 * <p>始终注册为 bean；当 {@code activity.grant-outbox.webhook-url} 非空时，
 * {@code GrantOutboxConfig} 会以 {@code @Primary WebhookGrantEventDispatcher} 覆盖它。
 * 「记日志即视为投递成功（返回 true）」——本地/联调时事件会被中继置 SENT，不会无限堆在 PENDING。
 */
@Component
public class LoggingGrantEventDispatcher implements GrantEventDispatcher {

    private static final Logger log = LoggerFactory.getLogger(LoggingGrantEventDispatcher.class);

    @Override
    public boolean dispatch(GrantEvent event) {
        log.info("[grant-outbox] (logging dispatcher) 投递事件 idem={} grantNo={} order={} activity={} "
                        + "type={} amountMinor={} ccy={} attempt={} payload={}",
                event.idempotencyKey(), event.grantNo(), event.orderId(), event.activityId(),
                event.eventType(), event.amountMinor(), event.currency(), event.attempt(), event.payload());
        return true;
    }
}
