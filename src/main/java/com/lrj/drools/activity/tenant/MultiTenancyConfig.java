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
     * 只在 /activity-marketing/* 上做租户来源解析 + fail-closed；不波及 Step 1~18 其它接口。
     * 仅在 <b>未开 Casdoor 鉴权</b>时启用（header 来源）；开启 {@code auth.enabled} 后来源改为 {@link JwtTenantFilter}
     * 从 token 的 aud 解析，此 header 过滤器让位（避免两处都 set 租户）。
     */
    @Bean
    @ConditionalOnProperty(name = "activity.tenant.auth.enabled", havingValue = "false", matchIfMissing = true)
    FilterRegistrationBean<TenantContextFilter> tenantContextFilter(TenantProperties props) {
        FilterRegistrationBean<TenantContextFilter> reg = new FilterRegistrationBean<>(new TenantContextFilter(props));
        reg.addUrlPatterns("/activity-marketing/*");
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        reg.setName("tenantContextFilter");
        return reg;
    }
}
