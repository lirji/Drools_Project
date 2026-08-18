package com.lrj.drools.activity;

import org.junit.jupiter.api.DisplayName;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * 旧灰度属性兼容回归。
 *
 * <p>这两个属性曾能把生产切回另一份 DRL 资格/算额语义，而旧 DRL 不认识随机、
 * 一口价和第 N 件折。现在属性仅保留配置兼容：即使明确设为 false，也必须继续满足
 * {@link DecisionGoldenSetTest} 的同一组金标。
 */
@SpringBootTest
@ActiveProfiles("h2")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:actgoldendrools;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "activity.marketing.seed-catalog-data=false",
        // 旧环境仍可保留 false，但它们不得再切换生产求值语义。
        "activity.marketing.rule-engine.java-benefit-eval=false",
        "activity.marketing.rule-engine.java-eligibility-eval=false"
})
@DisplayName("决策金标集 · 旧 false 开关不得切换生产求值器")
class DroolsBenefitGoldenSetTest extends DecisionGoldenSetTest {
}
