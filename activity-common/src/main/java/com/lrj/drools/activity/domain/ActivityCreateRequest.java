package com.lrj.drools.activity.domain;

import java.math.BigDecimal;
import java.util.List;

/**
 * 活动创建/编辑请求 —— 前端"报表式表单"的提交体。
 *
 * - {@code activityId} 为空=新建；非空=编辑（旧版本逻辑删除，新版本 version+1）。
 * - {@code requestId} 幂等键：同 requestId 重复提交返回首次结果。
 * - 时间用 epoch 毫秒（前端 {@code new Date(v).getTime()}，避免时区歧义）。
 * - {@code eligibilityConditionTree} 是可视化条件树，服务端翻译成受控 Drools 约束（不接受裸 DRL）。
 * - {@code redPackageRangeAmount} 存阶梯分档 JSON（LADDER 场景）。
 * - {@code redPackageAmountUnit} 是**权益形态判别位**（元 / 折，见 {@link BenefitForm}），
 *   {@code 折} 时 {@code redPackageAmount} 表示折数且 {@code redPackageMaxDiscount} 必填。
 */
public record ActivityCreateRequest(
        String requestId,
        String activityId,
        String activityName,
        String bizLine,
        Integer activityType,
        String activityRule,
        Long activityStartTime,
        Long activityEndTime,
        Integer activityAreaType,
        String districtIds,
        Integer priority,
        Integer inventory,
        Integer redPackageTakeType,
        BigDecimal redPackageAmount,
        String redPackageAmountUnit,
        String redPackageRangeAmount,
        String discountStrategy,
        ConditionNode eligibilityConditionTree,
        List<SpuBinding> spuBindings,
        List<Long> poolRefs,
        List<GiftInput> gifts,
        /** 折扣类的封顶减免额（元）。null = 不封顶——只有金额型才允许不封顶 */
        BigDecimal redPackageMaxDiscount,
        /**
         * 每人限领份数。null / ≤0 = 不限。
         *
         * <p><b>这个字段此前根本不存在</b>：实体上有 {@code user_inventory} 列、候选里有
         * {@code userInventory} 字段、快照还把它一路搬运过去——唯独提交入口没有它，
         * 写入口又硬编码 {@code setUserInventory(0)}，全链路零读取。
         * 也就是说它是条穿过整条流水线、两端都是空的幽灵字段：
         * 运营看不到、填不了，工程上却处处像是支持了限领。
         *
         * <p>现在它由 {@code activity_grant} 发放流水按 {@code (活动, 用户)} 计数执行。
         * 配了它的活动，claim 必须带 {@code userId}，否则一律拒绝——
         * 无从判断是不是同一个人时放行，等于这条限制不存在。
         */
        Integer userInventory,

        /**
         * 活动级币种（发放对账按币种分桶）。null / blank = 兜底 CNY（写入口 {@code saveManage} 归一为大写）。
         *
         * <p>末尾追加分量：前端活动配币种是后续 frontend-plan 的事，本次后端先落地并兜底 CNY，
         * 存量与旧调用方按 null 走默认，不阻塞对账地基。
         */
        String currency,

        /** 当前活动版本到企业权益 SKU 的受控映射；null/空表示继续走 legacy 发放。 */
        List<AwardBindingInput> awardBindings
) {
    public ActivityCreateRequest {
        awardBindings = awardBindings == null ? List.of() : List.copyOf(awardBindings);
    }

    /** 兼容加 awardBindings 之前的 24 参 canonical 签名。 */
    public ActivityCreateRequest(
            String requestId, String activityId, String activityName, String bizLine,
            Integer activityType, String activityRule, Long activityStartTime, Long activityEndTime,
            Integer activityAreaType, String districtIds, Integer priority, Integer inventory,
            Integer redPackageTakeType, BigDecimal redPackageAmount, String redPackageAmountUnit,
            String redPackageRangeAmount, String discountStrategy, ConditionNode eligibilityConditionTree,
            List<SpuBinding> spuBindings, List<Long> poolRefs, List<GiftInput> gifts,
            BigDecimal redPackageMaxDiscount, Integer userInventory, String currency) {
        this(requestId, activityId, activityName, bizLine, activityType, activityRule,
                activityStartTime, activityEndTime, activityAreaType, districtIds, priority, inventory,
                redPackageTakeType, redPackageAmount, redPackageAmountUnit, redPackageRangeAmount,
                discountStrategy, eligibilityConditionTree, spuBindings, poolRefs, gifts,
                redPackageMaxDiscount, userInventory, currency, null);
    }
    /**
     * 兼容 23 参构造（带每人限领、不带币种）——这是加 currency 之前的 canonical 签名。
     *
     * <p>加字段用附加构造而不是改所有调用点：这个 record 被二十多处按位置构造，
     * 逐个补 {@code null} 只会制造一大片与本次「营销发放对账」无关的 diff，
     * 真正在动账的几行会淹没在里面。currency 缺省 = null → 走 CNY 兜底。
     */
    public ActivityCreateRequest(
            String requestId, String activityId, String activityName, String bizLine,
            Integer activityType, String activityRule, Long activityStartTime, Long activityEndTime,
            Integer activityAreaType, String districtIds, Integer priority, Integer inventory,
            Integer redPackageTakeType, BigDecimal redPackageAmount, String redPackageAmountUnit,
            String redPackageRangeAmount, String discountStrategy, ConditionNode eligibilityConditionTree,
            List<SpuBinding> spuBindings, List<Long> poolRefs, List<GiftInput> gifts,
            BigDecimal redPackageMaxDiscount, Integer userInventory) {
        this(requestId, activityId, activityName, bizLine, activityType, activityRule,
                activityStartTime, activityEndTime, activityAreaType, districtIds, priority, inventory,
                redPackageTakeType, redPackageAmount, redPackageAmountUnit, redPackageRangeAmount,
                discountStrategy, eligibilityConditionTree, spuBindings, poolRefs, gifts,
                redPackageMaxDiscount, userInventory, null);
    }

    /** 兼容 22 参构造（带封顶、不带每人限领、不带币种）。 */
    public ActivityCreateRequest(
            String requestId, String activityId, String activityName, String bizLine,
            Integer activityType, String activityRule, Long activityStartTime, Long activityEndTime,
            Integer activityAreaType, String districtIds, Integer priority, Integer inventory,
            Integer redPackageTakeType, BigDecimal redPackageAmount, String redPackageAmountUnit,
            String redPackageRangeAmount, String discountStrategy, ConditionNode eligibilityConditionTree,
            List<SpuBinding> spuBindings, List<Long> poolRefs, List<GiftInput> gifts,
            BigDecimal redPackageMaxDiscount) {
        this(requestId, activityId, activityName, bizLine, activityType, activityRule,
                activityStartTime, activityEndTime, activityAreaType, districtIds, priority, inventory,
                redPackageTakeType, redPackageAmount, redPackageAmountUnit, redPackageRangeAmount,
                discountStrategy, eligibilityConditionTree, spuBindings, poolRefs, gifts,
                redPackageMaxDiscount, null, null);
    }

    /**
     * 兼容旧的 21 参构造（不带封顶）。
     *
     * <p>加字段用附加构造而不是改所有调用点，是因为这个 record 被十几处测试按位置构造——
     * 让它们逐个补一个 {@code null} 只会制造一大片与本次改动无关的 diff，
     * 真正的改动会淹没在里面，review 时看不出「哪几行是在动钱」。
     */
    public ActivityCreateRequest(
            String requestId, String activityId, String activityName, String bizLine,
            Integer activityType, String activityRule, Long activityStartTime, Long activityEndTime,
            Integer activityAreaType, String districtIds, Integer priority, Integer inventory,
            Integer redPackageTakeType, BigDecimal redPackageAmount, String redPackageAmountUnit,
            String redPackageRangeAmount, String discountStrategy, ConditionNode eligibilityConditionTree,
            List<SpuBinding> spuBindings, List<Long> poolRefs, List<GiftInput> gifts) {
        this(requestId, activityId, activityName, bizLine, activityType, activityRule,
                activityStartTime, activityEndTime, activityAreaType, districtIds, priority, inventory,
                redPackageTakeType, redPackageAmount, redPackageAmountUnit, redPackageRangeAmount,
                discountStrategy, eligibilityConditionTree, spuBindings, poolRefs, gifts, null, null, null);
    }

    /** 手动商品绑定行。 */
    public record SpuBinding(Integer storeId, Long spuId) {}

    /** 买赠赠品行。 */
    public record GiftInput(String batchId, String giftName, String giftType,
                            Integer giftNum, BigDecimal absoluteAmount, String rightType) {}
}
