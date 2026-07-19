package com.lrj.drools.activity.domain;

/**
 * 多活动优惠合并策略。对齐来源 {@code ActivityStackStrategy}（枚举名即 code）。
 *
 * - MAX：同 SPU 多活动取优惠金额最大的一个（默认）
 * - MUTEX / PRIORITY：互斥单选，按 priority 最小者胜出，priority 相同再比金额大
 * - STACK：所有生效活动金额累加，主活动 id 取优先级最高者（仅用于展示）
 *
 * 语义与 {@code DiscountDbRuleSource.buildDrl} 生成的 DRL 一致。
 */
public enum StackStrategy {

    MAX,
    MUTEX,
    STACK,
    PRIORITY;

    public static StackStrategy fromCode(String code) {
        if (code == null || code.isBlank()) return MAX;
        for (StackStrategy s : values()) {
            if (s.name().equalsIgnoreCase(code.trim())) return s;
        }
        throw new IllegalArgumentException("未知合并策略: " + code);
    }
}
