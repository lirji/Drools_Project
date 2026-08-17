package com.lrj.drools.domain;

/**
 * Step 21: traits 规则的输出标记 fact。
 *
 * 规则在给 Applicant `don` 上 PremiumApplicant 之后、用 trait 类型再匹配到时 insert 出来，
 * service 捞回来当结果。展示"贴了 trait 之后核心 fact 能被 trait 类型的模式命中"（动态多态）。
 */
public record TraitFinding(String name, String tier, long creditLimit, String perk) {
}
