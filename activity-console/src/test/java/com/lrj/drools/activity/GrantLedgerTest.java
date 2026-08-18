package com.lrj.drools.activity;

import com.lrj.drools.activity.domain.ActivityCreateRequest;
import com.lrj.drools.activity.domain.ActivityStatus;
import com.lrj.drools.activity.persistence.ActivityGrantEntity;
import com.lrj.drools.activity.persistence.ActivityManageRepository;
import com.lrj.drools.activity.service.ActivityMarketingService;
import com.lrj.drools.activity.service.ActivityMarketingService.CreateResult;
// 发放台账拆出去了（R12），ClaimResult 跟着它走；断言仍打 ActivityMarketingService 上的同名委派方法。
import com.lrj.drools.activity.service.GrantService.ClaimResult;
import com.lrj.drools.activity.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>发放流水</b>：claim 幂等 / 每人限领 / 退款冲正 / 发放对账，四件事一张表。
 *
 * <p>此前这条防线整体空转：
 * <ul>
 *   <li>claim <b>不幂等</b>——连点两次扣两次，因为没有任何东西记得「这一单领过了」；</li>
 *   <li>不传 version 时打到<b>草稿</b>版本，而决策发的是最高 ONLINE 版本——
 *       防超发的闸门装在了另一行数据上；</li>
 *   <li>扣减 SQL 的 WHERE 只有 {@code isDel + inventory >= n}，
 *       <b>已下线 / 未开始 / 已结束的库存都能被扣干净</b>；</li>
 *   <li>每人限领是条彻底的死路：提交入口没有这个字段、写入口硬编码 0、全仓零读取；</li>
 *   <li>无 release——订单取消后库存永久蒸发。</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("h2")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:grantledger;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "activity.marketing.seed-catalog-data=false"
})
@DisplayName("发放流水：幂等 / 限领 / 冲正 / 对账")
class GrantLedgerTest {

    private static final AtomicLong SPU = new AtomicLong(770_000L);
    private static final AtomicLong ORDER = new AtomicLong(1L);

    @Autowired ActivityMarketingService marketing;
    @Autowired ActivityManageRepository manageRepo;

    @BeforeEach
    void bindTenant() { TenantContext.set("__dev__"); }

    @AfterEach
    void clear() { TenantContext.clear(); }

    @Nested
    @DisplayName("幂等")
    class Idempotency {

        @Test
        @DisplayName("同一单重复 claim 只扣一次库存")
        void repeatedClaimDeductsOnce() {
            CreateResult a = onlineFlash("幂等券", 100, null);
            String order = nextOrder();

            ClaimResult first = marketing.claimInventory(a.activityId(), null, 1, "u1", order);
            ClaimResult second = marketing.claimInventory(a.activityId(), null, 1, "u1", order);

            assertTrue(first.ok());
            assertFalse(first.replay());
            assertTrue(second.ok(), "重复提交仍应返回成功（幂等），而不是报错");
            assertTrue(second.replay(), "第二次必须标记成重放");
            assertEquals(99, inventoryOf(a.activityId(), first.version()),
                    "库存只能被扣一次 —— 用户连点两次就扣两次是本轮要修的缺陷");
        }

        @Test
        @DisplayName("不同订单各扣各的")
        void differentOrdersEachDeduct() {
            CreateResult a = onlineFlash("多单券", 100, null);
            marketing.claimInventory(a.activityId(), null, 1, "u1", nextOrder());
            marketing.claimInventory(a.activityId(), null, 1, "u2", nextOrder());
            assertEquals(98, inventoryOf(a.activityId(), currentVersion(a)));
        }

        @Test
        @DisplayName("不带 orderId 时退化成不幂等，但仍然能扣（兼容旧调用方）")
        void withoutOrderIdStillWorks() {
            CreateResult a = onlineFlash("无单号券", 100, null);
            assertTrue(marketing.claimInventory(a.activityId(), null, 1).ok());
            assertEquals(99, inventoryOf(a.activityId(), currentVersion(a)));
        }
    }

    @Nested
    @DisplayName("扣减打在正确的那一行上")
    class RightRow {

        @Test
        @DisplayName("不传 version → 打到当前线上版本，不是最高的草稿版本")
        void defaultsToOnlineVersionNotDraft() {
            CreateResult v1 = onlineFlash("版本券", 100, null);
            // 编辑产生 v2 草稿（线上仍是 v1）
            CreateResult v2 = marketing.updateByVersion(flashReq("版本券v2", v1.activityId(), 100, null, spuOf(v1)));
            assertEquals(v1.version() + 1, v2.version(), "编辑应产生新草稿版本");

            ClaimResult r = marketing.claimInventory(v1.activityId(), null, 1, "u1", nextOrder());

            assertEquals(v1.version(), r.version(),
                    "claim 必须打到**线上**版本。打到草稿的后果是：线上版本的库存一件没少、"
                            + "草稿的库存被扣干净 —— 防超发的闸门装在了另一行数据上");
            assertEquals(99, inventoryOf(v1.activityId(), v1.version()), "线上版本库存应减少");
            assertEquals(100, inventoryOf(v1.activityId(), v2.version()), "草稿版本库存不应被动");
        }

        @Test
        @DisplayName("已下线的活动扣不动库存")
        void offlineActivityCannotBeClaimed() {
            CreateResult a = onlineFlash("待下线券", 100, null);
            marketing.changeStatus(a.activityId(), a.version(), ActivityStatus.OFFLINE.code());

            String order = nextOrder();
            ClaimResult r = marketing.claimInventory(a.activityId(), a.version(), 1, "u1", order);

            assertFalse(r.ok(), "已下线的活动不该还能扣库存");
            assertEquals(100, inventoryOf(a.activityId(), a.version()), "库存必须原封不动");
            assertTrue(marketing.grantsOfOrder(order).isEmpty(), "失败的 claim 不留流水");
        }

        @Test
        @DisplayName("未开始 / 已结束的活动扣不动库存")
        void outOfWindowActivityCannotBeClaimed() {
            long now = System.currentTimeMillis();
            // 活动窗口在过去
            CreateResult a = marketing.create(new ActivityCreateRequest(
                    null, null, "过期券", "grant-window", 1, "过期券",
                    now - 7_200_000L, now - 3_600_000L, 1, null, 1, 100,
                    1, new BigDecimal("9.9"), "价", null, "MAX",
                    null, List.of(new ActivityCreateRequest.SpuBinding(1, nextSpu())), null, null));
            marketing.changeStatus(a.activityId(), a.version(), ActivityStatus.ONLINE.code());

            String order = nextOrder();
            assertFalse(marketing.claimInventory(a.activityId(), a.version(), 1, "u1", order).ok(),
                    "活动期外不该还能扣库存");
            assertEquals(100, inventoryOf(a.activityId(), a.version()));
        }

        @Test
        @DisplayName("库存不足时不留下「有账无货」的流水")
        void failedClaimLeavesNoLedgerRow() {
            CreateResult a = onlineFlash("售罄券", 1, null);
            String o1 = nextOrder();
            assertTrue(marketing.claimInventory(a.activityId(), null, 1, "u1", o1).ok());

            String o2 = nextOrder();
            assertFalse(marketing.claimInventory(a.activityId(), null, 1, "u2", o2).ok(),
                    "卖光后必须失败");

            assertTrue(marketing.grantsOfOrder(o2).isEmpty(),
                    "失败的 claim 不能留下发放记录 —— 否则对账时会出现「有账无货」");
            assertEquals(0, inventoryOf(a.activityId(), currentVersion(a)));
        }
    }

    @Nested
    @DisplayName("每人限领")
    class PerUserLimit {

        @Test
        @DisplayName("超出每人限领被拒")
        void exceedingPerUserLimitIsRejected() {
            CreateResult a = onlineFlash("限领券", 100, 2);

            assertTrue(marketing.claimInventory(a.activityId(), null, 1, "alice", nextOrder()).ok());
            assertTrue(marketing.claimInventory(a.activityId(), null, 1, "alice", nextOrder()).ok());
            ClaimResult third = marketing.claimInventory(a.activityId(), null, 1, "alice", nextOrder());

            assertFalse(third.ok(), "每人限 2 份，第 3 次必须被拒");
            assertTrue(third.reason().contains("每人限领"), "拒绝原因要说人话，实际：" + third.reason());
            assertTrue(marketing.claimInventory(a.activityId(), null, 1, "bob", nextOrder()).ok(),
                    "限的是每个人，不是总量");
        }

        @Test
        @DisplayName("配了限领却拿不到 userId → 拒绝，而不是放行")
        void missingUserIdIsRejectedWhenLimited() {
            CreateResult a = onlineFlash("限领需实名", 100, 1);

            ClaimResult r = marketing.claimInventory(a.activityId(), null, 1, null, nextOrder());

            assertFalse(r.ok(),
                    "无从判断是不是同一个人时放行，等于这条限制不存在 —— 必须 fail-closed");
            assertEquals(100, inventoryOf(a.activityId(), a.version()));
        }

        @Test
        @DisplayName("没配限领时不需要 userId")
        void unlimitedActivityDoesNotRequireUserId() {
            CreateResult a = onlineFlash("不限领", 100, null);
            assertTrue(marketing.claimInventory(a.activityId(), null, 1, null, nextOrder()).ok());
        }
    }

    @Nested
    @DisplayName("冲正与对账")
    class ReleaseAndAudit {

        @Test
        @DisplayName("释放把库存还回去，并解除该用户的限领占用")
        void releaseReturnsInventoryAndQuota() {
            CreateResult a = onlineFlash("可退券", 100, 1);
            String order = nextOrder();
            assertTrue(marketing.claimInventory(a.activityId(), null, 1, "alice", order).ok());
            assertEquals(99, inventoryOf(a.activityId(), a.version()));
            // 限额已用完
            assertFalse(marketing.claimInventory(a.activityId(), null, 1, "alice", nextOrder()).ok());

            ClaimResult rel = marketing.releaseGrant(order, a.activityId());

            assertTrue(rel.ok());
            assertEquals(100, inventoryOf(a.activityId(), a.version()),
                    "退款后库存必须还回来 —— 此前订单取消库存就永久蒸发了");
            assertTrue(marketing.claimInventory(a.activityId(), null, 1, "alice", nextOrder()).ok(),
                    "退了就该把额度还给用户，否则「买了又退」会永久占掉他的领取资格");
        }

        @Test
        @DisplayName("重复释放不重复加库存")
        void releaseIsIdempotent() {
            CreateResult a = onlineFlash("重复退", 100, null);
            String order = nextOrder();
            marketing.claimInventory(a.activityId(), null, 1, "u1", order);

            marketing.releaseGrant(order, a.activityId());
            ClaimResult again = marketing.releaseGrant(order, a.activityId());

            assertTrue(again.ok());
            assertTrue(again.replay());
            assertEquals(100, inventoryOf(a.activityId(), a.version()),
                    "重复释放不能把库存刷上去");
        }

        @Test
        @DisplayName("按订单查得到发放记录——客服「这一单用了哪些优惠」")
        void grantsAreQueryableByOrder() {
            CreateResult a = onlineFlash("对账券", 100, null);
            String order = nextOrder();
            marketing.claimInventory(a.activityId(), null, 2, "alice", order);

            List<ActivityGrantEntity> grants = marketing.grantsOfOrder(order);

            assertEquals(1, grants.size());
            ActivityGrantEntity g = grants.get(0);
            assertEquals(a.activityId(), g.getActivityId());
            assertEquals(a.version(), g.getVersion(), "记下按哪一版发的");
            assertEquals("alice", g.getUserId());
            assertEquals(2, g.getQuantity());
            assertEquals(ActivityGrantEntity.HELD, g.getState());
            assertNotNull(g.getCreatedStime());
        }
    }

    // ---- helpers ----

    private static long nextSpu() { return SPU.incrementAndGet(); }
    private static String nextOrder() { return "ORD" + ORDER.incrementAndGet(); }

    private int inventoryOf(String activityId, Integer version) {
        return manageRepo.findFirstByActivityIdAndVersionAndIsDel(activityId, version, 0)
                .orElseThrow().getInventory();
    }

    private Integer currentVersion(CreateResult r) { return r.version(); }

    private long spuOf(CreateResult r) {
        return marketing.getDetail(r.activityId()).bindings().get(0).getSpuId();
    }

    private CreateResult onlineFlash(String name, int inventory, Integer userInventory) {
        CreateResult r = marketing.create(flashReq(name, null, inventory, userInventory, nextSpu()));
        marketing.changeStatus(r.activityId(), r.version(), ActivityStatus.ONLINE.code());
        return r;
    }

    private ActivityCreateRequest flashReq(String name, String activityId, int inventory,
                                           Integer userInventory, long spu) {
        long now = System.currentTimeMillis();
        return new ActivityCreateRequest(
                null, activityId, name, "grant-biz", 1, name,
                now - 3_600_000L, now + 3_600_000L, 1, null, 1, inventory,
                1, new BigDecimal("9.9"), "价", null, "MAX",
                null, List.of(new ActivityCreateRequest.SpuBinding(1, spu)), null, null,
                null, userInventory);
    }
}
