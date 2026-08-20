package com.lrj.drools.activity.service;

import com.lrj.drools.activity.config.GrantOutboxProperties;
import com.lrj.drools.activity.persistence.ActivityGrantOutboxEntity;
import com.lrj.drools.activity.persistence.ActivityGrantOutboxRepository;
import com.lrj.drools.activity.spi.GrantEvent;
import com.lrj.drools.activity.spi.GrantEventDispatcher;
import com.lrj.drools.activity.tenant.TenantContext;
import com.lrj.drools.activity.tenant.TenantIds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;

/**
 * 发放传播中继（仿 recon {@code AlertRelayService}）：读 {@code activity_grant_outbox} 可投递条目
 * （PENDING 首投 + FAILED 补投），经 {@link GrantEventDispatcher} at-least-once 推给下游，成功置 SENT、
 * 失败置 FAILED + attempt++。
 *
 * <p><b>多租户</b>：后台无请求上下文，先经 {@link GrantOutboxTenantScanner}（JDBC）跨租户发现「哪些租户有
 * 待投递条目」，再逐租户 {@link TenantContext#callWith} 回到 JPA 的 @TenantId 隔离读写（与
 * {@code ActivityLifecycleScheduleService} 同款）。
 *
 * <p><b>脱离写事务、每条一短事务</b>：由 {@code GrantOutboxRelayScheduler}（local）或
 * {@code GrantOutboxRelayXxlJobHandler}（xxl）驱动，<b>绝不</b>在 confirm/release 的写事务内发送。外层
 * {@code relayOnce} 以 {@code NOT_SUPPORTED} 挂起任何环境事务；每条投递后用 {@code REQUIRES_NEW} 短事务
 * 独立置态——一条失败不回滚账、不中断其它条目，投递本身在事务外执行（不持长事务锁跨网络 I/O）。
 *
 * <p><b>门控</b>：{@code activity.grant-outbox.enabled=false}（默认）时直接返回，不扫库、不投递（对既有零影响）。
 */
@Service
public class GrantOutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(GrantOutboxRelay.class);

    private final ActivityGrantOutboxRepository outbox;
    private final GrantOutboxTenantScanner tenantScanner;
    private final GrantEventDispatcher dispatcher;
    private final GrantOutboxProperties props;
    private final TransactionTemplate txTemplate;

    public GrantOutboxRelay(ActivityGrantOutboxRepository outbox,
                            GrantOutboxTenantScanner tenantScanner,
                            GrantEventDispatcher dispatcher,
                            GrantOutboxProperties props,
                            PlatformTransactionManager txManager) {
        this.outbox = outbox;
        this.tenantScanner = tenantScanner;
        this.dispatcher = dispatcher;
        this.props = props;
        this.txTemplate = new TransactionTemplate(txManager);
        this.txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 中继一轮：跨租户取可投递条目，逐条投递并置态。返回本轮成功投递（置 SENT）的条数。
     * 门控关时直接返回 0；单条/单租户异常被吞并计失败，不中断本轮其它条目。
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public int relayOnce() {
        if (!props.isEnabled()) {
            return 0;
        }
        int maxAttempt = Math.max(1, props.getMaxAttempt());
        Instant now = Instant.now();
        List<String> tenants = tenantScanner.findTenantsWithRetryable(maxAttempt, now);
        int sent = 0;
        for (String tenant : tenants) {
            if (!TenantIds.isValidExternal(tenant)) {
                log.error("[grant-outbox] 跳过非法或保留租户 id：{}", tenant);
                continue;
            }
            try {
                sent += TenantContext.callWith(tenant, () -> relayTenant(maxAttempt, now));
            } catch (RuntimeException exception) {
                // 单租户一轮失败不能杀死后续租户；逐条失败已在 relayEntry 内隔离。
                log.error("[grant-outbox] 中继租户 {} 失败", tenant, exception);
            }
        }
        return sent;
    }

    /** 当前租户上下文内拉一批可投递条目并逐条中继。 */
    private int relayTenant(int maxAttempt, Instant now) {
        List<ActivityGrantOutboxEntity> batch =
                outbox.findRetryable(maxAttempt, now, PageRequest.of(0, Math.max(1, props.getPollBatchSize())));
        int sent = 0;
        for (ActivityGrantOutboxEntity entry : batch) {
            if (relayEntry(entry)) {
                sent++;
            }
        }
        return sent;
    }

    private boolean relayEntry(ActivityGrantOutboxEntity entry) {
        if (entry.getAmountMinor() == null) {
            // amount_minor 声明 NOT NULL，正常写路径不可达；一旦被脏行 / schema 漂移破坏，下方拆箱进
            // GrantEvent 的 primitive long 会抛 NPE 并逸出两个 try、中止整个租户批次（毒丸）。前置跳过
            // 把租户级毒丸降级为单条可跳过，不饿死同批高 id 条目。
            log.error("[grant-outbox] 跳过 amount_minor 为 null 的坏行 id={} grantNo={}，需人工介入",
                    entry.getId(), entry.getGrantNo());
            // 置 FAILED 让 attempt 累加、到 maxAttempt 后退出补投/扫描集，避免坏行永停 PENDING 每轮刷屏 + 空扫。
            // 置态短事务瞬时故障照契约吞掉（不中断本轮其它条目），下轮再试。
            try {
                txTemplate.executeWithoutResult(status -> markFailedOrDead(entry, Instant.now()));
            } catch (RuntimeException persistFailure) {
                log.warn("[grant-outbox] 坏行置 FAILED/DEAD 失败 id={}，下轮再试", entry.getId(), persistFailure);
            }
            return false;
        }
        GrantEvent event = new GrantEvent(entry.getGrantNo(), entry.getOrderId(), entry.getActivityId(),
                entry.getEventType(), entry.getEntryType(), entry.getAmountMinor(), entry.getCurrency(),
                entry.getPayload(), entry.getAttempt());

        boolean ok;
        try {
            ok = dispatcher.dispatch(event); // 外部投递在短事务外执行
        } catch (RuntimeException dispatchFailure) {
            log.warn("[grant-outbox] dispatch 抛异常 idem={}，置 FAILED", event.idempotencyKey(), dispatchFailure);
            ok = false;
        }

        boolean success = ok;
        boolean[] firstSent = {false};
        try {
            txTemplate.executeWithoutResult(status -> {
                Instant now = Instant.now();
                if (success) {
                    // 以 CAS 影响行数为准计数：返回 1 才是本轮首次 SENT 转换，0 是已被并发轮置 SENT。
                    firstSent[0] = outbox.markSent(entry.getId(), now) == 1;
                } else {
                    markFailedOrDead(entry, now);
                }
            });
        } catch (RuntimeException persistFailure) {
            // 置态短事务的瞬时故障也必须被吞：中继契约是「投递失败不中断本轮其它条目」。该条保持原态
            // （PENDING/FAILED/DEAD），由下轮补投（at-least-once，下游按幂等键去重）。
            log.warn("[grant-outbox] 置态失败 idem={}，保持原态待下轮补投", event.idempotencyKey(), persistFailure);
            return false;
        }
        return firstSent[0];
    }

    /**
     * 失败置态（KI-9 修复）：未触顶 → FAILED + 指数退避 nextAttemptAt（避一次可恢复下游故障在 tick 间隔耗尽重试）；
     * attempt+1 达 maxAttempt → DEAD 死信（退出自动补投但绝不静默丢弃，须 {@link #redriveDeadLetters} 复活）。
     */
    private void markFailedOrDead(ActivityGrantOutboxEntity entry, Instant now) {
        int maxAttempt = Math.max(1, props.getMaxAttempt());
        if (entry.getAttempt() + 1 >= maxAttempt) {
            outbox.markDead(entry.getId(), now);
            log.error("[grant-outbox] 事件达最大重试({})进入死信 DEAD，须 redrive 复活 grantNo={} eventType={} attempt={}",
                    maxAttempt, entry.getGrantNo(), entry.getEventType(), entry.getAttempt() + 1);
        } else {
            outbox.markFailed(entry.getId(), now, backoffUntil(entry.getAttempt(), now));
        }
    }

    /** 指数退避：next = now + min(base * 2^attempt, cap)。attempt 从 0 起——首次失败退避 base。 */
    private Instant backoffUntil(int attempt, Instant now) {
        long base = Math.max(0L, props.getRetryBackoffBaseMs());
        long cap = Math.max(base, props.getRetryBackoffMaxMs());
        long delay = base;
        for (int i = 0; i < attempt && delay < cap; i++) {
            delay = Math.min(delay * 2, cap);
        }
        return now.plusMillis(Math.min(delay, cap));
    }

    /**
     * 重投死信（KI-9）：跨租户把 {@code DEAD} 重置回 {@code PENDING}（attempt 归 0、清退避），供下游恢复后
     * 人工/管理端一键补投。门控关时返回 0。返回重置的总条数。
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public int redriveDeadLetters() {
        if (!props.isEnabled()) {
            return 0;
        }
        Instant now = Instant.now();
        int total = 0;
        for (String tenant : tenantScanner.findTenantsWithDead()) {
            if (!TenantIds.isValidExternal(tenant)) {
                continue;
            }
            try {
                total += TenantContext.callWith(tenant,
                        () -> txTemplate.execute(status -> outbox.redriveDead(now)));
            } catch (RuntimeException exception) {
                log.error("[grant-outbox] redrive 租户 {} 失败", tenant, exception);
            }
        }
        if (total > 0) {
            log.info("[grant-outbox] redrive 死信复活 {} 条", total);
        }
        return total;
    }
}
