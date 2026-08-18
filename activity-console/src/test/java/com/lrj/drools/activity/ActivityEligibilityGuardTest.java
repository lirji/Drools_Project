package com.lrj.drools.activity;

import com.lrj.drools.activity.domain.ActivityCreateRequest;
import com.lrj.drools.activity.domain.ActivityStatus;
import com.lrj.drools.activity.domain.ConditionNode;
import com.lrj.drools.activity.domain.DecisionMode;
import com.lrj.drools.activity.domain.SpuDiscountRequest;
import com.lrj.drools.activity.service.ActivityMarketingService;
import com.lrj.drools.activity.service.ActivityMarketingService.CreateResult;
import com.lrj.drools.activity.service.ActivityQueryService;
import com.lrj.drools.activity.service.ActivityQueryService.DiscountView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-2 回归：资格条件的**否定运算符**（notIn / notContains / ne）遇到**缺字段**时必须 fail-CLOSED
 * （候选被淘汰），绝不能 fail-OPEN 放行——否则等于静默超发，违背防超发纪律。
 *
 * 修复前：{@code textAttr("userDistrictId") not in ("110000")} 在字段缺失时 = {@code null not in (...)} = true
 * → 约束成立 → 候选不被淘汰 → 放行（bug）。
 * 修复后：翻译器 emit {@code (textAttr("userDistrictId") != null && textAttr("userDistrictId") not in ("110000"))}
 * → 缺字段短路成 false → 候选被淘汰（fail-closed）。
 *
 * P0-1 上 Map fact 后，"缺字段"统一表现为访问器返回 {@code null}（putAttr 跳过 null → 键不存在），
 * 故 notIn(district) 与 notContains(userTags) 两条都能端到端复现。
 *
 * 真跑一遍 Drools（CLAUDE.md 坑 4/6：mvn compile 不校验 DRL，只有执行才暴露）。
 */
@SpringBootTest
@ActiveProfiles("h2")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:actguard;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "activity.marketing.rule-engine.enabled=true",
        "activity.marketing.seed-catalog-data=false"
})
class ActivityEligibilityGuardTest {

    @Autowired ActivityMarketingService marketing;
    @Autowired ActivityQueryService query;

    private long hAgo() { return System.currentTimeMillis() - 3_600_000L; }
    private long hLater() { return System.currentTimeMillis() + 3_600_000L; }

    /**
     * notIn 缺字段 fail-closed：黑名单地域 [110000]，红包 50，绑定 spu 8001。
     * 非黑名单地域命中 / 黑名单淘汰 / 缺字段（null）**必须淘汰**（不得放行）。
     */
    @Test
    void notInMissingFieldFailsClosed() {
        ConditionNode blacklist = leaf("userDistrictId", "notIn", List.of("110000"));
        CreateResult a = marketing.create(redPackageWithCond("地域黑名单红包", "biz-guard-1",
                new BigDecimal("50"), blacklist, 8001L));
        online(a);

        assertTrue(discount(8001L, "440000", List.of("vip")).hit(), "非黑名单地域应通过资格并命中");
        assertFalse(discount(8001L, "110000", List.of("vip")).hit(), "黑名单地域应被淘汰");
        assertFalse(discount(8001L, null, List.of("vip")).hit(),
                "P0-2：notIn 缺字段必须 fail-closed（候选淘汰），不得静默超发");
    }

    /**
     * notContains 缺字段 fail-closed：要求用户标签不含 blocked，红包 30，绑定 spu 8002。
     * 无 blocked 命中 / 含 blocked 淘汰 / 标签缺失（Map fact 下 = null）**必须淘汰**。
     */
    @Test
    void notContainsMissingFieldFailsClosed() {
        ConditionNode noBlocked = leaf("userTags", "notContains", "blocked");
        CreateResult a = marketing.create(redPackageWithCond("标签排除红包", "biz-guard-2",
                new BigDecimal("30"), noBlocked, 8002L));
        online(a);

        assertTrue(discount(8002L, "440000", List.of("vip")).hit(), "无排除标签应通过资格并命中");
        assertFalse(discount(8002L, "440000", List.of("blocked")).hit(), "含排除标签应被淘汰");
        assertFalse(discount(8002L, "440000", null).hit(),
                "P0-2：notContains 标签缺失必须 fail-closed，不得静默超发");
    }

    // ------------------------------------------------------------------ helpers

    private ConditionNode leaf(String field, String op, Object value) {
        ConditionNode n = new ConditionNode();
        n.setField(field); n.setOp(op); n.setValue(value);
        return n;
    }

    private ActivityCreateRequest redPackageWithCond(String name, String bizLine, BigDecimal amount,
                                                     ConditionNode cond, Long spuId) {
        return new ActivityCreateRequest(
                null, null, name, bizLine, 1, name,
                hAgo(), hLater(), 1, null, 1, 100,
                1, amount, "元", null, "MAX",
                cond,
                List.of(new ActivityCreateRequest.SpuBinding(1, spuId)),
                null, null);
    }

    private void online(CreateResult r) {
        marketing.changeStatus(r.activityId(), r.version(), ActivityStatus.ONLINE.code());
    }

    private DiscountView discount(Long spuId, String district, List<String> tags) {
        return query.spuDiscount(new SpuDiscountRequest(
                List.of(spuId), 1001L, district, tags, new BigDecimal("100"), 1), DecisionMode.HOT_PATH);
    }
}
