package com.lrj.drools.service;

import com.lrj.drools.domain.Customer;
import com.lrj.drools.domain.Order;
import org.kie.api.runtime.KieContainer;
import org.kie.api.runtime.KieSession;
import org.springframework.stereotype.Service;

@Service
public class DiscountService {

    private final KieContainer kieContainer;

    public DiscountService(KieContainer kieContainer) {
        this.kieContainer = kieContainer;
    }

    /**
     * 运行 discountSession 里的所有匹配规则。
     *
     * 流程:
     *   1. 从 container 派生一个新 session (廉价，每次请求都建)
     *   2. insert() 把 fact 塞进 working memory
     *   3. fireAllRules() 触发引擎，循环匹配-执行直到没有可触发的规则
     *   4. dispose() 释放 session 资源
     */
    public Order calculate(Order order) {
        // 不复用 session 是有意的: KieSession 不是线程安全的，
        // 复用就要加锁或者用 StatelessKieSession (适合无状态规则)
        KieSession session = kieContainer.newKieSession("discountSession");
        try {
            session.insert(order.getCustomer());
            session.insert(order);
            int firedCount = session.fireAllRules();
            System.out.println("[DiscountService] 触发了 " + firedCount + " 条规则");
            return order;
        } finally {
            session.dispose();
        }
    }

    /** Hello 示例: 只插 Customer，跑 helloSession 里的规则。 */
    public int runHello(Customer customer) {
        KieSession session = kieContainer.newKieSession("helloSession");
        try {
            session.insert(customer);
            return session.fireAllRules();
        } finally {
            session.dispose();
        }
    }
}
