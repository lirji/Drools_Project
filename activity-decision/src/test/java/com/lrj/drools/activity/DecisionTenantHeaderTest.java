package com.lrj.drools.activity;

import org.junit.jupiter.api.DisplayName;
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
 * 决策平面必须与写平面一样做租户解析（header 档）。
 *
 * <p><b>这条测试的来历</b>：docker 端到端验证时发现，带 {@code X-Tenant-Id: acme} 调
 * {@code /decision/v1/spu-discount} 查不到 acme 刚上线的活动，而同样的请求打到
 * {@code /activity-marketing/spu-discount}（写平面的读端点）却正常命中。
 * 追下去是 {@code MultiTenancyConfig} 里 {@code TenantContextFilter} 的 URL 模式
 * 只写了 {@code /activity-marketing/*}——决策平面是 M1.1 才加的，模式没同步扩。
 * 后果：header 档下决策平面**完全不解析租户**，{@code X-Tenant-Id} 被静默忽略，
 * 所有请求都落到兜底租户；跨租户读到别人的活动，而且没有任何报错。
 *
 * <p><b>为什么此前没被测出来</b>：decision 模块既有的测试都跑在 {@code dev-default-enabled=true}
 * 且不带 header 的前提下，恰好与「过滤器没生效」的表现一致——两种情况都解析成 dev-default，
 * 断言看不出差别。所以这里刻意**关掉 dev-default**：过滤器生效时缺 header 必须 403（fail-closed），
 * 过滤器没生效时会一路放行返回 200。403 就是「过滤器确实挂在这条路径上」的证据。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("h2")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:dectenant;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "activity.marketing.seed-demo-data=false",
        // 关掉 dev-default：缺租户就必须拒，这样才能区分「解析成默认」与「压根没解析」
        "activity.tenant.dev-default-enabled=false"
})
@DisplayName("决策平面：租户解析必须覆盖 /decision/v1/*")
class DecisionTenantHeaderTest {

    private static final String BODY =
            "{\"spuIdList\":[9001],\"userId\":1,\"userTags\":[],\"orderAmount\":200,\"quantity\":1}";

    @Autowired MockMvc mvc;

    @Test
    @DisplayName("缺 X-Tenant-Id → 403 fail-closed（证明过滤器挂在这条路径上）")
    void missingTenantHeaderIsRejected() throws Exception {
        mvc.perform(post("/decision/v1/spu-discount").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("缺 X-Tenant-Id → 买赠端点同样 403")
    void missingTenantHeaderRejectedOnGifts() throws Exception {
        mvc.perform(post("/decision/v1/gifts").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("带合法 X-Tenant-Id → 正常放行")
    void validTenantHeaderPasses() throws Exception {
        mvc.perform(post("/decision/v1/spu-discount")
                        .header("X-Tenant-Id", "acme")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("非法 X-Tenant-Id → 400，不得被当成合法租户")
    void malformedTenantHeaderIsRejected() throws Exception {
        mvc.perform(post("/decision/v1/spu-discount")
                        .header("X-Tenant-Id", "bad tenant!")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest());
    }
}
