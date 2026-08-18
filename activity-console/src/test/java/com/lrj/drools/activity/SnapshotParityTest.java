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
import com.lrj.drools.activity.snapshot.DecisionSnapshot;
import com.lrj.drools.activity.snapshot.DecisionSnapshotBuilder;
import com.lrj.drools.activity.snapshot.DecisionSnapshotStore;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>代际快照对拍（P1-1 的准入门禁）</b>——快照路径与走库路径必须给出**逐字段相同**的决策结果。
 *
 * <p><b>为什么必须对拍而不是只测快照</b>：快照把「取哪些活动、取哪个版本、时间窗怎么判、
 * 绑定怎么倒排」整套语义复制了一遍。复制就会漂——漏一个 {@code effective=1} 过滤、
 * 或者版本挑错，结果就是**发出去的钱不一样**，而两条路各自单独测都是绿的。
 * 所以判据不是「快照能跑」，而是「快照与走库<b>逐字段一致</b>」。
 *
 * <p>覆盖场景刻意与金标集同源：阶梯落档、MAX/PRIORITY 合并、资格淘汰、storeId 条件、
 * 未上线/已下线的排除。每个场景都跑两遍（清空 store → 走库；发布快照 → 走快照），比对
 * {@code hit / hitActivityId / hitAmount / strategy}。
 */
@SpringBootTest
@ActiveProfiles("h2")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:actparity;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "activity.marketing.seed-catalog-data=false"
})
@DisplayName("代际快照：与走库路径逐字段对拍")
class SnapshotParityTest {

    private static final String TIERS =
            "[{\"min\":0,\"max\":100,\"reward\":5},{\"min\":100,\"max\":200,\"reward\":12},{\"min\":200,\"max\":null,\"reward\":25}]";
    private static final AtomicLong SPU = new AtomicLong(700_000L);

    @Autowired ActivityMarketingService marketing;
    @Autowired ActivityQueryService query;
    @Autowired DecisionSnapshotBuilder builder;
    @Autowired DecisionSnapshotStore store;
    @Autowired EntityManagerFactory emf;

    /**
     * 显式置租户上下文。生产里这是 {@code TenantContextFilter} 干的活，
     * 而 {@code @SpringBootTest} 直调 service 不经过 filter——不置的话 {@code TenantContext.get()}
     * 返回 null，{@code store.forTenant(null)} 恒空，两条路就都退化成走库，
     * **对拍会变成"自己跟自己比"的假绿**。
     */
    @BeforeEach
    void bindTenant() {
        TenantContext.set(TENANT);
    }

    @AfterEach
    void clearSnapshots() {
        store.clear();   // 用例之间不得互相看到对方发布的快照
        TenantContext.clear();
    }

    @Test
    @DisplayName("阶梯 / 合并 / 资格 / storeId 四类场景，两条路结果必须一致")
    void snapshotMatchesDbAcrossScenarios() {
        List<Scenario> scenarios = new ArrayList<>();

        // ① 阶梯落档（含两个边界值）
        long ladder = nextSpu();
        online(red("阶梯", "par-ladder", null, TIERS, null, 1, ladder, "MAX"));
        scenarios.add(new Scenario("阶梯·首档", req(ladder, "50", null)));
        scenarios.add(new Scenario("阶梯·恰等第二档下界", req(ladder, "100", null)));
        scenarios.add(new Scenario("阶梯·末档", req(ladder, "500", null)));

        // ② MAX 三候选
        long max = nextSpu();
        online(red("a", "par-max", new BigDecimal("30"), null, null, 1, max, "MAX"));
        online(red("b", "par-max", new BigDecimal("80"), null, null, 1, max, "MAX"));
        online(red("c", "par-max", new BigDecimal("55"), null, null, 1, max, "MAX"));
        scenarios.add(new Scenario("MAX·三候选", req(max, "500", null)));

        // ③ PRIORITY（优先级小者胜，与金额无关）
        long prio = nextSpu();
        online(red("低优先高金额", "par-prio", new BigDecimal("90"), null, null, 5, prio, "PRIORITY"));
        online(red("高优先低金额", "par-prio", new BigDecimal("10"), null, null, 1, prio, "PRIORITY"));
        scenarios.add(new Scenario("PRIORITY", req(prio, "500", null)));

        // ④ 资格条件（满足 / 不满足）
        long elig = nextSpu();
        online(red("满100可用", "par-elig", new BigDecimal("20"), null,
                leaf("orderAmount", "ge", 100), 1, elig, "MAX"));
        scenarios.add(new Scenario("资格·满足", req(elig, "150", null)));
        scenarios.add(new Scenario("资格·不满足", req(elig, "99", null)));

        // ⑤ storeId 条件（带 / 不带）
        long store1 = nextSpu();
        online(red("门店专享", "par-store", new BigDecimal("18"), null,
                leaf("storeId", "eq", 1), 1, store1, "MAX"));
        scenarios.add(new Scenario("storeId·命中", req(store1, "500", 1)));
        scenarios.add(new Scenario("storeId·缺失 fail-closed", req(store1, "500", null)));

        // ⑥ 未上线 / 已下线不得进入任何一条路
        long draft = nextSpu();
        marketing.create(red("草稿", "par-life", new BigDecimal("50"), null, null, 1, draft, "MAX"));
        scenarios.add(new Scenario("草稿不参与", req(draft, "500", null)));

        // ⑦ **权益作用域是真子集**——两条路径各自独立地从绑定信息推导作用域，
        // 只填一边的表现是「同一张券在走库与走快照上发不同的钱」，而两条路单独测都是绿的。
        // 这是本次新增的分歧面，对拍必须覆盖它。
        long scopedIn = nextSpu();     // 活动绑这个
        long scopedOut = nextSpu();    // 车里还有这个，但活动不绑
        online(ratio("作用域8折", "par-scope", new BigDecimal("8"), scopedIn));
        scenarios.add(new Scenario("作用域·真子集（带订单行）",
                reqWithLines(List.of(scopedOut, scopedIn), "1020",
                        line(scopedOut, "1000", 1), line(scopedIn, "10", 2))));
        scenarios.add(new Scenario("作用域·真子集但无订单行 → 两条路都不适用",
                reqWithLines(List.of(scopedOut, scopedIn), "1020")));

        // ---- 第一遍：走库（store 为空）----
        store.clear();
        List<DiscountView> viaDb = new ArrayList<>();
        for (Scenario sc : scenarios) viaDb.add(query.spuDiscount(sc.request(), DecisionMode.HOT_PATH));

        // ---- 发布快照 ----
        for (String biz : List.of("par-ladder", "par-max", "par-prio", "par-elig", "par-store", "par-life",
                "par-scope")) {
            store.publish(builder.build(tenant(), biz, 1L));
        }

        // ---- 第二遍：走快照 ----
        // 防假绿：若快照没被真正采用，这一遍会静默退化成走库，对拍就变成"自己跟自己比"。
        // 用查询计数当证据——快照命中必然是 0 条语句。
        Statistics st = emf.unwrap(SessionFactory.class).getStatistics();
        st.clear();
        List<DiscountView> viaSnapshot = new ArrayList<>();
        for (Scenario sc : scenarios) viaSnapshot.add(query.spuDiscount(sc.request(), DecisionMode.HOT_PATH));
        assertEquals(0, st.getPrepareStatementCount(),
                "第二遍本应全部走快照，却发出了 " + st.getPrepareStatementCount()
                        + " 条 SQL —— 对拍已退化为『库 vs 库』，结果不可信");

        // ---- 逐字段比对 ----
        for (int i = 0; i < scenarios.size(); i++) {
            String name = scenarios.get(i).name();
            DiscountView db = viaDb.get(i);
            DiscountView sn = viaSnapshot.get(i);
            assertEquals(db.hit(), sn.hit(), name + "：hit 不一致（库=" + db.hit() + " 快照=" + sn.hit() + "）");
            assertEquals(db.hitActivityId(), sn.hitActivityId(), name + "：命中活动不一致");
            assertEquals(0, db.hitAmount().compareTo(sn.hitAmount()),
                    name + "：**金额不一致**，库=" + db.hitAmount() + " 快照=" + sn.hitAmount());
            assertEquals(db.strategy(), sn.strategy(), name + "：合并策略不一致");
        }

        // ---- provenance 是**唯一一个两条路必须不同**的字段 ----
        // 它存在的意义就是把「这次是照着谁算的」说出来，所以绝不能并进上面那轮逐字段 sweep。
        // 反过来它也是上面那个「0 条 SQL」断言的第二道保险：真退化成「库 vs 库」时这里也会红。
        for (int i = 0; i < scenarios.size(); i++) {
            String name = scenarios.get(i).name();
            assertEquals("db", viaDb.get(i).provenance().source(), name + "：第一遍应自证走库");
            assertEquals("snapshot", viaSnapshot.get(i).provenance().source(), name + "：第二遍应自证走快照");
            assertEquals(1L, viaSnapshot.get(i).provenance().generation(), name + "：快照代际应为发布时那一代");
        }
    }

    /**
     * <b>编辑收窄圈选范围后，被撤掉的 SPU 不得再发钱——两条路都不得。</b>
     *
     * <p>这是 P1-9 剩下的那一半。绑定查询<b>不带 version</b>，且旧版本的绑定行不会被软删，
     * 所以「v1 绑 A/B → 编辑成 v2 只绑 A」之后单查 B 时，走库路径仍然把这个活动<b>当成候选</b>，
     * 只是它的作用域是空集。而 {@code BenefitEvaluator} 的 <b>AMOUNT（直减/满减）形态压根不调 baseAmount</b>，
     * 直接把 {@code redPackageAmount} 发出去——于是走库照发、走快照根本不是候选。
     *
     * <p><b>为什么现有用例照不出</b>：场景 ⑦ 用的是「折」（走 baseAmount，空作用域会被算成 0/不适用），
     * 且请求里仍留着被保留的 SPU。两个条件各自都足以绕开这条。这里刻意用最常见的 AMOUNT 形态，
     * 且请求里<b>只有</b>被撤掉的那个 SPU。
     */
    @Test
    @DisplayName("编辑收窄绑定：被撤掉的 SPU 在走库与走快照两条路上都不得再命中（AMOUNT 形态）")
    void narrowedBindingStopsPayingOnBothPaths() {
        long keep = nextSpu();
        long dropped = nextSpu();

        CreateResult v1 = marketing.create(
                redBound("收窄前·绑两个", "par-narrow", new BigDecimal("50"), List.of(keep, dropped)));
        online(v1);
        CreateResult v2 = marketing.updateByVersion(
                editBound(v1.activityId(), "收窄后·只绑一个", "par-narrow", new BigDecimal("50"), List.of(keep)));
        online(v2);

        // ---- 走库 ----
        store.clear();
        DiscountView viaDb = query.spuDiscount(req(dropped, "500", null), DecisionMode.HOT_PATH);

        // ---- 走快照 ----
        store.publish(builder.build(tenant(), "par-narrow", 1L));
        DiscountView viaSnapshot = query.spuDiscount(req(dropped, "500", null), DecisionMode.HOT_PATH);

        assertFalse(viaSnapshot.hit(),
                "快照路径不该命中：v2 已经不绑这个 SPU（这一侧本来就是对的，红了说明快照侧也退化了）");
        assertFalse(viaDb.hit(),
                "走库路径仍在给『当前线上版本已经不绑的 SPU』发钱，金额 " + viaDb.hitAmount()
                        + "——绑定查询不带 version，而 AMOUNT 形态不看作用域");
        assertEquals(viaDb.hit(), viaSnapshot.hit(), "两条路对同一次请求给出了相反的结论");
    }

    @Test
    @DisplayName("快照命中时零数据库查询（P1-1 的核心收益）")
    void snapshotServesWithZeroQueries() {
        long spu = nextSpu();
        online(red("零查询", "par-zero", new BigDecimal("40"), null, null, 1, spu, "MAX"));
        store.publish(builder.build(tenant(), "par-zero", 1L));

        Statistics st = emf.unwrap(SessionFactory.class).getStatistics();
        st.clear();
        DiscountView v = query.spuDiscount(req(spu, "500", null), DecisionMode.HOT_PATH);

        assertTrue(v.hit(), "快照路径应命中");
        assertEquals(0, v.hitAmount().compareTo(new BigDecimal("40")));
        assertEquals(0, st.getPrepareStatementCount(),
                "快照命中时不应有任何数据库语句，实际 " + st.getPrepareStatementCount() + " 条");
    }

    @Test
    @DisplayName("回滚：切回上一代快照，决策立刻按旧物料执行")
    void rollbackRestoresPreviousGeneration() {
        long spu = nextSpu();
        CreateResult v1 = marketing.create(red("回滚验证", "par-rb", new BigDecimal("50"), null, null, 1, spu, "MAX"));
        online(v1);
        store.publish(builder.build(tenant(), "par-rb", 1L));
        assertEquals(0, query.spuDiscount(req(spu, "500", null), DecisionMode.HOT_PATH).hitAmount().compareTo(new BigDecimal("50")));

        // 发新版本 → 新快照
        CreateResult v2 = marketing.updateByVersion(edit(v1.activityId(), "回滚验证v2", "par-rb", new BigDecimal("88"), spu));
        online(v2);
        store.publish(builder.build(tenant(), "par-rb", 2L));
        assertEquals(0, query.spuDiscount(req(spu, "500", null), DecisionMode.HOT_PATH).hitAmount().compareTo(new BigDecimal("88")),
                "新快照应给出 88");

        // 回滚 → 立刻恢复旧物料，无需重启、无需反向发布
        assertTrue(store.rollback(tenant(), "par-rb"), "应能回滚到上一代");
        assertEquals(0, query.spuDiscount(req(spu, "500", null), DecisionMode.HOT_PATH).hitAmount().compareTo(new BigDecimal("50")),
                "回滚后应恢复成 50");

        // 只保留一代：再回滚一次应失败（而不是静默成功）
        assertFalse(store.rollback(tenant(), "par-rb"), "没有更早的一代时回滚必须返回 false");
    }

    // ---- helpers ----

    private record Scenario(String name, SpuDiscountRequest request) {}

    private static long nextSpu() { return SPU.incrementAndGet(); }

    /** 与 {@code TenantIdentifierResolver} 在无请求上下文时的兜底一致，保证写入与读取同租户。 */
    private static final String TENANT = "__dev__";

    private String tenant() { return TENANT; }

    private void online(ActivityCreateRequest req) { online(marketing.create(req)); }

    private void online(CreateResult r) {
        marketing.changeStatus(r.activityId(), r.version(), ActivityStatus.ONLINE.code());
    }

    private static SpuDiscountRequest req(long spu, String amount, Integer storeId) {
        return new SpuDiscountRequest(List.of(spu), 1001L, "110000", List.of("vip"),
                new BigDecimal(amount), 1, storeId);
    }

    private static SpuDiscountRequest reqWithLines(List<Long> spus, String amount,
                                                   SpuDiscountRequest.OrderLine... lines) {
        return new SpuDiscountRequest(spus, 1001L, "110000", List.of("vip"),
                new BigDecimal(amount), spus.size(), null,
                lines.length == 0 ? null : List.of(lines));
    }

    private static SpuDiscountRequest.OrderLine line(long spu, String unitPrice, int qty) {
        return new SpuDiscountRequest.OrderLine(spu, new BigDecimal(unitPrice), qty);
    }

    /** 折扣型（单位=折）活动，写平面强制要求封顶。 */
    private ActivityCreateRequest ratio(String name, String bizLine, BigDecimal zhe, long spu) {
        long now = System.currentTimeMillis();
        return new ActivityCreateRequest(
                null, null, name, bizLine, 1, name,
                now - 3_600_000L, now + 3_600_000L, 1, null, 1, 100,
                1, zhe, "折", null, "MAX",
                null, List.of(new ActivityCreateRequest.SpuBinding(1, spu)), null, null,
                new BigDecimal("99999"));
    }

    private static ConditionNode leaf(String field, String op, Object value) {
        ConditionNode n = new ConditionNode();
        n.setField(field); n.setOp(op); n.setValue(value);
        return n;
    }

    private ActivityCreateRequest red(String name, String bizLine, BigDecimal amount, String ladderJson,
                                      ConditionNode cond, int priority, long spu, String strategy) {
        long now = System.currentTimeMillis();
        return new ActivityCreateRequest(
                null, null, name, bizLine, 1, name,
                now - 3_600_000L, now + 3_600_000L, 1, null, priority, 100,
                1, amount, "元", ladderJson, strategy,
                cond, List.of(new ActivityCreateRequest.SpuBinding(1, spu)), null, null);
    }

    /** 金额型活动，绑定**多个** SPU（用于「编辑收窄圈选范围」这类场景）。 */
    private ActivityCreateRequest redBound(String name, String bizLine, BigDecimal amount, List<Long> spus) {
        long now = System.currentTimeMillis();
        return new ActivityCreateRequest(
                null, null, name, bizLine, 1, name,
                now - 3_600_000L, now + 3_600_000L, 1, null, 1, 100,
                1, amount, "元", null, "MAX",
                null, spus.stream().map(s -> new ActivityCreateRequest.SpuBinding(1, s)).toList(), null, null);
    }

    /** 同上，但走编辑（带 activityId → 产生新版本）。 */
    private ActivityCreateRequest editBound(String activityId, String name, String bizLine,
                                            BigDecimal amount, List<Long> spus) {
        long now = System.currentTimeMillis();
        return new ActivityCreateRequest(
                null, activityId, name, bizLine, 1, name,
                now - 3_600_000L, now + 3_600_000L, 1, null, 1, 100,
                1, amount, "元", null, "MAX",
                null, spus.stream().map(s -> new ActivityCreateRequest.SpuBinding(1, s)).toList(), null, null);
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
