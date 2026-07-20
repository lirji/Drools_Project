package com.lrj.drools.activity.service;

import com.lrj.drools.activity.domain.ActivityCreateRequest;
import com.lrj.drools.activity.domain.ActivityStatus;
import com.lrj.drools.activity.domain.ActivityType;
import com.lrj.drools.activity.domain.ConditionNode;
import com.lrj.drools.activity.domain.RuleScene;
import com.lrj.drools.activity.domain.StackStrategy;
import com.lrj.drools.activity.engine.ActivityDrlBuilder;
import com.lrj.drools.activity.engine.ActivityDrlBuilder.EligibilityRuleDef;
import com.lrj.drools.activity.engine.ActivityRuleRuntimeService;
import com.lrj.drools.activity.engine.LadderRangeParser;
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
import java.time.Instant;
import java.util.List;
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
                return new CreateResult(e.getActivityId(), e.getVersion(), e.getActivityStatus(), true, 0);
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
            // 并发保护：逻辑删除旧版本，影响行数为 0 说明被并发改过
            int affected = manageRepo.softDeleteVersion(activityId, current.getVersion(), now);
            if (affected == 0) {
                throw new IllegalStateException("活动版本冲突（并发编辑），请重试: " + activityId);
            }
            version = current.getVersion() + 1;
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
        // 阶梯 JSON 校验
        if (req.redPackageRangeAmount() != null && !req.redPackageRangeAmount().isBlank()
                && LadderRangeParser.parse(req.redPackageRangeAmount()).isEmpty()) {
            throw new IllegalArgumentException("阶梯分档 JSON 无有效档位");
        }

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

        return new CreateResult(activityId, version, status, false, autoBound);
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
        row.setActivityStatus(target.code());
        row.setModifiedStime(Instant.now());
        manageRepo.save(row);
        // M1.4/M2.2：发布(上线)bump 发布代际，供 decision 侧轮询预热（进程内直调已于 M2.2 移除，见 ArtifactService.onPublish）。
        if (target == ActivityStatus.ONLINE) {
            artifactService.onPublish(row.getActivityId(), row.getVersion());
        }
        return new CreateResult(row.getActivityId(), row.getVersion(), row.getActivityStatus(), false, 0);
    }

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
            if (hasFixed && (req.redPackageAmount().signum() < 0 || req.redPackageAmount().compareTo(MAX_AMOUNT) > 0)) {
                throw new IllegalArgumentException("红包金额需在 [0, " + MAX_AMOUNT + "] 内");
            }
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
        r.setRedPackageAmountUnit(req.redPackageAmountUnit() == null ? "元" : req.redPackageAmountUnit());
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

    public record CreateResult(String activityId, Integer version, Integer status,
                               boolean idempotentHit, int autoBoundCount) {}

    public record PreviewResult(boolean ok, String constraint, String drl, String message) {}

    public record ActivityDetail(ActivityManageEntity manage,
                                 List<ActivityRuleEntity> rules,
                                 List<ActivityConditionEntity> conditions,
                                 List<ActivitySpuBindingEntity> bindings,
                                 List<ActivityGiftEntity> gifts,
                                 List<PoolRefEntity> poolRefs) {}
}
