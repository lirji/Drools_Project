package com.lrj.drools.service;

import com.lrj.drools.domain.Alert;
import com.lrj.drools.domain.Sensor;
import org.kie.api.runtime.KieContainer;
import org.kie.api.runtime.KieSession;
import org.kie.api.runtime.rule.FactHandle;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Step 12: Truth Maintenance System 对照展示。
 *
 * 一次请求里在两个 kbase 各跑一遍同样的"先超阈值 → 再回落"流水线, 让调用方直观
 * 看到 insertLogical 和普通 insert 在"前提-结论"撤销上的差别:
 *
 *   阶段 1: insert Sensor(value=hotValue, 默认 95) → fireAllRules
 *           两个 kbase 都会触发 HIGH + CRITICAL 两条规则, 派生出 2 个 Alert
 *
 *   阶段 2: modify Sensor 把 value 改成 coolValue (默认 50) → fireAllRules
 *           - logical kbase: 两个 Alert 因为前提失配, 被引擎自动 retract
 *           - regular kbase: 两个 Alert 依然在 working memory, 因为普通 insert 跟前提解耦
 *
 * 没有 try-with-resources 因为 KieSession 不是 AutoCloseable; 用经典 try/finally + dispose。
 */
@Service
public class TmsService {

    private final KieContainer kieContainer;

    public TmsService(KieContainer kieContainer) {
        this.kieContainer = kieContainer;
    }

    public ComparisonResult compare(String sensorName, double hotValue, double coolValue) {
        Phase logical = runPhases("tmsLogicalSession", sensorName, hotValue, coolValue);
        Phase regular = runPhases("tmsRegularSession", sensorName, hotValue, coolValue);
        return new ComparisonResult(hotValue, coolValue, logical, regular);
    }

    private Phase runPhases(String sessionName, String sensorName, double hotValue, double coolValue) {
        KieSession session = kieContainer.newKieSession(sessionName);
        try {
            Sensor sensor = new Sensor(sensorName, hotValue);
            // 拿 FactHandle 是为了阶段 2 用 session.update(handle, sensor) 通知引擎字段变了。
            // 也可以走 DRL 内的 modify, 但这里改值发生在 Java 侧, 所以 update 更直白。
            FactHandle handle = session.insert(sensor);

            int phase1Fired = session.fireAllRules();
            List<Alert> phase1Alerts = collectAlerts(session);

            sensor.setValue(coolValue);
            session.update(handle, sensor);

            int phase2Fired = session.fireAllRules();
            List<Alert> phase2Alerts = collectAlerts(session);

            return new Phase(phase1Fired, phase1Alerts, phase2Fired, phase2Alerts);
        } finally {
            session.dispose();
        }
    }

    private List<Alert> collectAlerts(KieSession session) {
        return session.getObjects().stream()
                .filter(Alert.class::isInstance)
                .map(Alert.class::cast)
                .toList();
    }

    public record ComparisonResult(
            double hotValue,
            double coolValue,
            Phase logical,
            Phase regular
    ) {}

    public record Phase(
            int phase1FiredCount,
            List<Alert> phase1Alerts,
            int phase2FiredCount,
            List<Alert> phase2Alerts
    ) {}
}
