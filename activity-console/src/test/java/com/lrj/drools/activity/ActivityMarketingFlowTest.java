package com.lrj.drools.activity;

import com.lrj.drools.activity.domain.ActivityCreateRequest;
import com.lrj.drools.activity.domain.ActivityStatus;
import com.lrj.drools.activity.domain.ConditionNode;
import com.lrj.drools.activity.domain.DecisionMode;
import com.lrj.drools.activity.domain.SpuDiscountRequest;
import com.lrj.drools.activity.persistence.CatalogProductEntity;
import com.lrj.drools.activity.persistence.CatalogProductRepository;
import com.lrj.drools.activity.persistence.ProductPoolEntity;
import com.lrj.drools.activity.persistence.ProductPoolRepository;
import com.lrj.drools.activity.persistence.ProductPoolRuleEntity;
import com.lrj.drools.activity.persistence.ProductPoolRuleRepository;
import com.lrj.drools.activity.service.ActivityMarketingService;
import com.lrj.drools.activity.service.ActivityMarketingService.CreateResult;
import com.lrj.drools.activity.service.ActivityQueryService;
import com.lrj.drools.activity.service.ActivityQueryService.DiscountView;
import com.lrj.drools.activity.service.ActivityQueryService.GiftView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 活动营销核心链路集成测试（H2 内存库）。
 *
 * 重点：**真正跑一遍 Drools 规则**（资格淘汰 / 折扣合并 / 阶梯 / 买赠），
 * 因为 `mvn compile` 不校验 DRL 语法（CLAUDE.md 坑 4/6），只有执行才暴露规则错误。
 * 各用例用不同 SPU 段隔离，避免共享库互相污染。
 */
@SpringBootTest
@ActiveProfiles("h2")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:acttest;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "activity.marketing.rule-engine.enabled=true",
        "activity.marketing.seed-catalog-data=false"
})
class ActivityMarketingFlowTest {

    @Autowired ActivityMarketingService marketing;
    @Autowired ActivityQueryService query;
    @Autowired CatalogProductRepository catalogProductRepo;
    @Autowired ProductPoolRepository poolRepo;
    @Autowired ProductPoolRuleRepository poolRuleRepo;

    private long now() { return System.currentTimeMillis(); }
    private long hAgo() { return now() - 3_600_000L; }
    private long hLater() { return now() + 3_600_000L; }

    /** 资格淘汰 + MAX 折扣合并：订单金额决定第一个活动是否被资格淘汰。 */
    @Test
    void eligibilityAndMaxDiscount() {
        // A: 金额 80，资格要求 orderAmount >= 100，绑定 spu 1001
        ConditionNode cond = leaf("orderAmount", "ge", 100);
        CreateResult a = marketing.create(redPackage("大额红包", "biz-a", new BigDecimal("80"),
                cond, 1001L, 1, null, null));
        // B: 金额 50，无资格条件，绑定 spu 1001
        CreateResult b = marketing.create(redPackage("普通红包", "biz-a", new BigDecimal("50"),
                null, 1001L, 2, null, null));
        online(a); online(b);

        // 订单 200：A 通过（80）+ B（50）→ MAX 命中 80
        DiscountView big = query.spuDiscount(spuReq(1001L, new BigDecimal("200")), DecisionMode.HOT_PATH);
        assertTrue(big.hit());
        assertEquals(0, big.hitAmount().compareTo(new BigDecimal("80")), "订单达标应命中大额红包 80");

        // 订单 50：A 被资格淘汰 → 只剩 B（50）
        DiscountView small = query.spuDiscount(spuReq(1001L, new BigDecimal("50")), DecisionMode.HOT_PATH);
        assertTrue(small.hit());
        assertEquals(0, small.hitAmount().compareTo(new BigDecimal("50")), "订单不达标应只剩普通红包 50");
    }

    /** 上下线：下线后查询不命中。 */
    @Test
    void offlineMiss() {
        CreateResult a = marketing.create(redPackage("限时红包", "biz-b", new BigDecimal("30"),
                null, 2001L, 1, null, null));
        online(a);
        assertTrue(query.spuDiscount(spuReq(2001L, new BigDecimal("100")), DecisionMode.HOT_PATH).hit());

        marketing.changeStatus(a.activityId(), a.version(), ActivityStatus.OFFLINE.code());
        assertFalse(query.spuDiscount(spuReq(2001L, new BigDecimal("100")), DecisionMode.HOT_PATH).hit(), "下线后不应命中");
    }

    /** 版本化编辑：金额从 30 改到 60，版本 +1，查询命中新金额。 */
    @Test
    void versionEdit() {
        CreateResult a = marketing.create(redPackage("可编辑红包", "biz-c", new BigDecimal("30"),
                null, 3001L, 1, null, null));
        online(a);

        ActivityCreateRequest edit = redPackage("可编辑红包v2", "biz-c", new BigDecimal("60"),
                null, 3001L, 1, null, a.activityId());
        CreateResult v2 = marketing.updateByVersion(edit);
        assertEquals(2, v2.version(), "编辑后版本应为 2");
        online(v2);

        DiscountView view = query.spuDiscount(spuReq(3001L, new BigDecimal("100")), DecisionMode.HOT_PATH);
        assertTrue(view.hit());
        assertEquals(0, view.hitAmount().compareTo(new BigDecimal("60")), "应命中编辑后的 60");
    }

    /** 阶梯：订单金额落档给不同补贴。 */
    @Test
    void ladder() {
        String tiers = "[{\"min\":0,\"max\":100,\"reward\":5},{\"min\":100,\"max\":200,\"reward\":12},{\"min\":200,\"max\":null,\"reward\":25}]";
        CreateResult a = marketing.create(redPackage("阶梯红包", "biz-d", null,
                null, 4001L, 1, tiers, null));
        online(a);

        assertEquals(0, query.spuDiscount(spuReq(4001L, new BigDecimal("50")), DecisionMode.HOT_PATH).hitAmount().compareTo(new BigDecimal("5")));
        assertEquals(0, query.spuDiscount(spuReq(4001L, new BigDecimal("150")), DecisionMode.HOT_PATH).hitAmount().compareTo(new BigDecimal("12")));
        assertEquals(0, query.spuDiscount(spuReq(4001L, new BigDecimal("300")), DecisionMode.HOT_PATH).hitAmount().compareTo(new BigDecimal("25")));
    }

    /** 买赠：命中活动返回赠品。 */
    @Test
    void buyAndGet() {
        ActivityCreateRequest req = new ActivityCreateRequest(
                null, null, "买一赠一", "biz-e", 5 /*BUY_AND_GET*/, "买 A 赠 B",
                hAgo(), hLater(), 1, null, 1, 100,
                null, null, null, null, null, null,
                List.of(new ActivityCreateRequest.SpuBinding(1, 5001L)),
                null,
                List.of(new ActivityCreateRequest.GiftInput("B001", "赠品耳机", "PHYSICAL", 1, new BigDecimal("99"), "GIFT")));
        CreateResult a = marketing.create(req);
        online(a);

        GiftView gifts = query.buyAndGetGifts(spuReq(5001L, new BigDecimal("100")), DecisionMode.HOT_PATH);
        assertEquals(1, gifts.gifts().size(), "应返回 1 个赠品");
        assertEquals("赠品耳机", gifts.gifts().get(0).getGiftName());
    }

    /** 商品池自动圈选：规则圈中的商品自动绑定，查询命中。 */
    @Test
    void poolAutoBind() {
        // 目录商品：价格 120、类目 electronics、在架
        catalogProductRepo.save(new CatalogProductEntity(6001L, 1, "耳机", "electronics", new BigDecimal("120"), "hot", 1));
        catalogProductRepo.save(new CatalogProductEntity(6002L, 1, "沙发", "furniture", new BigDecimal("120"), "hot", 1));

        // 池：类目 electronics + 价格 [100,200]
        ProductPoolEntity pool = new ProductPoolEntity();
        pool.setPoolName("电子池"); pool.setBizLine("biz-f"); pool.setPoolType(1); pool.setStatus(1);
        pool.setIsDel(0); pool.setCreatedStime(Instant.now()); pool.setModifiedStime(Instant.now());
        pool = poolRepo.save(pool);

        ProductPoolRuleEntity rule = new ProductPoolRuleEntity();
        rule.setPoolId(pool.getId()); rule.setMinPrice(new BigDecimal("100")); rule.setMaxPrice(new BigDecimal("200"));
        rule.setCategories("electronics"); rule.setEnabled(1); rule.setIsDel(0);
        rule.setCreatedStime(Instant.now()); rule.setModifiedStime(Instant.now());
        poolRuleRepo.save(rule);

        // 活动引用该池
        ActivityCreateRequest req = new ActivityCreateRequest(
                null, null, "池红包", "biz-f", 1, "电子品类红包",
                hAgo(), hLater(), 1, null, 1, 100,
                1, new BigDecimal("40"), "元", null, null, null,
                null, List.of(pool.getId()), null);
        CreateResult a = marketing.create(req);
        assertTrue(a.autoBoundCount() >= 1, "应至少自动绑定 1 个商品");
        online(a);

        // 6001 命中（electronics 120），6002 不命中（furniture）
        assertTrue(query.spuDiscount(spuReq(6001L, new BigDecimal("100")), DecisionMode.HOT_PATH).hit(), "电子商品应命中池红包");
        assertFalse(query.spuDiscount(spuReq(6002L, new BigDecimal("100")), DecisionMode.HOT_PATH).hit(), "非电子商品不应命中");
    }

    // ------------------------------------------------------------------ helper

    private ConditionNode leaf(String field, String op, Object value) {
        ConditionNode n = new ConditionNode();
        n.setField(field); n.setOp(op); n.setValue(value);
        return n;
    }

    private ActivityCreateRequest redPackage(String name, String bizLine, BigDecimal amount,
                                             ConditionNode cond, Long spuId, int priority,
                                             String ladderJson, String editActivityId) {
        return new ActivityCreateRequest(
                null, editActivityId, name, bizLine, 1, name,
                hAgo(), hLater(), 1, null, priority, 100,
                1, amount, "元", ladderJson, "MAX",
                cond,
                List.of(new ActivityCreateRequest.SpuBinding(1, spuId)),
                null, null);
    }

    private void online(CreateResult r) {
        marketing.changeStatus(r.activityId(), r.version(), ActivityStatus.ONLINE.code());
    }

    private SpuDiscountRequest spuReq(Long spuId, BigDecimal orderAmount) {
        return new SpuDiscountRequest(List.of(spuId), 1001L, "110000", List.of("vip"), orderAmount, 1);
    }
}
