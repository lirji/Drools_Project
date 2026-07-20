package com.lrj.drools.domain;

/**
 * 加了 category 字段给 Step 3 的 accumulate 按品类聚合用。
 * 老的 /discount/calculate 请求不传 category，Jackson 反序列化成 null，
 * Drools LHS `category == "BOOK"` 对 null 做 equals 是 false，不会误触发。
 */
public record OrderItem(String name, int quantity, double unitPrice, String category) {
    public double subtotal() {
        return quantity * unitPrice;
    }
}
