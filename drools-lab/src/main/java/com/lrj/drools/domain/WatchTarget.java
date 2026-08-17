package com.lrj.drools.domain;

/**
 * Step 13 扩展 (后向链嵌 LHS) 的驱动 fact。
 *
 * 原 /backward/contains 走 Java 侧 `session.getQueryResults(...)` 主动 pull 后向链;
 * 新 /backward/derive 换一种消费方式: insert 一批 WatchTarget, 让**前向链规则**在 LHS
 * 里用 `?isContainedIn($thing, $zone;)` 反向拉起后向链证明 —— 证明成立则规则 fire。
 * 这展示"后向链作为前向链的一个可复用推理子程序"。
 *
 * thing = 要考察的对象, zone = 要判断是否 (递归地) 落在其中的受限区域。
 */
public record WatchTarget(String thing, String zone) {
}
