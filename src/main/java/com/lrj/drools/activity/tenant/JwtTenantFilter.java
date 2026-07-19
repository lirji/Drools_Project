package com.lrj.drools.activity.tenant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * P0-3 决策平面的租户来源：从**已验签的 JWT 的 {@code aud}（client_id）**解析租户写进 {@link TenantContext}。
 *
 * <p>命脉修正：实测 Casdoor client_credentials 的 {@code owner}=admin（非组织），故租户改从 {@code aud} 解析
 * （{@link AudienceTenantResolver}：client→tenant 映射 / {@code activity-{tenant}-cid} 家族反解）。这是 P0-4「来源接缝」
 * 的兑现：P0-4 来源是 header，这里换成"验证过的 aud→tenant"，下游 {@link TenantIdentifierResolver} + {@code @TenantId} 机制一行不动。
 *
 * <p><b>信封 tenantId 只作校验、绝不作来源</b>（SEC-3 / D1）：请求带 {@code X-Tenant-Id} 时必须等于从 token 解析出的租户，
 * 否则 <b>403</b>。解析不出租户（正常已被 {@link AudienceTenantValidator} 401 挡）→ 防御式放行、不落租户（resolver 兜底哨兵）。
 */
public class JwtTenantFilter extends OncePerRequestFilter {

    private final AudienceTenantResolver resolver;

    public JwtTenantFilter(AudienceTenantResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<String> tenantOpt = currentTenant();
        if (tenantOpt.isEmpty()) {
            // 正常不会到这（未知/歧义 aud 已被 AudienceTenantValidator 401）。防御式 **fail-closed 拒绝**（非放行），
            // 防止 matcher/decoder/顺序变化后回落哨兵。请求线程未 set 租户，无需清理。
            TenantContext.clear();
            ActorContext.clear();
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "无法从 token 确定租户，拒绝");
            return;
        }
        String tenant = tenantOpt.get();

        String envelope = request.getHeader(TenantContextFilter.TENANT_HEADER);
        if (envelope != null && !envelope.isBlank() && !envelope.equals(tenant)) {
            TenantContext.clear(); // 防御：拒绝前清掉线程可能残留的上下文
            ActorContext.clear();
            response.sendError(HttpServletResponse.SC_FORBIDDEN,
                    TenantContextFilter.TENANT_HEADER + " 与 token 租户不一致：租户只认 token 的 aud，拒绝跨租户信封");
            return;
        }

        try {
            TenantContext.set(tenant);
            setActorFromJwt(); // P1-8：auth 档操作者身份 = JWT sub（四眼开启时校验审批人≠提交人）
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
            ActorContext.clear();
        }
    }

    /** 从已验签 JWT 的 {@code sub} 取操作者身份落进 {@link ActorContext}。 */
    private void setActorFromJwt() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            String sub = jwtAuth.getToken().getSubject();
            if (sub != null && !sub.isBlank()) {
                ActorContext.set(sub);
            }
        }
    }

    private Optional<String> currentTenant() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            Jwt jwt = jwtAuth.getToken();
            return resolver.resolve(jwt);
        }
        return Optional.empty();
    }
}
