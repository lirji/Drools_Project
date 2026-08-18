package com.lrj.drools.activity;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * auth 档未开（默认）时 auth-config 只回 {@code authEnabled=false}，不下发端点/clientId 细节——
 * 前端据此保持 dev/header 租户栏行为一行不变（52-*.md §2）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("h2")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:authcfgoff;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "activity.marketing.seed-catalog-data=false"
})
class AuthConfigDisabledTest {

    @Autowired MockMvc mvc;

    @Test
    void disabledReturnsOnlyFlag() throws Exception {
        mvc.perform(get("/activity-marketing/auth-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authEnabled").value(false))
                .andExpect(jsonPath("$.authorizeEndpoint").doesNotExist())
                .andExpect(jsonPath("$.webClients").doesNotExist());
    }
}
