package com.lrj.drools.activity;

import com.lrj.drools.activity.domain.ActivityCreateRequest;
import com.lrj.drools.activity.domain.ActivityStatus;
import com.lrj.drools.activity.domain.ConditionNode;
import com.lrj.drools.activity.domain.SpuDiscountRequest;
import com.lrj.drools.activity.service.ActivityMarketingService;
import com.lrj.drools.activity.service.ActivityMarketingService.CreateResult;
import com.lrj.drools.activity.service.ActivityQueryService;
import com.lrj.drools.activity.service.ActivityQueryService.DiscountView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
 * <b>决策金标集（B0-2）</b>——后续所有重构的安全网。
 *
 * <p><b>为什么必须先有它</b>：改造前全仓 31 个测试类里，端到端**金额**断言只有 7 条，
 * 其余 amount 相关断言测的都是翻译出来的 DRL 字符串，不测钱。
 * 在这个覆盖率下动权益模型或把计算移出规则引擎，等于闭着眼改钱。
 *
 * <p><b>它守什么</b>：把「同样的活动配置 + 同样的订单上下文 → 同样的金额」钉死。
 * 后面无论是把 N+1 改成批量查、把阶梯落档移出 Drools、还是换成 BenefitSpec 求值，
 * 只要这组用例还绿，就证明**钱没算错**；一旦红，红的那一行直接指出是哪种策略/哪个档位边界坏了。
 *
 * <p><b>两条刻意的设计</b>
 * <ol>
 *   <li>金额一律用 {@code compareTo} 比，不用 {@code equals}——{@code BigDecimal("50")} 与
 *       {@code BigDecimal("50.00")} 的 {@code equals} 为 false。这是重构中最容易漏的一类回归
 *       （scale 漂移），用 equals 会在无害的 scale 变化上误报、却在真的算错时可能漏报。</li>
 *   <li>每个用例独占一个 SPU、每种策略独占一条 bizLine——策略是 <b>bizLine 级</b>配置
 *       （{@code ActivityStrategyEntity} 按 bizLine 落库并被 upsert 覆盖），
 *       混用 bizLine 会让用例之间互相改写策略，红得莫名其妙。</li>
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("h2")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:actgolden;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "activity.marketing.seed-demo-data=false"
})
@DisplayName("决策金标集：同配置同上下文必须给出同金额")
class DecisionGoldenSetTest {

    /** 阶梯档位：[0,100)→5 · [100,200)→12 · [200,∞)→25。边界值是这组用例的重点。 */
    private static final String TIERS =
            "[{\"min\":0,\"max\":100,\"reward\":5},{\"min\":100,\"max\":200,\"reward\":12},{\"min\":200,\"max\":null,\"reward\":25}]";

    private static final AtomicLong SPU_SEQ = new AtomicLong(500_000L);

    @Autowired ActivityMarketingService marketing;
    @Autowired ActivityQueryService query;
    @Autowired com.lrj.drools.activity.persistence.ActivityManageRepository manageRepo;

    // ================================================================ 1. 阶梯落档边界（9 例）

    @Nested
    @DisplayName("阶梯落档边界")
    class Ladder {

        @ParameterizedTest(name = "订单 {0} 元 → 补贴 {1} 元（{2}）")
        @CsvSource({
                "0,      5,  恰等首档下界",
                "0.01,   5,  首档区间内",
                "50,     5,  首档中部",
                "99.99,  5,  恰在首档上界之下",
                "100,    12, 恰等第二档下界（上界是开区间 必须落到下一档）",
                "150,    12, 第二档中部",
                "199.99, 12, 恰在第二档上界之下",
                "200,    25, 恰等末档下界",
                "99999,  25, 远超末档（末档无上界）",
        })
        void ladderTierBoundaries(String orderAmount, String expected, String why) {
            long spu = nextSpu();
            online(marketing.create(red("阶梯", "gold-ladder", null, TIERS, null, 1, spu, "MAX")));

            DiscountView v = query.spuDiscount(req(spu, new BigDecimal(orderAmount)));
            assertAmount(expected, v, why);
        }

        @Test
        @DisplayName("订单金额缺失 → 阶梯不参与，退回固定金额")
        void ladderSkippedWhenOrderAmountMissing() {
            long spu = nextSpu();
            online(marketing.create(red("阶梯带底价", "gold-ladder", new BigDecimal("7"), TIERS, null, 1, spu, "MAX")));

            DiscountView v = query.spuDiscount(req(spu, null));
            assertAmount("7", v, "无订单金额时阶梯闸门不开，应落到 redPackageAmount");
        }
    }

    // ================================================================ 2. 四种合并策略（12 例）

    @Nested
    @DisplayName("多活动合并策略")
    class Merge {

        @Test @DisplayName("MAX · 三个候选取最大")
        void maxPicksLargest() {
            long spu = nextSpu();
            onlineAll("gold-max", spu, "MAX", amt(30, 1), amt(80, 1), amt(55, 1));
            assertAmount("80", query.spuDiscount(req(spu, new BigDecimal("500"))), "MAX 取最大");
        }

        @Test @DisplayName("MAX · 单候选")
        void maxSingle() {
            long spu = nextSpu();
            onlineAll("gold-max", spu, "MAX", amt(42, 1));
            assertAmount("42", query.spuDiscount(req(spu, new BigDecimal("500"))), "单候选即结果");
        }

        @Test @DisplayName("MAX · 零候选 → 不命中且金额为 0")
        void maxNoCandidate() {
            DiscountView v = query.spuDiscount(req(nextSpu(), new BigDecimal("500")));
            assertFalse(v.hit(), "无绑定活动不应命中");
            assertEquals(0, v.hitAmount().compareTo(BigDecimal.ZERO));
        }

        @Test @DisplayName("MAX · 金额相同取其一，金额必须正确")
        void maxTie() {
            long spu = nextSpu();
            onlineAll("gold-max", spu, "MAX", amt(60, 1), amt(60, 2));
            assertAmount("60", query.spuDiscount(req(spu, new BigDecimal("500"))), "并列时金额仍是 60");
        }

        @Test @DisplayName("PRIORITY · priority 数字小者胜，与金额大小无关")
        void priorityWinsOverAmount() {
            long spu = nextSpu();
            onlineAll("gold-priority", spu, "PRIORITY", amt(10, 1), amt(90, 5));
            assertAmount("10", query.spuDiscount(req(spu, new BigDecimal("500"))),
                    "priority=1 优先于 priority=5，哪怕它金额更小");
        }

        @Test @DisplayName("PRIORITY · 同 priority 再比金额")
        void priorityTieBreaksByAmount() {
            long spu = nextSpu();
            onlineAll("gold-priority", spu, "PRIORITY", amt(10, 3), amt(90, 3));
            assertAmount("90", query.spuDiscount(req(spu, new BigDecimal("500"))), "同优先级取金额大者");
        }

        @Test @DisplayName("MUTEX · 与 PRIORITY 同语义（互斥单选）")
        void mutexPicksByPriority() {
            long spu = nextSpu();
            onlineAll("gold-mutex", spu, "MUTEX", amt(15, 2), amt(70, 8));
            assertAmount("15", query.spuDiscount(req(spu, new BigDecimal("500"))), "MUTEX 按 priority 单选");
        }

        @Test @DisplayName("STACK · 全部候选金额累加")
        void stackSumsAll() {
            long spu = nextSpu();
            onlineAll("gold-stack", spu, "STACK", amt(10, 1), amt(20, 2), amt(30, 3));
            assertAmount("60", query.spuDiscount(req(spu, new BigDecimal("500"))), "STACK 累加 10+20+30");
        }

        @Test @DisplayName("STACK · 单候选等于其自身")
        void stackSingle() {
            long spu = nextSpu();
            onlineAll("gold-stack", spu, "STACK", amt(33, 1));
            assertAmount("33", query.spuDiscount(req(spu, new BigDecimal("500"))), "单条累加即自身");
        }
    }

    // ================================================================ 3. 资格淘汰（6 例）

    @Nested
    @DisplayName("资格条件淘汰")
    class Eligibility {

        @Test @DisplayName("条件满足 → 命中")
        void conditionMet() {
            long spu = nextSpu();
            online(marketing.create(red("满 100 可用", "gold-elig", new BigDecimal("20"), null,
                    leaf("orderAmount", "ge", 100), 1, spu, "MAX")));
            assertAmount("20", query.spuDiscount(req(spu, new BigDecimal("150"))), "150 ≥ 100 应命中");
        }

        @Test @DisplayName("条件不满足 → 淘汰，且不得回退成命中")
        void conditionNotMet() {
            long spu = nextSpu();
            online(marketing.create(red("满 100 可用", "gold-elig", new BigDecimal("20"), null,
                    leaf("orderAmount", "ge", 100), 1, spu, "MAX")));
            DiscountView v = query.spuDiscount(req(spu, new BigDecimal("99")));
            assertFalse(v.hit(), "99 < 100 应被资格淘汰；若这里变成命中，说明 fail-closed 破了");
            assertEquals(0, v.hitAmount().compareTo(BigDecimal.ZERO));
        }

        @Test @DisplayName("多候选中只淘汰不满足者")
        void partialElimination() {
            long spu = nextSpu();
            online(marketing.create(red("高门槛大额", "gold-elig", new BigDecimal("99"), null,
                    leaf("orderAmount", "ge", 100000), 1, spu, "MAX")));
            online(marketing.create(red("无门槛小额", "gold-elig", new BigDecimal("8"), null,
                    null, 1, spu, "MAX")));
            assertAmount("8", query.spuDiscount(req(spu, new BigDecimal("500"))),
                    "大额被淘汰后应取剩下的 8，而不是回退成 99");
        }

        @Test @DisplayName("storeId 条件可命中（此前是死条件）")
        void storeIdConditionWorks() {
            long spu = nextSpu();
            online(marketing.create(red("门店专享", "gold-elig", new BigDecimal("18"), null,
                    leaf("storeId", "eq", 1), 1, spu, "MAX")));
            DiscountView v = query.spuDiscount(new SpuDiscountRequest(
                    List.of(spu), 1001L, "110000", List.of("vip"), new BigDecimal("500"), 1, 1));
            assertAmount("18", v, "storeId 已进入决策入参，条件必须能命中");
        }

        @Test @DisplayName("storeId 缺失 → fail-closed 淘汰，绝不静默放行")
        void storeIdMissingFailsClosed() {
            long spu = nextSpu();
            online(marketing.create(red("门店专享", "gold-elig", new BigDecimal("18"), null,
                    leaf("storeId", "eq", 1), 1, spu, "MAX")));
            DiscountView v = query.spuDiscount(req(spu, new BigDecimal("500")));   // 不传 storeId
            assertFalse(v.hit(), "缺字段必须 fail-closed（宁可不发，不可超发）");
        }

        @Test @DisplayName("用户标签 contains 条件")
        void userTagCondition() {
            long spu = nextSpu();
            online(marketing.create(red("VIP 专享", "gold-elig", new BigDecimal("25"), null,
                    leaf("userTags", "contains", "vip"), 1, spu, "MAX")));
            assertAmount("25", query.spuDiscount(req(spu, new BigDecimal("500"))), "标签含 vip 应命中");
        }
    }

    // ================================================================ 4. 金额精度与边界（6 例）

    @Nested
    @DisplayName("金额精度")
    class Precision {

        @ParameterizedTest(name = "配置 {0} → 命中金额与 {0} 数值相等")
        @CsvSource({"50", "50.00", "0.01", "9.99", "1234.56"})
        void amountValueIsPreservedRegardlessOfScale(String amount) {
            long spu = nextSpu();
            online(marketing.create(red("精度", "gold-precision", new BigDecimal(amount), null, null, 1, spu, "MAX")));
            DiscountView v = query.spuDiscount(req(spu, new BigDecimal("500")));
            // 用 compareTo：scale 变化（50 → 50.00）不算回归，数值变了才算
            assertEquals(0, v.hitAmount().compareTo(new BigDecimal(amount)),
                    "金额数值必须原样保留；期望 " + amount + " 实际 " + v.hitAmount());
        }

        @Test @DisplayName("STACK 累加不丢精度")
        void stackKeepsPrecision() {
            long spu = nextSpu();
            onlineAll("gold-stackp", spu, "STACK", amt(new BigDecimal("0.01"), 1), amt(new BigDecimal("0.02"), 2));
            assertAmount("0.03", query.spuDiscount(req(spu, new BigDecimal("500"))), "0.01+0.02 必须等于 0.03");
        }
    }

    // ================================================================ 5. 生效窗与状态（4 例）

    @Nested
    @DisplayName("生效窗与上下线")
    class Lifecycle {

        @Test @DisplayName("未上线的活动不参与决策")
        void draftNotConsidered() {
            long spu = nextSpu();
            marketing.create(red("草稿", "gold-life", new BigDecimal("50"), null, null, 1, spu, "MAX"));
            assertFalse(query.spuDiscount(req(spu, new BigDecimal("500"))).hit(), "NORMAL 状态不应命中");
        }

        @Test @DisplayName("已下线的活动不参与决策")
        void offlineNotConsidered() {
            long spu = nextSpu();
            CreateResult r = marketing.create(red("待下线", "gold-life", new BigDecimal("50"), null, null, 1, spu, "MAX"));
            online(r);
            marketing.changeStatus(r.activityId(), r.version(), ActivityStatus.OFFLINE.code());
            assertFalse(query.spuDiscount(req(spu, new BigDecimal("500"))).hit(), "OFFLINE 不应命中");
        }

        @Test @DisplayName("未开始的活动不参与决策")
        void notStartedYet() {
            long spu = nextSpu();
            long from = System.currentTimeMillis() + 3_600_000L;
            long to = from + 7_200_000L;
            online(marketing.create(redWindow("未来活动", "gold-life", new BigDecimal("50"), spu, from, to)));
            assertFalse(query.spuDiscount(req(spu, new BigDecimal("500"))).hit(), "时间窗未到不应命中");
        }

        @Test @DisplayName("编辑已上线的活动，线上版本继续服务（P0-4：编辑不等于下线）")
        void editingOnlineActivityDoesNotTakeItDown() {
            long spu = nextSpu();
            CreateResult v1 = marketing.create(red("在跑的活动", "gold-life", new BigDecimal("50"), null, null, 1, spu, "MAX"));
            online(v1);
            assertAmount("50", query.spuDiscount(req(spu, new BigDecimal("500"))), "上线后应命中 50");

            // 运营改了个字（金额也顺手改了）→ 产生 v2 草稿
            CreateResult v2 = marketing.updateByVersion(
                    edit(v1.activityId(), "在跑的活动(改)", "gold-life", new BigDecimal("80"), spu));
            assertEquals(2, v2.version().intValue(), "编辑应产生 v2");

            // 关键断言：v1 仍在服务，金额不变。旧实现在这里会 hit=false（活动凭空消失）
            assertAmount("50", query.spuDiscount(req(spu, new BigDecimal("500"))),
                    "编辑只产生草稿，正在服务的 v1 不得被下线");
        }

        @Test @DisplayName("发布草稿 → 原子切到新版本，旧线上版本退役")
        void publishingDraftSwitchesPointer() {
            long spu = nextSpu();
            CreateResult v1 = marketing.create(red("待换版", "gold-life", new BigDecimal("50"), null, null, 1, spu, "MAX"));
            online(v1);
            CreateResult v2 = marketing.updateByVersion(
                    edit(v1.activityId(), "待换版v2", "gold-life", new BigDecimal("80"), spu));

            online(v2);
            assertAmount("80", query.spuDiscount(req(spu, new BigDecimal("500"))), "发布后应命中 v2 的 80");

            // 且不能出现"两个版本同时在线"——旧版本必须被退役
            long onlineCount = manageRepo.findByActivityIdAndActivityStatusAndIsDel(
                    v1.activityId(), ActivityStatus.ONLINE.code(), 0).size();
            assertEquals(1, onlineCount, "同一活动同时只能有一个 ONLINE 版本，实际 " + onlineCount);
        }

        @Test @DisplayName("已结束的活动不参与决策")
        void alreadyEnded() {
            long spu = nextSpu();
            long to = System.currentTimeMillis() - 3_600_000L;
            long from = to - 7_200_000L;
            online(marketing.create(redWindow("过期活动", "gold-life", new BigDecimal("50"), spu, from, to)));
            assertFalse(query.spuDiscount(req(spu, new BigDecimal("500"))).hit(), "时间窗已过不应命中");
        }
    }

    // ================================================================ 6. 引擎模式（2 例）

    @Test
    @DisplayName("引擎开启时 mode=rule-engine（回退率指标据此归因）")
    void modeIsRuleEngineWhenHit() {
        long spu = nextSpu();
        online(marketing.create(red("模式", "gold-mode", new BigDecimal("11"), null, null, 1, spu, "MAX")));
        DiscountView v = query.spuDiscount(req(spu, new BigDecimal("500")));
        assertEquals("rule-engine", v.mode(), "命中时应由规则引擎给出，而非回退");
    }

    @Test
    @DisplayName("无候选时 mode=legacy 且不命中")
    void modeIsLegacyWhenNoCandidate() {
        DiscountView v = query.spuDiscount(req(nextSpu(), new BigDecimal("500")));
        assertEquals("legacy", v.mode());
        assertFalse(v.hit());
    }

    // ================================================================ 7. explain 开关（2 例）

    @Test
    @DisplayName("热路径默认 explain=false —— 不产出 trace")
    void hotPathEmitsNoTrace() {
        long spu = nextSpu();
        online(marketing.create(red("无 trace", "gold-explain", new BigDecimal("13"), null, null, 1, spu, "MAX")));
        DiscountView v = query.spuDiscount(req(spu, new BigDecimal("500")));
        assertAmount("13", v, "关掉 trace 不能影响金额");
        assertTrue(v.traces().isEmpty(),
                "决策热路径不应 emit trace（构建期就不该生成 result.trace 语句），实际=" + v.traces());
    }

    @Test
    @DisplayName("控制台试算 explain=true —— 产出可读链路，且金额与热路径一致")
    void consolePreviewEmitsTrace() {
        long spu = nextSpu();
        online(marketing.create(red("有 trace", "gold-explain", new BigDecimal("13"), null, null, 1, spu, "MAX")));
        DiscountView hot = query.spuDiscount(req(spu, new BigDecimal("500")), false);
        DiscountView dbg = query.spuDiscount(req(spu, new BigDecimal("500")), true);

        assertFalse(dbg.traces().isEmpty(), "开 explain 应能看到命中链路");
        assertEquals(0, hot.hitAmount().compareTo(dbg.hitAmount()),
                "explain 只影响可观测性，绝不能影响金额");
        assertEquals(hot.hitActivityId(), dbg.hitActivityId());
    }

    // ================================================================ 8. 折扣型（按折数）（7 例）

    /**
     * 折扣类权益。这些用例同样会被 {@code DroolsBenefitGoldenSetTest} 在 <b>DRL 路径</b>上再跑一遍——
     * 两条路都调 {@code BenefitMath.ratioDiscount}，所以真正被钉住的是「折数怎么变成钱」这一件事，
     * 以及它在合并阶段是不是以**算出来的钱**参与竞争（而不是拿折数当金额比）。
     */
    @Nested
    @DisplayName("折扣型（按折数）")
    class Ratio {

        @ParameterizedTest(name = "订单 {0} 元打 {1} 折 → 减 {2} 元（{3}）")
        @CsvSource({
                "100,    8,   20.00, 整数好算的基准",
                "100,    8.5, 15.00, 折数带小数",
                "333.33, 8,   66.66, 除不尽必须**向下**取整到分（66.666 → 66.66，不是 66.67）",
                "0.01,   5,   0.00,  金额小到减免不足一分时给 0 而不是四舍五入成 0.01",
        })
        void ratioComputesDiscount(String orderAmount, String zheValue, String expected, String why) {
            long spu = nextSpu();
            online(marketing.create(zhe("折扣", "gold-ratio", new BigDecimal(zheValue),
                    new BigDecimal("99999"), 1, spu, "MAX")));

            DiscountView v = query.spuDiscount(req(spu, new BigDecimal(orderAmount)));
            assertAmount(expected, v, why);
        }

        @Test
        @DisplayName("封顶生效：大额订单上减免被截断到封顶值")
        void capTruncates() {
            long spu = nextSpu();
            online(marketing.create(zhe("封顶券", "gold-ratio", new BigDecimal("8"),
                    new BigDecimal("50"), 1, spu, "MAX")));

            // 不封顶的话 10000 × 20% = 2000
            DiscountView v = query.spuDiscount(req(spu, new BigDecimal("10000")));
            assertAmount("50", v, "封顶必须截断——不封顶等于无上限支出");
        }

        @Test
        @DisplayName("订单金额缺失 → 折扣算不出来，**不得**退回把折数当元发")
        void missingOrderAmountDoesNotPayTheRatioValue() {
            long spu = nextSpu();
            online(marketing.create(zhe("无金额", "gold-ratio", new BigDecimal("8"),
                    new BigDecimal("50"), 1, spu, "MAX")));

            DiscountView v = query.spuDiscount(req(spu, null));
            assertEquals(0, v.hitAmount().compareTo(BigDecimal.ZERO),
                    "没有订单金额就算不出折扣；若这里出现 8，说明折数被当成了 8 元发出去");
        }

        @Test
        @DisplayName("与金额型同台竞争 MAX：比的是算出来的钱，不是折数")
        void ratioCompetesByComputedMoney() {
            long spu = nextSpu();
            // 8 折 × 100 元 = 减 20；固定券减 15。若把折数 8 当金额比，赢的会是 15 那张。
            online(marketing.create(zhe("八折券", "gold-ratio-mix", new BigDecimal("8"),
                    new BigDecimal("99999"), 1, spu, "MAX")));
            online(marketing.create(red("固定券", "gold-ratio-mix", new BigDecimal("15"),
                    null, null, 1, spu, "MAX")));

            DiscountView v = query.spuDiscount(req(spu, new BigDecimal("100")));
            assertAmount("20", v, "折扣券应以 20 元参与 MAX 竞争并胜出");
        }
    }

    // ================================================================ helpers

    private static long nextSpu() { return SPU_SEQ.incrementAndGet(); }

    private record Amt(BigDecimal amount, int priority) {}
    private static Amt amt(int a, int p) { return new Amt(new BigDecimal(a), p); }
    private static Amt amt(BigDecimal a, int p) { return new Amt(a, p); }

    private void onlineAll(String bizLine, long spu, String strategy, Amt... amts) {
        for (Amt a : amts) {
            online(marketing.create(red("act", bizLine, a.amount(), null, null, a.priority(), spu, strategy)));
        }
    }

    private void assertAmount(String expected, DiscountView v, String why) {
        assertTrue(v.hit(), why + "：应当命中但没有（mode=" + v.mode() + "）");
        assertEquals(0, v.hitAmount().compareTo(new BigDecimal(expected)),
                why + "：期望 " + expected + "，实际 " + v.hitAmount());
    }

    private void online(CreateResult r) {
        marketing.changeStatus(r.activityId(), r.version(), ActivityStatus.ONLINE.code());
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

    /** 编辑：带 activityId 的 create（= updateByVersion 的入参）。 */
    private ActivityCreateRequest edit(String activityId, String name, String bizLine, BigDecimal amount, long spu) {
        long now = System.currentTimeMillis();
        return new ActivityCreateRequest(
                null, activityId, name, bizLine, 1, name,
                now - 3_600_000L, now + 3_600_000L, 1, null, 1, 100,
                1, amount, "元", null, "MAX",
                null, List.of(new ActivityCreateRequest.SpuBinding(1, spu)), null, null);
    }

    private ActivityCreateRequest redWindow(String name, String bizLine, BigDecimal amount,
                                            long spu, long from, long to) {
        return new ActivityCreateRequest(
                null, null, name, bizLine, 1, name, from, to, 1, null, 1, 100,
                1, amount, "元", null, "MAX",
                null, List.of(new ActivityCreateRequest.SpuBinding(1, spu)), null, null);
    }

    /** 折扣型活动：单位「折」，redPackageAmount 是折数，cap 是封顶减免额（写平面强制要求）。 */
    private ActivityCreateRequest zhe(String name, String bizLine, BigDecimal zheValue, BigDecimal cap,
                                      int priority, long spu, String strategy) {
        long now = System.currentTimeMillis();
        return new ActivityCreateRequest(
                null, null, name, bizLine, 1, name,
                now - 3_600_000L, now + 3_600_000L, 1, null, priority, 100,
                1, zheValue, "折", null, strategy,
                null, List.of(new ActivityCreateRequest.SpuBinding(1, spu)), null, null,
                cap);
    }

    private static SpuDiscountRequest req(long spu, BigDecimal orderAmount) {
        return new SpuDiscountRequest(List.of(spu), 1001L, "110000", List.of("vip"), orderAmount, 1);
    }
}
