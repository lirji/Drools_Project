package com.lrj.drools.activity.tenant;

import java.util.function.Supplier;

/**
 * 当前请求/线程的租户上下文（P0-4 多租户隔离机制的"来源接缝"）。
 *
 * <p><b>机制 vs 来源分离</b>：本类只承载"当前线程属于哪个租户"这一事实，<em>不关心</em>它从哪来。
 * Track B-P0-4 由 {@code TenantContextFilter} 从 {@code X-Tenant-Id} header 写入（dev/local）；
 * P0-3 接 auth-platform 后改由 {@link JwtTenantFilter} 从 JWT 的 {@code aud} 解析写入，本类与下游
 * {@link TenantIdentifierResolver} 一行不用改——这正是 Track A 留下的"造机制、stub 源、后续换源"接缝。
 *
 * <p>{@link TenantIdentifierResolver} 从这里读值，交给 Hibernate 的 {@code @TenantId} 判别式多租户，
 * 由引擎在<strong>每条 SQL</strong>自动追加 {@code tenant_id = ?} 谓词——隔离是机制不是纪律。
 *
 * <p>线程模型：值存 {@link ThreadLocal}。请求线程用完必须 {@link #clear()}（过滤器 finally 已保证），
 * 否则线程池复用会把上一个租户串给下一个请求。
 */
public final class TenantContext {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private TenantContext() {}

    /** 设置当前线程租户。 */
    public static void set(String tenant) {
        CURRENT.set(tenant);
    }

    /** 当前线程租户；未设置返回 {@code null}（由 resolver 决定兜底/拒绝，不在此处判定）。 */
    public static String get() {
        return CURRENT.get();
    }

    /** 清除当前线程租户。请求结束务必调用，避免线程池串租户。 */
    public static void clear() {
        CURRENT.remove();
    }

    /** 在指定租户下执行并返回结果，执行后恢复原租户（嵌套安全）。测试/后台任务用。 */
    public static <T> T callWith(String tenant, Supplier<T> body) {
        String prev = CURRENT.get();
        CURRENT.set(tenant);
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

    /** {@link #callWith} 的无返回值版本。 */
    public static void runWith(String tenant, Runnable body) {
        callWith(tenant, () -> {
            body.run();
            return null;
        });
    }
}
