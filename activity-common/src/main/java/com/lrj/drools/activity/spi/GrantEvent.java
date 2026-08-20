package com.lrj.drools.activity.spi;

/**
 * 发放传播事件——{@link GrantEventDispatcher} 的投递单元。是 {@code activity_grant_outbox} 一行的
 * <b>不可变投影</b>，把中继要发的东西与 JPA 实体解耦：下游 dispatcher 实现只依赖这个 record，
 * 不接触持久化细节，便于替换通道（webhook / MQ / 直调）与离线测试。
 *
 * <p><b>幂等键 = {@code (grantNo, eventType)}</b>（{@link #idempotencyKey()}）：投递语义是<b>至少一次</b>，
 * 下游必须按它去重消费（同一 grant_no 的 GRANT_ISSUED/GRANT_REVERSED 各处理一次）。{@code payload} 是
 * 事件全文 JSON，通常即下游真正落库的内容。
 *
 * @param grantNo     发放号（= recon issue_id，下游 join 键）
 * @param orderId     订单号（= recon order_no）
 * @param activityId  活动 id
 * @param eventType   事件类型（GRANT_ISSUED / GRANT_REVERSED）
 * @param entryType   分录类型（ISSUE / REVERSAL）
 * @param amountMinor 带符号最小单位（分）：ISSUE 正、REVERSAL 负
 * @param currency    币种
 * @param payload     事件全文 JSON
 * @param attempt     已尝试次数（用于下游/日志观测重投）
 */
public record GrantEvent(String grantNo, String orderId, String activityId, String eventType,
                         String entryType, long amountMinor, String currency, String payload, int attempt) {

    /** 跨系统去重键：一次发放的一种事件唯一。下游按它幂等消费，重复投递可安全丢弃。 */
    public String idempotencyKey() {
        return grantNo + ":" + eventType;
    }
}
