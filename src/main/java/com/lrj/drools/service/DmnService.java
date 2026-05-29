package com.lrj.drools.service;

import com.lrj.drools.domain.Customer;
import org.kie.api.runtime.KieContainer;
import org.kie.api.runtime.KieRuntimeFactory;
import org.kie.dmn.api.core.DMNContext;
import org.kie.dmn.api.core.DMNDecisionResult;
import org.kie.dmn.api.core.DMNModel;
import org.kie.dmn.api.core.DMNResult;
import org.kie.dmn.api.core.DMNRuntime;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Step 17: DMN (Decision Model and Notation) 求值。
 *
 * 跟前面所有 Step 的 DRL 体系是两套独立东西:
 *   - DRL 走 KieSession.insert/fireAllRules (前向链)
 *   - DMN 走 DMNRuntime.evaluateAll(model, context) (按决策需求图 DRG 拓扑求值)
 *
 * DMNRuntime 从 dmnKBase 取, 线程安全且可复用, 所以跟 StatelessKieSession (Step 11)
 * 一样在构造时拿一次当字段持有, 不用每请求新建。DMNModel 也一次性解析好缓存。
 *
 * 求值流程:
 *   1. newContext() 拿一个空上下文
 *   2. set("Customer", map) / set("Order Amount", num) 灌入 inputData 节点的值
 *      —— key 必须跟 .dmn 里 inputData 的 name 完全一致 ("Order Amount" 带空格也要一字不差)
 *   3. evaluateAll(model, ctx) 触发引擎按 DRG 求所有决策
 *   4. getDecisionResults() 拿每个 decision 的输出 (Discount Rate / Final Price / Membership Tier)
 *
 * 注意: DMN 的 number 类型在 FEEL 引擎里是 BigDecimal, 所以返回的折扣率/价格是 BigDecimal,
 * 序列化成 JSON 是普通数字, 不影响。
 */
@Service
public class DmnService {

    // 跟 .dmn 文件里 definitions 的 namespace / name 一字不差
    private static final String NAMESPACE = "https://lrj.com/dmn/vip-pricing";
    private static final String MODEL_NAME = "VipPricing";

    private final DMNRuntime dmnRuntime;
    private final DMNModel model;

    public DmnService(KieContainer kieContainer) {
        this.dmnRuntime = KieRuntimeFactory.of(kieContainer.getKieBase("dmnKBase")).get(DMNRuntime.class);
        this.model = dmnRuntime.getModel(NAMESPACE, MODEL_NAME);
        if (model == null) {
            // 给出可诊断信息: 列出运行时实际加载到的模型, 方便排 namespace/name 写错
            String loaded = dmnRuntime.getModels().stream()
                    .map(m -> m.getNamespace() + " :: " + m.getName())
                    .collect(Collectors.joining(", "));
            throw new IllegalStateException("DMN 模型未找到 [" + NAMESPACE + " :: " + MODEL_NAME
                    + "]; 已加载: [" + loaded + "]");
        }
    }

    public Result evaluate(Customer customer, double orderAmount) {
        DMNContext ctx = dmnRuntime.newContext();
        // Customer 灌成 Map 对齐 tCustomer 结构 (key 对应 itemComponent name)
        ctx.set("Customer", Map.of(
                "name", customer.name(),
                "vipLevel", customer.vipLevel(),
                "age", customer.age()));
        ctx.set("Order Amount", orderAmount);

        DMNResult dmnResult = dmnRuntime.evaluateAll(model, ctx);

        if (dmnResult.hasErrors()) {
            String errs = dmnResult.getMessages().stream()
                    .map(Object::toString)
                    .collect(Collectors.joining("\n"));
            throw new IllegalStateException("DMN 求值出错:\n" + errs);
        }

        // 收集每个 decision 的结果 (保持 DMN 里的声明顺序)
        Map<String, Object> decisions = new LinkedHashMap<>();
        List<DMNDecisionResult> results = dmnResult.getDecisionResults();
        for (DMNDecisionResult dr : results) {
            decisions.put(dr.getDecisionName(), dr.getResult());
        }
        return new Result(decisions);
    }

    /** decisions = 决策名 → 结果, 含 Discount Rate / Final Price / Membership Tier 三项。 */
    public record Result(Map<String, Object> decisions) {}
}
