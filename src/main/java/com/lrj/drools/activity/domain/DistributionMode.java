package com.lrj.drools.activity.domain;

/**
 * 红包发放方式。对齐来源 {@code ActivityDistributionEnums}。
 *
 * - FIXED_AMOUNT：固定金额，直接用 {@code redPackageAmount}
 * - RANDOM_AMOUNT：随机金额，范围存在 {@code redPackageRangeAmount}
 */
public enum DistributionMode {

    FIXED_AMOUNT(1, "固定金额"),
    RANDOM_AMOUNT(2, "随机金额");

    private final int code;
    private final String label;

    DistributionMode(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public int code() { return code; }
    public String label() { return label; }

    public static DistributionMode fromCode(Integer code) {
        if (code == null) return null;
        for (DistributionMode m : values()) {
            if (m.code == code) return m;
        }
        throw new IllegalArgumentException("未知发放方式 code: " + code);
    }
}
