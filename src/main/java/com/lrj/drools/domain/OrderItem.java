package com.lrj.drools.domain;

public record OrderItem(String name, int quantity, double unitPrice) {
    public double subtotal() {
        return quantity * unitPrice;
    }
}
