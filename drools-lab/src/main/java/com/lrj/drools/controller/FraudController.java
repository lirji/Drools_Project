package com.lrj.drools.controller;

import com.lrj.drools.domain.BurstAlert;
import com.lrj.drools.domain.OrderEvent;
import com.lrj.drools.service.FraudService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class FraudController {

    private final FraudService fraudService;

    public FraudController(FraudService fraudService) {
        this.fraudService = fraudService;
    }

    /** Step 8 展示: CEP 滑窗风控。请求体里 events 不需要预排序, service 内部会按
     *  timestamp 排序后逐个 insert 并推进 pseudo clock。 */
    @PostMapping("/fraud/check")
    public CheckResponse check(@RequestBody CheckRequest req) {
        return new CheckResponse(fraudService.check(req.events()));
    }

    public record CheckRequest(List<OrderEvent> events) {}
    public record CheckResponse(List<BurstAlert> alerts) {}
}
