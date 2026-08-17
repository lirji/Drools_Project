package com.lrj.drools.controller;

import com.lrj.drools.domain.Task;
import com.lrj.drools.service.FireUntilHaltService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Step 22: fireUntilHalt（引擎作为常驻守护线程持续消费事实）入口。
 *
 * 请求体 tasks: [{id, amount}, ...]（不用带哨兵，service 会自动补一个 __STOP__ 收尾）
 * 响应: {processed:[{id, amount}], processedCount}
 *
 * 示例：
 *   tasks = [{id:"t1",amount:10},{id:"t2",amount:20},{id:"t3",amount:30}]
 *   → processedCount = 3
 */
@RestController
public class FireUntilHaltController {

    private final FireUntilHaltService fireUntilHaltService;

    public FireUntilHaltController(FireUntilHaltService fireUntilHaltService) {
        this.fireUntilHaltService = fireUntilHaltService;
    }

    @PostMapping("/fireuntilhalt/process")
    public FireUntilHaltService.Result process(@RequestBody ProcessRequest req) throws InterruptedException {
        return fireUntilHaltService.process(req.tasks());
    }

    public record ProcessRequest(List<Task> tasks) {}
}
