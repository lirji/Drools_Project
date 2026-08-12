package com.lrj.drools.activity;

import com.lrj.drools.activity.domain.ActivityRuleContext;
import com.lrj.drools.activity.domain.ConditionNode;
import com.lrj.drools.activity.domain.RuleLogic;
import com.lrj.drools.activity.domain.SchemaField;
import com.lrj.drools.activity.engine.ConditionTreeEvaluator;
import com.lrj.drools.activity.engine.RuleConditionTranslator;
import com.lrj.drools.activity.engine.RuleSchemaRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>资格求值器的两道护栏</b>：脏 logic 不许打断整次请求，且不许被误记成「用户不满足门槛」。
 *
 * <p>改造前 {@code eval} 直调 {@link RuleLogic#fromCode}，未知 code 抛 {@code IllegalArgumentException}，
 * 而决策链路从 controller 到求值器一路无 catch —— <b>一条脏 logic 把整次请求打成 500</b>，
 * 同一次请求里其它完全正常的活动跟着一起没了。判据必须与
 * {@code ActivityRuleContext.numberAttr} 那道「拿不到可用值就返回 null」的护栏一致：
 * 读路径拿不到可判定的结论就淘汰<b>这一个</b>候选。
 *
 * <p>而「淘汰」还不够 —— 必须与正常的门槛淘汰<b>分开计数</b>：前者是故障、要有人去修数据，
 * 后者是每天都在发生的正常业务。所以求值器给的是三态而不是 boolean。
 *
 * <p>另一半是<b>写平面必须继续抛</b>：脏 code 在创建期就该被拒，
 * 把它放行到库里只会把问题推迟到每一次决策。
 */
@DisplayName("资格求值：脏 logic fail-closed，且与正常淘汰分开")
class ConditionTreeGuardTest {

    private final ConditionTreeEvaluator evaluator = new ConditionTreeEvaluator();
    private final RuleSchemaRegistry registry = new RuleSchemaRegistry();
    private final RuleConditionTranslator translator = new RuleConditionTranslator();

    private Map<String, SchemaField> schema() {
        return registry.resolve(RuleSchemaRegistry.DEFAULT_TENANT, null);
    }

    @Test
    @DisplayName("脏 logic → UNDECIDABLE，而不是抛异常打断整次请求")
    void dirtyLogicIsUndecidableNotAnException() {
        ConditionNode dirty = group("XOR", leaf("orderAmount", "ge", 100));

        assertEquals(ConditionTreeEvaluator.Verdict.UNDECIDABLE,
                evaluator.evaluate(dirty, ctxWithAmount("500"), schema()),
                "读不懂的 logic 只能淘汰这一个候选，绝不能把整条决策链路打断");
        assertFalse(evaluator.matches(dirty, ctxWithAmount("500"), schema()),
                "布尔出口按 fail-closed 归入 false —— 宁可不发，不可超发");
    }

    @Test
    @DisplayName("脏 logic 藏在子组里同样不可判定：不会被外层的短路悄悄跳过")
    void dirtyLogicNestedStillUndecidable() {
        ConditionNode nested = group("AND",
                leaf("orderAmount", "ge", 100),
                group("MAYBE", leaf("orderAmount", "ge", 1)));

        assertEquals(ConditionTreeEvaluator.Verdict.UNDECIDABLE,
                evaluator.evaluate(nested, ctxWithAmount("500"), schema()),
                "子树读不懂 = 整棵树的结论不可信，不能只按读得懂的那半棵下结论");
    }

    @Test
    @DisplayName("正常 logic 的三态结论与既有 boolean 语义逐字节一致")
    void healthyTreesKeepTheirVerdicts() {
        ConditionNode and = group("AND", leaf("orderAmount", "ge", 100), leaf("quantity", "ge", 2));
        ConditionNode or = group("or", leaf("orderAmount", "ge", 9999), leaf("quantity", "ge", 2));

        assertEquals(ConditionTreeEvaluator.Verdict.PASS, evaluator.evaluate(and, ctxWithAmount("500"), schema()));
        assertEquals(ConditionTreeEvaluator.Verdict.PASS, evaluator.evaluate(or, ctxWithAmount("500"), schema()),
                "logic 大小写不敏感（equalsIgnoreCase），存量数据里两种写法都有");
        assertEquals(ConditionTreeEvaluator.Verdict.FAIL,
                evaluator.evaluate(and, ctxWithAmount("50"), schema()),
                "不满足门槛是 FAIL —— 正常业务，不是故障");
        assertEquals(ConditionTreeEvaluator.Verdict.PASS, evaluator.evaluate(null, ctxWithAmount("1"), schema()),
                "树为 null 恒通过：没有条件的活动本来就不生成淘汰规则");
        assertTrue(evaluator.matches(and, ctxWithAmount("500"), schema()));
        assertFalse(evaluator.matches(and, ctxWithAmount("50"), schema()));
    }

    @Test
    @DisplayName("写平面继续抛：脏 code 在创建期就该被拒")
    void writePlaneStillRejectsDirtyLogicAtCreation() {
        assertThrows(IllegalArgumentException.class, () -> RuleLogic.fromCode("XOR"),
                "fromCode 是写平面用的严格解析，放行等于把脏数据推给每一次决策");
        assertThrows(IllegalArgumentException.class,
                () -> translator.translate(group("XOR", leaf("orderAmount", "ge", 100)), schema()),
                "RuleConditionTranslator 必须继续在创建期拒掉");

        assertNull(RuleLogic.tryFromCode("XOR"), "读路径的宽容解析返回 null，由调用方 fail-closed");
        assertNull(RuleLogic.fromCode(null), "null 不是错误（『没写 logic』），既有语义保持不变");
        assertEquals(RuleLogic.AND, RuleLogic.tryFromCode(" and "), "两个出口的解析规则必须完全一致");
        assertEquals(RuleLogic.AND, RuleLogic.fromCode(" and "));
    }

    // ---- helpers ----

    private static ActivityRuleContext ctxWithAmount(String amount) {
        ActivityRuleContext c = new ActivityRuleContext();
        c.putAttr("orderAmount", new BigDecimal(amount));
        c.putAttr("quantity", 3);
        return c;
    }

    private static ConditionNode group(String logic, ConditionNode... children) {
        ConditionNode n = new ConditionNode();
        n.setLogic(logic);
        n.setChildren(List.of(children));
        return n;
    }

    private static ConditionNode leaf(String field, String op, Object value) {
        ConditionNode n = new ConditionNode();
        n.setField(field);
        n.setOp(op);
        n.setValue(value);
        return n;
    }
}
