package com.lrj.drools.activity.controller;

import com.lrj.drools.activity.error.ActivityErrorBody;
import com.lrj.drools.activity.error.ActivityErrorCode;
import com.lrj.drools.activity.error.ActivityException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 写平面（console）的<b>统一</b>异常 → HTTP 映射。
 *
 * <p><b>为什么要有它</b>：改造前这份映射被手抄在 {@code ActivityMarketingController} 的四个方法里，
 * 而写平面有九个端点——没抄到的那五个（{@code claim} / {@code release} / {@code grants} /
 * {@code preview} / {@code field-dict} …）里一旦抛出异常，落到的是 Spring Boot 的默认 {@code /error}：
 * 一个 500，带着完整的 message。也就是说「参数非法返回 400」这条约定，实际只在抄到的地方成立。
 *
 * <p><b>scope 收得很紧</b>：{@code basePackages} 只圈本包。console 的 classpath 上还挂着
 * drools-lab 的 Step 1–18 教学 controller（{@code com.lrj.drools.controller}），
 * 全局 advice 会把它们的错误行为一起改掉——那是本次改造范围之外的十六个端点。
 *
 * <p><b>与 controller 里现存 catch 的关系</b>：那些 {@code catch} 迁移期<b>原样保留</b>，
 * 本 advice 只兜没被 catch 的路径，因此已有端点的状态码一位都不会漂。唯一的例外是
 * {@link ActivityException}——它不是 {@code IllegalArgumentException} / {@code IllegalStateException}
 * 的子类，会<b>穿过</b>旧 catch 落到这里，按自己的分类给状态码。四眼失败的 409 → 403 正是这么发生的。
 */
@RestControllerAdvice(basePackages = "com.lrj.drools.activity.controller")
public class ActivityExceptionAdvice {

    private static final Logger log = LoggerFactory.getLogger(ActivityExceptionAdvice.class);

    /** 领域异常：状态码由 {@link ActivityErrorCode} 决定，不再由「抛的是哪个 JDK 异常」决定。 */
    @ExceptionHandler(ActivityException.class)
    public ResponseEntity<ActivityErrorBody> onActivityException(ActivityException ex) {
        ActivityErrorCode code = ex.code();
        log.warn("[activity] {} → {}: {}", code, code.httpStatus(), ex.getMessage());
        return ResponseEntity.status(code.httpStatus()).body(ActivityErrorBody.of(code, ex.getMessage()));
    }

    /** 还没迁移到领域异常的校验失败。状态码与改造前的 per-endpoint catch 一致（400）。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ActivityErrorBody> onIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.status(ActivityErrorCode.INVALID_ARGUMENT.httpStatus())
                .body(ActivityErrorBody.of(ActivityErrorCode.INVALID_ARGUMENT, ex.getMessage()));
    }

    /** 还没迁移到领域异常的状态冲突。状态码与改造前的 per-endpoint catch 一致（409）。 */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ActivityErrorBody> onIllegalState(IllegalStateException ex) {
        return ResponseEntity.status(ActivityErrorCode.STATE_CONFLICT.httpStatus())
                .body(ActivityErrorBody.of(ActivityErrorCode.STATE_CONFLICT, ex.getMessage()));
    }
}
