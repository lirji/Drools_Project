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
        "activity.marketing.seed-catalog-data=false",
        "activity.tenant.dev-default-enabled=false",
        "activity.tenant.auth.enabled=true",
        "activity.tenant.auth.warmup-enabled=false",
        "activity.tenant.auth.issuer=https://test-issuer",
        "activity.tenant.auth.audience-templates=activity-{tenant}-cid",
        "activity.tenant.auth.console-write-authority=SCOPE_activity.write"
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
        return mint(aud, false);
    }

    private String mint(String aud, boolean writer) {
        JwtEncoder encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(RSA)));
        Instant now = Instant.now();
        JwtClaimsSet.Builder builder = JwtClaimsSet.builder()
                .issuer("https://test-issuer")
                .subject("admin/" + aud)
                .audience(List.of(aud))
                .claim("owner", "admin")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300));
        if (writer) builder.claim("scope", "activity.write");
        JwtClaimsSet claims = builder.build();
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
    void addOnValidationAliasWithoutToken_unauthorized() throws Exception {
        mvc.perform(post("/activity-marketing/addon/options")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"spuIdList\":[990011],\"userTags\":[],\"orderAmount\":200,\"quantity\":1}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void addOnQuoteAliasWithoutToken_unauthorized() throws Exception {
        // 两阶段的第二阶段同样必须拦在门外——quote 会返回权威价格，比 options 更不能匿名读
        mvc.perform(post("/activity-marketing/addon/quote")
                        .param("activityId", "ACT-X").param("item", "保温杯")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"spuIdList\":[990011],\"userTags\":[],\"orderAmount\":200,\"quantity\":1}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void addOnAliasTenantIsolation() throws Exception {
        // 别名挂在 /activity-marketing/* 下，租户隔离必须与其它端点同规格：
        // beta 的 token 打 acme 的加价购活动，应看不到任何选项（隔离到空，而不是 403 泄漏存在性）。
        String acme = mint("activity-acme-cid", true);
        long spuId = 990500L;
        mvc.perform(post("/activity-marketing/create")
                        .header("Authorization", "Bearer " + acme)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + java.util.UUID.randomUUID() + "\",\"activityName\":\"acme 加价购\","
                                + "\"activityType\":6,\"activityStartTime\":" + (System.currentTimeMillis() - 3600_000)
                                + ",\"activityEndTime\":" + (System.currentTimeMillis() + 86400_000)
                                + ",\"activityAreaType\":1,\"priority\":1,\"discountStrategy\":\"MAX\","
                                + "\"spuBindings\":[{\"storeId\":1,\"spuId\":" + spuId + "}],"
                                + "\"gifts\":[{\"giftName\":\"保温杯\",\"giftNum\":1,\"absoluteAmount\":9.9,\"rightType\":\"ADD_ON\"}]}"))
                .andExpect(status().isOk());

        String beta = mint("activity-beta-cid");
        mvc.perform(post("/activity-marketing/addon/options")
                        .header("Authorization", "Bearer " + beta)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"spuIdList\":[" + spuId + "],\"userTags\":[],\"orderAmount\":200,\"quantity\":1}"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("acme 加价购"))));
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
        String acme = mint("activity-acme-cid", true);
        String beta = mint("activity-beta-cid");

        String created = mvc.perform(post("/activity-marketing/create")
                        .header("Authorization", "Bearer " + acme)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("acme 认证红包", 88101L)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        // 用真解析取字段，不要正则切 JSON：响应体加字段（如 P0-4/D12-3 的 warnings）就会让正则悄悄截错，
        // 而失败信息会指向"租户隔离断言失败"这种完全不相干的地方。
        String activityId = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(created).get("activityId").asText();
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
    void claimRequiresConfiguredWriteAuthority() throws Exception {
        String reader = mint("activity-acme-cid");
        mvc.perform(post("/activity-marketing/ACT-NOT-FOUND/claim")
                        .header("Authorization", "Bearer " + reader))
                .andExpect(status().isForbidden());

        String writer = mint("activity-acme-cid", true);
        mvc.perform(post("/activity-marketing/ACT-NOT-FOUND/claim")
                        .header("Authorization", "Bearer " + writer))
                // 通过鉴权后才到业务层；活动不存在 → 404（R13 起 claim 按失败种类分流：
                // 缺参 400 / 查无此活动 404 / 抢不到 409，此前一律 409）。
                .andExpect(status().isNotFound());
    }

    /**
     * bulk-status 是两段路径，{@code /activity-marketing/*}{@code /status} 那条 ant 模式罩不住它——
     * 这个洞的实际含义是「纯决策 M2M token 可批量上下线全租户活动」，比漏掉单条 status 更重。
     * 这条用例存在的意义：将来有人重排 requestMatchers 时，少列 bulk-status 这一行就会红。
     */
    @Test
    void bulkStatusRequiresConfiguredWriteAuthority() throws Exception {
        String body = "{\"items\":[{\"activityId\":\"ACT-NOT-FOUND\",\"version\":1}],\"targetStatus\":0}";

        String reader = mint("activity-acme-cid");
        mvc.perform(post("/activity-marketing/bulk-status")
                        .header("Authorization", "Bearer " + reader)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());

        String writer = mint("activity-acme-cid", true);
        mvc.perform(post("/activity-marketing/bulk-status")
                        .header("Authorization", "Bearer " + writer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                // 鉴权放行后才进业务层；bulk 的部分失败契约是一律 200 + failed[] 回执。
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("ACT-NOT-FOUND")));
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
