package com.lrj.drools.activity;

import com.lrj.drools.activity.domain.ActivityCandidate;
import com.lrj.drools.activity.domain.OfferSpec;
import com.lrj.drools.activity.snapshot.DecisionSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R7 的**结构守卫**：把「配置只有一条装配路径」钉成 CI 可查的不变量，而不是又一条注释。
 *
 * <p>守的是这条事故链：配置与计算态焊在同一个可变 {@link ActivityCandidate} 上 → 快照必须不可变 →
 * 只好造影子类 → 「行 → 候选」变成三份手写扇出，其中只有一份被编译器守着 →
 * {@code scopedSpuIds} 与 {@code redPackageMaxDiscount} 各漏过一次 →
 * 同一张券在走库与走快照两条路上发不同的钱，不报错、不回退、日志干净。
 *
 * <p>行为面由 {@code SnapshotParityTest} / {@code DecisionGoldenSetTest} 守；本类只守结构，
 * 因为下一次漂移会先表现为「有人给候选加了个配置 setter」，那时行为测试还是绿的。
 */
@DisplayName("R7 结构守卫：候选的配置只能来自 OfferSpec")
class OfferSpecArchGuardTest {

    /**
     * DRL 的 LHS 与 RHS 按名字绑定这些访问器，改名/去掉不会报错，只会让规则<b>静默失配</b>
     * （{@code buildGiftDrl} 读 {@code eligible} / {@code gifts}，
     * {@code buildEligibilityDrl} 与 {@code buildLadderDrl} 读 {@code activityId} / {@code eligible}）。
     *
     * <p><b>刻意写死字面量</b>：引用常量的话，谁把常量和访问器一起改名，测试会跟着一起绿。
     */
    private static final Set<String> CONFIG_ACCESSORS = Set.of(
            "getActivityId", "getActivityName", "getActivityType", "getBizLine",
            "getActivityStatus", "getActivityAreaType", "getDistrictIds",
            "getInventory", "getUserInventory", "getVersion", "getPriority",
            "getRedPackageTakeType", "getRedPackageAmount", "getRedPackageAmountUnit",
            "getRedPackageMaxDiscount", "getRedPackageRangeAmount", "getGifts");

    /** 本轮计算态——这些**必须**有 setter，规则要改它们。 */
    private static final Set<String> STATE_SETTERS = Set.of(
            "setEligible", "setRejectReason", "setComputedAmount",
            "setAmountComputed", "setLadderApplied", "setScopedSpuIds");

    @Test
    @DisplayName("候选上没有任何配置 setter——配置只能整体来自 OfferSpec")
    void candidateExposesNoConfigSetter() {
        List<String> setters = Arrays.stream(ActivityCandidate.class.getMethods())
                .filter(m -> m.getDeclaringClass() == ActivityCandidate.class)
                .map(Method::getName)
                .filter(n -> n.startsWith("set"))
                .distinct()
                .collect(Collectors.toList());

        assertThat(setters)
                .as("多出来的 setter 意味着又开了一条不受编译器保护的配置装配路径；"
                        + "配置字段一律进 OfferSpec，规则要改的计算态才留 setter")
                .containsExactlyInAnyOrderElementsOf(STATE_SETTERS);
    }

    @Test
    @DisplayName("19 个 DRL 访问器原名原签名还在（改名 = 规则静默失配）")
    void drlAccessorsSurvive() {
        for (String name : CONFIG_ACCESSORS) {
            assertThat(Arrays.stream(ActivityCandidate.class.getMethods()).map(Method::getName))
                    .as("DRL 按名字绑定 %s，去掉它规则不会报错、只会不再命中", name)
                    .contains(name);
        }
        assertThat(Arrays.stream(ActivityCandidate.class.getMethods()).map(Method::getName))
                .contains("isEligible", "isAmountComputed", "isLadderApplied", "reject", "addGift");
    }

    @Test
    @DisplayName("候选的每个构造器都以 OfferSpec 开头——造不出「配置来自别处」的候选")
    void everyCandidateConstructorTakesSpecFirst() {
        Constructor<?>[] ctors = ActivityCandidate.class.getConstructors();
        assertThat(ctors).isNotEmpty();
        for (Constructor<?> ctor : ctors) {
            assertThat(ctor.getParameterTypes()[0])
                    .as("新增的构造器 %s 绕开了 OfferSpec", ctor)
                    .isEqualTo(OfferSpec.class);
        }
    }

    @Test
    @DisplayName("快照直接持有 OfferSpec，影子类 CandidateTemplate 不许回来")
    void snapshotHoldsSpecDirectly() throws Exception {
        assertThat(DecisionSnapshot.class.getDeclaredField("candidates").getGenericType().getTypeName())
                .as("快照必须直接存配置；再造一个「与候选同形」的模板类，就是第三份手写扇出回来了")
                .contains(OfferSpec.class.getName());

        assertThat(Arrays.stream(DecisionSnapshot.class.getDeclaredClasses()).map(Class::getSimpleName))
                .doesNotContain("CandidateTemplate");
    }
}
