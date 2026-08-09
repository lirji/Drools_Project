package com.lrj.drools.activity.domain;

/**
 * 权益形态。<b>决定「redPackageAmount 这个数字是什么意思」</b>。
 *
 * <p>此前只有一种：那个数字就是要减的钱（单位元）。加了折扣类之后同一个字段可能是「折数」，
 * 于是必须有个判别位——否则「打 8 折」会被当成「减 8 元」静默发出去。
 *
 * <p><b>判别位选用既有的 {@code redPackageAmountUnit}</b>，不新增列：该字段本来就一路
 * 从 {@code activity_rule} 搬到候选、快照与 DRL 上下文，只是从来没被任何计算读过。
 * 现在给它一个受控取值域，顺带把这个「装饰字段」变成有意义的字段。
 *
 * <p><b>未知取值一律回落 {@link #AMOUNT}</b>——历史数据里全是 {@code '元'} 或 {@code null}，
 * 回落保证它们的行为一个字节都不变；而未来若有人写进一个拼错的单位，
 * 表现是「按金额发」（旧行为）而不是「按某种猜出来的比例发」（改钱）。
 */
public enum BenefitForm {

    /** 固定/阶梯金额：{@code redPackageAmount} 就是要减的钱（元） */
    AMOUNT,

    /**
     * 打折：{@code redPackageAmount} 是<b>折数</b>，取值 (0,10)。
     * 8 = 八折 = 按原价 80% 收，减免 20%。
     *
     * <p>用「折」而不是百分比，是因为百分比在中文语境里有歧义——「打 20%」既可能是
     * 「收 20%」也可能是「减 20%」，而「8 折」只有一个意思。
     */
    RATIO_ZHE,

    /**
     * 一口价（秒杀）：{@code redPackageAmount} 是<b>卖多少</b>，不是减多少。
     * 减免额 = 订单金额 − 一口价。
     *
     * <p>它与 {@link #AMOUNT}/{@link #RATIO_ZHE} 的根本区别是<b>结果与原价无关</b>——
     * 前两者是「在原价上减」，它是「不管原价多少，就卖这个数」。这也是为什么它不能
     * 用「减免额」表达：同一个活动在 100 元和 500 元的单上要减掉的钱完全不同。
     *
     * <p><b>秒杀必须配合库存扣减</b>，而决策服务连的是只读账号（物理上写不了库），
     * 所以扣减在写平面的 claim 端点上做，决策侧只做「余量 &gt; 0」的**建议性**闸门。
     * 详见 {@code ActivityMarketingService.claimInventory}。
     */
    FIXED_PRICE,

    /**
     * 第 N 件折（第二件半价）：{@code redPackageAmount} 是<b>折数</b>（同 {@link #RATIO_ZHE}），
     * 而「第几件」存在 {@code redPackageRangeAmount} 的 {@code {"nth":2}} 里。
     *
     * <p>它与 {@link #RATIO_ZHE} 的区别是<b>作用对象</b>：折扣型作用于整单金额，
     * 它作用于<b>同款商品里的第 N 件</b>——因此必须有逐行单价才算得出来，
     * 这也是它在决策入口补 {@code lines} 之前做不了的原因。
     */
    NTH_ZHE;

    public static final String UNIT_YUAN = "元";
    public static final String UNIT_ZHE = "折";
    /** 一口价的判别位。用「价」而不是「元」——后者已被"减多少"占用，混用会让同一个数字有两种含义。 */
    public static final String UNIT_PRICE = "价";
    /** 第 N 件折的判别位。与「折」分开：两者数字同为折数，但作用对象一个是整单、一个是第 N 件。 */
    public static final String UNIT_NTH_ZHE = "件折";

    public static BenefitForm of(String unit) {
        if (UNIT_ZHE.equals(unit)) return RATIO_ZHE;
        if (UNIT_PRICE.equals(unit)) return FIXED_PRICE;
        if (UNIT_NTH_ZHE.equals(unit)) return NTH_ZHE;
        return AMOUNT;
    }

    /** 写平面白名单：只有这三个单位是受控的，其余一律拒（防止拼错的单位被静默当成金额） */
    public static boolean isSupportedUnit(String unit) {
        return unit == null || UNIT_YUAN.equals(unit) || UNIT_ZHE.equals(unit)
                || UNIT_PRICE.equals(unit) || UNIT_NTH_ZHE.equals(unit);
    }
}
