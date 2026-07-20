package com.lrj.drools.activity.tenant;

import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Hibernate 判别式（{@code @TenantId}）多租户的租户解析器。SessionFactory 每次开 Session 时调用它，
 * 引擎据此对所有 {@code @TenantId} 实体的 SQL 自动追加 {@code tenant_id = ?} 谓词、insert 时自动落 tenant。
 *
 * <p><b>为什么"永不抛异常、永不返回 null"</b>（这条是本类的命门，别改）：
 * <ul>
 *   <li><b>永不 null</b>：{@link CurrentTenantIdentifierResolver#isRoot(Object)} 默认把 {@code null} 当
 *       "root 租户 = 看所有租户数据"。返回 null 会绕过隔离——最危险。故无租户时返回
 *       {@link #NO_TENANT} 哨兵（不匹配任何真实租户行）→ 读隔离到空、写打哨兵标签（孤儿行，不串租户），fail-closed。</li>
 *   <li><b>永不抛</b>：本项目 {@code open-in-view=false}，但 Step 10(loyalty)/Step 18(campaign) 等
 *       <em>非活动</em> DB 接口仍会开 Session（它们的实体没有 {@code @TenantId}，本 resolver 对其无副作用）。
 *       若这里对"无租户"抛异常，会误伤这些无关 Step。所以<strong>面向用户的 fail-closed（403）放在
 *       {@code TenantContextFilter}</strong>（只管 {@code /activity-marketing/*}），本 resolver 只做 ORM 层兜底。</li>
 * </ul>
 *
 * <p>解析顺序：{@link TenantContext} 有值 → 用它；否则 dev-default 开着 → 用 dev-default；否则 → 哨兵（并 warn，不静默）。
 */
@Component
public class TenantIdentifierResolver implements CurrentTenantIdentifierResolver<String> {

    private static final Logger log = LoggerFactory.getLogger(TenantIdentifierResolver.class);

    /** 无租户兜底哨兵：不等于任何真实租户 id，也不等于 dev-default → 命中它的查询恒空（读隔离）。 */
    public static final String NO_TENANT = TenantIds.NO_TENANT;

    private final TenantProperties props;

    public TenantIdentifierResolver(TenantProperties props) {
        this.props = props;
    }

    @Override
    public String resolveCurrentTenantIdentifier() {
        String t = TenantContext.get();
        if (t != null && !t.isBlank()) {
            return t;
        }
        if (props.isDevDefaultEnabled()) {
            String d = props.getDevDefault();
            if (TenantIds.isValidExternal(d)) {
                return d;
            }
            // dev-default 配置非法/保留/null：不返回它（否则可能 null 或串保留值），回落哨兵（ISSUE-01：永不 null）。
            log.warn("dev-default 租户配置非法 [{}]，回落哨兵 {}", d, NO_TENANT);
            return NO_TENANT;
        }
        // 无租户且 dev-default 未开：ORM 层兜底到哨兵（读隔离到空），不抛不 null。
        // 面向用户请求的 403 由 TenantContextFilter 提前拦；能走到这里的多是后台/非活动 Session。
        log.warn("无租户上下文且 dev-default 未启用，回落哨兵租户 {}（查询将隔离到空）。若这是活动接口请检查 X-Tenant-Id。",
                NO_TENANT);
        return NO_TENANT;
    }

    @Override
    public boolean validateExistingCurrentSessions() {
        // 每请求新开 Session，无需校验既有 Session 的租户一致性。
        return false;
    }
}
