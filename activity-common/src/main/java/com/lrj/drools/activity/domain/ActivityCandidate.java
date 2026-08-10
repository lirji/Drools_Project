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

    // 拓展配置（买赠等）
    private String extraConfigType;
    private String extraDataJson;

    /** 多活动碰撞优先级，越小越优先。 */
    private int priority = 0;

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

    /** 规则淘汰本候选（fail-closed 资格判定用）。 */
    public void reject(String reason) {
        this.eligible = false;
        this.rejectReason = reason;
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

    /** 权益形态。DRL 的 LHS 用它做判别（{@code benefitForm == "RATIO_ZHE"}），故返回名字而不是枚举 */
    public String getBenefitForm() { return BenefitForm.of(redPackageAmountUnit).name(); }

    public String getRedPackageAmountUnit() { return redPackageAmountUnit; }
    public void setRedPackageAmountUnit(String redPackageAmountUnit) { this.redPackageAmountUnit = redPackageAmountUnit; }

    public String getRedPackageRangeAmount() { return redPackageRangeAmount; }
    public void setRedPackageRangeAmount(String redPackageRangeAmount) { this.redPackageRangeAmount = redPackageRangeAmount; }

    public String getExtraConfigType() { return extraConfigType; }
    public void setExtraConfigType(String extraConfigType) { this.extraConfigType = extraConfigType; }

    public String getExtraDataJson() { return extraDataJson; }
    public void setExtraDataJson(String extraDataJson) { this.extraDataJson = extraDataJson; }

    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }

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
