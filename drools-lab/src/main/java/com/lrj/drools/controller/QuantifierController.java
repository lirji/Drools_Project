package com.lrj.drools.controller;

import com.lrj.drools.domain.Customer;
import com.lrj.drools.domain.OrderItem;
import com.lrj.drools.domain.ReviewFinding;
import com.lrj.drools.service.QuantifierService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Step 19: LHS 量词补全 (collect / forall / eval) 入口。
 *
 * 请求体:
 *   customer: {name, age, vipLevel, yearsSinceRegistration}  —— eval 规则按 vipLevel 算免审额度
 *   items:    [{name, quantity, unitPrice, category}, ...]    —— collect/forall/eval 的评估对象
 *
 * 响应 findings 每条 {code, detail}:
 *   BULK_BOOK        —— collect: 图书 ≥ 3 本
 *   ALL_LINES_VALID  —— forall: 所有订单行都有品类且单价为正
 *   MANUAL_REVIEW    —— eval: 总额超过该 VIP 等级免审额度
 *
 * 示例 (3 本书 + 一笔高价电子 + VIP1, 免审额度 1000*(1+1)=2000):
 *   customer = {name:"张三", vipLevel:1}
 *   items = [
 *     {name:"书A", quantity:1, unitPrice:50,   category:"BOOK"},
 *     {name:"书B", quantity:1, unitPrice:60,   category:"BOOK"},
 *     {name:"书C", quantity:1, unitPrice:70,   category:"BOOK"},
 *     {name:"手机", quantity:1, unitPrice:3000, category:"ELECTRONICS"}
 *   ]
 *   → BULK_BOOK (3 本书) + ALL_LINES_VALID (都分类且价>0) + MANUAL_REVIEW (总额 3180 > 2000)
 */
@RestController
public class QuantifierController {

    private final QuantifierService quantifierService;

    public QuantifierController(QuantifierService quantifierService) {
        this.quantifierService = quantifierService;
    }

    @PostMapping("/quantifier/review")
    public ReviewResponse review(@RequestBody ReviewRequest req) {
        return new ReviewResponse(quantifierService.review(req.customer(), req.items()));
    }

    public record ReviewRequest(Customer customer, List<OrderItem> items) {}
    public record ReviewResponse(List<ReviewFinding> findings) {}
}
