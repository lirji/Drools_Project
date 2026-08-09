package com.lrj.drools.activity.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;

/**
 * 解析随机红包的区间：{@code redPackageRangeAmount} 里的 {@code {"min":5,"max":20}}。
 *
 * <p><b>为什么必须是 JSON 对象、不能是数组</b>——这个列是双用途的：LADDER 存分档数组、
 * 随机红包存区间。{@code ActivityQueryService.ladderDefs()} 会把<b>任何</b>能被
 * {@link LadderRangeParser} 解析出档位的候选当成阶梯活动。若随机区间也写成数组
 * （哪怕是 {@code [{"min":5,"max":20}]}），两条解析路径就会同时认领同一份数据。
 * 现在的分工是：
 * <ul>
 *   <li>数组 → 阶梯（{@code LadderRangeParser}；无 reward 的元素被跳过）</li>
 *   <li>对象 → 随机区间（本类；{@code LadderRangeParser} 见到非数组直接返回空）</li>
 * </ul>
 * 两者互斥由「JSON 顶层类型」保证，不靠调用方自觉。写平面的校验也按这条来。
 *
 * <p><b>解析不出来一律返回 null</b>，调用方必须当成「本活动不给优惠」而不是「减 0 元」——
 * 与 {@link BenefitMath#ratioDiscount} 同一条规矩：0 元会以 0 参与 MAX 竞争并挤掉别的活动。
 */
public final class RandomRangeParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RandomRangeParser() {}

    /** 随机区间。{@code min}/{@code max} 均为元，闭区间 [min, max]。 */
    public record Range(BigDecimal min, BigDecimal max) {}

    /**
     * @param rangeJson {@code {"min":5,"max":20}}，字段名兼容 min/minAmount、max/maxAmount
     * @return 区间；JSON 空 / 非对象 / 缺字段 / min&gt;max / 负数 → null（不可计算）
     */
    public static Range parse(String rangeJson) {
        if (rangeJson == null || rangeJson.isBlank()) return null;
        try {
            JsonNode node = MAPPER.readTree(rangeJson);
            // 数组归阶梯管，这里直接放行给 LadderRangeParser，避免两条路径抢同一份数据
            if (node == null || !node.isObject()) return null;

            BigDecimal min = readDecimal(node, "min", "minAmount");
            BigDecimal max = readDecimal(node, "max", "maxAmount");
            if (min == null || max == null) return null;
            // 负区间是配置错误。按 fail-closed 处理成「不给优惠」，而不是截断到 0 后照发。
            if (min.signum() < 0 || max.signum() < 0) return null;
            if (min.compareTo(max) > 0) return null;
            return new Range(min, max);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 读「第 N 件」的 N。与随机区间共用 JSON 对象形态，靠 {@code redPackageAmountUnit}
     * 区分用途（'元'+takeType=2 → 随机区间；'件折' → 第 N 件），不靠猜键名。
     *
     * @return N，或 null（缺失/非法/&lt;2）。1 等于全场打折——那是另一个形态，配成 1 更像配错，故拒。
     */
    public static Integer parseNth(String rangeJson) {
        if (rangeJson == null || rangeJson.isBlank()) return null;
        try {
            JsonNode node = MAPPER.readTree(rangeJson);
            if (node == null || !node.isObject()) return null;
            JsonNode v = node.get("nth");
            if (v == null || !v.canConvertToInt()) return null;
            int n = v.asInt();
            return n >= 2 ? n : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static BigDecimal readDecimal(JsonNode node, String... names) {
        for (String n : names) {
            JsonNode v = node.get(n);
            if (v != null && !v.isNull() && v.isNumber()) return v.decimalValue();
            if (v != null && v.isTextual()) {
                try { return new BigDecimal(v.asText().trim()); } catch (NumberFormatException ignored) { /* 下一个别名 */ }
            }
        }
        return null;
    }
}
