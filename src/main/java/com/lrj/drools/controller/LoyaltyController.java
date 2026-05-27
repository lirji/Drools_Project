package com.lrj.drools.controller;

import com.lrj.drools.domain.LoyaltyState;
import com.lrj.drools.service.LoyaltyService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LoyaltyController {

    private final LoyaltyService loyaltyService;

    public LoyaltyController(LoyaltyService loyaltyService) {
        this.loyaltyService = loyaltyService;
    }

    /** Step 10: 新建会话, 同 id 重复调用会覆盖原有快照 (积分清零)。 */
    @PostMapping("/loyalty/start")
    public LoyaltyState start(@RequestBody StartRequest req) {
        return loyaltyService.start(req.sessionId());
    }

    /** Step 10: 给指定会话喂一笔购买, 返回最新 LoyaltyState (含升级链结果)。 */
    @PostMapping("/loyalty/{id}/purchase")
    public ResponseEntity<?> purchase(@PathVariable("id") String sessionId,
                                      @RequestBody PurchaseRequest req) {
        try {
            return ResponseEntity.ok(loyaltyService.purchase(sessionId, req.amount()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    /** Step 10: 只读查询当前会话状态 (不 fire, 不变更快照)。 */
    @GetMapping("/loyalty/{id}")
    public ResponseEntity<?> get(@PathVariable("id") String sessionId) {
        return loyaltyService.peek(sessionId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(404)
                        .body(new ErrorResponse("未知 sessionId: " + sessionId)));
    }

    public record StartRequest(String sessionId) {}
    public record PurchaseRequest(double amount) {}
    public record ErrorResponse(String error) {}
}
