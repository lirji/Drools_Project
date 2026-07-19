package com.lrj.drools.activity.domain;

import java.math.BigDecimal;
import java.util.List;

/**
 * 单条权益产出。P1-10 通用化前瞻结构：为了将来一个场景内**多种权益并存**（现金 + 折扣 + 赠品）
 * 留出表达位，取代把命中结果写死成 {@code hitAmount + gifts} 两个固定字段。
 *
 * - activityId    产出该权益的活动
 * - benefitType   权益类型（DISCOUNT / CASH / GIFT …；MVP 单场景单类型）
 * - amount        金额型权益的额度（非金额型传 {@code BigDecimal.ZERO}）
 * - gifts         赠品型权益的物料（非赠品传空 List）
 *
 * <p><b>MVP 边界（务必勿当已解）</b>：当前只支持**单场景单 benefit-type**——同类折扣合并
 * （MAX/MUTEX/STACK/PRIORITY）跑在 {@code ActivityCandidate.computedAmount} 上、与本结构无关，
 * 不受影响；但**跨异构权益合并**（现金 + 赠品 + 折扣同场命中如何合并）**MVP 明确不支持**，
 * 需要时补 Java 编排层，不在规则引擎里做。
 */
public record BenefitOutcome(String activityId, String benefitType, BigDecimal amount, List<GiftResult> gifts) {

    public static BenefitOutcome discount(String activityId, BigDecimal amount) {
        return new BenefitOutcome(activityId, "DISCOUNT", amount == null ? BigDecimal.ZERO : amount, List.of());
    }
}
