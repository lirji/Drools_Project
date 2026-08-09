package com.lrj.drools.activity;

import org.junit.jupiter.api.DisplayName;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * <b>P1-2 的对拍</b>：把 {@link DecisionGoldenSetTest} 的全部用例在 <b>Drools 路径</b>上再跑一遍。
 *
 * <p><b>为什么这样对拍而不是运行时比对</b>：两条路的判据本来就该是同一个——「同样的配置 + 同样的上下文
 * 必须给出同样的钱」。金标集就是这个判据的具体化。让两个实现分别去满足<b>同一组断言</b>，
 * 比让它们互相比对更强：互相比对只能证明「两个都错得一样」不会被发现，而金标里的期望值
 * （阶梯边界 5/12/25、MAX 取 80、PRIORITY 取 10、STACK 累加 60…）是独立写死的。
 *
 * <p>父类跑默认路径（{@code java-benefit-eval=true}，纯 Java 查表 + O(N) reduce），
 * 本类把开关翻成 {@code false} 走原来的 DRL（每档一条规则 + O(N²) 自连接）。
 * 两边同绿 = 阶梯落档与折扣合并移出规则引擎后**钱没变**。
 *
 * <p>用独立的 H2 库，避免与父类的用例共享数据（两个 context 各建各的表）。
 */
@SpringBootTest
@ActiveProfiles("h2")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:actgoldendrools;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "activity.marketing.seed-demo-data=false",
        // 关键：两个开关都翻回 Drools —— 资格判定与权益求值全走 DRL
        "activity.marketing.rule-engine.java-benefit-eval=false",
        "activity.marketing.rule-engine.java-eligibility-eval=false"
})
@DisplayName("决策金标集 · 全 Drools 路径（与 Java 路径对拍）")
class DroolsBenefitGoldenSetTest extends DecisionGoldenSetTest {
}
