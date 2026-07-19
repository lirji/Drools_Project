package com.lrj.drools.activity.engine;

import com.lrj.drools.activity.domain.FieldValueType;
import com.lrj.drools.activity.domain.RuleOperator;
import com.lrj.drools.activity.domain.SchemaField;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 资格条件字段 schema 注册表。P0-1 通用化命门：取代原 {@code RuleField} 硬编码枚举，
 * 把"哪些字段可用、什么类型、允许哪些运算符"变成**数据驱动、按 (tenant, bizLine) 解析**的白名单。
 *
 * <p><b>Track A（当前）</b>：单租户 + 全 bizLine 共用一份内置默认 schema，语义与原 6 字段白名单**完全等价**，
 * 保证 typed→Map 改造后行为不变、回归绿。
 *
 * <p><b>Track B 接缝（stub 成单租户常量）</b>：{@link #DEFAULT_TENANT} 是占位符；接入 auth-platform 后
 * tenant 从 token.owner 派生，{@link #resolve} 改为按 (tenant, bizLine) 查库/缓存的租户级字段元数据，
 * 运营在控制台维护字段。**仍白名单、仍 fail-closed、运营永不写 DRL。**
 *
 * <p>字段 key 即 Map fact 的属性 key，也是 DRL 访问器 {@code numberAttr("key")} 里的规范 key
 * （翻译期过 {@code ^[A-Za-z0-9_]+$}，见 {@code RuleConditionTranslator}）。
 */
@Component
public class RuleSchemaRegistry {

    /** 单租户/无上下文占位常量（Track B 由请求的租户派生真实 tenant）。 */
    public static final String DEFAULT_TENANT = "__single__";

    private static final Map<String, SchemaField> DEFAULT_SCHEMA = buildDefaultSchema();

    /** 按 (tenant, bizLine) 的字段 schema 覆盖表。为空时所有租户共享 {@link #DEFAULT_SCHEMA}（demo 默认）。 */
    private final Map<String, Map<String, SchemaField>> overrides = new ConcurrentHashMap<>();

    /**
     * 解析 (tenant, bizLine) → 字段 schema（key → {@link SchemaField}）。
     * 先查 (tenant,bizLine) 覆盖 → 再查租户级 (tenant,*) 覆盖 → 否则回落共享默认 schema。
     * 这样字段白名单可按租户定制（{@link #register}），未定制则与原行为等价。
     */
    public Map<String, SchemaField> resolve(String tenant, String bizLine) {
        Map<String, SchemaField> byBiz = overrides.get(key(tenant, bizLine));
        if (byBiz != null) return byBiz;
        Map<String, SchemaField> byTenant = overrides.get(key(tenant, null));
        if (byTenant != null) return byTenant;
        return DEFAULT_SCHEMA;
    }

    /** 解析出的字段集合（前端 field-dict 下拉据此渲染，随租户而变）。 */
    public Collection<SchemaField> resolveFields(String tenant, String bizLine) {
        return resolve(tenant, bizLine).values();
    }

    /**
     * (tenant, bizLine) 当前 schema 的**确定性版本号**（P1-9 pin/变更检测用）。
     * 由字段集的 {@code key:valueType} 排序拼接后哈希——字段增删或类型变更即版本变，artifact 据此判断是否需重建。
     */
    public String schemaVersion(String tenant, String bizLine) {
        String canonical = resolve(tenant, bizLine).values().stream()
                .map(f -> f.key() + ":" + f.valueType().name())
                .sorted()
                .collect(java.util.stream.Collectors.joining(","));
        return "sv" + Integer.toHexString(canonical.hashCode());
    }

    /**
     * P1-9 硬失效判定：某 pin 的字段集在**当前** (tenant,bizLine) schema 下是否已「失配」——
     * 有引用字段被删、或类型变了。true = 引用它的 artifact 须重建/退役（不可静默继续用旧 pin 跑）。
     */
    public boolean fieldsBrokenAgainstCurrent(String tenant, String bizLine, Map<String, String> pinnedFieldTypes) {
        Map<String, SchemaField> live = resolve(tenant, bizLine);
        for (Map.Entry<String, String> e : pinnedFieldTypes.entrySet()) {
            SchemaField cur = live.get(e.getKey());
            if (cur == null) return true;                              // 字段被删
            if (!cur.valueType().name().equals(e.getValue())) return true; // 类型变了
        }
        return false;
    }

    /** 注册某 (tenant, bizLine) 的字段 schema（bizLine 传 null 表示该租户全业务线兜底）。运营控制台维护字段的落点。 */
    public void register(String tenant, String bizLine, List<SchemaField> fields) {
        LinkedHashMap<String, SchemaField> m = new LinkedHashMap<>();
        for (SchemaField f : fields) m.put(f.key(), f);
        overrides.put(key(tenant, bizLine), Collections.unmodifiableMap(m));
    }

    /** 默认 schema 的字段集合（无租户上下文时的下拉来源）。 */
    public Collection<SchemaField> defaultFields() {
        return DEFAULT_SCHEMA.values();
    }

    private static String key(String tenant, String bizLine) {
        return (tenant == null ? DEFAULT_TENANT : tenant) + "|" + (bizLine == null ? "*" : bizLine);
    }

    /** 内置默认 schema：等价原 {@code RuleField} 的 6 字段白名单（电商红包场景）。 */
    private static Map<String, SchemaField> buildDefaultSchema() {
        List<SchemaField> fields = List.of(
                new SchemaField("orderAmount", "订单金额", FieldValueType.NUMBER,
                        EnumSet.of(RuleOperator.GT, RuleOperator.GE, RuleOperator.LT, RuleOperator.LE,
                                RuleOperator.EQ, RuleOperator.BETWEEN), List.of()),
                new SchemaField("quantity", "购买数量", FieldValueType.NUMBER,
                        EnumSet.of(RuleOperator.GT, RuleOperator.GE, RuleOperator.LT, RuleOperator.LE,
                                RuleOperator.EQ, RuleOperator.BETWEEN), List.of()),
                new SchemaField("userDistrictId", "用户地域", FieldValueType.STRING,
                        EnumSet.of(RuleOperator.EQ, RuleOperator.IN, RuleOperator.NOT_IN), List.of()),
                new SchemaField("userTags", "用户标签", FieldValueType.ARRAY,
                        EnumSet.of(RuleOperator.CONTAINS, RuleOperator.NOT_CONTAINS, RuleOperator.CONTAINS_ANY), List.of()),
                new SchemaField("spuId", "商品 SPU", FieldValueType.NUMBER,
                        EnumSet.of(RuleOperator.EQ, RuleOperator.IN), List.of()),
                new SchemaField("storeId", "店铺", FieldValueType.NUMBER,
                        EnumSet.of(RuleOperator.EQ, RuleOperator.IN), List.of()));
        LinkedHashMap<String, SchemaField> m = new LinkedHashMap<>();
        for (SchemaField f : fields) m.put(f.key(), f);
        return Collections.unmodifiableMap(m);
    }
}
