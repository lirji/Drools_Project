package com.lrj.drools.activity.tenant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 活动接口的租户来源过滤器（P0-4 面向用户的 fail-closed 闸）。只挂在 {@code /activity-marketing/*}。
 *
 * <p>从 {@code X-Tenant-Id} header 取租户写进 {@link TenantContext}，请求结束在 finally 清除（防线程池串租户）。
 * <ul>
 *   <li>有合法 header → 用它；</li>
 *   <li>无 header 且 dev-default 开 → 回落 dev-default（本地/前端手点方便）；</li>
 *   <li>无 header 且 dev-default 关 → <b>403 fail-closed</b>（生产默认）；</li>
 *   <li>header 非法字符 → <b>400</b>（租户 id 会进缓存 key / schema 解析，白名单收口）。</li>
 * </ul>
 *
 * <p>来源接缝：P0-3 接 auth-platform 后，租户改由 {@link JwtTenantFilter} 从 JWT 的 {@code aud} 解析（命脉实测
 * owner=admin 非组织），{@link TenantContext} 与 {@link TenantIdentifierResolver} 不动。
 */
public class TenantContextFilter extends OncePerRequestFilter {

    public static final String TENANT_HEADER = "X-Tenant-Id";
    /** P1-8 dev 档操作者来源：dev/header 档无 JWT，从 X-Actor header 取审批人身份（本地演示四眼用）。 */
    public static final String ACTOR_HEADER = "X-Actor";

    private final TenantProperties props;

    public TenantContextFilter(TenantProperties props) {
        this.props = props;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // 入口防御清理：任何出口(成功/400/403)都不残留上一个请求在本线程留下的租户/操作者（ISSUE-02）。
        TenantContext.clear();
        ActorContext.clear();

        String tenant = request.getHeader(TENANT_HEADER);
        boolean fromHeader = tenant != null && !tenant.isBlank();
        if (!fromHeader) {
            if (!props.isDevDefaultEnabled()) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN,
                        "缺少 " + TENANT_HEADER + "：多租户 fail-closed 拒绝");
                return;
            }
            tenant = props.getDevDefault();
        }

        // 统一校验最终租户（无论来自 header 还是 dev-default）：语法 + 非保留值（ISSUE-01）。
        if (!TenantIds.isValidExternal(tenant)) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, fromHeader
                    ? TENANT_HEADER + " 非法（仅 [A-Za-z0-9_-]≤64 且非保留值）"
                    : "dev-default 租户配置非法：" + tenant);
            return;
        }

        try {
            TenantContext.set(tenant);
            String actor = request.getHeader(ACTOR_HEADER);
            if (actor != null && !actor.isBlank()) {
                ActorContext.set(actor); // P1-8 dev 档操作者（可选，四眼开启时才被校验）
            }
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
            ActorContext.clear();
        }
    }
}
