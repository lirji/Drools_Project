package com.lrj.drools.domain;

/**
 * Step 20: RHS 对外 (globals / channels) 里, 通过 **channel (exit point)** 推出引擎的
 * 消息对象。
 *
 * 跟"规则 insert 一个标记 fact 再由 service 捞回来"不同 —— channel 是引擎主动把对象
 * `send` 到外部注册的回调 (RHS 里写 `channels["notify"].send(new Notice(...))`),
 * 语义上是"规则把副作用推给外部系统 (发短信/推 MQ/落审计)", fact 不留在 working memory。
 *
 * channel 是投递渠道 (SMS/PUSH/...), target 是收件人, content 是正文。
 */
public record Notice(String channel, String target, String content) {
}
