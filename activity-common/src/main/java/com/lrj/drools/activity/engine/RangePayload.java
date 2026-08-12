package com.lrj.drools.activity.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.drools.activity.domain.BenefitForm;
import com.lrj.drools.activity.domain.DistributionMode;
import com.lrj.drools.activity.engine.ActivityDrlBuilder.LadderTier;

import java.math.BigDecimal;
import java.util.List;

/**
 * {@code redPackageRangeAmount} 这一列的**唯一解析出口**（R9）。
 *
 * <p>这一列是<b>三用途</b>的：阶梯分档数组 / 随机金额区间 {@code {"min","max"}} / 第 N 件折 {@code {"nth":N}}。
 * 「哪种内容算数」这条约定此前在 Java 侧被写了三遍——{@code ActivityQueryService.ladderDefs}
 * 无条件按阶梯解析、{@link BenefitEvaluator} 按形态各自解析、
 * {@code ActivityMarketingService.validateRangeColumn} 又按形态分叉一次。三份约定各自演化的后果是
 * <b>写侧接受的配置读侧算不出金额</b>（活动以「不适用」姿态上线，而运营看到的是「已上线」），
 * 反过来读侧认识的形态写侧一律判成脏数据。现在三处共同调用 {@link #parse}。
 *
 * <p><b>判别规则原样照搬既有实现</b>，一个分支都没有重新设计：
 * <ol>
 *   <li><b>顶层 JSON 类型</b>先把数组与非数组切成不相交两半：数组 → 阶梯（{@link LadderRangeParser}
 *       见到非数组直接返回空档位，所以非数组永远解析不出 {@link Ladder}）；</li>
 *   <li>非数组再由 <b>{@code redPackageAmountUnit} + {@code redPackageTakeType}</b>
 *       （即 {@link #expectedKind}）切成随机区间 / 第 N 件 / 阶梯。</li>
 * </ol>
 *
 * <p><b>注意第 1 步不看形态</b>：一个单位配成「件折」但内容是阶梯数组的活动，
 * 这里返回的是 {@link Ladder}——与今天 {@code ladderDefs} 的行为一致（它本来就不看形态）。
 * 调用方要的载荷与实际载荷不匹配时，各自按自己的既有规矩处理：读侧 fail-closed 当算不出金额，
 * 写侧按 {@link #expectedKind} 报对应的错。**判「期望哪种载荷」的权威只有 {@link #expectedKind} 一处。**
 *
 * <p><b>刻意不在 {@code DecisionDataLoader.flatten} 与 {@code DecisionSnapshot.materialize} 里预解析</b>：
 * 那会把解析失败的发现时机从「每次决策、按候选 fail-closed 并打
 * {@code metrics.reject(benefit, bad-random-range)}」挪到「快照后台构建时」——告警链会断，
 * 同时在 {@code SnapshotParityTest} 守的等价面上新开一个分歧口（坑 16 的同款失败形状）。
 */
public sealed interface RangePayload {

    /** 这一列此刻承载的是哪种载荷。判别位是「顶层 JSON 类型 + 单位 + 发放方式」。 */
    enum Kind {
        /** 阶梯分档数组 */
        LADDER,
        /** 随机金额区间 {@code {"min","max"}} */
        RANDOM,
        /** 第 N 件折 {@code {"nth":N}} */
        NTH
    }

    /** 阶梯分档。{@code tiers} 非空——空档位视为 {@link Invalid}，与「无有效档位」是同一件事。 */
    record Ladder(List<LadderTier> tiers) implements RangePayload {}

    /** 随机金额区间，闭区间 [min, max]，两端均已校验非负且 min ≤ max。 */
    record Random(BigDecimal min, BigDecimal max) implements RangePayload {}

    /** 第 N 件折的 N（≥2）。 */
    record Nth(int n) implements RangePayload {}

    /** 这一列没配（null / 空白）。与 {@link Invalid} 区分：那是配了但解不出。 */
    record None() implements RangePayload {}

    /**
     * 配了内容但解析不出任何载荷（非法 JSON / 缺字段 / 越界 / 空档位）。
     *
     * <p><b>刻意不带「本想解析成哪种」</b>：那会与 {@link #expectedKind} 形成两个说法，
     * 而数组分支的实际尝试（阶梯）与调用方的期望（可能是第 N 件）本来就可以不一致。
     * 报错文案一律按 {@link #expectedKind} 选。
     */
    record Invalid() implements RangePayload {}

    None NONE = new None();
    Invalid INVALID = new Invalid();

    /**
     * 按判别位解析这一列。**不抛异常**：任何解析失败都是 {@link Invalid}，
     * 由调用方决定是 fail-closed（读侧）还是报错（写侧）。
     *
     * @param form     {@code redPackageAmountUnit} 解出的形态（{@link BenefitForm#of}）
     * @param takeType {@code redPackageTakeType}；只在 {@link BenefitForm#AMOUNT} 下区分固定/随机
     * @param json     {@code redPackageRangeAmount} 原文
     */
    static RangePayload parse(BenefitForm form, Integer takeType, String json) {
        if (json == null || json.isBlank()) return NONE;

        // 第 1 刀：顶层 JSON 类型。数组归阶梯管，且不看形态——ladderDefs 今天就是这么做的。
        if (isJsonArray(json)) {
            List<LadderTier> tiers = LadderRangeParser.parse(json);
            return tiers.isEmpty() ? INVALID : new Ladder(tiers);
        }

        // 第 2 刀：单位 + 发放方式。非数组（含非法 JSON）走这里，与两个子解析器
        // 「见到非对象/解不开就返回 null」的既有行为逐分支一致。
        return switch (expectedKind(form, takeType)) {
            case NTH -> {
                Integer n = RandomRangeParser.parseNth(json);
                yield n == null ? INVALID : new Nth(n);
            }
            case RANDOM -> {
                RandomRangeParser.Range r = RandomRangeParser.parse(json);
                yield r == null ? INVALID : new Random(r.min(), r.max());
            }
            // 非数组永远解析不出档位（LadderRangeParser 见到非数组直接返回空），必然是 Invalid。
            case LADDER -> INVALID;
        };
    }

    /**
     * 这份配置<b>期望</b>这一列是哪种载荷——只看判别位，不看列的内容。
     *
     * <p>写侧用它选报错文案、读侧用它对照实际载荷。它与 {@link #parse} 的第 2 刀共用同一段判断，
     * 于是「什么形态该配什么内容」在全仓库只有这一处定义。
     */
    static Kind expectedKind(BenefitForm form, Integer takeType) {
        if (form == BenefitForm.NTH_ZHE) return Kind.NTH;
        if (form == BenefitForm.AMOUNT && isRandom(takeType)) return Kind.RANDOM;
        return Kind.LADDER;
    }

    /**
     * 与 {@code BenefitEvaluator.distributionOf} 同一条规矩：只有恰好等于随机码才是随机，
     * 未知 code / null 一律按固定金额——脏数据的表现是「按旧行为发」而不是「按猜出来的方式发」。
     */
    private static boolean isRandom(Integer takeType) {
        return takeType != null && DistributionMode.RANDOM_AMOUNT.code() == takeType;
    }

    /** 非法 JSON 一律当成「不是数组」交给第 2 刀，好让形态各自的报错文案仍然生效。 */
    private static boolean isJsonArray(String json) {
        try {
            JsonNode node = RangeJson.MAPPER.readTree(json);
            return node != null && node.isArray();
        } catch (Exception e) {
            return false;
        }
    }
}

/** 只为把 {@code ObjectMapper} 藏起来——接口里的字段一律是 public，那不该成为对外契约。 */
final class RangeJson {
    static final ObjectMapper MAPPER = new ObjectMapper();

    private RangeJson() {}
}
