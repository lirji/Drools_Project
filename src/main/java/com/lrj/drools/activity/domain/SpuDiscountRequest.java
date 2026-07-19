package com.lrj.drools.activity.domain;

import java.math.BigDecimal;
import java.util.List;

/**
 * 商品优惠查询请求（前端"验证视图"用）。对齐来源 {@code SpuDiscountBo} + 规则上下文字段。
 *
 * {@code orderAmount} / {@code quantity} 供 LADDER / 资格条件用；{@code userTags} / {@code userDistrictId}
 * 供资格条件用；不填走默认（空标签、null 地域）。
 */
public record SpuDiscountRequest(
        List<Long> spuIdList,
        Long userId,
        String userDistrictId,
        List<String> userTags,
        BigDecimal orderAmount,
        Integer quantity
) {
}
