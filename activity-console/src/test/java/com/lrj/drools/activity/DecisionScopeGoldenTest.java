package com.lrj.drools.activity;

import com.lrj.drools.activity.domain.ActivityCreateRequest;
import com.lrj.drools.activity.domain.ActivityStatus;
import com.lrj.drools.activity.domain.DecisionMode;
import com.lrj.drools.activity.domain.SpuDiscountRequest;
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
 * <b>权益作用域的端到端验收</b>：写平面创建 → 取数 → 资格 → 算额 → 合并，全链路走一遍。
 *
 * <p>{@code BenefitScopeTest} 用手工候选证明了求值层的语义；本类证明**装配层真的把作用域填上了**。
 * 这两件事必须分开验：求值层写对了但取数层没填，表现是线上一切照旧（作用域恒为 null → 按整单算），
 * 而所有纯函数测试都是绿的。
 *
 * <p>同时对拍走库与走快照两条路——作用域是新增的、会影响金额的候选字段，
 * 只填一边的表现是「同一张券在两条路上发不同的钱」。
 */
@SpringBootTest
@ActiveProfiles("h2")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:scopegold;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "activity.marketing.seed-catalog-data=false"
})
@DisplayName("作用域端到端：商品级活动只对自己的商品发钱")
class DecisionScopeGoldenTest {

    private static final String TENANT = "__dev__";
    private static final String BIZ = "scope-gold";
    private static final AtomicLong SPU = new AtomicLong(660_000L);

    @Autowired ActivityMarketingService marketing;
    @Autowired ActivityQueryService query;
    @Autowired DecisionSnapshotBuilder builder;
    @Autowired DecisionSnapshotStore store;

    @BeforeEach
    void bindTenant() { TenantContext.set(TENANT); }

    @AfterEach
    void cleanup() { store.clear(); TenantContext.clear(); }

    @Test
    @DisplayName("指定商品 8 折：混合购物车里只打自己那件，两条路径一致")
    void itemLevelRatioOnlyDiscountsItsOwnItem() {
        long spuA = nextSpu();   // 贵重商品 1000 元，不参与活动
        long spuB = nextSpu();   // 活动商品 10 元 × 2

        CreateResult r = marketing.create(ratio("指定商品8折", new BigDecimal("8"), spuB));
        marketing.changeStatus(r.activityId(), r.version(), ActivityStatus.ONLINE.code());

        SpuDiscountRequest req = new SpuDiscountRequest(
                List.of(spuA, spuB), 1001L, "110000", List.of("vip"),
                new BigDecimal("1020"), 3, null,
                List.of(new SpuDiscountRequest.OrderLine(spuA, new BigDecimal("1000"), 1),
                        new SpuDiscountRequest.OrderLine(spuB, new BigDecimal("10"), 2)));

        // ---- 走库 ----
        store.clear();
        DiscountView viaDb = query.spuDiscount(req, DecisionMode.HOT_PATH);
        assertTrue(viaDb.hit(), "活动应命中");
        assertEquals(0, viaDb.hitAmount().compareTo(new BigDecimal("4.00")),
                "只绑 spuB 的 8 折券应按 B 的小计(20) 算出 4.00；"
                        + "若这里是 204.00，说明作用域没填、又在按整单 1020 算钱。实际 " + viaDb.hitAmount());

        // ---- 走快照 ----
        store.publish(builder.build(TENANT, BIZ, 1L));
        DiscountView viaSnapshot = query.spuDiscount(req, DecisionMode.HOT_PATH);
        assertEquals(0, viaDb.hitAmount().compareTo(viaSnapshot.hitAmount()),
                "两条路径必须发同样的钱：库=" + viaDb.hitAmount() + " 快照=" + viaSnapshot.hitAmount());
        assertEquals(viaDb.hitActivityId(), viaSnapshot.hitActivityId());
    }

    @Test
    @DisplayName("混合购物车但不传订单行 → 不发，而不是按整单多发")
    void mixedCartWithoutLinesDoesNotHitItemLevelActivity() {
        long spuA = nextSpu();
        long spuB = nextSpu();

        CreateResult r = marketing.create(ratio("指定商品8折-无行", new BigDecimal("8"), spuB));
        marketing.changeStatus(r.activityId(), r.version(), ActivityStatus.ONLINE.code());

        SpuDiscountRequest req = new SpuDiscountRequest(
                List.of(spuA, spuB), 1001L, "110000", List.of("vip"),
                new BigDecimal("1020"), 3, null, null);

        DiscountView v = query.spuDiscount(req, DecisionMode.EXPLAIN);

        assertFalse(v.hit(), "算不出作用域基数时宁可不发，不可按整单多发（会减 204）");
        assertTrue(v.traces().stream().anyMatch(t -> t.contains("作用域基数不可知"))
                        || v.items().stream().anyMatch(i -> i.rejectReason() != null
                            && i.rejectReason().contains("作用域基数不可知")),
                "必须能看到不适用的原因，否则运营只会看到「配了但不生效」。traces=" + v.traces());
    }

    @Test
    @DisplayName("一口价：绑定商品之外的东西不跟着白送")
    void fixedPriceOnlyAppliesToItsOwnItem() {
        long spuA = nextSpu();
        long spuB = nextSpu();

        CreateResult r = marketing.create(flash("9.9秒杀", new BigDecimal("9.9"), spuB));
        marketing.changeStatus(r.activityId(), r.version(), ActivityStatus.ONLINE.code());

        SpuDiscountRequest req = new SpuDiscountRequest(
                List.of(spuA, spuB), 1001L, "110000", List.of("vip"),
                new BigDecimal("5099.9"), 2, null,
                List.of(new SpuDiscountRequest.OrderLine(spuA, new BigDecimal("5000"), 1),
                        new SpuDiscountRequest.OrderLine(spuB, new BigDecimal("99.9"), 1)));

        DiscountView v = query.spuDiscount(req, DecisionMode.HOT_PATH);

        assertEquals(0, v.hitAmount().compareTo(new BigDecimal("90.00")),
                "B 小计 99.9 − 一口价 9.9 = 90.00。若是 5090.00，就是「整车按 9.9 成交」那个缺陷。实际 "
                        + v.hitAmount());
    }

    @Test
    @DisplayName("单商品查询不受影响：作用域等于整单时仍按订单金额算")
    void singleSpuRequestIsUnchanged() {
        long spu = nextSpu();
        CreateResult r = marketing.create(ratio("全场8折", new BigDecimal("8"), spu));
        marketing.changeStatus(r.activityId(), r.version(), ActivityStatus.ONLINE.code());

        DiscountView v = query.spuDiscount(new SpuDiscountRequest(
                List.of(spu), 1001L, "110000", List.of("vip"), new BigDecimal("100"), 1, null, null), DecisionMode.HOT_PATH);

        assertEquals(0, v.hitAmount().compareTo(new BigDecimal("20.00")),
                "这是今天绝大多数流量的形状（单 SPU、不传订单行）。它一旦被 fail-closed，"
                        + "线上所有折扣券/秒杀券会当场失效——修多发不能换来全线不发");
    }

    @Test
    @DisplayName("编辑撤掉某个 SPU 后，走库路径也不再对它发钱（作用域按版本配对）")
    void scopeFollowsCurrentOnlineVersion() {
        long spuKeep = nextSpu();
        long spuDrop = nextSpu();

        // v1 绑两个 SPU
        CreateResult v1 = marketing.create(ratioMulti("范围收窄", new BigDecimal("8"), spuKeep, spuDrop));
        marketing.changeStatus(v1.activityId(), v1.version(), ActivityStatus.ONLINE.code());

        // v2 只保留一个
        CreateResult v2 = marketing.updateByVersion(
                edit(v1.activityId(), "范围收窄v2", new BigDecimal("8"), spuKeep));
        marketing.changeStatus(v2.activityId(), v2.version(), ActivityStatus.ONLINE.code());

        store.clear();   // 走库
        SpuDiscountRequest req = new SpuDiscountRequest(
                List.of(spuKeep, spuDrop), 1001L, "110000", List.of("vip"),
                new BigDecimal("300"), 2, null,
                List.of(new SpuDiscountRequest.OrderLine(spuKeep, new BigDecimal("100"), 1),
                        new SpuDiscountRequest.OrderLine(spuDrop, new BigDecimal("200"), 1)));

        DiscountView v = query.spuDiscount(req, DecisionMode.HOT_PATH);

        assertEquals(0, v.hitAmount().compareTo(new BigDecimal("20.00")),
                "v2 已撤掉 spuDrop，作用域只剩 spuKeep(100) → 8 折减 20.00。"
                        + "若是 60.00，说明作用域用了旧版本的绑定行（绑定查询不带 version，"
                        + "旧版本的绑定行永远不会被软删）。实际 " + v.hitAmount());
    }

    // ---- helpers ----

    private static long nextSpu() { return SPU.incrementAndGet(); }

    private ActivityCreateRequest ratio(String name, BigDecimal zhe, long... spus) {
        return build(name, zhe, "折", spus);
    }

    private ActivityCreateRequest ratioMulti(String name, BigDecimal zhe, long... spus) {
        return build(name, zhe, "折", spus);
    }

    private ActivityCreateRequest flash(String name, BigDecimal price, long... spus) {
        return build(name, price, "价", spus);
    }

    private ActivityCreateRequest build(String name, BigDecimal amount, String unit, long... spus) {
        long now = System.currentTimeMillis();
        List<ActivityCreateRequest.SpuBinding> bindings = new java.util.ArrayList<>();
        for (long s : spus) bindings.add(new ActivityCreateRequest.SpuBinding(1, s));
        return new ActivityCreateRequest(
                null, null, name, BIZ, 1, name,
                now - 3_600_000L, now + 3_600_000L, 1, null, 1, 100,
                1, amount, unit, null, "MAX",
                null, bindings, null, null,
                "折".equals(unit) ? new BigDecimal("99999") : null);
    }

    private ActivityCreateRequest edit(String activityId, String name, BigDecimal zhe, long... spus) {
        long now = System.currentTimeMillis();
        List<ActivityCreateRequest.SpuBinding> bindings = new java.util.ArrayList<>();
        for (long s : spus) bindings.add(new ActivityCreateRequest.SpuBinding(1, s));
        return new ActivityCreateRequest(
                null, activityId, name, BIZ, 1, name,
                now - 3_600_000L, now + 3_600_000L, 1, null, 1, 100,
                1, zhe, "折", null, "MAX",
                null, bindings, null, null, new BigDecimal("99999"));
    }
}
