package com.lrj.drools.activity;

import com.lrj.drools.activity.metrics.DecisionMetrics;
import com.lrj.drools.activity.domain.ActivityCandidate;
import com.lrj.drools.activity.domain.ActivityRuleContext;
import com.lrj.drools.activity.domain.BenefitForm;
import com.lrj.drools.activity.engine.BenefitEvaluator;
import com.lrj.drools.activity.engine.BenefitMath;
import com.lrj.drools.activity.engine.LadderRangeParser;
import com.lrj.drools.activity.engine.RandomRangeParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 随机红包（确定性随机）。
 *
 * <p>这批测试守的核心不变量只有一条：**同一上下文永远算出同一个金额**。
 * 它不是"锦上添花的稳定性"，是这个功能能否上线的前提——决策接口会被重复调用
 * （用户刷新、前端重试、网关重放、事后对账），真随机意味着同一笔单每次报价不同。
 */
class RandomAmountTest {

    private final BenefitEvaluator evaluator = new BenefitEvaluator(DecisionMetrics.noop());

    private static ActivityCandidate randomCandidate(String rangeJson) {
        ActivityCandidate c = new ActivityCandidate();
        c.setActivityId("ACT-RAND-1");
        c.setActivityName("随机红包");
        c.setVersion(1);
        c.setRedPackageTakeType(2);              // DistributionMode.RANDOM_AMOUNT
        c.setRedPackageRangeAmount(rangeJson);
        c.setEligible(true);
        return c;
    }

    private static ActivityRuleContext ctx(Object userId, Object orderAmount) {
        ActivityRuleContext c = new ActivityRuleContext();
        c.putAttr("userId", userId);
        c.putAttr("orderAmount", orderAmount);
        c.putAttr("quantity", 1);
        // spuId 现在是**整个购物车的 SPU 列表**（作用域改造）；
        // 确定性随机的指纹刻意读另一个键 randomSeedSpu，好让种子在那次改造里保持不变。
        // 两个都要放：只放 spuId 的话，指纹里的 SPU 段恒为 "null"，
        // 「购物车指纹参与种子」这件事就没人守了——将来谁把 drawRandom 改回读 spuId，
        // 线上随机红包会全量重抽，而测试全绿。
        c.putAttr("spuId", java.util.List.of(990011L));
        c.putAttr("randomSeedSpu", 990011L);
        return c;
    }

    private BigDecimal compute(ActivityCandidate c, ActivityRuleContext ctx) {
        List<ActivityCandidate> list = new ArrayList<>(List.of(c));
        evaluator.computeAmounts(ctx, list);
        return list.get(0).isAmountComputed() ? list.get(0).getComputedAmount() : null;
    }

    @Nested
    @DisplayName("确定性")
    class Determinism {

        @Test
        @DisplayName("100 与 100.00 必须抽到同一个金额——纯格式差异不得改变价格")
        void scaleDifferenceDoesNotChangeAmount() {
            BigDecimal plain = compute(randomCandidate("{\"min\":5,\"max\":20}"), ctx(1001L, new BigDecimal("100")));
            BigDecimal scaled = compute(randomCandidate("{\"min\":5,\"max\":20}"), ctx(1001L, new BigDecimal("100.00")));

            assertThat(scaled).as(
                    "指纹此前直接用 toString()，于是 100 与 100.00 是两个种子、两个金额。"
                            + "而「同一笔订单刷新不变价」正是确定性随机存在的全部理由——"
                            + "一个纯粹的格式差异就能让用户看到价格跳动，是这套机制最不该出现的失效方式")
                    .isEqualByComparingTo(plain);
        }

        @Test
        @DisplayName("同一活动+用户+购物车，重复计算金额完全一致（刷新不变价）")
        void sameContextSameAmount() {
            BigDecimal first = compute(randomCandidate("{\"min\":5,\"max\":20}"), ctx(1001L, new BigDecimal("200")));
            for (int i = 0; i < 50; i++) {
                BigDecimal again = compute(randomCandidate("{\"min\":5,\"max\":20}"), ctx(1001L, new BigDecimal("200")));
                assertThat(again).as("第 %d 次重算", i).isEqualByComparingTo(first);
            }
        }

        @Test
        @DisplayName("不同用户抽到的金额会分散，不是全场同一个数")
        void differentUsersSpread() {
            List<BigDecimal> amounts = new ArrayList<>();
            for (long uid = 1; uid <= 40; uid++) {
                amounts.add(compute(randomCandidate("{\"min\":1,\"max\":100}"), ctx(uid, new BigDecimal("200"))));
            }
            // 只断言"没退化成常量"。不断言具体分布——那会把测试变成对散列实现的快照。
            assertThat(amounts).doesNotContainNull();
            assertThat(amounts.stream().distinct().count())
                    .as("40 个用户至少应抽出 10 个不同金额，否则随机退化了")
                    .isGreaterThan(10);
        }

        @Test
        @DisplayName("同一用户换订单金额会重抽")
        void differentOrderRedraw() {
            BigDecimal a = compute(randomCandidate("{\"min\":1,\"max\":100}"), ctx(1001L, new BigDecimal("200")));
            BigDecimal b = compute(randomCandidate("{\"min\":1,\"max\":100}"), ctx(1001L, new BigDecimal("999")));
            assertThat(a).isNotEqualByComparingTo(b);
        }

        @Test
        @DisplayName("运营改了版本号会重抽（否则改配置像是没生效）")
        void versionBumpRedraw() {
            ActivityCandidate v1 = randomCandidate("{\"min\":1,\"max\":100}");
            ActivityCandidate v2 = randomCandidate("{\"min\":1,\"max\":100}");
            v2.setVersion(2);
            assertThat(compute(v1, ctx(1001L, new BigDecimal("200"))))
                    .isNotEqualByComparingTo(compute(v2, ctx(1001L, new BigDecimal("200"))));
        }
    }

    @Nested
    @DisplayName("区间与边界")
    class Bounds {

        @Test
        @DisplayName("金额恒落在闭区间 [min,max] 内，且是整分")
        void withinClosedRange() {
            BigDecimal min = new BigDecimal("5"), max = new BigDecimal("20");
            for (long uid = 1; uid <= 200; uid++) {
                BigDecimal v = compute(randomCandidate("{\"min\":5,\"max\":20}"), ctx(uid, new BigDecimal("200")));
                assertThat(v).isNotNull();
                assertThat(v).isBetween(min, max);
                assertThat(v.scale()).isEqualTo(2);
            }
        }

        @Test
        @DisplayName("min == max 时恒定发那个数")
        void degenerateRange() {
            BigDecimal v = compute(randomCandidate("{\"min\":8,\"max\":8}"), ctx(1001L, new BigDecimal("200")));
            assertThat(v).isEqualByComparingTo(new BigDecimal("8.00"));
        }

        @Test
        @DisplayName("上下界都可能被抽中（不是开区间）")
        void boundsReachable() {
            boolean sawMin = false, sawMax = false;
            for (long uid = 1; uid <= 400 && !(sawMin && sawMax); uid++) {
                BigDecimal v = compute(randomCandidate("{\"min\":1,\"max\":3}"), ctx(uid, new BigDecimal("200")));
                if (v.compareTo(new BigDecimal("1.00")) == 0) sawMin = true;
                if (v.compareTo(new BigDecimal("3.00")) == 0) sawMax = true;
            }
            assertThat(sawMin).as("下界应可被抽中").isTrue();
            assertThat(sawMax).as("上界应可被抽中").isTrue();
        }
    }

    @Nested
    @DisplayName("fail-closed：算不出来就不给优惠，而不是给 0 元")
    class FailClosed {

        @Test
        @DisplayName("区间缺失 / 非法 / 负数 / min>max → 不给优惠")
        void badRangeYieldsNoBenefit() {
            for (String bad : new String[]{null, "", "not-json", "{}", "{\"min\":5}",
                    "{\"min\":-1,\"max\":10}", "{\"min\":20,\"max\":5}"}) {
                assertThat(compute(randomCandidate(bad), ctx(1001L, new BigDecimal("200"))))
                        .as("range=%s 应不给优惠", bad)
                        .isNull();
            }
        }

        @Test
        @DisplayName("0 元不会被当成'算出来了'——它会以 0 参与 MAX 竞争挤掉别的活动")
        void notComputedMeansNotEligibleForMax() {
            List<ActivityCandidate> list = new ArrayList<>(List.of(randomCandidate("bad-json")));
            evaluator.computeAmounts(ctx(1001L, new BigDecimal("200")), list);
            assertThat(list.get(0).isAmountComputed()).isFalse();
        }
    }

    @Nested
    @DisplayName("与既有形态不打架")
    class NoRegression {

        @Test
        @DisplayName("takeType 为 null / 1 / 未知 code 一律走固定金额（旧行为不漂移）")
        void nonRandomUnaffected() {
            for (Integer code : new Integer[]{null, 1, 99}) {
                ActivityCandidate c = randomCandidate("{\"min\":5,\"max\":20}");
                c.setRedPackageTakeType(code);
                c.setRedPackageAmount(new BigDecimal("12.00"));
                assertThat(compute(c, ctx(1001L, new BigDecimal("200"))))
                        .as("takeType=%s 应按固定金额发", code)
                        .isEqualByComparingTo(new BigDecimal("12.00"));
            }
        }

        @Test
        @DisplayName("随机区间是 JSON 对象，阶梯解析器看它得到空——两条路径不抢同一份数据")
        void rangeDoesNotCollideWithLadder() {
            assertThat(LadderRangeParser.parse("{\"min\":5,\"max\":20}")).isEmpty();
            assertThat(RandomRangeParser.parse("[{\"min\":0,\"max\":100,\"reward\":5}]")).isNull();
        }

        @Test
        @DisplayName("BenefitForm 优先于 takeType：折 + takeType=2 仍按折扣算，不被随机分支抢走")
        void benefitFormWinsOverDirtyTakeType() {
            ActivityCandidate c = randomCandidate("{\"min\":5,\"max\":20}");
            c.setRedPackageAmountUnit(BenefitForm.UNIT_ZHE);
            c.setRedPackageAmount(new BigDecimal("8"));
            c.setRedPackageMaxDiscount(new BigDecimal("50"));

            assertThat(compute(c, ctx(1001L, new BigDecimal("200"))))
                    .isEqualByComparingTo(new BigDecimal("40.00"));
        }
    }

    @Nested
    @DisplayName("BenefitMath 纯函数")
    class Math {

        @Test
        @DisplayName("种子为空 / 区间非法 → null")
        void guards() {
            assertThat(BenefitMath.randomAmount(null, BigDecimal.TEN, "k")).isNull();
            assertThat(BenefitMath.randomAmount(BigDecimal.ONE, null, "k")).isNull();
            assertThat(BenefitMath.randomAmount(BigDecimal.ONE, BigDecimal.TEN, null)).isNull();
            assertThat(BenefitMath.randomAmount(BigDecimal.ONE, BigDecimal.TEN, "  ")).isNull();
            assertThat(BenefitMath.randomAmount(BigDecimal.TEN, BigDecimal.ONE, "k")).isNull();
        }

        @Test
        @DisplayName("同 seed 跨调用稳定——这是可重放与对账的前提")
        void stableAcrossCalls() {
            BigDecimal a = BenefitMath.randomAmount(new BigDecimal("1"), new BigDecimal("100"), "seed-x");
            BigDecimal b = BenefitMath.randomAmount(new BigDecimal("1"), new BigDecimal("100"), "seed-x");
            assertThat(a).isEqualByComparingTo(b);
        }

        @Test
        @DisplayName("角分级区间也精确（不碰浮点）")
        void centsPrecision() {
            for (int i = 0; i < 50; i++) {
                BigDecimal v = BenefitMath.randomAmount(new BigDecimal("0.01"), new BigDecimal("0.03"), "s" + i);
                assertThat(v).isBetween(new BigDecimal("0.01"), new BigDecimal("0.03"));
                assertThat(v.scale()).isEqualTo(2);
            }
        }
    }
}
