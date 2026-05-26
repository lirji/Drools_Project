package com.lrj.drools.service;

import com.lrj.drools.domain.BurstAlert;
import com.lrj.drools.domain.OrderEvent;
import org.kie.api.runtime.KieContainer;
import org.kie.api.runtime.KieSession;
import org.kie.api.time.SessionPseudoClock;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Step 8 CEP 驱动逻辑。
 *
 * 关键模式: 用 pseudo clock 重放事件序列。
 *
 *   for each event (按时间戳排序):
 *       clock.advanceTime(event.timestamp - now)   // 推进时钟到事件时刻
 *       session.insert(event)
 *       session.fireAllRules()                      // 立刻评估当前时刻的滑窗
 *
 * 为什么每个事件后都要 fireAllRules?
 *   - stream mode 下事件 insert 不立刻评估, 攒到 fire 才算
 *   - 不立刻 fire, 后面 advanceTime 时之前事件已经过期会被 retract, 漏告警
 *   - 业务语义对齐"事件到达 → 实时风控" 的实时性要求
 */
@Service
public class FraudService {

    private final KieContainer kieContainer;

    public FraudService(KieContainer kieContainer) {
        this.kieContainer = kieContainer;
    }

    public List<BurstAlert> check(List<OrderEvent> events) {
        KieSession session = kieContainer.newKieSession("fraudSession");
        SessionPseudoClock clock = session.getSessionClock();
        try {
            // 按事件时间戳排序, 否则 stream mode 会报"事件时间倒退"或行为异常
            List<OrderEvent> sorted = new ArrayList<>(events);
            sorted.sort(Comparator.comparingLong(OrderEvent::timestamp));

            for (OrderEvent e : sorted) {
                long delta = e.timestamp() - clock.getCurrentTime();
                if (delta > 0) {
                    clock.advanceTime(delta, TimeUnit.MILLISECONDS);
                }
                session.insert(e);
                session.fireAllRules();
            }

            List<BurstAlert> alerts = new ArrayList<>();
            for (Object obj : session.getObjects()) {
                if (obj instanceof BurstAlert alert) {
                    alerts.add(alert);
                }
            }
            return alerts;
        } finally {
            session.dispose();
        }
    }
}
