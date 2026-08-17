package com.lrj.drools.service;

import com.lrj.drools.domain.FraudAlert;
import com.lrj.drools.domain.LoginEvent;
import com.lrj.drools.domain.OrderEvent;
import org.kie.api.runtime.KieContainer;
import org.kie.api.runtime.KieSession;
import org.kie.api.runtime.rule.EntryPoint;
import org.kie.api.time.SessionPseudoClock;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Step 8 扩展 (CEP 补完) 驱动逻辑: 长度滑窗 + 时序操作符 + 多 entry-point。
 *
 * 跟 FraudService 一样用 pseudo clock 重放事件, 但有两点不同:
 *   1. 两类事件 (OrderEvent / LoginEvent) 走**各自的 entry-point**, 用
 *      session.getEntryPoint(name).insert(...) 而不是 session.insert(...)
 *   2. 两条流必须**按时间戳合并成一条时间线**再逐个推进时钟, 否则跨流的时序操作符
 *      (after[..]) 会因为时钟乱序而失配
 *
 * 告警 FraudAlert 由 RHS `insert(...)` 落在**默认 entry-point**, 所以能被 getObjects()
 * 捞到 (getObjects 只看默认 entry-point, 不返回具名流里的原始事件)。
 */
@Service
public class FraudCepService {

    private final KieContainer kieContainer;

    public FraudCepService(KieContainer kieContainer) {
        this.kieContainer = kieContainer;
    }

    public List<FraudAlert> check(List<OrderEvent> orders, List<LoginEvent> logins) {
        KieSession session = kieContainer.newKieSession("fraudCepSession");
        SessionPseudoClock clock = session.getSessionClock();
        EntryPoint orderStream = session.getEntryPoint("order-stream");
        EntryPoint loginStream = session.getEntryPoint("login-stream");
        try {
            // 把两条流合并成一条按时间戳排序的时间线; 每个元素带一个"往对应 entry-point
            // insert 自己"的动作。推进时钟到该时刻后再 insert + fire。
            List<Timed> timeline = new ArrayList<>();
            if (orders != null) {
                for (OrderEvent o : orders) {
                    timeline.add(new Timed(o.timestamp(), () -> orderStream.insert(o)));
                }
            }
            if (logins != null) {
                for (LoginEvent l : logins) {
                    timeline.add(new Timed(l.timestamp(), () -> loginStream.insert(l)));
                }
            }
            timeline.sort(Comparator.comparingLong(Timed::ts));

            for (Timed t : timeline) {
                long delta = t.ts() - clock.getCurrentTime();
                if (delta > 0) {
                    clock.advanceTime(delta, TimeUnit.MILLISECONDS);
                }
                t.action().run();
                session.fireAllRules();
            }

            List<FraudAlert> alerts = new ArrayList<>();
            for (Object obj : session.getObjects()) {
                if (obj instanceof FraudAlert alert) {
                    alerts.add(alert);
                }
            }
            return alerts;
        } finally {
            session.dispose();
        }
    }

    /** 时间线上的一个投递动作: ts = 事件时间戳, action = 往对应 entry-point insert。 */
    private record Timed(long ts, Runnable action) {}
}
