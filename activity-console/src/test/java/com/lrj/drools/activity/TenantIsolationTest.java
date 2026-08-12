package com.lrj.drools.activity;

import com.lrj.drools.activity.domain.ActivityCreateRequest;
import com.lrj.drools.activity.domain.ActivityStatus;
import com.lrj.drools.activity.domain.DecisionMode;
import com.lrj.drools.activity.domain.SpuDiscountRequest;
import com.lrj.drools.activity.persistence.ActivityManageRepository;
import com.lrj.drools.activity.service.ActivityMarketingService;
import com.lrj.drools.activity.service.ActivityMarketingService.CreateResult;
import com.lrj.drools.activity.service.ActivityQueryService;
import com.lrj.drools.activity.service.ActivityQueryService.DiscountView;
import com.lrj.drools.activity.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-4 租户隔离机制的**端到端证明**（H2 内存库）。
 *
 * 隔离靠机制不靠纪律：实体加了 {@code @TenantId}，Hibernate 对每条 SQL 自动追加 {@code tenant_id = ?}。
 * 本测试显式切 {@link TenantContext} 到 tenantA / tenantB，证明：
 *   1) 写入自动打租户标签（不手动 set）；
 *   2) 列表/详情读被租户过滤（B 看不到 A 的活动）；
 *   3) 真实优惠决策读路径（SPU 绑定 → 生效活动）也被隔离（B 查 A 的 SPU 无命中）。
 *
 * dev-default 开着只影响"无上下文"时的兜底；本测试每步都显式设租户，故兜底不参与断言。
 */
@SpringBootTest
@ActiveProfiles("h2")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:tenantiso;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "activity.marketing.rule-engine.enabled=true",
        "activity.marketing.seed-demo-data=false",
        "activity.tenant.dev-default-enabled=true"
})
class TenantIsolationTest {

    private static final String TENANT_A = "tenantA";
    private static final String TENANT_B = "tenantB";

    @Autowired ActivityMarketingService marketing;
    @Autowired ActivityQueryService query;
    @Autowired ActivityManageRepository manageRepo;
    @Autowired org.springframework.transaction.PlatformTransactionManager txm;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    /** 写入自动打租户标签：create 时不手动 set tenantId，Hibernate 按当前租户落值。 */
    @Test
    void writeAutoTagsTenant() {
        TenantContext.set(TENANT_A);
        CreateResult a = marketing.create(redPackage("A 的红包", "biz-iso", new BigDecimal("50"), 7001L, null));

        var detail = marketing.getDetail(a.activityId());
        assertEquals(TENANT_A, detail.manage().getTenantId(), "create 应自动把行打上当前租户标签");
    }

    /** 列表 + 详情读隔离：B 看不到 A 建的活动。 */
    @Test
    void listAndDetailIsolation() {
        TenantContext.set(TENANT_A);
        CreateResult a = marketing.create(redPackage("A 独占红包", "biz-iso", new BigDecimal("30"), 7101L, null));

        // A 能看到自己的
        assertTrue(marketing.list().stream().anyMatch(m -> m.getActivityId().equals(a.activityId())),
                "A 应能在列表看到自己的活动");
        assertEquals(a.activityId(), marketing.getDetail(a.activityId()).manage().getActivityId());

        // 切到 B：列表看不到 A，详情按不存在处理（租户谓词把 A 的行挡在外面）
        TenantContext.set(TENANT_B);
        assertTrue(marketing.list().stream().noneMatch(m -> m.getActivityId().equals(a.activityId())),
                "B 的列表不应出现 A 的活动");
        assertThrows(IllegalArgumentException.class, () -> marketing.getDetail(a.activityId()),
                "B 查 A 的 activityId 详情应报不存在（fail-closed）");
    }

    /** 决策读路径隔离：A 上线后自己能命中；B 查同一 SPU 无命中（绑定行被租户过滤）。 */
    @Test
    void discountReadPathIsolation() {
        TenantContext.set(TENANT_A);
        CreateResult a = marketing.create(redPackage("A 的 SPU 红包", "biz-iso", new BigDecimal("60"), 7201L, null));
        marketing.changeStatus(a.activityId(), a.version(), ActivityStatus.ONLINE.code());

        DiscountView av = query.spuDiscount(spuReq(7201L, new BigDecimal("100")), DecisionMode.HOT_PATH);
        assertTrue(av.hit(), "A 自己查应命中");
        assertEquals(0, av.hitAmount().compareTo(new BigDecimal("60")));

        TenantContext.set(TENANT_B);
        DiscountView bv = query.spuDiscount(spuReq(7201L, new BigDecimal("100")), DecisionMode.HOT_PATH);
        assertFalse(bv.hit(), "B 查 A 的 SPU 不应命中（跨租户隔离）");
    }

    /** 跨租户写：B 用 A 的 activityId 编辑（走 softDeleteVersion bulk update）→ fail-closed，A 不被篡改。 */
    @Test
    void crossTenantEditFailsClosed() {
        TenantContext.set(TENANT_A);
        CreateResult a = marketing.create(redPackage("A 可编辑", "biz-iso", new BigDecimal("30"), 7301L, null));

        TenantContext.set(TENANT_B);
        ActivityCreateRequest edit = redPackage("B 篡改", "biz-iso", new BigDecimal("999"), 7301L, a.activityId());
        assertThrows(IllegalArgumentException.class, () -> marketing.updateByVersion(edit),
                "B 编辑 A 的活动应 fail-closed（看不到 → 不存在）");

        TenantContext.set(TENANT_A);
        var d = marketing.getDetail(a.activityId());
        assertEquals(1, d.manage().getVersion(), "A 的活动版本不应被 B 的编辑推进");
    }

    /** bulk JPQL update 也必须租户隔离：B 直接对 A 的 activityId 跑 softDeleteVersion 应影响 0 行。 */
    @Test
    void bulkUpdateIsTenantScoped() {
        TenantContext.set(TENANT_A);
        CreateResult a = marketing.create(redPackage("A bulk", "biz-iso", new BigDecimal("10"), 7501L, null));

        TenantContext.set(TENANT_B);
        var tt = new org.springframework.transaction.support.TransactionTemplate(txm);
        int affected = tt.execute(s -> manageRepo.softDeleteVersion(a.activityId(), a.version(), Instant.now()));
        assertEquals(0, affected, "bulk update 必须租户隔离：B 不应 soft-delete A 的行");

        TenantContext.set(TENANT_A);
        assertEquals(a.activityId(), marketing.getDetail(a.activityId()).manage().getActivityId(),
                "A 的行不应被 B 的 bulk update 删除");
    }

    /** 跨租户写：B 改 A 的活动上下线状态 → fail-closed。 */
    @Test
    void crossTenantChangeStatusFailsClosed() {
        TenantContext.set(TENANT_A);
        CreateResult a = marketing.create(redPackage("A 状态", "biz-iso", new BigDecimal("20"), 7401L, null));

        TenantContext.set(TENANT_B);
        assertThrows(IllegalArgumentException.class,
                () -> marketing.changeStatus(a.activityId(), a.version(), ActivityStatus.ONLINE.code()),
                "B 改 A 的活动状态应 fail-closed");
    }

    // ------------------------------------------------------------------ helper

    private ActivityCreateRequest redPackage(String name, String bizLine, BigDecimal amount,
                                             Long spuId, String editActivityId) {
        long hAgo = System.currentTimeMillis() - 3_600_000L;
        long hLater = System.currentTimeMillis() + 3_600_000L;
        return new ActivityCreateRequest(
                null, editActivityId, name, bizLine, 1, name,
                hAgo, hLater, 1, null, 1, 100,
                1, amount, "元", null, "MAX",
                null,
                List.of(new ActivityCreateRequest.SpuBinding(1, spuId)),
                null, null);
    }

    private SpuDiscountRequest spuReq(Long spuId, BigDecimal orderAmount) {
        return new SpuDiscountRequest(List.of(spuId), 1001L, "110000", List.of("vip"), orderAmount, 1);
    }
}
