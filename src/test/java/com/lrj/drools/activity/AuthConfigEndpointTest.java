package com.lrj.drools.activity;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsStringIgnoringCase;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 前端 OIDC 配置端点（52-*.md §2）：auth 档下 {@code /activity-marketing/auth-config} **匿名可读**
 * （链一 permitAll + JwtTenantFilter 跳过），只暴露公开 OIDC 参数；其余活动端点仍 401 不受影响。
 * 生产 decoder（warmup 关）构建即用、不发网络请求，故本测试不需要 mock decoder。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("h2")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:authcfg;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "activity.marketing.seed-demo-data=false",
        "activity.tenant.dev-default-enabled=false",
        "activity.tenant.auth.enabled=true",
        "activity.tenant.auth.warmup-enabled=false",
        "activity.tenant.auth.issuer=https://test-issuer",
        "activity.tenant.auth.redirect-uri=http://localhost:8099/index.html",
        "activity.tenant.auth.web-client-map.acme=activity-acme-web-cid",
        "activity.tenant.auth.web-client-map.beta=activity-beta-web-cid"
})
class AuthConfigEndpointTest {

    @Autowired MockMvc mvc;

    @Test
    void anonymousCanReadAuthConfig() throws Exception {
        mvc.perform(get("/activity-marketing/auth-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authEnabled").value(true))
                .andExpect(jsonPath("$.issuer").value("https://test-issuer"))
                .andExpect(jsonPath("$.authorizeEndpoint").value("http://localhost:8000/login/oauth/authorize"))
                .andExpect(jsonPath("$.tokenEndpoint").value("http://localhost:8000/api/login/oauth/access_token"))
                .andExpect(jsonPath("$.redirectUri").value("http://localhost:8099/index.html"))
                .andExpect(jsonPath("$.webClients[?(@.tenant=='acme')].clientId").value("activity-acme-web-cid"))
                .andExpect(jsonPath("$.webClients[?(@.tenant=='beta')].clientId").value("activity-beta-web-cid"));
    }

    @Test
    void noSecretMaterialInResponse() throws Exception {
        // 公有客户端流程：响应里绝不能出现 secret 类字段（公开参数白名单之外零下发）
        mvc.perform(get("/activity-marketing/auth-config"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsStringIgnoringCase("secret"))));
    }

    @Test
    void otherActivityEndpointsStillRequireToken() throws Exception {
        // permitAll 只放 auth-config 一个口子，list 仍 401（防御式回归：口子没开大）
        mvc.perform(get("/activity-marketing/list")).andExpect(status().isUnauthorized());
    }
}
