package com.lrj.drools.activity.controller;

import com.lrj.drools.activity.domain.SpuDiscountRequest;
import com.lrj.drools.activity.service.ActivityQueryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

    public DecisionPlaneController(ActivityQueryService query) {
        this.query = query;
    }

    /** 商品红包优惠决策（= /activity-marketing/spu-discount 的决策平面别名）。 */
    @PostMapping("/spu-discount")
    public ResponseEntity<?> spuDiscount(@RequestBody SpuDiscountRequest req) {
        return ResponseEntity.ok(query.spuDiscount(req));
    }

    /** 商品买赠决策（= /activity-marketing/gifts 的决策平面别名）。 */
    @PostMapping("/gifts")
    public ResponseEntity<?> gifts(@RequestBody SpuDiscountRequest req) {
        return ResponseEntity.ok(query.buyAndGetGifts(req));
    }
}
