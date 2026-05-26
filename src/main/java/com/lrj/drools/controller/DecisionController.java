package com.lrj.drools.controller;

import com.lrj.drools.domain.Cart;
import com.lrj.drools.domain.Customer;
import com.lrj.drools.domain.OrderItem;
import com.lrj.drools.service.DecisionService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
public class DecisionController {

    private final DecisionService decisionService;

    public DecisionController(DecisionService decisionService) {
        this.decisionService = decisionService;
    }

    /** Step 7: 用决策表 CSV 维护的 VIP 折扣档位。改 CSV 加档位不需要改 Java。 */
    @PostMapping("/decision/calculate")
    public Cart calculate(@RequestBody CalcRequest req) {
        Cart cart = new Cart(
                req.cartId() != null ? req.cartId() : UUID.randomUUID().toString(),
                req.customer(),
                req.items()
        );
        return decisionService.calculate(cart);
    }

    public record CalcRequest(String cartId, Customer customer, List<OrderItem> items) {}
}
