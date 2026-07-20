package com.lrj.drools.activity;

import com.lrj.drools.activity.domain.ActivityCreateRequest;
import com.lrj.drools.activity.domain.ConditionNode;
import com.lrj.drools.activity.domain.SchemaField;
import com.lrj.drools.activity.engine.RuleSchemaRegistry;
import com.lrj.drools.activity.persistence.ActivityArtifactEntity;
import com.lrj.drools.activity.persistence.ActivityArtifactRepository;
import com.lrj.drools.activity.service.ActivityMarketingService;
import com.lrj.drools.activity.service.ActivityMarketingService.CreateResult;
import com.lrj.drools.activity.service.ArtifactService;
import com.lrj.drools.activity.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-9 · artifact 冻结 + pinned-schema 硬失效：
 *   - 创建活动 → 冻结不可变 artifact（ACTIVE，pin schema 版本 + 引用字段）；
 *   - schema 删掉被引用字段 → {@code revalidateOnSchemaChange} 把该 artifact 标 NEEDS_REBUILD（不静默沿用旧 pin）；
 *   - 不引用被删字段的 artifact 保持 ACTIVE。
 */
@SpringBootTest
@ActiveProfiles("h2")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:artifact;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "activity.marketing.rule-engine.enabled=true",
        "activity.marketing.seed-demo-data=false",
        "activity.tenant.dev-default-enabled=true"
})
class ActivityArtifactTest {

    @Autowired ActivityMarketingService marketing;
    @Autowired ArtifactService artifactService;
    @Autowired ActivityArtifactRepository artifactRepo;
    @Autowired RuleSchemaRegistry schemaRegistry;

    private static final String BIZ = "artbiz";

    @AfterEach
    void clear() { TenantContext.clear(); }

    @Test
    void snapshotPinsSchema_thenHardInvalidatesOnFieldRemoval() {
        TenantContext.set("acme");
        // 1) 建带资格条件(引用 orderAmount)的活动 → 冻结 artifact
        CreateResult a = marketing.create(reqWithCond("artifact-pin", 8901L,
                leaf("orderAmount", "ge", 100)));
        ActivityArtifactEntity art = artifactRepo.findFirstByActivityIdAndVersion(a.activityId(), 1).orElseThrow();
        assertEquals(ActivityArtifactEntity.ACTIVE, art.getStatus());
        assertTrue(art.getReferencedFields().contains("orderAmount:NUMBER"),
                "artifact 应 pin 引用字段+类型：" + art.getReferencedFields());
        String pinnedVersion = art.getSchemaVersion();

        // 2) 改 (acme, artbiz) schema：删掉 orderAmount → schema 版本变
        List<SchemaField> without = schemaRegistry.defaultFields().stream()
                .filter(f -> !f.key().equals("orderAmount")).toList();
        schemaRegistry.register("acme", BIZ, without);
        assertNotEquals(pinnedVersion, schemaRegistry.schemaVersion("acme", BIZ), "删字段后 schema 版本应变");

        // 3) 复检 → 引用 orderAmount 的 artifact 硬失效
        int invalidated = artifactService.revalidateOnSchemaChange(BIZ);
        assertEquals(1, invalidated, "引用被删字段的 artifact 应被标 NEEDS_REBUILD");
        assertEquals(ActivityArtifactEntity.NEEDS_REBUILD,
                artifactRepo.findFirstByActivityIdAndVersion(a.activityId(), 1).orElseThrow().getStatus(),
                "P1-9：改 schema 后旧 artifact 状态可预测(NEEDS_REBUILD)，不静默沿用旧 pin");
    }

    @Test
    void artifactWithoutRemovedField_staysActive() {
        TenantContext.set("beta");
        // 不带资格条件（无引用字段）→ 删任何字段都不失配
        CreateResult a = marketing.create(reqWithCond("artifact-noref", 8902L, null));
        assertEquals(ActivityArtifactEntity.ACTIVE,
                artifactRepo.findFirstByActivityIdAndVersion(a.activityId(), 1).orElseThrow().getStatus());

        List<SchemaField> without = schemaRegistry.defaultFields().stream()
                .filter(f -> !f.key().equals("orderAmount")).toList();
        schemaRegistry.register("beta", BIZ, without);
        int invalidated = artifactService.revalidateOnSchemaChange(BIZ);
        assertEquals(0, invalidated, "无引用字段的 artifact 不受删字段影响");
        assertEquals(ActivityArtifactEntity.ACTIVE,
                artifactRepo.findFirstByActivityIdAndVersion(a.activityId(), 1).orElseThrow().getStatus());
    }

    // ---- helpers ----
    private ConditionNode leaf(String field, String op, Object value) {
        ConditionNode n = new ConditionNode();
        n.setField(field); n.setOp(op); n.setValue(value);
        return n;
    }

    private ActivityCreateRequest reqWithCond(String name, Long spuId, ConditionNode cond) {
        long hAgo = System.currentTimeMillis() - 3_600_000L;
        long hLater = System.currentTimeMillis() + 3_600_000L;
        return new ActivityCreateRequest(
                null, null, name, BIZ, 1, name,
                hAgo, hLater, 1, null, 1, 100,
                1, new BigDecimal("50"), "元", null, "MAX",
                cond, List.of(new ActivityCreateRequest.SpuBinding(1, spuId)), null, null);
    }
}
