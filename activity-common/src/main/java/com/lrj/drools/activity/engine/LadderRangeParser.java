package com.lrj.drools.activity.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.drools.activity.engine.ActivityDrlBuilder.LadderTier;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 解析 {@code redPackageRangeAmount} 里的阶梯分档 JSON。对齐来源 {@code LadderRangeParser}。
 *
 * 预期 JSON：{@code [ {"min":0,"max":100,"reward":5}, {"min":100,"max":null,"reward":12} ]}
 * 区间 [min, max) 左闭右开；max 缺省用哨兵大数。字段名兼容 min/minAmount、max/maxAmount、reward/amount。
 * JSON 空/非法/无有效档 → 返回空 List（调用方回退旧逻辑，不抛异常）。
 */
public final class LadderRangeParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final BigDecimal NO_UPPER_BOUND = new BigDecimal("999999999999");

    private LadderRangeParser() {}

    public static List<LadderTier> parse(String rangeJson) {
        List<LadderTier> tiers = new ArrayList<>();
        if (rangeJson == null || rangeJson.isBlank()) return tiers;
        try {
            JsonNode arr = MAPPER.readTree(rangeJson);
            if (!arr.isArray()) return tiers;
            for (JsonNode node : arr) {
                BigDecimal min = readDecimal(node, BigDecimal.ZERO, "min", "minAmount", "minOrderAmount");
                BigDecimal max = readDecimal(node, NO_UPPER_BOUND, "max", "maxAmount", "maxOrderAmount");
                BigDecimal reward = readDecimal(node, null, "reward", "rewardAmount", "amount");
                if (reward == null) continue; // 无金额的档跳过
                tiers.add(new LadderTier(min, max, reward));
            }
        } catch (Exception e) {
            return new ArrayList<>(); // 非法 JSON → 空，调用方回退
        }
        return tiers;
    }

    private static BigDecimal readDecimal(JsonNode node, BigDecimal defaultVal, String... keys) {
        for (String key : keys) {
            JsonNode v = node.get(key);
            if (v != null && !v.isNull() && v.isValueNode()) {
                try {
                    return new BigDecimal(v.asText().trim());
                } catch (NumberFormatException ignore) {
                    // 试下一个别名
                }
            }
        }
        return defaultVal;
    }
}
