package com.lrj.drools.activity.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 候选活动 fact —— 喂给 Drools 与纯 Java 求值层的那个对象。
 *
 * <p><b>结构（R7 之后）</b>：候选 = <b>配置</b>（{@link OfferSpec}，不可变、可跨请求共享）
 * + <b>本轮计算态</b>（每次决策新建）。两者此前焊在同一个可变对象上，直接逼出了三份手写字段扇出
 * 与一个 19 分量的影子类 {@code CandidateTemplate}，并已经漂移过两次（见 {@link OfferSpec} 类注释）。
 *
 * <p>配置的 19 个访问器<b>原名原签名保留</b>——DRL 的 LHS 按名字绑定
 * （{@code ActivityCandidate( eligible == true, gifts != null, gifts.size() > 0 )}），
 * 改名或去掉不会报错，只会让规则<b>静默失配</b>：买赠一个赠品都不发，日志干净。
 *
 * <p><b>配置没有 setter 是刻意的</b>：配置只能来自 {@link OfferSpec}，而 {@code OfferSpec} 只能来自
 * {@link OfferSpec#from}（生产）或 {@link OfferSpec.Builder}（手工构造）。想加一个配置字段却漏了某条
 * 装配路径，会在编译期被拦住而不是在对账时被发现。规则改的仍是计算态
 * （{@code setComputedAmount} / {@code reject} / {@code addGift}），那些 setter 都还在。
 */
public class ActivityCandidate {

    /** 本活动某版本的权益配置。可跨请求共享（不可变），19 个 getter 委托给它。 */
    private final OfferSpec spec;

    /**
     * <b>本活动在这一次请求里圈到的 SPU 集合</b>＝「请求的 spuIdList」∩「本活动当前版本的生效绑定」。
     *
     * <p><b>为什么必须有它</b>：绑定关系此前只被当成<em>候选筛选器</em>——用 SPU 查出「哪些活动可能适用」，
     * 之后绑定信息就被 {@code .distinct()} 丢掉了，求值层手上只剩 {@code orderAmount} 一个标量。
     * 于是一个只绑了 A 商品的「9.9 一口价」，在「A + 一台 5000 元电视」的购物车里会算成
     * {@code 5009.9 − 9.9}，<b>整车按 9.9 成交</b>；「指定商品 8 折」同理变成整单 8 折。
     * 全程没有报错、没有 warning——金额是正数、决策成功、日志干净。
     *
     * <p>所以绑定必须从「筛选器」升级成<b>权益作用域</b>：它回答的是
     * 「这个活动的钱该算在哪些商品上」，而不只是「这个活动要不要参与」。
     *
     * <p><b>它不进 {@link OfferSpec}</b>：它是逐请求的交集，不是配置。
     *
     * <p><b>null 与空集的区别是刻意的</b>：
     * <ul>
     *   <li>{@code null} = <b>作用域未知</b>（手工构造的候选、老的装配路径）→ 按整单算，与改造前逐字节一致</li>
     *   <li>非空集合 = 作用域已知 → 由 {@code BenefitEvaluator.baseAmount} 决定基数</li>
     * </ul>
     * 两条生产装配路径（{@code DecisionDataLoader.flatten} 与 {@code DecisionSnapshot.materialize}）
     * 都必须填它；漏填的表现是「这条路按整单算、另一条按作用域算」，同一张券在两条路上发不同的钱。
     */
    private Set<Long> scopedSpuIds;

    // 规则决策结果
    private boolean eligible = true;
    private String rejectReason;
    private BigDecimal computedAmount = BigDecimal.ZERO;
    private boolean amountComputed = false;
    /**
     * 阶梯是否为本候选落过档。
     *
     * <p>存在的唯一理由：{@code computedAmount} 分辨不出「阶梯落档发 0 元」与「阶梯根本没落档」——
     * 两者都是 0，而前者是合法优惠、后者必须淘汰。不能靠金额判别（首档 reward=0 是运营配得出来的
     * 合法档位），也不能复用 {@code amountComputed}：阶梯故意不设它，好让固定金额/折扣覆盖阶梯的
     * 既有语义成立（见 {@code BenefitEvaluator.computeAmounts} 的说明）。
     */
    private boolean ladderApplied = false;

    /**
     * 本轮<b>物化</b>出来的赠品。
     *
     * <p>权威配置在 {@code spec.gifts()}；这里是可变副本，因为
     * ① {@code withGifts=false} 的通道（红包）必须看到空表——今天两条装配路径都是这个语义；
     * ② {@link #addGift} 还在被使用。
     */
    private final List<GiftResult> gifts;

    /** 作用域未知 + 不带赠品。手工构造与非买赠通道的默认形态。 */
    public ActivityCandidate(OfferSpec spec) {
        this(spec, null, false);
    }

    /**
     * @param scopedSpuIds 本次请求里该活动圈到的 SPU（见字段注释；{@code null} = 作用域未知）
     * @param withGifts    是否物化赠品。{@code false} 时 {@link #getGifts()} 返回空表，
     *                     与改造前「只有买赠/加价购通道才 setGifts」逐字节一致
     */
    public ActivityCandidate(OfferSpec spec, Set<Long> scopedSpuIds, boolean withGifts) {
        this.spec = spec == null ? OfferSpec.builder().build() : spec;
        this.scopedSpuIds = scopedSpuIds;
        this.gifts = withGifts ? new ArrayList<>(this.spec.gifts()) : new ArrayList<>();
    }

    /** 本候选的权益配置（不可变）。 */
    public OfferSpec spec() { return spec; }

    /**
     * 规则淘汰本候选（fail-closed 资格判定用）。
     *
     * <p><b>String 重载必须保留</b>：生成的资格 DRL 里 emit 的就是
     * {@code $c.reject("不满足资格条件");}（{@code ActivityDrlBuilder}），DRL 文本按值调用它；
     * 算额阶段也要拼「本活动不适用：」前缀，同样落在这个重载上。
     * Java 侧的新调用点一律用 {@link #reject(RejectReason)}。
     */
    public void reject(String reason) {
        this.eligible = false;
        this.rejectReason = reason;
    }

    /**
     * 按枚举淘汰——<b>码与文案同源</b>，写入的仍是 {@link RejectReason#message()} 这个 String，
     * 故 {@link #getRejectReason()} 的取值与改造前逐字节一致（前端零改动）。
     */
    public void reject(RejectReason reason) {
        reject(reason == null ? null : reason.message());
    }

    public void addGift(GiftResult gift) {
        if (gift != null) this.gifts.add(gift);
    }

    // ---------------------------------------------------------------- 配置（委托 spec，只读）
    //
    // ⚠ 这 19 个访问器与 DRL 的 LHS 约束一一对应，改名/去掉会让规则静默失配。

    public String getActivityId() { return spec.activityId(); }

    public String getActivityName() { return spec.activityName(); }

    public Integer getActivityType() { return spec.activityType(); }

    public String getBizLine() { return spec.bizLine(); }

    public Integer getActivityStatus() { return spec.activityStatus(); }

    public Integer getActivityAreaType() { return spec.activityAreaType(); }

    public String getDistrictIds() { return spec.districtIds(); }

    public Integer getInventory() { return spec.inventory(); }

    public Integer getUserInventory() { return spec.userInventory(); }

    public Integer getVersion() { return spec.version(); }

    public Integer getRedPackageTakeType() { return spec.redPackageTakeType(); }

    public BigDecimal getRedPackageAmount() { return spec.redPackageAmount(); }

    /** 折扣型的封顶减免额（元）。null = 不封顶。 */
    public BigDecimal getRedPackageMaxDiscount() { return spec.redPackageMaxDiscount(); }

    public String getRedPackageAmountUnit() { return spec.redPackageAmountUnit(); }

    /** 阶梯/区间金额配置（JSON 串），LADDER 场景解析。 */
    public String getRedPackageRangeAmount() { return spec.redPackageRangeAmount(); }

    /** 多活动碰撞优先级，越小越优先。 */
    public int getPriority() { return spec.priority(); }

    /** 见 {@link OfferSpec}：买赠 DRL 的 LHS 读 {@code gifts}，这个委托不能去掉。 */
    public List<GiftResult> getGifts() { return gifts; }

    // ---------------------------------------------------------------- 本轮计算态（可变）

    /** 见字段注释。{@code null} = 作用域未知（按整单算），非 null = 已知作用域。 */
    public Set<Long> getScopedSpuIds() { return scopedSpuIds; }
    public void setScopedSpuIds(Set<Long> scopedSpuIds) { this.scopedSpuIds = scopedSpuIds; }

    public boolean isEligible() { return eligible; }
    public void setEligible(boolean eligible) { this.eligible = eligible; }

    public String getRejectReason() { return rejectReason; }
    public void setRejectReason(String rejectReason) { this.rejectReason = rejectReason; }

    public BigDecimal getComputedAmount() { return computedAmount; }
    public void setComputedAmount(BigDecimal computedAmount) { this.computedAmount = computedAmount; }

    public boolean isAmountComputed() { return amountComputed; }
    public void setAmountComputed(boolean amountComputed) { this.amountComputed = amountComputed; }

    public boolean isLadderApplied() { return ladderApplied; }
    public void setLadderApplied(boolean ladderApplied) { this.ladderApplied = ladderApplied; }
}
