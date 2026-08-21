package com.lrj.drools.activity.domain;

import java.util.Objects;

/**
 * 活动版本到权益 SKU 的受控绑定。它只描述静态映射，不接受表达式、脚本或渠道私有配置。
 */
public record AwardBindingInput(
        String sourceKind,
        String sourceRef,
        String benefitSkuId,
        String deliveryMode,
        String amountMode,
        String itemTemplateJson) {

    public AwardBindingInput {
        Objects.requireNonNull(sourceKind, "sourceKind");
        Objects.requireNonNull(sourceRef, "sourceRef");
        Objects.requireNonNull(benefitSkuId, "benefitSkuId");
        Objects.requireNonNull(itemTemplateJson, "itemTemplateJson");
        if (sourceKind.isBlank() || sourceRef.isBlank() || benefitSkuId.isBlank() || itemTemplateJson.isBlank()) {
            throw new IllegalArgumentException("award binding fields must not be blank");
        }
        sourceKind = sourceKind.trim().toUpperCase(java.util.Locale.ROOT);
        sourceRef = sourceRef.trim();
        benefitSkuId = benefitSkuId.trim();
        deliveryMode = deliveryMode == null || deliveryMode.isBlank()
                ? "LEGACY" : deliveryMode.trim().toUpperCase(java.util.Locale.ROOT);
        amountMode = amountMode == null || amountMode.isBlank()
                ? "FIXED" : amountMode.trim().toUpperCase(java.util.Locale.ROOT);
        if (!sourceKind.equals("DISCOUNT") && !sourceKind.equals("GIFT")) {
            throw new IllegalArgumentException("unsupported sourceKind: " + sourceKind);
        }
        if (!deliveryMode.equals("LEGACY") && !deliveryMode.equals("SHADOW") && !deliveryMode.equals("CENTER")) {
            throw new IllegalArgumentException("unsupported deliveryMode: " + deliveryMode);
        }
        if (!amountMode.equals("FIXED") && !amountMode.equals("DECISION")) {
            throw new IllegalArgumentException("unsupported amountMode: " + amountMode);
        }
    }
}
