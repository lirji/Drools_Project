package com.lrj.drools.activity.tenant;

import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从 JWT 的 {@code aud}（= Casdoor 应用 client_id）解析租户（P0-3 命脉修正）。
 *
 * <p><b>为什么不用 {@code owner}</b>：实测 Casdoor 的 client_credentials token 里 {@code owner}=应用的
 * <em>owner 字段</em>（=admin），<b>不是</b>组织；租户信息落在 {@code aud}=client_id（如 {@code activity-acme-cid}）
 * 和 {@code sub}={@code admin/activity-acme}。而 {@code aud} 由 Casdoor 签发时绑定到<strong>已认证的 client</strong>，
 * 每个应用独立 secret → 拿不到别租户的 secret 就换不出别租户的 aud（脚本实测"acme secret 换不出 beta token"）→
 * <b>aud 不可伪造</b>，用它作租户身份比 owner 更实在、更像显式白名单。
 *
 * <p>两级解析（map 优先，pattern 兜底）：
 * <ul>
 *   <li><b>{@code clientTenantMap}</b>：{@code client_id → tenant} 显式映射（生产推荐，等价白名单）；</li>
 *   <li><b>{@code audienceTemplates}</b>：{@code activity-{tenant}-cid} 模板反解出 {@code {tenant}}（约定即配置，对齐每租户 M2M 应用命名）。</li>
 * </ul>
 * 都解不出 → {@link Optional#empty()}（调用方 fail-closed：validator 拒 401 / filter 不落租户）。
 */
public class AudienceTenantResolver {

    private static final String PLACEHOLDER = "{tenant}";

    private final Map<String, String> clientTenantMap;
    private final List<Pattern> templatePatterns;

    public AudienceTenantResolver(Map<String, String> clientTenantMap, List<String> audienceTemplates) {
        this.clientTenantMap = clientTenantMap == null ? Map.of() : clientTenantMap;
        this.templatePatterns = (audienceTemplates == null ? List.<String>of() : audienceTemplates).stream()
                .filter(t -> t != null && !t.isBlank()) // ISSUE-09：list 内 null/blank 元素不进 compile，避免 NPE
                .map(AudienceTenantResolver::compile)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /**
     * 从一组 aud 解析出租户。**恰好解析出一个不同租户才可信**：
     * 0 个 → 空（未知/家族外）；≥2 个不同租户（多 aud 歧义）→ 空（拒，防身份歧义）。
     * 解析出的保留哨兵（{@link TenantIdentifierResolver#NO_TENANT}）被剔除，防止外部经 aud 触达孤儿行。
     */
    public Optional<String> resolve(Collection<String> auds) {
        if (auds == null) {
            return Optional.empty();
        }
        Set<String> tenants = new LinkedHashSet<>();
        for (String aud : auds) {
            if (aud == null || aud.isBlank()) {
                continue;
            }
            String t = null;
            String mapped = clientTenantMap.get(aud);
            if (mapped != null && !mapped.isBlank()) {
                t = mapped;
            } else {
                for (Pattern p : templatePatterns) {
                    Matcher m = p.matcher(aud);
                    if (m.matches()) {
                        t = m.group(1);
                        break;
                    }
                }
            }
            // 统一校验 map/pattern 解析出的租户：语法 + 非保留（ISSUE-03：map value 也过 grammar，剔除 __no_tenant__/__single__）
            if (t == null || !TenantIds.isValidExternal(t)) {
                continue;
            }
            tenants.add(t);
        }
        return tenants.size() == 1 ? Optional.of(tenants.iterator().next()) : Optional.empty();
    }

    /** 从 JWT 的 aud 解析租户。 */
    public Optional<String> resolve(Jwt jwt) {
        return resolve(jwt.getAudience());
    }

    /** 把 {@code activity-{tenant}-cid} 编译成 {@code ^activity-([A-Za-z0-9_-]+)-cid$}；无占位符则返回 null（不可反解）。 */
    private static Pattern compile(String template) {
        int i = template.indexOf(PLACEHOLDER);
        if (i < 0) {
            return null;
        }
        String pre = template.substring(0, i);
        String post = template.substring(i + PLACEHOLDER.length());
        return Pattern.compile("^" + Pattern.quote(pre) + "([A-Za-z0-9_-]+)" + Pattern.quote(post) + "$");
    }
}
