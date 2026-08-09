package com.lrj.drools.activity.snapshot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 已就绪快照的持有者（计划 P1-1）。**指针切换发生在这里，且只发生在这里。**
 *
 * <p><b>切换契约</b>：{@link #publish} 只在快照**完全构建并预编译完成后**被调用。
 * 请求线程读到的永远是一个自洽的、可用的快照——不存在"读到一半在建的物料"。
 * {@link ConcurrentHashMap} 的 put 提供了必要的可见性保证。
 *
 * <p><b>为什么保留上一代</b>：回滚（评估报告 D11「无回滚原语」）。发布出事时
 * {@link #rollback} 把指针切回上一代即可生效，不需要反向再发一次、也不需要重启。
 * 只保留一代——再多就是为不存在的需求占内存；真需要多代并存（A/B 载体）时，
 * 这里的数据结构已经支持，加一个按桶选代的读方法即可。
 */
@Component
public class DecisionSnapshotStore {

    private static final Logger log = LoggerFactory.getLogger(DecisionSnapshotStore.class);

    private final Map<String, DecisionSnapshot> current = new ConcurrentHashMap<>();
    private final Map<String, DecisionSnapshot> previous = new ConcurrentHashMap<>();

    /** 原子切指针。返回被替换掉的上一代（可能为 null）。 */
    public DecisionSnapshot publish(DecisionSnapshot snapshot) {
        String k = key(snapshot.tenant(), snapshot.bizLine());
        DecisionSnapshot old = current.put(k, snapshot);
        if (old != null) {
            previous.put(k, old);
        }
        log.info("[snapshot] 切换 tenant={} bizLine={} generation={}→{} 活动数={}",
                snapshot.tenant(), snapshot.bizLine(),
                old == null ? "-" : old.generation(), snapshot.generation(), snapshot.activityCount());
        return old;
    }

    /** 回滚到上一代。没有上一代时返回 false（调用方据此提示"无可回滚的版本"）。 */
    public boolean rollback(String tenant, String bizLine) {
        String k = key(tenant, bizLine);
        DecisionSnapshot prev = previous.remove(k);
        if (prev == null) {
            log.warn("[snapshot] 回滚失败：tenant={} bizLine={} 没有上一代快照", tenant, bizLine);
            return false;
        }
        DecisionSnapshot cur = current.put(k, prev);
        log.warn("[snapshot] 已回滚 tenant={} bizLine={} generation={}→{}",
                tenant, bizLine, cur == null ? "-" : cur.generation(), prev.generation());
        return true;
    }

    /** 该租户当前所有业务线的快照。决策入参只有 SPU、不带 bizLine，故按租户取全部再合并。 */
    public List<DecisionSnapshot> forTenant(String tenant) {
        if (tenant == null) return List.of();
        String prefix = tenant + "|";
        List<DecisionSnapshot> out = new ArrayList<>();
        current.forEach((k, v) -> { if (k.startsWith(prefix)) out.add(v); });
        return out;
    }

    public DecisionSnapshot get(String tenant, String bizLine) {
        return current.get(key(tenant, bizLine));
    }

    public int size() { return current.size(); }

    /** 测试用：清空（避免用例间互相看到对方的快照）。 */
    public void clear() { current.clear(); previous.clear(); }

    private static String key(String tenant, String bizLine) {
        return tenant + "|" + bizLine;
    }
}
