package com.lrj.drools.activity;

import com.lrj.drools.activity.tenant.AudienceTenantResolver;
import com.lrj.drools.activity.tenant.AudienceTenantValidator;
import com.lrj.drools.activity.tenant.TenantIds;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-3 命脉修正：租户从 {@code aud}(client_id) 解析（{@link AudienceTenantResolver}）+ 校验器（{@link AudienceTenantValidator}）。
 * 依据实测 Casdoor client_credentials 的 {@code owner}=admin，租户信息在 aud。
 */
class AudienceTenantResolutionTest {

    private final AudienceTenantResolver patternResolver =
            new AudienceTenantResolver(Map.of(), List.of("activity-{tenant}-cid"));

    private Jwt jwt(List<String> aud) {
        Jwt.Builder b = Jwt.withTokenValue("x").header("alg", "RS256").claim("owner", "admin");
        if (aud != null) b.audience(aud);
        return b.build();
    }

    // ---- resolver ----

    @Test
    void patternExtractsTenantFromAud() {
        assertEquals("acme", patternResolver.resolve(List.of("activity-acme-cid")).orElse(null));
        assertEquals("beta", patternResolver.resolve(List.of("activity-beta-cid")).orElse(null));
    }

    @Test
    void unknownAud_empty() {
        assertTrue(patternResolver.resolve(List.of("some-random-client")).isEmpty());
        assertTrue(patternResolver.resolve(List.of()).isEmpty());
    }

    @Test
    void explicitMapTakesPriority() {
        var r = new AudienceTenantResolver(Map.of("weird-cid", "acme"), List.of("activity-{tenant}-cid"));
        assertEquals("acme", r.resolve(List.of("weird-cid")).orElse(null));
        // map 命不中 → 家族反解兜底
        assertEquals("beta", r.resolve(List.of("activity-beta-cid")).orElse(null));
    }

    @Test
    void ownerIsNotUsedForTenant() {
        // owner=admin 的 token，租户仍从 aud 解析（命脉修正的核心）
        assertEquals("acme", patternResolver.resolve(jwt(List.of("activity-acme-cid"))).orElse(null));
    }

    @Test
    void multiAudDifferentTenants_reject() {
        // 多 aud 解析到不同租户 → 身份歧义 → 拒（空）
        assertTrue(patternResolver.resolve(List.of("activity-acme-cid", "activity-beta-cid")).isEmpty());
    }

    @Test
    void multiAudSameTenant_ok() {
        // 多 aud 但同一租户（去重后 1 个）→ 通过
        assertEquals("acme", patternResolver.resolve(List.of("activity-acme-cid", "activity-acme-cid")).orElse(null));
    }

    @Test
    void reservedSentinelAud_reject() {
        // aud 反解出保留哨兵 __no_tenant__ → 剔除，不可经 aud 触达孤儿行
        assertTrue(patternResolver.resolve(List.of("activity-__no_tenant__-cid")).isEmpty());
    }

    @Test
    void mapAndPatternAcrossAudsDifferentTenants_reject() {
        var r = new AudienceTenantResolver(Map.of("legacy-cid", "acme"), List.of("activity-{tenant}-cid"));
        // 一个 aud map→acme，另一个 pattern→beta → 歧义 → 拒
        assertTrue(r.resolve(List.of("legacy-cid", "activity-beta-cid")).isEmpty());
    }

    // ---- codex-test 补：map/pattern 解析结果的语法 + 保留值校验（ISSUE-03/09）----

    @Test
    void reservedMapValue_rejected() {
        var r = new AudienceTenantResolver(
                Map.of("cid-x", TenantIds.NO_TENANT, "cid-y", TenantIds.SINGLE), List.of("activity-{tenant}-cid"));
        assertTrue(r.resolve(List.of("cid-x")).isEmpty(), "map value=__no_tenant__ 应拒");
        assertTrue(r.resolve(List.of("cid-y")).isEmpty(), "map value=__single__ 应拒");
    }

    @Test
    void invalidGrammarMapValue_rejected() {
        var r = new AudienceTenantResolver(Map.of("cid-x", "bad tenant"), List.of("activity-{tenant}-cid"));
        assertTrue(r.resolve(List.of("cid-x")).isEmpty(), "map value 含非法字符应拒");
    }

    @Test
    void singleReservedViaPattern_rejected() {
        // aud 反解出 __single__（内部占位）也剔除，防与无上下文 schema key 撞
        assertTrue(patternResolver.resolve(List.of("activity-__single__-cid")).isEmpty());
    }

    @Test
    void nullTemplateElement_noNpe() {
        var r = new AudienceTenantResolver(Map.of(), java.util.Arrays.asList(null, "activity-{tenant}-cid"));
        assertEquals("acme", r.resolve(List.of("activity-acme-cid")).orElse(null), "list 内 null 元素不应 NPE");
    }

    // ---- validator ----

    @Test
    void validatorPassesKnownTenantAud() {
        var v = new AudienceTenantValidator(patternResolver);
        OAuth2TokenValidatorResult r = v.validate(jwt(List.of("activity-acme-cid")));
        assertFalse(r.hasErrors(), "aud 解析到租户应通过");
    }

    @Test
    void validatorRejectsUnknownAud() {
        var v = new AudienceTenantValidator(patternResolver);
        assertTrue(v.validate(jwt(List.of("evil-client"))).hasErrors(), "未知/家族外 aud 应拒绝");
        assertTrue(v.validate(jwt(List.of())).hasErrors(), "无 aud 应拒绝");
    }
}
