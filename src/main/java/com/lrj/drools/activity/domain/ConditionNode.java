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

    public ConditionNode() {}

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
}
