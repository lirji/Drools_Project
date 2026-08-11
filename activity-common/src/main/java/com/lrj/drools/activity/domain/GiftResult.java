package com.lrj.drools.activity.domain;

import java.math.BigDecimal;

/**
 * 奖品层决策承载 fact（买赠场景）。对齐来源 {@code engine/fact/GiftResult}。
 *
 * 可变 POJO：GIFT 规则可能保留/剔除候选奖品，或改数量金额。
 *
 * <p><b>{@code activityId} / {@code version} 是出参的归属信息，不是内部字段。</b>
 * 买赠响应此前是一个扁平的赠品名列表：多个买赠活动同时命中时，调用方收到一堆赠品
 * 却无从知道每件是哪个活动、哪一版送的——于是下游既没法核销、也没法在用户投诉时回溯
 * 「这件赠品当时按哪个活动发的」。红包侧的 {@code hitActivityId} 至少还有一个（尽管
 * STACK 下也只剩一个），买赠侧连一个都没有。加价购的 {@code AddOnOption} 早就带了
 * version，这里是把三个通道的出参形状对齐。
 */
public class GiftResult {

    private String activityId;
    private Integer version;
    private String batchId;
    private String giftName;
    private String giftType;
    private Integer giftNum;
    private BigDecimal absoluteAmount = BigDecimal.ZERO;
    private String rightType;

    public GiftResult() {}

    public GiftResult(String activityId, Integer version, String batchId, String giftName, String giftType,
                      Integer giftNum, BigDecimal absoluteAmount, String rightType) {
        this.activityId = activityId;
        this.version = version;
        this.batchId = batchId;
        this.giftName = giftName;
        this.giftType = giftType;
        this.giftNum = giftNum;
        this.absoluteAmount = absoluteAmount == null ? BigDecimal.ZERO : absoluteAmount;
        this.rightType = rightType;
    }

    public String getActivityId() { return activityId; }
    public void setActivityId(String activityId) { this.activityId = activityId; }

    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }

    public String getBatchId() { return batchId; }
    public void setBatchId(String batchId) { this.batchId = batchId; }

    public String getGiftName() { return giftName; }
    public void setGiftName(String giftName) { this.giftName = giftName; }

    public String getGiftType() { return giftType; }
    public void setGiftType(String giftType) { this.giftType = giftType; }

    public Integer getGiftNum() { return giftNum; }
    public void setGiftNum(Integer giftNum) { this.giftNum = giftNum; }

    public BigDecimal getAbsoluteAmount() { return absoluteAmount; }
    public void setAbsoluteAmount(BigDecimal absoluteAmount) { this.absoluteAmount = absoluteAmount; }

    public String getRightType() { return rightType; }
    public void setRightType(String rightType) { this.rightType = rightType; }
}
