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
 *
 * <p><b>{@code lines}（订单行）的来历</b>：「第二件半价」这类玩法此前做不了，卡点就在这里——
 * 入参只有 {@code orderAmount}（整单金额）与 {@code quantity}（总件数），<b>没有逐行单价</b>，
 * 于是算不出「第二件」是哪一件、值多少钱。整单金额除以件数得到的是均价，
 * 拿均价当第二件的价去打折，在购物车里混着贵重与便宜商品时会算错钱——而且是静默算错。
 *
 * <p>它同样是**纯增量**：不传 {@code lines} 的老调用方行为一个字节不变，
 * 只是配了「第 N 件折」形态的活动对他们不适用（{@code BenefitMath.nthItemDiscount} 返回 null →
 * fail-closed 不给优惠），而不是拿均价瞎算。
 */
public record SpuDiscountRequest(
        List<Long> spuIdList,
        Long userId,
        String userDistrictId,
        List<String> userTags,
        BigDecimal orderAmount,
        Integer quantity,
        Integer storeId,
        /** 订单行。为空 = 调用方没有逐行信息，此时「第 N 件折」类活动不适用（fail-closed）。 */
        List<OrderLine> lines
) {

    /**
     * 一行订单：同一 SPU 的 n 件同价商品。
     *
     * <p><b>为什么按行而不是按件</b>：按件展开会让 100 件的单产生 100 个对象，
     * 而「第 N 件」的计算只需要 (单价, 件数) 两个数——按行是这个玩法所需的最小信息量。
     */
    public record OrderLine(Long spuId, BigDecimal unitPrice, Integer quantity) {}

    /** 兼容旧调用方的六参构造（storeId 缺省为 null）。JSON 反序列化走全参构造，不受影响。 */
    public SpuDiscountRequest(List<Long> spuIdList, Long userId, String userDistrictId,
                              List<String> userTags, BigDecimal orderAmount, Integer quantity) {
        this(spuIdList, userId, userDistrictId, userTags, orderAmount, quantity, null, null);
    }

    /** 兼容七参构造（补 storeId 那一版）。JSON 反序列化走全参构造，不受影响。 */
    public SpuDiscountRequest(List<Long> spuIdList, Long userId, String userDistrictId,
                              List<String> userTags, BigDecimal orderAmount, Integer quantity,
                              Integer storeId) {
        this(spuIdList, userId, userDistrictId, userTags, orderAmount, quantity, storeId, null);
    }
}
