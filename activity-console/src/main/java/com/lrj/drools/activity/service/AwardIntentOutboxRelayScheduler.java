package com.lrj.drools.activity.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "activity.award-intent.relay-enabled", havingValue = "true")
public class AwardIntentOutboxRelayScheduler {
    private final AwardIntentOutboxRelay relay;
    public AwardIntentOutboxRelayScheduler(AwardIntentOutboxRelay relay) { this.relay = relay; }

    @Scheduled(fixedDelayString = "${activity.award-intent.relay-interval-ms:5000}")
    public void relay() { relay.relayOnce(); }
}
