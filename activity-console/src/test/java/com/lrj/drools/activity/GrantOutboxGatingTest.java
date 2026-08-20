package com.lrj.drools.activity;

import com.lrj.drools.activity.domain.ActivityCreateRequest;
import com.lrj.drools.activity.domain.ActivityStatus;
import com.lrj.drools.activity.persistence.ActivityGrantEntity;
import com.lrj.drools.activity.persistence.ActivityGrantOutboxRepository;
import com.lrj.drools.activity.service.ActivityMarketingService;
import com.lrj.drools.activity.service.ActivityMarketingService.CreateResult;
import com.lrj.drools.activity.service.GrantOutboxRelay;
import com.lrj.drools.activity.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>门控关（默认）时对既有零影响</b>：{@code activity.grant-outbox.enabled} 未设（false）——
 * confirm/release 不写任何 outbox 事件，中继 {@code relayOnce} 直接返回 0。这条钉死「默认部署零感知」，
 * 保证既有 confirm/release/分录台账逻辑与全量测试不被本特性触碰。
 */
@SpringBootTest
@ActiveProfiles("h2")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:grantoutboxoff;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "activity.marketing.seed-catalog-data=false",
        "activity.marketing.seed-district-data=false"
        // 不设 activity.grant-outbox.enabled → 默认 false
})
@DisplayName("发放传播 outbox：门控关时零写入")
class GrantOutboxGatingTest {

    private static final AtomicLong SPU = new AtomicLong(990_000L);
    private static final AtomicLong ORDER = new AtomicLong(1L);

    @Autowired ActivityMarketingService marketing;
    @Autowired ActivityGrantOutboxRepository outboxRepo;
    @Autowired GrantOutboxRelay relay;

    @BeforeEach
    void bindTenant() { TenantContext.set("__dev__"); }

    @AfterEach
    void clear() { TenantContext.clear(); }

    @Test
    @DisplayName("门控关：confirm / release 均不写 outbox，relay 返回 0")
    void gatedOffWritesNothing() {
        CreateResult a = onlineFlash("门控关券");
        String order = nextOrder();
        marketing.claimInventory(a.activityId(), null, 1, "u1", order);
        marketing.confirmGrant(a.activityId(), order, new BigDecimal("9.90"), "DEC-1");
        String grantNo = onlyGrant(order).getGrantNo();
        marketing.releaseGrant(order, a.activityId());

        assertTrue(outboxRepo.findByGrantNoOrderByIdAsc(grantNo).isEmpty(),
                "门控关时 confirm/release 不该写任何 outbox 事件");
        assertEquals(0, relay.relayOnce(), "门控关时中继直接返回 0，不扫库、不投递");
    }

    // ---- helpers ----

    private static long nextSpu() { return SPU.incrementAndGet(); }
    private static String nextOrder() { return "OFF" + ORDER.incrementAndGet(); }

    private ActivityGrantEntity onlyGrant(String order) {
        List<ActivityGrantEntity> grants = marketing.grantsOfOrder(order);
        assertEquals(1, grants.size(), "该订单应恰有一条发放记录");
        return grants.get(0);
    }

    private CreateResult onlineFlash(String name) {
        CreateResult r = marketing.create(flashReq(name, nextSpu()));
        marketing.changeStatus(r.activityId(), r.version(), ActivityStatus.ONLINE.code());
        return r;
    }

    private ActivityCreateRequest flashReq(String name, long spu) {
        long now = System.currentTimeMillis();
        return new ActivityCreateRequest(
                null, null, name, "grant-outbox", 1, name,
                now - 3_600_000L, now + 3_600_000L, 1, null, 1, 100,
                1, new BigDecimal("9.9"), "价", null, "MAX",
                null, List.of(new ActivityCreateRequest.SpuBinding(1, spu)), null, null,
                null, null);
    }
}
