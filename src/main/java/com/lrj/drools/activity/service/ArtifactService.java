package com.lrj.drools.activity.service;

import com.lrj.drools.activity.domain.ConditionNode;
import com.lrj.drools.activity.domain.SchemaField;
import com.lrj.drools.activity.engine.ActivityRuleRuntimeService;
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
 * <p><b>发布预热（{@link #warmOnPublish}）</b>：上线时按 artifact 冻结的 DRL 在独立编译池**异步预热**（冷编译不落决策热路径）。
 * <p><b>硬失效（{@link #revalidateOnSchemaChange}）</b>：schema 删字段/改类型 → 引用该字段的 ACTIVE artifact 标 NEEDS_REBUILD，
 * 不静默用旧 pin 继续跑（P1-9：改 schema 后旧 artifact 行为可预测、不静默改金额）。
 */
@Service
public class ArtifactService {

    private static final Logger log = LoggerFactory.getLogger(ArtifactService.class);

    private final ActivityArtifactRepository artifactRepo;
    private final RuleSchemaRegistry schemaRegistry;
    private final ActivityRuleRuntimeService ruleRuntime;

    public ArtifactService(ActivityArtifactRepository artifactRepo, RuleSchemaRegistry schemaRegistry,
                           ActivityRuleRuntimeService ruleRuntime) {
        this.artifactRepo = artifactRepo;
        this.schemaRegistry = schemaRegistry;
        this.ruleRuntime = ruleRuntime;
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

    /** 发布(上线)时按 artifact 冻结的 DRL 异步预热（P0-5）。NEEDS_REBUILD 的不预热（避免暖旧规则）。 */
    public void warmOnPublish(String activityId, Integer version) {
        artifactRepo.findFirstByActivityIdAndVersion(activityId, version).ifPresent(a -> {
            if (ActivityArtifactEntity.NEEDS_REBUILD.equals(a.getStatus())) {
                log.warn("artifact {} v{} 已 NEEDS_REBUILD，跳过预热（schema 漂移，待重建）", activityId, version);
                return;
            }
            if (a.getEligDrl() != null && !a.getEligDrl().isBlank()) {
                ruleRuntime.warmAsync(a.getTenantId() != null ? a.getTenantId() : TenantContext.get(), a.getEligDrl());
            }
        });
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
