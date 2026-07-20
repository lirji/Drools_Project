package com.lrj.drools.activity;

import com.lrj.drools.activity.domain.ActivityCreateRequest;
import com.lrj.drools.activity.domain.ActivityStatus;
import com.lrj.drools.activity.service.ActivityMarketingService;
import com.lrj.drools.activity.service.ActivityMarketingService.CreateResult;
import com.lrj.drools.activity.tenant.ActorContext;
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
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * P1-8 四眼职责分离：{@code four-eyes-enabled=true} 时，活动发布(上线)必须由**非提交人**执行。
 *   - 提交人 == 审批人 → 拒（不能自审自发）；
 *   - 审批人身份缺失 → 拒（无从校验分离，fail-closed）；
 *   - 审批人 ≠ 提交人 → 放行。
 * 操作者身份走 {@link ActorContext}（dev 档=X-Actor header / auth 档=JWT sub），此处直接注入。
 */
@SpringBootTest
@ActiveProfiles("h2")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:foureyes;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "activity.marketing.seed-demo-data=false",
        "activity.marketing.four-eyes-enabled=true",
        "activity.tenant.dev-default-enabled=true"
})
class ActivityFourEyesTest {

    @Autowired ActivityMarketingService marketing;

    @AfterEach
    void clear() {
        TenantContext.clear();
        ActorContext.clear();
    }

    private ActivityCreateRequest req(String name, Long spuId) {
        long hAgo = System.currentTimeMillis() - 3_600_000L;
        long hLater = System.currentTimeMillis() + 3_600_000L;
        return new ActivityCreateRequest(
                null, null, name, "mall", 1, name,
                hAgo, hLater, 1, null, 1, 100,
                1, new BigDecimal("50"), "元", null, "MAX",
                null, List.of(new ActivityCreateRequest.SpuBinding(1, spuId)), null, null);
    }

    @Test
    void submitterCannotSelfPublish() {
        CreateResult a = ActorContext.callWith("alice", () -> marketing.create(req("四眼-自审", 96601L)));
        // alice 自己发布 → 拒
        assertThrows(IllegalStateException.class,
                () -> ActorContext.runWith("alice",
                        () -> marketing.changeStatus(a.activityId(), a.version(), ActivityStatus.ONLINE.code())),
                "提交人 alice 不能发布自己提交的活动");
    }

    @Test
    void differentApproverCanPublish() {
        CreateResult a = ActorContext.callWith("alice", () -> marketing.create(req("四眼-他审", 96602L)));
        CreateResult r = ActorContext.callWith("bob",
                () -> marketing.changeStatus(a.activityId(), a.version(), ActivityStatus.ONLINE.code()));
        assertEquals(ActivityStatus.ONLINE.code(), r.status().intValue(), "非提交人 bob 可发布");
    }

    @Test
    void missingApproverIdentityRejected() {
        CreateResult a = ActorContext.callWith("alice", () -> marketing.create(req("四眼-缺审批人", 96603L)));
        // 无 actor 上下文发布 → 拒（fail-closed）
        assertThrows(IllegalStateException.class,
                () -> marketing.changeStatus(a.activityId(), a.version(), ActivityStatus.ONLINE.code()),
                "缺审批人身份应拒绝（fail-closed）");
    }

    @Test
    void offlineNotGatedByFourEyes() {
        CreateResult a = ActorContext.callWith("alice", () -> marketing.create(req("四眼-下线不拦", 96604L)));
        ActorContext.runWith("bob",
                () -> marketing.changeStatus(a.activityId(), a.version(), ActivityStatus.ONLINE.code()));
        // 下线不是四眼门（只拦发布/上线），提交人也能下线
        CreateResult off = ActorContext.callWith("alice",
                () -> marketing.changeStatus(a.activityId(), a.version(), ActivityStatus.OFFLINE.code()));
        assertEquals(ActivityStatus.OFFLINE.code(), off.status().intValue(), "下线不受四眼拦截");
    }
}
