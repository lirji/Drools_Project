package com.lrj.drools.activity.service;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.lrj.drools.activity.persistence.ActivityGrantEntity;
import com.lrj.drools.activity.persistence.ActivityGrantRepository;
import com.lrj.drools.activity.persistence.ActivityManageEntity;
import com.lrj.drools.activity.persistence.ActivityManageRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

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

    private static final int NOT_DEL = 0;

    private final ActivityManageRepository manageRepo;
    private final ActivityGrantRepository grantRepo;
    private final ActivityVersionResolver versions;

    public GrantService(ActivityManageRepository manageRepo,
                        ActivityGrantRepository grantRepo,
                        ActivityVersionResolver versions) {
        this.manageRepo = manageRepo;
        this.grantRepo = grantRepo;
        this.versions = versions;
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
        /** 入参本身不成立（缺 activityId / 数量非正 / 限领活动没带 userId）。 */
        BAD_REQUEST,
        /** 活动、版本或发放记录不存在。 */
        NOT_FOUND,
        /** 余量不足或活动不在可用窗口——原子 UPDATE 更新了 0 行。 */
        OUT_OF_STOCK,
        /** 超出每人限领。 */
        PER_USER_LIMIT
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
     * 释放已发放的份额并归还库存——退款 / 取消 / 超时的冲正入口。
     *
     * <p>此前这条路径完全不存在：订单取消后库存永久蒸发，且用户的「每人限领」额度也一并作废。
     * 幂等：已经 RELEASED 的记录直接返回成功，不会重复加库存。
     */
    @Transactional
    public ClaimResult releaseGrant(String orderId, String activityId) {
        String order = blankToNull(orderId);
        if (order == null || activityId == null || activityId.isBlank()) {
            // 缺参 ≠ 查无此单：前者补上参数就能成功，后者换多少次都不会成功。
            return ClaimResult.fail(FailureKind.BAD_REQUEST, activityId, null, "缺 orderId 或 activityId");
        }
        ActivityGrantEntity g = grantRepo.findFirstByOrderIdAndActivityId(order, activityId).orElse(null);
        if (g == null) return ClaimResult.fail(FailureKind.NOT_FOUND, activityId, null, "没有对应的发放记录");
        if (ActivityGrantEntity.RELEASED.equals(g.getState())) {
            // 幂等：重复释放不重复加库存
            return new ClaimResult(true, activityId, g.getVersion(), 0, "已释放", true, g.getId());
        }

        Instant now = Instant.now();
        g.setState(ActivityGrantEntity.RELEASED);
        g.setModifiedStime(now);
        grantRepo.save(g);
        // 归还不判活动状态与时间窗：活动结束之后仍可能有退款进来（见 incrementInventory 的说明）
        manageRepo.incrementInventory(activityId, g.getVersion(), g.getQuantity(), now);
        return new ClaimResult(true, activityId, g.getVersion(), g.getQuantity(), null, false, g.getId());
    }

    /** 某订单上的全部发放记录。客服「这一单用了哪些优惠」的数据源。 */
    public List<ActivityGrantEntity> grantsOfOrder(String orderId) {
        String order = blankToNull(orderId);
        return order == null ? List.of() : grantRepo.findByOrderId(order);
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
