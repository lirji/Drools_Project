package com.lrj.drools.activity.service;

import com.lrj.drools.activity.domain.ActivityCreateRequest;
import com.lrj.drools.activity.domain.ActivityStatus;
import com.lrj.drools.activity.domain.ActivityType;
import com.lrj.drools.activity.domain.BenefitForm;
import com.lrj.drools.activity.domain.ConditionNode;
import com.lrj.drools.activity.domain.RuleScene;
import com.lrj.drools.activity.domain.StackStrategy;
import com.lrj.drools.activity.engine.ActivityDrlBuilder;
import com.lrj.drools.activity.engine.ActivityDrlBuilder.EligibilityRuleDef;
import com.lrj.drools.activity.engine.ActivityRuleRuntimeService;
import com.lrj.drools.activity.engine.RangePayload;
import com.lrj.drools.activity.engine.RuleConditionTranslator;
import com.lrj.drools.activity.engine.RuleSchemaRegistry;
import com.lrj.drools.activity.persistence.*;
import com.lrj.drools.activity.tenant.ActorContext;
import com.lrj.drools.activity.tenant.TenantContext;
import org.springframework.beans.factory.annotation.Value;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import com.lrj.drools.activity.persistence.ActivityManageEntity;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 活动营销核心服务：创建 / 版本化编辑 / 上下线 / 详情。
 * 收敛自来源 {@code ActivityAdminPlatformManageServiceImpl}。
 *
 * 事务：创建/编辑整体 {@code @Transactional}，规则翻译+编译校验发生在写库前——失败不落任何表。
 * 版本化：编辑时旧版本行 {@code isDel=1}（带影响行数校验做并发保护），新行 version+1。
 * 幂等：同 {@code requestId} 重复提交返回首次结果。
 */
@Service
public class ActivityMarketingService {

    private static final int NOT_DEL = 0;
    private static final int DEL = 1;
    private static final int ENABLED = 1;
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("999999");

    private final ActivityManageRepository manageRepo;
    private final ActivityRuleRepository ruleRepo;
    private final ActivityConditionRepository conditionRepo;
    private final ActivitySpuBindingRepository bindingRepo;
    private final ActivityGiftRepository giftRepo;
    private final PoolRefRepository poolRefRepo;
    private final ActivityStrategyRepository strategyRepo;
    private final ActivityIdempotencyRepository idempotencyRepo;
    private final ActivityGrantRepository grantRepo;

    private final RuleConditionTranslator translator;
    private final RuleSchemaRegistry schemaRegistry;
    private final ActivityDrlBuilder drlBuilder;
    private final ActivityRuleRuntimeService ruleRuntime;
    private final ActivityPoolMatchService poolMatchService;
    private final ArtifactService artifactService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicInteger seq = new AtomicInteger(0);

    /** P1-8 四眼开关。默认 false（不破 demo/现有测试）；开启后发布(上线)要求审批人≠提交人。 */
    @Value("${activity.marketing.four-eyes-enabled:false}")
    private boolean fourEyesEnabled;

    public ActivityMarketingService(ActivityManageRepository manageRepo,
                                    ActivityRuleRepository ruleRepo,
                                    ActivityConditionRepository conditionRepo,
                                    ActivitySpuBindingRepository bindingRepo,
                                    ActivityGiftRepository giftRepo,
                                    PoolRefRepository poolRefRepo,
                                    ActivityStrategyRepository strategyRepo,
                                    ActivityIdempotencyRepository idempotencyRepo,
                                    ActivityGrantRepository grantRepo,
                                    RuleConditionTranslator translator,
                                    RuleSchemaRegistry schemaRegistry,
                                    ActivityDrlBuilder drlBuilder,
                                    ActivityRuleRuntimeService ruleRuntime,
                                    ActivityPoolMatchService poolMatchService,
                                    ArtifactService artifactService) {
        this.manageRepo = manageRepo;
        this.ruleRepo = ruleRepo;
        this.conditionRepo = conditionRepo;
        this.bindingRepo = bindingRepo;
        this.giftRepo = giftRepo;
        this.poolRefRepo = poolRefRepo;
        this.strategyRepo = strategyRepo;
        this.idempotencyRepo = idempotencyRepo;
        this.grantRepo = grantRepo;
        this.translator = translator;
        this.schemaRegistry = schemaRegistry;
        this.drlBuilder = drlBuilder;
        this.ruleRuntime = ruleRuntime;
        this.poolMatchService = poolMatchService;
        this.artifactService = artifactService;
    }

    // ------------------------------------------------------------------ 创建 / 编辑

    @Transactional(rollbackFor = Exception.class)
    public CreateResult create(ActivityCreateRequest req) {
        validateCommon(req);

        // ISSUE-07 幂等：查独立幂等表（覆盖 create 与 edit 重放；租户由 @TenantId 自动作用域）。
        // 命中=顺序重放，返回首次结果；空白 requestId 归一 null=不启用幂等（ISSUE-05）。
        String reqId = blankToNull(req.requestId());
        if (reqId != null) {
            var dup = idempotencyRepo.findFirstByRequestId(reqId);
            if (dup.isPresent()) {
                ActivityIdempotencyEntity e = dup.get();
                return new CreateResult(e.getActivityId(), e.getVersion(), e.getActivityStatus(), true, 0, List.of());
            }
        }

        Instant now = Instant.now();
        boolean isEdit = req.activityId() != null && !req.activityId().isBlank();
        String activityId;
        int version;

        if (isEdit) {
            activityId = req.activityId();
            ActivityManageEntity current = manageRepo
                    .findFirstByActivityIdAndIsDelOrderByVersionDesc(activityId, NOT_DEL)
                    .orElseThrow(() -> new IllegalArgumentException("活动不存在: " + req.activityId()));
            if (ActivityStatus.OFFLINE.code() == current.getActivityStatus()) {
                throw new IllegalArgumentException("已下线活动不可编辑: " + activityId);
            }
            version = current.getVersion() + 1;

            // P0-4：**编辑不再下线正在服务的版本**。
            //
            // 旧实现无条件软删当前版本再建 v+1(状态 NORMAL)，而决策取「未删除的最高版本」再判 ONLINE——
            // 于是运营改一个错别字，该活动在下一次决策里立刻消失，直到重新走一遍上线（四眼开着还得换人审批）。
            //
            // 新语义：线上版本与草稿并存。
            //   · 当前版本已上线 → 保留它继续服务，另建 v+1 草稿；发布草稿时才做指针切换（见 changeStatus）
            //   · 当前版本是草稿 → 直接顶掉（草稿不该堆积），沿用原子软删做并发保护
            if (ActivityStatus.ONLINE.code() == current.getActivityStatus()) {
                // 线上版本要留着，只能用「目标版本号是否已被占」做并发保护。
                // 比软删弱（非原子），但同 activityId 的并发编辑在本 demo 不是真实场景；
                // 真要收紧应给 (tenant_id, activity_id, version) 加唯一约束，由 DB 兜底。
                if (manageRepo.findFirstByActivityIdAndVersionAndIsDel(activityId, version, NOT_DEL).isPresent()) {
                    throw new IllegalStateException("活动版本冲突（并发编辑），请重试: " + activityId);
                }
            } else {
                int affected = manageRepo.softDeleteVersion(activityId, current.getVersion(), now);
                if (affected == 0) {
                    throw new IllegalStateException("活动版本冲突（并发编辑），请重试: " + activityId);
                }
            }
        } else {
            activityId = generateActivityId();
            version = 1;
        }

        // 资格条件翻译 + 严格编译校验（失败抛异常 → 整体回滚，什么都不落库）
        // schema 按 (当前租户, bizLine) 解析（无覆盖时回落共享默认 schema）
        String constraint = translator.translate(req.eligibilityConditionTree(),
                schemaRegistry.resolve(currentTenant(), req.bizLine()));
        String eligDrl = null;
        if (constraint != null) {
            eligDrl = drlBuilder.buildEligibilityDrl(List.of(new EligibilityRuleDef(activityId, constraint)), true);
            ruleRuntime.compileOrGet(eligDrl); // 编译失败带行号抛出
        }
        // range 列校验（按权益形态分叉，见 validateRangeColumn）
        validateRangeColumn(req);

        // 落库：基础层 → 规则层 → 条件层 → 买赠 → 手动绑定 → 池引用 → 自动圈选物化
        Integer status = ActivityStatus.NORMAL.code();
        saveManage(req, activityId, version, status, now);
        saveRule(req, activityId, version, now);
        saveCondition(req.eligibilityConditionTree(), constraint, activityId, version, now);
        saveGifts(req, activityId, version, now);
        saveManualBindings(req, activityId, version, now);
        int autoBound = savePoolRefsAndAutoBind(req, activityId, version, now);
        saveStrategyIfPresent(req, now);

        // P1-9：冻结本版本为不可变 artifact（pin schema 版本 + 引用字段 + 资格 DRL），供发布预热与硬失效判定。
        artifactService.snapshot(activityId, version, req.bizLine(), eligDrl, req.eligibilityConditionTree());

        // ISSUE-07：登记幂等结果（create/edit 统一）。同事务内 flush，并发相同 requestId 撞唯一约束 → 整事务回滚(无孤儿) → 409。
        recordIdempotency(reqId, activityId, version, status, now);

        return new CreateResult(activityId, version, status, false, autoBound, declarativeOnlyWarnings(req));
    }

    /** 登记 requestId → 首次结果。null=不启用幂等，跳过。 */
    private void recordIdempotency(String reqId, String activityId, Integer version, Integer status, Instant now) {
        if (reqId == null) {
            return;
        }
        try {
            idempotencyRepo.saveAndFlush(new ActivityIdempotencyEntity(reqId, activityId, version, status, now));
        } catch (DataIntegrityViolationException e) {
            // 并发相同 requestId：唯一约束在此同步暴露 → 转 409（整事务由 rollbackFor 回滚，刚建的业务行不残留）。
            throw new IllegalStateException("并发重复请求(requestId)，请重试: " + reqId);
        }
    }

    /** 编辑等价于带 activityId 的 create。 */
    @Transactional(rollbackFor = Exception.class)
    public CreateResult updateByVersion(ActivityCreateRequest req) {
        if (req.activityId() == null || req.activityId().isBlank()) {
            throw new IllegalArgumentException("编辑必须带 activityId");
        }
        return create(req);
    }

    // ------------------------------------------------------------------ 上下线

    @Transactional(rollbackFor = Exception.class)
    public CreateResult changeStatus(String activityId, Integer version, Integer targetStatus) {
        ActivityManageEntity row = (version != null)
                ? manageRepo.findFirstByActivityIdAndVersionAndIsDel(activityId, version, NOT_DEL)
                    .orElseThrow(() -> new IllegalArgumentException("活动版本不存在: " + activityId + " v" + version))
                : manageRepo.findFirstByActivityIdAndIsDelOrderByVersionDesc(activityId, NOT_DEL)
                    .orElseThrow(() -> new IllegalArgumentException("活动不存在: " + activityId));

        ActivityStatus target = ActivityStatus.fromCode(targetStatus);
        if (target == null) throw new IllegalArgumentException("目标状态非法: " + targetStatus);
        // P1-8 四眼：发布(上线)是敏感动作，开关开启时要求审批人身份存在且 ≠ 提交人（提交人不能自审自发）。
        if (fourEyesEnabled && target == ActivityStatus.ONLINE) {
            enforceFourEyes(row);
        }
        // P0-4 原子指针切换：新版本上线的同一事务里，把该活动其它仍处于 ONLINE 的版本退役。
        // 没有这一步，编辑不下线之后会出现「v1 与 v2 同时在线」，决策取谁取决于实现细节而非发布动作。
        if (target == ActivityStatus.ONLINE) {
            for (ActivityManageEntity old : manageRepo.findByActivityIdAndActivityStatusAndIsDel(
                    activityId, ActivityStatus.ONLINE.code(), NOT_DEL)) {
                if (old.getVersion() != null && !old.getVersion().equals(row.getVersion())) {
                    old.setActivityStatus(ActivityStatus.OFFLINE.code());
                    old.setModifiedStime(Instant.now());
                    manageRepo.save(old);
                }
            }
        }
        row.setActivityStatus(target.code());
        row.setModifiedStime(Instant.now());
        manageRepo.save(row);
        // 发布代际是 decision 侧唯一的「配置变了」信号，**任何状态变化都要发**——只在上线时发的后果是
        // 下线传播不出去，decision 的快照继续按原配置发钱（见 ArtifactService.onStatusChanged 的说明）。
        // 同事务提交：状态与代际要么一起生效、要么一起回滚，不会出现「状态变了但没人知道」。
        artifactService.onStatusChanged(row.getActivityId(), row.getVersion(),
                row.getBizLine(), row.getTenantId());
        return new CreateResult(row.getActivityId(), row.getVersion(), row.getActivityStatus(), false, 0, List.of());
    }

    /**
     * 批量改状态（PR-5）。**逐条独立事务**——一条失败不能回滚已成功的那些。
     *
     * <p>大促前批量下线几十个活动是真实场景，而「全成功或全失败」在这里是错的语义：
     * 运营要的是「尽量都下线，然后告诉我哪几个没成功、为什么」。
     * 所以本方法不加 {@code @Transactional}，由 {@link #changeStatus} 各自的事务边界兜底，
     * 失败逐条记进回执。
     *
     * <p>评审点名四家设计稿共同缺失的正是这个回执——只给「批量操作条」而不给部分失败反馈，
     * 运营点完不知道到底成了几个。
     *
     * <p><b>入参携带显式 version，不是只给 id</b>。最初的实现传 {@code version=null}，落到
     * {@link #changeStatus} 就是「取最高未删除版本」——而 P0-4 之后线上 v1 与草稿 v2 是**并存**的
     * （见 {@link #create} 里编辑不软删线上版的分支），最高版本恰恰是那个还没发布的草稿。
     * 于是「批量下线 23 个」把 23 个草稿置成下线，**正在发钱的线上版一个都没停**，
     * 而回执还报 23 个全部成功——这正是本功能要消灭的那类静默失败。
     * 现在由调用方按它在列表里看到的那一行传 version：下线传当前 ONLINE 版本，发布传要发的草稿版本。
     * {@code version} 仍允许为 null（表示「随便哪一版，取最高」），但工作台不该这么用。
     */
    public BulkStatusResult bulkChangeStatus(List<BulkStatusItem> items, Integer targetStatus) {
        List<String> succeeded = new ArrayList<>();
        List<BulkFailure> failed = new ArrayList<>();
        if (items == null) {
            return new BulkStatusResult(succeeded, failed);
        }
        // 按 activityId 去重（首个胜出）：同一活动在一个批次里出现两次是矛盾指令，
        // 「先下 v1 再下 v2」不是运营的意图，只会是前端选择模型漏了归并。
        List<BulkStatusItem> distinct = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (BulkStatusItem item : items) {
            if (item != null && item.activityId() != null && seen.add(item.activityId())) {
                distinct.add(item);
            }
        }
        for (BulkStatusItem item : distinct) {
            try {
                changeStatus(item.activityId(), item.version(), targetStatus);
                succeeded.add(item.activityId());
            } catch (RuntimeException e) {
                failed.add(new BulkFailure(item.activityId(), e.getMessage()));
            }
        }
        return new BulkStatusResult(succeeded, failed);
    }

    /**
     * @param version 要操作的**那一版**。null = 取最高未删除版本（与单条接口一致）；
     *                工作台必须传显式版本，否则会打到草稿而不是正在服务的版本。
     */
    public record BulkStatusItem(String activityId, Integer version) {}

    /** @param failed 逐条失败原因——不给这个，运营点完不知道成了几个 */
    public record BulkStatusResult(List<String> succeeded, List<BulkFailure> failed) {}
    public record BulkFailure(String activityId, String reason) {}

    /**
     * P1-8 四眼：发布(上线)必须由**非提交人**的审批人执行。
     * 审批人身份缺失 → 拒（无从校验分离，fail-closed）；审批人 == 该版本提交人 → 拒（不能自审自发）。
     */
    private void enforceFourEyes(ActivityManageEntity row) {
        String actor = ActorContext.get();
        if (actor == null || actor.isBlank()) {
            throw new IllegalStateException("四眼：发布需带审批人身份（auth 档=JWT sub / dev 档=X-Actor header），缺失拒绝");
        }
        if (actor.equals(row.getSubmittedBy())) {
            throw new IllegalStateException("四眼：提交人不能审批/发布自己提交的活动（提交人=" + row.getSubmittedBy() + "）");
        }
    }

    // ------------------------------------------------------------------ 详情 / 列表

    public List<ActivityManageEntity> list() {
        return manageRepo.findByIsDelOrderByModifiedStimeDesc(NOT_DEL);
    }

    public ActivityDetail getDetail(String activityId) {
        ActivityManageEntity manage = manageRepo
                .findFirstByActivityIdAndIsDelOrderByVersionDesc(activityId, NOT_DEL)
                .orElseThrow(() -> new IllegalArgumentException("活动不存在: " + activityId));
        Integer v = manage.getVersion();
        return new ActivityDetail(
                manage,
                ruleRepo.findByActivityIdAndVersionAndIsDel(activityId, v, NOT_DEL),
                conditionRepo.findByActivityIdAndVersionAndIsDel(activityId, v, NOT_DEL),
                bindingRepo.findByActivityIdAndVersionAndIsDel(activityId, v, NOT_DEL),
                giftRepo.findByActivityIdAndVersionAndIsDel(activityId, v, NOT_DEL),
                poolRefRepo.findByActivityIdAndVersionAndIsDel(activityId, v, NOT_DEL));
    }

    // ------------------------------------------------------------------ 校验

    /**
     * 权益形态校验（折扣类落地）。
     *
     * <p>此前 {@code redPackageAmountUnit} 是**零校验的自由文本**（默认「元」，其余原样入库），
     * 因为从来没有任何计算读过它。现在引擎按它判别形态，这个字段就从装饰品变成了「决定发多少钱」的开关，
     * 必须收进白名单——否则一个拼错的单位就能让活动按另一种形态发钱。
     *
     * <p><b>折扣类强制封顶</b>：「打 8 折」在一笔 10 万的订单上就是 2 万。不封顶等于开一个无上限的支出口子，
     * 而这类事故只有在对账时才会被发现。宁可让运营多填一个字段。
     */
    private void validateBenefitForm(ActivityCreateRequest req, boolean hasFixed, boolean hasLadder) {
        String unit = req.redPackageAmountUnit();
        if (!BenefitForm.isSupportedUnit(unit)) {
            throw new IllegalArgumentException(
                    "金额单位仅支持「元」(金额型) /「折」(折扣型) /「价」(一口价) /「件折」(第 N 件折)，收到: " + unit);
        }
        BenefitForm form = BenefitForm.of(unit);
        if (form != BenefitForm.RATIO_ZHE) {
            if (hasFixed && (req.redPackageAmount().signum() < 0 || req.redPackageAmount().compareTo(MAX_AMOUNT) > 0)) {
                throw new IllegalArgumentException("红包金额需在 [0, " + MAX_AMOUNT + "] 内");
            }
            if (req.redPackageMaxDiscount() != null) {
                throw new IllegalArgumentException("封顶减免额只对折扣型(单位=折)有意义");
            }
            // 一口价与第 N 件折此前从这里直接早退，两个「决定发多少钱」的数字一条护栏都没有——
            // 规则只写在前端表单里，任何绕过 SPA 的调用方（压测脚本 / 批量导入 / 租户直连）都能存进来。
            // 现在它们从模板走得通了（见 validateRangeColumn），护栏必须跟着落到写入口。
            if (form == BenefitForm.FIXED_PRICE) {
                // 一口价是「卖多少」不是「减多少」：0 等于白送、负数等于倒贴，都不是运营的本意
                if (!hasFixed || req.redPackageAmount().signum() <= 0) {
                    throw new IllegalArgumentException("一口价(单位=价)必须填 redPackageAmount 且大于 0");
                }
                // claim 的原子 UPDATE 明确要求 inventory 非空且余量足够；null 并不是「不限量」，
                // 而是任何 claim 都更新 0 行。必须在写入口拒绝这种看似健康、实际永远抢不到的活动。
                if (req.inventory() == null || req.inventory() < 1) {
                    throw new IllegalArgumentException("一口价(单位=价)必须配置至少 1 件库存，否则 claim 永远失败");
                }
            } else if (form == BenefitForm.NTH_ZHE) {
                // 这里的数字是折数不是钱，上面 [0, MAX_AMOUNT] 的金额护栏对它毫无意义
                if (!hasFixed) {
                    throw new IllegalArgumentException("第 N 件折(单位=件折)必须填折数（redPackageAmount）");
                }
                BigDecimal nthZhe = req.redPackageAmount();
                if (nthZhe.signum() <= 0 || nthZhe.compareTo(BigDecimal.TEN) >= 0) {
                    throw new IllegalArgumentException(
                            "第 N 件折的折数须在 (0,10) 之间，10 折=不打折、0 折=白送，均按配置错误拒绝，收到: " + nthZhe);
                }
            }
            return;
        }

        // ---- 折扣型 ----
        if (hasLadder) {
            // 阶梯的 reward 是「元」，与折扣型的语义冲突；同时配等于两种形态打架，
            // 而现有 computeAmounts 的覆盖语义会让结果取决于执行顺序——不如直接拒。
            throw new IllegalArgumentException("折扣型(单位=折)不支持阶梯分档，请二选一");
        }
        if (!hasFixed) {
            throw new IllegalArgumentException("折扣型需填折数（redPackageAmount）");
        }
        BigDecimal zhe = req.redPackageAmount();
        if (zhe.signum() <= 0 || zhe.compareTo(BigDecimal.TEN) >= 0) {
            throw new IllegalArgumentException("折数须在 (0,10) 之间，10 折=不打折、0 折=白送，均按配置错误拒绝，收到: " + zhe);
        }
        BigDecimal cap = req.redPackageMaxDiscount();
        if (cap == null || cap.signum() <= 0) {
            throw new IllegalArgumentException("折扣型必须填封顶减免额（redPackageMaxDiscount>0）——不封顶等于无上限支出");
        }
        if (cap.compareTo(MAX_AMOUNT) > 0) {
            throw new IllegalArgumentException("封顶减免额需在 (0, " + MAX_AMOUNT + "] 内");
        }
    }

    /**
     * 加价购的换购品校验。**这几条不是表单洁癖，每一条都对着决策侧的一行代码**——
     * 写平面放行了 type=6，就必须保证存进来的东西在
     * {@link com.lrj.drools.activity.service.AddOnPurchaseService} 里跑得通：
     *
     * <ul>
     *   <li><b>至少一个换购品</b>：{@code options()} 遍历 {@code c.getGifts()} 出选项，
     *       一个都没有就返回空清单——活动在控制台显示「已上线」，用户侧什么都看不到。
     *       这种「上线了但等于没上」的状态最难排查，宁可在创建时就拒。</li>
     *   <li><b>加价金额 &gt; 0</b>：{@code options()} 对 {@code absoluteAmount <= 0} 的行
     *       <b>静默 continue</b>（0 是白送、负数是倒贴，都不是加价购）。它在决策侧是 fail-closed 的正确做法，
     *       但如果写入口放行，运营配的选项会一声不响地消失。</li>
     *   <li><b>品名非空且活动内唯一</b>：{@code quote()} 的第二阶段<b>按 {@code itemName} 匹配</b>选项
     *       （刻意不发 token，价格重新查，见该类注释）。重名会让第二个选项永远选不中，
     *       且「用户选的到底是哪个」在服务端不可判定——那是在按错误的价格卖货。</li>
     * </ul>
     *
     * <p>加价金额沿用 {@code activity_gift.absolute_amount} 承载，含义是<b>加多少钱换购</b>，
     * 不是赠品价值——这是加价购复用买赠表时唯一需要记住的语义差别。
     */
    private void validateAddOnItems(ActivityCreateRequest req) {
        List<ActivityCreateRequest.GiftInput> items = req.gifts();
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("加价购活动至少需配置一个换购品，否则上线后没有任何可换购选项");
        }
        Set<String> names = new HashSet<>();
        for (ActivityCreateRequest.GiftInput g : items) {
            String name = g.giftName() == null ? "" : g.giftName().trim();
            if (name.isEmpty()) {
                throw new IllegalArgumentException("换购品名称不能为空——第二阶段报价按品名匹配选项");
            }
            if (name.length() > 128) {
                throw new IllegalArgumentException("换购品名称过长（≤128）: " + name);
            }
            if (!names.add(name)) {
                throw new IllegalArgumentException(
                        "换购品名称在同一活动内必须唯一，重复: " + name + "（第二阶段按品名匹配，重名会选不中）");
            }
            BigDecimal add = g.absoluteAmount();
            if (add == null || add.signum() <= 0) {
                throw new IllegalArgumentException(
                        "换购品「" + name + "」的加价金额必须大于 0（这个数是「加多少钱换购」，不是赠品价值）");
            }
            if (add.compareTo(MAX_AMOUNT) > 0) {
                throw new IllegalArgumentException("换购品「" + name + "」的加价金额需在 (0, " + MAX_AMOUNT + "] 内");
            }
        }
    }

    /**
     * 校验 {@code redPackageRangeAmount} 这一列。
     *
     * <p><b>它是三用途的</b>：数组=阶梯分档、{@code {"min","max"}}=随机金额区间、
     * {@code {"nth":N}}=第 N 件折。三者靠 <b>顶层 JSON 类型 + 判别位</b> 互斥，
     * 判别规则的唯一出处是 {@link RangePayload}（R9）——读写两侧共用它，
     * 不再各自实现一遍「什么形态该配什么内容」。
     *
     * <p>写侧此前却<b>无条件</b>按阶梯解析这一列：于是「第二件半价」({@code {"nth":2}}) 与
     * 「随机金额红包」({@code {"min":5,"max":20}}) 这两种合法配置在写入口 100% 被拒，
     * 报错文案还讲的是运营从没碰过的「阶梯分档」。统一写入口本身没错，错在这里少了按形态的分叉——
     * 一个读侧认识三种语义、写侧只认识一种的列，必然把另外两种判成脏数据。
     *
     * <p>各形态<b>都要真解析一遍</b>而不是只看形状：能存进来的必须是决策侧算得出金额的，
     * 否则活动会以「不适用」姿态上线（决策侧解不出载荷 → 不给优惠），
     * 而运营在控制台看到的是「已上线」。<b>报错文案按 {@link RangePayload#expectedKind} 选</b>：
     * 判据是「这份配置期望哪种载荷」，而不是「这段 JSON 碰巧长得像哪种」——
     * 单位配成「件折」却填了阶梯数组时，运营需要看到的是第 N 件的用法，不是阶梯的。
     */
    private void validateRangeColumn(ActivityCreateRequest req) {
        String json = req.redPackageRangeAmount();
        if (json == null || json.isBlank()) return;

        BenefitForm form = BenefitForm.of(req.redPackageAmountUnit());
        if (form == BenefitForm.FIXED_PRICE) {
            // 一口价的结果与原价无关，配了分档/区间也没有任何一条读路径会用它——
            // 静默留着只会在下一次「这个列到底是什么」里再坑一次，直接拒。
            throw new IllegalArgumentException("一口价(单位=价)不支持阶梯分档/区间，redPackageRangeAmount 必须为空");
        }

        RangePayload payload = RangePayload.parse(form, req.redPackageTakeType(), json);
        switch (RangePayload.expectedKind(form, req.redPackageTakeType())) {
            case NTH -> {
                if (!(payload instanceof RangePayload.Nth)) {
                    throw new IllegalArgumentException(
                            "第 N 件折需在 redPackageRangeAmount 配 {\"nth\":N} 且 N≥2（N=1 等于全场打折，那是折扣型），收到: " + json);
                }
            }
            case RANDOM -> {
                if (!(payload instanceof RangePayload.Random)) {
                    throw new IllegalArgumentException(
                            "随机金额红包需在 redPackageRangeAmount 配 {\"min\":x,\"max\":y} 且 0≤min≤max，收到: " + json);
                }
            }
            case LADDER -> {
                if (!(payload instanceof RangePayload.Ladder ladder)) {
                    throw new IllegalArgumentException("阶梯分档 JSON 无有效档位");
                }
                validateLadderRewards(ladder.tiers());
            }
        }
    }

    /**
     * 每一档的 reward 都要过和固定金额同一道 [0, MAX_AMOUNT] 护栏。
     *
     * <p>从前这道护栏只作用在 redPackageAmount 上，阶梯档的 reward 一条都没有——而阶梯正是
     * 「一次配好几个金额」的形态，最该被守住的恰恰是它。一档填 -50 会一路通到 hitAmount=-50：
     * 决策出口的闸门是 {@code hitActivityId != null || hitAmount > 0}，OR 短路让负数照样出门，
     * 下游拿到一个「负优惠」。
     */
    private void validateLadderRewards(List<ActivityDrlBuilder.LadderTier> tiers) {
        for (ActivityDrlBuilder.LadderTier t : tiers) {
            BigDecimal reward = t.reward();
            if (reward.signum() < 0 || reward.compareTo(MAX_AMOUNT) > 0) {
                throw new IllegalArgumentException(
                        "阶梯档奖励金额需在 [0, " + MAX_AMOUNT + "] 内，收到: " + reward);
            }
        }
    }

    private void validateCommon(ActivityCreateRequest req) {
        if (req.activityName() == null || req.activityName().isBlank()) {
            throw new IllegalArgumentException("活动名称不能为空");
        }
        // 长度前置校验（ISSUE-06）：否则超长名到 DB 才炸，被误判成"并发重复 requestId"。
        if (req.activityName().length() > 128) {
            throw new IllegalArgumentException("活动名称过长（≤128）");
        }
        if (req.bizLine() != null && req.bizLine().length() > 64) {
            throw new IllegalArgumentException("业务线过长（≤64）");
        }
        ActivityType type = ActivityType.fromCode(req.activityType());
        if (type != ActivityType.RED_PACKAGE && type != ActivityType.BUY_AND_GET
                && type != ActivityType.ADD_ON_PURCHASE) {
            throw new IllegalArgumentException(
                    "demo 仅支持红包(1) / 买赠(5) / 加价购(6)，收到: " + req.activityType());
        }
        if (req.activityStartTime() == null || req.activityEndTime() == null) {
            throw new IllegalArgumentException("活动开始/结束时间必填");
        }
        if (req.activityStartTime() >= req.activityEndTime()) {
            throw new IllegalArgumentException("开始时间必须早于结束时间");
        }
        if (type == ActivityType.RED_PACKAGE) {
            boolean hasFixed = req.redPackageAmount() != null;
            boolean hasLadder = req.redPackageRangeAmount() != null && !req.redPackageRangeAmount().isBlank();
            if (!hasFixed && !hasLadder) {
                throw new IllegalArgumentException("红包活动需填固定金额或阶梯分档");
            }
            validateBenefitForm(req, hasFixed, hasLadder);
        }
        if (type == ActivityType.BUY_AND_GET && (req.gifts() == null || req.gifts().isEmpty())) {
            throw new IllegalArgumentException("买赠活动至少需配置一个赠品");
        }
        if (type == ActivityType.ADD_ON_PURCHASE) {
            validateAddOnItems(req);
        }
        if (req.discountStrategy() != null && !req.discountStrategy().isBlank()) {
            StackStrategy.fromCode(req.discountStrategy()); // 非法策略抛异常
        }
    }

    // ------------------------------------------------------------------ 落库 helper

    private void saveManage(ActivityCreateRequest req, String activityId, int version, Integer status, Instant now) {
        ActivityManageEntity m = new ActivityManageEntity();
        m.setActivityId(activityId);
        m.setActivityName(req.activityName());
        m.setBizLine(req.bizLine());
        m.setActivityType(req.activityType());
        m.setActivityRule(req.activityRule());
        m.setActivityStartTime(Instant.ofEpochMilli(req.activityStartTime()));
        m.setActivityEndTime(Instant.ofEpochMilli(req.activityEndTime()));
        m.setActivityStatus(status);
        m.setActivityAreaType(req.activityAreaType());
        m.setDistrictIds(req.districtIds());
        m.setPriority(req.priority() == null ? 0 : req.priority());
        m.setInventory(req.inventory());
        // 每人限领：null / ≤0 归一成 0 = 不限。
        // 此前这里是无条件 `setUserInventory(0)`——运营填什么都没用，因为提交体里压根没有这个字段。
        // 现在它由 activity_grant 流水按 (活动, 用户) 计数执行（见 claimInventory）。
        m.setUserInventory(req.userInventory() == null || req.userInventory() <= 0 ? 0 : req.userInventory());
        m.setVersion(version);
        // 只在新建(v1)落 requestId：版本化编辑的新行不带，避免撞 (tenant_id,request_id) 唯一约束。
        // 空白 requestId 归一成 null（ISSUE-05）：表示"不启用幂等"，多次普通创建不因空白键互撞。
        m.setRequestId(version == 1 ? blankToNull(req.requestId()) : null);
        m.setSubmittedBy(ActorContext.get()); // P1-8：记录本版本提交人（发布时校验四眼）
        m.setIsDel(NOT_DEL);
        m.setCreatedStime(now);
        m.setModifiedStime(now);
        try {
            // saveAndFlush 让并发相同 requestId 的唯一约束冲突在此同步暴露(而非延到 commit)，可捕获转 409。
            manageRepo.saveAndFlush(m);
        } catch (DataIntegrityViolationException e) {
            // 只把 (tenant_id,request_id) 唯一冲突当并发重复→409；其它完整性错误透传真实原因（ISSUE-06）。
            String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
            if (msg.contains("uk_am_tenant_request")) {
                throw new IllegalStateException("并发重复请求(requestId)，请重试: " + req.requestId());
            }
            throw e;
        }
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private void saveRule(ActivityCreateRequest req, String activityId, int version, Instant now) {
        if (req.redPackageAmount() == null
                && (req.redPackageRangeAmount() == null || req.redPackageRangeAmount().isBlank())) {
            return; // 买赠等无红包规则
        }
        ActivityRuleEntity r = new ActivityRuleEntity();
        r.setActivityId(activityId);
        r.setActivityType(req.activityType());
        r.setRedPackageTakeType(req.redPackageTakeType());
        r.setRedPackageAmount(req.redPackageAmount());
        r.setRedPackageAmountUnit(req.redPackageAmountUnit() == null ? BenefitForm.UNIT_YUAN : req.redPackageAmountUnit());
        r.setRedPackageMaxDiscount(req.redPackageMaxDiscount());
        r.setRedPackageRangeAmount(req.redPackageRangeAmount());
        r.setVersion(version);
        r.setIsDel(NOT_DEL);
        r.setCreatedStime(now);
        r.setModifiedStime(now);
        ruleRepo.save(r);
    }

    private void saveCondition(ConditionNode tree, String constraint, String activityId, int version, Instant now) {
        if (tree == null) return;
        ActivityConditionEntity c = new ActivityConditionEntity();
        c.setActivityId(activityId);
        c.setVersion(version);
        c.setScene(RuleScene.ELIGIBILITY.code());
        try {
            c.setConditionTreeJson(objectMapper.writeValueAsString(tree));
        } catch (Exception e) {
            throw new IllegalArgumentException("条件树序列化失败: " + e.getMessage());
        }
        c.setGeneratedDrl(constraint);
        c.setEnabled(ENABLED);
        c.setIsDel(NOT_DEL);
        c.setCreatedStime(now);
        c.setModifiedStime(now);
        conditionRepo.save(c);
    }

    private void saveGifts(ActivityCreateRequest req, String activityId, int version, Instant now) {
        if (req.gifts() == null) return;
        for (ActivityCreateRequest.GiftInput g : req.gifts()) {
            ActivityGiftEntity e = new ActivityGiftEntity();
            e.setActivityId(activityId);
            e.setVersion(version);
            e.setBatchId(g.batchId());
            e.setGiftName(g.giftName());
            e.setGiftType(g.giftType());
            e.setGiftNum(g.giftNum() == null ? 0 : g.giftNum());
            e.setAbsoluteAmount(g.absoluteAmount() == null ? BigDecimal.ZERO : g.absoluteAmount());
            e.setRightType(g.rightType());
            e.setIsDel(NOT_DEL);
            e.setCreatedStime(now);
            e.setModifiedStime(now);
            giftRepo.save(e);
        }
    }

    private void saveManualBindings(ActivityCreateRequest req, String activityId, int version, Instant now) {
        if (req.spuBindings() == null) return;
        for (ActivityCreateRequest.SpuBinding b : req.spuBindings()) {
            if (b.spuId() == null) continue;
            ActivitySpuBindingEntity e = new ActivitySpuBindingEntity();
            e.setActivityId(activityId);
            e.setStoreId(b.storeId());
            e.setSpuId(b.spuId());
            e.setVersion(version);
            e.setBindSource(0); // 手动
            e.setEffective(1);
            e.setIsDel(NOT_DEL);
            e.setCreatedStime(now);
            e.setModifiedStime(now);
            bindingRepo.save(e);
        }
    }

    private int savePoolRefsAndAutoBind(ActivityCreateRequest req, String activityId, int version, Instant now) {
        if (req.poolRefs() == null || req.poolRefs().isEmpty()) return 0;
        for (Long poolId : req.poolRefs()) {
            if (poolId == null) continue;
            PoolRefEntity ref = new PoolRefEntity();
            ref.setActivityId(activityId);
            ref.setVersion(version);
            ref.setPoolId(poolId);
            ref.setIsDel(NOT_DEL);
            ref.setCreatedStime(now);
            ref.setModifiedStime(now);
            poolRefRepo.save(ref);
        }
        // 物化自动绑定（同事务；读得到刚存的 poolRefs）
        return poolMatchService.refreshActivityBinding(activityId, version);
    }

    private void saveStrategyIfPresent(ActivityCreateRequest req, Instant now) {
        // discountStrategy 是 bizLine 级的红包合并策略，不是活动自身属性。
        // 买赠/加价购表单即使夹带了默认 MAX，也不能把同业务线的 STACK/PRIORITY 改掉。
        if (ActivityType.fromCode(req.activityType()) != ActivityType.RED_PACKAGE) return;
        if (req.discountStrategy() == null || req.discountStrategy().isBlank()) return;
        String strategy = StackStrategy.fromCode(req.discountStrategy()).name();
        String scene = RuleScene.DISCOUNT.code();
        // 业务线级兜底策略（activityType=null）：upsert
        ActivityStrategyEntity row = strategyRepo
                .findFirstByBizLineAndActivityTypeIsNullAndSceneAndIsDel(req.bizLine(), scene, NOT_DEL)
                .orElseGet(() -> {
                    ActivityStrategyEntity s = new ActivityStrategyEntity();
                    s.setBizLine(req.bizLine());
                    s.setActivityType(null);
                    s.setScene(scene);
                    s.setVersion(0);
                    s.setIsDel(NOT_DEL);
                    s.setCreatedStime(now);
                    return s;
                });
        row.setStrategy(strategy);
        row.setVersion(row.getVersion() == null ? 1 : row.getVersion() + 1);
        row.setModifiedStime(now);
        strategyRepo.save(row);
    }

    private String generateActivityId() {
        return "ACT" + Instant.now().toEpochMilli() + String.format("%03d", seq.incrementAndGet() % 1000);
    }

    /** 当前请求租户（无上下文回落占位常量）；用于按租户解析字段 schema。 */
    private String currentTenant() {
        String t = TenantContext.get();
        return t != null ? t : RuleSchemaRegistry.DEFAULT_TENANT;
    }

    // ------------------------------------------------------------------ 预览（不落库）

    /** 预览：把资格条件树翻译成受控 Drools 约束并试编译，不写库。前端"保存前先验证"用。 */
    public PreviewResult previewEligibility(ConditionNode tree) {
        try {
            String constraint = translator.translate(tree,
                    schemaRegistry.resolve(currentTenant(), null));
            if (constraint == null) {
                return new PreviewResult(true, null, null, "空条件树：所有用户恒通过资格");
            }
            String drl = drlBuilder.buildEligibilityDrl(List.of(new EligibilityRuleDef("PREVIEW", constraint)), true);
            ruleRuntime.compileOrGet(drl);
            return new PreviewResult(true, constraint, drl, "条件合法，规则编译通过");
        } catch (Exception e) {
            return new PreviewResult(false, null, null, e.getMessage());
        }
    }

    // ------------------------------------------------------------------ 返回结构

    /**
     * 库存/限次是**声明式**的：字段存得下、决策不读取（拍板 D12-3 选 B）。
     *
     * <p>已核实 {@code inventory} / {@code userInventory} 读进 {@code ActivityCandidate} 后全仓零读取，
     * 也就是说运营配了「秒杀总量 500」线上会无限超发。本轮不做预占（量级接近整个 S 档），
     * 但**必须说清楚**——沉默才是最危险的：运营以为配了就生效。
     */
    public static List<String> declarativeOnlyWarnings(ActivityCreateRequest req) {
        List<String> warnings = new ArrayList<>();
        if (req.inventory() != null && req.inventory() > 0) {
            warnings.add("库存（" + req.inventory() + "）当前为声明式：决策链路不读取、不扣减，不构成超发防护。"
                    + "如需限量，请在下游发放/核销环节实现预占。");
        }
        return warnings;
    }

    /**
     * @param warnings 配置被接受、但**当前实现不会执行**的部分（如库存声明式不扣减）。
     *                 空列表表示没有此类落差。前端据此在详情页挂 warn Banner。
     */
    public record CreateResult(String activityId, Integer version, Integer status,
                               boolean idempotentHit, int autoBoundCount, List<String> warnings) {}

    public record PreviewResult(boolean ok, String constraint, String drl, String message) {}

    public record ActivityDetail(ActivityManageEntity manage,
                                 List<ActivityRuleEntity> rules,
                                 List<ActivityConditionEntity> conditions,
                                 List<ActivitySpuBindingEntity> bindings,
                                 List<ActivityGiftEntity> gifts,
                                 List<PoolRefEntity> poolRefs) {}
    // ================================================================ 秒杀库存扣减（一口价配套）

    /**
     * claim 结果。{@code ok=false} 时 {@code reason} 说明为什么没抢到；
     * {@code replay=true} 表示这一单本来就领过了（幂等命中，没有产生新的扣减）。
     */
    public record ClaimResult(boolean ok, String activityId, Integer version, int claimed,
                              String reason, boolean replay, Long grantId) {

        public ClaimResult(boolean ok, String activityId, Integer version, int claimed, String reason) {
            this(ok, activityId, version, claimed, reason, false, null);
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
            return new ClaimResult(false, activityId, version, 0, "缺 activityId");
        }
        if (n <= 0) return new ClaimResult(false, activityId, version, 0, "扣减数量必须为正");

        // 版本缺省 → 当前**线上**版本（不是最高版本，见方法注释第 1 条）
        Integer v = version;
        if (v == null) {
            v = currentOnlineVersion(activityId);
            if (v == null) {
                return new ClaimResult(false, activityId, null, 0, "活动不存在或当前没有上线版本");
            }
        }

        ActivityManageEntity row = manageRepo.findFirstByActivityIdAndVersionAndIsDel(activityId, v, NOT_DEL)
                .orElse(null);
        if (row == null) return new ClaimResult(false, activityId, v, 0, "活动版本不存在");

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
                return new ClaimResult(false, activityId, v, 0, "该活动限每人 " + perUser + " 份，claim 必须带 userId");
            }
            int already = grantRepo.claimedQuantityByUser(activityId, user);
            if (already + n > perUser) {
                return new ClaimResult(false, activityId, v, 0,
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
            return new ClaimResult(false, activityId, v, 0, "库存不足或活动不可用");
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
            return new ClaimResult(false, activityId, null, 0, "缺 orderId 或 activityId");
        }
        ActivityGrantEntity g = grantRepo.findFirstByOrderIdAndActivityId(order, activityId).orElse(null);
        if (g == null) return new ClaimResult(false, activityId, null, 0, "没有对应的发放记录");
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

    /** 当前线上版本号；没有上线版本时返回 null。 */
    private Integer currentOnlineVersion(String activityId) {
        return manageRepo.findByActivityIdAndActivityStatusAndIsDel(
                        activityId, ActivityStatus.ONLINE.code(), NOT_DEL).stream()
                .map(ActivityManageEntity::getVersion)
                .filter(java.util.Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(null);
    }


}
