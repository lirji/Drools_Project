package com.lrj.drools.domain;

/**
 * Step 13 扩展 (后向链嵌 LHS) 的输出标记 fact。
 *
 * 由前向链规则在 LHS 用 `?isContainedIn(...)` 反向证明成立后 insert 出来, service 事后
 * 从 working memory 捞回来。跟原 /backward/contains 返回 boolean 的区别在于: 这条结论
 * 是"前向 fire 的产物", 展示后向链结果如何驱动前向规则动作。
 */
public record ContainmentFinding(String thing, String zone, boolean contained) {
}
