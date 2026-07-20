package com.lrj.drools.controller;

import com.lrj.drools.domain.Cart;
import com.lrj.drools.domain.Customer;
import com.lrj.drools.domain.OrderItem;
import com.lrj.drools.service.HotReloadService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
public class HotReloadController {

    private final HotReloadService hotReloadService;

    public HotReloadController(HotReloadService hotReloadService) {
        this.hotReloadService = hotReloadService;
    }

    /** Step 9: 推送 DRL 字符串, 编译并缓存; 编译失败返回 400 + 详细错误。 */
    @PostMapping("/hot/upsert")
    public ResponseEntity<?> upsert(@RequestBody UpsertRequest req) {
        try {
            hotReloadService.upsert(req.name(), req.drl());
            return ResponseEntity.ok(new UpsertResponse(req.name(), "registered"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    /** Step 9: 用指定 name 注册过的规则跑 cart, 返回触发次数 + cart 当前状态。 */
    @PostMapping("/hot/run/{name}")
    public ResponseEntity<?> run(@PathVariable String name, @RequestBody RunRequest req) {
        try {
            Cart cart = new Cart(
                    req.cartId() != null ? req.cartId() : UUID.randomUUID().toString(),
                    req.customer(),
                    req.items()
            );
            return ResponseEntity.ok(hotReloadService.execute(name, cart));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @GetMapping("/hot/list")
    public Set<String> list() {
        return hotReloadService.registered();
    }

    public record UpsertRequest(String name, String drl) {}
    public record UpsertResponse(String name, String status) {}
    public record RunRequest(String cartId, Customer customer, List<OrderItem> items) {}
    public record ErrorResponse(String error) {}
}
