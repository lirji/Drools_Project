package com.lrj.drools.activity;

import com.lrj.drools.activity.domain.ActivityCreateRequest;
import com.lrj.drools.activity.domain.ActivityStatus;
import com.lrj.drools.activity.domain.ConditionNode;
import com.lrj.drools.activity.domain.DecisionMode;
import com.lrj.drools.activity.domain.SpuDiscountRequest;
import com.lrj.drools.activity.service.ActivityMarketingService;
import com.lrj.drools.activity.service.ActivityMarketingService.CreateResult;
import com.lrj.drools.activity.service.ActivityQueryService;
import com.lrj.drools.activity.service.ActivityQueryService.DiscountView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 灰度开关关闭（rule-engine.enabled=false）时走安全 Java 回退：先判资格，再按当前业务合并策略结算。
 * 单独一个 context（属性作用于整个 SpringBootTest）。
 */
@SpringBootTest
@ActiveProfiles("h2")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:actlegacy;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "activity.marketing.rule-engine.enabled=false",
        "activity.marketing.seed-catalog-data=false"
})
class ActivityMarketingLegacyTest {

    @Autowired ActivityMarketingService marketing;
    @Autowired ActivityQueryService query;

    private long hAgo() { return System.currentTimeMillis() - 3_600_000L; }
    private long hLater() { return System.currentTimeMillis() + 3_600_000L; }

    /** 关闭规则引擎只切换执行器，不能把资格条件一起关掉。 */
    @Test
    void legacyFallbackRespectsEligibilityAndTakesEligibleMax() {
        // A: 金额 70，带一个正常人满足不了的资格条件（orderAmount >= 100000）
        ConditionNode strict = leaf("orderAmount", "ge", 100000);
        CreateResult a = marketing.create(red("大额红包", new BigDecimal("70"), strict, 8001L));
        // B: 金额 30，无条件
        CreateResult b = marketing.create(red("普通红包", new BigDecimal("30"), null, 8001L));
        online(a); online(b);

        // 订单只有 200：A 必须被资格淘汰；安全回退在剩余候选里取 max=30。
        DiscountView view = query.spuDiscount(new SpuDiscountRequest(
                List.of(8001L), 1L, null, List.of(), new BigDecimal("200"), 1), DecisionMode.HOT_PATH);

        assertTrue(view.hit());
        assertEquals("legacy", view.mode(), "开关关闭应走 legacy");
        assertEquals(0, view.hitAmount().compareTo(new BigDecimal("30")),
                "安全回退必须先淘汰不满足资格的 70 元活动，再取通过候选中的最大值");
    }

    private ConditionNode leaf(String field, String op, Object value) {
        ConditionNode n = new ConditionNode();
        n.setField(field); n.setOp(op); n.setValue(value);
        return n;
    }

    private ActivityCreateRequest red(String name, BigDecimal amount, ConditionNode cond, Long spuId) {
        return new ActivityCreateRequest(
                null, null, name, "legacy", 1, name,
                hAgo(), hLater(), 1, null, 1, 100,
                1, amount, "元", null, "MAX",
                cond, List.of(new ActivityCreateRequest.SpuBinding(1, spuId)), null, null);
    }

    private void online(CreateResult r) {
        marketing.changeStatus(r.activityId(), r.version(), ActivityStatus.ONLINE.code());
    }
}
