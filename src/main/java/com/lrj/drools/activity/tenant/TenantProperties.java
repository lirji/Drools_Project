package com.lrj.drools.activity.tenant;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多租户开关（前缀 {@code activity.tenant}）。
 *
 * <p><b>dev-only 默认租户</b>：本地开发/手点前端时请求常常不带 {@code X-Tenant-Id}，
 * 若一律 fail-closed 会很难用。{@link #devDefaultEnabled} 开启后，无租户上下文时回落到
 * {@link #devDefault}，让 demo 单租户开箱即用。
 *
 * <p><b>默认 fail-closed</b>：{@link #devDefaultEnabled} 默认 {@code false}——
 * 即生产（不显式打开）严格拒绝无租户请求。仅 {@code application.yml}（dev-run）与测试显式置 true。
 * 这样"忘了配"的方向是安全的（拒绝），不是危险的（放行）。
 *
 * <p><b>{@link Auth}（P0-3 来源换 Casdoor）</b>：{@code auth.enabled=false}（默认）时 tenant 来自
 * {@code X-Tenant-Id} header（P0-4 行为不变）；{@code true} 时接 Casdoor，tenant 从**验证过的 JWT
 * {@code aud} 解析**（命脉实测 owner=admin 非组织），header 仅作信封校验（≠解析出的租户→403），绝不作来源。
 */
@ConfigurationProperties(prefix = "activity.tenant")
public class TenantProperties {

    /** 无租户上下文时是否回落到 {@link #devDefault}。默认 false=fail-closed；dev/测试显式开。 */
    private boolean devDefaultEnabled = false;

    /** dev 兜底租户 id（仅 {@link #devDefaultEnabled}=true 时生效）。 */
    private String devDefault = "__dev__";

    private final Auth auth = new Auth();

    private final Quota quota = new Quota();

    public boolean isDevDefaultEnabled() {
        return devDefaultEnabled;
    }

    public void setDevDefaultEnabled(boolean devDefaultEnabled) {
        this.devDefaultEnabled = devDefaultEnabled;
    }

    public String getDevDefault() {
        return devDefault;
    }

    public void setDevDefault(String devDefault) {
        this.devDefault = devDefault;
    }

    public Auth getAuth() {
        return auth;
    }

    public Quota getQuota() {
        return quota;
    }

    /**
     * P1-13 每租户限流（{@code activity.tenant.quota.*}）。
     *
     * <p><b>demo 切片</b>：**进程内** per-tenant token bucket——只在本实例范围限流。默认 {@code enabled=false}（不改 demo 行为）。
     * <p><b>生产</b>：无状态多实例下须换 **Redis token bucket**（如 Bucket4j+Redis / Redisson）或网关层限流，否则 N 实例总配额 = N×单实例。
     * 且必须**计入延迟预算**（每请求多一次 Redis 往返）并**显式定义 Redis 宕机时的开/闭**（fail-open=放行保可用 / fail-closed=拒绝保配额）。
     * 本 demo 进程内实现天然 fail-open（无外部依赖），生产选型见 Track B 收尾 doc。
     */
    public static class Quota {

        /** 是否启用每租户限流。默认 false（不改 demo）；dev/压测显式开。 */
        private boolean enabled = false;

        /** 每租户稳态 QPS（令牌补充速率）。 */
        private double perTenantQps = 50;

        /** 突发容量（桶容量）。默认等于 QPS。 */
        private double burst = 50;

        /** 桶缓存的最大租户数（防租户维度无界增长）；超出 LRU 淘汰空闲租户桶。 */
        private long maxTenants = 10_000;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public double getPerTenantQps() { return perTenantQps; }
        public void setPerTenantQps(double perTenantQps) { this.perTenantQps = perTenantQps; }

        public double getBurst() { return burst; }
        public void setBurst(double burst) { this.burst = burst; }

        public long getMaxTenants() { return maxTenants; }
        public void setMaxTenants(long maxTenants) { this.maxTenants = maxTenants; }
    }

    /**
     * P0-3 Casdoor 接入配置（{@code activity.tenant.auth.*}）。
     * 默认 {@link #enabled}=false → 保持 P0-4 的 header 来源；开启后走 OIDC resource-server 验签 + 从 aud 解析租户。
     */
    public static class Auth {

        /** 是否启用 Casdoor JWT 鉴权 + 从 aud 取租户。默认 false（dev/测试保持 header 来源）。 */
        private boolean enabled = false;

        /** P1-12：启动时预取 JWKS（首个决策请求不等拉取 + 早发现配置错）。测试置 false 保持无网络依赖。 */
        private boolean warmupEnabled = true;

        /** P1-12：JWKS 拉取的连接/读取超时（毫秒），Casdoor 挂时决策线程不被拖住。 */
        private int jwksFetchTimeoutMs = 2000;

        /** P1-12：JWKS 正常缓存 TTL（毫秒），到期后台刷新；默认 5 分钟。 */
        private long jwksCacheTtlMs = 300_000;

        /** P1-12 last-good：Casdoor 不可达时，用上次成功取到的 JWKS 继续验签的最长时长（毫秒）；默认 1 小时。0=关闭 last-good。 */
        private long jwksOutageTtlMs = 3_600_000;

        /**
         * 控制台写端点（create / status）所需权限（P1-k 决策/控制台分权）。空=仅需 authenticated（默认，不破坏 demo）。
         * 设为某 scope/角色（如 {@code SCOPE_activity.write} 或 {@code activity-admin}）后，纯决策 M2M token（无此权限）
         * 便无法调运营写接口——前提是 M2M 应用按最小权限发 scope。决策读端点（spu-discount/gifts）不受此限。
         */
        private String consoleWriteAuthority = "";

        /** Casdoor issuer（校验 iss），如 {@code http://localhost:8000}。 */
        private String issuer = "http://localhost:8000";

        /** JWKS 端点（本地缓存公钥离线验签），如 {@code http://localhost:8000/.well-known/jwks}。 */
        private String jwkSetUri = "http://localhost:8000/.well-known/jwks";

        /**
         * client_id → tenant 显式映射（生产推荐，等价白名单）。命脉实测 Casdoor client_credentials 的
         * {@code owner}=admin 非组织，故租户从 {@code aud}(client_id) 解析；此 map 优先于 {@link #audienceTemplates} 兜底。
         * 例：{@code activity-acme-cid: acme}。
         */
        private Map<String, String> clientTenantMap = new LinkedHashMap<>();

        /**
         * 租户反解模板（{@link AudienceTenantResolver} 兜底）。{@code {tenant}} 占位符，从 token 的 {@code aud} 反解出租户，
         * 对齐每租户 M2M 应用命名 {@code activity-<tenant>-cid}。map 命不中时用它。shared-app 家族可配 {@code <base>-org-{tenant}}。
         */
        private List<String> audienceTemplates = List.of("activity-{tenant}-cid");

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public boolean isWarmupEnabled() { return warmupEnabled; }
        public void setWarmupEnabled(boolean warmupEnabled) { this.warmupEnabled = warmupEnabled; }

        public int getJwksFetchTimeoutMs() { return jwksFetchTimeoutMs; }
        public void setJwksFetchTimeoutMs(int jwksFetchTimeoutMs) { this.jwksFetchTimeoutMs = jwksFetchTimeoutMs; }

        public long getJwksCacheTtlMs() { return jwksCacheTtlMs; }
        public void setJwksCacheTtlMs(long jwksCacheTtlMs) { this.jwksCacheTtlMs = jwksCacheTtlMs; }

        public long getJwksOutageTtlMs() { return jwksOutageTtlMs; }
        public void setJwksOutageTtlMs(long jwksOutageTtlMs) { this.jwksOutageTtlMs = jwksOutageTtlMs; }

        public String getConsoleWriteAuthority() { return consoleWriteAuthority; }
        public void setConsoleWriteAuthority(String consoleWriteAuthority) { this.consoleWriteAuthority = consoleWriteAuthority; }

        public String getIssuer() { return issuer; }
        public void setIssuer(String issuer) { this.issuer = issuer; }

        public String getJwkSetUri() { return jwkSetUri; }
        public void setJwkSetUri(String jwkSetUri) { this.jwkSetUri = jwkSetUri; }

        public Map<String, String> getClientTenantMap() { return clientTenantMap; }
        public void setClientTenantMap(Map<String, String> clientTenantMap) { this.clientTenantMap = clientTenantMap; }

        public List<String> getAudienceTemplates() { return audienceTemplates; }
        public void setAudienceTemplates(List<String> audienceTemplates) { this.audienceTemplates = audienceTemplates; }
    }
}
