package com.lrj.drools.activity;

import com.lrj.drools.activity.domain.ActivityCreateRequest;
import com.lrj.drools.activity.domain.ActivityStatus;
import com.lrj.drools.activity.domain.DecisionMode;
import com.lrj.drools.activity.domain.SpuDiscountRequest;
import com.lrj.drools.activity.service.ActivityMarketingService;
import com.lrj.drools.activity.service.ActivityMarketingService.CreateResult;
import com.lrj.drools.activity.service.ActivityQueryService;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-3 的**验收证据**：一次决策的数据库查询次数与候选活动数 N <b>无关</b>。
 *
 * <p><b>为什么要数 SQL 而不是读代码</b>：N+1 是最容易「改完以为修好了」的一类问题——
 * 循环里少一个查询、别处又多一个，读 diff 看不出来。这里直接用 Hibernate 的
 * {@link Statistics} 数真实发出的语句数，并且**对比 N=1 与 N=10 两次决策**：
 * 只要次数相等，就证明它不再随候选数增长；哪怕将来有人把某个查询挪回循环里，这条也会立刻红。
 *
 * <p><b>改造前的基线</b>：<code>3N+2</code> 次（逐活动查当前版本 N 次、逐候选查规则 N 次、
 * 逐候选查资格条件 N 次、加上 SPU 绑定与合并策略各 1 次）。N=10 时是 32 次。
 */
@SpringBootTest
@ActiveProfiles("h2")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:actqcount;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "activity.marketing.seed-demo-data=false"
})
@DisplayName("决策查询次数：与候选数 N 无关")
class DecisionQueryCountTest {

    /** 取数层的固定查询数：SPU 绑定 / 活动版本 / 规则 / 资格条件 / 合并策略。 */
    private static final int EXPECTED_QUERIES = 5;

    @Autowired ActivityMarketingService marketing;
    @Autowired ActivityQueryService query;
    @Autowired EntityManagerFactory emf;

    private Statistics stats() {
        return emf.unwrap(SessionFactory.class).getStatistics();
    }

    @Test
    void queryCountDoesNotGrowWithCandidateCount() {
        long spuSmall = 610_001L;
        long spuLarge = 610_002L;
        seed(spuSmall, 1);    // N=1
        seed(spuLarge, 10);   // N=10

        long one = countQueriesFor(spuSmall);
        long ten = countQueriesFor(spuLarge);
        System.out.println("[query-count] N=1 → " + one + " 条语句；N=10 → " + ten + " 条语句");

        // 防假绿：统计没真开启时计数恒为 0，上面两条断言会双双"通过"却什么都没验证
        assertTrue(one > 0,
                "Hibernate 统计未生效（计数为 0），本测试将退化成空断言——检查 generate_statistics 配置");

        assertEquals(one, ten,
                "查询次数随候选数增长了（N=1 用 " + one + " 次，N=10 用 " + ten + " 次）——N+1 回来了");
        assertTrue(ten <= EXPECTED_QUERIES,
                "一次决策应不超过 " + EXPECTED_QUERIES + " 次查询，实际 " + ten
                        + "。改造前是 3N+2（N=10 时 32 次）");
    }

    @Test
    void buyAndGetAlsoBounded() {
        long spu = 610_003L;
        for (int i = 0; i < 6; i++) {
            CreateResult r = marketing.create(gift("赠品活动" + i, spu));
            marketing.changeStatus(r.activityId(), r.version(), ActivityStatus.ONLINE.code());
        }
        Statistics st = stats();
        st.clear();
        query.buyAndGetGifts(req(spu), DecisionMode.HOT_PATH);
        assertTrue(st.getQueryExecutionCount() + st.getPrepareStatementCount() <= EXPECTED_QUERIES * 2L,
                "买赠链路同样不应随候选数增长，实际 statements=" + st.getPrepareStatementCount());
    }

    // ---- helpers ----

    private long countQueriesFor(long spu) {
        Statistics st = stats();
        st.clear();
        query.spuDiscount(req(spu), DecisionMode.HOT_PATH);
        // JPQL 派生查询走 prepared statement，这里数真实语句数最直接
        return st.getPrepareStatementCount();
    }

    private void seed(long spu, int n) {
        for (int i = 0; i < n; i++) {
            CreateResult r = marketing.create(red("活动" + spu + "-" + i, new BigDecimal(10 + i), spu));
            marketing.changeStatus(r.activityId(), r.version(), ActivityStatus.ONLINE.code());
        }
    }

    private static SpuDiscountRequest req(long spu) {
        return new SpuDiscountRequest(List.of(spu), 1001L, "110000", List.of("vip"), new BigDecimal("500"), 1);
    }

    private ActivityCreateRequest red(String name, BigDecimal amount, long spu) {
        long now = System.currentTimeMillis();
        return new ActivityCreateRequest(
                null, null, name, "qcount", 1, name,
                now - 3_600_000L, now + 3_600_000L, 1, null, 1, 100,
                1, amount, "元", null, "MAX",
                null, List.of(new ActivityCreateRequest.SpuBinding(1, spu)), null, null);
    }

    private ActivityCreateRequest gift(String name, long spu) {
        long now = System.currentTimeMillis();
        return new ActivityCreateRequest(
                null, null, name, "qcount-gift", 5, name,
                now - 3_600_000L, now + 3_600_000L, 1, null, 1, 100,
                null, null, "元", null, "MAX",
                null, List.of(new ActivityCreateRequest.SpuBinding(1, spu)), null,
                List.of(new ActivityCreateRequest.GiftInput("B1", "赠品", "PHYSICAL", 1, BigDecimal.ZERO, "GIFT")));
    }
}
