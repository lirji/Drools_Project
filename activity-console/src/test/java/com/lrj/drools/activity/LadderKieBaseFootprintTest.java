package com.lrj.drools.activity;

import com.lrj.drools.activity.domain.ActivityCreateRequest;
import com.lrj.drools.activity.domain.ActivityStatus;
import com.lrj.drools.activity.domain.DecisionMode;
import com.lrj.drools.activity.domain.SpuDiscountRequest;
import com.lrj.drools.activity.engine.ActivityRuleRuntimeService;
import com.lrj.drools.activity.service.ActivityMarketingService;
import com.lrj.drools.activity.service.ActivityMarketingService.CreateResult;
import com.lrj.drools.activity.service.ActivityQueryService;
import org.junit.jupiter.api.DisplayName;
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
 * P1-2 的附带收益：<b>KieBase 体积不再随运营配置的档位数增长</b>。
 *
 * <p>改造前每个档位生成一条 DRL 规则——{@code ActivityKieBaseSizingTest} 实测的足迹是
 * {@code 260KB + 37KB × 规则数}，200 档就是约 7.6MB 的 KieBase，而且这个 KieBase 的缓存键
 * 是「租户 + DRL 全文」、DRL 又按<b>本次候选集</b>拼装，于是档位越多、候选组合越多，
 * 缓存键膨胀得越快（评估报告 D2 的组合爆炸）。
 *
 * <p>阶梯落档移出规则引擎后，阶梯 DRL 与折扣 DRL <b>根本不再生成</b>，
 * 缓存里只剩资格判定那一类。本测试用 120 个档位跑一遍决策，断言：
 * <ol>
 *   <li>金额仍然正确（落档逻辑没坏）</li>
 *   <li>KieBase 缓存条目数保持在个位数——若阶梯还在走规则，这里会因为每次候选组合生成新 DRL 而增长</li>
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("h2")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:actfootprint;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "activity.marketing.seed-catalog-data=false"
})
@DisplayName("阶梯移出规则引擎：KieBase 不再随档位数膨胀")
class LadderKieBaseFootprintTest {

    private static final int TIERS = 120;

    @Autowired ActivityMarketingService marketing;
    @Autowired ActivityQueryService query;
    @Autowired ActivityRuleRuntimeService runtime;

    @Test
    void manyTiersDoNotInflateKieBaseCache() {
        long spu = 990_001L;
        CreateResult r = marketing.create(ladderActivity("超多档位", spu, TIERS));
        marketing.changeStatus(r.activityId(), r.version(), ActivityStatus.ONLINE.code());

        int before = runtime.cacheSize();

        // 跨多个档位各打一次决策：若阶梯还在走 DRL，每次都会因候选集拼装出新 DRL
        for (int i = 0; i < TIERS; i += 10) {
            BigDecimal orderAmount = new BigDecimal(i * 100 + 50);
            var v = query.spuDiscount(new SpuDiscountRequest(
                    List.of(spu), 1001L, "110000", List.of("vip"), orderAmount, 1), DecisionMode.HOT_PATH);
            assertTrue(v.hit(), "订单 " + orderAmount + " 应落在第 " + i + " 档");
            assertEquals(0, v.hitAmount().compareTo(new BigDecimal(i + 1)),
                    "第 " + i + " 档的奖励应为 " + (i + 1) + "，实得 " + v.hitAmount());
        }

        int added = runtime.cacheSize() - before;
        // P1-3 之后资格判定也不再生成 DRL —— 红包决策链路上已经没有任何一步会编译 KieBase，
        // 「按候选集拼 DRL」这个缓存键爆炸的根源（评估报告 D2）在此场景下彻底消失。
        assertEquals(0, added,
                "红包决策链路不应再生成任何 KieBase，却新增了 " + added + " 项");
        assertTrue(added <= 2,
                "阶梯与折扣不应再生成 KieBase，缓存却新增了 " + added + " 项——"
                        + "说明标量计算又回到规则引擎里了（" + TIERS + " 档会生成 " + TIERS + " 条规则）");
    }

    /** 生成 n 个连续档位：[i*100, (i+1)*100) → 奖励 i+1。 */
    private ActivityCreateRequest ladderActivity(String name, long spu, int n) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"min\":").append(i * 100)
              .append(",\"max\":").append((i + 1) * 100)
              .append(",\"reward\":").append(i + 1).append('}');
        }
        sb.append(']');

        long now = System.currentTimeMillis();
        return new ActivityCreateRequest(
                null, null, name, "footprint", 1, name,
                now - 3_600_000L, now + 3_600_000L, 1, null, 1, 100,
                1, null, "元", sb.toString(), "MAX",
                null, List.of(new ActivityCreateRequest.SpuBinding(1, spu)), null, null);
    }
}
