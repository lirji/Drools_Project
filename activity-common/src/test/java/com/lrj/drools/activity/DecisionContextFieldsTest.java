package com.lrj.drools.activity;

import com.lrj.drools.activity.domain.SchemaField;
import com.lrj.drools.activity.domain.SpuDiscountRequest;
import com.lrj.drools.activity.engine.RuleSchemaRegistry;
import com.lrj.drools.activity.service.ActivityQueryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 结构守卫：**条件字段白名单里的每个 key，都必须在决策入参里有来源**（拍板 D12-4）。
 *
 * <p><b>它防的是什么</b>：白名单（{@link RuleSchemaRegistry}）与属性袋填充
 * （{@code ActivityQueryService.requestAttributes}）此前是两处独立维护的清单，漂了也没人知道。
 * 真实后果已经发生过一次——白名单里有 {@code storeId}，前端条件下拉会把它给运营选，后端也编译得过，
 * 但决策时属性袋里没有这个键，访问器返回 null → 正向比较恒 false → 候选被淘汰。
 * 于是**配了 storeId 条件的活动永远不命中**，而且因为是 fail-closed，表现为「静默不发」而不是报错，
 * 排查时甚至看不出哪里错了。
 *
 * <p>这条断言让同类漂移在**加字段的那一刻**就红，而不是等线上有人问「这个活动怎么永远不命中」。
 */
@DisplayName("决策上下文：白名单字段必须都有请求来源")
class DecisionContextFieldsTest {

    private static final RuleSchemaRegistry REGISTRY = new RuleSchemaRegistry();

    /** 各字段都给了非 null 值的样例请求——确保守卫检查的是「键存在」而非「本次恰好有值」。 */
    private static SpuDiscountRequest sample() {
        return new SpuDiscountRequest(
                List.of(9101L), 1001L, "310000", List.of("hot"), new BigDecimal("260"), 2, 1);
    }

    @Test
    void everyWhitelistFieldHasARequestSource() {
        Set<String> whitelist = REGISTRY.defaultFields().stream()
                .map(SchemaField::key).collect(Collectors.toSet());
        Set<String> sources = ActivityQueryService.requestAttributes(sample()).keySet();

        Set<String> missing = whitelist.stream()
                .filter(k -> !sources.contains(k)).collect(Collectors.toSet());

        assertTrue(missing.isEmpty(),
                "条件白名单里的这些字段在决策入参里没有来源，配了会永远不命中（静默不发）: " + missing
                        + "。修法：要么在 ActivityQueryService.requestAttributes 补来源，"
                        + "要么把字段从 RuleSchemaRegistry 的白名单里删掉——不允许两边不一致。");
    }

    /**
     * 属性袋的键集合是**闭集**：不多不少就是这 9 个。
     *
     * <p><b>它补的是上一条守不住的那个洞</b>：上一条只断言「白名单 ⊆ 写侧键」，
     * 而 {@code userId} / {@code randomSeedSpu} / {@code orderLines} 三个键**不在白名单里**
     * （它们不是运营可配置的条件字段，是代码自己读的）。于是把写侧的 {@code randomSeedSpu}
     * 改个名，全仓测试照样全绿——而随机红包的 SHA-256 指纹会读到 null，
     * <b>全量随机红包一次性重抽</b>：用户刷新页面金额就变、历史对账全部对不上（CLAUDE.md 坑 15）。
     *
     * <p><b>这里刻意写死字面量，绝不引用 {@code DecisionAttrs} 常量。</b>
     * 引用常量的话，改名时常量与断言会一起改、测试跟着变绿，守卫就白建了——
     * 断言的对象必须是「线上今天在用的那 9 个字符串」，而不是「代码当前认为的那 9 个」。
     * 真要新增/改名一个键，就必须**手工改这里的字面量**，那一刻正是该停下来问
     * 「这会不会让历史金额重算」的时刻。
     */
    @Test
    @DisplayName("属性袋键集合恰好是这 9 个（含三个不在白名单里的内部键）")
    void attributeKeySetIsExactlyPinned() {
        Set<String> expected = Set.of(
                "orderAmount",
                "quantity",
                "userDistrictId",
                "userTags",
                "spuId",
                "storeId",
                // 以下三个不在 RuleSchemaRegistry 白名单里，上一条断言覆盖不到
                "userId",
                "randomSeedSpu",
                "orderLines");

        assertEquals(expected, ActivityQueryService.requestAttributes(sample()).keySet(),
                "决策属性袋的键集合变了。少键 = 读侧访问器静默取到 null（随机红包重抽 / 作用域基数失准 / 第 N 件折不适用），"
                        + "多键 = 有人新增了没人读的字段。确认影响后再手工同步这里的字面量。");
    }

    @Test
    void storeIdIsActuallyPopulated() {
        Map<String, Object> attrs = ActivityQueryService.requestAttributes(sample());
        assertEquals(1, attrs.get("storeId"), "storeId 必须从请求写进属性袋（这条曾是死条件）");
    }

    @Test
    void missingValuesStayNullSoConditionsFailClosed() {
        SpuDiscountRequest blank = new SpuDiscountRequest(List.of(), null, null, null, null, null, null);
        Map<String, Object> attrs = ActivityQueryService.requestAttributes(blank);

        // 键都在（映射表恒定），但值为 null；putAttr 会跳过 null → 访问器返回 null → 正向比较 false → 淘汰候选
        assertTrue(attrs.containsKey("storeId"));
        assertTrue(attrs.values().stream().allMatch(v -> v == null),
                "空请求下所有属性都应为 null，保证缺字段 fail-closed，不得出现默认值");
    }
}
