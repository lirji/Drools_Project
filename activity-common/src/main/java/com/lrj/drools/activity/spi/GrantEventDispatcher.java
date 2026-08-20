package com.lrj.drools.activity.spi;

/**
 * 发放事件投递器（可插拔 SPI，同 recon {@code AlertDispatcher} 范式）：真正把一条
 * {@code activity_grant_outbox} 事件推给下游账务/渠道系统。
 *
 * <p>由 {@code GrantOutboxRelay} 在<b>中继短事务之外</b>调用（外部 I/O 与事务解耦，超时不持锁跨网络），
 * <b>绝不</b>在 {@code confirmGrant}/{@code releaseGrant} 的写事务内调用。at-least-once + 幂等键
 * （{@link GrantEvent#idempotencyKey()}）保证重复投递在下游可去重。
 *
 * <p>默认实现 {@code LoggingGrantEventDispatcher}（只记日志占位，dev / 未配置 webhook）；生产用
 * {@code @Primary WebhookGrantEventDispatcher}（POST payload 到账务系统 webhook）覆盖。
 *
 * @return 投递成功 {@code true}（中继置 SENT）；失败 {@code false} 或抛异常（中继置 FAILED + attempt，待补投）
 */
public interface GrantEventDispatcher {

    boolean dispatch(GrantEvent event);
}
