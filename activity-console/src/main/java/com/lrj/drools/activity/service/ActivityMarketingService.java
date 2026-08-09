package com.lrj.drools.activity.service;

import com.lrj.drools.activity.domain.ActivityCreateRequest;
import com.lrj.drools.activity.domain.ActivityStatus;
import com.lrj.drools.activity.domain.ActivityType;
import com.lrj.drools.activity.domain.BenefitForm;
import com.lrj.drools.activity.domain.ConditionNode;
import com.lrj.drools.activity.domain.DistributionMode;
import com.lrj.drools.activity.domain.RuleScene;
import com.lrj.drools.activity.domain.StackStrategy;
import com.lrj.drools.activity.engine.ActivityDrlBuilder;
import com.lrj.drools.activity.engine.ActivityDrlBuilder.EligibilityRuleDef;
import com.lrj.drools.activity.engine.ActivityRuleRuntimeService;
import com.lrj.drools.activity.engine.LadderRangeParser;
import com.lrj.drools.activity.engine.RandomRangeParser;
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
        // M1.4/M2.2：发布(上线)bump 发布代际，供 decision 侧轮询预热（进程内直调已于 M2.2 移除，见 ArtifactService.onPublish）。
        if (target == ActivityStatus.ONLINE) {
            artifactService.onPublish(row.getActivityId(), row.getVersion());
        }
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
     * 校验 {@code redPackageRangeAmount} 这一列。
     *
     * <p><b>它是三用途的</b>：数组=阶梯分档、{@code {"min","max"}}=随机金额区间、
     * {@code {"nth":N}}=第 N 件折。三者靠 <b>顶层 JSON 类型 + 判别位</b> 互斥
     * （{@link com.lrj.drools.activity.engine.RandomRangeParser} 的类注释是这条分工的出处），
     * 读侧早就是这么分的——{@code LadderRangeParser} 见到非数组直接返回空，把对象让给随机/第 N 件。
     *
     * <p>写侧此前却<b>无条件</b>按阶梯解析这一列：于是「第二件半价」({@code {"nth":2}}) 与
     * 「随机金额红包」({@code {"min":5,"max":20}}) 这两种合法配置在写入口 100% 被拒，
     * 报错文案还讲的是运营从没碰过的「阶梯分档」。统一写入口本身没错，错在这里少了按形态的分叉——
     * 一个读侧认识三种语义、写侧只认识一种的列，必然把另外两种判成脏数据。
     *
     * <p>各形态<b>都要真解析一遍</b>而不是只看形状：能存进来的必须是决策侧算得出金额的，
     * 否则活动会以「不适用」姿态上线（{@code parseNth}/{@code parse} 返回 null → 决策侧不给优惠），
     * 而运营在控制台看到的是「已上线」。
     */
    private void validateRangeColumn(ActivityCreateRequest req) {
        String json = req.redPackageRangeAmount();
        if (json == null || json.isBlank()) return;

        BenefitForm form = BenefitForm.of(req.redPackageAmountUnit());
        if (form == BenefitForm.NTH_ZHE) {
            if (RandomRangeParser.parseNth(json) == null) {
                throw new IllegalArgumentException(
                        "第 N 件折需在 redPackageRangeAmount 配 {\"nth\":N} 且 N≥2（N=1 等于全场打折，那是折扣型），收到: " + json);
            }
            return;
        }
        if (form == BenefitForm.FIXED_PRICE) {
            // 一口价的结果与原价无关，配了分档/区间也没有任何一条读路径会用它——
            // 静默留着只会在下一次「这个列到底是什么」里再坑一次，直接拒。
            throw new IllegalArgumentException("一口价(单位=价)不支持阶梯分档/区间，redPackageRangeAmount 必须为空");
        }
        boolean random = req.redPackageTakeType() != null
                && DistributionMode.RANDOM_AMOUNT.code() == req.redPackageTakeType();
        if (form == BenefitForm.AMOUNT && random) {
            if (RandomRangeParser.parse(json) == null) {
                throw new IllegalArgumentException(
                        "随机金额红包需在 redPackageRangeAmount 配 {\"min\":x,\"max\":y} 且 0≤min≤max，收到: " + json);
            }
            return;
        }
        List<ActivityDrlBuilder.LadderTier> tiers = LadderRangeParser.parse(json);
        if (tiers.isEmpty()) {
            throw new IllegalArgumentException("阶梯分档 JSON 无有效档位");
        }
        // 每一档的 reward 都要过和固定金额同一道 [0, MAX_AMOUNT] 护栏。
        //
        // 从前这道护栏只作用在 redPackageAmount 上，阶梯档的 reward 一条都没有——而阶梯正是
        // 「一次配好几个金额」的形态，最该被守住的恰恰是它。一档填 -50 会一路通到 hitAmount=-50：
        // 决策出口的闸门是 `hitActivityId != null || hitAmount > 0`，OR 短路让负数照样出门，
        // 下游拿到一个「负优惠」。
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
        if (type != ActivityType.RED_PACKAGE && type != ActivityType.BUY_AND_GET) {
            throw new IllegalArgumentException("demo 仅支持红包(1) / 买赠(5)，收到: " + req.activityType());
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
        m.setUserInventory(0);
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

    /** claim 结果。{@code ok=false} 时 {@code reason} 说明为什么没抢到。 */
    public record ClaimResult(boolean ok, String activityId, Integer version, int claimed, String reason) {}

    /**
     * 抢占库存——**秒杀防超发的权威动作**。
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
     * <p>幂等性：**本方法不幂等**。同一个用户连点两次会扣两次库存。
     * 要幂等需要「用户 × 活动」的领取流水表来去重，当前没有那张表——
     * 这一点必须让调用方知道，而不是假装它幂等。
     */
    @Transactional
    public ClaimResult claimInventory(String activityId, Integer version, Integer quantity) {
        int n = quantity == null ? 1 : quantity;
        if (activityId == null || activityId.isBlank()) return new ClaimResult(false, activityId, version, 0, "缺 activityId");
        if (n <= 0) return new ClaimResult(false, activityId, version, 0, "扣减数量必须为正");

        Integer v = version;
        if (v == null) {
            // 没给版本就打到当前最高版——与「上线打到最新草稿」的既有语义一致
            v = manageRepo.findFirstByActivityIdAndIsDelOrderByVersionDesc(activityId, 0)
                    .map(ActivityManageEntity::getVersion).orElse(null);
            if (v == null) return new ClaimResult(false, activityId, null, 0, "活动不存在");
        }

        // 判断余量与减一压在同一条 UPDATE 里，靠数据库对同一行的串行化防超发。
        // **绝不能改成先查后减**——那是 check-then-act 竞态，低并发测不出来、大促必现。
        int affected = manageRepo.decrementInventory(activityId, v, n, Instant.now());
        return affected > 0
                ? new ClaimResult(true, activityId, v, n, null)
                : new ClaimResult(false, activityId, v, 0, "库存不足或活动不可用");
    }

}
