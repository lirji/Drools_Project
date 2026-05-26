package com.lrj.drools.service;

import com.lrj.drools.domain.Cart;
import org.kie.api.runtime.KieContainer;
import org.kie.api.runtime.KieSession;
import org.springframework.stereotype.Service;

/**
 * Step 4: not / exists 规则的运行入口。
 *
 * 跟 CartService 几乎一模一样, 唯一区别是用 "riskSession" — 演示"同一份 cart fact
 * 可以喂给不同 kbase, 跑不同维度的规则"。生产里常见模式是: discount → risk → audit
 * 这种链式调用, 每个 kbase 专注一个职责。
 */
@Service
public class RiskService {

    private final KieContainer kieContainer;

    public RiskService(KieContainer kieContainer) {
        this.kieContainer = kieContainer;
    }

    public Cart evaluate(Cart cart) {
        KieSession session = kieContainer.newKieSession("riskSession");
        try {
            session.insert(cart);
            int firedCount = session.fireAllRules();
            System.out.println("[RiskService] 触发了 " + firedCount + " 条规则, recommendations=" + cart.getRecommendations());
            return cart;
        } finally {
            session.dispose();
        }
    }
}
