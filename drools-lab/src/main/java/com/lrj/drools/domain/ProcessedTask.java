package com.lrj.drools.domain;

/**
 * Step 22: fireUntilHalt 处理每个 Task 后 insert 的结果标记 fact，service 捞回来当处理回执。
 */
public record ProcessedTask(String id, int amount) {
}
