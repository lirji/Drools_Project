package com.lrj.drools.audit;

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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Step 6: 把规则引擎运行轨迹攒成结构化事件列表。
 *
 * 同时实现两个 listener 接口:
 *   - AgendaEventListener:        规则匹配 / 触发 / 撤销 / agenda-group 栈事件
 *   - RuleRuntimeEventListener:   working memory 的 fact 增/改/删
 *
 * 一个 RuleAuditListener 实例对应一个 KieSession (因为 sequence 是实例内自增,
 * 而且 events 是非线程安全 ArrayList — 跟 KieSession 一样按请求新建即可)。
 *
 * 注: MATCH_* 事件不带 agenda-group 名, 因为 Drools 8 公共 API
 * `org.kie.api.definition.rule.Rule` 不暴露 getAgendaGroup()
 * (只在 internal RuleImpl 上, 强转会破坏 API 边界)。想知道一条 MATCH 属于哪个
 * group, 看上下文最近的 GROUP_PUSHED 事件 — agenda 是栈, 当前栈顶就是规则所属 group。
 *
 * 用法:
 *   RuleAuditListener audit = RuleAuditListener.attachTo(kieSession);
 *   kieSession.fireAllRules();
 *   List<AuditEvent> trail = audit.events();
 */
public class RuleAuditListener implements AgendaEventListener, RuleRuntimeEventListener {

    private final List<AuditEvent> events = new ArrayList<>();
    private long seq = 0;

    /** 创建实例并挂到 session 上 (两个 listener 接口都挂一次)。 */
    public static RuleAuditListener attachTo(KieSession session) {
        RuleAuditListener listener = new RuleAuditListener();
        session.addEventListener((AgendaEventListener) listener);
        session.addEventListener((RuleRuntimeEventListener) listener);
        return listener;
    }

    public List<AuditEvent> events() {
        return Collections.unmodifiableList(events);
    }

    private void record(String type, String detail) {
        events.add(new AuditEvent(++seq, type, detail));
    }

    // ───────────── AgendaEventListener ─────────────

    @Override
    public void matchCreated(MatchCreatedEvent event) {
        record("MATCH_CREATED", "rule='" + event.getMatch().getRule().getName() + "'");
    }

    @Override
    public void matchCancelled(MatchCancelledEvent event) {
        // 关键事件: not 的右侧出现匹配 fact / LHS 失配 / retract 等都会触发
        record("MATCH_CANCELLED",
                "rule='" + event.getMatch().getRule().getName() + "' cause=" + event.getCause());
    }

    @Override
    public void beforeMatchFired(BeforeMatchFiredEvent event) {
        // 不记录 — afterMatchFired 已经够看顺序了, 记两遍只会让日志翻倍
    }

    @Override
    public void afterMatchFired(AfterMatchFiredEvent event) {
        record("MATCH_FIRED", "rule='" + event.getMatch().getRule().getName() + "'");
    }

    @Override
    public void agendaGroupPushed(AgendaGroupPushedEvent event) {
        record("GROUP_PUSHED", "group='" + event.getAgendaGroup().getName() + "'");
    }

    @Override
    public void agendaGroupPopped(AgendaGroupPoppedEvent event) {
        record("GROUP_POPPED", "group='" + event.getAgendaGroup().getName() + "'");
    }

    @Override public void beforeRuleFlowGroupActivated(RuleFlowGroupActivatedEvent event) {}
    @Override public void afterRuleFlowGroupActivated(RuleFlowGroupActivatedEvent event) {}
    @Override public void beforeRuleFlowGroupDeactivated(RuleFlowGroupDeactivatedEvent event) {}
    @Override public void afterRuleFlowGroupDeactivated(RuleFlowGroupDeactivatedEvent event) {}

    // ───────────── RuleRuntimeEventListener ─────────────

    @Override
    public void objectInserted(ObjectInsertedEvent event) {
        record("OBJECT_INSERTED", describe(event.getObject()));
    }

    @Override
    public void objectUpdated(ObjectUpdatedEvent event) {
        record("OBJECT_UPDATED", describe(event.getObject()));
    }

    @Override
    public void objectDeleted(ObjectDeletedEvent event) {
        record("OBJECT_DELETED", describe(event.getOldObject()));
    }

    private static String describe(Object o) {
        if (o == null) return "null";
        // 只打类名+简要 toString, 不放整个 fact, 否则 audit 日志会被大 fact 撑爆
        String s = o.toString();
        if (s.length() > 120) s = s.substring(0, 117) + "...";
        return o.getClass().getSimpleName() + "=" + s;
    }
}
