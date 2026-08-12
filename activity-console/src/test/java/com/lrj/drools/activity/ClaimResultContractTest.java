package com.lrj.drools.activity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.drools.activity.service.GrantService.ClaimResult;
import com.lrj.drools.activity.service.GrantService.FailureKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * {@code ClaimResult} 的响应体契约：<b>加了 {@code failureKind} 之后 JSON 一个字段都不能多</b>。
 *
 * <p>R13 特意选了「record 加一个 {@code @JsonIgnore} 分量」而不是换 sealed 变体层次：
 * 后者的字段集因变体而异，Jackson 输出必然变形。种类只用于服务端把状态码分流成 400/404/409，
 * 客户端要的信息已经在状态码里了。这条一旦被无意间去掉（比如有人删掉 {@code @JsonIgnore}），
 * 就是一次没人注意到的契约变更，所以在这里钉死。
 */
class ClaimResultContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("failureKind 不出现在响应 JSON 里")
    void failureKindIsServerSideOnly() throws Exception {
        String json = MAPPER.writeValueAsString(new ClaimResult(
                false, "ACT1", 1, 0, "库存不足或活动不可用", false, null, FailureKind.OUT_OF_STOCK));
        assertFalse(json.contains("failureKind"), "响应体不该暴露失败种类，实得: " + json);
        assertFalse(json.contains("OUT_OF_STOCK"), "响应体不该暴露失败种类，实得: " + json);
        assertEquals(
                "{\"ok\":false,\"activityId\":\"ACT1\",\"version\":1,\"claimed\":0,"
                        + "\"reason\":\"库存不足或活动不可用\",\"replay\":false,\"grantId\":null}",
                json);
    }

    @Test
    @DisplayName("旧构造器仍可用，且不带失败种类（调用方按 409 兜底）")
    void legacyConstructorsKeepNullKind() {
        assertEquals(null, new ClaimResult(false, "ACT1", 1, 0, "缺 activityId").failureKind());
        assertEquals(null, new ClaimResult(true, "ACT1", 1, 1, null, false, 9L).failureKind());
    }
}
