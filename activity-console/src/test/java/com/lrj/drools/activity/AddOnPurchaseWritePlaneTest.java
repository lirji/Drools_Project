package com.lrj.drools.activity;

import com.lrj.drools.activity.domain.ActivityCreateRequest;
import com.lrj.drools.activity.domain.ActivityCreateRequest.GiftInput;
import com.lrj.drools.activity.domain.ActivityStatus;
import com.lrj.drools.activity.domain.DecisionMode;
import com.lrj.drools.activity.domain.RuleScene;
import com.lrj.drools.activity.domain.SpuDiscountRequest;
import com.lrj.drools.activity.persistence.ActivityGiftEntity;
import com.lrj.drools.activity.persistence.ActivityGiftRepository;
import com.lrj.drools.activity.persistence.ActivityStrategyRepository;
import com.lrj.drools.activity.service.ActivityMarketingService;
import com.lrj.drools.activity.service.AddOnPurchaseService;
import com.lrj.drools.activity.service.ActivityMarketingService.CreateResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 加价购在**写平面**的闸门。
 *
 * <p>写入口此前只放行红包(1)/买赠(5)，加价购(6) 建不出来——决策侧的两阶段早就通了，
 * 数据却只能手造。放行之后，这里的每一条校验都<b>对着 {@code AddOnPurchaseService} 的一行代码</b>：
 * <ul>
 *   <li>{@code options()} 遍历 gifts 出选项 → 一个都没有 = 上线了但没有任何可换购选项</li>
 *   <li>{@code options()} 对 {@code absoluteAmount <= 0} <b>静默 continue</b> → 不拦住的话运营配的选项会一声不响消失</li>
 *   <li>{@code quote()} 按 {@code itemName} 匹配 → 重名的那个永远选不中，且服务端无法判定用户选了哪个</li>
 * </ul>
 * 也就是说：这些不是表单洁癖，是「能存进来的必须在决策侧跑得通」。
 */
@SpringBootTest
@ActiveProfiles("h2")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:actaddonwrite;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "activity.marketing.seed-demo-data=false"
})
@DisplayName("加价购写平面：能存进来的必须在决策侧跑得通")
class AddOnPurchaseWritePlaneTest {

    @Autowired ActivityMarketingService marketing;
    @Autowired ActivityGiftRepository giftRepo;
    @Autowired ActivityStrategyRepository strategyRepo;
    @Autowired AddOnPurchaseService addOn;

    private static long spuSeq = 810_000L;

    @Test
    @DisplayName("加价购可以创建了——类型 6 不再被白名单拒")
    void addOnCanBeCreated() {
        CreateResult r = assertDoesNotThrow(() -> marketing.create(addOn(
                List.of(item("品牌保温杯", "9.9"), item("定制帆布袋", "19.9")))));

        List<ActivityGiftEntity> saved = giftRepo.findByActivityIdAndVersionAndIsDel(r.activityId(), r.version(), 0);
        assertEquals(2, saved.size());
        // 加价金额落的是 absolute_amount：决策侧 AddOnPurchaseService 读的就是这一列
        assertTrue(saved.stream().anyMatch(g ->
                "品牌保温杯".equals(g.getGiftName()) && g.getAbsoluteAmount().compareTo(new BigDecimal("9.9")) == 0));
    }

    @Test
    @DisplayName("一个换购品都没有 → 拒。否则活动显示「已上线」而用户侧什么都看不到")
    void emptyItemsRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> marketing.create(addOn(List.of())));
        assertTrue(e.getMessage().contains("换购品"), e.getMessage());

        assertThrows(IllegalArgumentException.class, () -> marketing.create(addOn(null)));
    }

    @Test
    @DisplayName("加价金额必须 > 0：决策侧对 <=0 是静默跳过，写入口不拦就会「配了等于没配」")
    void nonPositiveAddOnPriceRejected() {
        for (String bad : new String[]{"0", "-1"}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> marketing.create(addOn(List.of(item("保温杯", bad)))),
                    "加价金额 " + bad + " 应被拒");
            assertTrue(e.getMessage().contains("加价金额"), e.getMessage());
        }
        // 金额缺失同理
        assertThrows(IllegalArgumentException.class,
                () -> marketing.create(addOn(List.of(new GiftInput(null, "保温杯", "PHYSICAL", 1, null, "ADD_ON")))));
    }

    @Test
    @DisplayName("品名必填且活动内唯一——第二阶段按品名匹配，重名会选不中")
    void nameRequiredAndUnique() {
        assertThrows(IllegalArgumentException.class,
                () -> marketing.create(addOn(List.of(item("  ", "9.9")))));

        IllegalArgumentException dup = assertThrows(IllegalArgumentException.class,
                () -> marketing.create(addOn(List.of(item("保温杯", "9.9"), item("保温杯", "19.9")))));
        assertTrue(dup.getMessage().contains("唯一"), dup.getMessage());
    }

    @Test
    @DisplayName("买赠的校验不受影响（旧行为零变更）")
    void buyAndGetUnchanged() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> marketing.create(typed(5, null)));
        assertTrue(e.getMessage().contains("赠品"), e.getMessage());
        // 买赠的赠品价值允许为 0（它不是加价额），不该被加价购那条规则误伤
        assertDoesNotThrow(() -> marketing.create(typed(5, List.of(item("赠品A", "0")))));
    }

    @Test
    @DisplayName("买赠/加价购不得改写 bizLine 级红包合并策略")
    void nonDiscountActivitiesCannotOverwriteGlobalDiscountStrategy() {
        String bizLine = "addon-strategy-" + (++spuSeq);

        marketing.create(typedInBizLine(bizLine, 1, List.of(), "STACK"));
        assertEquals("STACK", discountStrategy(bizLine));

        // 非红包表单常带默认 MAX；它们不能把同业务线已有 STACK 覆盖掉。
        marketing.create(typedInBizLine(bizLine, 6, List.of(item("保温杯", "9.9")), "MAX"));
        assertEquals("STACK", discountStrategy(bizLine));

        marketing.create(typedInBizLine(bizLine, 5, List.of(item("赠品A", "0")), "PRIORITY"));
        assertEquals("STACK", discountStrategy(bizLine));
    }

    /**
     * **跨边界的那条链**：写平面建出来的加价购活动，决策侧真的能列出选项并报价。
     *
     * <p>这个玩法此前的处境是「两侧都有测试、中间断裂」——{@code AddOnPurchaseTest} 手造候选证明算得对，
     * 目录测试证明模板配得对，但没有一条测试走过「create → 上线 → options → quote」。
     * 断裂处正好就是 bug 所在（写入口根本不收 type=6）。这条用例把缝焊上。
     */
    @org.junit.jupiter.api.Nested
    @DisplayName("接进决策链路")
    class Wiring {

        @Test
        @DisplayName("建出来的加价购能列出选项，且第二阶段按品名报出权威价格")
        void createdActivityIsDecidable() {
            long spu = ++spuSeq;
            CreateResult r = marketing.create(withSpu(spu, List.of(item("品牌保温杯", "9.9"), item("定制帆布袋", "19.9"))));
            marketing.changeStatus(r.activityId(), r.version(), ActivityStatus.ONLINE.code());

            SpuDiscountRequest req = new SpuDiscountRequest(
                    List.of(spu), 1001L, null, null, new BigDecimal("200"), 1);

            AddOnPurchaseService.AddOnOptions options = addOn.options(req, DecisionMode.EXPLAIN);
            assertEquals(2, options.options().size(), "写平面存进去的换购品必须在决策侧列得出来");
            assertTrue(options.options().stream().anyMatch(o ->
                    "品牌保温杯".equals(o.itemName()) && o.addOnPrice().compareTo(new BigDecimal("9.9")) == 0));

            // 第二阶段只认「哪个活动的哪个换购品」，价格重新查——客户端传什么价都不读
            AddOnPurchaseService.AddOnQuote quote = addOn.quote(req, r.activityId(), "定制帆布袋", DecisionMode.EXPLAIN);
            assertTrue(quote.ok(), quote.reason());
            assertEquals(0, quote.addOnPrice().compareTo(new BigDecimal("19.9")));

            // 不存在的换购品不能报出价来（否则等于按不存在的配置卖货）
            assertTrue(!addOn.quote(req, r.activityId(), "不存在的东西", DecisionMode.EXPLAIN).ok());
        }

        private ActivityCreateRequest withSpu(long spu, List<GiftInput> items) {
            long now = System.currentTimeMillis();
            return new ActivityCreateRequest(
                    null, null, "加价购-链路-" + spu, "addon", 6, null,
                    now - 3_600_000L, now + 3_600_000L, 1, null, 1, 100,
                    null, null, "元", null, "MAX",
                    null, List.of(new ActivityCreateRequest.SpuBinding(1, spu)), null, items,
                    null);
        }
    }

    // ---- helpers ----

    private static GiftInput item(String name, String addOnPrice) {
        return new GiftInput(null, name, "PHYSICAL", 1,
                addOnPrice == null ? null : new BigDecimal(addOnPrice), "ADD_ON");
    }

    private ActivityCreateRequest addOn(List<GiftInput> items) {
        return typed(6, items);
    }

    private ActivityCreateRequest typed(int activityType, List<GiftInput> items) {
        return typedInBizLine("addon", activityType, items, "MAX");
    }

    private ActivityCreateRequest typedInBizLine(String bizLine, int activityType,
                                                  List<GiftInput> items, String strategy) {
        long now = System.currentTimeMillis();
        long spu = ++spuSeq;
        boolean redPackage = activityType == 1;
        return new ActivityCreateRequest(
                null, null, "活动-" + spu, bizLine, activityType, null,
                now - 3_600_000L, now + 3_600_000L, 1, null, 1, 100,
                redPackage ? 1 : null, redPackage ? new BigDecimal("10") : null, "元", null, strategy,
                null, List.of(new ActivityCreateRequest.SpuBinding(1, spu)), null, items,
                null);
    }

    private String discountStrategy(String bizLine) {
        return strategyRepo.findFirstByBizLineAndActivityTypeIsNullAndSceneAndIsDel(
                        bizLine, RuleScene.DISCOUNT.code(), 0)
                .orElseThrow()
                .getStrategy();
    }
}
