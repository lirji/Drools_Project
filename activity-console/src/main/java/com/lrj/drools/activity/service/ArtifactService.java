package com.lrj.drools.activity.service;

import com.lrj.drools.activity.domain.ConditionNode;
import com.lrj.drools.activity.domain.SchemaField;
import com.lrj.drools.activity.engine.RuleSchemaRegistry;
import com.lrj.drools.activity.persistence.ActivityArtifactEntity;
import com.lrj.drools.activity.persistence.ActivityArtifactRepository;
import com.lrj.drools.activity.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P1-9 · artifact 冻结/失效 + P0-5 发布预热的落点。
 *
 * <p><b>冻结（{@link #snapshot}）</b>：活动某版本创建时，把它的资格 DRL + **pin 的 schema 版本 / 引用字段**固化成不可变 artifact。
 * <p><b>发布传播（{@link #onPublish}）</b>：上线时 bump {@code (tenant,bizLine)} 发布代际，供 decision 侧轮询预热（M1.4）。
 * <p><b>硬失效（{@link #revalidateOnSchemaChange}）</b>：schema 删字段/改类型 → 引用该字段的 ACTIVE artifact 标 NEEDS_REBUILD，
 * 不静默用旧 pin 继续跑（P1-9：改 schema 后旧 artifact 行为可预测、不静默改金额）。
 */
@Service
public class ArtifactService {

    private static final Logger log = LoggerFactory.getLogger(ArtifactService.class);

    private final ActivityArtifactRepository artifactRepo;
    private final RuleSchemaRegistry schemaRegistry;
    private final GenerationService generationService;

    public ArtifactService(ActivityArtifactRepository artifactRepo, RuleSchemaRegistry schemaRegistry,
                           GenerationService generationService) {
        this.artifactRepo = artifactRepo;
        this.schemaRegistry = schemaRegistry;
        this.generationService = generationService;
    }

    /** 冻结活动某版本为不可变 artifact（pin schema 版本 + 引用字段 + 资格 DRL）。幂等：同版本已存在则不重复建。 */
    public void snapshot(String activityId, Integer version, String bizLine, String eligDrl, ConditionNode conditionTree) {
        if (artifactRepo.findFirstByActivityIdAndVersion(activityId, version).isPresent()) {
            return;
        }
        String tenant = TenantContext.get();
        Map<String, String> refFields = referencedFieldTypes(conditionTree, tenant, bizLine);

        ActivityArtifactEntity a = new ActivityArtifactEntity();
        a.setActivityId(activityId);
        a.setVersion(version);
        a.setBizLine(bizLine);
        a.setSchemaVersion(schemaRegistry.schemaVersion(tenant, bizLine)); // pin
        a.setReferencedFields(serialize(refFields));
        a.setEligDrl(eligDrl);
        a.setStatus(ActivityArtifactEntity.ACTIVE);
        a.setCreatedStime(Instant.now());
        artifactRepo.save(a);
    }

    /**
     * 活动状态变化时 bump {@code (tenant,bizLine)} 发布代际——decision 侧唯一的「配置变了」信号。
     *
     * <p><b>任何状态变化都要 bump，不只是上线。</b>此前本方法叫 {@code onPublish}，只在
     * {@code target == ONLINE} 时被调用，于是<b>下线在生产上不生效</b>：运营点下线 → 列表变「已下线」→
     * 控制台试算也说不再命中（console 侧 store 恒空、必走库，看到的是 DB 真相）→ 而 decision 的快照
     * 因为收不到信号，继续按原配置发钱，直到同 bizLine 恰好有别的活动上线、或 decision 进程重启。
     * 止损开关和用来确认止损的仪表盘会一起骗人。这是本链路唯一「错误无法被终止」的缺陷。
     *
     * <p><b>为什么去掉了原来的 NEEDS_REBUILD 早退</b>：那道守卫的本意是「别把坏规则传播出去」，
     * 但它拦错了东西——代际信号驱动的是 decision 侧<b>按数据库真相重建快照</b>
     * （{@code DecisionSnapshotBuilder} 读 activity_manage），artifact 的 {@code eligDrl} 只用于预热，
     * 而预热只取 {@code status = ACTIVE} 的 artifact，NEEDS_REBUILD 的本来就轮不到。
     * 更糟的是它在上线路径上有害：发布 v2 会在同事务里退役 v1，此时跳过 bump 意味着
     * decision 永远停留在「服务已被退役的 v1」。所以一律 bump，只保留告警日志。
     *
     * <p><b>M2.2 已移除进程内直调</b>（原 {@code warmOnPublish} 里的 {@code ruleRuntime.warmAsync}）：物理拆分后
     * console 与 decision 是两个进程，console 就地 warmAsync 只暖<em>自己</em>的缓存、暖不到 decision，属"写平面直连读平面缓存"
     * 的残留耦合。发布预热的唯一路径统一为 <b>代际轮询</b>（decision 侧 {@code GenerationWarmService} 见代际增长即预热）。
     * 本方法在 {@code changeStatus} 的 {@code @Transactional} 内，bump 与状态变更同事务提交。
     * console 自身的 legacy {@code /activity-marketing} 读端点首次命中冷编译（Caffeine single-flight，只慢一次）。
     *
     * @param fallbackBizLine  artifact 行不存在时的兜底业务线（取自 activity_manage 行）
     * @param fallbackTenantId artifact 行不存在时的兜底租户
     */
    public void onStatusChanged(String activityId, Integer version, String fallbackBizLine, String fallbackTenantId) {
        var artifact = artifactRepo.findFirstByActivityIdAndVersion(activityId, version).orElse(null);
        if (artifact != null && ActivityArtifactEntity.NEEDS_REBUILD.equals(artifact.getStatus())) {
            // 仍然 bump（见上）。日志保留：schema 漂移待重建这件事本身需要可见。
            log.warn("artifact {} v{} 处于 NEEDS_REBUILD（schema 漂移，待重建），代际照常 bump 以保证状态变更能传播",
                    activityId, version);
        }
        String tenant = artifact != null && artifact.getTenantId() != null
                ? artifact.getTenantId()
                : (fallbackTenantId != null ? fallbackTenantId : TenantContext.get());
        String bizLine = artifact != null && artifact.getBizLine() != null
                ? artifact.getBizLine()
                : fallbackBizLine;

        // bizLine 为空 → 不 bump，但必须吵一声。
        //
        // <b>为什么不能"照样 bump"</b>：{@code activity_manage.biz_line} 可空，而
        // {@code activity_generation.biz_line} 是 NOT NULL。插 null 会在**同一个事务**里抛非空约束违例，
        // 把刚写下的 activityStatus 一起回滚——于是「下线传播不出去」升级成「下线根本做不到」，
        // 正好和本次修复的目标相反。（改成任何状态都 bump 之前，这条路只在上线时才会走到，
        // 所以一直没暴露；扩到下线就成了新的、更严重的故障。）
        //
        // <b>为什么不能编一个哨兵 bizLine 顶上</b>：{@code DecisionSnapshotBuilder} 按 bizLine 精确匹配收活动，
        // 哨兵桶谁也匹配不上；而拿 null 去构建会让过滤条件短路，变成「该租户所有业务线一锅端」，
        // 再与真实 bizLine 的桶在 {@code DecisionDataLoader.load} 里合并 → 同一个活动被算两遍。
        // 宁可不传播，也不能造出重复候选。
        //
        // 真正的含义是：**没有 bizLine 的活动本来就进不了任何决策快照**，没有代际可传播。
        // 这是快照模型的既有落差，正确的收敛方向是把 bizLine 变成受控必填项（见评审 P0-5），
        // 不是在这里凑一个值。
        if (bizLine == null || bizLine.isBlank()) {
            log.warn("活动 {} v{} 没有 bizLine，跳过发布代际 bump —— 该活动本就不会进入任何决策快照；"
                    + "状态变更本身已提交。要让它可被决策命中，请补 bizLine 后重新发布。", activityId, version);
            return;
        }
        // artifact 行缺失（历史数据 / 冻结失败）也必须 bump——否则这个活动的上下线永远传播不出去。
        generationService.bump(tenant, bizLine);
    }

    /**
     * P1-9 硬失效：当前租户 + bizLine 下所有 ACTIVE artifact，若其引用字段在**当前 live schema** 下被删/改类型 →
     * 标 NEEDS_REBUILD。返回被失效的条数（不静默——调用方/日志可见）。
     */
    public int revalidateOnSchemaChange(String bizLine) {
        List<ActivityArtifactEntity> active = artifactRepo.findByBizLineAndStatus(bizLine, ActivityArtifactEntity.ACTIVE);
        String tenant = TenantContext.get();
        int invalidated = 0;
        for (ActivityArtifactEntity a : active) {
            Map<String, String> pinned = deserialize(a.getReferencedFields());
            if (!pinned.isEmpty() && schemaRegistry.fieldsBrokenAgainstCurrent(tenant, bizLine, pinned)) {
                a.setStatus(ActivityArtifactEntity.NEEDS_REBUILD);
                artifactRepo.save(a);
                invalidated++;
                log.warn("artifact {} v{} 硬失效 NEEDS_REBUILD：引用字段在新 schema 下被删/改类型（pin={}）",
                        a.getActivityId(), a.getVersion(), a.getReferencedFields());
            }
        }
        return invalidated;
    }

    // ---- helpers ----

    /** 递归收集条件树引用的字段 key，映射到其在 live schema 里的类型（缺失则记 UNKNOWN，视为已失配）。 */
    private Map<String, String> referencedFieldTypes(ConditionNode node, String tenant, String bizLine) {
        Map<String, String> out = new LinkedHashMap<>();
        Map<String, SchemaField> schema = schemaRegistry.resolve(tenant, bizLine);
        collect(node, schema, out);
        return out;
    }

    private void collect(ConditionNode node, Map<String, SchemaField> schema, Map<String, String> out) {
        if (node == null) return;
        if (node.isGroup()) {
            if (node.getChildren() != null) {
                for (ConditionNode c : node.getChildren()) collect(c, schema, out);
            }
        } else if (node.getField() != null && !node.getField().isBlank()) {
            SchemaField f = schema.get(node.getField());
            out.put(node.getField(), f != null ? f.valueType().name() : "UNKNOWN");
        }
    }

    private String serialize(Map<String, String> m) {
        if (m.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        m.forEach((k, v) -> sb.append(sb.length() == 0 ? "" : ",").append(k).append(':').append(v));
        return sb.toString();
    }

    private Map<String, String> deserialize(String s) {
        Map<String, String> m = new LinkedHashMap<>();
        if (s == null || s.isBlank()) return m;
        for (String pair : s.split(",")) {
            int i = pair.indexOf(':');
            if (i > 0) m.put(pair.substring(0, i), pair.substring(i + 1));
        }
        return m;
    }
}
