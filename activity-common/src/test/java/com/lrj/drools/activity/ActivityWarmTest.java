package com.lrj.drools.activity;

import com.lrj.drools.activity.engine.ActivityDrlBuilder;
import com.lrj.drools.activity.engine.ActivityDrlBuilder.LadderActivityDef;
import com.lrj.drools.activity.engine.ActivityDrlBuilder.LadderTier;
import com.lrj.drools.activity.engine.ActivityRuleRuntimeService;
import com.lrj.drools.activity.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * P0-5 发布预热：{@code warmAsync} 在独立编译池把 DRL 异步编译进缓存（冷编译不落决策热路径），
 * 且缓存 key 含租户（不同租户各占一份）。
 */
class ActivityWarmTest {

    private final ActivityDrlBuilder builder = new ActivityDrlBuilder();
    private ActivityRuleRuntimeService svc;

    @AfterEach
    void clean() {
        TenantContext.clear();
        if (svc != null) svc.shutdown();
    }

    private ActivityRuleRuntimeService newSvc() {
        return new ActivityRuleRuntimeService(builder, 262144L, 2000, 200);
    }

    private String ladderDrl(String actId) {
        List<LadderTier> t = List.of(
                new LadderTier(new BigDecimal(0), new BigDecimal(100), new BigDecimal(5)),
                new LadderTier(new BigDecimal(100), new BigDecimal(200), new BigDecimal(10)));
        return builder.buildLadderDrl(List.of(new LadderActivityDef(actId, t, "orderAmount")), false);
    }

    @Test
    void warmAsync_compilesIntoCache_offThread() throws Exception {
        svc = newSvc();
        String drl = ladderDrl("warmAct");
        assertEquals(0, svc.cacheSize(), "起始空缓存");

        svc.warmAsync("acme", drl).get(); // await 预热完成
        assertEquals(1, svc.cacheSize(), "预热后 KieBase 已入缓存");

        // 决策线程同租户同 DRL → single-flight 命中，不新增
        TenantContext.callWith("acme", () -> svc.compileOrGet(drl));
        assertEquals(1, svc.cacheSize(), "同租户同 DRL 命中 warm，不重复编译");
    }

    @Test
    void warmAsync_keyedByTenant() throws Exception {
        svc = newSvc();
        String drl = ladderDrl("warmAct2");
        svc.warmAsync("acme", drl).get();
        svc.warmAsync("beta", drl).get();
        assertEquals(2, svc.cacheSize(), "缓存 key 含租户：同 DRL 不同租户各占一份");
    }
}
