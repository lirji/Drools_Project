package com.lrj.drools.activity.controller;

import com.lrj.drools.activity.domain.ActivityCreateRequest;
import com.lrj.drools.activity.domain.ActivityStatus;
import com.lrj.drools.activity.domain.ActivityStatusRequest;
import com.lrj.drools.activity.domain.ActivityType;
import com.lrj.drools.activity.domain.ConditionNode;
import com.lrj.drools.activity.domain.DecisionMode;
import com.lrj.drools.activity.domain.DistributionMode;
import com.lrj.drools.activity.domain.RuleLogic;
import com.lrj.drools.activity.domain.RuleOperator;
import com.lrj.drools.activity.domain.SpuDiscountRequest;
import com.lrj.drools.activity.domain.StackStrategy;
import com.lrj.drools.activity.engine.RuleSchemaRegistry;
import com.lrj.drools.activity.tenant.TenantContext;
import com.lrj.drools.activity.service.AddOnPurchaseService;
import com.lrj.drools.activity.service.ActivityMarketingService;
import com.lrj.drools.activity.service.ActivityQueryService;
import com.lrj.drools.activity.service.GenerationService;
import com.lrj.drools.activity.service.GrantService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 活动营销 demo REST 入口。收敛自来源 {@code ActivityAdminPlatformManageController} + toC 优惠查询。
 *
 *   POST /activity-marketing/create            创建/编辑活动（带 activityId 即编辑，版本+1）
 *   POST /activity-marketing/{id}/status       上下线
 *   GET  /activity-marketing/list              活动列表（当前版本）
 *   GET  /activity-marketing/{id}              活动详情（基础/规则/条件/绑定/买赠/池引用）
 *   POST /activity-marketing/spu-discount      商品红包优惠查询（资格→阶梯→折扣合并 + 回退 trace）
 *   POST /activity-marketing/gifts             商品买赠查询
 *   POST /activity-marketing/addon/options     加价购第一阶段（列出可换购选项）
 *   POST /activity-marketing/addon/quote       加价购第二阶段（按活动+换购品重新报价）
 *   POST /activity-marketing/preview           资格条件树预览（翻译+试编译，不落库）
 *   GET  /activity-marketing/field-dict        字段/运算符/枚举字典（前端报表下拉用）
 *   GET  /activity-marketing/generation        库里当前发布代际（决策侧回显的 generation 的参照物）
 *
 * 错误约定与 CampaignController 一致：参数非法 400，状态/并发冲突 409，<b>四眼拒绝 403</b>
 * （「不该由你来做」不是「冲突，重试可能会成」）。
 *
 * <p>映射本身收在 {@link ActivityExceptionAdvice}，不再各方法手抄。下面几个方法里的
 * {@code try/catch} 是<b>迁移期</b>保留物：留着它们，已有端点的状态码就一位都不会漂；
 * advice 负责兜住它们没覆盖的那些端点，以及穿过它们的
 * {@link com.lrj.drools.activity.error.ActivityException}。
 */
@RestController
@RequestMapping("/activity-marketing")
public class ActivityMarketingController {

    private final ActivityMarketingService marketing;
    private final ActivityQueryService query;
    private final AddOnPurchaseService addOn;
    private final RuleSchemaRegistry schemaRegistry;
    private final GenerationService generations;

    public ActivityMarketingController(ActivityMarketingService marketing, ActivityQueryService query,
                                       AddOnPurchaseService addOn, RuleSchemaRegistry schemaRegistry,
                                       GenerationService generations) {
        this.generations = generations;
        this.marketing = marketing;
        this.query = query;
        this.addOn = addOn;
        this.schemaRegistry = schemaRegistry;
    }

    @PostMapping("/create")
    public ResponseEntity<?> create(@RequestBody ActivityCreateRequest req) {
        try {
            return ResponseEntity.ok(marketing.create(req));
        } catch (IllegalArgumentException ex) {
            return bad(ex);
        } catch (IllegalStateException ex) {
            return conflict(ex);
        }
    }

    @PostMapping("/{activityId}/status")
    public ResponseEntity<?> status(@PathVariable("activityId") String activityId,
                                    @RequestBody ActivityStatusRequest req) {
        try {
            return ResponseEntity.ok(marketing.changeStatus(activityId, req.version(), req.targetStatus()));
        } catch (IllegalArgumentException ex) {
            return bad(ex);
        } catch (IllegalStateException ex) {
            return conflict(ex);
        }
    }

    /**
     * 批量上下线（PR-5）。返回 {@code {succeeded[], failed[{activityId,reason}]}}——
     * **部分失败是正常结果，不是错误**，故一律 200，由前端渲染回执。
     *
     * <p>入参是 {@code items:[{activityId, version}]} 而不是裸 id 列表：P0-4 之后线上版与草稿并存，
     * 不传版本就会打到草稿、线上继续发钱（见
     * {@link com.lrj.drools.activity.service.ActivityMarketingService#bulkChangeStatus}）。
     *
     * <p>唯一的例外是 {@code targetStatus} 本身非法（含已封死的「待生效」3）：那是<b>整个请求</b>的参数错误，
     * 不是某几条活动的结果，逐条回执反而会把一次参数写错报成「几十个活动各有各的毛病」。
     * 这种情况按单条 {@code /status} 接口的同款反应转 400。
     */
    @PostMapping("/bulk-status")
    public ResponseEntity<?> bulkStatus(@RequestBody BulkStatusRequest req) {
        try {
            return ResponseEntity.ok(marketing.bulkChangeStatus(req.items(), req.targetStatus()));
        } catch (IllegalArgumentException ex) {
            return bad(ex);
        }
    }

    public record BulkStatusRequest(java.util.List<ActivityMarketingService.BulkStatusItem> items,
                                    Integer targetStatus) {}

    /**
     * 抢占秒杀库存。**决策 ≠ 提交**：决策接口只报价，这里才是权威扣减。
     *
     * <p>返回 200 表示抢到；没抢到时按 {@link GrantService.FailureKind} 分流状态码（见 {@link #claimStatus}）。
     * 不用 200+ok:false，是为了让调用方的重试/降级逻辑能靠状态码分流——
     * 200 会被大多数客户端当成成功而继续走下单流程，那正是超发的来源。
     */
    @PostMapping("/{activityId}/claim")
    public ResponseEntity<?> claim(@PathVariable("activityId") String activityId,
                                   @RequestParam(value = "version", required = false) Integer version,
                                   @RequestParam(value = "quantity", required = false) Integer quantity,
                                   @RequestParam(value = "userId", required = false) String userId,
                                   @RequestParam(value = "orderId", required = false) String orderId) {
        return respond(marketing.claimInventory(activityId, version, quantity, userId, orderId));
    }

    /**
     * 释放已发放的份额并归还库存——退款 / 取消 / 超时的冲正入口。
     *
     * <p>此前这条路径完全不存在：订单取消后库存永久蒸发，用户的每人限领额度也一并作废。
     * 幂等：重复释放返回 200 且不重复加库存。
     *
     * <p>状态码与 claim 走同一张映射表：<b>缺参是 400、真的查无此单才是 404</b>。
     * 此前两者都返回 404，客服拿到 404 无法区分「这一单确实没领过」和「调用方漏传了 orderId」。
     */
    @PostMapping("/{activityId}/release")
    public ResponseEntity<?> release(@PathVariable("activityId") String activityId,
                                     @RequestParam("orderId") String orderId) {
        return respond(marketing.releaseGrant(orderId, activityId));
    }

    /**
     * claim / release 的统一出口：成功 200，失败按<b>失败种类</b>给状态码。
     *
     * <p>从前这里只有一个布尔可用，于是 claim 一律 409、release 一律 404——
     * 「少传一个参数」与「库存真的没了」对客户端是同一个信号，
     * 而这两件事的正确反应恰好相反（改请求 vs 别再重试）。
     */
    private ResponseEntity<?> respond(GrantService.ClaimResult r) {
        return r.ok() ? ResponseEntity.ok(r) : ResponseEntity.status(claimStatus(r.failureKind())).body(r);
    }

    /**
     * 失败种类 → HTTP 状态码。<b>switch 表达式且不写 default</b>：新增一种失败种类而漏了这里就是编译失败，
     * 不会静默落进某个「差不多的」状态码。
     *
     * <p>{@code null}（旧的三参 {@code ClaimResult} 构造器，未标注种类）沿用历史行为 409，
     * 这样任何没被覆盖到的老路径都不会因为本次改造改变状态码。
     */
    private static int claimStatus(GrantService.FailureKind kind) {
        if (kind == null) return 409;
        return switch (kind) {
            case BAD_REQUEST -> 400;
            case NOT_FOUND -> 404;
            case OUT_OF_STOCK, PER_USER_LIMIT -> 409;
        };
    }

    /** 按订单查发放记录——客服「这一单用了哪些优惠、各发了多少」的数据源。 */
    @GetMapping("/grants")
    public ResponseEntity<?> grants(@RequestParam("orderId") String orderId) {
        return ResponseEntity.ok(marketing.grantsOfOrder(orderId));
    }

    @GetMapping("/list")
    public ResponseEntity<?> list() {
        return ResponseEntity.ok(marketing.list());
    }

    @GetMapping("/{activityId}")
    public ResponseEntity<?> detail(@PathVariable("activityId") String activityId) {
        try {
            return ResponseEntity.ok(marketing.getDetail(activityId));
        } catch (IllegalArgumentException ex) {
            return bad(ex);
        }
    }

    @PostMapping("/spu-discount")
    public ResponseEntity<?> spuDiscount(@RequestBody SpuDiscountRequest req) {
        // 控制台试算是运营的调试入口，显式开 EXPLAIN；决策平面 /decision/v1 显式 HOT_PATH。
        // 两边都必须写出来——服务侧已经没有「省掉档位」的重载了。
        return ResponseEntity.ok(query.spuDiscount(req, DecisionMode.EXPLAIN));
    }

    @PostMapping("/gifts")
    public ResponseEntity<?> gifts(@RequestBody SpuDiscountRequest req) {
        return ResponseEntity.ok(query.buyAndGetGifts(req, DecisionMode.EXPLAIN));
    }

    /**
     * 加价购第一阶段：只列出当前订单可选的换购品，不替用户选择，也不扣减库存。
     * 与 {@code /decision/v1/addon/options} 共用同一服务与响应契约。
     */
    @PostMapping("/addon/options")
    public ResponseEntity<?> addOnOptions(@RequestBody SpuDiscountRequest req) {
        return ResponseEntity.ok(addOn.options(req, DecisionMode.EXPLAIN));
    }

    /**
     * 加价购第二阶段：只接受活动与换购品标识，价格由服务端重新查询。
     * 报价有效返回 200；活动/选项已失效或参数伪造返回 409。报价不是库存提交，绝不调用 claim。
     */
    @PostMapping("/addon/quote")
    public ResponseEntity<?> addOnQuote(@RequestBody SpuDiscountRequest req,
                                        @RequestParam("activityId") String activityId,
                                        @RequestParam("item") String item) {
        var quote = addOn.quote(req, activityId, item, DecisionMode.EXPLAIN);
        return quote.ok() ? ResponseEntity.ok(quote) : ResponseEntity.status(409).body(quote);
    }

    @PostMapping("/preview")
    public ResponseEntity<?> preview(@RequestBody ConditionNode conditionTree) {
        return ResponseEntity.ok(marketing.previewEligibility(conditionTree));
    }

    /**
     * 字段白名单 + 运算符 + 枚举，前端报表式表单据此渲染下拉，避免与后端漂移。
     * 字段按 (当前租户, bizLine) 解析——与 create 同维度（ISSUE-04）：可选 {@code ?bizLine=} 取该业务线字段，缺省=租户级/默认。
     */
    @GetMapping("/field-dict")
    public ResponseEntity<?> fieldDict(@org.springframework.web.bind.annotation.RequestParam(required = false) String bizLine) {
        List<Map<String, Object>> fields = schemaRegistry.resolveFields(TenantContext.get(), bizLine).stream()
                .map(f -> Map.<String, Object>of(
                        "key", f.key(),
                        "label", f.label(),
                        "valueType", f.valueType().name(),
                        "operators", f.allowedOps().stream().map(RuleOperator::code).collect(Collectors.toList()),
                        "enumValues", f.enumValues()))
                .collect(Collectors.toList());

        List<Map<String, Object>> operators = Arrays.stream(RuleOperator.values())
                .map(o -> Map.<String, Object>of("code", o.code(), "label", o.label(), "operand", o.operand().name()))
                .collect(Collectors.toList());

        return ResponseEntity.ok(Map.of(
                "fields", fields,
                "operators", operators,
                "logics", Arrays.stream(RuleLogic.values())
                        .map(l -> Map.of("code", l.code(), "label", l.label())).collect(Collectors.toList()),
                "activityTypes", Arrays.stream(ActivityType.values())
                        .map(t -> Map.of("code", t.code(), "label", t.label())).collect(Collectors.toList()),
                "statuses", Arrays.stream(ActivityStatus.values())
                        .map(s -> Map.of("code", s.code(), "label", s.label())).collect(Collectors.toList()),
                "distributionModes", Arrays.stream(DistributionMode.values())
                        .map(d -> Map.of("code", d.code(), "label", d.label())).collect(Collectors.toList()),
                "strategies", Arrays.stream(StackStrategy.values()).map(Enum::name).collect(Collectors.toList())));
    }

    /**
     * <b>库里当前的发布代际</b>——决策响应里那个 {@code provenance.generation} 的**参照物**。
     *
     * <p>只回显决策一侧的代际号，运营看到「generation=7」是判断不了「我刚发布的那次进去了没有」的：
     * 7 可能是最新，也可能落后三代。要判断就必须有写平面这一侧的真值来对照。
     *
     * <p>放在 console 而不是 decision，是因为它读的是 {@code activity_generation} 表——
     * 那是写平面的账本；decision 侧的同名数字是它<b>看到的</b>那一份，两者的差值才是信息。
     */
    @GetMapping("/generation")
    public ResponseEntity<?> generation(@RequestParam(value = "bizLine", required = false) String bizLine) {
        long gen = generations.current(TenantContext.get(), bizLine);
        return ResponseEntity.ok(Map.of(
                "bizLine", bizLine == null ? "" : bizLine,
                "generation", gen,
                "note", gen == 0
                        ? "这条业务线还没发布过任何活动（代际从 1 起）"
                        : "决策侧 provenance.generation 小于这个数，说明快照还没跟上"));
    }

    private ResponseEntity<?> bad(RuntimeException ex) {
        return ResponseEntity.badRequest().body(new ErrorResponse(ex.getMessage()));
    }

    private ResponseEntity<?> conflict(RuntimeException ex) {
        return ResponseEntity.status(409).body(new ErrorResponse(ex.getMessage()));
    }

    public record ErrorResponse(String error) {}
}
