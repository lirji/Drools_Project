package com.lrj.drools.activity;

import com.lrj.drools.activity.engine.ActivityDrlBuilder;
import com.lrj.drools.activity.engine.ActivityDrlBuilder.LadderActivityDef;
import com.lrj.drools.activity.engine.ActivityDrlBuilder.LadderTier;
import com.lrj.drools.activity.engine.ActivityRuleRuntimeService;
import com.lrj.drools.activity.engine.GenerationWarmService;
import com.lrj.drools.activity.persistence.ActivityArtifactEntity;
import com.lrj.drools.activity.persistence.ActivityArtifactRepository;
import com.lrj.drools.activity.persistence.ActivityGenerationEntity;
import com.lrj.drools.activity.persistence.ActivityGenerationRepository;
import com.lrj.drools.activity.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1.4 验证门：发布(上线) bump 发布代际 → decision 侧轮询预热命中该 (tenant,bizLine) 的 ACTIVE artifact。
 *
 * <p>调度器由 {@code generation-poll.enabled=false} 关掉（{@link GenerationWarmService} 仍在容器里），
 * 测试手动调 {@code warmDueGenerations()} 并 await 返回的 Future 做确定性断言——不依赖 @Scheduled 定时。
 * 每个用例用<b>唯一租户</b>，故 lastSeen key 与共享 ruleRuntime 缓存断言互不干扰。
 *
 * <p>M2.1 物理拆分后本测试属 decision 模块：代际 bump 的写服务 {@code GenerationService} 在 console 模块（不在 decision classpath），
 * 故此处用 {@link #bumpGen} 经 {@code genRepo} 直接落代际行来驱动 poller（decision 只读代际、不 bump）；
 * bump 服务本身的覆盖见 console 模块 {@code GenerationBumpTest}。
 */
@SpringBootTest
@ActiveProfiles("h2")
@TestPropertySource(properties = {
        // 独立内存库 + create-drop：与既有测试同款隔离，跨运行干净、不撞 artifact 唯一约束（h2 file 会残留）。
        "spring.datasource.url=jdbc:h2:mem:genpolltest;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        // 关掉 @Scheduled 调度，手动调 warmDueGenerations 做确定性断言；GenerationWarmService 仍在容器里。
        "activity.marketing.generation-poll.enabled=false",
        "activity.marketing.seed-catalog-data=false"
})
class GenerationWarmPollerTest {

    @Autowired GenerationWarmService warmService;
    @Autowired ActivityArtifactRepository artifactRepo;
    @Autowired ActivityGenerationRepository genRepo;
    @Autowired ActivityRuleRuntimeService ruleRuntime;

    private final ActivityDrlBuilder drlBuilder = new ActivityDrlBuilder();

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    /** 唯一 activityId → 唯一 DRL 文本 → 唯一缓存 key（不会与既有缓存/其它用例碰撞）。 */
    private String uniqueDrl(String activityId) {
        List<LadderTier> tiers = List.of(
                new LadderTier(new BigDecimal(0), new BigDecimal(100), new BigDecimal(5)),
                new LadderTier(new BigDecimal(100), new BigDecimal(200), new BigDecimal(10)));
        return drlBuilder.buildLadderDrl(List.of(new LadderActivityDef(activityId, tiers, "orderAmount")), false);
    }

    private void saveActiveArtifact(String tenant, String bizLine, String activityId, String drl) {
        TenantContext.runWith(tenant, () -> {
            ActivityArtifactEntity a = new ActivityArtifactEntity();
            a.setActivityId(activityId);
            a.setVersion(1);
            a.setBizLine(bizLine);
            a.setSchemaVersion("v1");
            a.setEligDrl(drl);
            a.setStatus(ActivityArtifactEntity.ACTIVE);
            a.setCreatedStime(Instant.now());
            artifactRepo.save(a);   // @TenantId 在有上下文时自动落 tenant_id
        });
    }

    /** 直接经 genRepo 落/增代际（mirror console 的 GenerationService.bump，非 @TenantId 表，无需上下文）。返回新代际。 */
    private long bumpGen(String tenant, String bizLine) {
        ActivityGenerationEntity g = genRepo.findByTenantIdAndBizLine(tenant, bizLine).orElse(null);
        if (g == null) {
            genRepo.save(new ActivityGenerationEntity(tenant, bizLine, 1L, Instant.now()));
            return 1L;
        }
        g.setGeneration(g.getGeneration() + 1);
        g.setUpdatedStime(Instant.now());
        genRepo.save(g);
        return g.getGeneration();
    }

    @Test
    void publish_bumpsGeneration_thenPollWarmsActiveArtifact() throws Exception {
        String tenant = "genpoll-t1";
        String bizLine = "retail";
        String drl = uniqueDrl("genPollAct1");
        saveActiveArtifact(tenant, bizLine, "genPollAct1", drl);

        // 发布：bump 代际（首次 → generation=1）
        assertEquals(1L, bumpGen(tenant, bizLine), "首次发布代际=1");

        int before = ruleRuntime.cacheSize();
        List<Future<?>> futures = warmService.warmDueGenerations();
        for (Future<?> f : futures) f.get();  // await 预热完成

        assertFalse(futures.isEmpty(), "代际增长应触发至少一个 artifact 预热");
        int after = ruleRuntime.cacheSize();
        assertEquals(before + 1, after, "该唯一 DRL 被预热进缓存（+1）");

        // 决策线程同租户同 DRL → 命中 warm，不新增：证明 poller 确实暖的是这份 DRL
        TenantContext.runWith(tenant, () -> ruleRuntime.compileOrGet(drl));
        assertEquals(after, ruleRuntime.cacheSize(), "命中 poller 预热的 KieBase，不重复编译");
    }

    @Test
    void noNewGeneration_secondPollIsNoop() throws Exception {
        String tenant = "genpoll-t2";
        String bizLine = "retail";
        saveActiveArtifact(tenant, bizLine, "genPollAct2", uniqueDrl("genPollAct2"));
        bumpGen(tenant, bizLine);

        for (Future<?> f : warmService.warmDueGenerations()) f.get();
        int afterFirst = ruleRuntime.cacheSize();

        // 无新发布 → lastSeen 已追平 → 第二轮空扫，不再预热
        List<Future<?>> second = warmService.warmDueGenerations();
        assertTrue(second.isEmpty(), "无代际增长的轮询是空扫");
        assertEquals(afterFirst, ruleRuntime.cacheSize(), "空扫不改变缓存");
    }

    @Test
    void republish_bumpsGenerationAgain_pollWarmsAnew() throws Exception {
        String tenant = "genpoll-t3";
        String bizLine = "retail";
        saveActiveArtifact(tenant, bizLine, "genPollAct3", uniqueDrl("genPollAct3"));

        assertEquals(1L, bumpGen(tenant, bizLine));
        for (Future<?> f : warmService.warmDueGenerations()) f.get();

        // 再次发布 → 代际 +1，行值 1→2
        assertEquals(2L, bumpGen(tenant, bizLine));
        assertEquals(2L, genRepo.findByTenantIdAndBizLine(tenant, bizLine).orElseThrow().getGeneration());

        // 代际 2 > lastSeen 1 → 再次触发预热（single-flight 命中，缓存不必增长，但确有提交）
        List<Future<?>> again = warmService.warmDueGenerations();
        for (Future<?> f : again) f.get();
        assertFalse(again.isEmpty(), "代际再增长应再次触发预热提交");
    }
}
