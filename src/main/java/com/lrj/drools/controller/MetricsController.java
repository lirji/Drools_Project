package com.lrj.drools.controller;

import com.lrj.drools.domain.Customer;
import com.lrj.drools.domain.Order;
import com.lrj.drools.domain.OrderItem;
import com.lrj.drools.service.MeteredDiscountService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Step 15: 规则可观测性指标。
 *
 * /metrics/discount 跟 Step 2 的 /discount/calculate 同入参同折扣逻辑, 但每次调用会把
 * fire/match/fact/耗时累加进 Micrometer。多打几次, 再 curl GET /actuator/prometheus
 * 就能看到 drools.* 指标随调用累积 (类似 Step 6 的 /pipeline/audit 之于 /pipeline/run)。
 */
@RestController
public class MetricsController {

    private final MeteredDiscountService meteredDiscountService;

    public MetricsController(MeteredDiscountService meteredDiscountService) {
        this.meteredDiscountService = meteredDiscountService;
    }

    @PostMapping("/metrics/discount")
    public MeteredDiscountService.Result calculate(@RequestBody CalculateRequest req) {
        Order order = new Order(
                req.orderId() != null ? req.orderId() : UUID.randomUUID().toString(),
                req.customer(),
                req.items()
        );
        return meteredDiscountService.calculate(order);
    }

    public record CalculateRequest(String orderId, Customer customer, List<OrderItem> items) {}
}
