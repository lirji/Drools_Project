package com.lrj.drools.activity.engine;

import com.lrj.drools.activity.domain.ConditionNode;
import com.lrj.drools.activity.domain.FieldValueType;
import com.lrj.drools.activity.domain.RuleLogic;
import com.lrj.drools.activity.domain.RuleOperator;
import com.lrj.drools.activity.domain.SchemaField;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 资格条件树 → Drools LHS 约束表达式。P0-1 通用化后是一个**纯函数**：输入条件树 + 已解析的 schema
 * （由 {@code RuleSchemaRegistry} 按 (tenant,bizLine) 解析），输出方法左值形态的 Drools 约束。
 *
 * 产出的字符串落在 {@code ActivityRuleContext( <此处> )} 里，运行时用
 * {@code not ActivityRuleContext(<约束>)} 判定"上下文不满足资格" → 淘汰候选（fail-closed）。
 *
 * <p><b>emit 形态（spike 14/14 实证）</b>：字段访问走 Map fact 的方法左值访问器
 * {@code numberAttr("key") / textAttr("key") / listAttr("key") / boolAttr("key")}，key 是 schema 的
 * **规范 key**（不是用户输入原样，P2-19）且过 {@link #KEY_PATTERN} 白名单（P2-21）。
 *
 * <p><b>安全边界</b>：字段必须命中 schema、运算符必须在该字段 allowedOps 内、ENUM 值必须在候选集内、
 * value 形状必须与运算符匹配，否则抛 {@link IllegalArgumentException} —— 运营无法注入任意 DRL。
 *
 * <p><b>否定运算符 fail-closed（P0-2）</b>：NE / NOT_IN / NOT_CONTAINS emit 都加 {@code (acc != null && …)}，
 * 缺字段短路成 false → 候选被淘汰，杜绝静默超发。
 */
@Component
public class RuleConditionTranslator {

    private static final int MAX_DEPTH = 5;

    /** 规范 key 白名单：所有拼进 DRL 访问器的标识符必须匹配（P2-21 防注入）。 */
    private static final Pattern KEY_PATTERN = Pattern.compile("^[A-Za-z0-9_]+$");

    /**
     * 翻译整棵条件树。空树（null）返回 null，调用方据此**不生成资格淘汰规则**（= 恒通过）。
     *
     * @param schema 已解析的字段 schema（key → {@link SchemaField}），由 {@code RuleSchemaRegistry} 提供
     */
    public String translate(ConditionNode root, Map<String, SchemaField> schema) {
        if (root == null) return null;
        if (schema == null) throw new IllegalArgumentException("schema 未解析（tenant/bizLine 无字段定义）");
        return translate(root, schema, 0);
    }

    private String translate(ConditionNode node, Map<String, SchemaField> schema, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("条件树层级过深（>" + MAX_DEPTH + "）");
        }
        if (node.isGroup()) {
            RuleLogic logic = RuleLogic.fromCode(node.getLogic());
            List<ConditionNode> children = node.getChildren();
            if (children == null || children.isEmpty()) {
                throw new IllegalArgumentException("分组节点 " + logic.code() + " 缺少子条件");
            }
            StringBuilder sb = new StringBuilder("(");
            for (int i = 0; i < children.size(); i++) {
                if (i > 0) sb.append(logic.separator());
                sb.append(translate(children.get(i), schema, depth + 1));
            }
            return sb.append(")").toString();
        }
        return translateLeaf(node, schema);
    }

    private String translateLeaf(ConditionNode leaf, Map<String, SchemaField> schema) {
        SchemaField field = schema.get(leaf.getField());
        if (field == null) {
            throw new IllegalArgumentException("不支持的条件字段: " + leaf.getField());
        }
        RuleOperator op = RuleOperator.fromCode(leaf.getOp());
        if (!field.allows(op)) {
            throw new IllegalArgumentException("字段[" + field.key() + "]不支持运算符[" + op.code() + "]");
        }
        // 方法左值访问器 + 规范 key（emit schema key，非用户输入；过白名单正则）
        String acc = accessor(field);
        Object value = leaf.getValue();

        return switch (op) {
            case EQ -> "(" + acc + " == " + scalar(value, field) + ")";
            // 否定运算符加存在性护栏：缺字段短路成 false → 候选淘汰（fail-closed，防静默超发）
            case NE -> "(" + acc + " != null && " + acc + " != " + scalar(value, field) + ")";
            case GT -> "(" + acc + " > " + scalar(value, field) + ")";
            case GE -> "(" + acc + " >= " + scalar(value, field) + ")";
            case LT -> "(" + acc + " < " + scalar(value, field) + ")";
            case LE -> "(" + acc + " <= " + scalar(value, field) + ")";
            case BETWEEN -> {
                List<?> range = asList(value, 2, "between 需要 [下界, 上界] 两个元素");
                String lo = scalar(range.get(0), field);
                String hi = scalar(range.get(1), field);
                yield "(" + acc + " >= " + lo + " && " + acc + " <= " + hi + ")";
            }
            case IN -> "(" + acc + " in (" + joinScalars(value, field) + "))";
            case NOT_IN -> "(" + acc + " != null && " + acc + " not in (" + joinScalars(value, field) + "))";
            case CONTAINS -> "(" + acc + " contains " + scalar(value, field) + ")";
            case NOT_CONTAINS -> "(" + acc + " != null && " + acc + " not contains " + scalar(value, field) + ")";
            case CONTAINS_ANY -> {
                List<?> vals = asList(value, -1, "containsAny 需要一个非空列表");
                StringBuilder sb = new StringBuilder("(");
                for (int i = 0; i < vals.size(); i++) {
                    if (i > 0) sb.append(" || ");
                    sb.append(acc).append(" contains ").append(scalar(vals.get(i), field));
                }
                yield sb.append(")").toString();
            }
        };
    }

    /** 由 valueType 派生的访问器 + 规范 key，如 {@code numberAttr("orderAmount")}。key 过白名单正则。 */
    private String accessor(SchemaField field) {
        String key = field.key();
        if (key == null || !KEY_PATTERN.matcher(key).matches()) {
            throw new IllegalArgumentException("非法字段 key（须 ^[A-Za-z0-9_]+$）: " + key);
        }
        return field.accessor() + "(\"" + key + "\")";
    }

    /**
     * 单值格式化：NUMBER 走 BigDecimal 规范化（不加引号）；BOOLEAN → true/false（不加引号）；
     * STRING/ENUM/ARRAY 元素加引号并转义。ENUM 额外校验值在候选集内。
     */
    private String scalar(Object v, SchemaField field) {
        if (v == null) throw new IllegalArgumentException("条件值不能为空");
        FieldValueType type = field.valueType();
        if (type == FieldValueType.NUMBER) {
            try {
                return new BigDecimal(String.valueOf(v).trim()).toPlainString();
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("数值字段的条件值非法: " + v);
            }
        }
        if (type == FieldValueType.BOOLEAN) {
            String s = String.valueOf(v).trim().toLowerCase();
            if (!s.equals("true") && !s.equals("false")) {
                throw new IllegalArgumentException("布尔字段的条件值须为 true/false: " + v);
            }
            return s;
        }
        String raw = String.valueOf(v);
        if (type == FieldValueType.ENUM && !field.enumValues().isEmpty() && !field.enumValues().contains(raw)) {
            throw new IllegalArgumentException("字段[" + field.key() + "]的值不在枚举候选内: " + raw);
        }
        String s = raw.replace("\\", "\\\\").replace("\"", "\\\"");
        return "\"" + s + "\"";
    }

    private String joinScalars(Object value, SchemaField field) {
        List<?> vals = asList(value, -1, "in/notIn 需要一个非空列表");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < vals.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(scalar(vals.get(i), field));
        }
        return sb.toString();
    }

    /** 校验 value 是 List 且长度符合要求（expectedSize<0 表示只要非空）。 */
    private List<?> asList(Object value, int expectedSize, String msg) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalArgumentException(msg);
        }
        if (expectedSize > 0 && list.size() != expectedSize) {
            throw new IllegalArgumentException(msg);
        }
        return list;
    }
}
