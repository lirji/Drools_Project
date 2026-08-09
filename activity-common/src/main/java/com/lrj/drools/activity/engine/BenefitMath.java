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

    // ================================================================ 一口价（秒杀）

    /**
     * 一口价的减免额：{@code 减免 = 订单金额 − 一口价}。
     *
     * <p><b>与折扣/固定金额的根本区别是结果与原价强相关</b>：同一个「9.9 一口价」活动，
     * 在 100 元的单上减 90.1，在 500 元的单上减 490.1。所以它必须在决策时按当笔订单算，
     * 不能像固定金额那样预先存一个"减多少"。
     *
     * <p><b>订单金额低于一口价时返回 null（不给优惠），而不是返回 0 或负数</b>：
     * <ul>
     *   <li>负数 = 反向加价，是"优惠"里最不该出现的东西；</li>
     *   <li>0 元会以 0 参与 MAX 竞争并可能挤掉别的真能减钱的活动；</li>
     *   <li>而"这单本来就比秒杀价便宜"的正确语义就是<b>这个活动不适用</b>。</li>
     * </ul>
     *
     * @param orderAmount 订单金额；null（上游没传）→ null，与阶梯/折扣「缺驱动字段就不开闸」同一条规矩
     * @param price       一口价（元）。null 或负数 → null：一口价是这个形态的全部信息，缺了它无从算起
     * @return 减免额（2 位小数），或 null 表示不适用
     */
    public static BigDecimal fixedPriceDiscount(BigDecimal orderAmount, BigDecimal price) {
        if (orderAmount == null || price == null) return null;
        if (price.signum() < 0) return null;
        if (orderAmount.signum() <= 0) return null;
        BigDecimal off = orderAmount.subtract(price);
        // 订单比秒杀价还便宜 → 本活动不适用（不是减 0 元）
        if (off.signum() <= 0) return null;
        return off.setScale(MONEY_SCALE, MONEY_ROUNDING);
    }

    // ================================================================ 随机红包

    /**
     * 随机红包金额——**确定性随机**：同一个 {@code seedKey} 永远算出同一个金额。
     *
     * <p><b>为什么不能真随机</b>：决策接口是可被重复调用的（用户刷新购物车、前端重试、
     * 网关重放、对账复算）。真随机意味着同一笔订单每次调用给出不同的价格——
     * 用户刷新一次价格就变，客诉直接成立；而且 golden set 无法断言金额、决策不可重放、
     * 对账时无法回答「当时为什么发了 12 块」。要做真抽奖必须先有发放流水表把结果落库，
     * 当前没有那张表，所以**在决策侧只能给确定性随机**。
     *
     * <p>随机性的来源是 seedKey 的散列，不是时间——因此它对同一上下文可复现，
     * 对不同用户/不同订单又充分打散（SHA-256 的雪崩效应保证相邻 key 的结果不相关）。
     *
     * <p><b>为什么用 SHA-256 而不是 {@code String.hashCode()}</b>：后者虽然在 JDK 规范里
     * 是固定算法（跨 JVM 稳定），但只有 32 位且分布差，相邻字符串容易落进相近桶——
     * 表现是「同一用户在几笔金额接近的订单上总是抽到差不多的钱」，看起来就不像随机。
     *
     * <p>算术在**分**上做，不碰浮点：区间 [min,max] 折算成 [minCents,maxCents]，
     * 取 {@code floorMod(hash, span)} 落点。结果天然是整分，无需再取整。
     *
     * @param min     区间下界（元，含）
     * @param max     区间上界（元，含）
     * @param seedKey 决定性种子。必须包含「活动 + 用户 + 订单」三要素，否则同一用户
     *                在所有订单上都会抽到同一个数（见 {@link #randomSeedKey}）
     * @return 金额（2 位小数），或 null 表示不可计算（区间缺失/非法/种子为空）
     */
    public static BigDecimal randomAmount(BigDecimal min, BigDecimal max, String seedKey) {
        if (min == null || max == null || seedKey == null || seedKey.isBlank()) return null;
        if (min.signum() < 0 || max.signum() < 0) return null;
        if (min.compareTo(max) > 0) return null;

        long minCents = min.movePointRight(MONEY_SCALE).setScale(0, MONEY_ROUNDING).longValueExact();
        long maxCents = max.movePointRight(MONEY_SCALE).setScale(0, MONEY_ROUNDING).longValueExact();
        long span = maxCents - minCents + 1;          // 闭区间，+1 让上界可被抽中
        if (span <= 0) return null;
        if (span == 1) return BigDecimal.valueOf(minCents, MONEY_SCALE);

        long offset = Math.floorMod(hash64(seedKey), span);
        return BigDecimal.valueOf(minCents + offset, MONEY_SCALE);
    }

    /**
     * 组装种子。三要素缺一不可：
     * <ul>
     *   <li><b>活动 + 版本</b>：同一用户同一单命中两个随机活动时要抽出不同的钱；
     *       带版本是为了运营改了区间后重新抽（否则改了配置金额纹丝不动，像是没生效）</li>
     *   <li><b>用户</b>：不同用户必须抽到不同的钱，否则"随机"退化成"全场同一个数"</li>
     *   <li><b>订单标识</b>：同一用户的不同订单应各抽各的</li>
     * </ul>
     *
     * <p><b>订单标识目前是购物车指纹而非订单号</b>——决策入口没有订单号（见
     * {@code SpuDiscountRequest}）。后果是「同一用户、同样的商品与金额」会稳定拿到同一个数，
     * 这正是「刷新不变价」想要的；代价是它无法区分该用户先后下的两笔完全相同的单。
     * 入口若将来补上订单号，把它加进来即可，无需改算法。
     */
    public static String randomSeedKey(String activityId, Integer version, Object userId, Object orderFingerprint) {
        return String.valueOf(activityId) + '|' + version + '|' + userId + '|' + orderFingerprint;
    }

    /** SHA-256 前 8 字节当 64 位散列。跨 JVM / 跨机器稳定，是"可复现"的前提。 */
    private static long hash64(String s) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            long h = 0;
            for (int i = 0; i < 8; i++) h = (h << 8) | (d[i] & 0xFFL);
            return h;
        } catch (java.security.NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 强制实现，走不到这里；真走到了也不能静默换算法——
            // 换算法 = 所有历史金额改变，属于"悄悄改钱"。
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
