package com.lrj.drools.activity;

import com.lrj.drools.activity.domain.ActivityCreateRequest;
import com.lrj.drools.activity.domain.ActivityStatus;
import com.lrj.drools.activity.domain.ConditionNode;
import com.lrj.drools.activity.domain.DecisionMode;
import com.lrj.drools.activity.domain.GiftResult;
import com.lrj.drools.activity.domain.SpuDiscountRequest;
import com.lrj.drools.activity.service.ActivityMarketingService;
import com.lrj.drools.activity.service.ActivityMarketingService.CreateResult;
import com.lrj.drools.activity.service.ActivityQueryService;
import com.lrj.drools.activity.service.ActivityQueryService.DiscountItem;
import com.lrj.drools.activity.service.ActivityQueryService.DiscountView;
import com.lrj.drools.activity.service.ActivityQueryService.GiftView;
import com.lrj.drools.activity.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>决策出口契约</b>：钱有上限，账有明细。
 *
 * <p>这两件事是同一个缺陷的两面。出口原本只有
 * {@code hitActivityId + hitAmount + strategy}——**只有下界（>0）没有上界**，
 * 而且多活动命中时只报一个 id。于是：
 * <ul>
 *   <li>三张「满 100 减 50」打在 120 元订单上，返回 150，负的应付金额交给下游；</li>
 *   <li>STACK 下另外 N−1 个活动在响应里彻底不存在，下游连自建流水都建不对；</li>
 *   <li>用户问「我另外两张券用掉了吗」，客服无从回答；</li>
 *   <li>买赠拿到一堆赠品名，不知道每件是哪个活动送的。</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("h2")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:outcontract;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "activity.marketing.seed-catalog-data=false"
})
@DisplayName("决策出口契约：封顶 + 逐活动明细 + 对账锚点")
class DecisionOutputContractTest {

    private static final AtomicLong SPU = new AtomicLong(920_000L);

    @Autowired ActivityMarketingService marketing;
    @Autowired ActivityQueryService query;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Nested
    @DisplayName("金额上限")
    class Cap {

        @Test
        @DisplayName("单张券面额超过订单金额 → 按订单金额封顶")
        void singleCouponCapped() {
            long spu = nextSpu();
            online(red("大额券", "out-cap", new BigDecimal("50"), spu, "MAX"));

            DiscountView v = query.spuDiscount(req(spu, "30"), DecisionMode.HOT_PATH);

            assertEquals(0, v.hitAmount().compareTo(new BigDecimal("30")),
                    "50 元券打在 30 元订单上只能减 30 —— 减 50 会让应付变成 −20");
            assertTrue(v.clamped(), "截断必须被标记出来");
        }

        @Test
        @DisplayName("三张满减叠加超额 → 封顶，且明细如实报各自金额")
        void stackedCouponsCappedButItemisedInFull() {
            long spu = nextSpu();
            online(red("A", "out-cap3", new BigDecimal("50"), spu, "STACK"));
            online(red("B", "out-cap3", new BigDecimal("50"), spu, "STACK"));
            online(red("C", "out-cap3", new BigDecimal("50"), spu, "STACK"));

            DiscountView v = query.spuDiscount(req(spu, "120"), DecisionMode.HOT_PATH);

            assertEquals(0, v.hitAmount().compareTo(new BigDecimal("120")),
                    "150 > 120，出口按订单金额封顶");
            assertTrue(v.clamped());
            assertEquals(3, v.items().size(), "三张券都要出现在明细里");
            assertEquals(0, v.items().stream().map(DiscountItem::amount)
                            .reduce(BigDecimal.ZERO, BigDecimal::add).compareTo(new BigDecimal("150")),
                    "明细是**订单级封顶前**的各活动金额，合计 150；封顶只改出口总额");
        }

        @Test
        @DisplayName("没超额就不标 clamped（避免告警噪声）")
        void normalDecisionIsNotClamped() {
            long spu = nextSpu();
            online(red("正常券", "out-nocap", new BigDecimal("20"), spu, "MAX"));

            DiscountView v = query.spuDiscount(req(spu, "500"), DecisionMode.HOT_PATH);

            assertEquals(0, v.hitAmount().compareTo(new BigDecimal("20")));
            assertFalse(v.clamped(), "正常决策不得标记截断，否则这个指标会被噪声淹没");
        }

        @Test
        @DisplayName("订单金额缺失时不封顶——红包面额本就与订单金额无关")
        void missingOrderAmountIsNotCapped() {
            long spu = nextSpu();
            online(red("无门槛券", "out-noamt", new BigDecimal("20"), spu, "MAX"));

            DiscountView v = query.spuDiscount(req(spu, null), DecisionMode.HOT_PATH);

            assertEquals(0, v.hitAmount().compareTo(new BigDecimal("20")),
                    "上游没传订单金额时无从判断是否超发，一律按 0 处理会把正常决策打没");
            assertFalse(v.clamped());
        }
    }

    @Nested
    @DisplayName("逐活动明细")
    class Items {

        @Test
        @DisplayName("单选策略下：只有赢家 applied，落选者也在明细里")
        void losersAreVisibleToo() {
            long spu = nextSpu();
            online(red("小额", "out-items", new BigDecimal("10"), spu, "MAX"));
            online(red("大额", "out-items", new BigDecimal("80"), spu, "MAX"));

            DiscountView v = query.spuDiscount(req(spu, "500"), DecisionMode.HOT_PATH);

            assertEquals(2, v.items().size());
            assertEquals(1, v.items().stream().filter(DiscountItem::applied).count(),
                    "MAX 是单选，只能有一个 applied");
            assertEquals(0, v.items().stream().filter(DiscountItem::applied)
                    .findFirst().orElseThrow().amount().compareTo(new BigDecimal("80")));
        }

        @Test
        @DisplayName("被资格淘汰的候选带得出原因——「为什么我没享受到」才是客服工单的多数")
        void rejectedCandidateCarriesReason() {
            long spu = nextSpu();
            online(redWithCond("满100可用", "out-reject", new BigDecimal("20"), spu,
                    leaf("orderAmount", "ge", 100)));

            DiscountView v = query.spuDiscount(req(spu, "99"), DecisionMode.HOT_PATH);

            assertFalse(v.hit(), "99 < 100 不该命中");
            assertEquals(1, v.items().size(), "被淘汰的候选也要出现在明细里");
            DiscountItem item = v.items().get(0);
            assertFalse(item.applied());
            assertNotNull(item.rejectReason(), "必须说明为什么没生效");
        }

        @Test
        @DisplayName("命中项带版本号——「这笔钱按哪一版算的」")
        void hitCarriesVersion() {
            long spu = nextSpu();
            CreateResult v1 = marketing.create(red("版本券", "out-ver", new BigDecimal("15"), spu, "MAX"));
            marketing.changeStatus(v1.activityId(), v1.version(), ActivityStatus.ONLINE.code());

            DiscountView v = query.spuDiscount(req(spu, "500"), DecisionMode.HOT_PATH);

            assertEquals(v1.version(), v.hitVersion(), "命中活动的版本必须可见");
        }
    }

    @Nested
    @DisplayName("对账锚点")
    class Anchor {

        @Test
        @DisplayName("每次决策都有 decisionId，且两次不同")
        void decisionIdIsPresentAndUnique() {
            long spu = nextSpu();
            online(red("锚点券", "out-anchor", new BigDecimal("12"), spu, "MAX"));

            DiscountView a = query.spuDiscount(req(spu, "500"), DecisionMode.HOT_PATH);
            DiscountView b = query.spuDiscount(req(spu, "500"), DecisionMode.HOT_PATH);

            assertNotNull(a.decisionId(), "客服拿这一串在日志里定位当时的决策");
            assertNotEquals(a.decisionId(), b.decisionId(), "两次决策必须能区分开");
        }

        @Test
        @DisplayName("未命中的决策同样带 decisionId——「查不到」也需要能查")
        void missAlsoCarriesDecisionId() {
            DiscountView v = query.spuDiscount(req(nextSpu(), "500"), DecisionMode.HOT_PATH);
            assertFalse(v.hit());
            assertNotNull(v.decisionId(), "「为什么我没优惠」正是最需要回溯的那一类工单");
        }

        @Test
        @DisplayName("赠品带活动归属——否则收到一堆赠品名不知道是谁送的")
        void giftsCarryActivityId() {
            long spu = nextSpu();
            CreateResult r = marketing.create(gift("满额赠", "out-gift", spu));
            marketing.changeStatus(r.activityId(), r.version(), ActivityStatus.ONLINE.code());

            GiftView v = query.buyAndGetGifts(req(spu, "500"), DecisionMode.HOT_PATH);

            assertFalse(v.gifts().isEmpty(), "应发出赠品");
            assertNotNull(v.decisionId());
            for (GiftResult g : v.gifts()) {
                assertEquals(r.activityId(), g.getActivityId(), "赠品必须带来源活动");
                assertEquals(r.version(), g.getVersion(), "赠品必须带来源版本");
            }
        }
    }

    // ---- helpers ----

    private static long nextSpu() { return SPU.incrementAndGet(); }

    private void online(ActivityCreateRequest req) {
        CreateResult r = marketing.create(req);
        marketing.changeStatus(r.activityId(), r.version(), ActivityStatus.ONLINE.code());
    }

    private static SpuDiscountRequest req(long spu, String amount) {
        return new SpuDiscountRequest(List.of(spu), 1001L, "110000", List.of("vip"),
                amount == null ? null : new BigDecimal(amount), 1, null);
    }

    private static ConditionNode leaf(String field, String op, Object value) {
        ConditionNode n = new ConditionNode();
        n.setField(field); n.setOp(op); n.setValue(value);
        return n;
    }

    private ActivityCreateRequest red(String name, String bizLine, BigDecimal amount, long spu, String strategy) {
        return redWithCond(name, bizLine, amount, spu, null, strategy);
    }

    private ActivityCreateRequest redWithCond(String name, String bizLine, BigDecimal amount, long spu,
                                              ConditionNode cond) {
        return redWithCond(name, bizLine, amount, spu, cond, "MAX");
    }

    private ActivityCreateRequest redWithCond(String name, String bizLine, BigDecimal amount, long spu,
                                              ConditionNode cond, String strategy) {
        long now = System.currentTimeMillis();
        return new ActivityCreateRequest(
                null, null, name, bizLine, 1, name,
                now - 3_600_000L, now + 3_600_000L, 1, null, 1, 100,
                1, amount, "元", null, strategy,
                cond, List.of(new ActivityCreateRequest.SpuBinding(1, spu)), null, null);
    }

    private ActivityCreateRequest gift(String name, String bizLine, long spu) {
        long now = System.currentTimeMillis();
        return new ActivityCreateRequest(
                null, null, name, bizLine, 5, name,
                now - 3_600_000L, now + 3_600_000L, 1, null, 1, 100,
                1, null, "元", null, "MAX",
                null, List.of(new ActivityCreateRequest.SpuBinding(1, spu)),
                null, List.of(new ActivityCreateRequest.GiftInput("B1", "赠品杯子", "GOODS",
                        1, BigDecimal.ZERO, "GIFT")));
    }
}
