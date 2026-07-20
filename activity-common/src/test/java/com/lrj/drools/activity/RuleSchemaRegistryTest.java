package com.lrj.drools.activity;

import com.lrj.drools.activity.domain.FieldValueType;
import com.lrj.drools.activity.domain.RuleOperator;
import com.lrj.drools.activity.domain.SchemaField;
import com.lrj.drools.activity.engine.RuleSchemaRegistry;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * per-(tenant,bizLine) 字段 schema 覆盖（ISSUE-04 机制）：register 后 resolveFields 按维度解析，
 * 未覆盖回落共享默认。field-dict/create 共用 {@code resolve(tenant,bizLine)}，维度一致。
 */
class RuleSchemaRegistryTest {

    private SchemaField num(String key) {
        return new SchemaField(key, key, FieldValueType.NUMBER, EnumSet.of(RuleOperator.GE), List.of());
    }

    @Test
    void perTenantBizLineOverride() {
        RuleSchemaRegistry reg = new RuleSchemaRegistry();
        // 默认共享 schema = 6 字段
        assertEquals(6, reg.resolveFields("acme", null).size());

        // 给 acme/travel 注册自定义字段
        reg.register("acme", "travel", List.of(num("completedTrips")));

        // travel 维度 → 只有自定义字段（与 create 用同一 resolve 维度）
        assertEquals(1, reg.resolveFields("acme", "travel").size());
        assertTrue(reg.resolveFields("acme", "travel").stream().anyMatch(f -> f.key().equals("completedTrips")));

        // 未覆盖的维度回落默认：别的 bizLine、别的租户、租户级
        assertEquals(6, reg.resolveFields("acme", "mall").size(), "同租户别 bizLine 回落默认");
        assertEquals(6, reg.resolveFields("beta", "travel").size(), "别租户回落默认");
        assertEquals(6, reg.resolveFields("acme", null).size(), "租户级(无 bizLine)回落默认");
    }
}
