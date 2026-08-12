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
 *
 * <p><b>为什么一个桶只有一个 map entry</b>（R16）：current 与 previous 曾是两张
 * {@link ConcurrentHashMap}，于是「切当前代」与「移交上一代」是两条独立语句——中间存在一个
 * 两张表互相矛盾的窗口。现在一个桶就是一个不可变的 {@link SnapshotSlot}，靠
 * {@link ConcurrentHashMap#compute} 一次原子替换，读侧永远看到自洽的 (current, previous) 组合。
 * 读路径只看 {@code current}，决策语义与拆成两张表时完全一致。
 *
 * <p><b>为什么保留上一代</b>：回滚（评估报告 D11「无回滚原语」）。发布出事时
 * {@link #rollback} 把指针切回上一代即可生效，不需要反向再发一次、也不需要重启。
 * 只保留一代——再多就是为不存在的需求占内存；真需要多代并存（A/B 载体）时，
 * 这里的数据结构已经支持，加一个按桶选代的读方法即可。
 */
@Component
public class DecisionSnapshotStore {

    private static final Logger log = LoggerFactory.getLogger(DecisionSnapshotStore.class);

    /**
     * 一个桶的完整状态。{@code current} 非空；{@code previous} 为 null 表示「没有可回滚的上一代」。
     * 不可变 + 整体替换 = 读侧不可能看到「已切当前代但还没移交上一代」的中间态。
     */
    private record SnapshotSlot(DecisionSnapshot current, DecisionSnapshot previous) {}

    private final Map<String, SnapshotSlot> slots = new ConcurrentHashMap<>();

    /**
     * 原子切指针。返回被替换掉的上一代（可能为 null）。
     *
     * <p><b>只有代际前进时才移交回滚槽位</b>：{@code GenerationWarmService} 在预热失败时不更新
     * {@code lastSeen}，下一轮会对**同一代际**再发一次。若同代重发也占槽位，previous 就被挤成
     * 「同一代的旧副本」——{@link #rollback} 从此是空转（退到的还是出事的这一代）。
     * 同代重复 publish 只替换 current，回滚槽位原样保留。
     */
    public DecisionSnapshot publish(DecisionSnapshot snapshot) {
        String k = key(snapshot.tenant(), snapshot.bizLine());
        DecisionSnapshot[] before = new DecisionSnapshot[1];
        boolean[] advanced = new boolean[1];
        slots.compute(k, (key, slot) -> {
            DecisionSnapshot old = slot == null ? null : slot.current();
            before[0] = old;
            // 代际前进才把当前代让给回滚槽位；同代重发（预热失败后的重试）不动它。
            advanced[0] = old != null && snapshot.generation() > old.generation();
            DecisionSnapshot prev = advanced[0] ? old : (slot == null ? null : slot.previous());
            return new SnapshotSlot(snapshot, prev);
        });
        DecisionSnapshot old = before[0];
        log.info("[snapshot] 切换 tenant={} bizLine={} generation={}→{} 活动数={}",
                snapshot.tenant(), snapshot.bizLine(),
                old == null ? "-" : old.generation(), snapshot.generation(), snapshot.activityCount());
        if (old != null && !advanced[0]) {
            log.info("[snapshot] 同代重发 tenant={} bizLine={} generation={}：回滚槽位保持不变",
                    snapshot.tenant(), snapshot.bizLine(), snapshot.generation());
        }
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
        DecisionSnapshot[] before = new DecisionSnapshot[1];
        slots.compute(k, (key, slot) -> {
            before[0] = slot == null ? null : slot.current();
            return new SnapshotSlot(snapshot, slot == null ? null : slot.previous());
        });
        DecisionSnapshot old = before[0];
        log.info("[snapshot] 兜底重建 tenant={} bizLine={} generation={} 活动数={}（上一代指针不变，builtAt={}→{}）",
                snapshot.tenant(), snapshot.bizLine(), snapshot.generation(), snapshot.activityCount(),
                old == null ? "-" : old.builtAt(), snapshot.builtAt());
    }

    /** 回滚到上一代。没有上一代时返回 false（调用方据此提示"无可回滚的版本"）。 */
    public boolean rollback(String tenant, String bizLine) {
        String k = key(tenant, bizLine);
        SnapshotSlot[] before = new SnapshotSlot[1];
        slots.computeIfPresent(k, (key, slot) -> {
            before[0] = slot;
            // 只保留一代：回滚后 previous 清空（再回滚一次必须失败，而不是静默成功）
            return slot.previous() == null ? slot : new SnapshotSlot(slot.previous(), null);
        });
        SnapshotSlot old = before[0];
        if (old == null || old.previous() == null) {
            log.warn("[snapshot] 回滚失败：tenant={} bizLine={} 没有上一代快照", tenant, bizLine);
            return false;
        }
        log.warn("[snapshot] 已回滚 tenant={} bizLine={} generation={}→{}",
                tenant, bizLine, old.current().generation(), old.previous().generation());
        return true;
    }

    /** 该租户当前所有业务线的快照。决策入参只有 SPU、不带 bizLine，故按租户取全部再合并。 */
    public List<DecisionSnapshot> forTenant(String tenant) {
        if (tenant == null) return List.of();
        String prefix = tenant + "|";
        List<DecisionSnapshot> out = new ArrayList<>();
        slots.forEach((k, v) -> { if (k.startsWith(prefix)) out.add(v.current()); });
        return out;
    }

    public DecisionSnapshot get(String tenant, String bizLine) {
        SnapshotSlot slot = slots.get(key(tenant, bizLine));
        return slot == null ? null : slot.current();
    }

    /**
     * 当前全部快照（跨租户）。供**兜底陈旧扫描**与快照可观测性指标使用——两者都要看
     * 「所有桶里最旧的那个有多旧」，而调度线程与指标线程都没有租户上下文。
     */
    public List<DecisionSnapshot> all() {
        List<DecisionSnapshot> out = new ArrayList<>(slots.size());
        slots.values().forEach(s -> out.add(s.current()));
        return List.copyOf(out);
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
        for (SnapshotSlot slot : slots.values()) {
            double age = java.time.Duration.between(slot.current().builtAt(), now).toMillis() / 1000.0;
            if (age > worst) worst = age;
        }
        return worst;
    }

    public int size() { return slots.size(); }

    /** 测试用：清空（避免用例间互相看到对方的快照）。 */
    public void clear() { slots.clear(); }

    private static String key(String tenant, String bizLine) {
        return tenant + "|" + bizLine;
    }
}
