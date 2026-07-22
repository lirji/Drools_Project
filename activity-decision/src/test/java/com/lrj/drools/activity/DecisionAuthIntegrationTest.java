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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 证明独立 decision 服务启用 auth 后也会验签、校验 audience 并拒绝租户信封冒充。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("h2")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:decisionauth;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "activity.marketing.seed-demo-data=false",
        "activity.tenant.dev-default-enabled=false",
        "activity.tenant.auth.enabled=true",
        "activity.tenant.auth.warmup-enabled=false",
        "activity.tenant.auth.issuer=https://test-issuer",
        "activity.tenant.auth.audience-templates=activity-{tenant}-cid"
})
@Import(DecisionAuthIntegrationTest.TestDecoderConfig.class)
class DecisionAuthIntegrationTest {

    private static final RSAKey RSA = generateRsa();
    private static final String BODY =
            "{\"spuIdList\":[9001],\"userId\":1,\"userDistrictId\":null,\"userTags\":[],\"orderAmount\":200,\"quantity\":1}";

    @Autowired MockMvc mvc;

    private static RSAKey generateRsa() {
        try {
            return new RSAKeyGenerator(2048).keyID("decision-test-key").generate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

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

    private String mint(String audience) {
        JwtEncoder encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(RSA)));
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("https://test-issuer")
                .subject("admin/" + audience)
                .audience(List.of(audience))
                .claim("owner", "admin")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(RSA.getKeyID()).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request() {
        return post("/decision/v1/spu-discount")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY);
    }

    @Test
    void noTokenIsUnauthorized() throws Exception {
        mvc.perform(request()).andExpect(status().isUnauthorized());
    }

    @Test
    void unknownAudienceIsUnauthorized() throws Exception {
        mvc.perform(request().header("Authorization", "Bearer " + mint("untrusted-client")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void knownTenantAudienceCanCallDecisionApi() throws Exception {
        mvc.perform(request().header("Authorization", "Bearer " + mint("activity-acme-cid")))
                .andExpect(status().isOk());
    }

    @Test
    void tenantEnvelopeMismatchIsForbidden() throws Exception {
        mvc.perform(request()
                        .header("Authorization", "Bearer " + mint("activity-acme-cid"))
                        .header("X-Tenant-Id", "beta"))
                .andExpect(status().isForbidden());
    }
}
