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

    public static RuleOperator fromCode(String code) {
        if (code == null) return null;
        for (RuleOperator op : values()) {
            if (op.code.equalsIgnoreCase(code.trim())) return op;
        }
        throw new IllegalArgumentException("未知运算符: " + code);
    }
}
