package com.lrj.drools.controller;

import com.lrj.drools.service.PmmlService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Step 24: PMML（规则里嵌 ML 模型评分 + Scorecard）入口。
 *
 * 请求体:
 *   model:  模型 key（`credit-scorecard` 评分卡 / `risk-regression` 线性回归），缺省 credit-scorecard
 *   inputs: 模型输入字段 map（数字会转 double 喂给 PMML continuous 字段）
 *
 * 响应: {model, resultCode（OK / FAIL…）, variables（predictedValue / reasonCode 等 OutputField）}
 *
 * 示例（评分卡：初始 100 + 年龄档 + 收入档）：
 *   {"model":"credit-scorecard","inputs":{"age":30,"income":50000}}
 *   → {model:"CreditScorecard", resultCode:"OK", variables:{"Final Score":170.0, score:170.0}}
 *
 * 示例（回归：100 - 1*age - 0.001*income）：
 *   {"model":"risk-regression","inputs":{"age":30,"income":50000}}
 *   → {model:"RiskScore", resultCode:"OK", variables:{risk:20.0}}
 */
@RestController
public class PmmlController {

    private final PmmlService pmmlService;

    public PmmlController(PmmlService pmmlService) {
        this.pmmlService = pmmlService;
    }

    @PostMapping("/pmml/score")
    public ResponseEntity<?> score(@RequestBody ScoreRequest req) {
        String model = req.model() != null ? req.model() : "credit-scorecard";
        try {
            return ResponseEntity.ok(pmmlService.score(model, req.inputs()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    /** 列出已编译的 PMML 模型 key。 */
    @GetMapping("/pmml/models")
    public Object models() {
        return Map.of("models", pmmlService.models());
    }

    public record ScoreRequest(String model, Map<String, Object> inputs) {}
    public record ErrorResponse(String error) {}
}
