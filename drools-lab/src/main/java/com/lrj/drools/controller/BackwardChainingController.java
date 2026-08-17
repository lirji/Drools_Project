package com.lrj.drools.controller;

import com.lrj.drools.domain.Location;
import com.lrj.drools.domain.WatchTarget;
import com.lrj.drools.service.BackwardChainingService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Step 13: 后向链 + query 入口。
 *
 * 请求体:
 *   locations: 一组直接关系 [{thing, container}, ...]
 *   queries:   一组要回答的问题 [{thing, container}, ...]
 *              每个问题问"thing 是否 (递归地) 在 container 里"
 *
 * 响应:
 *   answers: 每个 query 对应一个 {thing, container, contained: boolean}
 *   ancestorsLookup: 每个 query.thing 的所有 (递归) 上级容器列表
 *
 * 经典请求示例 (House 在 City 在 Country):
 *   locations = [
 *     {thing:"Office",  container:"House"},
 *     {thing:"House",   container:"City"},
 *     {thing:"City",    container:"Country"}
 *   ]
 *   queries = [{thing:"Office", container:"Country"}]
 *   → answers[0].contained == true  (经过 Office → House → City → Country 三跳证明)
 *   → ancestorsLookup[0].ancestors == ["House", "City", "Country"]
 */
@RestController
public class BackwardChainingController {

    private final BackwardChainingService service;

    public BackwardChainingController(BackwardChainingService service) {
        this.service = service;
    }

    @PostMapping("/backward/contains")
    public BackwardChainingService.EvaluationResult contains(@RequestBody EvaluateRequest req) {
        return service.evaluate(req.locations(), req.queries());
    }

    /**
     * Step 13 扩展: 后向链嵌进前向链 LHS。
     *
     * 请求体:
     *   locations:    一组直接关系 [{thing, container}, ...] (事实库)
     *   watchTargets: 一组驱动 fact [{thing, zone}, ...]
     *                 每条问"thing 是否 (递归地) 落在受限区域 zone 里"
     *
     * 与 /backward/contains 的区别: /contains 走 Java pull (getQueryResults) 返回 boolean;
     * /derive 走前向链 fire, 由规则 LHS 的 ?isContainedIn 反向证明后 insert 出 findings。
     *
     * 示例 (Office → House → City → Country):
     *   locations = [{thing:"Office",container:"House"}, {thing:"House",container:"City"},
     *                {thing:"City",container:"Country"}]
     *   watchTargets = [{thing:"Office", zone:"Country"}]
     *   → findings = [{thing:"Office", zone:"Country", contained:true}]
     */
    @PostMapping("/backward/derive")
    public BackwardChainingService.DeriveResult derive(@RequestBody DeriveRequest req) {
        return service.derive(req.locations(), req.watchTargets());
    }

    public record EvaluateRequest(
            List<Location> locations,
            List<BackwardChainingService.Query> queries
    ) {}

    public record DeriveRequest(
            List<Location> locations,
            List<WatchTarget> watchTargets
    ) {}
}
