package com.lrj.drools.activity.error;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 错误响应体。
 *
 * <p>{@code error} 字段名与改造前 {@code ActivityMarketingController.ErrorResponse} <b>逐字一致</b>——
 * 前端 {@code apiClient} 的 {@code errMsg} 读的就是 {@code j.error || j.message}，改名等于让所有
 * 错误提示变成「HTTP 400」。{@code code} 是新增的<b>机器可读</b>分类，纯附加，老调用方读不到也不受影响。
 *
 * <p>{@code NON_NULL}：决策平面的 500 兜底刻意不回显 message，此时序列化出来就只有
 * {@code {"code":"INTERNAL"}}，而不是一个字面的 {@code "error":null} 让人以为字段丢了。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ActivityErrorBody(String error, String code) {

    public static ActivityErrorBody of(ActivityErrorCode code, String message) {
        return new ActivityErrorBody(message, code.name());
    }

    /** 不回显 message 的分类出口（决策平面 500 兜底用）。 */
    public static ActivityErrorBody of(ActivityErrorCode code) {
        return new ActivityErrorBody(null, code.name());
    }
}
