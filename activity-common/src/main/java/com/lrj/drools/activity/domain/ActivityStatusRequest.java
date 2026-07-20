package com.lrj.drools.activity.domain;

/**
 * 活动上下线请求。{@code targetStatus} 用 {@link ActivityStatus} 的 code（1=上线 2=下线）。
 * {@code version} 指定操作哪个版本（不填则操作当前最新版本）。
 */
public record ActivityStatusRequest(
        Integer version,
        Integer targetStatus
) {
}
