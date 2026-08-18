package com.lrj.drools.activity;

import com.lrj.drools.activity.domain.ActivityCreateRequest;
import com.lrj.drools.activity.domain.ActivityStatus;
import com.lrj.drools.activity.persistence.ActivityManageEntity;
import com.lrj.drools.activity.service.ActivityMarketingService;
import com.lrj.drools.activity.service.ActivityMarketingService.BulkStatusItem;
import com.lrj.drools.activity.service.ActivityMarketingService.BulkStatusResult;
import com.lrj.drools.activity.service.ActivityMarketingService.CreateResult;
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
 * 批量上下线（PR-5）：**部分失败必须逐条可见**。
 *
 * <p>评审点名四份设计稿共同缺失的就是这个——只给「批量操作条」而不给部分失败回执，
 * 运营点完「批量下线 23 个」之后不知道到底成了几个、哪几个没成、为什么。
 * 大促前这是最高危的操作，静默失败等于让运营以为活动已经停了、实际还在发钱。
 *
 * <p>另一条同样重要：**一条失败不能回滚已成功的那些**。「全成功或全失败」在这里是错的语义——
 * 运营要的是「尽量都下线，然后告诉我哪几个没成功」。
 */
@SpringBootTest
@ActiveProfiles("h2")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:actbulk;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "activity.marketing.seed-catalog-data=false"
})
@DisplayName("批量上下线：部分失败逐条回执")
class BulkStatusTest {

    @Autowired ActivityMarketingService marketing;

    @Test
    void allSucceed() {
        BulkStatusResult r = marketing.bulkChangeStatus(
                List.of(item(81001L), item(81002L), item(81003L)), ActivityStatus.OFFLINE.code());

        assertEquals(3, r.succeeded().size());
        assertTrue(r.failed().isEmpty());
    }

    @Test
    @DisplayName("其中一个不存在 → 其余照常成功，失败那个带原因")
    void partialFailureDoesNotRollbackTheRest() {
        BulkStatusItem a = item(81011L);
        BulkStatusItem b = item(81012L);
        BulkStatusResult r = marketing.bulkChangeStatus(
                List.of(a, new BulkStatusItem("ACT-NOT-EXIST", 1), b), ActivityStatus.OFFLINE.code());

        assertEquals(List.of(a.activityId(), b.activityId()), r.succeeded(), "已成功的不能被失败的那条回滚掉");
        assertEquals(1, r.failed().size());
        assertEquals("ACT-NOT-EXIST", r.failed().get(0).activityId());
        assertTrue(r.failed().get(0).reason() != null && !r.failed().get(0).reason().isBlank(),
                "失败必须带原因——只说『失败 1 个』运营无从下手");
    }

    @Test
    @DisplayName("重复 id 去重，不重复计数")
    void duplicateIdsAreDeduped() {
        BulkStatusItem a = item(81021L);
        BulkStatusResult r = marketing.bulkChangeStatus(List.of(a, a, a), ActivityStatus.OFFLINE.code());
        assertEquals(1, r.succeeded().size());
    }

    @Test
    @DisplayName("空列表返回空回执，不抛异常")
    void emptyInput() {
        BulkStatusResult r = marketing.bulkChangeStatus(List.of(), ActivityStatus.OFFLINE.code());
        assertTrue(r.succeeded().isEmpty() && r.failed().isEmpty());
        BulkStatusResult n = marketing.bulkChangeStatus(null, ActivityStatus.OFFLINE.code());
        assertTrue(n.succeeded().isEmpty() && n.failed().isEmpty());
    }

    @Test
    @DisplayName("已上线活动被编辑出草稿后，批量下线必须停掉**正在服务的那一版**，而不是草稿")
    void bulkOfflineHitsTheServingVersionNotTheDraft() {
        BulkStatusItem online = item(81031L);   // 记下的是**上线时那一版**
        editIntoDraft(online.activityId());     // P0-4：线上 v1 保留、另建 v2 草稿 → 此刻同一活动两行

        marketing.bulkChangeStatus(List.of(online), ActivityStatus.OFFLINE.code());

        List<ActivityManageEntity> rows = marketing.list().stream()
                .filter(r -> r.getActivityId().equals(online.activityId())).toList();
        assertEquals(2, rows.size(), "P0-4 语义：线上版与草稿并存，list 会返回两行");
        assertTrue(rows.stream().noneMatch(r -> ActivityStatus.ONLINE.code() == r.getActivityStatus()),
                "批量下线后不得再有 ONLINE 版本——否则运营以为活动停了，线上其实还在发钱");
    }

    @Test
    @DisplayName("反向验证：version 传 null 就会打到草稿、线上版存活 —— 这正是显式版本要防的")
    void nullVersionHitsTheDraftInstead() {
        BulkStatusItem online = item(81041L);
        editIntoDraft(online.activityId());

        marketing.bulkChangeStatus(
                List.of(new BulkStatusItem(online.activityId(), null)), ActivityStatus.OFFLINE.code());

        List<ActivityManageEntity> rows = marketing.list().stream()
                .filter(r -> r.getActivityId().equals(online.activityId())).toList();
        assertTrue(rows.stream().anyMatch(r -> ActivityStatus.ONLINE.code() == r.getActivityStatus()),
                "null 版本取最高版=草稿，线上那一版会原封不动地继续发钱");
    }

    // ---- helpers ----

    /** 编辑一个已上线的活动：P0-4 之后不软删线上版，只加 v+1 草稿 */
    private void editIntoDraft(String activityId) {
        long now = System.currentTimeMillis();
        marketing.updateByVersion(new ActivityCreateRequest(
                null, activityId, "批量-改名", "bulk", 1, null,
                now - 3_600_000L, now + 3_600_000L, 1, null, 1, 100,
                1, new BigDecimal("20"), "元", null, "MAX",
                null, List.of(new ActivityCreateRequest.SpuBinding(1, 99001L)), null, null));
    }

    /** 建一个已上线的活动，并返回「它上线的那一版」——工作台传给批量接口的正是这个 */
    private BulkStatusItem item(long spu) {
        long now = System.currentTimeMillis();
        CreateResult r = marketing.create(new ActivityCreateRequest(
                null, null, "批量-" + spu, "bulk", 1, null,
                now - 3_600_000L, now + 3_600_000L, 1, null, 1, 100,
                1, new BigDecimal("10"), "元", null, "MAX",
                null, List.of(new ActivityCreateRequest.SpuBinding(1, spu)), null, null));
        marketing.changeStatus(r.activityId(), r.version(), ActivityStatus.ONLINE.code());
        return new BulkStatusItem(r.activityId(), r.version());
    }
}
