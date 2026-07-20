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
    BUY_AND_GET(5, "买赠");

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
