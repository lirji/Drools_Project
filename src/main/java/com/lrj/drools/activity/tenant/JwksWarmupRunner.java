package com.lrj.drools.activity.tenant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * P1-12：启动时对 Casdoor JWKS 做一次**连通性 + fail-fast 自检**。
 *
 * <p><b>诚实说明</b>：本 runner 用独立 {@code RestTemplate} 拉一次 JWKS 只为**尽早暴露配置错/Casdoor 不可达**（WARN，
 * 不中断启动，届时决策路径 401 = fail-closed），以及确认 app 确实连得上真 Casdoor 密钥。它<strong>不</strong>把密钥灌进
 * {@code NimbusJwtDecoder} 的内部缓存——故第一个真实验签仍会由 decoder 自己拉一次 JWKS（Nimbus 之后自缓存）。
 * “真正预热 decoder 缓存”与“last-good（Casdoor 抖动用旧密钥集）”是 P1-12 剩余项，需自定义 outage-tolerant JWKSource，后续叠加。
 *
 * <p>仅 {@code activity.tenant.auth.enabled=true} 生效；测试置 {@code auth.warmup-enabled=false} 保持无网络依赖。
 */
@Component
@ConditionalOnProperty(name = "activity.tenant.auth.enabled", havingValue = "true")
public class JwksWarmupRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(JwksWarmupRunner.class);

    private final TenantProperties props;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public JwksWarmupRunner(TenantProperties props) {
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        TenantProperties.Auth auth = props.getAuth();
        if (!auth.isWarmupEnabled()) {
            return;
        }
        String uri = auth.getJwkSetUri();
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(auth.getJwksFetchTimeoutMs());
        rf.setReadTimeout(auth.getJwksFetchTimeoutMs());
        try {
            String body = new RestTemplate(rf).getForObject(uri, String.class);
            JsonNode keys = objectMapper.readTree(body == null ? "{}" : body).path("keys");
            int n = keys.isArray() ? keys.size() : 0;
            log.info("[JWKS check] 连通真 Casdoor {}，取到 {} 个签名公钥（仅连通/fail-fast 自检，不预热 decoder 缓存）", uri, n);
        } catch (Exception e) {
            log.warn("[JWKS check] 连不上 {}：{}（Casdoor 抖动时决策路径会 401；last-good 降级属 P1-12 剩余项）",
                    uri, e.toString());
        }
    }
}
