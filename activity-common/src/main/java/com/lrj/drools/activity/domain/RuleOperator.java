package com.lrj.drools.activity.domain;

/**
 * 资格条件运算符。对齐来源 {@code RuleOperator}（code 用 camelCase，如 notIn / containsAny）。
 *
 * {@link Operand} 描述 value 的形状，用于翻译前校验：
 * - SCALAR：单值（eq/ne/gt/ge/lt/le/contains/notContains）
 * - RANGE ：二元区间 [lo, hi]（between）
 * - LIST  ：值列表（in/notIn/containsAny）
 */
public enum RuleOperator {

    EQ("eq", "等于", Operand.SCALAR),
    NE("ne", "不等于", Operand.SCALAR),
    GT("gt", "大于", Operand.SCALAR),
    GE("ge", "大于等于", Operand.SCALAR),
    LT("lt", "小于", Operand.SCALAR),
    LE("le", "小于等于", Operand.SCALAR),
    BETWEEN("between", "区间", Operand.RANGE),
    IN("in", "属于", Operand.LIST),
    NOT_IN("notIn", "不属于", Operand.LIST),
    CONTAINS("contains", "包含", Operand.SCALAR),
    NOT_CONTAINS("notContains", "不包含", Operand.SCALAR),
    CONTAINS_ANY("containsAny", "包含任一", Operand.LIST);

    public enum Operand { SCALAR, RANGE, LIST }

    private final String code;
    private final String label;
    private final Operand operand;

    RuleOperator(String code, String label, Operand operand) {
        this.code = code;
        this.label = label;
        this.operand = operand;
    }

    public String code() { return code; }
    public String label() { return label; }
    public Operand operand() { return operand; }

    /**
     * 写平面用的严格解析：脏 code 直接抛，<b>创建期就该拒</b>（{@code RuleConditionTranslator} 依赖这条）。
     *
     * <p>{@code null} 仍返回 {@code null}（与改造前逐字节一致），由调用方决定"没写算子"怎么办。
     */
    public static RuleOperator fromCode(String code) {
        if (code == null) return null;
        RuleOperator parsed = tryFromCode(code);
        if (parsed == null) throw new IllegalArgumentException("未知运算符: " + code);
        return parsed;
    }

    /**
     * 读路径（资格求值）用的宽容解析：<b>解析不出来返回 {@code null}，不抛</b>。
     * 口径与 {@link RuleLogic#tryFromCode} 一致：读路径拿不到可判定的结论就 fail-closed 淘汰
     * <b>这一个候选</b>，而不是让一条脏 op 把整次请求（连同同请求里其它正常活动）打成 500。
     *
     * <p>注意 {@code null} code 在这里也返回 {@code null}——对求值器而言"没写算子"与
     * "算子读不懂"是同一件事：都判不出这个叶子。
     */
    public static RuleOperator tryFromCode(String code) {
        if (code == null) return null;
        for (RuleOperator op : values()) {
            if (op.code.equalsIgnoreCase(code.trim())) return op;
        }
        return null;
    }
}
