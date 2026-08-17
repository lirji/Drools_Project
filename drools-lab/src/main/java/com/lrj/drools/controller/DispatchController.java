package com.lrj.drools.controller;

import com.lrj.drools.domain.Cart;
import com.lrj.drools.domain.Customer;
import com.lrj.drools.domain.OrderItem;
import com.lrj.drools.service.DispatchService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Step 20: RHS 对外 (globals / channels) 入口。
 *
 * 请求体 (跟 /risk/evaluate 同形): {cartId?, customer, items}
 * 响应:
 *   auditLog: 经 **global** (NotificationSink) 记下的审计条目 (大额订单触发)
 *   notices:  经 **channel** ("notify" exit point) 推出的通知 (VIP 客户触发)
 *
 * 示例 (VIP2 + 大额订单 → 两条路径都触发):
 *   customer = {name:"李四", vipLevel:2}
 *   items = [{name:"笔记本", quantity:1, unitPrice:1500, category:"ELECTRONICS"}]
 *   → auditLog: ["大额订单 ... 金额 1500.0 已记入审计"]
 *   → notices:  [{channel:"SMS", target:"李四", content:"尊贵的VIP用户, ..."}]
 */
@RestController
public class DispatchController {

    private final DispatchService dispatchService;

    public DispatchController(DispatchService dispatchService) {
        this.dispatchService = dispatchService;
    }

    @PostMapping("/dispatch/run")
    public DispatchService.DispatchResult run(@RequestBody DispatchRequest req) {
        Cart cart = new Cart(
                req.cartId() != null ? req.cartId() : UUID.randomUUID().toString(),
                req.customer(),
                req.items()
        );
        return dispatchService.run(cart);
    }

    public record DispatchRequest(String cartId, Customer customer, List<OrderItem> items) {}
}
