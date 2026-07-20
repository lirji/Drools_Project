package com.lrj.drools.activity;

import com.lrj.drools.activity.domain.ConditionNode;
import com.lrj.drools.activity.domain.FieldValueType;
import com.lrj.drools.activity.domain.RuleOperator;
import com.lrj.drools.activity.domain.SchemaField;
import com.lrj.drools.activity.engine.RuleConditionTranslator;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-1 / P2-19 / P2-20 / P0-2 纯单元测试：条件树 → Drools 约束的 emit 形态。
 *
 * 锁定：方法左值访问器（numberAttr/textAttr/listAttr/boolAttr）+ 规范 key + ENUM/BOOLEAN 支持 +
 * 否定运算符存在性护栏 + 白名单/枚举/运算符校验。形态与 spike 14/14 一致。
 * 纯函数无 Spring，快。
 */
class RuleConditionTranslatorTest {

    private final RuleConditionTranslator t = new RuleConditionTranslator();

    private static Map<String, SchemaField> schema() {
        Map<String, SchemaField> m = new LinkedHashMap<>();
        put(m, new SchemaField("amount", "金额", FieldValueType.NUMBER,
                EnumSet.of(RuleOperator.GE, RuleOperator.GT, RuleOperator.BETWEEN, RuleOperator.EQ), List.of()));
        put(m, new SchemaField("city", "城市", FieldValueType.STRING,
                EnumSet.of(RuleOperator.EQ, RuleOperator.NE, RuleOperator.IN, RuleOperator.NOT_IN), List.of()));
        put(m, new SchemaField("tags", "标签", FieldValueType.ARRAY,
                EnumSet.of(RuleOperator.CONTAINS, RuleOperator.NOT_CONTAINS, RuleOperator.CONTAINS_ANY), List.of()));
        put(m, new SchemaField("level", "等级", FieldValueType.ENUM,
                EnumSet.of(RuleOperator.EQ, RuleOperator.IN, RuleOperator.NOT_IN), List.of("gold", "silver")));
        put(m, new SchemaField("isNew", "新客", FieldValueType.BOOLEAN,
                EnumSet.of(RuleOperator.EQ), List.of()));
        return m;
    }

    private static void put(Map<String, SchemaField> m, SchemaField f) { m.put(f.key(), f); }

    private static ConditionNode leaf(String field, String op, Object value) {
        ConditionNode n = new ConditionNode();
        n.setField(field); n.setOp(op); n.setValue(value);
        return n;
    }

    @Test
    void numberEmitsMethodLvalue() {
        assertEquals("(numberAttr(\"amount\") >= 100)", t.translate(leaf("amount", "ge", 100), schema()));
    }

    @Test
    void betweenEmitsBothBounds() {
        assertEquals("(numberAttr(\"amount\") >= 10 && numberAttr(\"amount\") <= 20)",
                t.translate(leaf("amount", "between", List.of(10, 20)), schema()));
    }

    @Test
    void enumEqQuotedViaTextAttr() {
        assertEquals("(textAttr(\"level\") == \"gold\")", t.translate(leaf("level", "eq", "gold"), schema()));
    }

    @Test
    void enumValueOutOfCandidatesRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> t.translate(leaf("level", "eq", "platinum"), schema()));
    }

    @Test
    void booleanEmitsUnquotedViaBoolAttr() {
        assertEquals("(boolAttr(\"isNew\") == true)", t.translate(leaf("isNew", "eq", "true"), schema()));
    }

    // ---- P0-2 否定运算符存在性护栏 ----

    @Test
    void notInHasNullGuard() {
        assertEquals("(textAttr(\"city\") != null && textAttr(\"city\") not in (\"BJ\", \"SH\"))",
                t.translate(leaf("city", "notIn", List.of("BJ", "SH")), schema()));
    }

    @Test
    void neHasNullGuard() {
        assertEquals("(textAttr(\"city\") != null && textAttr(\"city\") != \"BJ\")",
                t.translate(leaf("city", "ne", "BJ"), schema()));
    }

    @Test
    void notContainsHasNullGuard() {
        assertEquals("(listAttr(\"tags\") != null && listAttr(\"tags\") not contains \"black\")",
                t.translate(leaf("tags", "notContains", "black"), schema()));
    }

    @Test
    void containsAnyExpandsToOr() {
        assertEquals("(listAttr(\"tags\") contains \"a\" || listAttr(\"tags\") contains \"b\")",
                t.translate(leaf("tags", "containsAny", List.of("a", "b")), schema()));
    }

    // ---- 白名单 / 运算符 / 空树 ----

    @Test
    void unknownFieldRejected() {
        assertThrows(IllegalArgumentException.class, () -> t.translate(leaf("ghost", "eq", 1), schema()));
    }

    @Test
    void disallowedOperatorRejected() {
        // amount 不允许 contains
        assertThrows(IllegalArgumentException.class, () -> t.translate(leaf("amount", "contains", 1), schema()));
    }

    @Test
    void nullTreeReturnsNull() {
        assertNull(t.translate(null, schema()));
    }

    @Test
    void groupWrapsChildren() {
        ConditionNode group = new ConditionNode();
        group.setLogic("and");
        group.setChildren(List.of(leaf("amount", "ge", 100), leaf("city", "eq", "BJ")));
        String out = t.translate(group, schema());
        assertTrue(out.startsWith("(") && out.endsWith(")"), out);
        assertTrue(out.contains("numberAttr(\"amount\") >= 100"), out);
        assertTrue(out.contains("textAttr(\"city\") == \"BJ\""), out);
    }
}
