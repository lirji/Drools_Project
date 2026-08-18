package com.lrj.drools.activity.domain;

/**
 * 资格条件树的分组逻辑。对齐来源 {@code RuleLogic}。
 *
 * separator 是翻译成 Drools LHS 约束时子条件之间的连接符
 * （当前实现不用 QLExpress，直接生成受控 Drools 约束表达式）。
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

    /**
     * 写平面用的严格解析：脏 code 直接抛，<b>创建期就该拒</b>（{@code RuleConditionTranslator} 依赖这条）。
     *
     * <p>{@code null} 仍返回 {@code null}（"没写 logic" 不是错误，由调用方决定默认语义），
     * 这与改造前逐字节一致。
     */
    public static RuleLogic fromCode(String code) {
        if (code == null) return null;
        RuleLogic parsed = tryFromCode(code);
        if (parsed == null) throw new IllegalArgumentException("未知条件逻辑: " + code);
        return parsed;
    }

    /**
     * 读路径（决策热路径）用的宽容解析：<b>解析不出来返回 {@code null}，不抛</b>。
     *
     * <p>为什么要有这一个出口：资格求值器在决策链路上一路无 catch，直调 {@link #fromCode} 时
     * 一条脏 logic 就把<b>整次请求</b>打成 500——一个活动的坏数据连累了同一次请求里所有正常活动。
     * 口径与 {@code ActivityRuleContext.numberAttr} 那道「拿不到可用值就返回 null」的护栏一致：
     * 读路径拿不到可判定的结论就交给调用方 fail-closed（淘汰这一个候选），而不是打断整条链路。
     */
    public static RuleLogic tryFromCode(String code) {
        if (code == null) return null;
        for (RuleLogic l : values()) {
            if (l.code.equalsIgnoreCase(code.trim())) return l;
        }
        return null;
    }
}
