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
        List<GiftInput> gifts
) {
    /** 手动商品绑定行。 */
    public record SpuBinding(Integer storeId, Long spuId) {}

    /** 买赠赠品行。 */
    public record GiftInput(String batchId, String giftName, String giftType,
                            Integer giftNum, BigDecimal absoluteAmount, String rightType) {}
}
