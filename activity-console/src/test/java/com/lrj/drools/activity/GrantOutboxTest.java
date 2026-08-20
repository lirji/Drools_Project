package com.lrj.drools.activity;

import com.lrj.drools.activity.config.GrantOutboxProperties;
import com.lrj.drools.activity.domain.ActivityCreateRequest;
import com.lrj.drools.activity.domain.ActivityStatus;
import com.lrj.drools.activity.persistence.ActivityGrantEntity;
import com.lrj.drools.activity.persistence.ActivityGrantOutboxEntity;
import com.lrj.drools.activity.persistence.ActivityGrantOutboxRepository;
import com.lrj.drools.activity.service.ActivityMarketingService;
import com.lrj.drools.activity.service.ActivityMarketingService.CreateResult;
import com.lrj.drools.activity.service.GrantOutboxRelay;
import com.lrj.drools.activity.spi.GrantEvent;
import com.lrj.drools.activity.spi.GrantEventDispatcher;
import com.lrj.drools.activity.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>发放传播 outbox</b>（transactional outbox，推模式）：发放确认/冲正同事务写事件、中继 at-least-once 推送。
 *
 * <p>门控开（{@code enabled=true}）+ {@code relay-mode=off}（禁自动调度，测试手动 {@code relayOnce}）。
 * dispatcher 由 {@link RecordingDispatcher} 接管（{@code @Primary} 覆盖 logging 默认），可控成功/失败/抛异常
 * 并记录投递内容。门控关时的零写入回归见 {@code GrantOutboxGatingTest}。
 */
@SpringBootTest
@ActiveProfiles("h2")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:grantoutbox;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "activity.marketing.seed-catalog-data=false",
        "activity.marketing.seed-district-data=false",
        "activity.grant-outbox.enabled=true",
        "activity.grant-outbox.relay-mode=off",
        "activity.grant-outbox.max-attempt=3",
        "activity.grant-outbox.retry-backoff-base-ms=0"
})
@DisplayName("发放传播 outbox：同事务入队 / 中继投递 / 幂等 / 重试")
class GrantOutboxTest {

    private static final AtomicLong SPU = new AtomicLong(880_000L);
    private static final AtomicLong ORDER = new AtomicLong(1L);

    @Autowired ActivityMarketingService marketing;
    @Autowired ActivityGrantOutboxRepository outboxRepo;
    @Autowired GrantOutboxRelay relay;
    @Autowired GrantOutboxProperties props;
    @Autowired RecordingDispatcher dispatcher;

    @TestConfiguration
    static class Dispatchers {
        @Bean
        @Primary
        RecordingDispatcher recordingDispatcher() {
            return new RecordingDispatcher();
        }
    }

    /** 可控 dispatcher：SUCCESS 记录并返回 true；FAIL 返回 false；THROW 抛异常。中继据此置 SENT/FAILED。 */
    enum Mode { SUCCESS, FAIL, THROW }

    static class RecordingDispatcher implements GrantEventDispatcher {
        volatile Mode mode = Mode.SUCCESS;
        final List<GrantEvent> dispatched = new ArrayList<>();

        @Override
        public synchronized boolean dispatch(GrantEvent event) {
            switch (mode) {
                case THROW -> throw new IllegalStateException("模拟下游不可达");
                case FAIL -> { return false; }
                default -> {
                    dispatched.add(event);
                    return true;
                }
            }
        }
    }

    @BeforeEach
    void setUp() {
        TenantContext.set("__dev__");
        dispatcher.mode = Mode.SUCCESS;
        dispatcher.dispatched.clear();
        // h2:mem 跨用例常驻（DB_CLOSE_DELAY=-1），清掉本租户 outbox 残留，让中继计数按用例隔离。
        // 写平面允许删，且这是测试库；生产 outbox 永不 delete（无 delete 业务路径）。
        outboxRepo.deleteAll();
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    @Nested
    @DisplayName("同事务入队")
    class Enqueue {

        @Test
        @DisplayName("confirm 追 ISSUE 分录后同事务写 GRANT_ISSUED（PENDING，+X，payload 带幂等键）")
        void confirmEnqueuesGrantIssued() {
            CreateResult a = onlineFlash("确认入队券");
            String order = nextOrder();
            marketing.claimInventory(a.activityId(), null, 1, "u1", order);
            String grantNo = onlyGrant(order).getGrantNo();

            marketing.confirmGrant(a.activityId(), order, new BigDecimal("9.90"), "DEC-1");

            ActivityGrantOutboxEntity ev = outboxRepo
                    .findFirstByGrantNoAndEventType(grantNo, ActivityGrantOutboxEntity.EVENT_GRANT_ISSUED)
                    .orElseThrow();
            assertEquals(ActivityGrantOutboxEntity.STATUS_PENDING, ev.getStatus(), "入队即 PENDING（未中继）");
            assertEquals(990L, ev.getAmountMinor(), "GRANT_ISSUED = +amount×100");
            assertEquals("ISSUE", ev.getEntryType());
            assertEquals(order, ev.getOrderId());
            assertEquals(a.activityId(), ev.getActivityId());
            assertEquals(0, ev.getAttempt());
            assertNull(ev.getSentAt());
            assertTrue(ev.getPayload() != null && ev.getPayload().contains(grantNo + ":GRANT_ISSUED"),
                    "payload 应带幂等键 grant_no:event_type，实得: " + ev.getPayload());
        }

        @Test
        @DisplayName("release(CONFIRMED→RELEASED) 追 REVERSAL 后同事务写 GRANT_REVERSED（−X）")
        void releaseConfirmedEnqueuesGrantReversed() {
            CreateResult a = onlineFlash("冲正入队券");
            String order = nextOrder();
            marketing.claimInventory(a.activityId(), null, 1, "u1", order);
            marketing.confirmGrant(a.activityId(), order, new BigDecimal("9.90"), null);
            String grantNo = onlyGrant(order).getGrantNo();

            marketing.releaseGrant(order, a.activityId());

            List<ActivityGrantOutboxEntity> events = outboxRepo.findByGrantNoOrderByIdAsc(grantNo);
            assertEquals(2, events.size(), "退已确认发放 = GRANT_ISSUED + GRANT_REVERSED 两条事件");
            assertEquals(ActivityGrantOutboxEntity.EVENT_GRANT_ISSUED, events.get(0).getEventType());
            assertEquals(ActivityGrantOutboxEntity.EVENT_GRANT_REVERSED, events.get(1).getEventType());
            assertEquals(990L, events.get(0).getAmountMinor());
            assertEquals(-990L, events.get(1).getAmountMinor(), "GRANT_REVERSED 取负已存 ISSUE 分额，符号对称");
            assertEquals("REVERSAL", events.get(1).getEntryType());
        }

        @Test
        @DisplayName("HELD→RELEASED（未付即取消）不写任何事件")
        void releaseHeldEnqueuesNothing() {
            CreateResult a = onlineFlash("未付取消券");
            String order = nextOrder();
            marketing.claimInventory(a.activityId(), null, 1, "u1", order);
            String grantNo = onlyGrant(order).getGrantNo();

            marketing.releaseGrant(order, a.activityId());

            assertTrue(outboxRepo.findByGrantNoOrderByIdAsc(grantNo).isEmpty(),
                    "从未确认发放的释放不该产生任何传播事件（无 ISSUE→无 REVERSED）");
        }

        @Test
        @DisplayName("confirm 幂等重放：重复回调不重复写 GRANT_ISSUED（uk 兜底）")
        void confirmReplayDoesNotDuplicate() {
            CreateResult a = onlineFlash("幂等入队券");
            String order = nextOrder();
            marketing.claimInventory(a.activityId(), null, 1, "u1", order);
            marketing.confirmGrant(a.activityId(), order, new BigDecimal("9.90"), "DEC-1");
            String grantNo = onlyGrant(order).getGrantNo();

            // 携带不同金额的迟到重复回调（走 replay 分支）
            marketing.confirmGrant(a.activityId(), order, new BigDecimal("20.00"), "DEC-2");

            List<ActivityGrantOutboxEntity> issued = outboxRepo.findByGrantNoOrderByIdAsc(grantNo);
            assertEquals(1, issued.size(), "幂等重放不重复入队（同 grant_no,event_type 唯一）");
            assertEquals(990L, issued.get(0).getAmountMinor(), "金额仍是首次的 +990，不被第二次覆盖");
        }
    }

    @Nested
    @DisplayName("中继投递")
    class Relay {

        @Test
        @DisplayName("poll PENDING → dispatch 成功 → SENT（sentAt 落，dispatcher 收到该事件）")
        void relaySuccessMarksSent() {
            CreateResult a = onlineFlash("中继成功券");
            String order = nextOrder();
            marketing.claimInventory(a.activityId(), null, 1, "u1", order);
            marketing.confirmGrant(a.activityId(), order, new BigDecimal("9.90"), null);
            String grantNo = onlyGrant(order).getGrantNo();

            int sent = relay.relayOnce();

            assertEquals(1, sent, "本轮应成功投递 1 条");
            ActivityGrantOutboxEntity ev = outboxRepo
                    .findFirstByGrantNoAndEventType(grantNo, ActivityGrantOutboxEntity.EVENT_GRANT_ISSUED)
                    .orElseThrow();
            assertEquals(ActivityGrantOutboxEntity.STATUS_SENT, ev.getStatus());
            assertNotNull(ev.getSentAt(), "SENT 应落 sentAt");
            assertTrue(dispatcher.dispatched.stream().anyMatch(e -> e.grantNo().equals(grantNo)
                            && e.eventType().equals(ActivityGrantOutboxEntity.EVENT_GRANT_ISSUED)),
                    "dispatcher 应收到该 GRANT_ISSUED 事件");
        }

        @Test
        @DisplayName("dispatch 返回 false → FAILED + attempt++，恢复后重投 → SENT")
        void relayFailureThenRetrySucceeds() {
            CreateResult a = onlineFlash("中继重试券");
            String order = nextOrder();
            marketing.claimInventory(a.activityId(), null, 1, "u1", order);
            marketing.confirmGrant(a.activityId(), order, new BigDecimal("9.90"), null);
            String grantNo = onlyGrant(order).getGrantNo();

            dispatcher.mode = Mode.FAIL;
            int sent1 = relay.relayOnce();
            assertEquals(0, sent1, "投递失败不计入成功数");
            ActivityGrantOutboxEntity afterFail = outboxRepo
                    .findFirstByGrantNoAndEventType(grantNo, ActivityGrantOutboxEntity.EVENT_GRANT_ISSUED)
                    .orElseThrow();
            assertEquals(ActivityGrantOutboxEntity.STATUS_FAILED, afterFail.getStatus());
            assertEquals(1, afterFail.getAttempt(), "失败一次 attempt=1");
            assertNull(afterFail.getSentAt());

            dispatcher.mode = Mode.SUCCESS;
            int sent2 = relay.relayOnce();
            assertEquals(1, sent2, "FAILED 且 attempt<maxAttempt 的条目应被补投");
            ActivityGrantOutboxEntity afterRetry = outboxRepo
                    .findFirstByGrantNoAndEventType(grantNo, ActivityGrantOutboxEntity.EVENT_GRANT_ISSUED)
                    .orElseThrow();
            assertEquals(ActivityGrantOutboxEntity.STATUS_SENT, afterRetry.getStatus());
            assertNotNull(afterRetry.getSentAt());
        }

        @Test
        @DisplayName("dispatch 抛异常被吞 → FAILED（不使中继中断）")
        void relayDispatchThrowMarksFailed() {
            CreateResult a = onlineFlash("中继异常券");
            String order = nextOrder();
            marketing.claimInventory(a.activityId(), null, 1, "u1", order);
            marketing.confirmGrant(a.activityId(), order, new BigDecimal("9.90"), null);
            String grantNo = onlyGrant(order).getGrantNo();

            dispatcher.mode = Mode.THROW;
            int sent = relay.relayOnce();

            assertEquals(0, sent);
            ActivityGrantOutboxEntity ev = outboxRepo
                    .findFirstByGrantNoAndEventType(grantNo, ActivityGrantOutboxEntity.EVENT_GRANT_ISSUED)
                    .orElseThrow();
            assertEquals(ActivityGrantOutboxEntity.STATUS_FAILED, ev.getStatus());
            assertEquals(1, ev.getAttempt());
        }

        @Test
        @DisplayName("已 SENT 的条目不再被 poll，重复 relay 幂等（不重复投递）")
        void relayIsIdempotentAcrossRounds() {
            CreateResult a = onlineFlash("中继幂等券");
            String order = nextOrder();
            marketing.claimInventory(a.activityId(), null, 1, "u1", order);
            marketing.confirmGrant(a.activityId(), order, new BigDecimal("9.90"), null);
            String grantNo = onlyGrant(order).getGrantNo();

            assertEquals(1, relay.relayOnce());
            int before = dispatcher.dispatched.size();
            assertEquals(0, relay.relayOnce(), "已 SENT 的条目不该被再次投递");
            assertEquals(before, dispatcher.dispatched.size(), "第二轮不产生新的下游投递");
            assertEquals(ActivityGrantOutboxEntity.STATUS_SENT,
                    outboxRepo.findFirstByGrantNoAndEventType(grantNo,
                            ActivityGrantOutboxEntity.EVENT_GRANT_ISSUED).orElseThrow().getStatus());
        }

        @Test
        @DisplayName("KI-9：达 maxAttempt 落 DEAD 死信，不再被自动补投")
        void exhaustedRetriesGoesDeadLetter() {
            String grantNo = issuedGrant("死信券");

            dispatcher.mode = Mode.FAIL;
            relay.relayOnce(); // attempt 1 → FAILED
            relay.relayOnce(); // attempt 2 → FAILED
            relay.relayOnce(); // attempt 3 == maxAttempt → DEAD
            ActivityGrantOutboxEntity dead = issuedEvent(grantNo);
            assertEquals(ActivityGrantOutboxEntity.STATUS_DEAD, dead.getStatus(), "达 maxAttempt 应落 DEAD");
            assertEquals(3, dead.getAttempt());

            dispatcher.mode = Mode.SUCCESS;
            assertEquals(0, relay.relayOnce(), "DEAD 不被自动补投");
            assertEquals(ActivityGrantOutboxEntity.STATUS_DEAD, issuedEvent(grantNo).getStatus());
        }

        @Test
        @DisplayName("KI-9：redriveDeadLetters 把 DEAD 复活为 PENDING，下游恢复后重投 → SENT")
        void redriveRevivesDeadLetter() {
            String grantNo = issuedGrant("复活券");

            dispatcher.mode = Mode.FAIL;
            relay.relayOnce(); relay.relayOnce(); relay.relayOnce(); // → DEAD
            assertEquals(ActivityGrantOutboxEntity.STATUS_DEAD, issuedEvent(grantNo).getStatus());

            assertEquals(1, relay.redriveDeadLetters(), "redrive 复活 1 条");
            ActivityGrantOutboxEntity revived = issuedEvent(grantNo);
            assertEquals(ActivityGrantOutboxEntity.STATUS_PENDING, revived.getStatus(), "复活为 PENDING");
            assertEquals(0, revived.getAttempt(), "attempt 归 0");

            dispatcher.mode = Mode.SUCCESS;
            assertEquals(1, relay.relayOnce(), "复活后下游恢复即投递成功");
            assertEquals(ActivityGrantOutboxEntity.STATUS_SENT, issuedEvent(grantNo).getStatus());
        }

        @Test
        @DisplayName("KI-9：失败后退避未到不补投（防一次可恢复抖动耗尽重试）")
        void backoffHoldsBeforeNextAttempt() {
            long saved = props.getRetryBackoffBaseMs();
            props.setRetryBackoffBaseMs(3_600_000L); // 1h 退避，本轮内绝不到期
            try {
                String grantNo = issuedGrant("退避券");

                dispatcher.mode = Mode.FAIL;
                assertEquals(0, relay.relayOnce()); // FAILED + next_attempt_at = now + 1h
                ActivityGrantOutboxEntity failed = issuedEvent(grantNo);
                assertEquals(ActivityGrantOutboxEntity.STATUS_FAILED, failed.getStatus());
                assertNotNull(failed.getNextAttemptAt(), "退避应落 next_attempt_at");

                dispatcher.mode = Mode.SUCCESS; // 下游恢复，但退避未到
                assertEquals(0, relay.relayOnce(), "退避未到不补投（修掉一次抖动耗尽重试）");
                assertEquals(ActivityGrantOutboxEntity.STATUS_FAILED, issuedEvent(grantNo).getStatus(),
                        "仍 FAILED 待退避到期");
            } finally {
                props.setRetryBackoffBaseMs(saved);
            }
        }
    }

    // ---- helpers ----

    /** 建活动 + claim + confirm，返回其 grant_no（已入队 GRANT_ISSUED）。 */
    private String issuedGrant(String name) {
        CreateResult a = onlineFlash(name);
        String order = nextOrder();
        marketing.claimInventory(a.activityId(), null, 1, "u1", order);
        marketing.confirmGrant(a.activityId(), order, new BigDecimal("9.90"), null);
        return onlyGrant(order).getGrantNo();
    }

    private ActivityGrantOutboxEntity issuedEvent(String grantNo) {
        return outboxRepo.findFirstByGrantNoAndEventType(grantNo,
                ActivityGrantOutboxEntity.EVENT_GRANT_ISSUED).orElseThrow();
    }

    private static long nextSpu() { return SPU.incrementAndGet(); }
    private static String nextOrder() { return "OBX" + ORDER.incrementAndGet(); }

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
