package com.lrj.drools.activity.error;

/**
 * 活动平台的领域异常：<b>带分类</b>的失败，由 {@code @RestControllerAdvice} 统一转成 HTTP 响应。
 *
 * <p>它解决的不是「少写几个 try/catch」，而是<b>分类信息在抛出点存在、到出口就丢了</b>：
 * 抛出方明明知道这是「四眼拒绝」还是「并发重复」，可它只能选 {@code IllegalArgumentException} 或
 * {@code IllegalStateException}，剩下的语义只活在 message 字符串里。下游要再用它，就只能去
 * <b>匹配 message</b>——{@code ActivityMarketingService} 里那句
 * {@code msg.contains("uk_am_tenant_request")} 正是这么来的：把异常文案当成了控制流的 key，
 * 而文案是各家数据库自己拼的、随驱动版本变。
 *
 * <p><b>message 保持与改造前逐字一致</b>：它是面向运营的中文提示，会原样出现在控制台上，
 * 前端也有测试直接断言这些串。本类只在旁边补上一个机器可读的 {@link ActivityErrorCode}。
 *
 * <p>继承 {@link RuntimeException} 而<b>不是</b> {@code IllegalStateException} 是有意的：
 * console 侧现存的 per-endpoint {@code catch} 在迁移期原样保留，领域异常必须能<b>穿过</b>它们
 * 落到 advice 上，否则新分类会被旧 catch 就地降级回它想摆脱的那个状态码。
 */
public class ActivityException extends RuntimeException {

    private final ActivityErrorCode code;

    public ActivityException(ActivityErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ActivityException(ActivityErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public ActivityErrorCode code() {
        return code;
    }

    // ---------------------------------------------------------------- 具名工厂
    // 抛出点直接说出「这是哪一类失败」，不必在调用处重复挑选枚举值。

    /** 并发编辑：目标版本号已被占用 / 软删未命中。 */
    public static ActivityException versionConflict(String message) {
        return new ActivityException(ActivityErrorCode.VERSION_CONFLICT, message);
    }

    /** 并发重复提交：同租户同 requestId 撞唯一约束。 */
    public static ActivityException duplicateRequest(String message, Throwable cause) {
        return new ActivityException(ActivityErrorCode.DUPLICATE_REQUEST, message, cause);
    }

    /** 四眼职责分离拒绝（审批人缺失 / 审批人 == 提交人）。 */
    public static ActivityException fourEyesRequired(String message) {
        return new ActivityException(ActivityErrorCode.FOUR_EYES_REQUIRED, message);
    }
}
