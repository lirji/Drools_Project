package com.lrj.drools.controller;

import com.lrj.drools.domain.Customer;
import com.lrj.drools.domain.Order;
import com.lrj.drools.domain.OrderItem;
import com.lrj.drools.service.TemplateService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Step 23: 规则模板 .drt（模板 + 数据行生成规则）入口。
 *
 * 请求体:
 *   customer, items: 组成待打折的 Order
 *   tiers: [{minAmount, maxAmount, discount}, ...] —— 每档生成一条规则
 *
 * 响应: {order（含折后价 + discountReasons）, firedCount, generatedDrl（展开出来的 DRL 文本）}
 *
 * 示例（订单 800 元，命中第二档 0.85 折）：
 *   tiers = [
 *     {minAmount:0,   maxAmount:500,   discount:0.95},
 *     {minAmount:500, maxAmount:1000,  discount:0.85},
 *     {minAmount:1000,maxAmount:1e9,   discount:0.75}
 *   ]
 */
@RestController
public class TemplateController {

    private final TemplateService templateService;

    public TemplateController(TemplateService templateService) {
        this.templateService = templateService;
    }

    @PostMapping("/template/discount")
    public ResponseEntity<?> discount(@RequestBody TemplateRequest req) {
        Order order = new Order(
                req.orderId() != null ? req.orderId() : UUID.randomUUID().toString(),
                req.customer(),
                req.items()
        );
        try {
            return ResponseEntity.ok(templateService.generate(order, TemplateService.toRows(req.tiers())));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    public record TemplateRequest(String orderId, Customer customer, List<OrderItem> items,
                                  List<TemplateService.Tier> tiers) {}
    public record ErrorResponse(String error) {}
}
