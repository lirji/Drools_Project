package com.lrj.drools.activity.controller;

import com.lrj.drools.activity.domain.ActivityCreateRequest;
import com.lrj.drools.activity.domain.ActivityStatus;
import com.lrj.drools.activity.domain.ActivityStatusRequest;
import com.lrj.drools.activity.domain.ActivityType;
import com.lrj.drools.activity.domain.ConditionNode;
import com.lrj.drools.activity.domain.DistributionMode;
import com.lrj.drools.activity.domain.RuleLogic;
import com.lrj.drools.activity.domain.RuleOperator;
import com.lrj.drools.activity.domain.SpuDiscountRequest;
import com.lrj.drools.activity.domain.StackStrategy;
import com.lrj.drools.activity.engine.RuleSchemaRegistry;
import com.lrj.drools.activity.tenant.TenantContext;
import com.lrj.drools.activity.service.ActivityMarketingService;
import com.lrj.drools.activity.service.ActivityQueryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
 *   POST /activity-marketing/preview           资格条件树预览（翻译+试编译，不落库）
 *   GET  /activity-marketing/field-dict        字段/运算符/枚举字典（前端报表下拉用）
 *
 * 错误约定与 CampaignController 一致：参数非法 400，状态/并发冲突 409。
 */
@RestController
@RequestMapping("/activity-marketing")
public class ActivityMarketingController {

    private final ActivityMarketingService marketing;
    private final ActivityQueryService query;
    private final RuleSchemaRegistry schemaRegistry;

    public ActivityMarketingController(ActivityMarketingService marketing, ActivityQueryService query,
                                       RuleSchemaRegistry schemaRegistry) {
        this.marketing = marketing;
        this.query = query;
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
     */
    @PostMapping("/bulk-status")
    public ResponseEntity<?> bulkStatus(@RequestBody BulkStatusRequest req) {
        return ResponseEntity.ok(marketing.bulkChangeStatus(req.items(), req.targetStatus()));
    }

    public record BulkStatusRequest(java.util.List<ActivityMarketingService.BulkStatusItem> items,
                                    Integer targetStatus) {}

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
        // 控制台试算是运营的调试入口，显式开 explain；决策平面 /decision/v1 走默认 false
        return ResponseEntity.ok(query.spuDiscount(req, true));
    }

    @PostMapping("/gifts")
    public ResponseEntity<?> gifts(@RequestBody SpuDiscountRequest req) {
        return ResponseEntity.ok(query.buyAndGetGifts(req, true));
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

    private ResponseEntity<?> bad(RuntimeException ex) {
        return ResponseEntity.badRequest().body(new ErrorResponse(ex.getMessage()));
    }

    private ResponseEntity<?> conflict(RuntimeException ex) {
        return ResponseEntity.status(409).body(new ErrorResponse(ex.getMessage()));
    }

    public record ErrorResponse(String error) {}
}
