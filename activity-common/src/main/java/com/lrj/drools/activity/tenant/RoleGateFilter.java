package com.lrj.drools.activity.tenant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * M2 角色门控：让**同一个 artifact** 按 {@code activity.role} 扮演「决策服务」或「控制台服务」，无需先做 Maven 物理拆分
 * 即可展示微服务化的核心价值——读写平面独立部署、独立生命周期（决策 D1 的低风险落地路径）。
 *
 * <ul>
 *   <li>{@code all}（默认）：不装此 filter（{@link ConditionalOnProperty}），全端点开放——本地开发与 104 测试行为不变；</li>
 *   <li>{@code decision}：只放行 {@code /decision/v1/**} + {@code /actuator/**}；其余（{@code /activity-marketing/**} 写面、
 *       Step1~18、静态页）一律 404。决策服务据此只承载无写的决策热路径；</li>
 *   <li>{@code console}：屏蔽 {@code /decision/v1/**}（交给决策服务）；其余（写面 + Step1~18 + SPA + auth-config）放行。</li>
 * </ul>
 *
 * <p>compose 里 nginx 网关把 {@code /api/decision/*}→decision 实例、{@code /api/console/*}+{@code /ui/*}→console 实例，
 * 于是「kill 掉 console 实例，决策 API 仍正确服务」可当场展示（拆分价值验收）。这不是安全边界（同 artifact），
 * 是**部署角色边界**；真安全隔离仍靠 Casdoor 验签 + {@code @TenantId}。
 *
 * <p>只在 {@code activity.role} 被显式设置时注册（{@link ConditionalOnProperty}）——默认不设=无此 filter，
 * 本地开发与 104 测试零行为改变；设为 {@code all} 也会注册但 {@link #shouldNotFilter} 全旁路。
 */
@Component
@Order(1) // 早于 tenant/auth filter：非本角色路径直接 404，不进后续链
@ConditionalOnProperty(name = "activity.role")
public class RoleGateFilter extends OncePerRequestFilter {

    private final String role;

    RoleGateFilter(@Value("${activity.role:all}") String role) {
        this.role = role == null ? "all" : role.trim().toLowerCase();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return "all".equals(role); // all 角色下彻底旁路
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        boolean isDecision = path.startsWith("/decision/");
        boolean isActuator = path.startsWith("/actuator/");

        if ("decision".equals(role)) {
            // 决策服务：只服务决策端点 + 健康检查，其余 404（不暴露写面/Step/静态页）
            if (!isDecision && !isActuator) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND, "decision 角色不提供此端点");
                return;
            }
        } else if ("console".equals(role)) {
            // 控制台服务：决策端点交给决策实例，这里 404
            if (isDecision) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND, "console 角色不提供决策端点，请走决策服务");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
