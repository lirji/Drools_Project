package com.lrj.drools.activity.config;

import com.lrj.drools.activity.service.WebhookGrantEventDispatcher;
import com.lrj.drools.activity.spi.GrantEventDispatcher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 发放传播 outbox 装配。<b>本配置无条件生效</b>，仅绑定 {@link GrantOutboxProperties}，使
 * {@code GrantService} 无论门控开关如何都能注入属性（读到 {@code enabled=false} 即跳过入队）。
 *
 * <p><b>webhook dispatcher 是条件装配</b>：仅当 {@code activity.grant-outbox.webhook-url} 非空时，
 * 注册 {@code @Primary WebhookGrantEventDispatcher} 覆盖默认 {@code LoggingGrantEventDispatcher}
 * （后者始终作为 {@code @Component} 存在，是未配 webhook 时的退化通道）。这套「默认 logging + 可
 * @Primary 覆盖」与 recon {@code AlertDispatcherConfig} 同构。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GrantOutboxProperties.class)
public class GrantOutboxConfig {

    // ⚠️ 用 @ConditionalOnExpression 判「非空白」而非 @ConditionalOnProperty(name=...)：后者把**空字符串**也算「已设置」
    //    会匹配（application.yml 里 webhook-url 默认注入 ""），导致未配 webhook 时仍装配 @Primary webhook bean，与
    //    测试/其它 @Primary 冲突。这里显式要求 trim 后非空才创建。

    /** 仅在配了非空 webhook-url 时创建：投递用 {@link RestClient}，超时有界（挂了不拖住中继线程）。 */
    @Bean
    @ConditionalOnExpression("'${activity.grant-outbox.webhook-url:}'.trim().length() > 0")
    public RestClient grantOutboxRestClient(GrantOutboxProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.getWebhookTimeoutMs());
        factory.setReadTimeout(props.getWebhookTimeoutMs());
        return RestClient.builder().requestFactory(factory).build();
    }

    /** 配了非空 webhook-url → @Primary 覆盖 logging 默认；否则不创建，注入点回落到 LoggingGrantEventDispatcher。 */
    @Bean
    @Primary
    @ConditionalOnExpression("'${activity.grant-outbox.webhook-url:}'.trim().length() > 0")
    public GrantEventDispatcher webhookGrantEventDispatcher(RestClient grantOutboxRestClient,
                                                            GrantOutboxProperties props) {
        return new WebhookGrantEventDispatcher(grantOutboxRestClient, props);
    }
}
