package com.lrj.drools.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 订单 — 被规则修改的"事实"。
 *
 * 注意几个 Drools 用法约定:
 * - 必须可变 (规则会改 finalAmount / discountReasons)，所以不是 record
 * - 字段都有 getter (DRL 里 `Order( totalAmount >= 500 )` 用的是 getTotalAmount())
 * - 规则改完字段必须调 update(fact)，否则引擎不知道该重新评估依赖此字段的规则
 *   (RETE 网络靠"变更通知"传播，不会主动轮询字段)
 */
public class Order {

    private final String orderId;
    private final Customer customer;
    private final List<OrderItem> items;
    private final double totalAmount;          // 原价 (items 求和)
    private double finalAmount;                 // 折后价，规则会改
    private final List<String> discountReasons = new ArrayList<>();

    public Order(String orderId, Customer customer, List<OrderItem> items) {
        this.orderId = orderId;
        this.customer = customer;
        this.items = items;
        this.totalAmount = items.stream().mapToDouble(OrderItem::subtotal).sum();
        this.finalAmount = this.totalAmount;
    }

    /** 按比例打折 (0.9 = 9 折)。规则里调用。 */
    public void applyRatioDiscount(double ratio, String reason) {
        this.finalAmount = round2(this.finalAmount * ratio);
        this.discountReasons.add(reason);
    }

    /** 减固定金额。规则里调用。 */
    public void applyFixedDiscount(double amount, String reason) {
        this.finalAmount = round2(Math.max(0, this.finalAmount - amount));
        this.discountReasons.add(reason);
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    public String getOrderId() { return orderId; }
    public Customer getCustomer() { return customer; }
    public List<OrderItem> getItems() { return items; }
    public double getTotalAmount() { return totalAmount; }
    public double getFinalAmount() { return finalAmount; }
    public List<String> getDiscountReasons() { return discountReasons; }
}
