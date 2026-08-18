package com.lrj.drools.controller;

import com.lrj.drools.service.TmsService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Step 12: Truth Maintenance System 展示入口。
 *
 * 单端点 POST /tms/compare:
 *   - 请求体指定传感器名 + "超阈值的高读数" + "回落后的低读数"
 *   - 响应里同时给出两个 kbase 各两阶段的告警快照, 阅读结果时重点对比:
 *
 *       response.logical.phase2Alerts  应该为空     ← insertLogical 触发 TMS 自动撤销
 *       response.regular.phase2Alerts  应该还在     ← 普通 insert 跟前提解耦
 *
 *   这就是这一步要"看到"的核心现象。
 */
@RestController
public class TmsController {

    private final TmsService tmsService;

    public TmsController(TmsService tmsService) {
        this.tmsService = tmsService;
    }

    @PostMapping("/tms/compare")
    public TmsService.ComparisonResult compare(@RequestBody CompareRequest req) {
        String name = req.sensorName() != null ? req.sensorName() : "sensor-1";
        double hot = req.hotValue() != null ? req.hotValue() : 95.0;
        double cool = req.coolValue() != null ? req.coolValue() : 50.0;
        return tmsService.compare(name, hot, cool);
    }

    public record CompareRequest(String sensorName, Double hotValue, Double coolValue) {}
}
