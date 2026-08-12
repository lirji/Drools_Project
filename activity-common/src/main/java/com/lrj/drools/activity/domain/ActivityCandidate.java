package com.lrj.drools.activity.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 候选活动 fact —— 把"活动基础层 + 红包规则层 + 拓展配置"拍平成一个对象喂给 Drools。
 * 对齐来源 {@code engine/fact/ActivityCandidate}。
 *
 * 可变 POJO：规则会 {@code setComputedAmount} / {@code reject} / {@code addGift} / {@code modify}。
 * 字段命名与 DRL 里的约束访问器一一对应（改名要同步改 DRL）。
 */
public class ActivityCandidate {

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

    // 红包规则层
    private Integer redPackageTakeType;
    private BigDecimal redPackageAmount;
    private String redPackageAmountUnit;
    /** 折扣型的封顶减免额（元）。null = 不封顶 */
    private BigDecimal redPackageMaxDiscount;
    /** 阶梯/区间金额配置（JSON 串），LADDER 场景解析。 */
    private String redPackageRangeAmount;

    /** 多活动碰撞优先级，越小越优先。 */
    private int priority = 0;

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
     * <p><b>null 与空集的区别是刻意的</b>：
     * <ul>
     *   <li>{@code null} = <b>作用域未知</b>（手工构造的候选、老的装配路径）→ 按整单算，与改造前逐字节一致</li>
     *   <li>非空集合 = 作用域已知 → 由 {@code BenefitEvaluator.baseAmount} 决定基数</li>
     * </ul>
     * 两条生产装配路径（{@code DecisionDataLoader.flatten} 与 {@code DecisionSnapshot.materialize}）
     * 都必须填它；漏填的表现是「这条路按整单算、另一条按作用域算」，同一张券在两条路上发不同的钱。
     */
    private java.util.Set<Long> scopedSpuIds;

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
    private List<GiftResult> gifts = new ArrayList<>();

    public ActivityCandidate() {}

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

    public String getActivityId() { return activityId; }
    public void setActivityId(String activityId) { this.activityId = activityId; }

    public String getActivityName() { return activityName; }
    public void setActivityName(String activityName) { this.activityName = activityName; }

    public Integer getActivityType() { return activityType; }
    public void setActivityType(Integer activityType) { this.activityType = activityType; }

    public String getBizLine() { return bizLine; }
    public void setBizLine(String bizLine) { this.bizLine = bizLine; }

    public Integer getActivityStatus() { return activityStatus; }
    public void setActivityStatus(Integer activityStatus) { this.activityStatus = activityStatus; }

    public Integer getActivityAreaType() { return activityAreaType; }
    public void setActivityAreaType(Integer activityAreaType) { this.activityAreaType = activityAreaType; }

    public String getDistrictIds() { return districtIds; }
    public void setDistrictIds(String districtIds) { this.districtIds = districtIds; }

    public Integer getInventory() { return inventory; }
    public void setInventory(Integer inventory) { this.inventory = inventory; }

    public Integer getUserInventory() { return userInventory; }
    public void setUserInventory(Integer userInventory) { this.userInventory = userInventory; }

    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }

    public Integer getRedPackageTakeType() { return redPackageTakeType; }
    public void setRedPackageTakeType(Integer redPackageTakeType) { this.redPackageTakeType = redPackageTakeType; }

    public BigDecimal getRedPackageAmount() { return redPackageAmount; }
    public void setRedPackageAmount(BigDecimal redPackageAmount) { this.redPackageAmount = redPackageAmount; }

    public BigDecimal getRedPackageMaxDiscount() { return redPackageMaxDiscount; }
    public void setRedPackageMaxDiscount(BigDecimal redPackageMaxDiscount) { this.redPackageMaxDiscount = redPackageMaxDiscount; }

    public String getRedPackageAmountUnit() { return redPackageAmountUnit; }
    public void setRedPackageAmountUnit(String redPackageAmountUnit) { this.redPackageAmountUnit = redPackageAmountUnit; }

    public String getRedPackageRangeAmount() { return redPackageRangeAmount; }
    public void setRedPackageRangeAmount(String redPackageRangeAmount) { this.redPackageRangeAmount = redPackageRangeAmount; }

    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }

    /** 见字段注释。{@code null} = 作用域未知（按整单算），非 null = 已知作用域。 */
    public java.util.Set<Long> getScopedSpuIds() { return scopedSpuIds; }
    public void setScopedSpuIds(java.util.Set<Long> scopedSpuIds) { this.scopedSpuIds = scopedSpuIds; }

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

    public List<GiftResult> getGifts() { return gifts; }
    public void setGifts(List<GiftResult> gifts) { this.gifts = gifts; }
}
