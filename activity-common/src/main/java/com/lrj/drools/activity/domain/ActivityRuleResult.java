package com.lrj.drools.activity.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 规则输出 fact —— DRL 里的 {@code global result}。对齐来源 {@code engine/fact/ActivityRuleResult}。
 *
 * 各场景写入口径：
 * - ELIGIBILITY：{@link #eligibleCandidates} 收集通过资格的候选
 * - DISCOUNT / LADDER：{@link #hit} 写命中活动与金额；STACK 用 {@link #setHitAmount} 写累加额
 * - GIFT：{@link #eligibleCandidates} 里候选的 {@code gifts} 即应发奖品
 * - {@link #traces} 是规则诊断信息（前端展示 + 灰度对照）
 *
 * <p><b>P1-10 前瞻结构</b>：{@link #benefits} 是"命中权益列表"，为将来多权益并存留位；
 * {@code hitActivityId/hitAmount/gifts} 保留为**兼容投影**（现有读路径与前端不变）。
 * MVP 单场景单 benefit-type，跨异构权益合并不支持（见 {@link BenefitOutcome}）。
 */
public class ActivityRuleResult {

    private List<ActivityCandidate> eligibleCandidates = new ArrayList<>();
    private StackStrategy strategy = StackStrategy.MAX;
    private String hitActivityId;
    private String hitActivityName;
    private BigDecimal hitAmount = BigDecimal.ZERO;
    private List<GiftResult> gifts = new ArrayList<>();
    private List<BenefitOutcome> benefits = new ArrayList<>();
    private List<String> traces = new ArrayList<>();

    public ActivityRuleResult() {}

    public void addEligible(ActivityCandidate candidate) {
        if (candidate != null) this.eligibleCandidates.add(candidate);
    }

    /** DISCOUNT/LADDER 命中单个主活动：写 id / name / amount（+ 追加一条权益产出）。 */
    public void hit(ActivityCandidate candidate) {
        if (candidate == null) return;
        this.hitActivityId = candidate.getActivityId();
        this.hitActivityName = candidate.getActivityName();
        this.hitAmount = candidate.getComputedAmount() == null ? BigDecimal.ZERO : candidate.getComputedAmount();
        this.benefits.add(BenefitOutcome.discount(this.hitActivityId, this.hitAmount));
    }

    public void trace(String msg) {
        if (msg != null) this.traces.add(msg);
    }

    public List<ActivityCandidate> getEligibleCandidates() { return eligibleCandidates; }
    public void setEligibleCandidates(List<ActivityCandidate> eligibleCandidates) {
        this.eligibleCandidates = eligibleCandidates == null ? new ArrayList<>() : eligibleCandidates;
    }

    public StackStrategy getStrategy() { return strategy; }
    public void setStrategy(StackStrategy strategy) { this.strategy = strategy; }

    public String getHitActivityId() { return hitActivityId; }
    public void setHitActivityId(String hitActivityId) { this.hitActivityId = hitActivityId; }

    public String getHitActivityName() { return hitActivityName; }
    public void setHitActivityName(String hitActivityName) { this.hitActivityName = hitActivityName; }

    public BigDecimal getHitAmount() { return hitAmount; }
    public void setHitAmount(BigDecimal hitAmount) { this.hitAmount = hitAmount; }

    public List<GiftResult> getGifts() { return gifts; }
    public void setGifts(List<GiftResult> gifts) { this.gifts = gifts == null ? new ArrayList<>() : gifts; }

    public List<BenefitOutcome> getBenefits() { return benefits; }
    public void setBenefits(List<BenefitOutcome> benefits) {
        this.benefits = benefits == null ? new ArrayList<>() : benefits;
    }

    public List<String> getTraces() { return traces; }
    public void setTraces(List<String> traces) { this.traces = traces == null ? new ArrayList<>() : traces; }
}
