package com.lrj.drools.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 购物车 — Step 3 的主 fact。
 *
 * 跟 Order 的区别:
 * - items 里每个 OrderItem 带 category，用于 accumulate 按品类聚合
 * - 加了 goldStatus (mutable boolean)，给 modify 语法演示用:
 *   一条规则会把它从 false 改成 true，另一条规则的 LHS 依赖这个字段
 *
 * setGoldStatus 是给 DRL 里 `modify($cart) { setGoldStatus(true) }` 调的；
 * applyXxxDiscount 跟 Order 一致。
 */
public class Cart {

    private final String cartId;
    private final Customer customer;
    private final List<OrderItem> items;
    private final double totalAmount;          // 原价 (items 求和)
    private double finalAmount;                 // 折后价
    private boolean goldStatus = false;         // 大客户动态升级标志，规则改
    private final List<String> discountReasons = new ArrayList<>();
    // Step 4 引入: 风控/推荐规则把"建议加购 / 赠品 / 新人券"之类的文案塞这里。
    // 跟 discountReasons 区分: discountReasons 是"已应用的折扣账本"，
    // recommendations 是"还没成交的推荐位"。
    private final List<String> recommendations = new ArrayList<>();

    public Cart(String cartId, Customer customer, List<OrderItem> items) {
        this.cartId = cartId;
        this.customer = customer;
        this.items = items;
        this.totalAmount = items.stream().mapToDouble(OrderItem::subtotal).sum();
        this.finalAmount = this.totalAmount;
    }

    public void applyRatioDiscount(double ratio, String reason) {
        this.finalAmount = round2(this.finalAmount * ratio);
        this.discountReasons.add(reason);
    }

    public void applyFixedDiscount(double amount, String reason) {
        this.finalAmount = round2(Math.max(0, this.finalAmount - amount));
        this.discountReasons.add(reason);
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    public String getCartId() { return cartId; }
    public Customer getCustomer() { return customer; }
    public List<OrderItem> getItems() { return items; }
    public double getTotalAmount() { return totalAmount; }
    public double getFinalAmount() { return finalAmount; }
    public boolean isGoldStatus() { return goldStatus; }
    public void setGoldStatus(boolean goldStatus) { this.goldStatus = goldStatus; }
    public List<String> getDiscountReasons() { return discountReasons; }
    public List<String> getRecommendations() { return recommendations; }

    public void addRecommendation(String message) {
        this.recommendations.add(message);
    }
}
