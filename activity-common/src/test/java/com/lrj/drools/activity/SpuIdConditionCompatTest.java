package com.lrj.drools.activity;

import com.lrj.drools.activity.domain.ActivityRuleContext;
import com.lrj.drools.activity.domain.ConditionNode;
import com.lrj.drools.activity.domain.FieldValueType;
import com.lrj.drools.activity.domain.SchemaField;
import com.lrj.drools.activity.engine.ConditionTreeEvaluator;
import com.lrj.drools.activity.engine.RuleConditionTranslator;
import com.lrj.drools.activity.engine.RuleSchemaRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>{@code spuId} 从「购物车第一件」改成「整个 SPU 列表」之后，存量条件仍要能用。</b>
 *
 * <p>旧映射是 {@code req.spuIdList().get(0)}——「商品 SPU = X」实际判的是
 * 「购物车里排在第一位的商品是 X」：同样两件商品换个加购顺序，同一个活动的资格结论就不一样。
 * 而运营配这个条件时想说的从来都是「买了 X」。
 *
 * <p>字段类型因此从 NUMBER 改成 ARRAY。库里的存量条件树写的仍是 {@code eq / in / notIn}，
 * 运营也不该被要求重配一遍，所以求值器与翻译器都要把标量算子映射成集合语义。
 *
 * <p><b>本类同时把「放宽了多少」写死</b>：单 SPU 请求下新旧完全等价；
 * 多 SPU 请求下「第一件是 X」→「包含 X」是一次<b>有意的语义放宽</b>，方向是更容易命中。
 * 它之所以可接受，是因为同一批改动里权益作用域把「命中后发多少」也收窄到了活动自己的商品上——
 * 更容易命中 + 每次发得更少，合起来不是放大敞口。这个判断需要有人签字，所以写在测试里而不是注释里。
 */
@DisplayName("spuId 条件：存量 eq/in 映射成集合语义")
class SpuIdConditionCompatTest {

    private final ConditionTreeEvaluator evaluator = new ConditionTreeEvaluator();
    private final RuleSchemaRegistry registry = new RuleSchemaRegistry();
    private final RuleConditionTranslator translator = new RuleConditionTranslator();

    private Map<String, SchemaField> schema() {
        return registry.resolve(RuleSchemaRegistry.DEFAULT_TENANT, null);
    }

    @Test
    @DisplayName("spuId 现在是 ARRAY，且仍允许存量的 eq/in/notIn")
    void spuIdIsArrayButKeepsLegacyOperators() {
        SchemaField spuId = schema().get("spuId");
        assertEquals(FieldValueType.ARRAY, spuId.valueType(),
                "购物车里的商品是一组，不是一个标量");
        assertTrue(spuId.allows(com.lrj.drools.activity.domain.RuleOperator.EQ),
                "存量条件树用的是 eq，不允许就等于要求运营把历史活动全部重配一遍");
        assertTrue(spuId.allows(com.lrj.drools.activity.domain.RuleOperator.CONTAINS),
                "新配置应当能用语义正确的 contains");
        assertEquals(6, schema().size(), "字段数不变，只是 spuId 的类型与算子集合变了");
    }

    @Test
    @DisplayName("存量 eq 读作 contains：包含即命中，与商品在车里的顺序无关")
    void legacyEqReadsAsContains() {
        ConditionNode cond = leaf("spuId", "eq", 990011);

        assertTrue(evaluator.matches(cond, ctx(List.of(990011L)), schema()),
                "单商品：新旧完全等价");
        assertTrue(evaluator.matches(cond, ctx(List.of(990011L, 990012L)), schema()),
                "目标商品排在第一位");
        assertTrue(evaluator.matches(cond, ctx(List.of(990012L, 990011L)), schema()),
                "**放宽点**：目标商品排在第二位也命中。旧行为在这里是 false —— "
                        + "同样两件商品换个加购顺序结论就相反，那不是运营想表达的意思");
        assertFalse(evaluator.matches(cond, ctx(List.of(990012L)), schema()),
                "不含目标商品仍要淘汰");
        assertFalse(evaluator.matches(cond, missingSpu(), schema()),
                "没有 SPU 信息时 fail-closed");
    }

    @Test
    @DisplayName("存量 in / notIn 映射成 containsAny / 都不含")
    void legacyInAndNotIn() {
        ConditionNode in = leaf("spuId", "in", List.of(990011, 990013));
        assertTrue(evaluator.matches(in, ctx(List.of(990012L, 990013L)), schema()), "含其一即命中");
        assertFalse(evaluator.matches(in, ctx(List.of(990012L)), schema()), "一个都不含则淘汰");

        ConditionNode notIn = leaf("spuId", "notIn", List.of(990011));
        assertTrue(evaluator.matches(notIn, ctx(List.of(990012L)), schema()), "不含被排除的商品 → 通过");
        assertFalse(evaluator.matches(notIn, ctx(List.of(990011L, 990012L)), schema()),
                "含被排除的商品 → 淘汰");
        assertFalse(evaluator.matches(notIn, missingSpu(), schema()),
                "没有 SPU 信息时否定算子必须 fail-closed，而不是「没有这个字段所以不在里面，算通过」");
    }

    @Test
    @DisplayName("空购物车在属性映射层就被归一成「没有这个字段」")
    void emptyCartIsNormalisedToMissingAtMappingLayer() {
        // fail-closed 的落点在**属性映射**这一层，不在求值器里。
        // 求值器对 ARRAY 的空集合按「真实信息」处理是对的——「这个用户没有任何标签」
        // 与「不知道这个用户有什么标签」是两件事，前者足以判定 notContains 成立。
        // 而「空购物车」属于后者，所以 requestAttributes 把空列表映射成 null（键不存在）。
        // 这条断言把这个分工钉住：改了任何一边，缺 SPU 的请求都可能被静默放行。
        var attrs = com.lrj.drools.activity.service.DecisionEligibilityService.requestAttributes(
                new com.lrj.drools.activity.domain.SpuDiscountRequest(
                        List.of(), 1L, null, null, null, null));
        assertEquals(null, attrs.get("spuId"), "空 spuIdList 必须映射成 null，而不是空列表");
        assertEquals(null, attrs.get("randomSeedSpu"), "随机种子的 SPU 段同样归一成 null");
    }

    @Test
    @DisplayName("条件值是 JSON 数字（Integer）而属性袋里是 Long —— 仍要能比上")
    void numericValueMatchesLongElements() {
        // 条件树从 JSON 反序列化出来时，990011 是 Integer；属性袋里的 spuIdList 是 List<Long>。
        // 这是本次改动最容易翻车的类型细节：集合比对按字符串做，两者才对得上。
        ConditionNode cond = leaf("spuId", "eq", Integer.valueOf(990011));
        assertTrue(evaluator.matches(cond, ctx(List.of(990011L)), schema()),
                "Integer 条件值 vs Long 属性元素必须能匹配，否则所有存量 spuId 条件会静默恒假");
    }

    @Test
    @DisplayName("翻译器 emit 的 DRL 与求值器结论一一对应")
    void translatorEmitsMatchingCollectionForm() {
        String eq = translator.translate(leaf("spuId", "eq", 990011), schema());
        assertTrue(eq.contains("contains"),
                "ARRAY 字段上的 eq 必须 emit contains；emit 成 == 会让控制台预览与线上求值结论相反。实际: " + eq);
        assertTrue(eq.contains("listAttr(\"spuId\")"),
                "访问器要跟着类型走（listAttr 而不是 numberAttr）。实际: " + eq);

        String notIn = translator.translate(leaf("spuId", "notIn", List.of(990011)), schema());
        assertTrue(notIn.contains("not contains") && notIn.contains("!= null"),
                "否定算子要带存在性护栏（fail-closed）。实际: " + notIn);
    }

    // ---- helpers ----

    private static ActivityRuleContext ctx(List<Long> spuIds) {
        ActivityRuleContext c = new ActivityRuleContext();
        c.putAttr("spuId", spuIds);
        return c;
    }

    /** 没有 SPU 信息的上下文——与生产里「空 spuIdList → 属性映射成 null」等价。 */
    private static ActivityRuleContext missingSpu() {
        return new ActivityRuleContext();
    }

    private static ConditionNode leaf(String field, String op, Object value) {
        ConditionNode n = new ConditionNode();
        n.setField(field);
        n.setOp(op);
        n.setValue(value);
        return n;
    }
}
