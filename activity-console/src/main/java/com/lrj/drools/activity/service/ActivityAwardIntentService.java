package com.lrj.drools.activity.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.lrj.drools.activity.domain.ActivityStatus;
import com.lrj.drools.activity.domain.DecisionMode;
import com.lrj.drools.activity.domain.GiftResult;
import com.lrj.drools.activity.domain.SpuDiscountRequest;
import com.lrj.drools.activity.persistence.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

import static com.lrj.drools.activity.benefit.AwardIntentContract.*;

/**
 * Converts a server-side activity decision into the neutral AwardIntent contract. Bindings are immutable per
 * activity version; channel selection remains exclusively inside benefit-center.
 */
@Service
public class ActivityAwardIntentService {
    private static final String SOURCE_SYSTEM = "drools-activity";
    private final ActivityManageRepository activities;
    private final ActivityAwardBindingRepository bindings;
    private final ActivityAwardIntentOutboxRepository outbox;
    private final ActivityQueryService decisions;
    private final ObjectMapper json;

    public ActivityAwardIntentService(ActivityManageRepository activities,
                                      ActivityAwardBindingRepository bindings,
                                      ActivityAwardIntentOutboxRepository outbox,
                                      ActivityQueryService decisions,
                                      ObjectMapper json) {
        this.activities = activities;
        this.bindings = bindings;
        this.outbox = outbox;
        this.decisions = decisions;
        this.json = json;
    }

    @Transactional
    public AssembleResult assemble(AssembleCommand command) {
        requireText("sourceRequestId", command.sourceRequestId());
        requireText("recipientRef", command.recipientRef());
        Objects.requireNonNull(command.decisionContext(), "decisionContext");
        var activity = activities.findFirstByActivityIdAndVersionAndIsDel(
                        command.activityId(), command.activityVersion(), 0)
                .orElseThrow(() -> new IllegalArgumentException("activity version does not exist"));
        if (activity.getActivityStatus() != ActivityStatus.ONLINE.code()) {
            throw new IllegalStateException("only the ONLINE activity version can emit AwardIntent");
        }
        List<ActivityAwardBindingEntity> rows = bindings.findByActivityIdAndVersionOrderByIdAsc(
                command.activityId(), command.activityVersion());
        if (rows.isEmpty()) throw new IllegalStateException("activity version has no award binding");
        Set<String> modes = new HashSet<>();
        rows.forEach(row -> modes.add(row.getDeliveryMode()));
        if (modes.size() != 1) throw new IllegalStateException("one activity version cannot mix delivery modes");
        String mode = modes.iterator().next();
        if ("LEGACY".equals(mode)) return new AssembleResult(mode, null, null, false, false);

        ServerDecision decision = recompute(command, rows);
        if (decision.items().isEmpty()) {
            throw new IllegalStateException("authoritative decision did not produce an award for this activity version");
        }
        if (decision.items().size() > 20) {
            throw new IllegalStateException("one AwardIntent can contain at most 20 atomic items");
        }
        AwardIntent intent = new AwardIntent("1.0", SOURCE_SYSTEM, command.sourceRequestId(),
                command.sourceBusinessNo(), command.recipientRef(),
                new DecisionReference(decision.decisionId(), command.activityId(), command.activityVersion()),
                PartialPolicy.BEST_EFFORT, decision.items(), command.trace());
        String payload = write(intent);
        String hash = sha256(payload);
        if ("SHADOW".equals(mode)) return new AssembleResult(mode, intent, hash, false, false);
        if (!"CENTER".equals(mode)) throw new IllegalStateException("unknown delivery mode: " + mode);

        var existing = outbox.findFirstBySourceSystemAndSourceRequestId(SOURCE_SYSTEM, command.sourceRequestId());
        if (existing.isPresent()) return replay(existing.get(), intent, hash, mode);
        try {
            outbox.saveAndFlush(new ActivityAwardIntentOutboxEntity(SOURCE_SYSTEM, command.sourceRequestId(),
                    command.activityId(), command.activityVersion(), hash, payload, Instant.now()));
            return new AssembleResult(mode, intent, hash, true, false);
        } catch (DataIntegrityViolationException concurrent) {
            return replay(outbox.findFirstBySourceSystemAndSourceRequestId(SOURCE_SYSTEM, command.sourceRequestId())
                    .orElseThrow(), intent, hash, mode);
        }
    }

    private AssembleResult replay(ActivityAwardIntentOutboxEntity existing, AwardIntent intent,
                                  String hash, String mode) {
        if (!existing.getPayloadHash().equals(hash)) {
            throw new IllegalStateException("sourceRequestId was reused with a different AwardIntent");
        }
        return new AssembleResult(mode, intent, hash, true, true);
    }

    private ServerDecision recompute(AssembleCommand command, List<ActivityAwardBindingEntity> rows) {
        return switch (command.scene()) {
            case "DISCOUNT" -> discount(command, rows);
            case "GIFT" -> gifts(command, rows);
            default -> throw new IllegalArgumentException("unsupported award decision scene: " + command.scene());
        };
    }

    private ServerDecision discount(AssembleCommand command, List<ActivityAwardBindingEntity> rows) {
        ActivityQueryService.DiscountView result = decisions.spuDiscount(command.decisionContext(), DecisionMode.HOT_PATH);
        ActivityQueryService.DiscountItem applied = result.items().stream()
                .filter(ActivityQueryService.DiscountItem::applied)
                .filter(item -> command.activityId().equals(item.activityId()))
                .filter(item -> command.activityVersion().equals(item.version()))
                .findFirst().orElse(null);
        if (applied == null) return new ServerDecision(result.decisionId(), List.of());
        List<AwardItemIntent> items = rows.stream()
                .filter(row -> "DISCOUNT".equals(row.getSourceKind()))
                .flatMap(row -> atomicItems(row, minor(applied.amount()), 1L).stream())
                .toList();
        requireAllRowsUsed(rows, items.size(), "DISCOUNT");
        return new ServerDecision(result.decisionId(), items);
    }

    private ServerDecision gifts(AssembleCommand command, List<ActivityAwardBindingEntity> rows) {
        ActivityQueryService.GiftView result = decisions.buyAndGetGifts(command.decisionContext(), DecisionMode.HOT_PATH);
        Map<String, GiftResult> byBatch = new LinkedHashMap<>();
        for (GiftResult gift : result.gifts()) {
            if (command.activityId().equals(gift.getActivityId())
                    && command.activityVersion().equals(gift.getVersion())) {
                if (byBatch.putIfAbsent(gift.getBatchId(), gift) != null) {
                    throw new IllegalStateException("authoritative decision returned duplicate gift batch: " + gift.getBatchId());
                }
            }
        }
        List<AwardItemIntent> items = new ArrayList<>();
        for (ActivityAwardBindingEntity row : rows) {
            if (!"GIFT".equals(row.getSourceKind())) {
                throw new IllegalStateException("binding sourceKind does not match GIFT scene");
            }
            GiftResult gift = byBatch.get(row.getSourceRef());
            if (gift != null) {
                Long amount = gift.getAbsoluteAmount() == null ? null : minor(gift.getAbsoluteAmount());
                items.addAll(atomicItems(row, amount, gift.getGiftNum() == null ? 1L : gift.getGiftNum().longValue()));
            }
        }
        return new ServerDecision(result.decisionId(), List.copyOf(items));
    }

    private List<AwardItemIntent> atomicItems(ActivityAwardBindingEntity row, Long decisionAmount,
                                              long decisionQuantity) {
        try {
            JsonNode template = json.readTree(row.getItemTemplateJson());
            BenefitType type = BenefitType.valueOf(required(template, "benefitType"));
            long quantity = template.path("quantity").asLong(1);
            Long amount = template.hasNonNull("amountMinor") ? template.path("amountMinor").longValue() : null;
            String currency = template.hasNonNull("currency") ? template.path("currency").asText() : null;
            if ("DECISION".equals(row.getAmountMode())) {
                quantity = decisionQuantity;
                if (type == BenefitType.CASH) {
                    if (decisionAmount == null || decisionAmount <= 0) {
                        throw new IllegalStateException("authoritative decision has no positive cash amount");
                    }
                    amount = decisionAmount;
                } else {
                    amount = null;
                    currency = null;
                }
            }
            Map<String, String> metadata = new LinkedHashMap<>();
            if (template.path("metadata").isObject()) {
                template.path("metadata").fields().forEachRemaining(entry -> metadata.put(entry.getKey(), entry.getValue().asText()));
            }
            if (quantity <= 0 || quantity > 20) {
                throw new IllegalStateException("one binding can produce only 1..20 atomic items");
            }
            String baseClientItemId = template.path("clientItemId")
                    .asText(row.getSourceKind() + ':' + row.getSourceRef());
            List<AwardItemIntent> result = new ArrayList<>((int) quantity);
            for (int index = 1; index <= quantity; index++) {
                String clientItemId = quantity == 1 ? baseClientItemId : baseClientItemId + ':' + index;
                result.add(new AwardItemIntent(clientItemId, row.getBenefitSkuId(), type,
                        amount, currency, 1, metadata));
            }
            return List.copyOf(result);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception invalidTemplate) {
            throw new IllegalArgumentException("invalid award binding template", invalidTemplate);
        }
    }

    private String write(AwardIntent intent) {
        try { return json.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsString(intent); }
        catch (Exception e) { throw new IllegalStateException("AwardIntent serialization failed", e); }
    }

    private static String required(JsonNode value, String field) {
        String result = value.path(field).asText(null);
        if (result == null || result.isBlank()) throw new IllegalArgumentException("binding template field is required: " + field);
        return result;
    }

    private static void requireAllRowsUsed(List<ActivityAwardBindingEntity> rows, int itemCount, String scene) {
        if (rows.size() != itemCount) {
            throw new IllegalStateException("binding sourceKind does not match " + scene + " scene");
        }
    }

    private static long minor(BigDecimal amount) {
        if (amount == null) throw new IllegalStateException("authoritative decision amount is missing");
        return amount.movePointRight(2).longValueExact();
    }

    private static void requireText(String name, String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    public record AssembleCommand(String activityId, Integer activityVersion, String sourceRequestId,
                                  String sourceBusinessNo, String recipientRef, String scene,
                                  SpuDiscountRequest decisionContext, Map<String, String> trace) {
        public AssembleCommand {
            requireText("activityId", activityId);
            Objects.requireNonNull(activityVersion, "activityVersion");
            requireText("scene", scene);
            trace = trace == null ? Map.of() : Map.copyOf(trace);
        }
    }
    private record ServerDecision(String decisionId, List<AwardItemIntent> items) {}
    public record AssembleResult(String deliveryMode, AwardIntent intent, String payloadHash,
                                 boolean enqueued, boolean replay) {}
}
