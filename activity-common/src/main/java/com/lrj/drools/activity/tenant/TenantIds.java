package com.lrj.drools.activity.tenant;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * 租户 id 的统一语法与保留值（codex-test ISSUE-01/03/09 收口）。header、dev-default、aud-map value、
 * pattern 反解**共用同一 grammar + 同一保留值集合**，避免"某条路径漏校验"放进非法/内部占位租户。
 *
 * <p>保留值：{@link #NO_TENANT}（resolver 无租户兜底哨兵）、{@link #SINGLE}（= {@code RuleSchemaRegistry.DEFAULT_TENANT}，
 * 无上下文时的 schema key）。外部一律不得冒充这两个值。{@code __dev__} 不是保留值（它是 dev 档的合法单租户）。
 */
public final class TenantIds {

    /** 无租户兜底哨兵（不匹配任何真实租户行）。 */
    public static final String NO_TENANT = "__no_tenant__";

    /** 单租户/无上下文的内部占位（与 {@code RuleSchemaRegistry.DEFAULT_TENANT} 对齐）。 */
    public static final String SINGLE = "__single__";

    /** 租户 id 白名单语法：字母数字 + 下划线/连字符，1~64 位。 */
    public static final Pattern GRAMMAR = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private static final Set<String> RESERVED = Set.of(NO_TENANT, SINGLE);

    private TenantIds() {}

    /** 是否内部保留值（外部不得冒充）。 */
    public static boolean isReserved(String tenant) {
        return tenant != null && RESERVED.contains(tenant);
    }

    /** 是否合法的**外部**租户 id：符合语法且非保留值。 */
    public static boolean isValidExternal(String tenant) {
        return tenant != null && GRAMMAR.matcher(tenant).matches() && !isReserved(tenant);
    }
}
