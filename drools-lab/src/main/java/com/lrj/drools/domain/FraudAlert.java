package com.lrj.drools.domain;

/**
 * Step 8 扩展 (CEP 补完) 的告警标记 fact。
 *
 * 独立于 Step 8 原有的 BurstAlert —— 原 /fraud/check 只输出 BurstAlert, 保持不变;
 * 新的 /fraud/patterns 输出 FraudAlert, 用 type 区分三种进阶 CEP 形态:
 *   - LENGTH_BURST            : 长度滑窗 (over window:length)
 *   - PROBE_THEN_STRIKE       : 时序操作符 (this after[..])
 *   - FAST_ORDER_AFTER_LOGIN  : 跨 entry-point 关联
 *
 * 跟 BurstAlert 同样配合 `not FraudAlert(customerName == $cust, type == "...")` 做
 * "同客户同类型不重复告警"的自终止。
 */
public record FraudAlert(String type, String customerName, String detail, long detectedAt) {
}
