package com.lrj.drools.activity.domain;

import com.lrj.drools.activity.persistence.ActivityManageEntity;
import com.lrj.drools.activity.persistence.ActivityRuleEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * <b>活动某版本的不可变权益配置</b>——「行 → 候选」这条装配的唯一目标类型（计划 R7）。
 *
 * <p><b>它解决什么</b>：同一份配置此前被三处**手写**地铺进 {@code ActivityCandidate} 的 19 个字段：
 * <ul>
 *   <li>{@code DecisionDataLoader.flatten}（17 个 setter）</li>
 *   <li>{@code DecisionSnapshotBuilder} → {@code CandidateTemplate}（18 个位置参数）</li>
 *   <li>{@code DecisionSnapshot.CandidateTemplate.toCandidate}（又一遍 setter）</li>
 * </ul>
 * 只有中间那份被编译器守着，于是**同一条缝已经裂开过两次**：
 * {@code scopedSpuIds}（CLAUDE.md 坑 16）与 {@code redPackageMaxDiscount}
 * （原 {@code DecisionSnapshot} 里那行事故注释）。两次的表现完全一样——
 * 不报错、不回退、日志干净，同一张券在走库与走快照两条路上<b>发不同的钱</b>，只有对账时才发现。
 *
 * <p><b>为什么是 record 而不是又一个可变 POJO</b>：装配收成一个<em>规范构造器</em>之后，
 * 「加一个配置字段却漏了某条路」在类型上不可表达——漏了就是编译失败。这正是那两次事故缺的那道约束。
 *
 * <p><b>刻意不在这里的东西</b>：
 * <ul>
 *   <li>{@code scopedSpuIds}——它是「请求的 SPU」∩「本活动当前版本的绑定」，
 *       <b>逐请求</b>算出来的交集，不属于配置。它留在 {@link ActivityCandidate} 上，
 *       且 {@code null}（作用域未知 → 按整单算）与空集（作用域已知）的语义差异必须保留</li>
 *   <li>本轮计算态（{@code eligible} / {@code computedAmount} / …）——同上，见 {@link ActivityCandidate}</li>
 * </ul>
 *
 * <p><b>{@code startTime} / {@code endTime} 为什么在这里</b>：快照的时间窗过滤要用
 * （见 {@code DecisionSnapshot.materialize}）。走库那条路的窗判定在上游
 * （{@code DecisionDataLoader.currentEffectiveVersions}）已经做过，这两个值填进来不改变它的行为。
 */
public record OfferSpec(
        String activityId, String activityName, Integer activityType, String bizLine,
        Integer activityStatus, Integer activityAreaType, String districtIds,
        Integer inventory, Integer userInventory, Integer version, int priority,
        Integer redPackageTakeType, BigDecimal redPackageAmount, String redPackageAmountUnit,
        BigDecimal redPackageMaxDiscount, String redPackageRangeAmount,
        Instant startTime, Instant endTime, List<GiftResult> gifts) {

    /** 赠品列表归一成不可变空列表：{@code null} 与「没有赠品」在下游是同一回事，别让它们分叉。 */
    public OfferSpec {
        gifts = gifts == null ? List.of() : List.copyOf(gifts);
    }

    /**
     * <b>「行 → 配置」的唯一装配入口。</b>走库路径与快照构建路径都必须经过这里。
     *
     * @param m     活动基础行（已由调用方选定为「当前线上版本」）
     * @param r     该 {@code (activityId, version)} 的规则行；{@code null} = 没有规则行，
     *              红包五列全空（与改造前逐字节一致：走库侧当年是「r != null 才 set」，
     *              快照侧是「r == null ? null : …」，两者结果相同）
     * @param gifts 赠品；买赠/加价购之外的通道传 {@code null} 或空表
     */
    public static OfferSpec from(ActivityManageEntity m, ActivityRuleEntity r, List<GiftResult> gifts) {
        return new OfferSpec(
                m.getActivityId(), m.getActivityName(), m.getActivityType(), m.getBizLine(),
                m.getActivityStatus(), m.getActivityAreaType(), m.getDistrictIds(),
                m.getInventory(), m.getUserInventory(), m.getVersion(),
                m.getPriority() == null ? 0 : m.getPriority(),
                r == null ? null : r.getRedPackageTakeType(),
                r == null ? null : r.getRedPackageAmount(),
                r == null ? null : r.getRedPackageAmountUnit(),
                r == null ? null : r.getRedPackageMaxDiscount(),
                r == null ? null : r.getRedPackageRangeAmount(),
                m.getActivityStartTime(), m.getActivityEndTime(),
                gifts);
    }

    public static Builder builder() { return new Builder(); }

    /**
     * <b>手工构造用的建造器——生产装配路径一律走 {@link #from}，不要用它。</b>
     *
     * <p>存在的理由只有一个：测试要造「只配了某两三个字段」的候选，而 19 个位置参数的规范构造器
     * 会让这类用例彻底不可读。它<b>不是第二条装配路径</b>——它不认识任何数据库行，
     * 且 {@link Builder#build()} 走的就是规范构造器，加字段照样编译失败。
     *
     * <p>刻意<b>没有</b> {@code toBuilder()}：那种「把 19 个分量拷进建造器」的方法不受编译器保护，
     * 漏拷一个就是本类要消灭的那种静默漂移，等于把刚焊死的缝重新撬开。
     */
    public static final class Builder {
        private String activityId;
        private String activityName;
        private Integer activityType;
        private String bizLine;
        private Integer activityStatus;
        private Integer activityAreaType;
        private String districtIds;
        private Integer inventory;
        private Integer userInventory;
        private Integer version;
        private int priority;
        private Integer redPackageTakeType;
        private BigDecimal redPackageAmount;
        private String redPackageAmountUnit;
        private BigDecimal redPackageMaxDiscount;
        private String redPackageRangeAmount;
        private Instant startTime;
        private Instant endTime;
        private List<GiftResult> gifts = List.of();

        public Builder activityId(String v) { this.activityId = v; return this; }
        public Builder activityName(String v) { this.activityName = v; return this; }
        public Builder activityType(Integer v) { this.activityType = v; return this; }
        public Builder bizLine(String v) { this.bizLine = v; return this; }
        public Builder activityStatus(Integer v) { this.activityStatus = v; return this; }
        public Builder activityAreaType(Integer v) { this.activityAreaType = v; return this; }
        public Builder districtIds(String v) { this.districtIds = v; return this; }
        public Builder inventory(Integer v) { this.inventory = v; return this; }
        public Builder userInventory(Integer v) { this.userInventory = v; return this; }
        public Builder version(Integer v) { this.version = v; return this; }
        public Builder priority(int v) { this.priority = v; return this; }
        public Builder redPackageTakeType(Integer v) { this.redPackageTakeType = v; return this; }
        public Builder redPackageAmount(BigDecimal v) { this.redPackageAmount = v; return this; }
        public Builder redPackageAmountUnit(String v) { this.redPackageAmountUnit = v; return this; }
        public Builder redPackageMaxDiscount(BigDecimal v) { this.redPackageMaxDiscount = v; return this; }
        public Builder redPackageRangeAmount(String v) { this.redPackageRangeAmount = v; return this; }
        public Builder startTime(Instant v) { this.startTime = v; return this; }
        public Builder endTime(Instant v) { this.endTime = v; return this; }
        public Builder gifts(List<GiftResult> v) { this.gifts = v; return this; }

        public OfferSpec build() {
            return new OfferSpec(activityId, activityName, activityType, bizLine,
                    activityStatus, activityAreaType, districtIds,
                    inventory, userInventory, version, priority,
                    redPackageTakeType, redPackageAmount, redPackageAmountUnit,
                    redPackageMaxDiscount, redPackageRangeAmount,
                    startTime, endTime, gifts);
        }
    }
}
