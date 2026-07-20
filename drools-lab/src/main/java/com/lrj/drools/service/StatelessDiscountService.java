package com.lrj.drools.service;

import com.lrj.drools.domain.Order;
import jakarta.annotation.PostConstruct;
import org.kie.api.runtime.KieContainer;
import org.kie.api.runtime.StatelessKieSession;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Step 11: StatelessKieSession 对比 demo。
 *
 * 跟 DiscountService (stateful) 用同一组规则 (discountKBase / order-discount.drl),
 * 但派生的是 type="stateless" 的 ksession。教学要点全在两边 API 形态的差异:
 *
 * Stateful (DiscountService.calculate):
 *     KieSession s = kc.newKieSession("discountSession");
 *     try {
 *         s.insert(customer);
 *         s.insert(order);
 *         s.fireAllRules();
 *         return order;
 *     } finally { s.dispose(); }
 *
 * Stateless (本文件 calculate):
 *     stateless.execute(List.of(customer, order));
 *     return order;
 *
 * 关键差异:
 *   1. 没有 dispose() — execute() 内部封装 newSession → insertAll → fireAllRules → dispose,
 *      用错的概率为 0; stateful 漏 dispose 会泄漏 RETE 网络节点。
 *   2. execute(Iterable) 一次塞多个 fact, 比 stateful 循环 insert 简洁。
 *   3. **StatelessKieSession 线程安全, 可以注成 Spring 单例反复用**; stateful 每次请求
 *      必须 newKieSession, 不能跨请求复用。
 *   4. 完全无状态: 两次 execute 之间 working memory 不共享。这就是"stateless"的字面意思 —
 *      不是说规则集没状态, 是 execute 调用之间不留状态。
 *
 * 不能做什么:
 *   - 没有 fireAllRules / fireUntilHalt 的分别 (没法在 fire 之间干预 agenda);
 *     setFocus、insertEvent、跨调用累积统计这类需求只能用 stateful。
 *   - CEP stream mode + pseudo clock 不能用 stateless 重放, 因为每次 execute 是孤立的。
 *   - Step 10 那种"长寿命会话 + Marshaller 持久化"自然也用不上 — stateless 没有
 *     需要被 marshall 的东西。
 *
 * 适用场景:
 *   - RPC/HTTP 一次性"请求-响应"型规则评估 (本 demo 的 /discount 就属于这类, Step 2
 *     用 stateful 是"为了教学一致"; 单看业务诉求 stateless 更贴切)。
 *   - 批量评估: 一次提交 N 个 Order, 每个独立计算, 结果不互相污染。
 *   - 任何"喂数据 → 拿结果"且不需要跨调用记忆的场景。
 */
@Service
public class StatelessDiscountService {

    /**
     * StatelessKieSession 线程安全, 拿一次复用到底; 不像 KieSession 每次请求都要新建。
     */
    private final StatelessKieSession stateless;

    public StatelessDiscountService(KieContainer kieContainer) {
        this.stateless = kieContainer.newStatelessKieSession("discountStatelessSession");
    }

    @PostConstruct
    void logSetup() {
        System.out.println("[StatelessDiscountService] stateless session ready: "
                + stateless.getClass().getSimpleName());
    }

    /** 跟 DiscountService.calculate 业务结果完全等价的 stateless 版本, 一行 execute 搞定。 */
    public Order calculate(Order order) {
        // execute(Iterable) 自动 insert 每个元素 + fireAllRules + 内部 dispose。
        // order 是同一个 Java 对象引用, 规则改完直接读它即可 — stateless 没有 getObjects()
        // 给你扫工作内存 (内部 session 已 dispose 不能访问), 所以拿结果要靠 mutable fact 引用。
        stateless.execute(List.of(order.getCustomer(), order));
        return order;
    }

    /**
     * 批处理: 同一个 stateless 实例反复 execute, 每次拿一个干净的 internal session。
     * 第 N 单的 working memory 跟第 N-1 单完全隔离 — 这就是 stateless 名字的核心承诺。
     *
     * 同样的事情 stateful 要写成: for-loop 里 newKieSession + try/finally dispose,
     * 一行写不下还容易漏 dispose。
     */
    public List<Order> batch(List<Order> orders) {
        for (Order o : orders) {
            stateless.execute(List.of(o.getCustomer(), o));
        }
        return orders;
    }
}
