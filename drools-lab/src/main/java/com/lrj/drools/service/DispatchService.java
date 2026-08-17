package com.lrj.drools.service;

import com.lrj.drools.domain.Cart;
import com.lrj.drools.domain.Notice;
import org.kie.api.runtime.Channel;
import org.kie.api.runtime.KieContainer;
import org.kie.api.runtime.KieSession;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Step 20: RHS 对外 (globals / channels) 运行入口。
 *
 * fire 前做两件"接线"动作, 再插 fact:
 *   1. session.setGlobal("sink", sink) —— 注入 global, 供规则 RHS `sink.audit(...)` 调用
 *   2. session.registerChannel("notify", ...) —— 注册 exit point, 接住规则
 *      `channels["notify"].send(...)` 推出来的 Notice
 *
 * sink 与 channel 收集器都是**每请求 new** 的 (KieSession 不线程安全, 不复用),
 * 结果一并返回: auditLog 是 global 路径的产物, notices 是 channel 路径的产物。
 */
@Service
public class DispatchService {

    private final KieContainer kieContainer;

    public DispatchService(KieContainer kieContainer) {
        this.kieContainer = kieContainer;
    }

    public DispatchResult run(Cart cart) {
        KieSession session = kieContainer.newKieSession("dispatchSession");
        NotificationSink sink = new NotificationSink();
        List<Notice> notices = new ArrayList<>();
        try {
            session.setGlobal("sink", sink);
            // Channel 是单方法接口, 用 lambda 接住 RHS send 出来的对象。
            session.registerChannel("notify", (Channel) object -> {
                if (object instanceof Notice n) {
                    notices.add(n);
                }
            });

            session.insert(cart);
            if (cart.getCustomer() != null) {
                session.insert(cart.getCustomer());
            }
            int fired = session.fireAllRules();
            System.out.println("[DispatchService] 触发了 " + fired + " 条规则, audit="
                    + sink.getAuditLog().size() + " notices=" + notices.size());

            return new DispatchResult(sink.getAuditLog(), notices);
        } finally {
            session.dispose();
        }
    }

    public record DispatchResult(List<String> auditLog, List<Notice> notices) {}
}
