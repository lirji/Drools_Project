package com.lrj.drools.service;

import com.lrj.drools.domain.Customer;
import com.lrj.drools.domain.OrderItem;
import com.lrj.drools.domain.ReviewFinding;
import org.kie.api.runtime.KieContainer;
import org.kie.api.runtime.KieSession;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Step 19: LHS 量词补全 (collect / forall / eval) 运行入口。
 *
 * 跟 RiskService 一个套路 —— insert 请求里的 fact, fireAllRules, 再把规则 insert 出来的
 * ReviewFinding 从 working memory 捞回来。三条规则各展示一种 LHS 元素:
 *   collect (图书批发) / forall (全部行合规) / eval (总额超免审额度)。
 */
@Service
public class QuantifierService {

    private final KieContainer kieContainer;

    public QuantifierService(KieContainer kieContainer) {
        this.kieContainer = kieContainer;
    }

    public List<ReviewFinding> review(Customer customer, List<OrderItem> items) {
        KieSession session = kieContainer.newKieSession("quantifierSession");
        try {
            if (customer != null) {
                session.insert(customer);
            }
            for (OrderItem item : items) {
                session.insert(item);
            }
            int fired = session.fireAllRules();
            System.out.println("[QuantifierService] 触发了 " + fired + " 条规则");

            List<ReviewFinding> findings = new ArrayList<>();
            for (Object obj : session.getObjects()) {
                if (obj instanceof ReviewFinding f) {
                    findings.add(f);
                }
            }
            return findings;
        } finally {
            session.dispose();
        }
    }
}
