package com.lrj.drools.activity.domain;

/**
 * 活动状态。取值对齐来源项目 {@code ActivityStatusEnums}（mall-common）。
 *
 * 生效判定（{@code filterBeginActivityIds} 旧逻辑）取 {@link #ONLINE} 且当前时间在活动时间范围内。
 * {@link #PENDING_EFFECT} 表示已经审批并预约，console 调度器会在开始时间到达后切为 ONLINE；
 * ONLINE 超过结束时间后会自动切为 OFFLINE。
 */
public enum ActivityStatus {

    NORMAL(0, "待上线"),
    ONLINE(1, "已上线"),
    OFFLINE(2, "已下线"),
    PENDING_EFFECT(3, "待生效");

    private final int code;
    private final String label;

    ActivityStatus(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public int code() { return code; }
    public String label() { return label; }

    public static ActivityStatus fromCode(Integer code) {
        if (code == null) return null;
        for (ActivityStatus s : values()) {
            if (s.code == code) return s;
        }
        throw new IllegalArgumentException("未知活动状态 code: " + code);
    }
}
