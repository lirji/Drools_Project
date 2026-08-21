package com.lrj.drools.activity.benefit;

import java.util.List;
import java.util.Map;

/**
 * Local transport snapshot of benefit-center OpenAPI v1.
 *
 * <p>The activity platform integrates over JSON/HTTP and must remain independently buildable. Keep these DTOs
 * backward compatible with {@code benefit-center-v1.yaml}; contract compatibility is verified at the connector
 * boundary rather than through an unpublished cross-repository SNAPSHOT dependency.</p>
 */
public final class AwardIntentContract {
    private AwardIntentContract() {}

    public enum BenefitType {
        CASH,
        COUPON,
        SERVICE_VOUCHER,
        REDEMPTION_CODE,
        PHYSICAL
    }

    public enum PartialPolicy {
        BEST_EFFORT
    }

    public record DecisionReference(String decisionId, String activityId, Integer activityVersion) {}

    public record AwardItemIntent(
            String clientItemId,
            String benefitSkuId,
            BenefitType benefitType,
            Long amountMinor,
            String currency,
            long quantity,
            Map<String, String> metadata) {

        public AwardItemIntent {
            requireText("clientItemId", clientItemId);
            requireText("benefitSkuId", benefitSkuId);
            if (benefitType == null) throw new IllegalArgumentException("benefitType must not be null");
            if (quantity != 1) {
                throw new IllegalArgumentException(
                        "v1 AwardItemIntent is atomic; split quantity into stable clientItemIds");
            }
            if (benefitType == BenefitType.CASH) {
                if (amountMinor == null || amountMinor <= 0 || currency == null || currency.length() != 3) {
                    throw new IllegalArgumentException("cash item requires positive amountMinor and ISO currency");
                }
            } else if (amountMinor != null || currency != null) {
                throw new IllegalArgumentException("non-cash item must not carry monetary fields");
            }
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }

    public record AwardIntent(
            String schemaVersion,
            String sourceSystem,
            String sourceRequestId,
            String sourceBusinessNo,
            String recipientRef,
            DecisionReference decision,
            PartialPolicy partialPolicy,
            List<AwardItemIntent> items,
            Map<String, String> trace) {

        public AwardIntent {
            requireText("schemaVersion", schemaVersion);
            requireText("sourceSystem", sourceSystem);
            requireText("sourceRequestId", sourceRequestId);
            requireText("recipientRef", recipientRef);
            if (partialPolicy == null) throw new IllegalArgumentException("partialPolicy must not be null");
            items = items == null ? List.of() : List.copyOf(items);
            if (items.isEmpty() || items.size() > 20) {
                throw new IllegalArgumentException("AwardIntent must contain 1..20 items");
            }
            trace = trace == null ? Map.of() : Map.copyOf(trace);
        }
    }

    private static void requireText(String name, String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
    }
}
