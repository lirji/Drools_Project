package com.lrj.drools.activity.service;

import com.lrj.drools.activity.config.GrantOutboxProperties;
import com.lrj.drools.activity.spi.GrantEvent;
import com.lrj.drools.activity.spi.GrantEventDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * 生产级 {@link GrantEventDispatcher} · 通用 Webhook 通道（仿 recon {@code WebhookAlertDispatcher}）。
 * 仅当 {@code activity.grant-outbox.webhook-url} 非空时经 {@code GrantOutboxConfig} 注册为 {@code @Primary}，
 * 覆盖 {@link LoggingGrantEventDispatcher}。
 *
 * <p>向配置 URL POST 事件 {@code payload}（JSON），并带 {@code X-Idempotency-Key = grantNo:eventType} 头供下游
 * 对 at-least-once 重复投递去重；可选再带一个鉴权/签名头。2xx 视为成功（中继置 SENT）；非 2xx / 连接超时 /
 * 异常一律失败（返回 {@code false}，中继置 FAILED + attempt，由后续补投重来）。
 *
 * <p>外部 I/O 由 {@code GrantOutboxRelay} 在<b>中继短事务之外</b>执行，超时不持锁跨网络。
 */
public class WebhookGrantEventDispatcher implements GrantEventDispatcher {

    private static final Logger log = LoggerFactory.getLogger(WebhookGrantEventDispatcher.class);

    private final RestClient http;
    private final GrantOutboxProperties props;

    public WebhookGrantEventDispatcher(RestClient http, GrantOutboxProperties props) {
        this.http = http;
        this.props = props;
    }

    @Override
    public boolean dispatch(GrantEvent event) {
        try {
            RestClient.RequestBodySpec req = http.post()
                    .uri(props.getWebhookUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Idempotency-Key", event.idempotencyKey());
            if (StringUtils.hasText(props.getWebhookHeaderName())
                    && StringUtils.hasText(props.getWebhookHeaderValue())) {
                req = req.header(props.getWebhookHeaderName(), props.getWebhookHeaderValue());
            }
            // payload 本身即事件全文 JSON，直接透传（下游落库的就是它）。默认状态处理器对 4xx/5xx 抛异常 → 落 catch。
            ResponseEntity<Void> resp = req.body(event.payload()).retrieve().toBodilessEntity();
            boolean ok = resp.getStatusCode().is2xxSuccessful();
            if (!ok) {
                log.warn("[grant-outbox] webhook 非 2xx status={} idem={}", resp.getStatusCode(), event.idempotencyKey());
            }
            return ok;
        } catch (RuntimeException e) {
            log.warn("[grant-outbox] webhook 投递失败 idem={} url={}: {}",
                    event.idempotencyKey(), props.getWebhookUrl(), e.toString());
            return false;
        }
    }
}
