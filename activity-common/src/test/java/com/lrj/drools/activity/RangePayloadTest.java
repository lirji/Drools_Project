package com.lrj.drools.activity;

import com.lrj.drools.activity.domain.BenefitForm;
import com.lrj.drools.activity.domain.DistributionMode;
import com.lrj.drools.activity.engine.RangePayload;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R9 单一解析出口的**判别规则**。
 *
 * <p>{@code redPackageRangeAmount} 一列三用途，判别规则此前在 Java 侧写了三遍
 * （{@code ladderDefs} / {@code BenefitEvaluator} / {@code validateRangeColumn}）。
 * 本类钉的是那条被三处共享的规则本身：
 * <ol>
 *   <li><b>顶层 JSON 类型</b>先把数组与非数组切开，数组归阶梯，<b>且不看形态</b>；</li>
 *   <li>非数组再由 <b>单位 + 发放方式</b> 切成随机区间 / 第 N 件 / 阶梯。</li>
 * </ol>
 *
 * <p>金额是否正确由 {@code RandomAmountTest} / {@code NthItemDiscountTest} /
 * {@code DecisionGoldenSetTest} 守；这里只守「这段 JSON 算哪种载荷」。
 */
@DisplayName("R9 RangePayload：一列三载荷的判别规则")
class RangePayloadTest {

    private static final Integer RANDOM = DistributionMode.RANDOM_AMOUNT.code();

    @Nested
    @DisplayName("第 1 刀：顶层 JSON 类型")
    class TopLevelType {

        @Test
        @DisplayName("数组 → 阶梯")
        void arrayIsLadder() {
            RangePayload p = RangePayload.parse(BenefitForm.AMOUNT, null,
                    "[{\"min\":0,\"max\":100,\"reward\":5}]");
            assertThat(p).isInstanceOf(RangePayload.Ladder.class);
            assertThat(((RangePayload.Ladder) p).tiers()).hasSize(1);
        }

        @Test
        @DisplayName("数组归阶梯**不看形态**——单位配成件折也一样，与改造前 ladderDefs 的行为一致")
        void arrayStaysLadderEvenForOtherForms() {
            assertThat(RangePayload.parse(BenefitForm.NTH_ZHE, null, "[{\"reward\":5}]"))
                    .isInstanceOf(RangePayload.Ladder.class);
            assertThat(RangePayload.parse(BenefitForm.AMOUNT, RANDOM, "[{\"reward\":5}]"))
                    .isInstanceOf(RangePayload.Ladder.class);
        }

        @Test
        @DisplayName("对象不会被当成阶梯（否则随机区间与阶梯会互抢同一份数据）")
        void objectIsNeverLadder() {
            assertThat(RangePayload.parse(BenefitForm.AMOUNT, null, "{\"min\":5,\"max\":20}"))
                    .isEqualTo(RangePayload.INVALID);
        }
    }

    @Nested
    @DisplayName("第 2 刀：单位 + 发放方式")
    class Discriminant {

        @Test
        @DisplayName("件折 → 第 N 件")
        void nthZheReadsNth() {
            assertThat(RangePayload.parse(BenefitForm.NTH_ZHE, null, "{\"nth\":2}"))
                    .isEqualTo(new RangePayload.Nth(2));
        }

        @Test
        @DisplayName("元 + 发放方式=随机 → 区间")
        void amountWithRandomTakeTypeReadsRange() {
            assertThat(RangePayload.parse(BenefitForm.AMOUNT, RANDOM, "{\"min\":5,\"max\":20}"))
                    .isInstanceOf(RangePayload.Random.class);
        }

        @Test
        @DisplayName("折 + 发放方式=随机 → 仍不是随机区间：形态判别位优先级高于 takeType")
        void ratioIsNotRandomEvenWithRandomTakeType() {
            assertThat(RangePayload.expectedKind(BenefitForm.RATIO_ZHE, RANDOM))
                    .isEqualTo(RangePayload.Kind.LADDER);
        }

        @Test
        @DisplayName("未知 takeType 一律按固定金额（脏数据按旧行为，不猜）")
        void unknownTakeTypeIsNotRandom() {
            assertThat(RangePayload.expectedKind(BenefitForm.AMOUNT, 99))
                    .isEqualTo(RangePayload.Kind.LADDER);
            assertThat(RangePayload.expectedKind(BenefitForm.AMOUNT, null))
                    .isEqualTo(RangePayload.Kind.LADDER);
        }
    }

    @Nested
    @DisplayName("没配 与 配错 是两件事")
    class NoneVsInvalid {

        @Test
        @DisplayName("null / 空白 → None")
        void blankIsNone() {
            assertThat(RangePayload.parse(BenefitForm.AMOUNT, null, null)).isEqualTo(RangePayload.NONE);
            assertThat(RangePayload.parse(BenefitForm.AMOUNT, null, "  ")).isEqualTo(RangePayload.NONE);
        }

        @Test
        @DisplayName("非法 JSON / 空档位 / N<2 / 负区间 → Invalid，且**不抛异常**（读侧要 fail-closed，写侧才报错）")
        void unparsableIsInvalid() {
            assertThat(RangePayload.parse(BenefitForm.AMOUNT, null, "{bad")).isEqualTo(RangePayload.INVALID);
            assertThat(RangePayload.parse(BenefitForm.AMOUNT, null, "[]")).isEqualTo(RangePayload.INVALID);
            assertThat(RangePayload.parse(BenefitForm.AMOUNT, null, "[{\"min\":0}]")).isEqualTo(RangePayload.INVALID);
            assertThat(RangePayload.parse(BenefitForm.NTH_ZHE, null, "{\"nth\":1}")).isEqualTo(RangePayload.INVALID);
            assertThat(RangePayload.parse(BenefitForm.AMOUNT, RANDOM, "{\"min\":20,\"max\":5}"))
                    .isEqualTo(RangePayload.INVALID);
            assertThat(RangePayload.parse(BenefitForm.AMOUNT, RANDOM, "{\"min\":-1,\"max\":5}"))
                    .isEqualTo(RangePayload.INVALID);
        }
    }
}
