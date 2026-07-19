package com.lrj.drools.activity.domain;

/**
 * 资格条件字段的值类型。对齐来源 {@code FieldValueType}。
 *
 * - NUMBER：数值（DRL 里不加引号，走 numberAttr 访问器）
 * - STRING：字符串（加引号，走 textAttr）
 * - ARRAY：集合字段（如 userTags），配合 contains 系运算符，走 listAttr
 * - ENUM：受控枚举值（加引号，走 textAttr；带候选值白名单，翻译期校验）—— P0-1/P2-20 通用化新增
 * - BOOLEAN：布尔（true/false 不加引号，走 boolAttr）—— 顺带补齐，零成本
 *
 * 访问器由 {@link SchemaField#accessor()} 从本类型派生，不再硬编码 factField（Map fact 通用化）。
 */
public enum FieldValueType {
    NUMBER,
    STRING,
    ARRAY,
    ENUM,
    BOOLEAN
}
