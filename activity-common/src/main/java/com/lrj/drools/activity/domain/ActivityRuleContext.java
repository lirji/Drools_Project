package com.lrj.drools.activity.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 规则评估的根输入 fact。P0-1 通用化：由**固定电商 typed POJO** 改造成 **Map 支撑 + 强类型访问器**，
 * 字段不再写死在 Java 里，而是由 {@code RuleSchemaRegistry} 的 (tenant,bizLine) schema 决定
 * （改造选型 (a)，spike 14/14 实证方法左值 {@code numberAttr("x")>=…} 在 Drools 8.44.2 可编译+执行）。
 *
 * 资格条件树翻译出的 Drools 约束落在 {@code ActivityRuleContext( numberAttr("orderAmount") >= 100 )} 上，
 * 用 {@code not ActivityRuleContext(<约束>)} 判定"上下文不满足资格" → 淘汰候选（fail-closed）。
 *
 * <p><b>缺字段归一化（P0-2 / P1-9）</b>：{@link #putAttr} 跳过 null，故"键不存在"与"值为 null"统一表现为
 * 访问器返回 {@code null}；配合翻译器对否定运算符加的存在性护栏，缺字段一律 fail-closed（不静默超发）。
 */
public class ActivityRuleContext {

    private RuleScene scene;
    private String bizLine;
    private Instant evalTime = Instant.now();

    /** 通用属性袋：key = schema 字段 key，value = 原始值（BigDecimal / String / Collection / Boolean / 可转换）。 */
    private final Map<String, Object> attrs = new HashMap<>();

    private List<ActivityCandidate> candidates = new ArrayList<>();

    public ActivityRuleContext() {}

    // ---------------------------------------------------------------- 强类型访问器（DRL 方法左值，照 spike）

    /**
     * 数值访问器。键不存在 / 值为 null / <b>值不是个数</b> → null（正向比较缺字段天然 false = fail-closed）。
     *
     * <p>「不是个数就返回 null」这一条是后加的护栏：此前直接 {@code new BigDecimal(v.toString())}，
     * 一旦某个 key 的值变成集合（如 spuId 从「第一件」改成「整个列表」），这里会抛
     * {@code NumberFormatException} 并**打断整次决策**——一条脏配置或一次 schema 漂移就能让
     * 整个请求 500，而不是让那一个条件 fail-closed。判据必须与本类其它访问器一致：
     * 拿不到可用的值就是 null，由调用方按缺字段处理。
     */
    public BigDecimal numberAttr(String k) {
        Object v = attrs.get(k);
        if (v == null) return null;
        if (v instanceof BigDecimal b) return b;
        if (v instanceof Number n) return new BigDecimal(n.toString());
        try {
            return new BigDecimal(v.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 文本 / 枚举访问器。 */
    public String textAttr(String k) {
        Object v = attrs.get(k);
        return v == null ? null : v.toString();
    }

    /** 集合访问器（raw Collection，配合 contains 系）。 */
    @SuppressWarnings("rawtypes")
    public Collection listAttr(String k) {
        Object v = attrs.get(k);
        return v == null ? null : (Collection) v;
    }

    /** 布尔访问器。 */
    public Boolean boolAttr(String k) {
        Object v = attrs.get(k);
        if (v == null) return null;
        return (v instanceof Boolean b) ? b : Boolean.valueOf(v.toString());
    }

    // ---------------------------------------------------------------- 装配

    /** 写入属性；**value 为 null 时跳过**（缺字段归一成"键不存在"→访问器 null→fail-closed）。 */
    public void putAttr(String k, Object v) {
        if (k != null && v != null) attrs.put(k, v);
    }

    public Map<String, Object> getAttrs() { return attrs; }

    public void addCandidate(ActivityCandidate candidate) {
        if (candidate != null) this.candidates.add(candidate);
    }

    // ---------------------------------------------------------------- Java 侧便捷读取（委托给属性袋，非 DRL 用）

    /** 便捷读订单金额（{@code ActivityQueryService} 的阶梯闸门用）。 */
    public BigDecimal getOrderAmount() { return numberAttr("orderAmount"); }

    /**
     * 本次请求涉及的全部 SPU。用于判断「活动的作用域是否覆盖整单」——
     * 覆盖时 {@code orderAmount} 本身就是该活动的合法基数，不覆盖时必须靠订单行分摊。
     *
     * @return 请求 SPU 集合；键缺失时返回空集（不是 null，调用方按「无从判断」处理）
     */
    @SuppressWarnings("unchecked")
    public java.util.Set<Long> requestedSpuIds() {
        Object v = attrs.get("spuId");
        if (!(v instanceof Collection<?> col) || col.isEmpty()) return java.util.Set.of();
        java.util.Set<Long> out = new java.util.LinkedHashSet<>();
        for (Object o : col) {
            if (o instanceof Long l) out.add(l);
            else if (o instanceof Number n) out.add(n.longValue());
            else if (o != null) {
                try { out.add(Long.valueOf(o.toString())); } catch (NumberFormatException ignore) { /* 非数值 SPU 忽略 */ }
            }
        }
        return out;
    }

    /** 订单行。为空 = 调用方没有逐行信息（此时作用域是真子集的活动一律不适用，绝不拿整单顶替）。 */
    @SuppressWarnings("unchecked")
    public List<SpuDiscountRequest.OrderLine> orderLines() {
        Object v = attrs.get("orderLines");
        if (!(v instanceof List<?> list) || list.isEmpty()) return List.of();
        List<SpuDiscountRequest.OrderLine> out = new ArrayList<>(list.size());
        for (Object o : list) {
            if (o instanceof SpuDiscountRequest.OrderLine l) out.add(l);
        }
        return out;
    }

    public RuleScene getScene() { return scene; }
    public void setScene(RuleScene scene) { this.scene = scene; }

    public String getBizLine() { return bizLine; }
    public void setBizLine(String bizLine) { this.bizLine = bizLine; }

    public Instant getEvalTime() { return evalTime; }
    public void setEvalTime(Instant evalTime) { this.evalTime = evalTime; }

    public List<ActivityCandidate> getCandidates() { return candidates; }
    public void setCandidates(List<ActivityCandidate> candidates) {
        this.candidates = candidates == null ? new ArrayList<>() : candidates;
    }
}
