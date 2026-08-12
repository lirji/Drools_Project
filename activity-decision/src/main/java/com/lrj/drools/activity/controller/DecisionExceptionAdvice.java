package com.lrj.drools.activity.controller;

import com.lrj.drools.activity.error.ActivityErrorBody;
import com.lrj.drools.activity.error.ActivityErrorCode;
import com.lrj.drools.activity.error.ActivityException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * 决策平面（只读热路径）的异常出口。<b>刻意比 console 侧窄得多</b>。
 *
 * <p><b>这里没有 {@code IllegalArgumentException → 400} 这条兜底，是有意的。</b>
 * console 侧那条成立，是因为写平面的 IAE 绝大多数确实来自运营填错的表单；
 * 而决策服务只读、入参极简，它抛出的 IAE 只可能是两种东西：<b>库里的脏数据</b>，或者<b>真 bug</b>。
 * 把它们统一报成 400，等于对调用方说「是你请求写错了」——于是：
 * <ul>
 *   <li>告警不会响（4xx 通常不计入错误预算），SRE 也不会来看；</li>
 *   <li>调用方会去改自己那条本来没问题的请求；</li>
 *   <li>而真正的脏策略行 / 空指针会一直待在库里继续影响发钱。</li>
 * </ul>
 * 一个 bug 被伪装成客户端错误之后，就再也没人负责它了。所以这里只留两个出口：
 * <b>分类明确</b>的 {@link ActivityException} 按自己的状态码走，其余一律 500。
 *
 * <p>500 兜底<b>不回显 message</b>（只给 {@code code=INTERNAL}）：决策平面是面向 toC 流量的，
 * 异常文案里可能带着活动 id、SQL 片段、内部字段名。细节进日志（带完整堆栈），不进响应体。
 *
 * <p>继承 {@link ResponseEntityExceptionHandler} 是为了<b>不误伤 Spring 自己的语义状态码</b>：
 * 父类已经声明了 {@code HttpMessageNotReadableException}（请求体不是合法 JSON → 400）、
 * {@code MissingServletRequestParameterException}（{@code /addon/quote} 少传 activityId → 400）
 * 等一批 handler。若只写一个 {@code @ExceptionHandler(Throwable.class)}，这些<b>本来就该是 400</b>
 * 的情况会被一起吞成 500 —— 那是把上面这套论证反过来做了一遍。
 */
@RestControllerAdvice(basePackages = "com.lrj.drools.activity.controller")
public class DecisionExceptionAdvice extends ResponseEntityExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(DecisionExceptionAdvice.class);

    @ExceptionHandler(ActivityException.class)
    public ResponseEntity<ActivityErrorBody> onActivityException(ActivityException ex) {
        ActivityErrorCode code = ex.code();
        LOG.warn("[decision] {} → {}: {}", code, code.httpStatus(), ex.getMessage());
        return ResponseEntity.status(code.httpStatus()).body(ActivityErrorBody.of(code, ex.getMessage()));
    }

    /** 未分类的一切 = 故障。响应只给分类，细节留在日志里。 */
    @ExceptionHandler(Throwable.class)
    public ResponseEntity<ActivityErrorBody> onUnexpected(Throwable ex) {
        LOG.error("[decision] 未分类异常，按 INTERNAL 处理", ex);
        return ResponseEntity.status(ActivityErrorCode.INTERNAL.httpStatus())
                .body(ActivityErrorBody.of(ActivityErrorCode.INTERNAL));
    }
}
