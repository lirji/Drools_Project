package com.lrj.drools.activity;

import com.lrj.drools.activity.controller.ActivityMarketingController;
import com.lrj.drools.activity.engine.RuleSchemaRegistry;
import com.lrj.drools.activity.service.ActivityMarketingService;
import com.lrj.drools.activity.service.ActivityQueryService;
import com.lrj.drools.activity.service.AddOnPurchaseService;
import com.lrj.drools.activity.service.DistrictQueryService;
import com.lrj.drools.activity.service.GenerationService;
import com.lrj.drools.activity.service.StorePickerQueryService;
import com.lrj.drools.activity.service.StorePickerQueryService.PickerProduct;
import com.lrj.drools.activity.service.StorePickerQueryService.PickerProductPage;
import com.lrj.drools.activity.service.StorePickerQueryService.PickerStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** picker 目录浏览两端点的契约：路由、参数透传、默认值。 */
@ExtendWith(MockitoExtension.class)
class StorePickerContractTest {

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
        mvc = MockMvcBuilders.standaloneSetup(new ActivityMarketingController(
                marketing, query, addOn, schemaRegistry, generations, districts, storePicker)).build();
    }

    @Test
    void listStoresRoutesAndReturnsJson() throws Exception {
        when(storePicker.stores()).thenReturn(List.of(
                new PickerStore(1, "旗舰店", 5), new PickerStore(2, null, 3)));

        mvc.perform(get("/activity-marketing/store-picker/stores"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].storeId").value(1))
                .andExpect(jsonPath("$[0].storeName").value("旗舰店"))
                .andExpect(jsonPath("$[0].productCount").value(5))
                .andExpect(jsonPath("$[1].storeName").doesNotExist());   // 回退 null

        verify(storePicker).stores();
    }

    /** page/size/keyword 缺省时用默认值（page=0,size=20,keyword=null）。 */
    @Test
    void listProductsDefaultsParams() throws Exception {
        when(storePicker.products(1, null, 0, 20)).thenReturn(new PickerProductPage(1, 0, 20,
                List.of(new PickerProduct(9101L, "耳机", new BigDecimal("120"), 1))));

        mvc.perform(get("/activity-marketing/store-picker/stores/1/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].spuId").value(9101));

        verify(storePicker).products(1, null, 0, 20);
    }

    @Test
    void listProductsForwardsAllParams() throws Exception {
        when(storePicker.products(7, "耳机", 2, 5)).thenReturn(new PickerProductPage(0, 2, 5, List.of()));

        mvc.perform(get("/activity-marketing/store-picker/stores/7/products")
                        .queryParam("keyword", "耳机").queryParam("page", "2").queryParam("size", "5"))
                .andExpect(status().isOk());

        verify(storePicker).products(7, "耳机", 2, 5);
    }
}
