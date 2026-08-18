package com.lrj.drools.controller;

import com.lrj.drools.domain.Cart;
import com.lrj.drools.domain.Customer;
import com.lrj.drools.domain.OrderItem;
import com.lrj.drools.service.GuardService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Step 14: 引擎安全护栏 (熔断 + AgendaFilter)。
 *
 * 三个端点对应三个护栏, 都拿"失控/灰度"做靶子, 展示引擎级兜底。
 */
@RestController
public class GuardController {

    private final GuardService guardService;

    public GuardController(GuardService guardService) {
        this.guardService = guardService;
    }

    /** Step 14-A: fireAllRules(maxFires) 硬上限。失控自增规则被 fire 到 maxFires 强制停。
     *  不传 maxFires 默认 100; startValue 默认 0。 */
    @PostMapping("/guard/runaway")
    public GuardService.RunawayResult runaway(@RequestBody(required = false) RunawayRequest req) {
        int startValue = req != null && req.startValue() != null ? req.startValue() : 0;
        int maxFires = req != null && req.maxFires() != null ? req.maxFires() : 100;
        return guardService.runawayCapped(startValue, maxFires);
    }

    /** Step 14-B: watchdog 超时 halt()。失控规则裸跑, 另一线程在 timeoutMillis 后打断。
     *  不传 timeoutMillis 默认 200ms; startValue 默认 0。 */
    @PostMapping("/guard/timeout")
    public GuardService.RunawayResult timeout(@RequestBody(required = false) TimeoutRequest req) {
        int startValue = req != null && req.startValue() != null ? req.startValue() : 0;
        long timeoutMillis = req != null && req.timeoutMillis() != null ? req.timeoutMillis() : 200L;
        return guardService.runawayWithTimeout(startValue, timeoutMillis);
    }

    /** Step 14-C: AgendaFilter 灰度。allowedReleases 决定放行哪些 @release 通道,
     *  不传默认只放 {"stable"} (baseline 无标记永远放行, canary 被拦)。 */
    @PostMapping("/guard/canary")
    public GuardService.CanaryResult canary(@RequestBody CanaryRequest req) {
        Cart cart = new Cart(
                req.cartId() != null ? req.cartId() : UUID.randomUUID().toString(),
                req.customer(),
                req.items()
        );
        Set<String> allowed = (req.allowedReleases() != null && !req.allowedReleases().isEmpty())
                ? new HashSet<>(req.allowedReleases())
                : Set.of("stable");
        return guardService.canary(cart, allowed);
    }

    public record RunawayRequest(Integer startValue, Integer maxFires) {}
    public record TimeoutRequest(Integer startValue, Long timeoutMillis) {}
    public record CanaryRequest(String cartId, Customer customer, List<OrderItem> items,
                                List<String> allowedReleases) {}
}
