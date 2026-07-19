package com.lrj.drools.activity.tenant;

import java.util.function.Supplier;

/**
 * 当前请求/线程的**操作者身份**（P1-8 四眼职责分离的"来源接缝"）。
 *
 * <p>只承载"当前动作是谁发起的"这一事实，不关心它从哪来：
 * <ul>
 *   <li><b>auth 档</b>：{@link JwtTenantFilter} 从 JWT 的 {@code sub} 写入（机器/人的稳定身份）；</li>
 *   <li><b>dev/header 档</b>：{@code TenantContextFilter} 从 {@code X-Actor} header 写入（本地演示用）。</li>
 * </ul>
 * 与 {@link TenantContext} 同构（ThreadLocal + finally 清理）。四眼在应用层强制：
 * {@code ActivityMarketingService} 在活动<strong>发布(上线)</strong>时校验「审批人 ≠ 提交人」。
 */
public final class ActorContext {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private ActorContext() {}

    public static void set(String actor) {
        CURRENT.set(actor);
    }

    /** 当前线程操作者；未设置返回 {@code null}（调用方按四眼开关决定是否拒绝）。 */
    public static String get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }

    /** 在指定操作者下执行并返回结果，执行后恢复（嵌套安全）。测试/后台任务用。 */
    public static <T> T callWith(String actor, Supplier<T> body) {
        String prev = CURRENT.get();
        CURRENT.set(actor);
        try {
            return body.get();
        } finally {
            if (prev == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(prev);
            }
        }
    }

    public static void runWith(String actor, Runnable body) {
        callWith(actor, () -> {
            body.run();
            return null;
        });
    }
}
