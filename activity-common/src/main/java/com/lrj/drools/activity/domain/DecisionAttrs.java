package com.lrj.drools.activity.domain;

/**
 * 决策属性袋里**被 Java 代码硬引用**的那几个 key 的唯一出处。
 *
 * <p><b>为什么只收这几个</b>：属性袋的键分两类。一类是运营可配置的条件字段
 * （{@code quantity} / {@code userDistrictId} / {@code userTags} / {@code storeId} …），
 * 它们的权威在 {@code RuleSchemaRegistry} 的白名单，写侧改名会被
 * {@code DecisionContextFieldsTest#everyWhitelistFieldHasARequestSource} 当场照出来。
 * 另一类是**代码自己读**的键——写侧与读侧各写一遍字面量，中间没有任何编译期或测试期的联结。
 * 本类收的就是第二类：写侧在 {@code DecisionEligibilityService.requestAttributes}，
 * 读侧散在 {@link ActivityRuleContext} 的便捷访问器、{@code BenefitEvaluator} 的随机指纹、
 * {@code ActivityQueryService} 的阶梯字段。
 *
 * <p><b>这些字符串的取值本身就是契约，一个字节都不能改</b>：
 * <ul>
 *   <li>{@link #RANDOM_SEED_SPU} 进随机红包的 SHA-256 指纹。改它 = 全量随机红包一次性重抽，
 *       用户刷新页面金额就变、历史对账全部对不上（见 {@code BenefitEvaluator.drawRandom} 的注释）。</li>
 *   <li>{@link #ORDER_AMOUNT} 同时是白名单字段与阶梯落档字段（{@code LadderActivityDef.ladderField}），
 *       它会原样出现在生成的 DRL 文本里，而编译缓存的 key 就是 DRL 全文。</li>
 *   <li>{@link #SPU_ID} 的值是 ARRAY（整个购物车的 SPU 列表），与 {@link #RANDOM_SEED_SPU} 那个标量
 *       是**两个不同的键**，不要合并——合并即重抽。</li>
 * </ul>
 *
 * <p>常量化只是把「写侧改名、读侧不知情」从静默变成可见；它不替代
 * {@code DecisionContextFieldsTest} 里那条写死字面量的键集合断言——那条才是真正的守卫，
 * 因为它不引用本类，本类改名它照样红。
 */
public final class DecisionAttrs {

    private DecisionAttrs() {}

    /** 订单金额。白名单字段 + 阶梯落档字段 + 随机指纹的数值段。 */
    public static final String ORDER_AMOUNT = "orderAmount";

    /** 本次请求的全部 SPU（ARRAY）。作用域基数判定用；条件里的 eq/in 由求值器映射成集合语义。 */
    public static final String SPU_ID = "spuId";

    /** 用户标识。不在条件白名单里，但随机红包的确定性种子依赖它。 */
    public static final String USER_ID = "userId";

    /**
     * 随机红包种子专用的 SPU 标量（「购物车第一件」的旧值）。
     * 不在条件白名单里、也不该被任何条件引用，唯一职责是把种子链钉住。
     */
    public static final String RANDOM_SEED_SPU = "randomSeedSpu";

    /** 订单行。只服务于第 N 件折与作用域小计，不进条件白名单。 */
    public static final String ORDER_LINES = "orderLines";
}
