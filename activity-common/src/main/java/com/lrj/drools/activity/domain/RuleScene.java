package com.lrj.drools.activity.domain;

/**
 * 规则场景。对齐来源 {@code ActivityRuleScene}，决定加载哪一组 KieBase / DRL。
 *
 * 来源里 DISCOUNT 分两段（DISCOUNT_COMPUTE 用 QLExpress 先算金额，再 DISCOUNT 合并）。
 * 当前规则能力模块使用 Drools（不引 QLExpress），金额计算与合并放在同一组 discount 规则里完成，
 * 故省掉 DISCOUNT_COMPUTE 这一 QL 专用场景。
 */
public enum RuleScene {

    ELIGIBILITY("eligibility", "资格校验"),
    DISCOUNT("discount", "优惠合并"),
    LADDER("ladder", "阶梯结算"),
    GIFT("gift", "买赠发放");

    private final String code;
    private final String desc;

    RuleScene(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public String code() { return code; }
    public String desc() { return desc; }

    public static RuleScene fromCode(String code) {
        if (code == null) return null;
        for (RuleScene s : values()) {
            if (s.code.equalsIgnoreCase(code)) return s;
        }
        throw new IllegalArgumentException("未知规则场景 code: " + code);
    }
}
