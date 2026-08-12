package com.lrj.drools.activity;

import com.lrj.drools.activity.domain.ActivityCreateRequest;
import com.lrj.drools.activity.domain.ActivityStatus;
import com.lrj.drools.activity.metrics.DecisionMetrics;
import com.lrj.drools.activity.service.ActivityMarketingService;
import com.lrj.drools.activity.service.ActivityMarketingService.CreateResult;
import com.lrj.drools.activity.snapshot.DecisionSnapshot;
import com.lrj.drools.activity.snapshot.DecisionSnapshotBuilder;
import com.lrj.drools.activity.tenant.TenantContext;
import io.micrometer.core.instrument.Counter;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R15 的验收证据：<b>快照构建期</b>的查询次数与活动目录规模无关。
 *
 * <p><b>为什么这条门禁必须存在</b>：热路径早就被 {@code DecisionQueryCountTest} 钉死在 5 次，
 * 而构建期一道门禁都没有——于是它悄悄长成了「捞全租户在线活动 + 每活动一次绑定查询」。
 * 这类开销<b>不随请求量增长、只随活动数增长</b>，压测照不出来（压测跑的是热路径），
 * 它却全打在 decision 那条只读连接上，并且被兜底重建每分钟重跑一遍。
 * 换句话说：没有这个测试，N+1 长回来的那一天不会有任何信号。
 *
 * <p>数法与 {@code DecisionQueryCountTest} 一致——用 Hibernate {@link Statistics} 数真实语句数，
 * 对比 N=1 与 N=10 两次构建。
 */
@SpringBootTest
@ActiveProfiles("h2")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:snapqcount;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "activity.marketing.seed-demo-data=false"
})
@DisplayName("快照构建查询次数：与活动数无关")
class SnapshotBuildQueryCountTest {

    private static final String TENANT = "__dev__";
    private static final AtomicLong SPU = new AtomicLong(910_000L);

    /**
     * 构建一个非空桶的固定查询数：孤儿 bizLine 计数 / 本业务线在线活动 / 规则 / 赠品 / 条件 / 绑定 / 合并策略。
     * 改造前是 {@code 6+N}（绑定查询在 for 循环体里）且活动那次是<b>全租户</b>扫描。
     */
    private static final int EXPECTED_QUERIES = 7;

    @Autowired ActivityMarketingService marketing;
    @Autowired DecisionSnapshotBuilder builder;
    @Autowired DecisionMetrics metrics;
    @Autowired EntityManagerFactory emf;

    @BeforeEach
    void bindTenant() {
        TenantContext.set(TENANT);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private Statistics stats() {
        return emf.unwrap(SessionFactory.class).getStatistics();
    }

    @Test
    @DisplayName("N=1 与 N=10 的构建语句数相同")
    void buildQueryCountDoesNotGrowWithActivityCount() {
        seed("snapq-one", 1);
        seed("snapq-ten", 10);

        long one = countStatementsFor("snapq-one");
        long ten = countStatementsFor("snapq-ten");
        System.out.println("[snapshot-build] N=1 → " + one + " 条语句；N=10 → " + ten + " 条语句");

        // 防假绿：统计没真开启时计数恒为 0，下面两条断言会双双"通过"却什么都没验证
        assertTrue(one > 0,
                "Hibernate 统计未生效（计数为 0），本测试将退化成空断言——检查 generate_statistics 配置");
        assertEquals(one, ten,
                "构建语句数随活动数增长了（N=1 用 " + one + " 次，N=10 用 " + ten + " 次）——构建期 N+1 回来了");
        assertTrue(ten <= EXPECTED_QUERIES,
                "一次快照构建应不超过 " + EXPECTED_QUERIES + " 次查询，实际 " + ten);
    }

    @Test
    @DisplayName("bizLine 过滤在 SQL 里，别条业务线的活动不进桶")
    void otherBizLinesAreNotInTheBucket() {
        seed("snapq-a", 2);
        seed("snapq-b", 3);

        DecisionSnapshot a = builder.build(TENANT, "snapq-a", 1L);
        assertEquals(2, a.activityCount(),
                "本桶只应收自己业务线的活动——把过滤下推到 SQL 之后，"
                        + "少收（谓词写错）与多收（谓词失效）都会在这里露出来");
    }

    @Test
    @DisplayName("bizLine 为空的在线活动被数出来：那是「快照很新、代际正常、就是不命中」的唯一构建期信号")
    void orphanBizLineIsCounted() {
        double before = orphanCount();

        CreateResult r = marketing.create(red("没有业务线", null, new BigDecimal("30"), nextSpu()));
        marketing.changeStatus(r.activityId(), r.version(), ActivityStatus.ONLINE.code());

        builder.build(TENANT, "snapq-orphan-probe", 1L);

        assertTrue(orphanCount() > before,
                "bizLine 为空的在线活动没有被数出来 —— 它进不了任何快照桶，"
                        + "而决策侧对它的表现是 provenance 三个值全绿、活动就是不命中，"
                        + "在此之前只有诊断端点能照出来，且要求排查的人先怀疑到这个活动头上");
    }

    // ---- helpers ----

    private long countStatementsFor(String bizLine) {
        Statistics st = stats();
        st.clear();
        builder.build(TENANT, bizLine, 1L);
        return st.getPrepareStatementCount();
    }

    private double orphanCount() {
        Counter c = metrics.registry().find(DecisionMetrics.SNAPSHOT_ORPHAN).counter();
        return c == null ? 0d : c.count();
    }

    private void seed(String bizLine, int n) {
        for (int i = 0; i < n; i++) {
            CreateResult r = marketing.create(
                    red(bizLine + "-" + i, bizLine, new BigDecimal(10 + i), nextSpu()));
            marketing.changeStatus(r.activityId(), r.version(), ActivityStatus.ONLINE.code());
        }
    }

    private static long nextSpu() {
        return SPU.incrementAndGet();
    }

    private ActivityCreateRequest red(String name, String bizLine, BigDecimal amount, long spu) {
        long now = System.currentTimeMillis();
        return new ActivityCreateRequest(
                null, null, name, bizLine, 1, name,
                now - 3_600_000L, now + 3_600_000L, 1, null, 1, 100,
                1, amount, "元", null, "MAX",
                null, List.of(new ActivityCreateRequest.SpuBinding(1, spu)), null, null);
    }
}
