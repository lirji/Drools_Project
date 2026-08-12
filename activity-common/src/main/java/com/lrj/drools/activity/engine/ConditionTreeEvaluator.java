package com.lrj.drools.activity.engine;

import com.lrj.drools.activity.domain.ActivityRuleContext;
import com.lrj.drools.activity.domain.ConditionNode;
import com.lrj.drools.activity.domain.FieldValueType;
import com.lrj.drools.activity.domain.RuleLogic;
import com.lrj.drools.activity.domain.RuleOperator;
import com.lrj.drools.activity.domain.SchemaField;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 资格条件树的**直接求值器**（计划 P1-3 · 分层引擎第二步）。
 *
 * <p><b>为什么不是 QLExpress</b>：计划里这一步写的是「引入 QLExpress 做资格判定」。
 * 但查证后发现 {@code activity_condition.condition_tree_json} <b>已经存了结构化的条件树</b>——
 * 也就是说我们手里本来就有 AST。把 AST 编译成字符串、再引一个表达式引擎去解析那个字符串，是绕远路：
 * <ul>
 *   <li>多一个运行时依赖，且 QLExpress 3.x 默认放开反射、要靠白名单收——多一类注入面</li>
 *   <li>多一次「树 → 串」的翻译，每加一个算子就要同时维护翻译器与求值器两处</li>
 *   <li>字符串要转义，转义就有漏的可能；直接求值连转义这回事都不存在</li>
 * </ul>
 * 直接解释树：零新依赖、零注入面、纯函数、可单测到每个算子。表达式引擎的价值在
 * 「让**非开发者**写逻辑」，而这里运营写的是条件树、不是表达式——那个价值在此处不存在。
 *
 * <p><b>语义必须与 {@link RuleConditionTranslator} emit 的 DRL 逐条对齐</b>，
 * 尤其这几处容易漏的：
 * <ul>
 *   <li>{@code BETWEEN} 是<b>双闭区间</b> {@code [lo, hi]}（注意与阶梯档位的 {@code [min,max)} 不同）</li>
 *   <li>否定算子 {@code NE / NOT_IN / NOT_CONTAINS} 都带存在性护栏：
 *       字段缺失时结果为 <b>false</b>（候选被淘汰），而不是"没有这个字段所以不等于、算通过"。
 *       这是 fail-closed 的核心——宁可不发，不可超发。</li>
 *   <li>正向比较遇到 null 一律 false（DRL 里 {@code null > 100} 不成立）</li>
 * </ul>
 * <p><b>本类是唯一的生产求值器</b>：discount / gifts / addon 三通道的资格判定都只走这里。
 * {@link RuleConditionTranslator} 的产物仅用于写入口的编译校验，<b>不参与求值</b>——
 * 所以两者之间没有、也不该有"两条路对拍"这回事；上面几条语义对齐是给翻译器的约束，不是对拍关系。
 */
@Service
public class ConditionTreeEvaluator {

    /**
     * 求值整棵树。
     *
     * @param schema 字段白名单（决定用哪种访问器语义），与创建期翻译时用的是同一份来源
     * @return true = 满足资格；false = 淘汰。<b>树为 null 视为恒通过</b>（与"没有条件的活动不生成淘汰规则"一致）
     */
    public boolean matches(ConditionNode root, ActivityRuleContext ctx, Map<String, SchemaField> schema) {
        if (root == null) return true;
        return eval(root, ctx, schema);
    }

    private boolean eval(ConditionNode node, ActivityRuleContext ctx, Map<String, SchemaField> schema) {
        if (node.isGroup()) {
            List<ConditionNode> children = node.getChildren();
            if (children == null || children.isEmpty()) return true;   // 空组 = 无约束（翻译期也会剪掉）
            RuleLogic logic = RuleLogic.fromCode(node.getLogic());
            if (logic == RuleLogic.OR) {
                for (ConditionNode c : children) if (eval(c, ctx, schema)) return true;
                return false;
            }
            for (ConditionNode c : children) if (!eval(c, ctx, schema)) return false;
            return true;
        }
        return evalLeaf(node, ctx, schema);
    }

    private boolean evalLeaf(ConditionNode leaf, ActivityRuleContext ctx, Map<String, SchemaField> schema) {
        SchemaField field = schema == null ? null : schema.get(leaf.getField());
        if (field == null) {
            // 字段不在白名单里：创建期就该被拒。运行时遇到只可能是 schema 漂移 → fail-closed
            return false;
        }
        RuleOperator op = RuleOperator.fromCode(leaf.getOp());
        FieldValueType type = field.valueType();
        Object raw = leaf.getValue();

        // ARRAY 字段上的标量算子 → 集合语义（存量兼容层）。
        //
        // 背景：spuId 从 NUMBER 改成了 ARRAY——属性袋里装的从「购物车第一件」变成了「整个 SPU 列表」，
        // 因为「第一件是 X」这个语义是错的（同样两件商品换个加购顺序，资格结论就不一样）。
        // 但库里存量的条件树写的是 `spuId eq 990011` / `spuId in [...]`，运营也不该被要求重配一遍。
        // 于是在求值层做语义映射：eq→contains、ne→not contains、in→containsAny、notIn→都不含。
        //
        // ⚠ 这是一次**语义放宽**：单 SPU 请求下两者完全等价；多 SPU 请求下
        // 「购物车第一件是 X」→「购物车包含 X」会更容易命中。方向是往外发钱，属于有意为之——
        // 运营配这个条件时想说的本来就是「买了 X」，而且配合权益作用域（活动只对自己圈的商品算钱），
        // 「更容易命中」同时伴随「每次命中发得更少」，合起来不是放大敞口。
        if (type == FieldValueType.ARRAY) {
            Collection<?> actual = ctx.listAttr(field.key());
            switch (op) {
                case EQ -> { return actual != null && containsValue(actual, raw); }
                case NE -> { return actual != null && !containsValue(actual, raw); }
                case IN -> {
                    List<?> vals = asList(raw);
                    return actual != null && vals != null && vals.stream().anyMatch(v -> containsValue(actual, v));
                }
                case NOT_IN -> {
                    List<?> vals = asList(raw);
                    return actual != null && vals != null && vals.stream().noneMatch(v -> containsValue(actual, v));
                }
                default -> { /* CONTAINS 系与数值算子走下面的通用分支 */ }
            }
        }

        return switch (op) {
            case EQ -> {
                Object actual = scalarAttr(ctx, field);
                yield actual != null && scalarEquals(actual, raw, type);
            }
            // 否定算子：缺字段短路成 false（fail-closed，防静默超发）
            case NE -> {
                Object actual = scalarAttr(ctx, field);
                yield actual != null && !scalarEquals(actual, raw, type);
            }
            case GT -> cmp(ctx, field, raw, c -> c > 0);
            case GE -> cmp(ctx, field, raw, c -> c >= 0);
            case LT -> cmp(ctx, field, raw, c -> c < 0);
            case LE -> cmp(ctx, field, raw, c -> c <= 0);
            case BETWEEN -> {
                List<?> range = asList(raw);
                if (range == null || range.size() != 2) yield false;
                BigDecimal actual = ctx.numberAttr(field.key());
                if (actual == null) yield false;
                BigDecimal lo = num(range.get(0));
                BigDecimal hi = num(range.get(1));
                // 双闭区间：与翻译器 emit 的 (acc >= lo && acc <= hi) 一致
                yield lo != null && hi != null
                        && actual.compareTo(lo) >= 0 && actual.compareTo(hi) <= 0;
            }
            case IN -> {
                Object actual = scalarAttr(ctx, field);
                List<?> vals = asList(raw);
                yield actual != null && vals != null
                        && vals.stream().anyMatch(v -> scalarEquals(actual, v, type));
            }
            case NOT_IN -> {
                Object actual = scalarAttr(ctx, field);
                List<?> vals = asList(raw);
                yield actual != null && vals != null
                        && vals.stream().noneMatch(v -> scalarEquals(actual, v, type));
            }
            case CONTAINS -> {
                Collection<?> actual = ctx.listAttr(field.key());
                yield actual != null && containsValue(actual, raw);
            }
            case NOT_CONTAINS -> {
                Collection<?> actual = ctx.listAttr(field.key());
                yield actual != null && !containsValue(actual, raw);
            }
            case CONTAINS_ANY -> {
                Collection<?> actual = ctx.listAttr(field.key());
                List<?> vals = asList(raw);
                yield actual != null && vals != null
                        && vals.stream().anyMatch(v -> containsValue(actual, v));
            }
        };
    }

    /** 数值比较；任一侧为 null 一律 false（DRL 里 null 参与比较不成立）。 */
    private boolean cmp(ActivityRuleContext ctx, SchemaField field, Object raw,
                        java.util.function.IntPredicate accept) {
        BigDecimal actual = ctx.numberAttr(field.key());
        BigDecimal expected = num(raw);
        if (actual == null || expected == null) return false;
        return accept.test(actual.compareTo(expected));
    }

    /** 按字段类型取标量属性：NUMBER 走 numberAttr，其余走 textAttr（与翻译器的访问器选择一致）。 */
    private Object scalarAttr(ActivityRuleContext ctx, SchemaField field) {
        return field.valueType() == FieldValueType.NUMBER
                ? ctx.numberAttr(field.key())
                : ctx.textAttr(field.key());
    }

    /** 标量相等：NUMBER 用 compareTo（避免 50 与 50.00 的 equals 陷阱），其余按字符串。 */
    private boolean scalarEquals(Object actual, Object expected, FieldValueType type) {
        if (expected == null) return false;
        if (type == FieldValueType.NUMBER) {
            BigDecimal a = num(actual);
            BigDecimal b = num(expected);
            return a != null && b != null && a.compareTo(b) == 0;
        }
        return Objects.equals(String.valueOf(actual), String.valueOf(expected));
    }

    /** 集合包含：元素按字符串比，与 DRL 的 {@code contains} 在字符串集合上的行为一致。 */
    private boolean containsValue(Collection<?> actual, Object expected) {
        if (expected == null) return false;
        String want = String.valueOf(expected);
        for (Object o : actual) {
            if (o != null && String.valueOf(o).equals(want)) return true;
        }
        return false;
    }

    private static BigDecimal num(Object v) {
        if (v == null) return null;
        if (v instanceof BigDecimal b) return b;
        try {
            return new BigDecimal(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static List<?> asList(Object v) {
        return v instanceof List<?> l ? l : null;
    }
}
