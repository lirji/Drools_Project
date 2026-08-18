package com.lrj.drools.domain;

/**
 * Step 12: 由规则衍生出来的告警 fact。
 *
 * 跟 Step 4 的 Promotion 角色类似 — 都是规则 RHS 自己 insert 出来的"派生 fact"。
 * 但 Step 4 用 insert (普通插入), Step 12 重点要展示 insertLogical:
 *
 *   - insert(new Alert(...))        : Alert 跟前提解耦, 永久存在直到手动 retract
 *   - insertLogical(new Alert(...)) : Alert 跟"导出它的 LHS 匹配"绑定, 前提失配引擎自动 retract
 *
 * record 即可, 衍生 fact 是只读快照。
 */
public record Alert(String sensorName, String level, String message) {
}
