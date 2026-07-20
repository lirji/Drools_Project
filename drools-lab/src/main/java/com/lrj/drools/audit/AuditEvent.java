package com.lrj.drools.audit;

/**
 * Step 6: 规则引擎事件的结构化记录。
 *
 * type 枚举字符串而不是 enum 是为了 JSON 序列化对客户端友好 (前端不用建 enum 字典)。
 * 可能的 type:
 *   MATCH_CREATED    — activation（激活） 入 agenda（日程） (LHS 通过, 等待触发)
 *   MATCH_FIRED      — activation 已执行 (then 块跑完)
 *   MATCH_CANCELLED  — activation 被撤销 (LHS 后来失配, 没机会跑)
 *                      ← 这条是观察 `not` 反向触发的关键事件
 *   GROUP_PUSHED     — agenda-group 被压栈 (setFocus / auto-focus 触发的)
 *   GROUP_POPPED     — agenda-group 跑完弹栈
 *   OBJECT_INSERTED  — fact insert 进 working memory
 *   OBJECT_UPDATED   — fact 被 update / modify
 *   OBJECT_DELETED   — fact 被 retract
 *
 * sequence 是事件全局自增序号, 用来固定时序 (Phreak 段内顺序在 JSON 里没法保证)。
 */
public record AuditEvent(long sequence, String type, String detail) {
}
