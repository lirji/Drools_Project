package com.lrj.drools.activity;

import com.lrj.drools.activity.persistence.ActivityAwardIntentOutboxEntity;
import com.lrj.drools.activity.persistence.ActivityAwardIntentOutboxRepository;
import com.lrj.drools.activity.service.AwardIntentOutboxTenantScanner;
import com.lrj.drools.activity.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("h2")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:awardintentlease;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "activity.marketing.seed-catalog-data=false",
        "activity.marketing.seed-district-data=false",
        "activity.award-intent.relay-enabled=false"
})
@DisplayName("AwardIntent outbox 多实例租约")
class AwardIntentOutboxLeaseTest {
    private static final String TENANT = "lease-tenant";

    @Autowired ActivityAwardIntentOutboxRepository repository;
    @Autowired AwardIntentOutboxTenantScanner scanner;
    @Autowired TransactionTemplate transactions;

    @BeforeEach
    void setUp() {
        TenantContext.set(TENANT);
        repository.deleteAll();
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("崩溃 worker 的过期 SENDING 可被扫描并由新 worker CAS 接管")
    void expiredSendingCanBeReclaimedWithoutStaleWorkerSettlement() {
        Instant now = Instant.parse("2026-08-21T03:00:00Z");
        ActivityAwardIntentOutboxEntity entry = repository.saveAndFlush(
                new ActivityAwardIntentOutboxEntity("drools", "request-1", "activity-1", 1,
                        "hash", "{}", now));

        int firstClaim = transactions.execute(status -> repository.tryClaim(
                entry.getId(), "dead-worker", now, now.minusSeconds(1)));
        assertEquals(1, firstClaim);
        assertTrue(scanner.findTenants(now).contains(TENANT),
                "租约过期的 SENDING 必须重新进入租户扫描结果");
        assertEquals(1, repository.findRetryable(now, PageRequest.of(0, 10)).size());

        int reclaimed = transactions.execute(status -> repository.tryClaim(
                entry.getId(), "replacement-worker", now, now.plusSeconds(30)));
        assertEquals(1, reclaimed);

        int staleSettlement = transactions.execute(status -> repository.markSentClaimed(
                entry.getId(), "dead-worker", now.plusSeconds(1)));
        int currentSettlement = transactions.execute(status -> repository.markSentClaimed(
                entry.getId(), "replacement-worker", now.plusSeconds(1)));
        assertEquals(0, staleSettlement, "旧 lease owner 不得提交结果");
        assertEquals(1, currentSettlement);
        assertEquals(ActivityAwardIntentOutboxEntity.SENT, repository.findById(entry.getId()).orElseThrow().getStatus());
    }
}
