package com.lrj.drools.activity.service;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.drools.activity.config.GrantOutboxProperties;
import com.lrj.drools.activity.engine.BenefitMath;
import com.lrj.drools.activity.persistence.ActivityGrantEntity;
import com.lrj.drools.activity.persistence.ActivityGrantEntryEntity;
import com.lrj.drools.activity.persistence.ActivityGrantEntryRepository;
import com.lrj.drools.activity.persistence.ActivityGrantOutboxEntity;
import com.lrj.drools.activity.persistence.ActivityGrantOutboxRepository;
import com.lrj.drools.activity.persistence.ActivityGrantRepository;
import com.lrj.drools.activity.persistence.ActivityManageEntity;
import com.lrj.drools.activity.persistence.ActivityManageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * <b>发放台账</b>：抢占库存（claim）/ 冲正（release）/ 按单查发放记录。
 *
 * <p>它从 {@link ActivityMarketingService} 里拆出来，因为两者<b>零共享状态</b>——
 * 配置写入口关心的是「这一版活动长什么样」，台账关心的是「这份优惠发出去没有」，
 * 唯一的交集是 {@link ActivityVersionResolver} 那两条版本定义。
 * {@code ActivityMarketingService} 保留同名委派方法（既有调用方与测试按那组签名写的），
 * 但实现只有这一份。
 *
 * <p><b>拆分不改变任何鉴权边界</b>：{@code console-write-authority} 是按 <b>HTTP 路径</b>枚举的
 * （{@code ActivityResourceServerConfig}），claim / release 仍受同一条规则保护。
 */
@Service
public class GrantService {

    private static final Logger log = LoggerFactory.getLogger(GrantService.class);

    private static final int NOT_DEL = 0;

    /** 币种兜底：活动/发放没显式配币种时按人民币记账，避免 recon 按币种分桶时空币种异常。 */
    private static final String DEFAULT_CURRENCY = "CNY";

    private final ActivityManageRepository manageRepo;
    private final ActivityGrantRepository grantRepo;
    private final ActivityGrantEntryRepository grantEntryRepo;
    private final ActivityGrantOutboxRepository grantOutboxRepo;
    private final ActivityVersionResolver versions;
    private final GrantOutboxProperties outboxProps;
    private final ObjectMapper objectMapper;

    public GrantService(ActivityManageRepository manageRepo,
                        ActivityGrantRepository grantRepo,
                        ActivityGrantEntryRepository grantEntryRepo,
                        ActivityGrantOutboxRepository grantOutboxRepo,
                        ActivityVersionResolver versions,
                        GrantOutboxProperties outboxProps,
                        ObjectMapper objectMapper) {
        this.manageRepo = manageRepo;
        this.grantRepo = grantRepo;
        this.grantEntryRepo = grantEntryRepo;
        this.grantOutboxRepo = grantOutboxRepo;
        this.versions = versions;
        this.outboxProps = outboxProps;
        this.objectMapper = objectMapper;
    }

    /**
     * claim/release 失败的<b>种类</b>——给调用方分流用，与给人看的 {@code reason} 文案分开。
     *
     * <p>从前七个失败点只有一个中文串，controller 于是只能按布尔映射：claim 一律 409、release 一律 404。
     * 后果是「少传一个参数」和「库存真的没了」这两件完全不同的事，客户端看到同一个状态码，
     * 重试逻辑无从分流——把一次参数写错反复重试到活动结束，或者把真的售罄当成自己写错了。
     *
     * <p>它<b>不进响应体</b>（{@link ClaimResult#failureKind} 标了 {@code @JsonIgnore}）：
     * 状态码已经把这个信息表达出去了，加字段等于给已经稳定的 JSON 契约再开一个面。
     */
    public enum FailureKind {
        /** 入参本身不成立（缺 activityId / 数量非正 / 限领活动没带 userId / confirm 金额非正或亚分溢出）。 */
        BAD_REQUEST,
        /** 活动、版本或发放记录不存在（含 confirm 收到未 claim 订单的支付回调）。 */
        NOT_FOUND,
        /** 余量不足或活动不在可用窗口——原子 UPDATE 更新了 0 行。 */
        OUT_OF_STOCK,
        /** 超出每人限领。 */
        PER_USER_LIMIT,
        /**
         * 状态机冲突：目标迁移与当前状态不相容且重试也不会成功（如 confirm 一笔已 RELEASED 的发放）。
         * 与 {@code OUT_OF_STOCK} 同为 409 但语义不同——后者重试可能成，这个换多少次都不成。
         */
        STATE_CONFLICT
    }

    /**
     * claim 结果。{@code ok=false} 时 {@code reason} 说明为什么没抢到；
     * {@code replay=true} 表示这一单本来就领过了（幂等命中，没有产生新的扣减）。
     *
     * @param failureKind 失败种类（{@code ok=true} 时为 null）。仅供服务端分流状态码，不出现在响应 JSON 里
     */
    public record ClaimResult(boolean ok, String activityId, Integer version, int claimed,
                              String reason, boolean replay, Long grantId,
                              @JsonIgnore FailureKind failureKind) {

        public ClaimResult(boolean ok, String activityId, Integer version, int claimed,
                           String reason, boolean replay, Long grantId) {
            this(ok, activityId, version, claimed, reason, replay, grantId, null);
        }

        public ClaimResult(boolean ok, String activityId, Integer version, int claimed, String reason) {
            this(ok, activityId, version, claimed, reason, false, null, null);
        }

        static ClaimResult fail(FailureKind kind, String activityId, Integer version, String reason) {
            return new ClaimResult(false, activityId, version, 0, reason, false, null, kind);
        }
    }

    /**
     * 抢占库存并落发放流水——**秒杀防超发与发放对账的权威动作**。
     *
     * <p><b>为什么必须在写平面、不能在决策链路里做</b>：决策服务连的是只读账号
     * （{@code deploy/mysql-init/01-decision-readonly-user.sql} 只 GRANT SELECT，
     * 且 {@code DecisionDdlGuardTest} 钉死），物理上写不了库。这不是疏漏——
     * 决策热路径是高 QPS 只读，让它去写库会同时毁掉「只读副本可扩」与「写面独占 DDL」两条边界。
     *
     * <p>所以分工是：<b>决策只报价，claim 才是提交</b>。
     * 决策结果里的库存判断是<b>建议性</b>的（读到的那一刻余量可能已被别人抢走，天然 TOCTOU），
     * 真正的裁决只发生在这里的原子 UPDATE 上。调用方拿到决策报价后必须再 claim 一次，
     * claim 失败就是没抢到——<b>不能拿决策成功当作抢到了</b>。
     *
     * <p><b>本轮修掉的三件事</b>：
     * <ol>
     *   <li><b>版本打错行</b>：不传 version 时原来取「最高未删除版本」＝<em>草稿</em>，
     *       而决策发的是「最高 ONLINE 版本」——防超发的闸门装在了另一行数据上，
     *       线上版本的库存一件没少、草稿的库存被扣干净。现在缺省解析成<b>当前线上版本</b>。</li>
     *   <li><b>扣减谓词太松</b>：原来只判 {@code isDel + inventory >= n}，
     *       已下线、未开始、已结束、草稿版本的库存<b>都能被扣干净</b>。现在补上状态与时间窗。</li>
     *   <li><b>不幂等</b>：原来同一个用户连点两次就扣两次，因为没有任何东西记得「这一单领过了」。
     *       现在先插 {@link ActivityGrantEntity}（唯一约束 {@code tenant+order+activity}）再扣减，
     *       重复提交在数据库层被挡住并返回首次结果。</li>
     * </ol>
     *
     * <p><b>顺序不能反</b>：先插流水后扣库存。反过来（先扣后插）时，插入撞唯一约束会回滚整个事务，
     * 库存看似安全；但如果扣减成功而插入因为别的原因失败，就会出现「扣了库存却没有账」的黑洞。
     * 先插流水则让唯一约束在<b>任何扣减发生之前</b>就拦住重复请求。
     *
     * @param orderId 订单号。<b>幂等键的一半</b>；为空时退化成不幂等（并在结果里说明），
     *                因为没有订单号就无从判断「是不是同一单」
     * @param userId  领取人。每人限领按它计数；为空时该活动若配了 {@code userInventory} 一律拒绝——
     *                无从判断是不是同一个人时放行，等于限领形同虚设
     */
    @Transactional
    public ClaimResult claimInventory(String activityId, Integer version, Integer quantity,
                                      String userId, String orderId) {
        int n = quantity == null ? 1 : quantity;
        if (activityId == null || activityId.isBlank()) {
            return ClaimResult.fail(FailureKind.BAD_REQUEST, activityId, version, "缺 activityId");
        }
        if (n <= 0) return ClaimResult.fail(FailureKind.BAD_REQUEST, activityId, version, "扣减数量必须为正");

        // 版本缺省 → 当前**线上**版本（不是最高版本，见方法注释第 1 条）
        Integer v = version;
        if (v == null) {
            v = versions.currentOnlineVersion(activityId);
            if (v == null) {
                return ClaimResult.fail(FailureKind.NOT_FOUND, activityId, null, "活动不存在或当前没有上线版本");
            }
        }

        ActivityManageEntity row = manageRepo.findFirstByActivityIdAndVersionAndIsDel(activityId, v, NOT_DEL)
                .orElse(null);
        if (row == null) return ClaimResult.fail(FailureKind.NOT_FOUND, activityId, v, "活动版本不存在");

        // ① 幂等：这一单的这个活动领过没有
        String order = blankToNull(orderId);
        if (order != null) {
            var dup = grantRepo.findFirstByOrderIdAndActivityId(order, activityId);
            if (dup.isPresent()) {
                ActivityGrantEntity g = dup.get();
                return new ClaimResult(true, activityId, g.getVersion(), g.getQuantity(),
                        null, true, g.getId());
            }
        }

        // ② 每人限领：userInventory > 0 时按流水计数。**拿不到 userId 就拒绝**——
        // 无从判断是不是同一个人时放行，等于这条限制不存在。
        Integer perUser = row.getUserInventory();
        String user = blankToNull(userId);
        if (perUser != null && perUser > 0) {
            if (user == null) {
                // 缺参而不是「领满了」：客户端补上 userId 就能成功，重试同一个请求永远不会成功。
                return ClaimResult.fail(FailureKind.BAD_REQUEST, activityId, v,
                        "该活动限每人 " + perUser + " 份，claim 必须带 userId");
            }
            int already = grantRepo.claimedQuantityByUser(activityId, user);
            if (already + n > perUser) {
                return ClaimResult.fail(FailureKind.PER_USER_LIMIT, activityId, v,
                        "超出每人限领（已领 " + already + "，本次 " + n + "，上限 " + perUser + "）");
            }
        }

        // ③ 先落流水，再扣库存。**顺序不能反**：
        // 唯一约束要在任何扣减发生之前就拦住「并发的同一单重复提交」，
        // 反过来（先扣后插）时两个并发请求会各自扣成功，再由其中一个撞约束回滚——
        // 回滚能救回库存，但那要靠事务边界一路不出错，而不是靠一条约束。
        Instant now = Instant.now();
        ActivityGrantEntity grant = null;
        if (order != null) {
            grant = new ActivityGrantEntity(activityId, v, user, order, n,
                    null, ActivityGrantEntity.HELD, null, now);
            // 发放对账地基：claim 即生成全局唯一发放号（recon 的 issue_id/match_key）+ 继承活动币种。
            // amount/amount_minor/entry_type 留空——发放金额到 confirm 才落，分录到 confirm/release 才追加。
            grant.setGrantNo(UUID.randomUUID().toString());
            grant.setCurrency(coalesceCurrency(row.getCurrency()));
            grantRepo.saveAndFlush(grant);
        }

        // ④ 判断余量与减一压在同一条 UPDATE 里，靠数据库对同一行的串行化防超发。
        // **绝不能改成先查后减**——那是 check-then-act 竞态，低并发测不出来、大促必现。
        int affected = manageRepo.decrementInventory(activityId, v, n, now);
        if (affected <= 0) {
            // 没抢到（余量不足 / 活动已下线 / 不在活动期）→ 把刚插的流水撤掉。
            // 不能留着：一条 HELD 却没有对应扣减的记录，在对账上就是「有账无货」，
            // 而且会永久占掉这个用户的限领额度、并让这一单再也 claim 不了（幂等分支会命中它）。
            // 用显式删除而不是抛异常回滚整个事务，是为了保住既有契约——
            // 调用方一直按「返回 ok=false」处理没抢到，抛异常会让所有调用点的降级逻辑失效。
            if (grant != null) {
                grantRepo.delete(grant);
                grantRepo.flush();
            }
            return ClaimResult.fail(FailureKind.OUT_OF_STOCK, activityId, v, "库存不足或活动不可用");
        }
        return new ClaimResult(true, activityId, v, n, null, false, grant == null ? null : grant.getId());
    }

    /** 兼容旧签名（无 userId/orderId）：退化成不幂等、不执行每人限领。新调用方一律用五参版本。 */
    @Transactional
    public ClaimResult claimInventory(String activityId, Integer version, Integer quantity) {
        return claimInventory(activityId, version, quantity, null, null);
    }

    /**
     * <b>确认发放（支付成功回调）</b>——HELD→CONFIRMED，并向分录台账追加一条 ISSUE 分录（{@code +amount×100}）。
     * 这是营销发放对账的<b>起账点</b>：从这一刻起，这笔发放才对 recon 可见（有 amount_minor 的分录）。
     *
     * <p><b>幂等硬保证 = CAS {@code WHERE state='HELD'}</b>（{@link ActivityGrantRepository#confirmIfHeld}）：
     * <ul>
     *   <li>{@code affected==1} → 首次确认，同事务追 ISSUE 分录（{@code uk_entry_grant_type} 兜底防重）；</li>
     *   <li>{@code affected==0} → 回读一次<b>仅用于响应分流</b>（非 check-then-act）：
     *       无行 → {@code NOT_FOUND}（收到未 claim 订单的回调，不凭空建账）；
     *       已 {@code CONFIRMED} → 幂等重放（{@code replay=true}，<b>不覆盖金额、不重复追分录</b>，first-write-wins）；
     *       已 {@code RELEASED} → {@code STATE_CONFLICT}（退款先于迟到回调，<b>绝不 RELEASED→CONFIRMED</b>）。</li>
     * </ul>
     *
     * <p><b>金额来源</b>：{@code amount}（元）由回调携带，drools 不重跑决策、不强校验 {@code amount==报价}
     * （decision 是无状态只读平面，无报价表可回查；实体注释已明确「记的是发放，可能≠报价」）。
     * 系统只对 amount 做 {@code >0} 与 {@link BenefitMath#toMinorExact 精确换算}（亚分/溢出 fail-fast→400）校验。
     *
     * <p><b>租户上下文</b>：confirm 与 claim/release 走同一条鉴权链，{@code @TenantId} 为 CAS 自动追加租户谓词、
     * 为分录 insert 自动落租户值——因此调用方必须是租户上下文已就绪的内部服务（订单/支付适配层），非匿名 webhook。
     *
     * @param decisionId 报价↔发放锚点（可选）；只落行不强校验
     */
    @Transactional
    public ClaimResult confirmGrant(String activityId, String orderId, BigDecimal amount, String decisionId) {
        String order = blankToNull(orderId);
        if (order == null || activityId == null || activityId.isBlank()) {
            return ClaimResult.fail(FailureKind.BAD_REQUEST, activityId, null, "缺 orderId 或 activityId");
        }
        if (amount == null || amount.signum() <= 0) {
            return ClaimResult.fail(FailureKind.BAD_REQUEST, activityId, null, "确认金额必须为正");
        }
        // 亚分（scale>2）/ 溢出 fail-fast → 400，绝不静默截断/四舍五入（记的是既定金额，亚分即脏输入）。
        long minor;
        try {
            minor = BenefitMath.toMinorExact(amount);
        } catch (ArithmeticException e) {
            return ClaimResult.fail(FailureKind.BAD_REQUEST, activityId, null,
                    "金额精度非法（最多 2 位小数）或超出可记范围: " + amount);
        }

        Instant now = Instant.now();
        int affected = grantRepo.confirmIfHeld(order, activityId, amount, decisionId, now);
        if (affected == 1) {
            // 首次确认：回读拿 grant_no/currency（CAS 的 clearAutomatically 保证读到 CONFIRMED 新态），追 ISSUE 分录。
            ActivityGrantEntity g = grantRepo.findFirstByOrderIdAndActivityId(order, activityId).orElseThrow();
            // 存量兜底：本特性上线前的 HELD 行 grant_no 可能为 NULL（ddl-auto 加的是可空列）。迁移回填前的
            // 迟到回调若把 NULL 传给分录表（grant_no NOT NULL）会 DataIntegrityViolation→500 卡死确认——
            // 这里惰性补一个 UUID（与 claim 同源），dirty checking 同事务落库，既解卡死又不依赖迁移先跑。
            String grantNo = g.getGrantNo();
            if (grantNo == null) {
                grantNo = UUID.randomUUID().toString();
                g.setGrantNo(grantNo);
            }
            grantEntryRepo.saveAndFlush(new ActivityGrantEntryEntity(
                    grantNo, order, activityId, ActivityGrantEntryEntity.ISSUE,
                    minor, coalesceCurrency(g.getCurrency()), now, now));
            // 同事务写发放传播 outbox（GRANT_ISSUED，+X）：发放状态 + 分录 + 事件原子落库，杜绝「发了钱但事件丢」。
            // 首次确认由 CAS 保证只发生一次，故此处至多入队一条；门控关时不写（对既有零影响）。
            enqueueGrantEvent(ActivityGrantOutboxEntity.EVENT_GRANT_ISSUED, ActivityGrantEntryEntity.ISSUE,
                    grantNo, order, activityId, minor, coalesceCurrency(g.getCurrency()), now);
            return new ClaimResult(true, activityId, g.getVersion(), g.getQuantity(), null, false, g.getId());
        }

        // affected==0：回读仅用于响应分流，不改写任何状态。
        ActivityGrantEntity g = grantRepo.findFirstByOrderIdAndActivityId(order, activityId).orElse(null);
        if (g == null) {
            return ClaimResult.fail(FailureKind.NOT_FOUND, activityId, null,
                    "没有对应的 HELD 发放记录（未 claim 的订单不凭空建账）");
        }
        if (ActivityGrantEntity.CONFIRMED.equals(g.getState())) {
            // 幂等重放：不覆盖首次金额、不重复追分录（first-write-wins）。
            // 但迟到回调携带**不同**金额时，静默丢弃会让这笔金额矛盾无迹可寻——留一条 warn 供 recon 归因。
            if (g.getAmount() != null && g.getAmount().compareTo(amount) != 0) {
                log.warn("[grant] confirm 重放金额不一致 order={} activity={} 保留={} 丢弃={}",
                        order, activityId, g.getAmount(), amount);
            }
            return new ClaimResult(true, activityId, g.getVersion(), g.getQuantity(), "已确认", true, g.getId());
        }
        // RELEASED（退款先于迟到回调）→ 冲突；绝不把已冲正的发放改回已确认。
        return ClaimResult.fail(FailureKind.STATE_CONFLICT, activityId, g.getVersion(),
                "发放已释放，不能再确认（RELEASED→CONFIRMED 被拒）");
    }

    /**
     * 释放已发放的份额并归还库存——退款 / 取消 / 超时的冲正入口。
     *
     * <p>此前这条路径完全不存在：订单取消后库存永久蒸发，且用户的「每人限领」额度也一并作废。
     * 幂等：已经 RELEASED 的记录直接返回成功，不会重复加库存、不会重复追分录。
     *
     * <p><b>分录台账（追加式）</b>：只有 {@code CONFIRMED→RELEASED} 才是真冲正——追加一条 REVERSAL 分录，
     * 金额 = <b>取负已存 ISSUE 分额</b>（不用 {@code -amount×100} 重算，杜绝漂移、天然避开 amount 为 null）。
     * {@code HELD→RELEASED}（未付即取消）从未 ISSUE、无分录，绝不凭空产生一笔没有对应发放的冲正。
     */
    @Transactional
    public ClaimResult releaseGrant(String orderId, String activityId) {
        String order = blankToNull(orderId);
        if (order == null || activityId == null || activityId.isBlank()) {
            // 缺参 ≠ 查无此单：前者补上参数就能成功，后者换多少次都不会成功。
            return ClaimResult.fail(FailureKind.BAD_REQUEST, activityId, null, "缺 orderId 或 activityId");
        }
        // 状态迁移走 CAS（复刻 confirmIfHeld），不再「读快照→算 wasConfirmed→save 全行」——
        // 老路径与并发 confirmGrant 有丢失更新竞态，且两个并发 release 会各自还一次库存。
        // 先试 HELD 再试 CONFIRMED：状态机无回边（HELD→CONFIRMED→RELEASED），两次 CAS 都落空即已 RELEASED。
        Instant now = Instant.now();
        boolean wasConfirmed;
        if (grantRepo.releaseIfHeld(order, activityId, now) == 1) {
            wasConfirmed = false;
        } else if (grantRepo.releaseIfConfirmed(order, activityId, now) == 1) {
            wasConfirmed = true;
        } else {
            ActivityGrantEntity g = grantRepo.findFirstByOrderIdAndActivityId(order, activityId).orElse(null);
            if (g == null) return ClaimResult.fail(FailureKind.NOT_FOUND, activityId, null, "没有对应的发放记录");
            // 本次 CAS 未命中 → 已被（并发或先前的）释放置为 RELEASED：幂等重放，不重复加库存、不重复追分录。
            return new ClaimResult(true, activityId, g.getVersion(), 0, "已释放", true, g.getId());
        }

        // CAS 已原子置 RELEASED；回读仅取 version/quantity/grant_no（数据用途，非状态决策）。
        ActivityGrantEntity g = grantRepo.findFirstByOrderIdAndActivityId(order, activityId).orElseThrow();
        if (wasConfirmed) {
            appendReversalIfIssued(g, now);
        }
        // 归还不判活动状态与时间窗：活动结束之后仍可能有退款进来（见 incrementInventory 的说明）
        manageRepo.incrementInventory(activityId, g.getVersion(), g.getQuantity(), now);
        return new ClaimResult(true, activityId, g.getVersion(), g.getQuantity(), null, false, g.getId());
    }

    /**
     * 对已确认发放追加 REVERSAL 冲正分录——金额取负已存 ISSUE 分额（不重算）。
     *
     * <p>null 守卫：{@code grant_no} 为空（旧三参 claim 的历史遗留）或找不到 ISSUE 分录、或其分额为空时，
     * <b>不追加</b>——没有对应 ISSUE 就不该产生凭空的冲正，宁可不写也不写一笔孤儿 REVERSAL。
     * {@code uk_entry_grant_type} 保证 REVERSAL 至多一条（release 幂等已在上游拦重复释放）。
     */
    private void appendReversalIfIssued(ActivityGrantEntity g, Instant now) {
        String grantNo = g.getGrantNo();
        if (grantNo == null) return;
        ActivityGrantEntryEntity issue =
                grantEntryRepo.findFirstByGrantNoAndEntryType(grantNo, ActivityGrantEntryEntity.ISSUE).orElse(null);
        if (issue == null || issue.getAmountMinor() == null) return;
        long reversalMinor = -issue.getAmountMinor();
        String currency = coalesceCurrency(issue.getCurrency());
        grantEntryRepo.saveAndFlush(new ActivityGrantEntryEntity(
                grantNo, issue.getOrderId(), g.getActivityId(), ActivityGrantEntryEntity.REVERSAL,
                reversalMinor, currency, now, now));
        // 存量 cutover 兜底：若这笔发放在门控关时确认（写了 ISSUE 分录，但没写 GRANT_ISSUED 事件），翻开关后
        // 再 release，只发 GRANT_REVERSED 会给下游一条无对应 +X 发放的孤儿冲正，按 grant_no 三方 join 永久对不平。
        // 故在写 REVERSED 前先按幂等补发对应 GRANT_ISSUED（+X，用已存 ISSUE 分额重建）：正常路径 confirm 已入队，
        // (grant_no,event_type) 软查重使其为 no-op；仅 cutover 缺口才真正补发，使下游 +X/−X 成对、净额为零。
        enqueueGrantEvent(ActivityGrantOutboxEntity.EVENT_GRANT_ISSUED, ActivityGrantEntryEntity.ISSUE,
                grantNo, issue.getOrderId(), g.getActivityId(), issue.getAmountMinor(), currency, now);
        // 同事务写发放传播 outbox（GRANT_REVERSED，−X）：只有真追了 REVERSAL 分录才发事件，绝不产生没有对应
        // 冲正的孤儿事件。HELD→RELEASED 从不进这里（无 ISSUE），故天然不写。CONFIRMED→RELEASED 由 CAS 保证一次。
        enqueueGrantEvent(ActivityGrantOutboxEntity.EVENT_GRANT_REVERSED, ActivityGrantEntryEntity.REVERSAL,
                grantNo, issue.getOrderId(), g.getActivityId(), reversalMinor, currency, now);
    }

    /**
     * 同事务把一条发放事件追加进 {@code activity_grant_outbox}（PENDING）。门控关时直接返回（对既有零影响）。
     *
     * <p><b>幂等</b>：先按 {@code (grant_no, event_type)} 软查重（唯一约束是硬兜底）——confirm/release 的首次
     * 写点由 CAS 保证只发生一次，这层软查重是防御性的，避免任何重复路径在同事务内触发唯一键异常污染外层事务
     * （PostgreSQL 上会毒化整个事务）。payload 即下游消费的事件全文 JSON，带幂等键 {@code grant_no:event_type}。
     */
    private void enqueueGrantEvent(String eventType, String entryType, String grantNo, String orderId,
                                   String activityId, long amountMinor, String currency, Instant now) {
        if (!outboxProps.isEnabled()) return;
        if (grantOutboxRepo.findFirstByGrantNoAndEventType(grantNo, eventType).isPresent()) {
            return; // 幂等重放：事件已入队，不重复写（uk 兜底）。
        }
        String payload = buildEventPayload(eventType, entryType, grantNo, orderId, activityId,
                amountMinor, currency, now);
        grantOutboxRepo.save(new ActivityGrantOutboxEntity(
                grantNo, orderId, activityId, eventType, entryType, amountMinor, currency, payload, now));
    }

    /** 构造事件全文 JSON（供下游按 grant_no 落 issue_id 消费；键序稳定用 LinkedHashMap）。 */
    private String buildEventPayload(String eventType, String entryType, String grantNo, String orderId,
                                     String activityId, long amountMinor, String currency, Instant now) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("idempotencyKey", grantNo + ":" + eventType);
        body.put("grantNo", grantNo);
        body.put("orderId", orderId);
        body.put("activityId", activityId);
        body.put("eventType", eventType);
        body.put("entryType", entryType);
        body.put("amountMinor", amountMinor);
        body.put("currency", currency);
        body.put("bizTime", now.toString());
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            // 简单 map 序列化理论上不会失败；万一失败则连发放一起回滚（原子性优先于「发了但事件无载荷」）。
            throw new IllegalStateException("发放事件 payload 序列化失败 grantNo=" + grantNo, e);
        }
    }

    /** 某订单上的全部发放记录。客服「这一单用了哪些优惠」的数据源。 */
    public List<ActivityGrantEntity> grantsOfOrder(String orderId) {
        String order = blankToNull(orderId);
        return order == null ? List.of() : grantRepo.findByOrderId(order);
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    /** 币种兜底：空/空白 → CNY（分录 amount_minor 与 recon 分桶都要求非空币种）。 */
    private static String coalesceCurrency(String ccy) {
        return blankToNull(ccy) == null ? DEFAULT_CURRENCY : ccy;
    }
}
