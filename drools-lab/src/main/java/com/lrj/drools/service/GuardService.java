package com.lrj.drools.service;

import com.lrj.drools.audit.AuditEvent;
import com.lrj.drools.audit.RuleAuditListener;
import com.lrj.drools.domain.Cart;
import com.lrj.drools.domain.Counter;
import com.lrj.drools.guard.ReleaseAgendaFilter;
import org.kie.api.runtime.KieContainer;
import org.kie.api.runtime.KieSession;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Step 14: 引擎安全护栏。三个生产必备的"防失控"手段, 都跑在 guardSession 上。
 *
 * 教学重点是: 规则集是会被改错的, 引擎必须有兜底, 不能指望"规则都写对"。
 */
@Service
public class GuardService {

    private final KieContainer kieContainer;

    public GuardService(KieContainer kieContainer) {
        this.kieContainer = kieContainer;
    }

    // ───────────── 护栏 1: fireAllRules(maxFires) 硬上限熔断 ─────────────

    /**
     * 失控规则 + fire 次数硬上限。
     *
     * "Runaway increment" 本会无限自增, 这里给 fireAllRules 传 maxFires:
     * 引擎 fire 满 N 条就强制返回, 请求不会挂死。这是最简单、最该默认带上的护栏 —
     * 生产里几乎所有 fireAllRules() 都应该写成 fireAllRules(上限)。
     */
    public RunawayResult runawayCapped(int startValue, int maxFires) {
        KieSession session = kieContainer.newKieSession("guardSession");
        try {
            Counter counter = new Counter(startValue);
            session.insert(counter);

            long t0 = System.nanoTime();
            int fired = session.fireAllRules(maxFires);   // ← 关键: 不传 max 就会无限循环
            long ms = (System.nanoTime() - t0) / 1_000_000;

            return new RunawayResult(fired, counter.getValue(), ms,
                    "fireAllRules(maxFires=" + maxFires + ") 截断: fire 满上限强制返回");
        } finally {
            session.dispose();
        }
    }

    // ───────────── 护栏 2: session.halt() watchdog 超时熔断 ─────────────

    /**
     * 失控规则 + 另一线程超时 halt。
     *
     * 不是所有失控都能用"fire 次数"卡 (有的规则一次 fire 就很慢)。更通用的是按
     * **挂钟时间**兜底: 主线程裸跑 fireAllRules(), 一个 watchdog 线程在 timeoutMillis
     * 后调 session.halt() —— halt 会让引擎跑完当前 activation 后优雅返回 (不是 kill 线程,
     * 不会留下脏状态)。这是 KieServer 等生产部署里"单请求超时"的标准做法。
     *
     * 注: halt() 是 KieSession 上少数几个**可以跨线程调**的方法之一。
     */
    public RunawayResult runawayWithTimeout(int startValue, long timeoutMillis) {
        KieSession session = kieContainer.newKieSession("guardSession");
        ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "drools-halt-watchdog");
            t.setDaemon(true);
            return t;
        });
        try {
            Counter counter = new Counter(startValue);
            session.insert(counter);

            watchdog.schedule(session::halt, timeoutMillis, TimeUnit.MILLISECONDS);

            long t0 = System.nanoTime();
            int fired = session.fireAllRules();            // ← 没有 max, 靠 halt 打断
            long ms = (System.nanoTime() - t0) / 1_000_000;

            return new RunawayResult(fired, counter.getValue(), ms,
                    "watchdog 在 ~" + timeoutMillis + "ms 后 halt(): 引擎优雅返回");
        } finally {
            watchdog.shutdownNow();
            session.dispose();
        }
    }

    // ───────────── 护栏 3: AgendaFilter 灰度放行 ─────────────

    /**
     * 用 ReleaseAgendaFilter 按 @release 元数据放行规则。
     *
     * 同一个 Cart 命中三条规则 (baseline / stable / canary)。allowedReleases
     * 决定哪些 release 通道的规则真正 fire, 被拦的记进 skipped 一起返回, 让你"看见"
     * 灰度生效。整个过程不改 DRL、不重编译 KieBase。
     */
    public CanaryResult canary(Cart cart, Set<String> allowedReleases) {
        KieSession session = kieContainer.newKieSession("guardSession");
        RuleAuditListener audit = RuleAuditListener.attachTo(session);
        try {
            session.insert(cart);
            ReleaseAgendaFilter filter = new ReleaseAgendaFilter(allowedReleases);
            int fired = session.fireAllRules(filter);
            return new CanaryResult(cart, fired, allowedReleases, filter.skipped(), audit.events());
        } finally {
            session.dispose();
        }
    }

    /** fireCount = 实际 fire 的规则数; finalValue = 截断时 Counter 的值; elapsedMillis = 耗时。 */
    public record RunawayResult(int fireCount, int finalValue, long elapsedMillis, String guard) {}

    /** skipped = 被 AgendaFilter 拦下没 fire 的规则; auditTrail = 复用 Step 6 的结构化轨迹。 */
    public record CanaryResult(Cart cart, int fireCount, Set<String> allowedReleases,
                               List<String> skipped, List<AuditEvent> auditTrail) {}
}
