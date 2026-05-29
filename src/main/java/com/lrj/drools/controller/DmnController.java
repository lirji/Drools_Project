package com.lrj.drools.controller;

import com.lrj.drools.domain.Customer;
import com.lrj.drools.service.DmnService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Step 17: DMN 定价。
 *
 * 入参跟折扣类接口一致 (Customer + 金额), 但走的是 DMN 决策模型而非 DRL:
 * 返回三个决策的结果 (Discount Rate / Final Price / Membership Tier)。
 *
 * 跟 Step 7 决策表对照: Step 7 是 Excel → 编译成 DRL → 跑 KieSession;
 * 这里是 .dmn 标准模型 → DMNRuntime 按决策需求图求值, 是另一套引擎。
 */
@RestController
public class DmnController {

    private final DmnService dmnService;

    public DmnController(DmnService dmnService) {
        this.dmnService = dmnService;
    }

    @PostMapping("/dmn/price")
    public DmnService.Result price(@RequestBody PriceRequest req) {
        return dmnService.evaluate(req.customer(), req.orderAmount());
    }

    public record PriceRequest(Customer customer, double orderAmount) {}
}
