package com.lrj.drools.activity;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M1.1 决策别名 {@code /decision/v1/*} 与决策平面契约。此测试只验「别名端点存在且与控制台读端点走同一决策服务」——
 * 具体折扣数值由 {@link ActivityMarketingFlowTest} 等覆盖，这里只证路由通、请求体形状一致。
 * 角色门控（decision/console）的 404 行为见 {@link RoleGateTest}（另起 context，需设 activity.role）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("h2")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:decalias;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "activity.marketing.seed-demo-data=false"
})
class DecisionAliasAndRoleGateTest {

    @Autowired MockMvc mvc;

    private static final String BODY =
            "{\"spuIdList\":[9001],\"userId\":1,\"userDistrictId\":null,\"userTags\":[],\"orderAmount\":200,\"quantity\":1}";

    @Test
    void decisionSpuDiscountAliasReturns200() throws Exception {
        // 无命中活动也返回 200（决策视图，hit=false）——证别名端点接通 ActivityQueryService
        mvc.perform(post("/decision/v1/spu-discount").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk());
    }

    @Test
    void decisionGiftsAliasReturns200() throws Exception {
        mvc.perform(post("/decision/v1/gifts").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk());
    }

    @Test
    void consolePathStillWorks() throws Exception {
        // 旧控制台读端点保留、不弃用
        mvc.perform(post("/activity-marketing/spu-discount").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk());
    }
}
