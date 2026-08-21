package com.lrj.drools.activity.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.drools.activity.domain.ActivityStatus;
import com.lrj.drools.activity.domain.GiftResult;
import com.lrj.drools.activity.domain.SpuDiscountRequest;
import com.lrj.drools.activity.persistence.ActivityAwardBindingEntity;
import com.lrj.drools.activity.persistence.ActivityAwardBindingRepository;
import com.lrj.drools.activity.persistence.ActivityAwardIntentOutboxRepository;
import com.lrj.drools.activity.persistence.ActivityManageEntity;
import com.lrj.drools.activity.persistence.ActivityManageRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ActivityAwardIntentServiceTest {

    @Test
    void dynamicCashAmountComesFromServerDecisionAndCanonicalHashIgnoresMapOrder() {
        Fixture f = fixture(new ActivityQueryService.DiscountItem(
                "ACT-1", "activity", 3, "元", new BigDecimal("12.34"), true, null));
        Map<String, String> traceA = new LinkedHashMap<>();
        traceA.put("z", "last"); traceA.put("a", "first");
        Map<String, String> traceB = new LinkedHashMap<>();
        traceB.put("a", "first"); traceB.put("z", "last");

        var first = f.service.assemble(command(traceA));
        var second = f.service.assemble(command(traceB));

        assertThat(first.intent().items()).singleElement().satisfies(item -> {
            assertThat(item.amountMinor()).isEqualTo(1234L);
            assertThat(item.currency()).isEqualTo("CNY");
        });
        assertThat(second.payloadHash()).isEqualTo(first.payloadHash());
        assertThat(first.enqueued()).isFalse();
    }

    @Test
    void failsClosedWhenRequestedActivityVersionWasNotApplied() {
        Fixture f = fixture(new ActivityQueryService.DiscountItem(
                "ACT-1", "activity", 2, "元", new BigDecimal("99.99"), true, null));

        assertThatThrownBy(() -> f.service.assemble(command(Map.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("authoritative decision");
    }

    @Test
    void multiUnitGiftIsSplitIntoStableAtomicClientItems() {
        ActivityManageRepository activities = mock(ActivityManageRepository.class);
        ActivityAwardBindingRepository bindings = mock(ActivityAwardBindingRepository.class);
        ActivityAwardIntentOutboxRepository outbox = mock(ActivityAwardIntentOutboxRepository.class);
        ActivityQueryService decisions = mock(ActivityQueryService.class);
        ActivityManageEntity activity = mock(ActivityManageEntity.class);
        when(activity.getActivityStatus()).thenReturn(ActivityStatus.ONLINE.code());
        when(activities.findFirstByActivityIdAndVersionAndIsDel("ACT-1", 3, 0))
                .thenReturn(Optional.of(activity));
        var binding = new ActivityAwardBindingEntity("ACT-1", 3, "GIFT", "batch-1",
                "COUPON-1", "SHADOW", "DECISION",
                "{\"benefitType\":\"COUPON\",\"clientItemId\":\"gift-batch-1\"}", Instant.EPOCH);
        when(bindings.findByActivityIdAndVersionOrderByIdAsc("ACT-1", 3)).thenReturn(List.of(binding));
        GiftResult gift = new GiftResult("ACT-1", 3, "batch-1", "wash", "COUPON",
                2, BigDecimal.ZERO, null);
        when(decisions.buyAndGetGifts(any(), any())).thenReturn(new ActivityQueryService.GiftView(
                List.of(gift), List.of(), "rule-engine", "DECISION-GIFT", null));
        var service = new ActivityAwardIntentService(activities, bindings, outbox, decisions, new ObjectMapper());
        var command = new ActivityAwardIntentService.AssembleCommand("ACT-1", 3, "REQ-GIFT", "ORDER-1",
                "USER-1", "GIFT", new SpuDiscountRequest(List.of(1L), 2L, null, List.of(),
                new BigDecimal("100.00"), 1), Map.of());

        var result = service.assemble(command);

        assertThat(result.intent().items()).extracting(item -> item.clientItemId())
                .containsExactly("gift-batch-1:1", "gift-batch-1:2");
        assertThat(result.intent().items()).allSatisfy(item -> assertThat(item.quantity()).isEqualTo(1));
    }

    private static Fixture fixture(ActivityQueryService.DiscountItem decisionItem) {
        ActivityManageRepository activities = mock(ActivityManageRepository.class);
        ActivityAwardBindingRepository bindings = mock(ActivityAwardBindingRepository.class);
        ActivityAwardIntentOutboxRepository outbox = mock(ActivityAwardIntentOutboxRepository.class);
        ActivityQueryService decisions = mock(ActivityQueryService.class);
        ActivityManageEntity activity = mock(ActivityManageEntity.class);
        when(activity.getActivityStatus()).thenReturn(ActivityStatus.ONLINE.code());
        when(activities.findFirstByActivityIdAndVersionAndIsDel("ACT-1", 3, 0))
                .thenReturn(Optional.of(activity));
        var binding = new ActivityAwardBindingEntity("ACT-1", 3, "DISCOUNT", "discount",
                "CASH-1", "SHADOW", "DECISION",
                "{\"benefitType\":\"CASH\",\"currency\":\"CNY\",\"quantity\":1}", Instant.EPOCH);
        when(bindings.findByActivityIdAndVersionOrderByIdAsc("ACT-1", 3)).thenReturn(List.of(binding));
        when(decisions.spuDiscount(any(), any())).thenReturn(new ActivityQueryService.DiscountView(
                true, decisionItem.activityId(), decisionItem.activityName(), decisionItem.amount(), "MAX",
                List.of(), "rule-engine", decisionItem.version(), false, "DECISION-1",
                List.of(decisionItem), null));
        return new Fixture(new ActivityAwardIntentService(activities, bindings, outbox, decisions, new ObjectMapper()));
    }

    private static ActivityAwardIntentService.AssembleCommand command(Map<String, String> trace) {
        return new ActivityAwardIntentService.AssembleCommand("ACT-1", 3, "REQ-1", "ORDER-1", "USER-1",
                "DISCOUNT", new SpuDiscountRequest(List.of(1L), 2L, null, List.of(),
                new BigDecimal("100.00"), 1), trace);
    }

    private record Fixture(ActivityAwardIntentService service) {}
}
