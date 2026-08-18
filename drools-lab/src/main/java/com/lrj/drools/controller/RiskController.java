package com.lrj.drools.controller;

import com.lrj.drools.domain.Cart;
import com.lrj.drools.domain.Customer;
import com.lrj.drools.domain.OrderItem;
import com.lrj.drools.service.RiskService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
public class RiskController {

    private final RiskService riskService;

    public RiskController(RiskService riskService) {
        this.riskService = riskService;
    }

    /** Step 4 展示: not / exists (风控 + 推荐), 不计算折扣。 */
    @PostMapping("/risk/evaluate")
    public Cart evaluate(@RequestBody EvaluateRequest req) {
        Cart cart = new Cart(
                req.cartId() != null ? req.cartId() : UUID.randomUUID().toString(),
                req.customer(),
                req.items()
        );
        return riskService.evaluate(cart);
    }

    public record EvaluateRequest(String cartId, Customer customer, List<OrderItem> items) {}
}
