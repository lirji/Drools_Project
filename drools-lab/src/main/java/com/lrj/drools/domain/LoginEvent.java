package com.lrj.drools.domain;

/**
 * Step 8 扩展 (CEP 补完): 第二条事件流 —— 登录事件。
 *
 * 跟 OrderEvent 一样是"事件型" fact (有 @timestamp, 会随滑窗/过期), 但走**另一个
 * entry-point** ("login-stream")。多 entry-point 的意义: 让不同来源的事件流物理隔离,
 * 规则再按需跨流关联 (例: 登录后 30 秒内就大额下单 = 盗号高危信号)。
 *
 * timestamp 口径跟 OrderEvent 对齐 (ms), 由 SessionPseudoClock 统一推进。
 */
public record LoginEvent(String customerName, String ip, long timestamp) {
}
