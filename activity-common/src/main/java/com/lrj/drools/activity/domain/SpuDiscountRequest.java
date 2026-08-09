package com.lrj.drools.activity.domain;

import java.math.BigDecimal;
import java.util.List;

/**
 * 决策入参：一次优惠查询的订单/用户上下文。
 *
 * <p><b>{@code storeId} 的来历（拍板 D12-4）</b>：此前本 record 没有这个字段，而
 * {@code RuleSchemaRegistry} 的条件白名单里有「店铺」——于是运营在控制台配得出
 * {@code storeId == 1} 的条件、后端也编译得过，但决策时属性袋里根本没有这个键，
 * 访问器返回 null → 正向比较恒 false → 候选被淘汰。表现是**配了 storeId 条件的活动永远不命中**，
 * 且因为 fail-closed 是「静默不发」而不是报错。
 *
 * <p>写侧其实完整建模了店铺（{@code DemoProductEntity.storeId} /
 * {@code ActivitySpuBindingEntity.storeId} / {@code ActivityCreateRequest.SpuBinding} /
 * 前端编辑器的「店铺ID」列），只有决策入参漏了，故补入参而不是删白名单。
 * 语义与同组字段一致 —— 「这一单来自哪个门店」，不是「活动绑在哪个店」。
 *
 * <p><b>兼容</b>：新增字段为纯增量。老调用方走 {@linkplain #SpuDiscountRequest(List, Long, String, List, BigDecimal, Integer)
 * 六参构造}，{@code storeId} 归 null → 属性袋无此键 → 与今天行为完全一致（仍 fail-closed）。
 */
public record SpuDiscountRequest(
        List<Long> spuIdList,
        Long userId,
        String userDistrictId,
        List<String> userTags,
        BigDecimal orderAmount,
        Integer quantity,
        Integer storeId
) {

    /** 兼容旧调用方的六参构造（storeId 缺省为 null）。JSON 反序列化走全参构造，不受影响。 */
    public SpuDiscountRequest(List<Long> spuIdList, Long userId, String userDistrictId,
                              List<String> userTags, BigDecimal orderAmount, Integer quantity) {
        this(spuIdList, userId, userDistrictId, userTags, orderAmount, quantity, null);
    }
}
