package com.lrj.drools.activity.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AwardIntentConnectorProperties.class)
public class AwardIntentConnectorConfig {
    @Bean("benefitCenterRestClient")
    RestClient benefitCenterRestClient(AwardIntentConnectorProperties properties) {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(3000);
        requestFactory.setReadTimeout(5000);
        return RestClient.builder().baseUrl(properties.getBenefitCenterUrl()).requestFactory(requestFactory).build();
    }
}
