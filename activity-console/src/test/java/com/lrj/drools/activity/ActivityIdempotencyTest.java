package com.lrj.drools.activity;

import com.lrj.drools.activity.domain.ActivityCreateRequest;
import com.lrj.drools.activity.service.ActivityMarketingService;
import com.lrj.drools.activity.service.ActivityMarketingService.CreateResult;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 幂等 / 输入校验（codex-test ISSUE-05/06）：
 *   - 空白 requestId 归一 null → 两次普通创建不因空白键互撞 409；
 *   - 合法 requestId 去重返回首次结果；
 *   - 超长名 → 400（IllegalArgumentException），不被误判成"并发重复 requestId"。
 */
@SpringBootTest
@ActiveProfiles("h2")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:idemp;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "activity.marketing.seed-catalog-data=false",
        "activity.tenant.dev-default-enabled=true"
})
class ActivityIdempotencyTest {

    @Autowired ActivityMarketingService marketing;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private ActivityCreateRequest req(String requestId, String name, Long spuId) {
        long hAgo = System.currentTimeMillis() - 3_600_000L;
        long hLater = System.currentTimeMillis() + 3_600_000L;
        return new ActivityCreateRequest(
                requestId, null, name, "mall", 1, name,
                hAgo, hLater, 1, null, 1, 100,
                1, new BigDecimal("50"), "元", null, "MAX",
                null, List.of(new ActivityCreateRequest.SpuBinding(1, spuId)), null, null);
    }

    @Test
    void blankRequestId_twoCreatesOk() {
        CreateResult a = marketing.create(req(" ", "空白req1", 95001L));
        CreateResult b = marketing.create(req("  ", "空白req2", 95002L));
        assertNotEquals(a.activityId(), b.activityId(), "空白 requestId 归一 null，两次应各建各的");
        assertFalse(a.idempotentHit());
        assertFalse(b.idempotentHit());
    }

    @Test
    void validRequestId_dedup() {
        CreateResult a = marketing.create(req("K-100", "幂等1", 95101L));
        CreateResult b = marketing.create(req("K-100", "幂等2", 95102L));
        assertEquals(a.activityId(), b.activityId(), "同 requestId 返回首次结果");
        assertTrue(b.idempotentHit());
    }

    @Test
    void overlongName_badRequestNotDuplicate() {
        String longName = "x".repeat(200);
        // 应是 400(IllegalArgumentException 长度校验)，而非误判成并发重复的 IllegalStateException(409)
        assertThrows(IllegalArgumentException.class, () -> marketing.create(req(null, longName, 95201L)));
    }

    /** ISSUE-07：编辑重放幂等——独立幂等表让编辑（version+1）也能顺序重放返回首次结果，不再无限 version+1。 */
    @Test
    void editReplay_idempotent_noNewVersion() {
        CreateResult v1 = marketing.create(req(null, "编辑幂等-base", 96001L));
        assertEquals(1, v1.version().intValue());

        // 首次编辑（带 requestId "E-1"）→ v2
        CreateResult e1 = marketing.create(editReq("E-1", "编辑幂等-改1", 96001L, v1.activityId()));
        assertEquals(v1.activityId(), e1.activityId());
        assertEquals(2, e1.version().intValue(), "首次编辑应 v2");
        assertFalse(e1.idempotentHit());

        // 重放同一编辑 requestId → 幂等命中，返回 v2，绝不 v3（原实现会 version+1）
        CreateResult e2 = marketing.create(editReq("E-1", "编辑幂等-改2(应被忽略)", 96001L, v1.activityId()));
        assertTrue(e2.idempotentHit(), "编辑重放应幂等命中（ISSUE-07）");
        assertEquals(2, e2.version().intValue(), "重放不产生新版本，仍 v2");
        assertEquals(2, marketing.getDetail(v1.activityId()).manage().getVersion().intValue(),
                "库里当前生效版本仍是 v2");
    }

    private ActivityCreateRequest editReq(String requestId, String name, Long spuId, String activityId) {
        long hAgo = System.currentTimeMillis() - 3_600_000L;
        long hLater = System.currentTimeMillis() + 3_600_000L;
        return new ActivityCreateRequest(
                requestId, activityId, name, "mall", 1, name,
                hAgo, hLater, 1, null, 1, 100,
                1, new BigDecimal("50"), "元", null, "MAX",
                null, List.of(new ActivityCreateRequest.SpuBinding(1, spuId)), null, null);
    }
}
