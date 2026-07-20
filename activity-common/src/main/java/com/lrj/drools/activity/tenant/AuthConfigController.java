package com.lrj.drools.activity.tenant;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 前端 OIDC 登录的**匿名可读**配置端点（52-frontend-oidc-login-design.md §2）。
 *
 * <p>前端 mount 时先打这里判断「auth 开没开、去哪 authorize、用哪个 clientId」：
 * {@code authEnabled=false}（默认）→ 前端保持 dev/header 租户栏一行不变；{@code true} → 渲染登录入口，
 * 走授权码+PKCE。只暴露**公开** OIDC 参数（authorize/token 端点、client_id、redirect、scope）——
 * 公有客户端本就无 secret 可泄，故可放行匿名（安全链 permitAll + {@link JwtTenantFilter} 跳过本路径）。
 */
@RestController
public class AuthConfigController {

    /** 匿名放行路径（{@link ActivityResourceServerConfig} 链一 permitAll + {@link JwtTenantFilter#shouldNotFilter} 共用）。 */
    public static final String PATH = "/activity-marketing/auth-config";

    private final TenantProperties props;

    public AuthConfigController(TenantProperties props) {
        this.props = props;
    }

    @GetMapping(PATH)
    public Map<String, Object> authConfig() {
        TenantProperties.Auth auth = props.getAuth();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("authEnabled", auth.isEnabled());
        if (!auth.isEnabled()) {
            return out; // dev/header 档：不下发端点细节，前端行为不变
        }
        out.put("issuer", auth.getIssuer());
        out.put("authorizeEndpoint", auth.getAuthorizeEndpoint());
        out.put("tokenEndpoint", auth.getTokenEndpoint());
        out.put("redirectUri", auth.getRedirectUri());
        out.put("scope", auth.getScope());
        // 每租户一个 SPA 应用：前端按用户点选的租户取对应 clientId 发起 authorize
        List<Map<String, String>> webClients = auth.getWebClientMap().entrySet().stream()
                .map(e -> Map.of("tenant", e.getKey(), "clientId", e.getValue()))
                .toList();
        out.put("webClients", webClients);
        return out;
    }
}
