package com.lrj.drools.activity;

import com.lrj.drools.activity.engine.ActivityDrlBuilder;
import com.lrj.drools.activity.engine.ActivityDrlBuilder.LadderActivityDef;
import com.lrj.drools.activity.engine.ActivityDrlBuilder.LadderTier;
import com.lrj.drools.activity.engine.ActivityRuleRuntimeService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-5 · KieBase 缓存足迹加权（PERF-1「系统性误估」修复）的回归锁。
 *
 * <p>核心断言：权重随**生成规则数**（≈ ladder 总档位）走，<em>不是</em>活动数——这正是评审骂原 {@code maximumSize}
 * 按个数计权会把「1 活动 × 200 档」当 1、比小 KieBase 低估 ~20× 的点。这里锁住修好后的加权函数，防回退。
 * 绝对足迹的实测（堆/Metaspace 数字）在 {@code ActivityKieBaseSizingTest}（gated -Dsizing=true）。
 */
class ActivityCacheWeigherTest {

    private final ActivityDrlBuilder builder = new ActivityDrlBuilder();

    @Test
    void countRules_countsGeneratedRuleBlocks() {
        assertEquals(0, ActivityRuleRuntimeService.countRules(""));
        assertEquals(0, ActivityRuleRuntimeService.countRules("package x;\n// 无规则\n"));
        // 1 活动 × 10 档 → 10 条生成 rule
        String drl10 = ladderDrl("act1", 10);
        assertEquals(10, ActivityRuleRuntimeService.countRules(drl10), "ladder 每档一条 rule");
        // 1 活动 × 200 档 → 200 条
        assertEquals(200, ActivityRuleRuntimeService.countRules(ladderDrl("act1", 200)));
    }

    @Test
    void footprint_scalesWithRules_notActivities() {
        // 命门实证：同为 200 生成规则，无论 1 活动×200 档 还是 20 活动×10 档，足迹权重应几乎一致（差异仅来自 tenant 前缀长度可忽略）。
        int w1x200 = ActivityRuleRuntimeService.footprintKb(ladderDrl("act1", 200));
        int w20x10 = ActivityRuleRuntimeService.footprintKb(ladderMultiDrl(20, 10));
        assertEquals(w1x200, w20x10, "权重随生成规则数走，与活动如何切分无关（评审 PERF-1 铁证）");

        // 反例：若按活动数计权，「1 活动 × 200 档」会被当作与「1 活动 × 1 档」同重 → 低估。这里证加权后前者远重于后者。
        int wBig = ActivityRuleRuntimeService.footprintKb(ladderDrl("act1", 200));   // 200 规则
        int wSmall = ActivityRuleRuntimeService.footprintKb(ladderDrl("act1", 1));   // 1 规则
        assertTrue(wBig > wSmall * 15, "200 档 KieBase 足迹应 >15× 单档（按个数计权会低估约 20×）：big=" + wBig + " small=" + wSmall);
    }

    @Test
    void footprint_isPositiveAndMonotonic() {
        int prev = ActivityRuleRuntimeService.footprintKb(ladderDrl("a", 1));
        for (int tiers : new int[]{5, 10, 50, 100, 200}) {
            int w = ActivityRuleRuntimeService.footprintKb(ladderDrl("a", tiers));
            assertTrue(w > prev, "足迹应随档位单调增：tiers=" + tiers + " w=" + w + " prev=" + prev);
            prev = w;
        }
    }

    // ---- helpers ----
    private String ladderDrl(String actId, int tiers) {
        return builder.buildLadderDrl(List.of(new LadderActivityDef(actId, tiersOf(tiers), "orderAmount")), false);
    }

    private String ladderMultiDrl(int activities, int tiersEach) {
        List<LadderActivityDef> defs = new ArrayList<>();
        for (int a = 0; a < activities; a++) {
            defs.add(new LadderActivityDef("act" + a, tiersOf(tiersEach), "orderAmount"));
        }
        return builder.buildLadderDrl(defs, false);
    }

    private List<LadderTier> tiersOf(int n) {
        List<LadderTier> tiers = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            tiers.add(new LadderTier(new BigDecimal(i * 100), new BigDecimal((i + 1) * 100), new BigDecimal(i + 1)));
        }
        return tiers;
    }
}
