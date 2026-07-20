package com.lrj.drools.domain;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Step 10 持久化主 fact。
 *
 * 必须 implements Serializable: Drools 默认的 ObjectMarshallingStrategy
 * (SerializablePlaceholderResolverStrategy) 用 Java 原生序列化把 fact 塞进
 * 整体 byte[]。fact 不可序列化的话 Marshaller.marshall 会抛 NotSerializableException。
 *
 * 字段约定:
 *  - totalPoints: 累积积分, 规则在 RHS 累加
 *  - tier: 当前等级 (NONE → BRONZE → SILVER → GOLD), 规则 LHS 据此判断"够格升级吗"
 *  - unlockedBadges: 历次升级历史; 仅供观察, 不参与规则判定
 *  - lastEarned: 上次 PurchaseEvent 实际加了多少分, 给响应体看
 *
 * 升级路径强制串行: 一次 1000 块的购买不能直接跳 GOLD, 必须 BRONZE → SILVER → GOLD
 * 链式触发 (modify 引发 LHS 重新评估), 这是教学要观察的"工作记忆里 state 跨 fire
 * 持续演化"的核心点。
 */
public class LoyaltyState implements Serializable {

    private static final long serialVersionUID = 1L;

    private long totalPoints = 0L;
    private String tier = "NONE";
    private final List<String> unlockedBadges = new ArrayList<>();
    private int lastEarned = 0;

    public long getTotalPoints() { return totalPoints; }
    public void setTotalPoints(long totalPoints) { this.totalPoints = totalPoints; }

    public String getTier() { return tier; }
    public void setTier(String tier) { this.tier = tier; }

    public List<String> getUnlockedBadges() { return unlockedBadges; }

    public int getLastEarned() { return lastEarned; }
    public void setLastEarned(int lastEarned) { this.lastEarned = lastEarned; }
}
