package com.lrj.drools.domain;

/**
 * Step 4 引入的 fact 类型 — 用来在 working memory 里追踪"已经发出的推荐/促销"。
 *
 * 设计动机:
 *   Step 3 之前所有 fact 都是请求里带进来的 (Customer / Order / Cart)。
 *   Step 4 要演示 `not` 在 working memory 上的用法，必须有规则**自己 insert** 出来的
 *   fact，否则 `not Promotion(...)` 永远成立，跟"防重复"语义对不上。
 *
 * type 用枚举字符串而不是 enum 是为了少改动: 改规则时 (新增 type) 不用动 Java。
 */
public record Promotion(String type, String message) {
}
