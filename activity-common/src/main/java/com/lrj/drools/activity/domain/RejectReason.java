package com.lrj.drools.activity.domain;

/**
 * 候选活动被淘汰的原因——<b>原因码与文案的唯一真相</b>。
 *
 * <p><b>为什么必须是一个枚举而不是两处字面量</b>：改造前同一件事有三份互不相干的拷贝——
 * 给 Prometheus 的原因码（{@code metrics.reject(scene, "missing-lines")}）、
 * 给人看的中文串（{@code candidate.reject("第 N 件折缺订单行或 N 非法")}）、
 * 以及 {@code ActivityDrlBuilder} 在 DRL 文本里 emit 的第三份。三者靠人在每个调用点手工配对，
 * 谁都不校验另一个。漂移已经实证发生过一次：{@code DecisionMetrics} 的 javadoc 写着
 * {@code price-above-order}，而实际发出去的是 {@code price-above-base}，
 * 文档只好补一句「以代码为准」。<b>漏码</b>→ 这类淘汰在指标里凭空消失；<b>漏串</b>→ 用户看到空的
 * 「未生效」原因；两者都不会让任何测试变红。把码与文案钉在同一行之后，配对错位在结构上就不可能了。
 *
 * <ul>
 *   <li>{@link #code()} —— 进 {@code activity.decision.reject} 的 {@code reason} 标签。
 *       <b>已经是线上 Prometheus 标签值，一个字节都不能改</b>；同时它是有限封闭集合，
 *       这正是标签基数不会爆的原因（与 {@code DecisionMetrics.ACTIVITY_TAG_CAP} 是同一套顾虑）。</li>
 *   <li>{@link #message()} —— 进 {@code ActivityCandidate.rejectReason}，经 {@code DiscountItem}
 *       原样出到响应里，被控制台「优惠验证」页直接渲染。<b>前端测试直接断言这些中文串</b>
 *       （{@code ValidateView.test.ts} 断言 {@code '不满足资格条件'}），改文案等于改前端契约。</li>
 * </ul>
 *
 * <p><b>「本活动不适用：」前缀不在这里</b>。算额阶段（{@code BenefitEvaluator.notApplicable}）
 * 会给文案加这个前缀，资格阶段（{@code DecisionEligibilityService}）不加——这个不对称是既有行为，
 * 原样保留。所以枚举里存的是<b>不带前缀</b>的 message，前缀由算额阶段自己拼，
 * 拼出来的串与改造前逐字节一致。
 */
public enum RejectReason {

    // ---- 资格阶段（无前缀）----
    /** 条件树判定为不通过：用户不符合运营配的门槛。**正常业务**，不是故障。 */
    INELIGIBLE("ineligible", "不满足资格条件"),
    /** 物料声明该活动有受控约束、却拿不到可解释的条件树 → fail-closed 淘汰。**这是故障**，与上一条分开计数。 */
    CONDITION_UNAVAILABLE("condition-unavailable", "资格条件不可判定"),

    // ---- 算额阶段（出到 rejectReason 时带「本活动不适用：」前缀）----
    /** 既没有固定金额、随机区间也不适用，唯一的金额来源是阶梯，而阶梯没落过档。 */
    NO_LADDER_TIER("no-ladder-tier", "阶梯未落档且无固定金额"),
    /** 随机红包的区间缺失或非法 → 不给优惠，而不是给 0 元（0 元会以 0 参与 MAX 竞争挤掉别的活动）。 */
    BAD_RANDOM_RANGE("bad-random-range", "随机区间缺失或非法"),
    /** 第 N 件折拿不到逐行单价，或 N 非法 → fail-closed，**绝不拿整单均价凑**。 */
    MISSING_LINES("missing-lines", "第 N 件折缺订单行或 N 非法"),
    /** 一口价高于作用域基数（订单比秒杀价还便宜），或压根没有订单金额。 */
    PRICE_ABOVE_BASE("price-above-base", "一口价高于作用域金额或缺订单金额"),
    /** 折扣型算不出来：没有基数，或折数越界 (0,10)。 */
    BAD_RATIO("bad-ratio", "缺订单金额或折数越界"),
    /**
     * 作用域基数不可知——活动只圈了请求里的一部分商品，而请求没带订单行，分摊不出「本活动的商品一共多少钱」。
     *
     * <p>它优先于形态自己的原因码：「算不出基数」和「基数不够」是两种完全不同的排查方向。
     */
    OUT_OF_SCOPE("out-of-scope", "作用域基数不可知（活动只圈了部分商品，但请求未带订单行）");

    private final String code;
    private final String message;

    RejectReason(String code, String message) {
        this.code = code;
        this.message = message;
    }

    /** 指标标签取值。已是线上 Prometheus 序列的一部分，改它会让面板与告警失配。 */
    public String code() {
        return code;
    }

    /** 面向用户的淘汰原因（不含算额阶段的「本活动不适用：」前缀）。前端直接渲染。 */
    public String message() {
        return message;
    }
}
