package com.lrj.drools.activity;

import com.lrj.drools.activity.domain.ActivityCreateRequest;
import com.lrj.drools.activity.domain.ConditionNode;
import com.lrj.drools.activity.persistence.ActivityManageRepository;
import com.lrj.drools.activity.service.ActivityMarketingService;
import com.lrj.drools.activity.service.ActivityMarketingService.CreateResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 边界/异常路径测试：非法条件不落库（事务/顺序保证）、幂等、版本化完整性。
 */
@SpringBootTest
@ActiveProfiles("h2")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:actedge;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "activity.marketing.rule-engine.enabled=true",
        "activity.marketing.seed-catalog-data=false"
})
class ActivityMarketingEdgeTest {

    @Autowired ActivityMarketingService marketing;
    @Autowired ActivityManageRepository manageRepo;

    private long hAgo() { return System.currentTimeMillis() - 3_600_000L; }
    private long hLater() { return System.currentTimeMillis() + 3_600_000L; }

    /** 资格条件用非白名单字段 → 翻译期抛错，且不落任何 manage 行（写库前已失败）。 */
    @Test
    void invalidConditionRejectedNoPartialWrite() {
        long before = manageRepo.count();
        ConditionNode bad = leaf("nonexistentField", "eq", "1");
        ActivityCreateRequest req = red("非法条件活动", new BigDecimal("10"), bad, 7001L, null, null);

        assertThrows(IllegalArgumentException.class, () -> marketing.create(req));
        assertEquals(before, manageRepo.count(), "非法条件不应落任何 manage 行");
    }

    /** 同 requestId 重复提交返回首次结果，不新增版本。 */
    @Test
    void idempotentSameRequestId() {
        ActivityCreateRequest req = red("幂等活动", new BigDecimal("15"), null, 7002L, null, "idem-key-1");
        CreateResult r1 = marketing.create(req);
        long after1 = manageRepo.count();

        CreateResult r2 = marketing.create(req);
        assertTrue(r2.idempotentHit(), "第二次同 requestId 应命中幂等");
        assertEquals(r1.activityId(), r2.activityId());
        assertEquals(after1, manageRepo.count(), "幂等重复提交不应新增行");
    }

    /** 版本化编辑：新版 version+1，旧版逻辑删除、新版存活。 */
    @Test
    void versionEditIntegrity() {
        CreateResult r1 = marketing.create(red("可编辑", new BigDecimal("10"), null, 7003L, null, null));
        String id = r1.activityId();
        CreateResult r2 = marketing.updateByVersion(red("可编辑v2", new BigDecimal("20"), null, 7003L, id, null));

        assertEquals(2, r2.version());
        assertTrue(manageRepo.findFirstByActivityIdAndVersionAndIsDel(id, 1, 0).isEmpty(), "旧版本 v1 应被逻辑删除");
        assertTrue(manageRepo.findFirstByActivityIdAndVersionAndIsDel(id, 2, 0).isPresent(), "新版本 v2 应存活");
    }

    /**
     * D12-3：库存是**声明式**的——存得下、决策不读取。创建响应必须显式回 warnings。
     *
     * <p>沉默才是最危险的：运营配了「秒杀总量 500」以为生效，线上却无限超发，
     * 而界面和 API 都不提示。本轮不做预占（量级接近整个 S 档），但必须把这个落差说出来。
     */
    @Test
    void declarativeInventoryIsWarnedNotSilentlyIgnored() {
        CreateResult withInv = marketing.create(red("带库存", new BigDecimal("10"), null, 7009L, null, null));
        assertTrue(withInv.warnings().stream().anyMatch(w -> w.contains("声明式")),
                "配了库存必须回 warnings 说明它不生效，实得: " + withInv.warnings());
        assertTrue(withInv.warnings().stream().anyMatch(w -> w.contains("不扣减")),
                "warnings 要说清楚是『不扣减』，不能只说『暂不支持』");
    }

    // ---- helpers ----
    private ConditionNode leaf(String field, String op, Object value) {
        ConditionNode n = new ConditionNode();
        n.setField(field); n.setOp(op); n.setValue(value);
        return n;
    }

    private ActivityCreateRequest red(String name, BigDecimal amount, ConditionNode cond,
                                      Long spuId, String editId, String requestId) {
        return new ActivityCreateRequest(
                requestId, editId, name, "edge", 1, name,
                hAgo(), hLater(), 1, null, 1, 100,
                1, amount, "元", null, "MAX",
                cond,
                List.of(new ActivityCreateRequest.SpuBinding(1, spuId)),
                null, null);
    }
}
