package com.lrj.drools.activity;

import com.lrj.drools.activity.domain.ActivityCandidate;
import com.lrj.drools.activity.domain.ActivityRuleContext;
import com.lrj.drools.activity.domain.ActivityRuleResult;
import com.lrj.drools.activity.domain.GiftResult;
import com.lrj.drools.activity.engine.ActivityDrlBuilder;
import com.lrj.drools.activity.engine.ActivityRuleRuntimeService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * P1-17 · per-artifact fire 上界护栏：fire 超上界即 {@code halt()} 停火，{@code safeRun} 兜底返回 null（决策回退旧逻辑）。
 *
 * <p>直接构造 runtime（无 Spring）对比两档上界跑真 Drools：
 * <ul>
 *   <li>充裕上界（2000+200×规则）→ 引擎正常 fire、返回非 null；</li>
 *   <li>上界=0 → 首次 fire 即触顶 halt → 决策抛异常被 safeRun 兜住 → 返回 null（= 回退信号）。</li>
 * </ul>
 * 证上界确实按 KieBase 派生并生效，而非全局常量。正常端到端引擎路径由 {@code ActivityEligibilityGuardTest} 等覆盖。
 *
 * <p>D1 追认（2026-08-10）后 DRL 侧只剩买赠一个生产场景（evalEligibility/evalLadder/evalDiscount
 * 随灰度开关一并退役），所以护栏用 {@code evalGift} 验——护栏挂在共享的 run() 上，场景无关。
 */
class ActivityFireCeilingTest {

    private ActivityRuleContext ctxWithGiftCandidate() {
        ActivityRuleContext ctx = new ActivityRuleContext();
        ctx.putAttr("orderAmount", new BigDecimal("100"));
        ActivityCandidate c = new ActivityCandidate();
        c.setActivityId("actFire");
        c.setEligible(true);
        c.addGift(new GiftResult()); // gift-collect 规则的触发条件：eligible + gifts 非空
        ctx.addCandidate(c);
        return ctx;
    }

    @Test
    void generousCeiling_engineRunsAndReturns() {
        ActivityRuleRuntimeService svc = new ActivityRuleRuntimeService(
                new ActivityDrlBuilder(), 262144L, 2000, 200);
        ActivityRuleResult result = svc.evalGift(ctxWithGiftCandidate());
        assertNotNull(result, "充裕 fire 上界：引擎正常执行返回非 null");
    }

    @Test
    void zeroCeiling_haltsAndSafeRunReturnsNull() {
        ActivityRuleRuntimeService svc = new ActivityRuleRuntimeService(
                new ActivityDrlBuilder(), 262144L, 0, 0); // maxFires = 0 → 首次 fire 即 halt
        ActivityRuleResult result = svc.evalGift(ctxWithGiftCandidate());
        assertNull(result, "fire 上界=0：halt 触顶 → safeRun 兜底返回 null（决策回退旧逻辑）");
    }
}
