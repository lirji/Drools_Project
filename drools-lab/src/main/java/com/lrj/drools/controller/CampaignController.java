package com.lrj.drools.controller;

import com.lrj.drools.domain.UserProfile;
import com.lrj.drools.persistence.CampaignEntity;
import com.lrj.drools.service.CampaignService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Step 18: 营销活动资格判定 REST 接口。
 *
 *   POST /campaign/create        运营创建活动 + 绑定资格规则 (DRL), 编译失败 400
 *   POST /campaign/{id}/check    用户申请参加, 判定够不够格
 *   POST /campaign/{id}/end      结束活动 (之后 check 被拒)
 *   GET  /campaign/list          列出所有活动 (含是否已编译进内存缓存)
 */
@RestController
public class CampaignController {

    private final CampaignService campaignService;

    public CampaignController(CampaignService campaignService) {
        this.campaignService = campaignService;
    }

    @PostMapping("/campaign/create")
    public ResponseEntity<?> create(@RequestBody CreateRequest req) {
        try {
            CampaignEntity e = campaignService.create(req.campaignId(), req.name(), req.eligibilityDrl());
            return ResponseEntity.ok(new CreateResponse(e.getCampaignId(), e.getName(), e.getStatus()));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new ErrorResponse(ex.getMessage()));
        }
    }

    @PostMapping("/campaign/{id}/check")
    public ResponseEntity<?> check(@PathVariable("id") String campaignId, @RequestBody UserProfile user) {
        try {
            return ResponseEntity.ok(campaignService.check(campaignId, user));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new ErrorResponse(ex.getMessage()));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(409).body(new ErrorResponse(ex.getMessage()));
        }
    }

    @PostMapping("/campaign/{id}/end")
    public ResponseEntity<?> end(@PathVariable("id") String campaignId) {
        try {
            campaignService.end(campaignId);
            return ResponseEntity.ok(new CreateResponse(campaignId, null, "ENDED"));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new ErrorResponse(ex.getMessage()));
        }
    }

    @GetMapping("/campaign/list")
    public List<CampaignService.CampaignSummary> list() {
        return campaignService.list();
    }

    public record CreateRequest(String campaignId, String name, String eligibilityDrl) {}
    public record CreateResponse(String campaignId, String name, String status) {}
    public record ErrorResponse(String error) {}
}
