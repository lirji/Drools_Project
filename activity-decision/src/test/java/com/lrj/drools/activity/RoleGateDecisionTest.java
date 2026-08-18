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
 * M2 角色门控 · decision 角色（{@link com.lrj.drools.activity.tenant.RoleGateFilter}）：
 * 只服务 {@code /decision/v1/**} + actuator，屏蔽写面/Step/静态页（404）。
 * 「同一 artifact 按 activity.role 扮演决策服务」的可测证据——kill console 决策仍在的基础。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("h2")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:roledecision;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "activity.marketing.seed-catalog-data=false",
        "activity.role=decision"
})
class RoleGateDecisionTest {

    @Autowired MockMvc mvc;
    private static final String BODY =
            "{\"spuIdList\":[9001],\"userId\":1,\"userDistrictId\":null,\"userTags\":[],\"orderAmount\":200,\"quantity\":1}";

    @Test
    void decisionEndpointServed() throws Exception {
        mvc.perform(post("/decision/v1/spu-discount").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk());
    }

    @Test
    void consoleWriteFaceBlocked404() throws Exception {
        mvc.perform(get("/activity-marketing/list")).andExpect(status().isNotFound());
    }

    @Test
    void stepEndpointBlocked404() throws Exception {
        mvc.perform(post("/hello").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
    }
}
