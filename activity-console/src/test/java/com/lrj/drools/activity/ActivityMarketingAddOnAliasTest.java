package com.lrj.drools.activity;

import com.lrj.drools.activity.controller.ActivityMarketingController;
import com.lrj.drools.activity.domain.SpuDiscountRequest;
import com.lrj.drools.activity.engine.RuleSchemaRegistry;
import com.lrj.drools.activity.service.ActivityMarketingService;
import com.lrj.drools.activity.service.ActivityQueryService;
import com.lrj.drools.activity.service.GenerationService;
import com.lrj.drools.activity.service.AddOnPurchaseService;
import com.lrj.drools.activity.service.AddOnPurchaseService.AddOnOption;
import com.lrj.drools.activity.service.AddOnPurchaseService.AddOnOptions;
import com.lrj.drools.activity.service.AddOnPurchaseService.AddOnQuote;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Console 试算入口的加价购别名契约：路由、状态码、请求透传，以及报价不得误触库存提交。 */
@ExtendWith(MockitoExtension.class)
class ActivityMarketingAddOnAliasTest {

    private static final String CONTEXT = """
            {
              "spuIdList": [990011, 990012],
              "userId": 1001,
              "userDistrictId": "110000",
              "userTags": ["vip", "new"],
              "orderAmount": 200.50,
              "quantity": 3,
              "storeId": 7,
              "lines": [
                {"spuId": 990011, "unitPrice": 100.25, "quantity": 2}
              ]
            }
            """;

    @Mock ActivityMarketingService marketing;
    @Mock ActivityQueryService query;
    @Mock AddOnPurchaseService addOn;
    @Mock RuleSchemaRegistry schemaRegistry;
    @Mock GenerationService generations;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        ActivityMarketingController controller =
                new ActivityMarketingController(marketing, query, addOn, schemaRegistry, generations);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void optionsReturns200AndForwardsCompleteDecisionContext() throws Exception {
        when(addOn.options(any())).thenReturn(new AddOnOptions(
                List.of(new AddOnOption("ACT-ADDON-1", "加价购", 3, "保温杯", new BigDecimal("9.90"))),
                List.of("加价购选项 1 个")));

        mvc.perform(post("/activity-marketing/addon/options")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CONTEXT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.options[0].activityId").value("ACT-ADDON-1"))
                .andExpect(jsonPath("$.options[0].version").value(3))
                .andExpect(jsonPath("$.options[0].itemName").value("保温杯"))
                .andExpect(jsonPath("$.options[0].addOnPrice").value(9.9))
                .andExpect(jsonPath("$.traces[0]").value("加价购选项 1 个"));

        ArgumentCaptor<SpuDiscountRequest> request = ArgumentCaptor.forClass(SpuDiscountRequest.class);
        verify(addOn).options(request.capture());
        assertCompleteContext(request.getValue());
        verify(marketing, never()).claimInventory(anyString(), any(), anyInt());
    }

    @Test
    void validQuoteReturns200AndForwardsSelectionWithoutClaimingInventory() throws Exception {
        when(addOn.quote(any(), eq("ACT-ADDON-1"), eq("保温杯")))
                .thenReturn(new AddOnQuote(true, "ACT-ADDON-1", "保温杯", new BigDecimal("9.90"), null,
                        List.of("加价购权威报价：ACT-ADDON-1/保温杯")));

        mvc.perform(post("/activity-marketing/addon/quote")
                        .queryParam("activityId", "ACT-ADDON-1")
                        .queryParam("item", "保温杯")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CONTEXT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.activityId").value("ACT-ADDON-1"))
                .andExpect(jsonPath("$.itemName").value("保温杯"))
                .andExpect(jsonPath("$.addOnPrice").value(9.9))
                .andExpect(jsonPath("$.reason").doesNotExist())
                .andExpect(jsonPath("$.traces[0]").value("加价购权威报价：ACT-ADDON-1/保温杯"));

        ArgumentCaptor<SpuDiscountRequest> request = ArgumentCaptor.forClass(SpuDiscountRequest.class);
        verify(addOn).quote(request.capture(), eq("ACT-ADDON-1"), eq("保温杯"));
        assertCompleteContext(request.getValue());
        verify(marketing, never()).claimInventory(anyString(), any(), anyInt());
    }

    @ParameterizedTest
    @CsvSource({
            "ACT-ADDON-1, 已失效商品",
            "FORGED-ACTIVITY, 保温杯"
    })
    void staleOrForgedQuoteReturns409WithReason(String activityId, String item) throws Exception {
        when(addOn.quote(any(), eq(activityId), eq(item)))
                .thenReturn(new AddOnQuote(false, activityId, item, null,
                        "选项已失效或不适用于当前订单",
                        List.of("加价购报价拒绝：选项已失效或资格不满足")));

        mvc.perform(post("/activity-marketing/addon/quote")
                        .queryParam("activityId", activityId)
                        .queryParam("item", item)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CONTEXT))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.addOnPrice").doesNotExist())
                .andExpect(jsonPath("$.reason").value("选项已失效或不适用于当前订单"))
                .andExpect(jsonPath("$.traces[0]").value("加价购报价拒绝：选项已失效或资格不满足"));

        verify(addOn).quote(any(), eq(activityId), eq(item));
        verify(marketing, never()).claimInventory(anyString(), any(), anyInt());
    }

    private static void assertCompleteContext(SpuDiscountRequest request) {
        assertThat(request.spuIdList()).containsExactly(990011L, 990012L);
        assertThat(request.userId()).isEqualTo(1001L);
        assertThat(request.userDistrictId()).isEqualTo("110000");
        assertThat(request.userTags()).containsExactly("vip", "new");
        assertThat(request.orderAmount()).isEqualByComparingTo("200.50");
        assertThat(request.quantity()).isEqualTo(3);
        assertThat(request.storeId()).isEqualTo(7);
        assertThat(request.lines()).hasSize(1);
        assertThat(request.lines().get(0).spuId()).isEqualTo(990011L);
        assertThat(request.lines().get(0).unitPrice()).isEqualByComparingTo("100.25");
        assertThat(request.lines().get(0).quantity()).isEqualTo(2);
    }
}
