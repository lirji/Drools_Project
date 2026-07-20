package com.lrj.drools.domain;

import java.io.Serializable;

/**
 * Step 10: 单次购买事件。
 *
 * record 不会自动 implements Serializable, Drools Marshaller 序列化需要,
 * 所以这里显式声明。fact 工作完会被 retract, 不会留在持久化的 working memory 里,
 * 但 Marshaller 仍可能在 marshall 瞬间需要序列化 — 必须实现 Serializable 才安全。
 */
public record PurchaseEvent(double amount) implements Serializable {}
