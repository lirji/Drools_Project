package com.lrj.drools.activity.domain;

/**
 * 决策指标的 {@code scene} 标签——<b>唯一词汇表</b>。
 *
 * <p><b>为什么要收敛</b>：改造前 scene 分裂成四套词汇，且分裂点分散在四个类里——
 * {@code ActivityQueryService} 的 {@code spu-discount}/{@code gifts}、
 * {@code AddOnPurchaseService} 的 {@code addon}、{@code BenefitEvaluator} 里硬编码的
 * {@code benefit}（那是<b>阶段</b>不是通道）、以及取数层的 {@code ActivityType.name()}
 * （{@code RED_PACKAGE}/{@code BUY_AND_GET}/{@code ADD_ON_PURCHASE}）。
 * 后果不是「不整齐」，是<b>两组指标 join 不上</b>：值班按
 * {@code activity_decision_source_total{scene="gifts"}} 查会直接得到空结果，
 * 而按 {@code activity_decision_reject_total{scene="gifts"}} 统计买赠淘汰量会漏掉全部算额淘汰
 * （它们都被记在 {@code benefit} 这一格）。这个漂移此前是被写进文档「以代码为准」，而不是被修掉。
 *
 * <p>用枚举而不是常量类：它给出<b>编译期封闭集合</b>——scene 是 Prometheus 标签，
 * 而标签取值集合必须是有限的（与 {@code DecisionMetrics.ACTIVITY_TAG_CAP} 是同一套顾虑）。
 *
 * <p><b>{@link #code()} 的取值一个字节都不能改</b>：它们已经是线上时间序列的标签值，
 * 面板与告警按它过滤。
 */
public enum DecisionScene {

    /** 红包 / 折扣通道（{@code /decision/v1/spu-discount}）。 */
    SPU_DISCOUNT("spu-discount"),
    /** 买赠通道（{@code /decision/v1/gifts}）。 */
    GIFTS("gifts"),
    /** 加价购通道（{@code /decision/v1/addon/*}，两阶段）。 */
    ADDON("addon"),
    /**
     * 算额<b>阶段</b>，不是业务通道。
     *
     * <p>求值层（{@code BenefitEvaluator}）此前拿不到通道，只能把这个阶段名硬编码成 scene；
     * 现在它由调用方以参数传入，取值仍是 {@code benefit}——<b>刻意保持不变</b>，
     * 见 {@code BenefitEvaluator.computeAmounts} 上那条 TODO：换成真实通道会改变已有
     * Prometheus 序列，属于要与 Grafana 同批做的契约变更，不是重构副作用。
     */
    BENEFIT("benefit");

    private final String code;

    DecisionScene(String code) {
        this.code = code;
    }

    /** 指标标签取值。已是线上 Prometheus 序列的一部分。 */
    public String code() {
        return code;
    }
}
