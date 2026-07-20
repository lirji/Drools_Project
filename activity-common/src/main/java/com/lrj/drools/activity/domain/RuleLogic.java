package com.lrj.drools.activity.domain;

/**
 * 资格条件树的分组逻辑。对齐来源 {@code RuleLogic}。
 *
 * separator 是翻译成 Drools LHS 约束时子条件之间的连接符
 * （本 demo 不用 QLExpress，直接拼 Drools 约束表达式）。
 */
public enum RuleLogic {

    AND("AND", "且", " && "),
    OR("OR", "或", " || ");

    private final String code;
    private final String label;
    private final String separator;

    RuleLogic(String code, String label, String separator) {
        this.code = code;
        this.label = label;
        this.separator = separator;
    }

    public String code() { return code; }
    public String label() { return label; }
    public String separator() { return separator; }

    public static RuleLogic fromCode(String code) {
        if (code == null) return null;
        for (RuleLogic l : values()) {
            if (l.code.equalsIgnoreCase(code.trim())) return l;
        }
        throw new IllegalArgumentException("未知条件逻辑: " + code);
    }
}
