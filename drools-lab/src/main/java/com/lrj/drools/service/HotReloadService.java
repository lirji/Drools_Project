package com.lrj.drools.service;

import com.lrj.drools.domain.Cart;
import org.kie.api.KieBase;
import org.kie.api.builder.Message;
import org.kie.api.builder.Results;
import org.kie.api.io.ResourceType;
import org.kie.api.runtime.KieSession;
import org.kie.internal.utils.KieHelper;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Step 9: 运行时 DRL 编译 + KieBase 缓存。
 *
 * 适用场景:
 *   - 业务方把 DRL 存在数据库, 应用启动时 / 用户操作时拉下来编译
 *   - LLM 生成 DRL, 应用即时验证 + 缓存可用
 *   - A/B 实验: 不发版本切换规则
 *
 * 跟生产 KieScanner 路径的差别:
 *   - KieScanner: KJAR + Maven repo, 引擎轮询版本号, 自动替换 KieBase, 适合"规则跟代码独立发版"
 *   - 本路径: DRL 字符串直接传入, 应用控制何时编译, 适合"规则即数据"
 *   - 核心机制完全一样: 都是 DRL → KieBase 的运行时编译; KieScanner 就是定时调用本服务的 upsert
 *
 * KieHelper 在 org.kie.internal.utils 包里, 是 Drools 提供的"测试/工具"级 API,
 * 包名带 internal 表示稳定性弱于公共 API; 生产建议用 KieFileSystem + KieBuilder
 * 走完整流程 (像 DroolsConfig.kieContainer() 那样)。学习场景 KieHelper 一行解决。
 */
@Service
public class HotReloadService {

    // 用 ConcurrentHashMap 因为 Spring Service 是单例, upsert 可能并发触发
    private final Map<String, KieBase> registry = new ConcurrentHashMap<>();

    /**
     * 编译 DRL 字符串成 KieBase, 替换 registry 里同名的旧 KieBase。
     * 编译失败抛 IllegalArgumentException, 错误信息里带 DRL 行号 + 原因。
     *
     * 注意: 老 KieSession 不会被影响 — 一个 session 关联到它创建时的 KieBase 引用,
     * 即使 registry 里 KieBase 被换掉, 已经在跑的 session 继续用老 KieBase 直到 dispose。
     * 这是热加载安全性的关键: 不打断进行中的请求。
     */
    public void upsert(String name, String drl) {
        KieHelper helper = new KieHelper();
        helper.addContent(drl, ResourceType.DRL);

        Results results = helper.verify();
        if (results.hasMessages(Message.Level.ERROR)) {
            String detail = results.getMessages(Message.Level.ERROR).stream()
                    .map(m -> "line " + m.getLine() + ": " + m.getText())
                    .collect(Collectors.joining("\n"));
            throw new IllegalArgumentException("DRL 编译失败:\n" + detail);
        }

        KieBase newBase = helper.build();
        registry.put(name, newBase);
        System.out.println("[HotReload] upsert '" + name + "' OK, rules=" + newBase.getKiePackages().stream()
                .mapToInt(p -> p.getRules().size()).sum());
    }

    /**
     * 用 name 对应的 KieBase 跑 cart, 返回 fire count + (被规则改过的) cart。
     */
    public Result execute(String name, Cart cart) {
        KieBase base = registry.get(name);
        if (base == null) {
            throw new IllegalArgumentException("未注册的规则名: " + name
                    + " (已注册: " + registry.keySet() + ")");
        }
        KieSession session = base.newKieSession();
        try {
            session.insert(cart);
            int fired = session.fireAllRules();
            return new Result(fired, cart);
        } finally {
            session.dispose();
        }
    }

    public java.util.Set<String> registered() {
        return registry.keySet();
    }

    public record Result(int firedCount, Cart cart) {}
}
