package com.lrj.drools.activity.controller;

import com.lrj.drools.activity.domain.SpuDiscountRequest;
import com.lrj.drools.activity.service.ActivityQueryService;
import com.lrj.drools.activity.metrics.DecisionMetrics;
import com.lrj.drools.activity.service.AddOnPurchaseService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * M1.1 决策平面 API（{@code /decision/v1/**}）—— 前后端分离 + 微服务化的读平面入口（决策 D1）。
 *
 * <p><b>为什么单独一组路径</b>：营销活动的<em>决策热路径</em>（资格→阶梯→折扣合并，无写）与<em>控制台写面</em>
 * （create/status/幂等/四眼）负载画像与数据权限完全不同。这组 {@code /decision/v1/*} 是「决策服务」将来物理拆出去时
 * 的稳定契约；现在它与控制台同进程，只是<b>薄别名</b>，复用 {@link ActivityQueryService}（与 {@code /activity-marketing/spu-discount}
 * 走同一份代码，行为一致）。旧路径保留、不弃用，前端与旧脚本不受影响。
 *
 * <p>拆分后网关把 {@code /api/decision/*} 路由到只读决策服务；决策服务只需带 {@code ActivityQueryService}+engine+tenant 验签，
 * 甩掉 kie-ci/dmn/decisiontables 与全部写面依赖。当前通过 {@code activity.role} 配置在同一 artifact 内做角色门控
 * （见 {@code RoleGate}）：{@code decision} 角色只暴露本组端点，{@code console} 角色只暴露写面，{@code all}（默认）全开。
 */
@RestController
@RequestMapping("/decision/v1")
public class DecisionPlaneController {

    private final ActivityQueryService query;
    private final AddOnPurchaseService addOn;
    private final DecisionMetrics metrics;

    public DecisionPlaneController(ActivityQueryService query, AddOnPurchaseService addOn,
                                   DecisionMetrics metrics) {
        this.query = query;
        this.addOn = addOn;
        this.metrics = metrics;
    }

    /**
     * 决策指标聚合。控制台工作台上那块「决策指标尚未接入」的说明卡，等的就是这个端点。
     *
     * <p><b>为什么不让前端直接查 Prometheus</b>：那要求浏览器能连到 :9090，
     * 而 Prometheus 在编排里不对外暴露、生产更不会；且 PromQL 拼在前端等于把
     * 监控查询语言变成前端契约的一部分，改一次告警规则就可能打碎页面。
     *
     * <p>数据来自本进程的 MeterRegistry，即**单实例视角**。多实例部署时它只反映
     * 被路由到的那个实例——这一点必须让调用方知道，跨实例汇总仍应看 Prometheus。
     */
    @GetMapping("/metrics")
    public ResponseEntity<?> metrics() {
        var reg = metrics.registry();
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("scope", "single-instance");
        out.put("note", "本进程视角；跨实例汇总看 Prometheus :9090");

        java.util.Map<String, Object> duration = new java.util.LinkedHashMap<>();
        reg.find(DecisionMetrics.DURATION).timers().forEach(t -> duration.put(
                t.getId().getTag("scene") + "/" + t.getId().getTag("mode"),
                java.util.Map.of("count", t.count(),
                        "meanMs", Math.round(t.mean(java.util.concurrent.TimeUnit.MILLISECONDS) * 100) / 100.0,
                        "maxMs", Math.round(t.max(java.util.concurrent.TimeUnit.MILLISECONDS) * 100) / 100.0)));
        out.put("duration", duration);

        java.util.Map<String, Double> fallback = new java.util.LinkedHashMap<>();
        reg.find(DecisionMetrics.FALLBACK).counters().forEach(c -> fallback.put(
                c.getId().getTag("scene") + "/" + c.getId().getTag("reason"), c.count()));
        out.put("fallback", fallback);
        return ResponseEntity.ok(out);
    }

    /**
     * 按活动的命中量。
     *
     * <p><b>基数是有上限的</b>（{@link DecisionMetrics#ACTIVITY_TAG_CAP}）：超出部分并进
     * {@code __over_cap__}。总量仍准确，只是分不出是哪几个活动——这是为了不让
     * 运营随手建活动就把 Prometheus 的序列数顶爆。响应里原样带出该哨兵，不隐藏。
     */
    @GetMapping("/by-activity")
    public ResponseEntity<?> byActivity() {
        java.util.Map<String, Double> hits = new java.util.LinkedHashMap<>();
        metrics.registry().find(DecisionMetrics.HIT).counters()
                .forEach(c -> hits.merge(c.getId().getTag("activityId"), c.count(), Double::sum));
        return ResponseEntity.ok(java.util.Map.of(
                "hits", hits,
                "tagCap", DecisionMetrics.ACTIVITY_TAG_CAP,
                "overCapTag", DecisionMetrics.OVER_CAP));
    }

    /** 商品红包优惠决策（= /activity-marketing/spu-discount 的决策平面别名）。 */
    @PostMapping("/spu-discount")
    public ResponseEntity<?> spuDiscount(@RequestBody SpuDiscountRequest req) {
        return ResponseEntity.ok(query.spuDiscount(req));
    }

    /**
     * 加价购**第一阶段**：这一单能换购什么、各加多少钱。
     *
     * <p>它只列选项、不替用户挑。返回空列表是正常结果（这一单没得换），不是错误。
     */
    @PostMapping("/addon/options")
    public ResponseEntity<?> addOnOptions(@RequestBody SpuDiscountRequest req) {
        return ResponseEntity.ok(addOn.options(req));
    }

    /**
     * 加价购**第二阶段**：用户选定后的权威报价。
     *
     * <p>只接受「哪个活动的哪个换购品」，**价格重新查、绝不读客户端传来的价**——
     * 这是防改价的根本手段。选项在两阶段之间失效（活动下线/配置改了）时返回 409，
     * 而不是沿用第一阶段的价格：那等于按已作废的配置卖货。
     */
    @PostMapping("/addon/quote")
    public ResponseEntity<?> addOnQuote(@RequestBody SpuDiscountRequest req,
                                        @RequestParam("activityId") String activityId,
                                        @RequestParam("item") String item) {
        var q = addOn.quote(req, activityId, item);
        return q.ok() ? ResponseEntity.ok(q) : ResponseEntity.status(409).body(q);
    }

    /** 商品买赠决策（= /activity-marketing/gifts 的决策平面别名）。 */
    @PostMapping("/gifts")
    public ResponseEntity<?> gifts(@RequestBody SpuDiscountRequest req) {
        return ResponseEntity.ok(query.buyAndGetGifts(req));
    }
}
