package com.lrj.drools.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.kie.api.event.rule.AfterMatchFiredEvent;
import org.kie.api.event.rule.AgendaEventListener;
import org.kie.api.event.rule.AgendaGroupPoppedEvent;
import org.kie.api.event.rule.AgendaGroupPushedEvent;
import org.kie.api.event.rule.BeforeMatchFiredEvent;
import org.kie.api.event.rule.MatchCancelledEvent;
import org.kie.api.event.rule.MatchCreatedEvent;
import org.kie.api.event.rule.ObjectDeletedEvent;
import org.kie.api.event.rule.ObjectInsertedEvent;
import org.kie.api.event.rule.ObjectUpdatedEvent;
import org.kie.api.event.rule.RuleFlowGroupActivatedEvent;
import org.kie.api.event.rule.RuleFlowGroupDeactivatedEvent;
import org.kie.api.event.rule.RuleRuntimeEventListener;
import org.kie.api.runtime.KieSession;

/**
 * Step 15: 把规则引擎运行轨迹打成 Micrometer 指标 (而不是 Step 6 那样攒成事件数组)。
 *
 * 跟 Step 6 的 RuleAuditListener 是同一套 listener 接口、同样按请求挂一个实例,
 * 区别只在"输出去向":
 *   - RuleAuditListener  → 攒进 List<AuditEvent>, 跟单次请求结果一起返回 (单请求级排障)
 *   - MeteredRuleListener → 累加进全局 MeterRegistry, 经 /actuator/prometheus 抓取 (聚合监控)
 *
 * 发出的指标 (都带 session tag, fired 额外带 rule tag):
 *   drools.rules.fired{session,rule}   counter  每条规则触发次数 → 看哪条规则最热
 *   drools.matches.created{session}    counter  agenda 上产生的 activation 数
 *   drools.matches.cancelled{session}  counter  被撤销的 activation (not 反向触发 / retract)
 *   drools.facts{session,op}           counter  working memory 增/改/删, op=inserted|updated|deleted
 *
 * 注: fireAllRules 的耗时不在这里——listener 拿不到"整段 fire"的边界, 那是个 Timer,
 * 包在 service 的 fireAllRules 调用外层 (见 MeteredDiscountService)。
 *
 * 跟 KieSession 一样按请求新建即可; MeterRegistry 是线程安全的, 多 session 并发累加没问题。
 * Micrometer 的 Counter.builder(...).register(registry) 对相同 meter id 幂等 (返回已存在实例),
 * 所以每次事件都 builder→register→increment 是安全的, 不会重复创建。
 */
public class MeteredRuleListener implements AgendaEventListener, RuleRuntimeEventListener {

    private final MeterRegistry registry;
    private final String sessionName;

    private MeteredRuleListener(MeterRegistry registry, String sessionName) {
        this.registry = registry;
        this.sessionName = sessionName;
    }

    /** 创建实例并挂到 session 上 (两个 listener 接口都挂一次)。 */
    public static MeteredRuleListener attachTo(KieSession session, MeterRegistry registry, String sessionName) {
        MeteredRuleListener listener = new MeteredRuleListener(registry, sessionName);
        session.addEventListener((AgendaEventListener) listener);
        session.addEventListener((RuleRuntimeEventListener) listener);
        return listener;
    }

    // ───────────── AgendaEventListener ─────────────

    @Override
    public void afterMatchFired(AfterMatchFiredEvent event) {
        Counter.builder("drools.rules.fired")
                .description("每条规则被 fire 的次数")
                .tag("session", sessionName)
                .tag("rule", event.getMatch().getRule().getName())
                .register(registry)
                .increment();
    }

    @Override
    public void matchCreated(MatchCreatedEvent event) {
        counter("drools.matches.created", "agenda 上新产生的 activation 数").increment();
    }

    @Override
    public void matchCancelled(MatchCancelledEvent event) {
        counter("drools.matches.cancelled", "被撤销的 activation (LHS 失配 / retract / not 反向触发)").increment();
    }

    @Override public void beforeMatchFired(BeforeMatchFiredEvent event) {}
    @Override public void agendaGroupPushed(AgendaGroupPushedEvent event) {}
    @Override public void agendaGroupPopped(AgendaGroupPoppedEvent event) {}
    @Override public void beforeRuleFlowGroupActivated(RuleFlowGroupActivatedEvent event) {}
    @Override public void afterRuleFlowGroupActivated(RuleFlowGroupActivatedEvent event) {}
    @Override public void beforeRuleFlowGroupDeactivated(RuleFlowGroupDeactivatedEvent event) {}
    @Override public void afterRuleFlowGroupDeactivated(RuleFlowGroupDeactivatedEvent event) {}

    // ───────────── RuleRuntimeEventListener ─────────────

    @Override
    public void objectInserted(ObjectInsertedEvent event) {
        factCounter("inserted").increment();
    }

    @Override
    public void objectUpdated(ObjectUpdatedEvent event) {
        factCounter("updated").increment();
    }

    @Override
    public void objectDeleted(ObjectDeletedEvent event) {
        factCounter("deleted").increment();
    }

    // ───────────── helpers ─────────────

    private Counter counter(String name, String description) {
        return Counter.builder(name)
                .description(description)
                .tag("session", sessionName)
                .register(registry);
    }

    private Counter factCounter(String op) {
        return Counter.builder("drools.facts")
                .description("working memory fact 操作次数")
                .tag("session", sessionName)
                .tag("op", op)
                .register(registry);
    }
}
