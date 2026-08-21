package com.lrj.drools.activity.service;

import com.lrj.drools.activity.config.AwardIntentConnectorProperties;
import com.lrj.drools.activity.persistence.ActivityAwardIntentOutboxEntity;
import com.lrj.drools.activity.persistence.ActivityAwardIntentOutboxRepository;
import com.lrj.drools.activity.tenant.TenantContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.UUID;

@Service
public class AwardIntentOutboxRelay {
    private final ActivityAwardIntentOutboxRepository repository;
    private final AwardIntentOutboxTenantScanner tenants;
    private final RestClient http;
    private final AwardIntentConnectorProperties properties;
    private final TransactionTemplate transactions;
    private final String workerId = "award-intent-relay-" + UUID.randomUUID();

    public AwardIntentOutboxRelay(ActivityAwardIntentOutboxRepository repository,
                                  AwardIntentOutboxTenantScanner tenants,
                                  @Qualifier("benefitCenterRestClient") RestClient http,
                                  AwardIntentConnectorProperties properties,
                                  TransactionTemplate transactions) {
        this.repository = repository;
        this.tenants = tenants;
        this.http = http;
        this.properties = properties;
        this.transactions = transactions;
    }

    public int relayOnce() {
        if (!properties.isRelayEnabled()) return 0;
        int sent = 0;
        Instant now = Instant.now();
        for (String tenant : tenants.findTenants(now)) {
            sent += TenantContext.callWith(tenant, () -> relayTenant(tenant, now));
        }
        return sent;
    }

    private int relayTenant(String tenant, Instant now) {
        int sent = 0;
        var batch = repository.findRetryable(now, PageRequest.of(0, Math.max(1, properties.getBatchSize())));
        for (ActivityAwardIntentOutboxEntity entry : batch) {
            boolean claimed = Boolean.TRUE.equals(transactions.execute(status -> repository.tryClaim(
                    entry.getId(), workerId, now,
                    now.plusMillis(Math.max(1000L, properties.getLeaseMs()))) == 1));
            if (!claimed) continue;
            try {
                var request = http.post().uri("/openapi/v1/award-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", entry.getSourceRequestId())
                        .header("X-Tenant-Id", tenant);
                if (properties.getBearerToken() != null && !properties.getBearerToken().isBlank()) {
                    request.header("Authorization", "Bearer " + properties.getBearerToken());
                }
                request.body(entry.getPayload()).retrieve().toBodilessEntity();
                boolean marked = Boolean.TRUE.equals(transactions.execute(status ->
                        repository.markSentClaimed(entry.getId(), workerId, Instant.now()) == 1));
                if (marked) sent++;
            } catch (RuntimeException deliveryFailure) {
                transactions.executeWithoutResult(status -> {
                    Instant failedAt = Instant.now();
                    if (entry.getAttempt() + 1 >= Math.max(1, properties.getMaxAttempts())) {
                        repository.markDeadClaimed(entry.getId(), workerId, failedAt);
                    } else {
                        long backoff = Math.min(300, 1L << Math.min(entry.getAttempt(), 8));
                        repository.markFailedClaimed(entry.getId(), workerId, failedAt,
                                failedAt.plusSeconds(backoff));
                    }
                });
            }
        }
        return sent;
    }
}
