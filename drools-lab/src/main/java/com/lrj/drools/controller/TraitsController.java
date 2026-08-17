package com.lrj.drools.controller;

import com.lrj.drools.domain.Applicant;
import com.lrj.drools.domain.TraitFinding;
import com.lrj.drools.service.TraitsService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Step 21: traits（动态贴接口做多态）入口。
 *
 * 请求体 applicants: [{name, age, annualIncome}, ...]
 * 响应 findings: 被 don 上 PremiumApplicant 且信用额度达标的申请人 [{name, tier, creditLimit, perk}]
 *
 * 示例（一个高收入 + 一个低收入 → 只有高收入的被贴 trait 并命中）：
 *   applicants = [
 *     {name:"王五", age:35, annualIncome:300000},
 *     {name:"赵六", age:28, annualIncome:80000}
 *   ]
 *   → findings = [{name:"王五", tier:"GOLD", creditLimit:500000, perk:"专属客户经理"}]
 */
@RestController
public class TraitsController {

    private final TraitsService traitsService;

    public TraitsController(TraitsService traitsService) {
        this.traitsService = traitsService;
    }

    @PostMapping("/traits/evaluate")
    public TraitsResponse evaluate(@RequestBody TraitsRequest req) {
        return new TraitsResponse(traitsService.evaluate(req.applicants()));
    }

    public record TraitsRequest(List<Applicant> applicants) {}
    public record TraitsResponse(List<TraitFinding> findings) {}
}
