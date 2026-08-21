package com.lrj.drools.activity.tenant;

import org.hibernate.cfg.MultiTenancySettings;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * P0-4 多租户装配：把租户解析器接进 Hibernate + 注册活动接口的租户过滤器。
 *
 * <p>不依赖 Spring Boot 的自动多租户探测（那条路为 DATABASE/SCHEMA 策略设计、要 ConnectionProvider）。
 * 判别式（{@code @TenantId}）策略只需一个 {@link org.hibernate.context.spi.CurrentTenantIdentifierResolver}，
 * 这里用 {@link HibernatePropertiesCustomizer} 显式塞进属性（key 用编译期常量，塞错=静默失效=最坏，故用常量兜底）。
 */
@Configuration
@EnableConfigurationProperties(TenantProperties.class)
public class MultiTenancyConfig {

    /** 把租户解析器注入 Hibernate 属性，激活判别式多租户（对所有 {@code @TenantId} 实体生效）。 */
    @Bean
    HibernatePropertiesCustomizer tenantIdentifierResolverCustomizer(TenantIdentifierResolver resolver) {
        return props -> props.put(MultiTenancySettings.MULTI_TENANT_IDENTIFIER_RESOLVER, resolver);
    }

    /**
     * 在<b>两个平面</b>上做租户来源解析 + fail-closed：写平面 {@code /activity-marketing/*}
     * 与决策平面 {@code /decision/v1/*}；不波及 Step 1~18 其它接口。
     * 仅在 <b>未开 Casdoor 鉴权</b>时启用（header 来源）；开启 {@code auth.enabled} 后来源改为 {@link JwtTenantFilter}
     * 从 token 的 aud 解析，此 header 过滤器让位（避免两处都 set 租户）。
     *
     * <p><b>为什么这里曾经漏了决策平面</b>：本过滤器写于 P0-4，当时只有 {@code /activity-marketing/*}；
     * M1.1 新增决策平面 {@code /decision/v1/*} 时没有同步扩这里的 URL 模式。
     * 后果是 header 档下决策平面**完全不解析租户**——{@code X-Tenant-Id} 被静默忽略，
     * 所有请求都落到 {@link TenantIdentifierResolver} 的兜底（dev-default 或 NO_TENANT），
     * 即 A 租户查到的是 dev-default 租户的活动。auth 档不受影响（{@link JwtTenantFilter}
     * 挂在同时匹配两个平面的安全链上）。
     *
     * <p>这条由 docker 端到端验证发现：单元测试全都跑在 dev-default 下，恰好绕过了这个缺口。
     * 回归由 {@code DecisionTenantHeaderTest} 钉死（无 header 且关掉 dev-default 时必须 403）。
     */
    @Bean
    @ConditionalOnProperty(name = "activity.tenant.auth.enabled", havingValue = "false", matchIfMissing = true)
    FilterRegistrationBean<TenantContextFilter> tenantContextFilter(TenantProperties props) {
        FilterRegistrationBean<TenantContextFilter> reg = new FilterRegistrationBean<>(new TenantContextFilter(props));
        reg.addUrlPatterns("/activity-marketing/*", "/activity-awards/*", "/decision/v1/*");
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        reg.setName("tenantContextFilter");
        return reg;
    }
}
