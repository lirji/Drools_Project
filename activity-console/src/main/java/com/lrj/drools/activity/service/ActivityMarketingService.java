package com.lrj.drools.activity.service;

import com.lrj.drools.activity.domain.ActivityCreateRequest;
import com.lrj.drools.activity.domain.ActivityStatus;
import com.lrj.drools.activity.domain.ActivityType;
import com.lrj.drools.activity.domain.BenefitForm;
import com.lrj.drools.activity.domain.ConditionNode;
import com.lrj.drools.activity.domain.RuleLogic;
import com.lrj.drools.activity.domain.RuleOperator;
import com.lrj.drools.activity.domain.RuleScene;
import com.lrj.drools.activity.domain.StackStrategy;
import com.lrj.drools.activity.engine.ActivityDrlBuilder;
import com.lrj.drools.activity.engine.ActivityDrlBuilder.EligibilityRuleDef;
import com.lrj.drools.activity.engine.ActivityRuleRuntimeService;
import com.lrj.drools.activity.engine.RangePayload;
import com.lrj.drools.activity.engine.RuleConditionTranslator;
import com.lrj.drools.activity.engine.RuleSchemaRegistry;
import com.lrj.drools.activity.error.ActivityException;
import com.lrj.drools.activity.persistence.*;
import com.lrj.drools.activity.tenant.ActorContext;
import com.lrj.drools.activity.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import com.lrj.drools.activity.persistence.ActivityManageEntity;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

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

    private static final Logger log = LoggerFactory.getLogger(ActivityMarketingService.class);

    private static final int NOT_DEL = 0;
    private static final int DEL = 1;
    private static final int ENABLED = 1;

    /** {@code activity_manage.district_ids} 的列宽。6 位码 + 逗号 = 7 字符 → 最多 146 个。 */
    private static final int DISTRICT_IDS_MAX_LEN = 1024;

    /** 「指定地域」的 {@code activityAreaType} 取值。1=全国，2=指定地域。 */
    private static final int AREA_TYPE_DISTRICTS = 2;

    /** 投放地域被翻译成的条件字段——必须与 {@code RuleSchemaRegistry} 里那个白名单字段同名。 */
    private static final String FIELD_USER_DISTRICT = "userDistrictId";
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("999999");

    private final ActivityManageRepository manageRepo;
    private final ActivityRuleRepository ruleRepo;
    private final ActivityConditionRepository conditionRepo;
    private final ActivitySpuBindingRepository bindingRepo;
    /** 详情下钻时按 spuId 批量补商品名/价（{@code findAllById} = PK IN + @TenantId 自动，一页一查）。 */
    private final CatalogProductRepository catalogProductRepo;
    private final ActivityGiftRepository giftRepo;
    private final PoolRefRepository poolRefRepo;
    private final ActivityStrategyRepository strategyRepo;
    private final ActivityIdempotencyRepository idempotencyRepo;

    /** 「当前是哪一版」的唯一出口（两套互斥定义都在那里）。 */
    private final ActivityVersionResolver versions;
    /** 发放台账。本类只保留同名委派方法，实现在那边（见 {@link GrantService} 类注释）。 */
    private final GrantService grants;

    private final RuleConditionTranslator translator;
    private final RuleSchemaRegistry schemaRegistry;
    private final ActivityDrlBuilder drlBuilder;
    private final ActivityRuleRuntimeService ruleRuntime;
    private final ActivityPoolMatchService poolMatchService;
    private final ArtifactService artifactService;
    /** 批量操作逐条开启独立事务，避免同类自调用绕过 {@code @Transactional}。 */
    private final TransactionTemplate transactionTemplate;
    /** 投放地域展开成 {@code userDistrictId} 取值集合时用（见 {@link #mergeDistrictCondition}）。 */
    private final DistrictQueryService districtQuery;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicInteger seq = new AtomicInteger(0);

    /** P1-8 四眼开关。开启后发布（上线）要求审批人不等于提交人。 */
    @Value("${activity.marketing.four-eyes-enabled:false}")
    private boolean fourEyesEnabled;

    public ActivityMarketingService(ActivityManageRepository manageRepo,
                                    ActivityRuleRepository ruleRepo,
                                    ActivityConditionRepository conditionRepo,
                                    ActivitySpuBindingRepository bindingRepo,
                                    CatalogProductRepository catalogProductRepo,
                                    ActivityGiftRepository giftRepo,
                                    PoolRefRepository poolRefRepo,
                                    ActivityStrategyRepository strategyRepo,
                                    ActivityIdempotencyRepository idempotencyRepo,
                                    ActivityVersionResolver versions,
                                    GrantService grants,
                                    RuleConditionTranslator translator,
                                    RuleSchemaRegistry schemaRegistry,
                                    ActivityDrlBuilder drlBuilder,
                                    ActivityRuleRuntimeService ruleRuntime,
                                    ActivityPoolMatchService poolMatchService,
                                    ArtifactService artifactService,
                                    DistrictQueryService districtQuery,
                                    TransactionTemplate transactionTemplate) {
        this.districtQuery = districtQuery;
        this.manageRepo = manageRepo;
        this.ruleRepo = ruleRepo;
        this.conditionRepo = conditionRepo;
        this.bindingRepo = bindingRepo;
        this.catalogProductRepo = catalogProductRepo;
        this.giftRepo = giftRepo;
        this.poolRefRepo = poolRefRepo;
        this.strategyRepo = strategyRepo;
        this.idempotencyRepo = idempotencyRepo;
        this.versions = versions;
        this.grants = grants;
        this.translator = translator;
        this.schemaRegistry = schemaRegistry;
        this.drlBuilder = drlBuilder;
        this.ruleRuntime = ruleRuntime;
        this.poolMatchService = poolMatchService;
        this.artifactService = artifactService;
        this.transactionTemplate = transactionTemplate;
    }

    // ------------------------------------------------------------------ 创建 / 编辑

    @Transactional(rollbackFor = Exception.class)
    public CreateResult create(ActivityCreateRequest req) {
        return createInternal(req);
    }

    /**
     * create / updateByVersion 的<b>共同实现</b>。
     *
     * <p>两个公开入口都自带同款 {@code @Transactional}，所以 {@code updateByVersion} 从前那句
     * {@code return create(req)} 在行为上并无问题——但它是一次<b>绕过代理的自调用</b>：
     * 读者要先确认「两边注解一样」才能放心，而下一次有人给 {@code create} 单独加个切面
     * （重试 / 审计 / 更严的传播级别）时，编辑这条路径会静默不生效。
     * 走私有实现方法则让「自调用不经代理」这件事根本无从发生。
     */
    private CreateResult createInternal(ActivityCreateRequest req) {
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
            // 编辑基线 = **最高未删除版**（见 {@link #latestDraftVersion}）：编辑要接着草稿改，
            // 而不是回到正在服务的那一版。这里显式选一套定义，不再让「取哪一版」散在调用点里。
            ActivityManageEntity current = latestVersionRow(activityId)
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
                // 比软删弱（非原子）；同 activityId 的并发编辑由版本唯一约束作最后防线。
                // 真要收紧应给 (tenant_id, activity_id, version) 加唯一约束，由 DB 兜底。
                if (manageRepo.findFirstByActivityIdAndVersionAndIsDel(activityId, version, NOT_DEL).isPresent()) {
                    throw ActivityException.versionConflict("活动版本冲突（并发编辑），请重试: " + activityId);
                }
            } else {
                int affected = manageRepo.softDeleteVersion(activityId, current.getVersion(), now);
                if (affected == 0) {
                    throw ActivityException.versionConflict("活动版本冲突（并发编辑），请重试: " + activityId);
                }
            }
        } else {
            activityId = generateActivityId();
            version = 1;
        }

        // 投放地域 → 资格条件。**必须发生在翻译之前**：翻译、条件落库、artifact 冻结三处都吃同一棵树，
        // 合成晚一步就意味着这条地域约束没过翻译器校验、没参与编译、也没进 artifact 的字段 pin。
        ConditionNode mergedTree = mergeDistrictCondition(req);

        // 资格条件翻译 + 严格编译校验（失败抛异常 → 整体回滚，什么都不落库）
        // schema 按 (当前租户, bizLine) 解析（无覆盖时回落共享默认 schema）
        String constraint = translator.translate(mergedTree,
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
        saveCondition(mergedTree, constraint, activityId, version, now);
        saveGifts(req, activityId, version, now);
        saveManualBindings(req, activityId, version, now);
        int autoBound = savePoolRefsAndAutoBind(req, activityId, version, now);
        saveStrategyIfPresent(req, now);

        // P1-9：冻结本版本为不可变 artifact（pin schema 版本 + 引用字段 + 资格 DRL），供发布预热与硬失效判定。
        artifactService.snapshot(activityId, version, req.bizLine(), eligDrl, mergedTree);

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
            // 这张幂等表只有 requestId 一个唯一约束，故无需分辨是哪一条约束炸的。
            throw ActivityException.duplicateRequest("并发重复请求(requestId)，请重试: " + reqId, e);
        }
    }

    /** 编辑等价于带 activityId 的 create。 */
    @Transactional(rollbackFor = Exception.class)
    public CreateResult updateByVersion(ActivityCreateRequest req) {
        if (req.activityId() == null || req.activityId().isBlank()) {
            throw new IllegalArgumentException("编辑必须带 activityId");
        }
        return createInternal(req);
    }

    // ------------------------------------------------------------------ 上下线

    /** 人工可写的完整状态集；PENDING_EFFECT 现在是显式“预约上线”状态。 */
    private static final Set<ActivityStatus> WRITABLE_STATUSES = Set.of(ActivityStatus.values());

    /** 已上线版本不能原地改成预约态；如需未来切版，应编辑出新版本后预约该草稿。 */
    private static final Set<ActivityStatus> ONLINE_TARGETS =
            Set.of(ActivityStatus.NORMAL, ActivityStatus.ONLINE, ActivityStatus.OFFLINE);

    /**
     * 允许的状态迁移表 from × to。
     *
     * <p>此前 {@link #changeStatus} 只把 targetStatus 过一遍 {@code fromCode}，<b>从不看当前状态</b>——
     * 「哪些流转是合法的」在代码里没有任何一处写下来，接手人只能从各个调用点反推。这张表把它写下来。
     *
     * <p><b>本表按今天实际发生的流转成文，不趁机收紧</b>（收紧是行为变更，要单独立项）。
     * 于是三个活跃态之间是全通的，其中两条容易被误当成 bug、都请<b>原样保留</b>：
     * <ul>
     *   <li><b>OFFLINE → ONLINE</b>：控制台列表页的上下线按钮就是 {@code status===1 ? 2 : 1}
     *       （{@code ListView.vue}），已下线的活动点一下就重新上架。注意它与 {@link #create} 里
     *       「已下线活动不可编辑」<b>不对称</b>：改不了、却能原样重新上线。本表只是让这个不对称
     *       从「没人写过」变成「写下来了」，没有改变它。</li>
     *   <li><b>X → X</b> 同态自转：批量下线时勾中一个已经下线的行，今天照样成功并推进代际。
     *       禁掉它会让批量回执凭空多出一批「失败」，而运营的意图（让这些活动都停）其实已经达成。</li>
     * </ul>
     *
     * <p>{@code PENDING_EFFECT} 是显式预约态：只有运营主动把未来开始的版本置为该状态，后台才会到点发布；
     * 普通 {@code NORMAL} 草稿永远不会被定时任务擅自上线。正在服务的版本也不能原地变预约态，
     * 应编辑出新版本后预约，旧版本继续服务到切版时刻。
     */
    private static final Map<ActivityStatus, Set<ActivityStatus>> ALLOWED_TRANSITIONS = Map.of(
            ActivityStatus.NORMAL, WRITABLE_STATUSES,
            ActivityStatus.ONLINE, ONLINE_TARGETS,
            ActivityStatus.OFFLINE, WRITABLE_STATUSES,
            ActivityStatus.PENDING_EFFECT, WRITABLE_STATUSES);

    /** 迁移动作：合法性判定通过之后、状态落到 row 之前执行的副作用。 */
    @FunctionalInterface
    private interface TransitionAction {
        void apply(ActivityManageEntity row);
    }

    /**
     * 每个目标状态挂的副作用，<b>按列表顺序执行</b>（四眼要在退役旧线上版之前，否则一次被拒的发布
     * 会先把正在服务的版本退役掉）。今天只有上线有动作，此前它们是散在方法体里的两段
     * {@code if (target == ONLINE)}。预约上线同样是一次发布审批，因此在写入 PENDING_EFFECT 时完成四眼校验；
     * 真正到点执行时后台不再伪造一个“审批人”。
     */
    private final Map<ActivityStatus, List<TransitionAction>> transitionActions = Map.of(
            ActivityStatus.ONLINE, List.of(this::enforceFourEyesIfEnabled, this::retireOtherOnlineVersions),
            ActivityStatus.PENDING_EFFECT, List.of(this::validateScheduleWindow, this::enforceFourEyesIfEnabled));

    @Transactional(rollbackFor = Exception.class)
    public CreateResult changeStatus(String activityId, Integer version, Integer targetStatus) {
        // 对外仍可传 null，但**在这里就解析成具名的那一套**（最高未删除版 = 编辑基线同一套定义），
        // 不把「null 是什么意思」带进方法体后半段——那正是「批量下线打到草稿」那类事故的温床。
        // 与定时任务锁同一组行，避免“运营手动下线”和“后台到点上线”互相覆盖。
        List<ActivityManageEntity> lockedVersions = manageRepo.lockVersionsForLifecycle(activityId, NOT_DEL);
        if (lockedVersions.isEmpty()) {
            throw new IllegalArgumentException("活动不存在: " + activityId);
        }
        Integer v = version != null ? version : lockedVersions.stream()
                .map(ActivityManageEntity::getVersion)
                .max(Integer::compareTo)
                .orElse(null);
        ActivityManageEntity row = lockedVersions.stream()
                .filter(candidate -> candidate.getVersion().equals(v))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("活动版本不存在: " + activityId + " v" + v));

        ActivityStatus target = resolveTargetStatus(targetStatus);
        ActivityStatus from = currentStatusOf(row);
        if (!ALLOWED_TRANSITIONS.getOrDefault(from, Set.of()).contains(target)) {
            throw new IllegalArgumentException("状态流转非法: " + from.label() + " → " + target.label()
                    + "（" + activityId + " v" + row.getVersion() + "）");
        }
        for (TransitionAction action : transitionActions.getOrDefault(target, List.of())) {
            action.apply(row);
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
     * 所以本方法不加 {@code @Transactional}，而是通过 {@link TransactionTemplate} 为每一条显式
     * 开启独立事务；不能依赖同类内调用 {@link #changeStatus} 上的注解，因为 Spring 代理不会拦截自调用。
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
     *
     * <p><b>targetStatus 在进循环之前校验一次</b>。它是整个请求级的参数，不是逐条的结果：
     * 此前非法目标状态要等每一条各自查完库、各自失败一次才暴露，几十条就是几十次无谓往返，
     * 回执还长得像「这几十个活动各有各的问题」。现在整请求快速失败（controller 转 400），
     * 与单条 {@code /status} 接口对同一个非法入参的反应一致。
     */
    public BulkStatusResult bulkChangeStatus(List<BulkStatusItem> items, Integer targetStatus) {
        resolveTargetStatus(targetStatus);
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
                transactionTemplate.executeWithoutResult(ignored ->
                        changeStatus(item.activityId(), item.version(), targetStatus));
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
     * 解析并校验<b>目标</b>状态。
     *
     * <p>{@link ActivityStatus#PENDING_EFFECT}(3) 表示“已审批、等待 activityStartTime 自动发布”。
     * 具体时间窗校验由 {@link #validateScheduleWindow} 完成。
     */
    private static ActivityStatus resolveTargetStatus(Integer targetStatus) {
        ActivityStatus target = ActivityStatus.fromCode(targetStatus);
        if (target == null) throw new IllegalArgumentException("目标状态非法: " + targetStatus);
        if (!WRITABLE_STATUSES.contains(target)) {
            throw new IllegalArgumentException("目标状态非法: " + targetStatus);
        }
        return target;
    }

    /** 预约上线只接受未来窗口；已经到点的活动应直接上线，避免等待下一轮调度。 */
    private void validateScheduleWindow(ActivityManageEntity row) {
        Instant start = row.getActivityStartTime();
        Instant end = row.getActivityEndTime();
        Instant now = Instant.now();
        if (start == null || end == null || !start.isBefore(end)) {
            throw new IllegalArgumentException("定时上线需要合法的开始/结束时间: "
                    + row.getActivityId() + " v" + row.getVersion());
        }
        if (!start.isAfter(now)) {
            throw new IllegalArgumentException("活动开始时间已到，不能预约上线；请直接上线: "
                    + row.getActivityId() + " v" + row.getVersion());
        }
    }

    /** 解析<b>当前</b>状态（迁移表的 from 侧）。 */
    private static ActivityStatus currentStatusOf(ActivityManageEntity row) {
        ActivityStatus from = ActivityStatus.fromCode(row.getActivityStatus());
        if (from == null) {
            throw new IllegalStateException("活动当前状态为空: " + row.getActivityId() + " v" + row.getVersion());
        }
        return from;
    }

    /** 上线迁移动作①：P1-8 四眼（开关关闭时无操作）。 */
    private void enforceFourEyesIfEnabled(ActivityManageEntity row) {
        if (fourEyesEnabled) {
            enforceFourEyes(row);
        }
    }

    /**
     * 上线迁移动作②：P0-4 原子指针切换——新版本上线的同一事务里，把该活动其它仍处于 ONLINE 的版本退役。
     * 没有这一步，编辑不下线之后会出现「v1 与 v2 同时在线」，决策取谁取决于实现细节而非发布动作。
     */
    private void retireOtherOnlineVersions(ActivityManageEntity row) {
        for (ActivityManageEntity old : manageRepo.findByActivityIdAndActivityStatusAndIsDel(
                row.getActivityId(), ActivityStatus.ONLINE.code(), NOT_DEL)) {
            if (old.getVersion() != null && !old.getVersion().equals(row.getVersion())) {
                old.setActivityStatus(ActivityStatus.OFFLINE.code());
                old.setModifiedStime(Instant.now());
                manageRepo.save(old);
            }
        }
    }

    /**
     * P1-8 四眼：发布(上线)必须由**非提交人**的审批人执行。
     * 审批人身份缺失 → 拒（无从校验分离，fail-closed）；审批人 == 该版本提交人 → 拒（不能自审自发）。
     *
     * <p><b>抛 {@link ActivityException}（→ 403）而不是 {@code IllegalStateException}（→ 409）</b>：
     * 这是一次有意的状态码修正。四眼拒绝说的是「不该由你来做」，不是「状态冲突、重试可能会成」——
     * 而 409 恰恰会诱导调用方重试，重试再多次也永远不会成功，必须换一个人来点。
     * 它是 {@link RuntimeException} 的直接子类，因此会穿过 controller 里迁移期保留的
     * {@code catch (IllegalStateException)}，落到 {@code ActivityExceptionAdvice} 上。
     */
    private void enforceFourEyes(ActivityManageEntity row) {
        String actor = ActorContext.get();
        if (actor == null || actor.isBlank()) {
            throw ActivityException.fourEyesRequired(
                    "四眼：发布需带审批人身份（auth 档=JWT sub / dev 档=X-Actor header），缺失拒绝");
        }
        if (actor.equals(row.getSubmittedBy())) {
            throw ActivityException.fourEyesRequired(
                    "四眼：提交人不能审批/发布自己提交的活动（提交人=" + row.getSubmittedBy() + "）");
        }
    }

    // ------------------------------------------------------------------ 详情 / 列表

    public List<ActivityManageEntity> list() {
        return manageRepo.findByIsDelOrderByModifiedStimeDesc(NOT_DEL);
    }

    /**
     * 活动详情——返回的是 <b>{@link #latestDraftVersion 最高未删除版}</b>，也就是草稿（若有）。
     *
     * <p><b>不要改成返回线上版</b>：编辑器（{@code EditorView.vue}）拿它当编辑基线，
     * 编辑就该编草稿；改了会让编辑器加载到一份不可编辑的旧配置。
     *
     * <p>但「详情这一版可能不是正在发钱的那一版」必须说出来，而不是留给调用方自己去猜——
     * 所以另带一个 {@code servingVersion}（当前 ONLINE 版，没有上线版本时为 null）。
     * 工作台据此提示「你看的是 v2 草稿、在服务的是 v1」（{@code ListView.vue} 的 versionMismatch）。
     */
    public ActivityDetail getDetail(String activityId) {
        ActivityManageEntity manage = latestVersionRow(activityId)
                .orElseThrow(() -> new IllegalArgumentException("活动不存在: " + activityId));
        Integer v = manage.getVersion();
        // D1：bindings 收窄为「仅手动」（bindSource=0）。爆炸源是自动绑定（商品池×店铺物化），
        // 而 EditorView 编辑基线本就只 filter(bindSource===0)——收窄后它拿到的正是全部手动集，免改；
        // 自动绑定的明细改由 /binding-stores + /binding-spus 两个端点按店铺聚合+分页取。
        // 摘要 storeCount/spuTotal 覆盖全部未删除行（含自动），供详情标题与列表页计数。
        List<ActivitySpuBindingEntity> manualBindings =
                bindingRepo.findByActivityIdAndVersionAndBindSourceAndIsDel(activityId, v, MANUAL_BIND, NOT_DEL);
        List<ActivitySpuBindingRepository.StoreSpuCount> agg = bindingRepo.aggregateStoresByVersion(activityId, v);
        long spuTotal = agg.stream().mapToLong(ActivitySpuBindingRepository.StoreSpuCount::getSpuCount).sum();
        return new ActivityDetail(
                manage,
                ruleRepo.findByActivityIdAndVersionAndIsDel(activityId, v, NOT_DEL),
                conditionRepo.findByActivityIdAndVersionAndIsDel(activityId, v, NOT_DEL),
                manualBindings,
                giftRepo.findByActivityIdAndVersionAndIsDel(activityId, v, NOT_DEL),
                poolRefRepo.findByActivityIdAndVersionAndIsDel(activityId, v, NOT_DEL),
                currentOnlineVersion(activityId),
                agg.size(),
                spuTotal);
    }

    /** 手动绑定的 {@code bind_source} 取值（0）。自动=1。收窄详情 bindings 时用。 */
    private static final int MANUAL_BIND = 0;

    // ------------------------------------------------------------------ 详情回显·绑定商品（店铺聚合 + 下钻分页）

    /**
     * 店铺聚合（D4/D5）：一次返回该活动<b>草稿基线版</b>下每个店铺绑了多少商品（含失效）+ 多少生效。
     * 行数 = O(店铺数)，不随 SPU 总量增长。{@code version} 缺省 = {@link #latestDraftVersion}（D6，与 getDetail 同源）。
     */
    public List<StoreBindingView> bindingStores(String activityId, Integer version) {
        Integer v = version != null ? version : requireVersion(activityId);
        return bindingRepo.aggregateStoresByVersion(activityId, v).stream()
                .map(r -> new StoreBindingView(r.getStoreId(), r.getSpuCount(), r.getEffectiveCount()))
                .collect(Collectors.toList());
    }

    /**
     * 店铺下钻明细分页（D4）：某店铺下的绑定商品，服务端分页；商品名/价用 {@code findAllById} 一页一次批量补
     * （PK IN + @TenantId 自动，无 N+1，{@code BindingViewQueryCountTest} 钉死）。join 不到 → name/price 为 null，
     * 前端回退裸 {@code SPU {id}}。{@code storeId} 传 null 命中「未指定门店」桶（D7）。
     */
    public SpuBindingPage bindingSpus(String activityId, Integer version, Integer storeId, int page, int size) {
        Integer v = version != null ? version : requireVersion(activityId);
        Pageable pageable = PageRequest.of(Math.max(0, page), size <= 0 ? 20 : size);
        Page<ActivitySpuBindingEntity> pageResult = bindingRepo.pageStoreBindings(activityId, v, storeId, pageable);
        List<Long> spuIds = pageResult.getContent().stream()
                .map(ActivitySpuBindingEntity::getSpuId).collect(Collectors.toList());
        Map<Long, CatalogProductEntity> byId = spuIds.isEmpty() ? Map.of()
                : catalogProductRepo.findAllById(spuIds).stream()
                    .collect(Collectors.toMap(CatalogProductEntity::getSpuId, p -> p, (a, b) -> a));
        List<SpuBindingRow> items = pageResult.getContent().stream().map(b -> {
            CatalogProductEntity p = byId.get(b.getSpuId());
            return new SpuBindingRow(b.getSpuId(), p != null ? p.getSpuName() : null,
                    p != null ? p.getPrice() : null, b.getBindSource(), b.getEffective(), b.getPoolId());
        }).collect(Collectors.toList());
        return new SpuBindingPage(pageResult.getTotalElements(), pageResult.getNumber(), pageResult.getSize(), items);
    }

    /** 详情/聚合的版本解析：草稿基线（最高未删除版）；活动不存在则与 getDetail 一致地抛 IAE→400。 */
    private Integer requireVersion(String activityId) {
        return latestVersionRow(activityId)
                .orElseThrow(() -> new IllegalArgumentException("活动不存在: " + activityId))
                .getVersion();
    }

    /** 店铺聚合行。{@code storeId} 可空（null = 未指定门店桶）。 */
    public record StoreBindingView(Integer storeId, long spuCount, long effectiveCount) {}

    /** 下钻明细一行。{@code spuName}/{@code price} 可空（catalog_product 里查不到该 spu 时回退）。 */
    public record SpuBindingRow(Long spuId, String spuName, BigDecimal price,
                                Integer bindSource, Integer effective, Long poolId) {}

    /** 下钻明细一页。 */
    public record SpuBindingPage(long total, int page, int size, List<SpuBindingRow> items) {}

    // ------------------------------------------------------------------ 版本解析（委派给唯一出口）

    /**
     * <b>最高未删除版</b>——编辑基线 / {@link #changeStatus} 的缺省 / {@link #getDetail}。
     * 定义与注意事项见 {@link ActivityVersionResolver#latestDraftVersion}。
     */
    public Integer latestDraftVersion(String activityId) {
        return versions.latestDraftVersion(activityId);
    }

    /**
     * <b>最高 ONLINE 版</b>——正在服务（正在发钱）的那一版。
     * 定义与注意事项见 {@link ActivityVersionResolver#currentOnlineVersion}。
     */
    public Integer currentOnlineVersion(String activityId) {
        return versions.currentOnlineVersion(activityId);
    }

    /** {@link #latestDraftVersion} 的整行版本（编辑与详情要的是整行，不只是版本号）。 */
    private java.util.Optional<ActivityManageEntity> latestVersionRow(String activityId) {
        return versions.latestVersionRow(activityId);
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
        // 同一条 ISSUE-06 的第三处漏网：district_ids 是 varchar(1024)，6 位码 + 逗号 = 7 字符，
        // 最多装 146 个。没有这条前置校验时，超长会在 saveAndFlush 抛 DataIntegrityViolationException，
        // 而那个 catch 是为 requestId 唯一约束写的 → 落到 advice 成 **500**。
        // 「参数写错」报成 500 会让调用方无限重试一个永远不会成功的请求，且故障从监控上消失。
        if (req.districtIds() != null && req.districtIds().length() > DISTRICT_IDS_MAX_LEN) {
            throw new IllegalArgumentException("投放地域过多（编码总长 ≤" + DISTRICT_IDS_MAX_LEN
                    + " 字符，约 " + ((DISTRICT_IDS_MAX_LEN + 1) / 7) + " 个），当前 " + req.districtIds().length() + " 字符");
        }
        ActivityType type = ActivityType.fromCode(req.activityType());
        if (type != ActivityType.RED_PACKAGE && type != ActivityType.BUY_AND_GET
                && type != ActivityType.ADD_ON_PURCHASE) {
            throw new IllegalArgumentException(
                    "当前版本仅支持红包(1) / 买赠(5) / 加价购(6)，收到: " + req.activityType());
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

    // ------------------------------------------------------------------ 投放地域 → 资格条件

    /**
     * 把「投放地域」翻译成一条 {@code userDistrictId IN (...)} 资格条件，与运营自己的条件树 AND 后返回。
     *
     * <p><b>为什么要有这一步</b>：{@code activityAreaType} / {@code district_ids} 此前是一对
     * <b>假开关</b>——能编辑、能落库、能进候选和快照，但全链路<b>零读取点</b>
     * （{@code service/}、{@code engine/}、{@code snapshot/} 三个包对这两个字段名 grep 为空）。
     * 运营配了地域，活动照样全国发钱，而且详情页还把它当生效配置回显。
     * 本方法把它接到<b>唯一真正生效</b>的那条地域链路上：条件树字段 {@code userDistrictId}
     * （{@code RuleSchemaRegistry} 白名单，由 {@code DecisionEligibilityService} 从请求填入属性袋）。
     * 决策侧因此<b>一行都不用改</b>。
     *
     * <p><b>展开到「自身 + 全部后代」而不是只到叶子</b>：{@code userDistrictId} 是调用方给什么就是什么。
     * 本仓既有取值全是省级码（{@code playbooks.ts} 的地域定向模板与 e2e 都用 {@code 310000}），
     * 而真实业务系统多半送区县码。只展开到叶子的话，带 {@code 440000} 的请求在「投放广东」的活动上
     * 一律不命中——失败方式是<b>少发钱</b>，最难被发现。
     *
     * <p><b>幂等</b>：合成前先剥掉上一次合成的节点（靠 {@link ConditionNode#SOURCE_DISTRICT} 标记）。
     * 不剥的话，编辑器回读整份存储树、下次保存再合成一次，叶子逐次翻倍、树深逐次 +1，
     * 而 {@code RuleConditionTranslator.MAX_DEPTH} 是硬闸，堆几次就再也保存不了。
     *
     * <p><b>只在保存时发生</b>。绕过控制台直接改库里的 {@code district_ids} 不会自动重译——
     * 这是一次性翻译不是活绑定，别当它是。
     */
    private ConditionNode mergeDistrictCondition(ActivityCreateRequest req) {
        ConditionNode userTree = stripDistrictNodes(req.eligibilityConditionTree());

        Integer areaType = req.activityAreaType();
        if (areaType == null || areaType != AREA_TYPE_DISTRICTS) return userTree;

        List<String> picked = parseDistrictCodes(req.districtIds());
        if (picked.isEmpty()) return userTree;

        // 租户装了不含 userDistrictId 的自定义 schema 时**跳过注入**而不是炸掉：
        // 否则每一次「指定地域」的保存都会变成一个莫名其妙的 400。落差由 declarativeOnlyWarnings 说出来。
        if (!schemaRegistry.resolve(currentTenant(), req.bizLine()).containsKey(FIELD_USER_DISTRICT)) {
            log.warn("[district] 租户 schema 里没有 {} 字段，投放地域未翻译成资格条件（本次为声明式）",
                    FIELD_USER_DISTRICT);
            return userTree;
        }

        Set<String> expanded = districtQuery.expandWithDescendants(picked);
        if (expanded.isEmpty()) return userTree;

        ConditionNode leaf = new ConditionNode();
        leaf.setField(FIELD_USER_DISTRICT);
        leaf.setOp(RuleOperator.IN.code());
        leaf.setValue(new ArrayList<>(expanded));
        leaf.setSource(ConditionNode.SOURCE_DISTRICT);

        if (userTree == null) {
            // 「只投广东、不配其它条件」是本功能最典型的用法。这里必须自己起一棵根组——
            // 否则 saveCondition 首行的 `if (tree == null) return` 会让条件行压根不建出来，
            // 地域依旧零生效，而且失败得悄无声息。
            ConditionNode root = new ConditionNode();
            root.setLogic(RuleLogic.AND.code());
            root.setChildren(new ArrayList<>(List.of(leaf)));
            return root;
        }
        if (userTree.isGroup() && RuleLogic.AND.code().equalsIgnoreCase(userTree.getLogic())) {
            // 并进现有 AND 组：**树深不变**，不会吃掉运营的嵌套预算。
            List<ConditionNode> children = new ArrayList<>(
                    userTree.getChildren() == null ? List.of() : userTree.getChildren());
            children.add(leaf);
            userTree.setChildren(children);
            return userTree;
        }
        // 用户树是 OR 组或裸叶子 → 只能包一层。包装组同样标记来源，剥离时才能原样还原回去。
        ConditionNode wrapper = new ConditionNode();
        wrapper.setLogic(RuleLogic.AND.code());
        wrapper.setChildren(new ArrayList<>(List.of(userTree, leaf)));
        wrapper.setSource(ConditionNode.SOURCE_DISTRICT);
        return wrapper;
    }

    /**
     * 剥掉上一次由投放地域合成的节点，还原成运营手写的那棵树。
     *
     * <p>两种形态都要认：① 并进 AND 组的那片叶子；② 包在外面的那层 AND 组（要还原成里面的用户子树）。
     */
    static ConditionNode stripDistrictNodes(ConditionNode node) {
        if (node == null) return null;
        if (!node.isGroup()) return node.isDistrictGenerated() ? null : node;

        List<ConditionNode> children = node.getChildren() == null ? List.of() : node.getChildren();
        if (node.isDistrictGenerated()) {
            // 我们造的包装组：里面至多一个非 district 子节点，就是原来的用户树。
            for (ConditionNode child : children) {
                if (!child.isDistrictGenerated()) return stripDistrictNodes(child);
            }
            return null;
        }
        List<ConditionNode> kept = new ArrayList<>();
        for (ConditionNode child : children) {
            ConditionNode s = stripDistrictNodes(child);
            if (s != null) kept.add(s);
        }
        if (kept.isEmpty()) return null; // 组被掏空 = 这棵树本来就只有地域条件
        node.setChildren(kept);
        return node;
    }

    /** CSV → 去重去空的代码列表，保持运营的选择顺序。 */
    static List<String> parseDistrictCodes(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String s : csv.split(",")) {
            String t = s.trim();
            if (!t.isEmpty() && !out.contains(t)) out.add(t);
        }
        return out;
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
            // 判据是**具名约束**而不是异常文案：文案由方言+驱动+DB 版本拼出来，把它当控制流 key
            // 意味着换个驱动小版本就可能把「并发重复」错判成 500（详见 ConstraintViolations）。
            if (ConstraintViolations.isViolationOf(e, ActivityManageEntity.UK_TENANT_REQUEST)) {
                throw ActivityException.duplicateRequest("并发重复请求(requestId)，请重试: " + req.requestId(), e);
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

    /**
     * @param manage         <b>最高未删除版</b>那一行（草稿优先，见 {@link #getDetail}）
     * @param bindings       <b>仅手动绑定</b>（bindSource=0，D1）。自动绑定（商品池物化，可达万级）不在这里，
     *                       改由 {@code /binding-stores} + {@code /binding-spus} 按店铺聚合+分页取。
     *                       此契约收窄见 BREAKING-CHANGES。
     * @param servingVersion 当前正在服务的 ONLINE 版本号；没有上线版本时为 null。
     *                       与 {@code manage.getVersion()} 不等就意味着「你看的这一版还没在发钱」——
     *                       此前这个落差只能由调用方各自去猜，现在写在响应里（纯增量字段）
     * @param storeCount     该版本绑定覆盖的店铺数（含仅有失效行的店铺；末位纯增量字段）
     * @param spuTotal       该版本全部未删除绑定行数（含自动+失效；详情标题与列表页计数用；末位纯增量字段）
     */
    public record ActivityDetail(ActivityManageEntity manage,
                                 List<ActivityRuleEntity> rules,
                                 List<ActivityConditionEntity> conditions,
                                 List<ActivitySpuBindingEntity> bindings,
                                 List<ActivityGiftEntity> gifts,
                                 List<PoolRefEntity> poolRefs,
                                 Integer servingVersion,
                                 int storeCount,
                                 long spuTotal) {}
    // ================================================================ 秒杀库存扣减（一口价配套）
    //
    // 实现全部在 {@link GrantService}——发放台账与配置写入口零共享状态。
    // 这里只保留同名委派：既有调用方（controller / 测试）按这组签名写的，签名不变即零改动。

    /** 委派 {@link GrantService#claimInventory(String, Integer, Integer, String, String)}。 */
    public GrantService.ClaimResult claimInventory(String activityId, Integer version, Integer quantity,
                                                   String userId, String orderId) {
        return grants.claimInventory(activityId, version, quantity, userId, orderId);
    }

    /** 委派 {@link GrantService#claimInventory(String, Integer, Integer)}（旧三参签名）。 */
    public GrantService.ClaimResult claimInventory(String activityId, Integer version, Integer quantity) {
        return grants.claimInventory(activityId, version, quantity);
    }

    /** 委派 {@link GrantService#releaseGrant}。 */
    public GrantService.ClaimResult releaseGrant(String orderId, String activityId) {
        return grants.releaseGrant(orderId, activityId);
    }

    /** 委派 {@link GrantService#grantsOfOrder}。 */
    public List<ActivityGrantEntity> grantsOfOrder(String orderId) {
        return grants.grantsOfOrder(orderId);
    }

}
