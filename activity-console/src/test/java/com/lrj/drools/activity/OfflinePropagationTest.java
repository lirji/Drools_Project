package com.lrj.drools.activity;

import com.lrj.drools.activity.domain.ActivityCreateRequest;
import com.lrj.drools.activity.domain.ActivityStatus;
import com.lrj.drools.activity.domain.DecisionMode;
import com.lrj.drools.activity.domain.SpuDiscountRequest;
import com.lrj.drools.activity.persistence.ActivityGenerationRepository;
import com.lrj.drools.activity.service.ActivityMarketingService;
import com.lrj.drools.activity.service.ActivityMarketingService.CreateResult;
import com.lrj.drools.activity.service.ActivityQueryService;
import com.lrj.drools.activity.service.ActivityQueryService.DiscountView;
import com.lrj.drools.activity.snapshot.DecisionSnapshotBuilder;
import com.lrj.drools.activity.snapshot.DecisionSnapshotStore;
import com.lrj.drools.activity.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>止损传播</b>：运营点下线之后，这件事必须走得到 decision 侧。
 *
 * <p><b>这个测试类补的是一个结构性盲区。</b>{@code SnapshotParityTest} 的三个用例
 * 全部只做「create → online → publish」，它们再多加几个场景也照不出下线——
 * 因为下线动作压根不在它们的路径上。而 bump 又只在 {@code target == ONLINE} 分支里调，
 * 于是「点了下线、列表变已下线、控制台试算也说不再命中，可线上继续发钱」这条链
 * 在整个测试矩阵里没有任何一处会亮红灯。
 *
 * <p><b>为什么控制台自己看不见这个故障</b>：console 没有快照构建器的调用方，
 * {@code DecisionSnapshotStore} 恒空，它的 legacy 读端点必然走库、必然看到 DB 真相
 * （已下线 = 不命中）。也就是说，运营用来确认止损是否生效的那个工具，
 * 恰好是唯一看不到问题的那条路。所以本测试显式地把快照建出来，
 * 逼 console 走上 decision 才会走的那条路径。
 */
@SpringBootTest
@ActiveProfiles("h2")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:offlineprop;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "activity.marketing.seed-catalog-data=false"
})
@DisplayName("止损传播：下线必须推进发布代际，重建后不再发钱")
class OfflinePropagationTest {

    private static final String TENANT = "__dev__";
    private static final AtomicLong SPU = new AtomicLong(810_000L);

    @Autowired ActivityMarketingService marketing;
    @Autowired ActivityQueryService query;
    @Autowired DecisionSnapshotBuilder builder;
    @Autowired DecisionSnapshotStore store;
    @Autowired ActivityGenerationRepository genRepo;

    @BeforeEach
    void bindTenant() {
        TenantContext.set(TENANT);
    }

    @AfterEach
    void cleanup() {
        store.clear();
        TenantContext.clear();
    }

    @Test
    @DisplayName("下线推进发布代际——这是 decision 侧唯一的『配置变了』信号")
    void offlineBumpsGeneration() {
        String biz = "prop-off";
        long spu = nextSpu();
        CreateResult r = marketing.create(red("待下线", biz, new BigDecimal("30"), spu));

        marketing.changeStatus(r.activityId(), r.version(), ActivityStatus.ONLINE.code());
        long afterOnline = generationOf(biz);
        assertTrue(afterOnline >= 1, "上线应产生代际，实际 " + afterOnline);

        marketing.changeStatus(r.activityId(), r.version(), ActivityStatus.OFFLINE.code());
        long afterOffline = generationOf(biz);

        assertEquals(afterOnline + 1, afterOffline,
                "下线没有推进发布代际 —— decision 侧收不到任何信号，快照会继续按原配置发钱，"
                        + "而控制台（走库）会显示『已停止命中』。止损开关和它的仪表盘一起在骗人。");
    }

    @Test
    @DisplayName("待上线 / 重新上线等任意状态流转都推进代际")
    void anyStatusChangePropagates() {
        String biz = "prop-any";
        long spu = nextSpu();
        CreateResult r = marketing.create(red("状态流转", biz, new BigDecimal("30"), spu));

        marketing.changeStatus(r.activityId(), r.version(), ActivityStatus.ONLINE.code());
        long g1 = generationOf(biz);
        marketing.changeStatus(r.activityId(), r.version(), ActivityStatus.OFFLINE.code());
        long g2 = generationOf(biz);
        marketing.changeStatus(r.activityId(), r.version(), ActivityStatus.NORMAL.code());
        long g3 = generationOf(biz);
        marketing.changeStatus(r.activityId(), r.version(), ActivityStatus.ONLINE.code());
        long g4 = generationOf(biz);

        assertEquals(g1 + 1, g2, "上线→下线应 +1");
        assertEquals(g2 + 1, g3, "下线→待上线应 +1（配置面已变，decision 必须重算）");
        assertEquals(g3 + 1, g4, "待上线→重新上线应 +1");
    }

    @Test
    @DisplayName("没有 bizLine 的活动仍能下线（代际跳过，但状态必须落库）")
    void offlineWorksWithoutBizLine() {
        long spu = nextSpu();
        // bizLine 可空（activity_manage.biz_line 无 NOT NULL），而 activity_generation.biz_line 是 NOT NULL。
        // 「任何状态变化都 bump」如果不加守卫，这里会撞非空约束 → 整事务回滚 → **下线直接失败**，
        // 比「下线传播不出去」更严重：运营连停都停不掉。
        CreateResult r = marketing.create(red("无业务线", null, new BigDecimal("30"), spu));
        marketing.changeStatus(r.activityId(), r.version(), ActivityStatus.ONLINE.code());

        marketing.changeStatus(r.activityId(), r.version(), ActivityStatus.OFFLINE.code());

        assertEquals(ActivityStatus.OFFLINE.code(),
                marketing.getDetail(r.activityId()).manage().getActivityStatus(),
                "没有 bizLine 的活动必须仍然下得掉——状态变更不能被代际 bump 的失败连累");
    }

    @Test
    @DisplayName("端到端：下线 + 按新代际重建快照 → 决策不再命中")
    void offlineStopsPayingAfterRebuild() {
        String biz = "prop-e2e";
        long spu = nextSpu();
        CreateResult r = marketing.create(red("秒杀清仓", biz, new BigDecimal("50"), spu));
        marketing.changeStatus(r.activityId(), r.version(), ActivityStatus.ONLINE.code());

        // decision 侧轮询见代际增长 → 建快照切指针
        store.publish(builder.build(TENANT, biz, generationOf(biz)));
        DiscountView online = query.spuDiscount(req(spu), DecisionMode.HOT_PATH);
        assertTrue(online.hit(), "上线后应命中");
        assertEquals(0, online.hitAmount().compareTo(new BigDecimal("50")));

        // 运营点下线
        marketing.changeStatus(r.activityId(), r.version(), ActivityStatus.OFFLINE.code());

        // 这一步就是本次修复的价值所在：下线 bump 了代际 → decision 轮询会重建快照。
        // 重建用的代际号取自库里那一行，与生产轮询取的是同一个值。
        store.publish(builder.build(TENANT, biz, generationOf(biz)));

        DiscountView offline = query.spuDiscount(req(spu), DecisionMode.HOT_PATH);
        assertFalse(offline.hit(),
                "下线并重建快照后仍在命中 —— 已下线的活动还在发钱");
        assertEquals(0, BigDecimal.ZERO.compareTo(offline.hitAmount()), "下线后金额必须为 0");
    }

    @Test
    @DisplayName("反证：快照不重建就还在发钱——这正是信号必须发出去的原因")
    void staleSnapshotKeepsPaying() {
        String biz = "prop-stale";
        long spu = nextSpu();
        CreateResult r = marketing.create(red("陈旧验证", biz, new BigDecimal("70"), spu));
        marketing.changeStatus(r.activityId(), r.version(), ActivityStatus.ONLINE.code());
        store.publish(builder.build(TENANT, biz, generationOf(biz)));

        marketing.changeStatus(r.activityId(), r.version(), ActivityStatus.OFFLINE.code());

        // 故意不重建快照，模拟「信号漏发 / 轮询卡住」
        DiscountView stale = query.spuDiscount(req(spu), DecisionMode.HOT_PATH);
        assertTrue(stale.hit(),
                "本用例记录的是快照的固有性质：它是**正向物化**的（构建期按 ONLINE 过滤），"
                        + "『后来下线了』在快照的数据结构里无法表达，materialize 只重判类型与时间窗。"
                        + "所以下线能不能止住，完全取决于代际信号有没有发出去 + 兜底重建有没有兜住。"
                        + "如果这条断言变红，说明快照已改成能自行识别下线——那是好事，请改掉本用例而不是绕过它。");

        // 兜底重建（GenerationWarmService 的陈旧扫描做的事）——不占回滚槽位
        store.refresh(builder.build(TENANT, biz, generationOf(biz)));
        assertFalse(query.spuDiscount(req(spu), DecisionMode.HOT_PATH).hit(), "兜底重建后必须停止命中");
    }

    @Test
    @DisplayName("兜底重建不得占用回滚槽位")
    void refreshDoesNotConsumeRollbackSlot() {
        String biz = "prop-slot";
        long spu = nextSpu();
        CreateResult v1 = marketing.create(red("第一代", biz, new BigDecimal("20"), spu));
        marketing.changeStatus(v1.activityId(), v1.version(), ActivityStatus.ONLINE.code());
        store.publish(builder.build(TENANT, biz, 1L));

        CreateResult v2 = marketing.updateByVersion(edit(v1.activityId(), "第二代", biz, new BigDecimal("60"), spu));
        marketing.changeStatus(v2.activityId(), v2.version(), ActivityStatus.ONLINE.code());
        store.publish(builder.build(TENANT, biz, 2L));
        assertEquals(0, query.spuDiscount(req(spu), DecisionMode.HOT_PATH).hitAmount().compareTo(new BigDecimal("60")));

        // 兜底重建若走 publish，就会把 previous 槽位挤成「同一代的旧副本」，
        // 回滚将退到几十秒前的自己而不是上一个发布代际 —— 等于回滚失效。
        store.refresh(builder.build(TENANT, biz, 2L));

        assertTrue(store.rollback(TENANT, biz), "应仍能回滚");
        assertEquals(0, query.spuDiscount(req(spu), DecisionMode.HOT_PATH).hitAmount().compareTo(new BigDecimal("20")),
                "回滚必须退到上一个**发布代际**（20），而不是兜底重建前的同代副本（60）");
    }

    // ---- helpers ----

    private static long nextSpu() { return SPU.incrementAndGet(); }

    private long generationOf(String bizLine) {
        return genRepo.findByTenantIdAndBizLine(TENANT, bizLine)
                .map(g -> g.getGeneration())
                .orElse(0L);
    }

    private static SpuDiscountRequest req(long spu) {
        return new SpuDiscountRequest(List.of(spu), 1001L, "110000", List.of("vip"),
                new BigDecimal("500"), 1, null);
    }

    private ActivityCreateRequest red(String name, String bizLine, BigDecimal amount, long spu) {
        long now = System.currentTimeMillis();
        return new ActivityCreateRequest(
                null, null, name, bizLine, 1, name,
                now - 3_600_000L, now + 3_600_000L, 1, null, 1, 100,
                1, amount, "元", null, "MAX",
                null, List.of(new ActivityCreateRequest.SpuBinding(1, spu)), null, null);
    }

    private ActivityCreateRequest edit(String activityId, String name, String bizLine, BigDecimal amount, long spu) {
        long now = System.currentTimeMillis();
        return new ActivityCreateRequest(
                null, activityId, name, bizLine, 1, name,
                now - 3_600_000L, now + 3_600_000L, 1, null, 1, 100,
                1, amount, "元", null, "MAX",
                null, List.of(new ActivityCreateRequest.SpuBinding(1, spu)), null, null);
    }
}
