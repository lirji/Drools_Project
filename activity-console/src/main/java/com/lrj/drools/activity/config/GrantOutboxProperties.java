package com.lrj.drools.activity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 发放传播 outbox 配置（{@code activity.grant-outbox.*}）。
 *
 * <p><b>门控默认关</b>（{@link #enabled} = false）：既有部署对本特性零感知——{@code confirmGrant}/
 * {@code releaseGrant} 不写 outbox、中继不调度，故对既有测试与线上行为零影响。开启后才写事件并中继推送。
 *
 * <p>{@link #webhookUrl} 为空 → dispatcher 退化为 {@code LoggingGrantEventDispatcher}（只记日志不真发）；
 * 非空 → 装配 {@code @Primary WebhookGrantEventDispatcher} 覆盖，POST 事件到该 URL。
 */
@ConfigurationProperties(prefix = "activity.grant-outbox")
public class GrantOutboxProperties {

    /** 总开关（默认关）：同时门控「写点入队」与「中继调度」。 */
    private boolean enabled = false;

    /**
     * 中继调度模式：{@code local}（Spring @Scheduled，默认）/ {@code xxl}（XXL-JOB 触发）/ 其它值=不自动调度
     * （仅手动/测试触发）。与 {@code activity.marketing.lifecycle-schedule.mode} 同款双模式。
     */
    private String relayMode = "local";

    /** local 模式中继扫描间隔（毫秒）。 */
    private long relayIntervalMs = 5000L;

    /** local 模式中继首次延迟（毫秒）。 */
    private long relayInitialDelayMs = 5000L;

    /** 单轮单租户拉取的可投递条目上限。 */
    private int pollBatchSize = 200;

    /** 最大投递尝试次数：FAILED 且 attempt < maxAttempt 才补投，达到后进死信（DEAD），须 redrive 复活（KI-9）。 */
    private int maxAttempt = 5;

    /** 失败重试指数退避基值（毫秒）：{@code nextAttemptAt = now + min(base * 2^attempt, retryBackoffMaxMs)}。默认 30s。 */
    private long retryBackoffBaseMs = 30_000L;

    /** 失败重试退避上限（毫秒，封顶），避免退避无限增长。默认 10min。 */
    private long retryBackoffMaxMs = 600_000L;

    /** 下游账务/渠道 webhook 地址；空 → logging 退化。 */
    private String webhookUrl = "";

    /** 可选鉴权/签名头名（与 headerValue 成对生效）。 */
    private String webhookHeaderName = "";

    /** 可选鉴权/签名头值。 */
    private String webhookHeaderValue = "";

    /** webhook 连接/读取超时（毫秒）。 */
    private int webhookTimeoutMs = 3000;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getRelayMode() { return relayMode; }
    public void setRelayMode(String relayMode) { this.relayMode = relayMode; }

    public long getRelayIntervalMs() { return relayIntervalMs; }
    public void setRelayIntervalMs(long relayIntervalMs) { this.relayIntervalMs = relayIntervalMs; }

    public long getRelayInitialDelayMs() { return relayInitialDelayMs; }
    public void setRelayInitialDelayMs(long relayInitialDelayMs) { this.relayInitialDelayMs = relayInitialDelayMs; }

    public int getPollBatchSize() { return pollBatchSize; }
    public void setPollBatchSize(int pollBatchSize) { this.pollBatchSize = pollBatchSize; }

    public int getMaxAttempt() { return maxAttempt; }
    public void setMaxAttempt(int maxAttempt) { this.maxAttempt = maxAttempt; }

    public long getRetryBackoffBaseMs() { return retryBackoffBaseMs; }
    public void setRetryBackoffBaseMs(long retryBackoffBaseMs) { this.retryBackoffBaseMs = retryBackoffBaseMs; }

    public long getRetryBackoffMaxMs() { return retryBackoffMaxMs; }
    public void setRetryBackoffMaxMs(long retryBackoffMaxMs) { this.retryBackoffMaxMs = retryBackoffMaxMs; }

    public String getWebhookUrl() { return webhookUrl; }
    public void setWebhookUrl(String webhookUrl) { this.webhookUrl = webhookUrl; }

    public String getWebhookHeaderName() { return webhookHeaderName; }
    public void setWebhookHeaderName(String webhookHeaderName) { this.webhookHeaderName = webhookHeaderName; }

    public String getWebhookHeaderValue() { return webhookHeaderValue; }
    public void setWebhookHeaderValue(String webhookHeaderValue) { this.webhookHeaderValue = webhookHeaderValue; }

    public int getWebhookTimeoutMs() { return webhookTimeoutMs; }
    public void setWebhookTimeoutMs(int webhookTimeoutMs) { this.webhookTimeoutMs = webhookTimeoutMs; }
}
