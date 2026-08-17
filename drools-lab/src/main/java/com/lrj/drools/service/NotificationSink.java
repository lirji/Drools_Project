package com.lrj.drools.service;

import java.util.ArrayList;
import java.util.List;

/**
 * Step 20: 通过 `global` 注入 RHS 的外部对象示例。
 *
 * global 是"给规则动作侧递一个外部句柄" —— logger / 领域 service / 计数器都常这么注入。
 * RHS 里写 `sink.audit(...)`, 引擎不管这个对象怎么来的, 只负责在规则命中时调它。
 *
 * 跟 channel 的区别:
 *   - global : RHS **主动调**注入进来的对象 (pull 式句柄), 常用于"读外部配置 / 记日志 / 累加"
 *   - channel: RHS **send** 一个对象给外部注册的回调 (push 式出口), 常用于"把消息投递出引擎"
 *
 * 本类刻意**不是** Spring @Bean —— KieSession 每请求新建, global 也每请求 new 一个,
 * 避免多请求共享可变状态 (KieSession/global 都不是线程安全的复用对象)。
 */
public class NotificationSink {

    private final List<String> auditLog = new ArrayList<>();

    /** RHS 调用: 记一条审计。 */
    public void audit(String message) {
        auditLog.add(message);
    }

    public List<String> getAuditLog() {
        return auditLog;
    }
}
