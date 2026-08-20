package com.lrj.drools.activity.tenant;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Casdoor 控制台与决策平面鉴权（仅 {@code activity.tenant.auth.enabled=true} 时生效）。
 *
 * <p>保护 {@code /activity-marketing/**}（控制台接口）和 {@code /decision/v1/**}（决策接口）：需带 Casdoor 验签 JWT；其余（Step1~18、
 * 静态页、actuator、h2-console）按配置放行。验签后 {@link JwtTenantFilter}
 * 从 token 的 {@code aud} 解析租户落进 {@link TenantContext}，接上 P0-4 的 {@code @TenantId} 隔离机制。
 *
 * <p>JWT 校验链：JWKS 验签 + {@link JwtTimestampValidator}（exp）+ {@link JwtIssuerValidator}（iss）+
 * **自写 {@link AudienceTenantValidator}**（aud 必须解析到已知租户、家族外拒，不抄参考的默认空 aud）。
 */
@Configuration
@ConditionalOnProperty(name = "activity.tenant.auth.enabled", havingValue = "true")
public class ActivityResourceServerConfig {

    /**
     * 链一（@Order 1）：匹配控制台与决策平面——需 JWT 验签，且 {@link JwtTenantFilter} 只挂在此链，
     * 故其 fail-closed(403) 不波及 health/其它 Step/静态页（那些走链二）。
     */
    @Bean
    @Order(1)
    public SecurityFilterChain activitySecurityFilterChain(HttpSecurity http, AudienceTenantResolver tenantResolver,
                                                           TenantProperties props) throws Exception {
        String writeAuthority = props.getAuth().getConsoleWriteAuthority();
        http
                .securityMatcher("/activity-marketing/**", "/decision/v1/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> {
                    // 前端 OIDC 配置端点匿名可读（只暴露公开参数；JwtTenantFilter 同步跳过此路径，见 shouldNotFilter）
                    auth.requestMatchers(HttpMethod.GET, AuthConfigController.PATH).permitAll();
                    // P1-k：配了 console-write-authority 时，所有会改状态的运营端点要求该权限。
                    // bulk-status 是两段路径，`*/status` 那条模式匹配不到——漏了它，纯决策 M2M token
                    // 就能批量上下线全租户活动，而且比单条 status 危害更大。新增写端点必须同步补这张表。
                    if (StringUtils.hasText(writeAuthority)) {
                        auth.requestMatchers(HttpMethod.POST,
                                        "/activity-marketing/create",
                                        "/activity-marketing/*/status",
                                        "/activity-marketing/bulk-status",
                                        "/activity-marketing/*/claim",
                                        // confirm 会**确认发放并落金额**（写 amount + 追 ISSUE 分录，进对账账本）——
                                        // 不设防的话，纯决策 M2M token 就能确认任意订单的发放 = 越权改账。
                                        "/activity-marketing/*/confirm",
                                        // release 会**把库存加回去**并解除该用户的限领占用——
                                        // 不设防的话，反复调它就能把一个限量活动的库存刷到任意大。
                                        "/activity-marketing/*/release",
                                        // 决策平面上唯一的**写动作**：快照回滚会立刻改变这条业务线
                                        // 每一次决策实际发出去的钱（切回上一代物料）。它不写数据库，
                                        // 但它跟 status/claim 一样是运营级操作，用同一个权限守。
                                        "/decision/v1/snapshot/rollback")
                                .hasAuthority(writeAuthority);
                    }
                    auth.anyRequest().authenticated();
                })
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(activityJwtAuthConverter())))
                // 验签+鉴权通过后，从 token 的 aud 解析租户（放在授权之后：只对已放行的请求落租户）
                .addFilterAfter(new JwtTenantFilter(tenantResolver), AuthorizationFilter.class);
        return http.build();
    }

    /**
     * 链二（@Order 2，兜底匹配其余全部）：health / actuator / Step1~18 / 静态页 / h2-console 一律放行。
     * auth 开启时只保护控制台与决策业务路径，其余能力端点按现有边界开放。
     */
    @Bean
    @Order(2)
    public SecurityFilterChain activityOpenFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .headers(h -> h.frameOptions(frame -> frame.disable())) // h2-console（dev）
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    /** Casdoor claim → 权限：{@code scope/scp} → {@code SCOPE_*}（默认转换器）+ {@code groups} 归一化（末段）为权限。 */
    private JwtAuthenticationConverter activityJwtAuthConverter() {
        JwtGrantedAuthoritiesConverter scopes = new JwtGrantedAuthoritiesConverter();
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            Collection<GrantedAuthority> authorities = new ArrayList<>(scopes.convert(jwt));
            Object groups = jwt.getClaim("groups");
            if (groups instanceof Collection<?> col) {
                for (Object g : col) {
                    String s = String.valueOf(g);
                    int i = s.lastIndexOf('/');
                    authorities.add(new SimpleGrantedAuthority(i >= 0 ? s.substring(i + 1) : s));
                }
            }
            return authorities;
        });
        return converter;
    }

    /**
     * aud→tenant 解析器（client→tenant 映射优先，activity-{tenant}-cid 家族反解兜底）。校验器与来源过滤器共用。
     * <p>{@code webClientMap}（tenant→SPA client_id）的**反向**自动并入 map 级：SPA 应用命名带 {@code -web-}，
     * 若落到模板兜底会被误反解成租户 {@code <tenant>-web}，必须 map 短路；自动并入也免去两处配置漂移。
     * 显式 {@code clientTenantMap} 后放（同 key 时以显式配置为准）。
     */
    @Bean
    public AudienceTenantResolver audienceTenantResolver(TenantProperties props) {
        TenantProperties.Auth auth = props.getAuth();
        java.util.Map<String, String> merged = new java.util.LinkedHashMap<>();
        auth.getWebClientMap().forEach((tenant, clientId) -> merged.put(clientId, tenant));
        merged.putAll(auth.getClientTenantMap());
        return new AudienceTenantResolver(merged, auth.getAudienceTemplates());
    }

    /**
     * JWKS 本地验签 + iss/exp + 自写 aud→tenant 校验（aud 必须解析到已知租户）。
     * P1-12：有界超时 + **last-good** —— Casdoor 抖动/轮转期用上次成功密钥集验签，不阻塞热路径、不误 401（{@link OutageTolerantJwks}）。
     */
    @Bean
    public JwtDecoder activityJwtDecoder(TenantProperties props, AudienceTenantResolver tenantResolver) {
        TenantProperties.Auth auth = props.getAuth();
        List<OAuth2TokenValidator<Jwt>> validators = List.of(
                new JwtTimestampValidator(),
                new JwtIssuerValidator(auth.getIssuer()),
                new AudienceTenantValidator(tenantResolver));
        return OutageTolerantJwks.decoder(
                auth.getJwkSetUri(), auth.getJwksFetchTimeoutMs(),
                auth.getJwksCacheTtlMs(), auth.getJwksOutageTtlMs(), validators);
    }
}
