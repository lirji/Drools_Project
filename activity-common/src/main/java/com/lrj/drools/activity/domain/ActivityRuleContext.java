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

    /** 数值访问器。键不存在 / 值为 null → null（正向比较缺字段天然 false = fail-closed）。 */
    public BigDecimal numberAttr(String k) {
        Object v = attrs.get(k);
        if (v == null) return null;
        return (v instanceof BigDecimal b) ? b : new BigDecimal(v.toString());
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
