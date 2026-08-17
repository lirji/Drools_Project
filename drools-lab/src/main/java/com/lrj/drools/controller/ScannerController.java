package com.lrj.drools.controller;

import com.lrj.drools.domain.Cart;
import com.lrj.drools.domain.Customer;
import com.lrj.drools.domain.OrderItem;
import com.lrj.drools.service.ScannerService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Step 16: KieScanner + KJAR。
 *
 * 跟 Step 9 (/hot/*) 对照看: Step 9 是"DRL 字符串 → Map 缓存"的轻量热加载;
 * 这里是"DRL → KJAR → Maven 仓库 → KieScanner 轮询替换"的标准发版路径。
 */
@RestController
public class ScannerController {

    private final ScannerService scannerService;

    public ScannerController(ScannerService scannerService) {
        this.scannerService = scannerService;
    }

    /** Step 16: 把 DRL 打成 KJAR 装进本地 ~/.m2, 并热替换运行中的 KieBase。编译错误 400。 */
    @PostMapping("/scanner/deploy")
    public ResponseEntity<?> deploy(@RequestBody DeployRequest req) {
        try {
            return ResponseEntity.ok(scannerService.deploy(req.drl()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    /** Step 16: 用当前 live 的 KieBase 跑 cart, 返回 fire count + cart + 当前内容代次。 */
    @PostMapping("/scanner/run")
    public ResponseEntity<?> run(@RequestBody RunRequest req) {
        try {
            Cart cart = new Cart(
                    req.cartId() != null ? req.cartId() : UUID.randomUUID().toString(),
                    req.customer(),
                    req.items()
            );
            return ResponseEntity.ok(scannerService.run(cart));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    /** Step 16: 开启 KieScanner 自动轮询 (生产形态, 不传 intervalMillis 默认 5000ms)。 */
    @PostMapping("/scanner/poll/start")
    public ResponseEntity<?> startPolling(@RequestBody(required = false) PollRequest req) {
        long interval = req != null && req.intervalMillis() != null ? req.intervalMillis() : 5000L;
        try {
            return ResponseEntity.ok(new MessageResponse(scannerService.startPolling(interval)));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @PostMapping("/scanner/poll/stop")
    public MessageResponse stopPolling() {
        return new MessageResponse(scannerService.stopPolling());
    }

    @GetMapping("/scanner/status")
    public ScannerService.Status status() {
        return scannerService.status();
    }

    /** Step 16 扩展: 显式版本切换 (KieContainer.updateToVersion), install 到新固定 release 再手动切。编译错误 400。 */
    @PostMapping("/scanner/update-version")
    public ResponseEntity<?> updateVersion(@RequestBody DeployRequest req) {
        try {
            return ResponseEntity.ok(scannerService.updateToVersion(req.drl()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    /** Step 16 扩展: KieScannerEventListener 攒到的热替换事件 (只有走 scanner 的 scanNow/轮询会触发)。 */
    @GetMapping("/scanner/events")
    public List<ScannerService.ScanEvent> events() {
        return scannerService.scanEvents();
    }

    public record DeployRequest(String drl) {}
    public record RunRequest(String cartId, Customer customer, List<OrderItem> items) {}
    public record PollRequest(Long intervalMillis) {}
    public record MessageResponse(String message) {}
    public record ErrorResponse(String error) {}
}
