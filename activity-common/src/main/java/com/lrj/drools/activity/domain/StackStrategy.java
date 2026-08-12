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

    /**
     * 写平面用的严格解析：脏 code 直接抛，<b>创建期就该拒</b>
     * （{@code ActivityMarketingService.validateCommon} 依赖这条）。
     *
     * <p>{@code null} / 空白返回 {@link #MAX}（"没配策略" 不是错误，默认取最大），与改造前逐字节一致。
     */
    public static StackStrategy fromCode(String code) {
        StackStrategy parsed = tryFromCode(code);
        if (parsed == null) throw new IllegalArgumentException("未知合并策略: " + code);
        return parsed;
    }

    /**
     * 读路径（决策取数 / 快照构建）用的宽容解析：<b>解析不出来返回 {@code null}，不抛</b>。
     * 口径与 {@link RuleLogic#tryFromCode} 一致。
     *
     * <p>调用方拿到 null 一律回落 {@link #MAX}——这不是"随便挑一个"：走库路径的
     * {@code orElse(MAX)} 早就把"查不到策略行"定义成 MAX 了，脏策略行与缺策略行对决策的可用信息量
     * 完全相同（都得不到合并策略），回落到同一个值才不会让两条路发不同的钱。
     *
     * <p>{@code null} / 空白同样返回 {@link #MAX}（与 {@link #fromCode} 同口径），
     * 因此 null 返回值<b>只</b>表示"这个 code 读不懂"。
     */
    public static StackStrategy tryFromCode(String code) {
        if (code == null || code.isBlank()) return MAX;
        for (StackStrategy s : values()) {
            if (s.name().equalsIgnoreCase(code.trim())) return s;
        }
        return null;
    }
}
