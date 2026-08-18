package com.lrj.drools.service;

import com.lrj.drools.domain.ContainmentFinding;
import com.lrj.drools.domain.Location;
import com.lrj.drools.domain.WatchTarget;
import org.kie.api.runtime.KieContainer;
import org.kie.api.runtime.KieSession;
import org.kie.api.runtime.rule.QueryResults;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Step 13: 后向链 + query 运行入口。
 *
 * 调用流程:
 *   1. newKieSession("backwardSession")
 *   2. 把所有 Location 直接关系 insert 进 working memory
 *   3. 对每个查询请求调用 session.getQueryResults("isContainedIn", thing, container)
 *      — 引擎接到这个调用后启动后向链, 递归证明 isContainedIn(thing, container) 是否成立
 *   4. results.iterator().hasNext() 为 true 即"链路存在"
 *
 * 关键 API: `KieSession.getQueryResults(queryName, args...)`
 *   - args 跟 query 声明里的参数一一对应; 都是 input 模式时, 返回的 QueryResults
 *     要么有一条 (证明成功) 要么空 (证明失败)
 *   - 想做"列出所有满足 query 的绑定" (output 模式), 要用 `Variable.v` 占位 unbound arg,
 *     当前实现走“枚举候选容器 + 逐个 boolean 检”的路径，避免 internal API 依赖，
 *     教学上也更直白
 *
 * 注意点 (vs 前向链):
 *   - **fireAllRules 不是必需** — query 求值跟 RETE agenda 解耦, 调 getQueryResults
 *     直接拉一次"反向证明"; 不像前向链要靠 fire 推动 agenda
 *   - 因此后向链是 pull-based, 跟前向链 push-based 形成对照
 */
@Service
public class BackwardChainingService {

    private final KieContainer kieContainer;

    public BackwardChainingService(KieContainer kieContainer) {
        this.kieContainer = kieContainer;
    }

    public EvaluationResult evaluate(List<Location> locations, List<Query> queries) {
        KieSession session = kieContainer.newKieSession("backwardSession");
        try {
            // 前向链场景 fact 一进 working memory 就触发 RETE 计算; 后向链场景这一步只是
            // 把"事实库"填好, 真正的推理发生在 getQueryResults 调用时。
            locations.forEach(session::insert);

            List<QueryAnswer> answers = new ArrayList<>(queries.size());
            for (Query q : queries) {
                // 后向链调用: 引擎反向证明 isContainedIn(thing, container) 是否成立。
                // 不需要 fireAllRules — query 求值是同步的、独立于 agenda 的。
                QueryResults rs = session.getQueryResults("isContainedIn", q.thing(), q.container());
                boolean contained = rs.iterator().hasNext();
                answers.add(new QueryAnswer(q.thing(), q.container(), contained));
            }

            // 附加场景: 给定 thing, 找出所有 (直接 + 间接) 容器。
            // 不走 Variable.v 那条 unbound 输出路径, 而是"枚举 locations 里出现过的容器,
            // 对每个候选做 boolean 后向链证明", 教学价值更高 — 让人直观看到 query 是
            // 可复用的"证明子程序"。
            List<ContainerLookup> lookups = new ArrayList<>(queries.size());
            for (Query q : queries) {
                Set<String> candidates = collectAllContainers(locations);
                List<String> ancestors = new ArrayList<>();
                for (String candidate : candidates) {
                    QueryResults rs = session.getQueryResults("isContainedIn", q.thing(), candidate);
                    if (rs.iterator().hasNext()) {
                        ancestors.add(candidate);
                    }
                }
                lookups.add(new ContainerLookup(q.thing(), ancestors));
            }

            return new EvaluationResult(answers, lookups);
        } finally {
            session.dispose();
        }
    }

    /**
     * Step 13 扩展: 后向链嵌进前向链 LHS。
     *
     * 跟 evaluate() 的 pull 路径 (getQueryResults) 不同, 这里靠 **fireAllRules** 跑前向链:
     *   1. insert 一批 Location (事实库) + 一批 WatchTarget (驱动 fact)
     *   2. fireAllRules —— 规则 "Forward rule pulls backward query via ?isContainedIn"
     *      在 LHS 用 ?isContainedIn($thing, $zone;) 反向证明; 成立则 insert ContainmentFinding
     *   3. 从 working memory 捞出所有 ContainmentFinding 返回
     *
     * 关键点: 同一个 isContainedIn query 既能被 Java 侧 pull (evaluate), 又能被规则 LHS
     * 的 ?query 语法 push 消费 —— 后向链作为前向链的可复用推理子程序。
     */
    public DeriveResult derive(List<Location> locations, List<WatchTarget> targets) {
        KieSession session = kieContainer.newKieSession("backwardSession");
        try {
            locations.forEach(session::insert);
            targets.forEach(session::insert);

            // 前向链: ?isContainedIn 在 fire 时反向证明, 成立的 WatchTarget 会 insert 出
            // 对应的 ContainmentFinding。
            session.fireAllRules();

            List<ContainmentFinding> findings = new ArrayList<>();
            for (Object obj : session.getObjects()) {
                if (obj instanceof ContainmentFinding f) {
                    findings.add(f);
                }
            }
            return new DeriveResult(findings);
        } finally {
            session.dispose();
        }
    }

    private Set<String> collectAllContainers(List<Location> locations) {
        Set<String> containers = new LinkedHashSet<>();
        for (Location l : locations) {
            containers.add(l.container());
        }
        return containers;
    }

    public record Query(String thing, String container) {}

    public record QueryAnswer(String thing, String container, boolean contained) {}

    public record ContainerLookup(String thing, List<String> ancestors) {}

    public record EvaluationResult(List<QueryAnswer> answers, List<ContainerLookup> ancestorsLookup) {}

    public record DeriveResult(List<ContainmentFinding> findings) {}
}
