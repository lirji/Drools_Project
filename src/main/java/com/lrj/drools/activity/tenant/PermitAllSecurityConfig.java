package com.lrj.drools.activity.tenant;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 默认（{@code activity.tenant.auth.enabled} 未开）安全链——**全放行**。
 *
 * <p>引入 {@code spring-boot-starter-oauth2-resource-server} 会把 Spring Security 带上 classpath，
 * 若不显式给一条 filter chain，Boot 自动配置会用生成密码把**所有**端点锁死，直接打断本 demo（Step1~18、
 * 静态页、h2-console）。这条 permitAll 链保证「不开 Casdoor 鉴权时，demo 行为与 P0-4 完全一致」——
 * 租户隔离仍由 {@code TenantContextFilter}（header）+ {@code @TenantId} 机制托底，不依赖 HTTP 鉴权。
 */
@Configuration
@ConditionalOnProperty(name = "activity.tenant.auth.enabled", havingValue = "false", matchIfMissing = true)
public class PermitAllSecurityConfig {

    @Bean
    public SecurityFilterChain permitAllFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .headers(h -> h.frameOptions(frame -> frame.disable())) // h2-console
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
