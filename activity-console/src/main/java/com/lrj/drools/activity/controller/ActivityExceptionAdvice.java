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

    // ------------------------------------------------------------------
    // 这里**刻意没有** @ExceptionHandler(IllegalStateException.class) → 409。
    //
    // 一度有过，理由是「与改造前的 per-endpoint catch 一致」。但 advice 的作用域是整个
    // controller 包，而那些 catch 只挂在 create / status 两个方法上——把它提成兜底，
    // 等于顺带宣布 list / preview / grants / generation / field-dict 上抛出的**任何**
    // IllegalStateException 都是「状态冲突」。而在那些端点上，ISE 的来源是 Optional.get、
    // 懒加载、bean 状态错——那是 bug，不是冲突。报成 409 的后果有三层，一层比一层贵：
    //   ① 4xx 不计错误预算、不触发告警 —— 写平面的故障在监控上直接消失；
    //   ② 409 的标准语义是「重试可能成功」，调用方会去重试一个永远不会成功的请求；
    //   ③ 排查时先去查「谁在并发改这条活动」，而根因在另一个方向。
    // 这正是同一批改动里 DecisionExceptionAdvice 花大段注释论证要避免的「把 bug 伪装成
    // 客户端错误」，只是方向相反——那边怕 IAE→400 掩盖脏数据，这边怕 ISE→409 掩盖 NPE 类故障。
    //
    // 真正需要 409 的路径都已经有归属，不依赖这个兜底：
    //   · 版本冲突 / 幂等重放 / 状态迁移非法 → ActivityException（VERSION_CONFLICT 等），
    //     由上面那个 handler 按 ActivityErrorCode 给码，且能穿过旧 catch；
    //   · create / status 两个端点保留的 per-endpoint catch 仍把 ISE 兜成 409，状态码一位不漂。
    // 没被分类的 ISE 就该落到 500 —— 让它响亮地失败，而不是安静地变成一个 4xx。
}
