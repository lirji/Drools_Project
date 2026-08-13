package com.lrj.drools.activity;

import com.lrj.drools.activity.controller.ActivityMarketingController;
import com.lrj.drools.activity.service.ActivityMarketingService;
import com.lrj.drools.activity.service.ActivityMarketingService.SpuBindingPage;
import com.lrj.drools.activity.service.ActivityMarketingService.SpuBindingRow;
import com.lrj.drools.activity.service.ActivityMarketingService.StoreBindingView;
import com.lrj.drools.activity.service.ActivityQueryService;
import com.lrj.drools.activity.service.AddOnPurchaseService;
import com.lrj.drools.activity.service.DistrictQueryService;
import com.lrj.drools.activity.service.GenerationService;
import com.lrj.drools.activity.service.StorePickerQueryService;
import com.lrj.drools.activity.engine.RuleSchemaRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 详情回显·绑定商品两条新读端点的契约：路由、参数透传、storeId 省略→null 桶、IAE→400。 */
@ExtendWith(MockitoExtension.class)
class BindingViewContractTest {

    @Mock ActivityMarketingService marketing;
    @Mock ActivityQueryService query;
    @Mock AddOnPurchaseService addOn;
    @Mock RuleSchemaRegistry schemaRegistry;
    @Mock GenerationService generations;
    @Mock DistrictQueryService districts;
    @Mock StorePickerQueryService storePicker;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(
                new ActivityMarketingController(marketing, query, addOn, schemaRegistry, generations, districts, storePicker)).build();
    }

    @Test
    void bindingStoresRoutesAndForwardsVersion() throws Exception {
        when(marketing.bindingStores("ACT1", 2))
                .thenReturn(List.of(new StoreBindingView(10, 5, 4), new StoreBindingView(null, 1, 1)));

        mvc.perform(get("/activity-marketing/ACT1/binding-stores").queryParam("version", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].storeId").value(10))
                .andExpect(jsonPath("$[0].spuCount").value(5))
                .andExpect(jsonPath("$[0].effectiveCount").value(4))
                .andExpect(jsonPath("$[1].storeId").doesNotExist());   // null 桶 → 键值为 null

        verify(marketing).bindingStores("ACT1", 2);
    }

    /** storeId 省略时必须以 null 传给 service（命中「未指定门店」桶），而不是空串导致 400。 */
    @Test
    void bindingSpusOmittedStoreIdBecomesNullBucket() throws Exception {
        when(marketing.bindingSpus(eq("ACT1"), eq(1), isNull(), eq(0), eq(10)))
                .thenReturn(new SpuBindingPage(1, 0, 10,
                        List.of(new SpuBindingRow(9001L, "耳机", new BigDecimal("120"), 0, 1, null))));

        mvc.perform(get("/activity-marketing/ACT1/binding-spus")
                        .queryParam("version", "1").queryParam("page", "0").queryParam("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].spuId").value(9001))
                .andExpect(jsonPath("$.items[0].spuName").value("耳机"));

        verify(marketing).bindingSpus("ACT1", 1, null, 0, 10);
    }

    @Test
    void bindingSpusForwardsAllParams() throws Exception {
        when(marketing.bindingSpus("ACT1", 3, 7, 2, 5))
                .thenReturn(new SpuBindingPage(0, 2, 5, List.of()));

        mvc.perform(get("/activity-marketing/ACT1/binding-spus")
                        .queryParam("version", "3").queryParam("storeId", "7")
                        .queryParam("page", "2").queryParam("size", "5"))
                .andExpect(status().isOk());

        verify(marketing).bindingSpus("ACT1", 3, 7, 2, 5);
    }

    @Test
    void missingActivityMapsToBadRequest() throws Exception {
        when(marketing.bindingStores("NOPE", null)).thenThrow(new IllegalArgumentException("活动不存在: NOPE"));

        mvc.perform(get("/activity-marketing/NOPE/binding-stores"))
                .andExpect(status().isBadRequest());
    }
}
