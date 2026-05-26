package com.lrj.drools.domain;

/**
 * Step 8: CEP 事件型 fact。
 *
 * 跟 Order / OrderItem 不一样 — Order 是"购物车快照"型的状态 fact, OrderEvent 是
 * "下单这个动作"的时间序列事件, 有自己的时间戳, 进 working memory 后会随滑窗自动
 * 过期。
 *
 * timestamp 是事件发生时间, 单位毫秒 (ms since epoch 或相对会话起点皆可,
 * 跟 SessionPseudoClock 的口径对齐即可)。在 DRL 里用 `@timestamp(timestamp)` 把
 * 这个字段告诉 Drools 引擎, 让它当事件时间线用。
 */
public record OrderEvent(String orderId, String customerName, double amount, long timestamp) {
}
