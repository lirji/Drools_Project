package com.lrj.drools.controller;

import com.lrj.drools.domain.Customer;
import com.lrj.drools.domain.Order;
import com.lrj.drools.domain.OrderItem;
import com.lrj.drools.service.DiscountService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
public class DiscountController {

    private final DiscountService discountService;

    public DiscountController(DiscountService discountService) {
        this.discountService = discountService;
    }

    /** Step 1 展示: 跑 hello 规则，规则触发会打到 stdout，body 返回触发条数。 */
    @PostMapping("/hello")
    public Map<String, Object> hello(@RequestBody Customer customer) {
        int fired = discountService.runHello(customer);
        return Map.of("customer", customer, "rulesFired", fired,
                "hint", "看应用控制台输出每条规则的打印");
    }

    /** Step 2 展示: 跑订单折扣规则，返回折后价 + 折扣说明列表。 */
    @PostMapping("/discount/calculate")
    public Order calculate(@RequestBody CalculateRequest req) {
        Order order = new Order(
                req.orderId() != null ? req.orderId() : UUID.randomUUID().toString(),
                req.customer(),
                req.items()
        );
        return discountService.calculate(order);
    }

    public record CalculateRequest(String orderId, Customer customer, List<OrderItem> items) {}
}
