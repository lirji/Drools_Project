package com.lrj.drools.activity;

import com.lrj.drools.activity.domain.ActivityCreateRequest;
import com.lrj.drools.activity.domain.ActivityStatus;
import com.lrj.drools.activity.engine.BenefitMath;
import com.lrj.drools.activity.persistence.ActivityGrantEntity;
import com.lrj.drools.activity.persistence.ActivityGrantEntryEntity;
import com.lrj.drools.activity.persistence.ActivityGrantEntryRepository;
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
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    @Autowired ActivityGrantEntryRepository entryRepo;
    @Autowired JdbcTemplate jdbc;

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

    @Nested
    @DisplayName("确认发放（支付回调）与分录台账")
    class ConfirmAndLedger {

        @Test
        @DisplayName("confirm 一笔 HELD → CONFIRMED，落 amount/decisionId，并追加 +ISSUE 分录")
        void confirmHeldWritesIssueEntry() {
            CreateResult a = onlineFlash("确认券", 100, null);
            String order = nextOrder();
            marketing.claimInventory(a.activityId(), null, 1, "u1", order);

            ClaimResult r = marketing.confirmGrant(a.activityId(), order, new BigDecimal("9.90"), "DEC-1");

            assertTrue(r.ok());
            assertFalse(r.replay());
            ActivityGrantEntity g = onlyGrant(order);
            assertEquals(ActivityGrantEntity.CONFIRMED, g.getState(), "支付回调应把发放确认");
            assertEquals(0, new BigDecimal("9.90").compareTo(g.getAmount()), "确认金额首次落在 grant.amount 上");
            assertEquals("DEC-1", g.getDecisionId());

            List<ActivityGrantEntryEntity> entries = entryRepo.findByGrantNoOrderByIdAsc(g.getGrantNo());
            assertEquals(1, entries.size(), "确认发放追加且只追加一条分录");
            ActivityGrantEntryEntity issue = entries.get(0);
            assertEquals(ActivityGrantEntryEntity.ISSUE, issue.getEntryType());
            assertEquals(990L, issue.getAmountMinor(), "+amount×100，带正号");
            assertEquals(order, issue.getOrderId());
            assertEquals(a.activityId(), issue.getActivityId());
            assertEquals("CNY", issue.getCurrency(), "未配币种兜底 CNY");
        }

        @Test
        @DisplayName("confirm 幂等：重复回调 replay=true，不重复追分录、不覆盖首次金额")
        void confirmIsIdempotentAndDoesNotOverwrite() {
            CreateResult a = onlineFlash("幂等确认券", 100, null);
            String order = nextOrder();
            marketing.claimInventory(a.activityId(), null, 1, "u1", order);
            marketing.confirmGrant(a.activityId(), order, new BigDecimal("9.90"), "DEC-1");

            // 携带**不同**金额的迟到重复回调：以首次为准，不覆盖。
            ClaimResult again = marketing.confirmGrant(a.activityId(), order, new BigDecimal("20.00"), "DEC-2");

            assertTrue(again.ok());
            assertTrue(again.replay(), "第二次必须标记成重放");
            ActivityGrantEntity g = onlyGrant(order);
            assertEquals(0, new BigDecimal("9.90").compareTo(g.getAmount()), "first-write-wins：金额不被第二次覆盖");
            List<ActivityGrantEntryEntity> entries = entryRepo.findByGrantNoOrderByIdAsc(g.getGrantNo());
            assertEquals(1, entries.size(), "重复回调不能重复追分录（uk_entry_grant_type 兜底）");
            assertEquals(990L, entries.get(0).getAmountMinor(), "分录金额仍是首次的 +990");
        }

        @Test
        @DisplayName("confirm 未 claim 的订单 → NOT_FOUND，不凭空建账")
        void confirmUnknownOrderIsNotFound() {
            CreateResult a = onlineFlash("未领确认券", 100, null);
            String order = nextOrder(); // 从未 claim

            ClaimResult r = marketing.confirmGrant(a.activityId(), order, new BigDecimal("9.90"), null);

            assertFalse(r.ok());
            assertTrue(marketing.grantsOfOrder(order).isEmpty(), "未 claim 的订单不凭空产生发放记录");
        }

        @Test
        @DisplayName("confirm 亚分金额（scale>2）→ 拒绝，grant 仍 HELD、无分录")
        void confirmSubCentAmountIsRejected() {
            CreateResult a = onlineFlash("亚分券", 100, null);
            String order = nextOrder();
            marketing.claimInventory(a.activityId(), null, 1, "u1", order);

            ClaimResult r = marketing.confirmGrant(a.activityId(), order, new BigDecimal("9.999"), null);

            assertFalse(r.ok(), "亚分金额必须 fail-fast，不静默截断");
            ActivityGrantEntity g = onlyGrant(order);
            assertEquals(ActivityGrantEntity.HELD, g.getState(), "拒绝的确认不能改变状态");
            assertTrue(entryRepo.findByGrantNoOrderByIdAsc(g.getGrantNo()).isEmpty(), "拒绝的确认不留分录");
        }

        @Test
        @DisplayName("退款先到、支付回调迟到：confirm 一笔已 RELEASED → STATE_CONFLICT，绝不改回 CONFIRMED")
        void confirmAfterReleaseIsStateConflict() {
            CreateResult a = onlineFlash("迟到回调券", 100, null);
            String order = nextOrder();
            marketing.claimInventory(a.activityId(), null, 1, "u1", order);
            marketing.confirmGrant(a.activityId(), order, new BigDecimal("9.90"), null);
            marketing.releaseGrant(order, a.activityId());

            ClaimResult late = marketing.confirmGrant(a.activityId(), order, new BigDecimal("9.90"), null);

            assertFalse(late.ok(), "已释放的发放不能再确认");
            assertEquals(ActivityGrantEntity.RELEASED, onlyGrant(order).getState(), "绝不 RELEASED→CONFIRMED");
        }
    }

    @Nested
    @DisplayName("退款冲正分录（追加式红蓝字）")
    class ReversalLedger {

        @Test
        @DisplayName("CONFIRMED→RELEASED 追加 −REVERSAL 分录，与 ISSUE 符号对称、组内守恒对平")
        void releaseConfirmedAppendsSymmetricReversal() {
            CreateResult a = onlineFlash("可退确认券", 100, null);
            String order = nextOrder();
            marketing.claimInventory(a.activityId(), null, 1, "u1", order);
            marketing.confirmGrant(a.activityId(), order, new BigDecimal("9.90"), null);

            marketing.releaseGrant(order, a.activityId());

            ActivityGrantEntity g = onlyGrant(order);
            List<ActivityGrantEntryEntity> entries = entryRepo.findByGrantNoOrderByIdAsc(g.getGrantNo());
            assertEquals(2, entries.size(), "退已确认的发放 = ISSUE + REVERSAL 两条分录");
            assertEquals(ActivityGrantEntryEntity.ISSUE, entries.get(0).getEntryType());
            assertEquals(ActivityGrantEntryEntity.REVERSAL, entries.get(1).getEntryType());
            assertEquals(990L, entries.get(0).getAmountMinor());
            assertEquals(-990L, entries.get(1).getAmountMinor(), "取负已存 ISSUE 分额，符号对称");
            long sum = entries.stream().mapToLong(ActivityGrantEntryEntity::getAmountMinor).sum();
            assertEquals(0L, sum, "按 grant_no 分组守恒对平——追加式台账的核心收益");
            assertEquals(0, new BigDecimal("9.90").compareTo(g.getAmount()),
                    "amount(元) 只记发放幅值，永不带冲正符号");
        }

        @Test
        @DisplayName("HELD→RELEASED（未付即取消）不写任何分录，但仍还库存")
        void releaseHeldWritesNoEntry() {
            CreateResult a = onlineFlash("未付取消券", 100, null);
            String order = nextOrder();
            marketing.claimInventory(a.activityId(), null, 1, "u1", order);
            assertEquals(99, inventoryOf(a.activityId(), a.version()));

            ClaimResult rel = marketing.releaseGrant(order, a.activityId());

            assertTrue(rel.ok());
            ActivityGrantEntity g = onlyGrant(order);
            assertTrue(entryRepo.findByGrantNoOrderByIdAsc(g.getGrantNo()).isEmpty(),
                    "从未确认发放的释放不该凭空产生冲正分录");
            assertEquals(100, inventoryOf(a.activityId(), a.version()), "未付取消仍要还库存");
        }
    }

    @Nested
    @DisplayName("发放号与币种")
    class GrantNoAndCurrency {

        @Test
        @DisplayName("claim 即生成非空、且各单互不相同的 grant_no")
        void claimGeneratesUniqueGrantNo() {
            CreateResult a = onlineFlash("发放号券", 100, null);
            String o1 = nextOrder();
            String o2 = nextOrder();
            marketing.claimInventory(a.activityId(), null, 1, "u1", o1);
            marketing.claimInventory(a.activityId(), null, 1, "u2", o2);

            String g1 = onlyGrant(o1).getGrantNo();
            String g2 = onlyGrant(o2).getGrantNo();
            assertNotNull(g1);
            assertNotNull(g2);
            assertNotEquals(g1, g2, "grant_no 必须全局唯一（issue_id/match_key）");
        }

        @Test
        @DisplayName("claim 时 state=HELD 不产生任何分录（HELD 占用天然不进对账）")
        void heldClaimHasNoEntry() {
            CreateResult a = onlineFlash("占用券", 100, null);
            String order = nextOrder();
            marketing.claimInventory(a.activityId(), null, 1, "u1", order);
            assertTrue(entryRepo.findByGrantNoOrderByIdAsc(onlyGrant(order).getGrantNo()).isEmpty());
        }

        @Test
        @DisplayName("活动配了币种 → grant 与分录继承（大写归一）；未配 → 兜底 CNY")
        void currencyInheritedFromActivity() {
            // 活动配 usd（小写），写入口归一大写。
            CreateResult a = marketing.create(flashReqWithCurrency("美元券", nextSpu(), "usd"));
            marketing.changeStatus(a.activityId(), a.version(), ActivityStatus.ONLINE.code());
            String order = nextOrder();
            marketing.claimInventory(a.activityId(), null, 1, "u1", order);
            marketing.confirmGrant(a.activityId(), order, new BigDecimal("9.90"), null);

            ActivityGrantEntity g = onlyGrant(order);
            assertEquals("USD", g.getCurrency(), "grant 继承活动币种并大写");
            assertEquals("USD", entryRepo.findByGrantNoOrderByIdAsc(g.getGrantNo()).get(0).getCurrency(),
                    "分录继承 grant 币种");
        }
    }

    @Nested
    @DisplayName("金额换算 toMinorExact fail-fast")
    class MoneyConversion {

        @Test
        @DisplayName("2 位内精确换算；亚分 / 溢出抛 ArithmeticException")
        void toMinorExactIsFailFast() {
            assertEquals(990L, BenefitMath.toMinorExact(new BigDecimal("9.90")));
            assertEquals(990L, BenefitMath.toMinorExact(new BigDecimal("9.9")));
            assertEquals(0L, BenefitMath.toMinorExact(new BigDecimal("0.00")));
            assertThrows(ArithmeticException.class,
                    () -> BenefitMath.toMinorExact(new BigDecimal("9.999")), "亚分必须 fail-fast");
            assertThrows(ArithmeticException.class,
                    () -> BenefitMath.toMinorExact(new BigDecimal("1E30")), "溢出 long 必须 fail-fast");
        }
    }

    @Nested
    @DisplayName("recon 对账视图别名契约")
    class ReconViewContract {

        /**
         * {@code recon_src_marketing} 视图只存在于一次性迁移脚本里（ddl-auto 从不建视图），其 9 列别名投影
         * 必须严格对齐 recon {@code MarketingThreeWayScenario.marketingLikeDescriptor}。这里对 H2 fixture
         * 执行<b>真实迁移脚本里的视图 DDL</b> 再断言投影列名：drools 侧改源列名 → 视图引用旧列名建视图即失败；
         * 改别名 / 脚本漂移 → 断言的列名集合不符。跨仓（recon）改描述符仍需人工同步，这条把别名钉成契约。
         */
        @Test
        @DisplayName("对 H2 执行迁移脚本的视图 DDL，投影列名严格对齐 recon 营销侧描述符")
        void viewAliasProjectionMatchesReconDescriptor() throws Exception {
            jdbc.execute(loadReconViewDdl());

            Set<String> projected = jdbc.execute((ConnectionCallback<Set<String>>) con -> {
                try (Statement st = con.createStatement();
                     ResultSet rs = st.executeQuery("SELECT * FROM recon_src_marketing WHERE 1=0")) {
                    ResultSetMetaData md = rs.getMetaData();
                    Set<String> names = new HashSet<>();
                    for (int i = 1; i <= md.getColumnCount(); i++) {
                        names.add(md.getColumnLabel(i).toLowerCase());
                    }
                    return names;
                }
            });

            assertEquals(
                    Set.of("id", "issue_id", "order_no", "ccy", "amount_minor",
                            "entry_type", "biz_status", "biz_time", "posting_time"),
                    projected,
                    "视图别名必须严格对齐 recon MarketingThreeWayScenario.marketingLikeDescriptor");
        }
    }

    // ---- helpers ----

    /** 从仓库根一次性迁移脚本抽出 recon_src_marketing 视图 DDL（不含结尾分号）。 */
    private static String loadReconViewDdl() throws Exception {
        File sql = null;
        for (File d = new File(System.getProperty("user.dir")).getAbsoluteFile(); d != null; d = d.getParentFile()) {
            File f = new File(d, "deploy/mysql-grant-recon-onboarding.sql");
            if (f.isFile()) { sql = f; break; }
        }
        assertNotNull(sql, "找不到 deploy/mysql-grant-recon-onboarding.sql（视图 DDL 无 CI 覆盖）");
        String text = Files.readString(sql.toPath());
        int start = text.indexOf("CREATE OR REPLACE VIEW");
        assertTrue(start >= 0, "迁移脚本缺 recon_src_marketing 视图 DDL");
        int end = text.indexOf(';', start);
        assertTrue(end > start, "视图 DDL 未以分号结束");
        return text.substring(start, end);
    }

    private static long nextSpu() { return SPU.incrementAndGet(); }
    private static String nextOrder() { return "ORD" + ORDER.incrementAndGet(); }

    /** 取某订单唯一的发放记录（本测试每单只 claim 一次）。 */
    private ActivityGrantEntity onlyGrant(String order) {
        List<ActivityGrantEntity> grants = marketing.grantsOfOrder(order);
        assertEquals(1, grants.size(), "该订单应恰有一条发放记录");
        return grants.get(0);
    }

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

    /** 带活动级币种的一口价活动（走 24 参 canonical 构造，末位 currency）。 */
    private ActivityCreateRequest flashReqWithCurrency(String name, long spu, String currency) {
        long now = System.currentTimeMillis();
        return new ActivityCreateRequest(
                null, null, name, "grant-biz", 1, name,
                now - 3_600_000L, now + 3_600_000L, 1, null, 1, 100,
                1, new BigDecimal("9.9"), "价", null, "MAX",
                null, List.of(new ActivityCreateRequest.SpuBinding(1, spu)), null, null,
                null, null, currency);
    }
}
