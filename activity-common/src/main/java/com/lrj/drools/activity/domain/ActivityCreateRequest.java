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
        BigDecimal redPackageMaxDiscount
) {
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
                discountStrategy, eligibilityConditionTree, spuBindings, poolRefs, gifts, null);
    }

    /** 手动商品绑定行。 */
    public record SpuBinding(Integer storeId, Long spuId) {}

    /** 买赠赠品行。 */
    public record GiftInput(String batchId, String giftName, String giftType,
                            Integer giftNum, BigDecimal absoluteAmount, String rightType) {}
}
