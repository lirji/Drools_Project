package com.lrj.drools.controller;

import com.lrj.drools.domain.Customer;
import com.lrj.drools.domain.Order;
import com.lrj.drools.domain.OrderItem;
import com.lrj.drools.service.StatelessDiscountService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
public class StatelessDiscountController {

    private final StatelessDiscountService statelessService;

    public StatelessDiscountController(StatelessDiscountService statelessService) {
        this.statelessService = statelessService;
    }

    /**
     * Step 11: 单笔订单 stateless 评估。请求体 / 响应体跟 /discount/calculate 完全一致,
     * 业务结果也应该一模一样 (同 KBase 同规则); 唯一差别在内部 session 模型。
     */
    @PostMapping("/stateless/calculate")
    public Order calculate(@RequestBody DiscountController.CalculateRequest req) {
        Order order = new Order(
                req.orderId() != null ? req.orderId() : UUID.randomUUID().toString(),
                req.customer(),
                req.items()
        );
        return statelessService.calculate(order);
    }

    /**
     * Step 11: 批量评估。每条 OrderInput 独立 execute, 第 N 单的 working memory
     * 跟第 N-1 单完全隔离 — 适合"批量结算"这类无跨单依赖的场景。
     *
     * 同样的事情 stateful 需要每条 newKieSession + try/finally dispose;
     * stateless 单实例反复 execute, 代码量差一大半。
     */
    @PostMapping("/stateless/batch")
    public List<Order> batch(@RequestBody BatchRequest req) {
        List<Order> orders = req.orders().stream()
                .map(in -> new Order(
                        in.orderId() != null ? in.orderId() : UUID.randomUUID().toString(),
                        in.customer(),
                        in.items()))
                .toList();
        return statelessService.batch(orders);
    }

    public record BatchRequest(List<OrderInput> orders) {}
    public record OrderInput(String orderId, Customer customer, List<OrderItem> items) {}
}
