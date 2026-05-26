package com.lrj.drools.domain;

/**
 * Step 8: 风控告警 fact。
 *
 * 跟 Step 4 的 Promotion 思路一样: 规则自己 insert 出来, 配合 `not BurstAlert(...)`
 * 做"同客户不重复告警"的自终止。
 *
 * detectedAt 是引擎检测到 burst 时的 (pseudo) 时钟时间, 不是任一具体事件的时间戳。
 */
public record BurstAlert(String customerName, int eventCount, long detectedAt) {
}
