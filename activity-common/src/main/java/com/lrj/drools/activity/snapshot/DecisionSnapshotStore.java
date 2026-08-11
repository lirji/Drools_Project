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

    /**
     * <b>同代刷新</b>：用重新构建的物料替换当前快照，但<b>不动上一代指针</b>。
     *
     * <p>与 {@link #publish} 的区别是「这不是一次发布」。兜底重建（{@code GenerationWarmService} 的
     * 陈旧扫描）用它来自愈——若走 publish，一次兜底重建就会把 previous 槽位挤成「同一代的旧副本」，
     * 于是 {@link #rollback} 回滚到的不再是上一个发布代际，而是几十秒前的自己，回滚等于没回滚。
     * 代际号保持不变，因为配置代际确实没有前进。
     */
    public void refresh(DecisionSnapshot snapshot) {
        String k = key(snapshot.tenant(), snapshot.bizLine());
        DecisionSnapshot old = current.put(k, snapshot);
        log.info("[snapshot] 兜底重建 tenant={} bizLine={} generation={} 活动数={}（上一代指针不变，builtAt={}→{}）",
                snapshot.tenant(), snapshot.bizLine(), snapshot.generation(), snapshot.activityCount(),
                old == null ? "-" : old.builtAt(), snapshot.builtAt());
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

    /**
     * 当前全部快照（跨租户）。供**兜底陈旧扫描**与快照可观测性指标使用——两者都要看
     * 「所有桶里最旧的那个有多旧」，而调度线程与指标线程都没有租户上下文。
     */
    public List<DecisionSnapshot> all() {
        return List.copyOf(current.values());
    }

    /**
     * 全部快照里**最旧的那个**的年龄（秒）。没有快照时返回 -1（与「有快照但很新」区分开）。
     *
     * <p>这是止损可观测性的核心读数：代际信号漏发、轮询线程卡死、构建持续失败——三种故障
     * 都表现为这个数一路涨，而它们在回退率、耗时、命中数上<b>全部看不出来</b>（快照还在，
     * 只是内容过期，决策照常成功）。
     */
    public double oldestAgeSeconds(java.time.Instant now) {
        double worst = -1;
        for (DecisionSnapshot s : current.values()) {
            double age = java.time.Duration.between(s.builtAt(), now).toMillis() / 1000.0;
            if (age > worst) worst = age;
        }
        return worst;
    }

    public int size() { return current.size(); }

    /** 测试用：清空（避免用例间互相看到对方的快照）。 */
    public void clear() { current.clear(); previous.clear(); }

    private static String key(String tenant, String bizLine) {
        return tenant + "|" + bizLine;
    }
}
