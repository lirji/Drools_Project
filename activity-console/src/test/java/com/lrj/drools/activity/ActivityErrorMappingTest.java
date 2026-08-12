package com.lrj.drools.activity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 异常 → HTTP 的<b>出口</b>体检（R14）。
 *
 * <p>其余用例都在 service 层断言「抛了什么」，而这套映射真正生效的地方是 HTTP 出口——
 * 尤其四眼那条：它此前抛 {@code IllegalStateException}，被 controller 里手抄的
 * {@code catch} 转成 <b>409</b>；改成领域异常之后必须落到 <b>403</b>。
 * 这个差别在 service 层完全看不出来（两种情况都只是"抛了一个异常"），只有打到端点上才能验。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("h2")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:errmapping;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "activity.marketing.seed-demo-data=false",
        "activity.marketing.four-eyes-enabled=true",
        "activity.tenant.dev-default-enabled=true"
})
@DisplayName("写平面错误出口：四眼 403、其余状态码一位不漂")
class ActivityErrorMappingTest {

    @Autowired MockMvc mvc;
    private final ObjectMapper mapper = new ObjectMapper();

    private String createBy(String actor, String name, long spuId) throws Exception {
        long now = System.currentTimeMillis();
        String body = """
                {"activityName":"%s","bizLine":"mall","activityType":1,
                 "activityStartTime":%d,"activityEndTime":%d,
                 "activityAreaType":1,"priority":1,"inventory":100,
                 "redPackageTakeType":1,"redPackageAmount":50,"redPackageAmountUnit":"元",
                 "discountStrategy":"MAX","spuBindings":[{"storeId":1,"spuId":%d}]}
                """.formatted(name, now - 3_600_000L, now + 3_600_000L, spuId);
        String json = mvc.perform(post("/activity-marketing/create")
                        .header("X-Actor", actor)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode node = mapper.readTree(json);
        return node.get("activityId").asText();
    }

    @Test
    @DisplayName("提交人自审自发 → 403（不是 409：重试一万次也不会成，必须换人）")
    void selfPublishIsForbiddenNotConflict() throws Exception {
        String id = createBy("alice", "出口-自审", 96_701L);
        mvc.perform(post("/activity-marketing/" + id + "/status")
                        .header("X-Actor", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1,\"targetStatus\":1}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FOUR_EYES_REQUIRED"))
                // 面向运营的中文文案原样保留：控制台直接把它显示出来
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @DisplayName("换个人审批 → 200（四眼只拦自审，不是把发布拦死）")
    void anotherApproverCanPublish() throws Exception {
        String id = createBy("alice", "出口-他审", 96_702L);
        mvc.perform(post("/activity-marketing/" + id + "/status")
                        .header("X-Actor", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1,\"targetStatus\":1}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("参数非法仍是 400，且响应体仍带 error 字段（前端 errMsg 读的就是它）")
    void invalidArgumentStaysBadRequest() throws Exception {
        mvc.perform(post("/activity-marketing/create")
                        .header("X-Actor", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"activityName\":\"\",\"activityType\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    /**
     * 迁移期保留的 per-endpoint {@code catch} 必须继续生效，否则「统一映射」会顺手改掉一批状态码。
     *
     * <p>这里挑的是 {@code bulk-status}：它对<b>逐条失败</b>返回 200 + 回执，只有
     * {@code targetStatus} 这种<b>整请求</b>参数错误才转 400——两种失败同在一个端点上，
     * 最容易被一刀切的 advice 搅乱。
     */
    @Test
    @DisplayName("既有端点的状态码一位不漂：整请求参数错误仍是 400，逐条失败仍是 200 回执")
    void existingStatusCodesDoNotDrift() throws Exception {
        mvc.perform(post("/activity-marketing/bulk-status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[],\"targetStatus\":3}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());

        mvc.perform(post("/activity-marketing/bulk-status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"activityId\":\"no-such\",\"version\":1}],\"targetStatus\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failed[0].activityId").value("no-such"));

        // 查无此单是正常结果（空清单），不是错误——advice 不该把它变成 4xx
        mvc.perform(get("/activity-marketing/grants").param("orderId", "no-such-order"))
                .andExpect(status().isOk());
    }
}
