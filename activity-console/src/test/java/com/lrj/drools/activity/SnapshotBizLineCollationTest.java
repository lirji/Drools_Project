package com.lrj.drools.activity;

import com.lrj.drools.activity.domain.ActivityCreateRequest;
import com.lrj.drools.activity.domain.ActivityStatus;
import com.lrj.drools.activity.service.ActivityMarketingService;
import com.lrj.drools.activity.service.ActivityMarketingService.CreateResult;
import com.lrj.drools.activity.snapshot.DecisionSnapshot;
import com.lrj.drools.activity.snapshot.DecisionSnapshotBuilder;
import com.lrj.drools.activity.tenant.TenantContext;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 快照桶归属必须按 <b>bizLine 精确相等</b>判定，不能把判据交给数据库的排序规则。
 *
 * <p><b>这条测试为什么要单独存在，而不是并进 {@code SnapshotBuildQueryCountTest}</b>：
 * R15 把 bizLine 过滤从 Java 的 {@code bizLine.equals(m.getBizLine())} 下推成了 SQL 的
 * {@code biz_line = ?}。两者在<b>测试环境</b>里恒等价，在<b>生产环境</b>里不等价——
 * <ul>
 *   <li>生产是 MySQL 8（{@code deploy/docker-compose.yml}），默认排序规则 {@code utf8mb4_0900_ai_ci}
 *       是<b>大小写不敏感 + 重音不敏感</b>的（5.7 的 {@code general_ci} 还额外忽略尾随空格）；</li>
 *   <li>而全部快照测试跑在 H2 上，H2 的字符串比较<b>默认大小写敏感</b>。</li>
 * </ul>
 * 于是「{@code Retail} 的活动会不会被收进 {@code retail} 桶」这个问题，在既有测试里<b>永远是否</b>，
 * 在生产上<b>永远是是</b>。而桶归属决定的是「谁在快照里 = 谁能被发钱」：这些活动改造前进不了任何桶，
 * 放任下推之后会命中并按其配置发钱——一次不报错、不回退、没人声明过的语义放宽。
 *
 * <p><b>做法</b>：用 {@code IGNORECASE=TRUE} 把这个测试库的 H2 字符串比较调成<b>大小写不敏感</b>，
 * 复现 MySQL 的排序规则。此时若 Java 侧那道精确比对被删掉，下面第一条断言立刻红。
 * 换句话说，这个测试类的 JDBC URL 本身就是断言的一部分，改它等于关掉这条门禁。
 *
 * @see DecisionSnapshotBuilder#build 里保留 Java 侧 equals 的原因说明
 */
@SpringBootTest
@ActiveProfiles("h2")
@TestPropertySource(properties = {
        // IGNORECASE=TRUE 让 H2 的 VARCHAR 比较大小写不敏感，复现生产 MySQL 的 utf8mb4_0900_ai_ci。
        // 没有它，这个测试在两种实现下都会绿——那正是这个 bug 当初能溜过去的原因。
        "spring.datasource.url=jdbc:h2:mem:snapcollation;DB_CLOSE_DELAY=-1;MODE=MySQL;IGNORECASE=TRUE",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "activity.marketing.seed-catalog-data=false"
})
@DisplayName("快照桶归属：bizLine 必须精确相等，不随数据库排序规则放宽")
class SnapshotBizLineCollationTest {

    private static final String TENANT = "__dev__";
    private static final AtomicLong SPU = new AtomicLong(930_000L);

    @Autowired ActivityMarketingService marketing;
    @Autowired DecisionSnapshotBuilder builder;

    @BeforeEach
    void bindTenant() {
        TenantContext.set(TENANT);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("只差大小写的 bizLine 不进同一个桶")
    void caseDifferingBizLineDoesNotLeakIntoBucket() {
        String lower = "retailcase";
        String upper = "RETAILCASE";

        String lowerId = online("大小写-小写线", lower);
        String upperId = online("大小写-大写线", upper);

        DecisionSnapshot lowerBucket = builder.build(TENANT, lower, 1L);

        assertTrue(lowerBucket.contains(lowerId),
                "本业务线自己的活动没进桶——过滤谓词收得过紧了");
        assertFalse(lowerBucket.contains(upperId),
                "bizLine 为 " + upper + " 的活动漏进了 " + lower + " 桶。"
                        + "这说明桶归属的判据落在了数据库排序规则上：生产 MySQL 的 utf8mb4_0900_ai_ci "
                        + "大小写不敏感，会让这个活动在快照里被命中并按其配置发钱，"
                        + "而它改造前进不了任何桶。判据必须是 Java 侧的精确相等。");
        assertEquals(1, lowerBucket.activityCount(),
                "桶里应当只有本业务线的那 1 个活动");
    }

    @Test
    @DisplayName("反向也成立：大写线的桶不收小写线的活动")
    void reverseDirectionHoldsToo() {
        String lower = "travelcase";
        String upper = "TRAVELCASE";

        String lowerId = online("大小写-出行小写", lower);
        online("大小写-出行大写", upper);

        DecisionSnapshot upperBucket = builder.build(TENANT, upper, 1L);

        assertFalse(upperBucket.contains(lowerId),
                "小写业务线的活动漏进了大写线的桶——同一个排序规则问题的另一个方向");
        assertEquals(1, upperBucket.activityCount());
    }

    // ---- helpers ----

    private String online(String name, String bizLine) {
        CreateResult r = marketing.create(red(name, bizLine, new BigDecimal("20"), SPU.incrementAndGet()));
        marketing.changeStatus(r.activityId(), r.version(), ActivityStatus.ONLINE.code());
        return r.activityId();
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
