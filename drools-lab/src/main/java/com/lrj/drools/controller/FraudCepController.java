package com.lrj.drools.controller;

import com.lrj.drools.domain.FraudAlert;
import com.lrj.drools.domain.LoginEvent;
import com.lrj.drools.domain.OrderEvent;
import com.lrj.drools.service.FraudCepService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Step 8 扩展 (CEP 补完) 入口: 长度滑窗 + 时序操作符 + 多 entry-point。
 *
 * 跟原 /fraud/check 并列 —— 后者只演示 window:time, 只输出 BurstAlert; 本端点演示
 * 另外三种 CEP 形态, 输出 FraudAlert (带 type 区分)。events 不需预排序, service 内部
 * 会把两条流按 timestamp 合并成时间线后推进 pseudo clock。
 *
 * 请求体:
 *   orders: [{orderId, customerName, amount, timestamp}, ...]  → "order-stream"
 *   logins: [{customerName, ip, timestamp}, ...]               → "login-stream"
 *
 * 三种告警 (FraudAlert.type):
 *   LENGTH_BURST            —— 同客户最近 5 单里 ≥ 3 笔大额 (over window:length)
 *   PROBE_THEN_STRIKE       —— 小额(<10)试探后 2 分钟内大额(>1000) (this after[0s,2m])
 *   FAST_ORDER_AFTER_LOGIN  —— 登录后 30 秒内大额下单 (跨 entry-point + after[0s,30s])
 */
@RestController
public class FraudCepController {

    private final FraudCepService fraudCepService;

    public FraudCepController(FraudCepService fraudCepService) {
        this.fraudCepService = fraudCepService;
    }

    @PostMapping("/fraud/patterns")
    public CheckResponse patterns(@RequestBody CheckRequest req) {
        return new CheckResponse(fraudCepService.check(req.orders(), req.logins()));
    }

    public record CheckRequest(List<OrderEvent> orders, List<LoginEvent> logins) {}
    public record CheckResponse(List<FraudAlert> alerts) {}
}
