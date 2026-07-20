package com.lrj.drools.activity.domain;

import java.math.BigDecimal;

/**
 * 奖品层决策承载 fact（买赠场景）。对齐来源 {@code engine/fact/GiftResult}。
 *
 * 可变 POJO：GIFT 规则可能保留/剔除候选奖品，或改数量金额。
 */
public class GiftResult {

    private String batchId;
    private String giftName;
    private String giftType;
    private Integer giftNum;
    private BigDecimal absoluteAmount = BigDecimal.ZERO;
    private String rightType;

    public GiftResult() {}

    public GiftResult(String batchId, String giftName, String giftType,
                      Integer giftNum, BigDecimal absoluteAmount, String rightType) {
        this.batchId = batchId;
        this.giftName = giftName;
        this.giftType = giftType;
        this.giftNum = giftNum;
        this.absoluteAmount = absoluteAmount == null ? BigDecimal.ZERO : absoluteAmount;
        this.rightType = rightType;
    }

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
