package com.lrj.drools.activity.engine;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 权益金额的**纯数学**。
 *
 * <p><b>为什么单独一个类</b>：折扣要在两条路径上算——纯 Java 的 {@link BenefitEvaluator}，
 * 以及生成的 DRL（{@code discount-compute-ratio} 规则的 RHS 直接调这里的静态方法）。
 * 如果两边各写一遍取整逻辑，它们迟早会漂移，而漂移的表现是「同一张券在两条路上少发/多发几分钱」——
 * 金标集能抓到，但代价是先在生产上多发过。<b>让两条路调同一个函数，等价性就不靠测试保证、靠构造保证。</b>
 *
 * <p>DRL 里通过 {@code import com.lrj.drools.activity.engine.BenefitMath;} 引用（RHS 是编译成 Java 的）。
 */
public final class BenefitMath {

    /** 钱一律 2 位小数 */
    public static final int MONEY_SCALE = 2;

    /**
     * 减免额的取整方式：<b>向下</b>。
     *
     * <p>这是一笔往外发的钱，四舍五入会在半数情况下多发。单笔多发几厘无所谓，
     * 但这是**系统性偏向**，量大之后是真金白银，而且「为什么账对不上」查起来极贵。
     * 向下取整让误差恒定偏向不多发——与本项目其它地方的 fail-closed 取向一致。
     */
    public static final RoundingMode MONEY_ROUNDING = RoundingMode.DOWN;

    private static final BigDecimal TEN = BigDecimal.valueOf(10);

    private BenefitMath() {}

    /**
     * 按折数算减免额：{@code 减免 = 订单金额 × (10 − 折数) / 10}，向下取整到分，再按封顶截断。
     *
     * <p><b>返回 null 表示「算不出来」，调用方必须当成「本活动不给优惠」而不是「减 0 元」</b>——
     * 两者在合并阶段的表现不同：前者不该参与 MAX 竞争，后者会以 0 元参与并可能挤掉别的活动。
     *
     * @param orderAmount 订单金额。为 null（上游没传）时返回 null——闸门不开，与阶梯的
     *                    「缺驱动字段就不落档」同一条规矩。<b>绝不能退回把折数当元发</b>。
     * @param zhe         折数，必须落在 (0,10)。越界（含 0、10、负数）返回 null：
     *                    10 折等于不打折、0 折等于白送，都不像是运营的本意，更像配错了，一律不算。
     * @param cap         封顶减免额（元）。<b>必填</b>——见下。
     * @return 减免额（2 位小数），或 null 表示不可计算
     */
    public static BigDecimal ratioDiscount(BigDecimal orderAmount, BigDecimal zhe, BigDecimal cap) {
        if (orderAmount == null || zhe == null) return null;
        if (zhe.signum() <= 0 || zhe.compareTo(TEN) >= 0) return null;
        if (orderAmount.signum() <= 0) return null;
        // 折扣**必须**封顶：写平面已强制要求，这里再挡一次是因为写平面只管新写入——
        // 直接写库、历史数据、或将来某条绕过校验的写入路径，都会让读路径拿到 cap=null。
        // 把「没有封顶」解释成「不封顶」是 fail-open：越是数据有问题的时候发得越多。
        if (cap == null) return null;

        BigDecimal off = orderAmount
                .multiply(TEN.subtract(zhe))
                .divide(TEN, MONEY_SCALE, MONEY_ROUNDING);

        // 封顶一律生效。**不能**写成 `cap.signum() > 0 && ...`——那样 cap=0 会被当成「没配封顶」
        // 而把全额发出去，是个标准的 fail-open：配置里最保守的那个值反而产生最激进的结果。
        // 负值按 0 处理（负减免 = 反向加价）。
        if (off.compareTo(cap) > 0) {
            return cap.max(BigDecimal.ZERO).setScale(MONEY_SCALE, MONEY_ROUNDING);
        }
        return off;
    }
}
