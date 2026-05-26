package com.lrj.drools.service;

import com.lrj.drools.domain.Cart;
import org.kie.api.runtime.KieContainer;
import org.kie.api.runtime.KieSession;
import org.springframework.stereotype.Service;

/**
 * Step 7: 跑决策表生成的规则。
 *
 * 决策表 (CSV/XLS/XLSX) 在 KieContainer 初始化时被 SpreadsheetCompiler 编译成
 * 等价 DRL, 然后跟手写 DRL 一样进入 KieBase。所以这里的代码跟其他 service 完全
 * 一样, 看不出"它跑的是决策表"——这正是决策表的卖点: 同样的运行时, 给业务方一个
 * 他们能维护的入口。
 */
@Service
public class DecisionService {

    private final KieContainer kieContainer;

    public DecisionService(KieContainer kieContainer) {
        this.kieContainer = kieContainer;
    }

    public Cart calculate(Cart cart) {
        KieSession session = kieContainer.newKieSession("decisionSession");
        try {
            session.insert(cart);
            int fired = session.fireAllRules();
            System.out.println("[DecisionService] 决策表触发了 " + fired + " 条规则, finalAmount=" + cart.getFinalAmount());
            return cart;
        } finally {
            session.dispose();
        }
    }
}
