package com.lrj.drools.activity.domain;

/**
 * 活动类型。取值对齐来源项目 {@code ActivityTypeEnums}（mall-common）。
 *
 * 本 demo（收敛移植）首期只打通 {@link #RED_PACKAGE} 与 {@link #BUY_AND_GET} 两种；
 * COUPONS / CPS / RIGHT_COUPON 保留枚举位但不实现链路。
 */
public enum ActivityType {

    RED_PACKAGE(1, "红包"),
    COUPONS(2, "优惠券"),
    CPS(3, "CPS 分润"),
    RIGHT_COUPON(4, "权益券"),
    BUY_AND_GET(5, "买赠"),

    /**
     * 加价购：买主商品后，可以加少量钱换购指定商品。
     *
     * <p><b>它与买赠的区别是"要不要用户选"</b>——买赠是一次性把赠品全给出去，
     * 加价购必须先返回可换购清单、等用户挑一个、再二次定价。这就是它此前做不了的原因：
     * 决策链路是一次性返回最终优惠，没有第二阶段。
     *
     * <p>复用 {@code activity_gift} 承载换购品：{@code giftName} 是品名、
     * {@code absoluteAmount} 是<b>加价金额</b>（加多少钱换购），不是赠品价值。
     */
    ADD_ON_PURCHASE(6, "加价购");

    private final int code;
    private final String label;

    ActivityType(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public int code() { return code; }
    public String label() { return label; }

    public static ActivityType fromCode(Integer code) {
        if (code == null) return null;
        for (ActivityType t : values()) {
            if (t.code == code) return t;
        }
        throw new IllegalArgumentException("未知活动类型 code: " + code);
    }
}
