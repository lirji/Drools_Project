package com.lrj.drools.activity;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M2 角色门控 · console 角色：服务写面 + Step + SPA，屏蔽 {@code /decision/v1/**}（交给决策服务，404）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("h2")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:roleconsole;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "activity.marketing.seed-catalog-data=false",
        "activity.role=console"
})
class RoleGateConsoleTest {

    @Autowired MockMvc mvc;

    @Test
    void consoleServesWriteFace() throws Exception {
        mvc.perform(get("/activity-marketing/list")).andExpect(status().isOk());
    }

    @Test
    void decisionEndpointBlocked404() throws Exception {
        mvc.perform(post("/decision/v1/spu-discount").contentType(MediaType.APPLICATION_JSON)
                .content("{\"spuIdList\":[1],\"userId\":1,\"userDistrictId\":null,\"userTags\":[],\"orderAmount\":1,\"quantity\":1}"))
                .andExpect(status().isNotFound());
    }
}
