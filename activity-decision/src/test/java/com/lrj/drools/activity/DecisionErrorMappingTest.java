package com.lrj.drools.activity;

import com.lrj.drools.activity.domain.DecisionMode;
import com.lrj.drools.activity.domain.SpuDiscountRequest;
import com.lrj.drools.activity.service.ActivityQueryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 决策平面的错误出口（R14）。这里守的是<b>两个方向的误判</b>，它们的代价不对称但都很贵：
 *
 * <ul>
 *   <li><b>别把 bug 报成 400。</b>决策服务只读、入参极简，它抛出的 {@code IllegalArgumentException}
 *       只可能来自脏数据或真 bug。若照 console 的样子加一条 {@code IAE → 400} 兜底，
 *       这些故障就会伪装成客户端错误：告警不响、调用方去改自己那条没问题的请求、
 *       真正的脏数据继续留在库里影响发钱。所以此处必须是 <b>500 + code=INTERNAL</b>。</li>
 *   <li><b>也别把 400 吞成 500。</b>请求体不是合法 JSON、必填 query 参数没传，本来就该是 400；
 *       只写一个 {@code @ExceptionHandler(Throwable.class)} 会把它们一起吞掉——那是把上一条反过来做。</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("h2")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:decerrmap;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "activity.marketing.seed-catalog-data=false"
})
@DisplayName("决策平面错误出口：故障是 500，客户端错误仍是 400")
class DecisionErrorMappingTest {

    private static final String BODY =
            "{\"spuIdList\":[9001],\"userId\":1,\"userDistrictId\":null,\"userTags\":[],\"orderAmount\":200,\"quantity\":1}";

    /** 内部细节：一旦被回显到响应体里，就是把活动 id / SQL 片段直接送到 toC 调用方手上。 */
    private static final String LEAKY = "activity=A-998 的 red_package_amount_unit 是脏的";

    @Autowired MockMvc mvc;
    @MockBean ActivityQueryService query;

    @Test
    @DisplayName("未分类异常 → 500 且 code=INTERNAL，绝不回显 message")
    void unexpectedFailureIsInternalAndDoesNotLeak() throws Exception {
        given(query.spuDiscount(any(SpuDiscountRequest.class), any(DecisionMode.class)))
                .willThrow(new IllegalArgumentException(LEAKY));

        mvc.perform(post("/decision/v1/spu-discount").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL"))
                .andExpect(jsonPath("$.error").doesNotExist())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("A-998"))));
    }

    @Test
    @DisplayName("请求体不是合法 JSON 仍是 400——没被 Throwable 兜底吞成 500")
    void malformedBodyStaysBadRequest() throws Exception {
        mvc.perform(post("/decision/v1/spu-discount")
                        .contentType(MediaType.APPLICATION_JSON).content("{ this is not json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("必填 query 参数缺失仍是 400（/addon/quote 的 activityId & item）")
    void missingRequiredParamStaysBadRequest() throws Exception {
        mvc.perform(post("/decision/v1/addon/quote")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest());
    }
}
