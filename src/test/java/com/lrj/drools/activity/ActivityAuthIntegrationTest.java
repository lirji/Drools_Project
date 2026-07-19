package com.lrj.drools.activity;

import com.lrj.drools.activity.tenant.AudienceTenantResolver;
import com.lrj.drools.activity.tenant.AudienceTenantValidator;
import com.lrj.drools.activity.tenant.TenantProperties;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-3 端到端（离线，命脉修正版）：真跑 Spring Security 链 + {@link com.lrj.drools.activity.tenant.JwtTenantFilter} +
 * @TenantId 隔离，用本地 RSA 自签 JWT 代替 Casdoor。token 刻意 {@code owner=admin}（复现实测：Casdoor
 * client_credentials 的 owner 是 admin 非组织），**租户从 aud=activity-{tenant}-cid 解析**。证明：
 *   1) 无 token → 401；2) aud 未知/家族外 → 401（自写校验器挡）；
 *   3) 有效 acme token 建的活动 beta token 看不到（aud→租户隔离）；4) 信封 X-Tenant-Id≠租户 → 403。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("h2")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:authiso;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "activity.marketing.rule-engine.enabled=true",
        "activity.marketing.seed-demo-data=false",
        "activity.tenant.dev-default-enabled=false",
        "activity.tenant.auth.enabled=true",
        "activity.tenant.auth.warmup-enabled=false",
        "activity.tenant.auth.issuer=https://test-issuer",
        "activity.tenant.auth.audience-templates=activity-{tenant}-cid"
})
@Import(ActivityAuthIntegrationTest.TestDecoderConfig.class)
class ActivityAuthIntegrationTest {

    /** 类加载即生成（早于 Spring 上下文），@Bean 与 mint 共用同一密钥。 */
    private static final RSAKey RSA = genRsa();

    private static RSAKey genRsa() {
        try {
            return new RSAKeyGenerator(2048).keyID("test-key").generate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired MockMvc mvc;

    /** 测试 RSA 公钥验签 + 复用生产同款校验器（iss/exp/aud→tenant），@Primary 顶掉走 Casdoor JWKS 的生产 decoder。 */
    @TestConfiguration
    static class TestDecoderConfig {
        @Bean
        @Primary
        JwtDecoder testJwtDecoder(TenantProperties props) throws Exception {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(RSA.toRSAPublicKey()).build();
            AudienceTenantResolver resolver = new AudienceTenantResolver(
                    props.getAuth().getClientTenantMap(), props.getAuth().getAudienceTemplates());
            List<OAuth2TokenValidator<Jwt>> validators = List.of(
                    new JwtTimestampValidator(),
                    new JwtIssuerValidator(props.getAuth().getIssuer()),
                    new AudienceTenantValidator(resolver));
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validators));
            return decoder;
        }
    }

    /** 复现实测：owner=admin（非组织），租户信息在 aud。 */
    private String mint(String aud) {
        JwtEncoder encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(RSA)));
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("https://test-issuer")
                .subject("admin/" + aud)
                .audience(List.of(aud))
                .claim("owner", "admin")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(RSA.getKeyID()).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private String createBody(String name, long spuId) {
        long start = System.currentTimeMillis() - 3_600_000L;
        long end = System.currentTimeMillis() + 3_600_000L;
        return "{\"activityName\":\"" + name + "\",\"bizLine\":\"biz-auth\",\"activityType\":1,"
                + "\"activityStartTime\":" + start + ",\"activityEndTime\":" + end + ","
                + "\"activityAreaType\":1,\"priority\":1,\"inventory\":100,"
                + "\"redPackageTakeType\":1,\"redPackageAmount\":50,\"redPackageAmountUnit\":\"元\",\"discountStrategy\":\"MAX\","
                + "\"spuBindings\":[{\"storeId\":1,\"spuId\":" + spuId + "}]}";
    }

    @Test
    void noToken_unauthorized() throws Exception {
        mvc.perform(get("/activity-marketing/list")).andExpect(status().isUnauthorized());
    }

    @Test
    void unknownAud_unauthorized() throws Exception {
        // aud 解析不到租户（家族外）→ 自写校验器拒 → 401
        String bad = mint("some-evil-client");
        mvc.perform(get("/activity-marketing/list").header("Authorization", "Bearer " + bad))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tenantIsolationOverHttp() throws Exception {
        String acme = mint("activity-acme-cid");
        String beta = mint("activity-beta-cid");

        String created = mvc.perform(post("/activity-marketing/create")
                        .header("Authorization", "Bearer " + acme)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("acme 认证红包", 88101L)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String activityId = created.replaceAll(".*\"activityId\":\"([^\"]+)\".*", "$1");
        assertTrue(activityId.startsWith("ACT"), "应创建成功返回 activityId，实得: " + created);

        // acme 自己看得到
        mvc.perform(get("/activity-marketing/list").header("Authorization", "Bearer " + acme))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(activityId)));

        // beta 看不到（aud→租户驱动隔离）
        mvc.perform(get("/activity-marketing/list").header("Authorization", "Bearer " + beta))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString(activityId))));
    }

    @Test
    void envelopeMismatch_forbidden() throws Exception {
        String acme = mint("activity-acme-cid");
        mvc.perform(get("/activity-marketing/list")
                        .header("Authorization", "Bearer " + acme)
                        .header("X-Tenant-Id", "beta")) // 信封冒充别租户
                .andExpect(status().isForbidden());
    }
}
