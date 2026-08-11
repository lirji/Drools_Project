package com.lrj.drools.activity.controller;

import com.lrj.drools.activity.domain.SpuDiscountRequest;
import com.lrj.drools.activity.service.ActivityQueryService;
import com.lrj.drools.activity.metrics.DecisionMetrics;
import com.lrj.drools.activity.service.AddOnPurchaseService;
import com.lrj.drools.activity.snapshot.DecisionSnapshot;
import com.lrj.drools.activity.snapshot.DecisionSnapshotStore;
import com.lrj.drools.activity.tenant.TenantContext;
import java.util.List;
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
    private final DecisionSnapshotStore store;

    public DecisionPlaneController(ActivityQueryService query, AddOnPurchaseService addOn,
                                   DecisionMetrics metrics, DecisionSnapshotStore store) {
        this.query = query;
        this.addOn = addOn;
        this.metrics = metrics;
        this.store = store;
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
        // 发出去的钱，按活动。命中**次数**回答不了「这个活动花了多少预算」——
        // 而那才是运营与财务真正要问的问题。
        java.util.Map<String, Double> amounts = new java.util.LinkedHashMap<>();
        metrics.registry().find(DecisionMetrics.AMOUNT).summaries()
                .forEach(s -> amounts.merge(s.getId().getTag("activityId"), s.totalAmount(), Double::sum));
        return ResponseEntity.ok(java.util.Map.of(
                "hits", hits,
                "amounts", amounts,
                "tagCap", DecisionMetrics.ACTIVITY_TAG_CAP,
                "overCapTag", DecisionMetrics.OVER_CAP,
                // 与 /metrics 一致的自述：这是**本进程**的视角，多实例部署下要看 Prometheus 聚合。
                // 少了这句自述的读数最容易被当成全局真相——而它恰恰不是。
                "scope", "single-instance"));
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
        // 决策热路径 explain=false：逐候选资格 trace 不外泄（与 spu-discount 的分档约定一致）。
        // console 的 /activity-marketing/addon/* 别名是试算档，那边保持 true。
        return ResponseEntity.ok(addOn.options(req, false));
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
        var q = addOn.quote(req, activityId, item, false);
        return q.ok() ? ResponseEntity.ok(q) : ResponseEntity.status(409).body(q);
    }

    /** 商品买赠决策（= /activity-marketing/gifts 的决策平面别名）。 */
    @PostMapping("/gifts")
    public ResponseEntity<?> gifts(@RequestBody SpuDiscountRequest req) {
        return ResponseEntity.ok(query.buyAndGetGifts(req));
    }

    /**
     * <b>本租户的快照桶清单</b>——回答「我的活动到底在不在决策服务眼里」。
     *
     * <p><b>为什么必须有这个端点</b>：决策响应里的 {@code provenance}（source/generation/buckets）
     * 在最要命的那条故障上<b>三个值全绿</b>——活动的 {@code bizLine} 为空（写平面不强制必填）时
     * 它进不了任何桶（构建期按 bizLine 精确匹配），而兜底重建只遍历<b>已存在</b>的桶、
     * 永远建不出不存在的那个。此时决策照常走快照、代际是别的业务线的正常数、快照也很新，
     * 只是这个活动根本不在里面，于是页面上看到的是「未命中」，与「活动确实不该命中」完全同形。
     *
     * <p>加了 {@code ?activityId=} 之后它直接回答：<b>在哪个桶 / 不在任何桶</b>。
     * 这是控制台验证页上「我配了活动却什么都没返回」这个困惑的终点。
     *
     * <p><b>只读、无副作用、不占指标标签位</b>：它不发起决策，不会把验证流量混进
     * {@code activity.decision.{hit,amount}}，也不消耗 {@code ACTIVITY_TAG_CAP} 的 200 个标签位。
     *
     * <p>年龄读数取的是<b>本租户</b>的桶。注意它与 {@code activity.decision.snapshot.age.seconds}
     * 那个 gauge <b>不是同一个数</b>——后者是 {@code DecisionSnapshotStore.oldestAgeSeconds}，
     * 跨租户统计（调度线程与指标线程没有租户上下文）。多租户下两者永远对不上，别拿来互相印证。
     */
    @GetMapping("/snapshot")
    public ResponseEntity<?> snapshot(@RequestParam(value = "activityId", required = false) String activityId) {
        String tenant = TenantContext.get();
        java.time.Instant now = java.time.Instant.now();
        List<DecisionSnapshot> snaps = store.forTenant(tenant);

        List<java.util.Map<String, Object>> buckets = new java.util.ArrayList<>();
        for (DecisionSnapshot s : snaps) {
            java.util.Map<String, Object> b = new java.util.LinkedHashMap<>();
            b.put("bizLine", s.bizLine());
            b.put("generation", s.generation());
            b.put("builtAt", s.builtAt() == null ? null : s.builtAt().toString());
            b.put("ageSeconds", s.builtAt() == null ? null
                    : Math.round(java.time.Duration.between(s.builtAt(), now).toMillis() / 100.0) / 10.0);
            b.put("activityCount", s.activityCount());
            if (activityId != null && !activityId.isBlank()) {
                b.put("containsActivity", s.contains(activityId));
            }
            buckets.add(b);
        }

        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("tenant", tenant);
        out.put("buckets", buckets);
        // 没有任何桶 = 这个租户的决策目前**全部走库**。这本身是正常的（还没发布过任何活动，
        // 或轮询还没跑过第一轮），但它必须能被一眼看出来，而不是让人从「决策成功」倒推。
        out.put("bucketCount", buckets.size());
        if (activityId != null && !activityId.isBlank()) {
            List<String> hosting = new java.util.ArrayList<>();
            for (DecisionSnapshot s : snaps) if (s.contains(activityId)) hosting.add(s.bizLine());
            out.put("activityId", activityId);
            out.put("inSnapshot", !hosting.isEmpty());
            out.put("hostedByBizLines", hosting);
            // 这句话是给运营看的，别删：三个 provenance 值全绿而活动不命中时，
            // 十有八九就是它——而这条故障靠等、靠重启、靠兜底重建都好不了。
            out.put("hint", hosting.isEmpty()
                    ? "该活动不在本租户的任何快照桶里：要么它还没上线，要么它的 bizLine 为空/与桶键对不上"
                      + "（bizLine 为空的活动永远不会进入任何桶，兜底重建也救不了它）"
                    : "该活动在快照里，未命中应从资格条件、时间窗、绑定 SPU 三处查");
        }
        return ResponseEntity.ok(out);
    }
}
