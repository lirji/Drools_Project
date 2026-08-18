package com.lrj.drools.controller;

import com.lrj.drools.domain.Cart;
import com.lrj.drools.domain.Customer;
import com.lrj.drools.domain.OrderItem;
import com.lrj.drools.service.PipelineService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
public class PipelineController {

    private final PipelineService pipelineService;

    public PipelineController(PipelineService pipelineService) {
        this.pipelineService = pipelineService;
    }

    /** Step 5 展示: agenda-group 流水线 (validate → discount → risk → notify)。 */
    @PostMapping("/pipeline/run")
    public Cart run(@RequestBody RunRequest req) {
        return pipelineService.run(buildCart(req));
    }

    /** Step 6 展示: 同样的 pipeline, 但挂 AgendaEventListener + RuleRuntimeEventListener,
     *  返回的 auditTrail 包含 matchCreated / matchCancelled / agendaGroupPushed 等结构化事件。 */
    @PostMapping("/pipeline/audit")
    public PipelineService.AuditedRun runWithAudit(@RequestBody RunRequest req) {
        return pipelineService.runWithAudit(buildCart(req));
    }

    private Cart buildCart(RunRequest req) {
        return new Cart(
                req.cartId() != null ? req.cartId() : UUID.randomUUID().toString(),
                req.customer(),
                req.items()
        );
    }

    public record RunRequest(String cartId, Customer customer, List<OrderItem> items) {}
}
