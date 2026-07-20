package com.lrj.drools.activity.tenant;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * 自写 audience 校验器（P0-3 SEC-3，命脉修正版）——**常开 + aud 必须解析到已知租户 + 家族外拒**。
 *
 * <p>不抄参考 {@code SecurityConfig} 的「可选+精确单值+默认空即不校验」。本平台租户身份 = {@code aud}(client_id)，
 * 故校验 = token 的 {@code aud} 必须能被 {@link AudienceTenantResolver} 解析成某个租户（在 client→tenant 映射里、
 * 或匹配 {@code activity-{tenant}-cid} 家族）；解析不出 = 未知/家族外 client → 拒（401）。
 *
 * <p>安全依据：{@code aud} 由 Casdoor 绑定到已认证 client，每应用独立 secret → aud 不可伪造（脚本实测跨租户 secret 互斥），
 * 故"aud 属于某租户"这一判定可信。owner=admin 对决策平面无意义，此处不参与。
 */
public class AudienceTenantValidator implements OAuth2TokenValidator<Jwt> {

    private final AudienceTenantResolver resolver;

    public AudienceTenantValidator(AudienceTenantResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        return resolver.resolve(jwt).isPresent()
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token",
                        "aud 不属任何已知租户（未知/家族外 client）：" + jwt.getAudience(), null));
    }
}
