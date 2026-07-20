package com.lrj.drools.activity.domain;

import java.util.List;
import java.util.Set;

/**
 * 单个资格条件字段的 schema 定义。P0-1 通用化：取代原 {@code RuleField} 硬编码枚举，
 * 由 {@code RuleSchemaRegistry} 按 (tenant, bizLine) 装配成数据驱动的白名单。
 *
 * - key         条件树 / DRL 访问器里用的**规范字段 key**（须 {@code ^[A-Za-z0-9_]+$}，翻译期强制）
 * - label       中文名（前端下拉展示）
 * - valueType   值类型，决定访问器方法 + DRL 里加不加引号
 * - allowedOps  允许的运算符（不在白名单内翻译期直接报错）
 * - enumValues  仅 ENUM 用：候选值白名单（非空时翻译期校验条件值在其中），其余类型传空 List
 *
 * 安全边界：运营只能从注册的字段 + 允许的运算符 + （ENUM）候选值里拼条件，**不能提交任意 DRL**。
 */
public record SchemaField(String key, String label, FieldValueType valueType,
                          Set<RuleOperator> allowedOps, List<String> enumValues) {

    public boolean allows(RuleOperator op) {
        return allowedOps.contains(op);
    }

    /**
     * Map fact 上的强类型访问器方法名，由 valueType 派生（不再硬编码 factField）。
     * 对应 {@code ActivityRuleContext} 的 numberAttr/textAttr/listAttr/boolAttr。
     */
    public String accessor() {
        return switch (valueType) {
            case NUMBER -> "numberAttr";
            case STRING, ENUM -> "textAttr";
            case ARRAY -> "listAttr";
            case BOOLEAN -> "boolAttr";
        };
    }
}
