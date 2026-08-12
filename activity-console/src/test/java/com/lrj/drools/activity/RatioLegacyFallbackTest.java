package com.lrj.drools.activity;

import com.lrj.drools.activity.domain.ActivityCreateRequest;
import com.lrj.drools.activity.domain.ActivityStatus;
import com.lrj.drools.activity.domain.DecisionMode;
import com.lrj.drools.activity.domain.SpuDiscountRequest;
import com.lrj.drools.activity.service.ActivityMarketingService;
import com.lrj.drools.activity.service.ActivityMarketingService.CreateResult;
import com.lrj.drools.activity.service.ActivityQueryService;
import com.lrj.drools.activity.service.ActivityQueryService.DiscountView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 折扣型在**回退路径**上的金额。
 *
 * <p>旧逻辑 {@code legacyMax} 直接读 {@code redPackageAmount} 当元——而折扣型往那个字段里放的是**折数**。
 * 不作区分的话，「打 8 折」在回退时会被当成「减 8 元」发出去：金额是正数、决策成功、日志干净，
 * 没有任何地方会报错。
 *
 * <p>回退**不是罕见分支**：引擎开关关闭、规则空决策、规则执行异常，三种情况都会走到那里。
 * 本类用最确定的一种（把 {@code rule-engine.enabled} 关掉）把它钉住。
 */
@SpringBootTest
@ActiveProfiles("h2")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:actratiolegacy;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "activity.marketing.seed-demo-data=false",
        // 关键：整个规则引擎关掉 → 决策必然走 legacyMax
        "activity.marketing.rule-engine.enabled=false"
})
@DisplayName("折扣型 · 引擎关闭走旧逻辑时的金额")
class RatioLegacyFallbackTest {

    @Autowired ActivityMarketingService marketing;
    @Autowired ActivityQueryService query;

    private static long spuSeq = 760_000L;

    @Test
    @DisplayName("旧逻辑必须按折算钱，而不是把折数当元发")
    void legacyComputesRatioNotRawValue() {
        long spu = ++spuSeq;
        online(marketing.create(zhe("回退折扣券", new BigDecimal("8"), new BigDecimal("99999"), spu)));

        DiscountView v = query.spuDiscount(req(spu, new BigDecimal("100")), DecisionMode.HOT_PATH);

        assertEquals("legacy", v.mode(), "前提：本用例必须真的走到旧逻辑，否则它什么也没证明");
        assertEquals(0, v.hitAmount().compareTo(new BigDecimal("20")),
                "8 折 × 100 元 = 减 20；若这里是 8，说明旧逻辑把折数当成了元");
    }

    @Test
    @DisplayName("旧逻辑同样尊重封顶")
    void legacyRespectsCap() {
        long spu = ++spuSeq;
        online(marketing.create(zhe("回退封顶券", new BigDecimal("8"), new BigDecimal("50"), spu)));

        DiscountView v = query.spuDiscount(req(spu, new BigDecimal("10000")), DecisionMode.HOT_PATH);

        assertEquals(0, v.hitAmount().compareTo(new BigDecimal("50")),
                "回退路径不封顶的话，越是引擎出问题的时候越会超发");
    }

    @Test
    @DisplayName("旧逻辑的 MAX 竞争比的也是算出来的钱")
    void legacyComparesComputedMoney() {
        long spu = ++spuSeq;
        // 8 折 × 100 = 20 应胜过固定 15；若拿折数 8 去比，赢的会是 15 那张
        online(marketing.create(zhe("八折", new BigDecimal("8"), new BigDecimal("99999"), spu)));
        online(marketing.create(fixed("固定十五", new BigDecimal("15"), spu)));

        DiscountView v = query.spuDiscount(req(spu, new BigDecimal("100")), DecisionMode.HOT_PATH);
        assertEquals(0, v.hitAmount().compareTo(new BigDecimal("20")));
    }

    @Test
    @DisplayName("算不出来（缺订单金额）时按 0 计，不发一个来路不明的数")
    void legacyPaysNothingWhenNotComputable() {
        long spu = ++spuSeq;
        online(marketing.create(zhe("无金额", new BigDecimal("8"), new BigDecimal("50"), spu)));

        DiscountView v = query.spuDiscount(req(spu, null), DecisionMode.HOT_PATH);
        assertEquals(0, v.hitAmount().compareTo(BigDecimal.ZERO),
                "没有订单金额就算不出折扣；出现 8 即为把折数当元");
    }

    // ---- helpers ----

    private void online(CreateResult r) {
        marketing.changeStatus(r.activityId(), r.version(), ActivityStatus.ONLINE.code());
    }

    private ActivityCreateRequest zhe(String name, BigDecimal zheValue, BigDecimal cap, long spu) {
        return build(name, zheValue, "折", cap, spu);
    }

    private ActivityCreateRequest fixed(String name, BigDecimal amount, long spu) {
        return build(name, amount, "元", null, spu);
    }

    private ActivityCreateRequest build(String name, BigDecimal amount, String unit, BigDecimal cap, long spu) {
        long now = System.currentTimeMillis();
        return new ActivityCreateRequest(
                null, null, name, "ratio-legacy", 1, null,
                now - 3_600_000L, now + 3_600_000L, 1, null, 1, 100,
                1, amount, unit, null, "MAX",
                null, List.of(new ActivityCreateRequest.SpuBinding(1, spu)), null, null,
                cap);
    }

    private static SpuDiscountRequest req(long spu, BigDecimal orderAmount) {
        return new SpuDiscountRequest(List.of(spu), 1001L, "110000", List.of("vip"), orderAmount, 1);
    }
}
