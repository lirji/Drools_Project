package com.lrj.drools.service;

import com.lrj.drools.domain.Order;
import com.lrj.drools.metrics.MeteredRuleListener;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.kie.api.runtime.KieContainer;
import org.kie.api.runtime.KieSession;
import org.springframework.stereotype.Service;

import java.util.function.Supplier;

/**
 * Step 15: 给规则执行加 Micrometer 指标。
 *
 * 复用 Step 2 的 discountKBase / discountSession (规则零改动), 只是在
 * "newKieSession → insert → fireAllRules → dispose" 这条标准链路上挂了:
 *   1. MeteredRuleListener  — 把 fire/match/fact 事件累加成 counter
 *   2. 一个 Micrometer Timer — 包住 fireAllRules, 记录整段 fire 的挂钟耗时 + 分位数
 *
 * 这两层加起来就是规则引擎的基础可观测性: 多热 (fired counter)、多慢 (fire timer)、
 * agenda 翻腾多剧烈 (matches created/cancelled)。指标进全局 registry, 不随请求返回,
 * 经 GET /actuator/prometheus 被 Prometheus 抓走聚合。
 *
 * 跟 Step 6 (RuleAuditListener) 的分工: Step 6 是单请求级"放大镜"(这一次到底怎么跑的),
 * Step 15 是跨请求"仪表盘"(整体趋势 / 报警阈值)。生产里两者都要, 解决的问题不同。
 */
@Service
public class MeteredDiscountService {

    private static final String SESSION = "discountSession";

    private final KieContainer kieContainer;
    private final MeterRegistry registry;
    private final Timer fireTimer;

    public MeteredDiscountService(KieContainer kieContainer, MeterRegistry registry) {
        this.kieContainer = kieContainer;
        this.registry = registry;
        // Timer 在构造时一次性注册 (meter 复用), 比每请求 builder 省一点。
        // publishPercentiles 让 /actuator/prometheus 直接吐 p50/p95/p99, 不用后端再算。
        this.fireTimer = Timer.builder("drools.session.fire")
                .description("fireAllRules 整段挂钟耗时")
                .tag("session", SESSION)
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
    }

    public Result calculate(Order order) {
        KieSession session = kieContainer.newKieSession(SESSION);
        MeteredRuleListener.attachTo(session, registry, SESSION);
        try {
            session.insert(order.getCustomer());
            session.insert(order);
            // Timer.record(Supplier) 计时并透传返回值; fire 抛异常也会记 (finally 语义)。
            // 显式转 Supplier<Integer>: 否则 fireAllRules 返回 int 会跟 record 的
            // DoubleSupplier / Runnable 重载撞歧义。
            int fired = fireTimer.record((Supplier<Integer>) session::fireAllRules);
            return new Result(order, fired);
        } finally {
            session.dispose();
        }
    }

    /** order = 折后结果 (跟 /discount/calculate 一致); rulesFired = 本次触发条数。
     *  真正想看的指标在 GET /actuator/prometheus, 这里返回够确认请求跑通即可。 */
    public record Result(Order order, int rulesFired) {}
}
