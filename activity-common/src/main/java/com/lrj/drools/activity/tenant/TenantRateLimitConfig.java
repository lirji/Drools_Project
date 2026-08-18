package com.lrj.drools.activity.tenant;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * P1-13：把 {@link TenantQuotaService} 挂到 {@code /activity-marketing/**} 上做每租户限流。
 * 仅 {@code activity.tenant.quota.enabled=true} 时注册（默认不挂，零开销）。
 * 拦截器在 DispatcherServlet 阶段跑，此时租户来源过滤器（header 或 JWT aud）已把 {@link TenantContext} 落好。
 */
@Configuration
@ConditionalOnProperty(name = "activity.tenant.quota.enabled", havingValue = "true")
public class TenantRateLimitConfig implements WebMvcConfigurer {

    private final TenantQuotaService quota;

    public TenantRateLimitConfig(TenantQuotaService quota) {
        this.quota = quota;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new QuotaInterceptor(quota)).addPathPatterns("/activity-marketing/**");
    }

    /** 超配额 → 429（不静默丢），放行 → 继续。 */
    private static final class QuotaInterceptor implements HandlerInterceptor {
        private final TenantQuotaService quota;

        QuotaInterceptor(TenantQuotaService quota) { this.quota = quota; }

        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
                throws Exception {
            String tenant = TenantContext.get();
            if (!quota.tryAcquire(tenant)) {
                response.setStatus(429); // Too Many Requests
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"error\":\"tenant rate limited\"}");
                return false;
            }
            return true;
        }
    }
}
