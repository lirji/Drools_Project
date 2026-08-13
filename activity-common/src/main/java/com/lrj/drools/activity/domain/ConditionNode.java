package com.lrj.drools.activity.domain;

import java.util.List;

/**
 * 资格条件树节点。对齐来源 {@code engine/builder/ConditionNode}：
 * 一个类同时承载"分组节点"和"叶子节点"两种形态，方便 Jackson 零配置反序列化。
 *
 * - 分组节点：{@code logic} 非空（AND/OR），{@code children} 是子节点
 * - 叶子节点：{@code field} + {@code op} + {@code value}
 *
 * value 的形状由运算符决定：
 * - 标量（eq/gt/contains…）：Number 或 String
 * - 区间（between）：长度为 2 的 List [lo, hi]
 * - 列表（in/notIn/containsAny）：List
 *
 * 这个 POJO 会以 JSON 形式存进 {@code activity_condition.condition_tree_json}，
 * 由 {@code RuleConditionTranslator} 翻译成 Drools LHS 约束（阶段 2）。
 */
public class ConditionNode {

    /** AND / OR；非空即为分组节点。 */
    private String logic;
    private List<ConditionNode> children;

    /** 叶子：字段 key（须命中 {@code RuleSchemaRegistry} 解析出的 schema 白名单）。 */
    private String field;
    /** 叶子：运算符 code（须命中 {@link RuleOperator#fromCode}）。 */
    private String op;
    /** 叶子：条件值。 */
    private Object value;

    /**
     * 这个节点是**谁生成的**。运营手写的条件为 {@code null}；写平面自动合成的标 {@link #SOURCE_DISTRICT}。
     *
     * <p><b>没有它就没法做幂等合成</b>：投放地域是保存时被翻译成一个 {@code userDistrictId IN (...)}
     * 叶子并进条件树的（详见 {@code ActivityMarketingService.mergeDistrictCondition}）。
     * 而编辑器回读的是<b>整份</b>存储树（{@code EditorView.loadForEdit}），
     * 若不标记来源：① 运营会在条件树 UI 里看到一条自己没写过的规则，还能手动改它；
     * ② 下次保存时这条叶子会被当成用户条件再合成一次，叶子逐次翻倍、树深逐次 +1，
     * 而 {@code RuleConditionTranslator.MAX_DEPTH = 5} 是硬闸——堆几次就保存不了了。
     *
     * <p>所以两侧都靠它剥离：后端合成前先剥、前端回读时也剥。
     */
    private String source;

    /** {@link #source} 的取值：由「投放地域」翻译而来的条件节点。 */
    public static final String SOURCE_DISTRICT = "district";

    public ConditionNode() {}

    /**
     * 分组节点判别。前端 {@code shared/types.ts} 的 {@code isGroup} 必须与这里同语义
     * （非空且非空白的 {@code logic}），错开一点就会把叶子当成组。
     *
     * <p>{@code @JsonIgnore} 与 {@link #isDistrictGenerated()} 上那条同理，而且这条是**实测炸过的**：
     * 不加的话 Jackson 按 boolean getter 惯例往 {@code condition_tree_json} 里多写一个 {@code "group"} 键，
     * 而本类没有 {@code setGroup}、也没开 {@code ignoreUnknown} —— 于是
     * {@code readValue(json, ConditionNode.class)} 直接抛 {@code UnrecognizedPropertyException}：
     * <b>自己写出去的 JSON 自己读不回来</b>。今天后端没有回读这份 JSON 的路径，所以它一直是哑的。
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isGroup() {
        return logic != null && !logic.isBlank();
    }

    public String getLogic() { return logic; }
    public void setLogic(String logic) { this.logic = logic; }

    public List<ConditionNode> getChildren() { return children; }
    public void setChildren(List<ConditionNode> children) { this.children = children; }

    public String getField() { return field; }
    public void setField(String field) { this.field = field; }

    public String getOp() { return op; }
    public void setOp(String op) { this.op = op; }

    public Object getValue() { return value; }
    public void setValue(Object value) { this.value = value; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    /**
     * 是不是写平面按投放地域自动合成的那条。
     *
     * <p>{@code @JsonIgnore} 不是可选的：这个类会被序列化进 {@code condition_tree_json}
     * <b>并原样回传给前端</b>。不加的话，Jackson 会按 boolean getter 惯例多写一个
     * {@code "districtGenerated"} 键进存储 JSON，而反序列化时它不是已知属性 → 读回来直接炸。
     * 也就是说：<b>存得进去、读不回来</b>，且只在真正回读某条既有活动时才暴露。
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isDistrictGenerated() {
        return SOURCE_DISTRICT.equals(source);
    }
}
