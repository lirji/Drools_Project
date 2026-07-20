package com.lrj.drools.service;

import com.lrj.drools.audit.AuditEvent;
import com.lrj.drools.audit.RuleAuditListener;
import com.lrj.drools.domain.Cart;
import org.kie.api.runtime.KieContainer;
import org.kie.api.runtime.KieSession;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Step 5: agenda-group 流水线驱动。
 *
 * 重点是 setFocus 的"反向压栈"语义:
 *   想要执行顺序 validate → discount → risk,
 *   setFocus 顺序要写成 risk → discount → validate,
 *   因为 agenda 是 LIFO 栈, 后压的先弹。
 *
 * notify 阶段没在这里 setFocus, 因为 DRL 里那条规则标了 auto-focus = true,
 * 一旦匹配会自动把自己 group 挂到栈上。
 */
@Service
public class PipelineService {

    private final KieContainer kieContainer;

    public PipelineService(KieContainer kieContainer) {
        this.kieContainer = kieContainer;
    }

    public Cart run(Cart cart) {
        return runInternal(cart, null).cart();
    }

    /**
     * Step 6: 跑 pipeline 并附带结构化 audit 轨迹。
     * 跟 run() 共用 runInternal, 唯一区别是挂了 RuleAuditListener。
     */
    public AuditedRun runWithAudit(Cart cart) {
        return runInternal(cart, new Object());  // 第二个参数只是 marker, 表示要 audit
    }

    private AuditedRun runInternal(Cart cart, Object auditMarker) {
        KieSession session = kieContainer.newKieSession("pipelineSession");
        RuleAuditListener audit = (auditMarker == null) ? null : RuleAuditListener.attachTo(session);
        try {
            session.insert(cart);

            // 反向压栈: 最后想执行的 group 最先 setFocus, 最早想执行的最后 setFocus
            session.getAgenda().getAgendaGroup("risk").setFocus();
            session.getAgenda().getAgendaGroup("discount").setFocus();
            session.getAgenda().getAgendaGroup("validate").setFocus();
            // notify 故意不 setFocus, 靠 DRL 里 auto-focus 触发

            int fired = session.fireAllRules();
            System.out.println("[PipelineService] 总触发 " + fired + " 条规则, finalAmount=" + cart.getFinalAmount());
            return new AuditedRun(cart, audit == null ? List.of() : audit.events());
        } finally {
            session.dispose();
        }
    }

    public record AuditedRun(Cart cart, List<AuditEvent> auditTrail) {}
}
