package com.lrj.drools.activity.tenant;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import com.nimbusds.jose.util.ResourceRetriever;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.net.URI;
import java.net.URL;
import java.util.List;

/**
 * P1-12 last-good JWKS：构建**抗抖动**的 {@link NimbusJwtDecoder}。
 *
 * <p>用 Nimbus {@link JWKSourceBuilder} 组合三层韧性：
 * <ul>
 *   <li><b>cache</b>：正常按 TTL 缓存密钥集，热路径不每次拉 Casdoor；</li>
 *   <li><b>retrying</b>：瞬时失败重试一次；</li>
 *   <li><b>outageTolerant（last-good）</b>：Casdoor <em>不可达</em>时，继续用**上次成功取到的**密钥集验签，
 *       最长 {@code outageTtlMs}——轮转/Casdoor 抖动期间决策不被 JWKS 拉取阻塞、也不误 401。</li>
 * </ul>
 *
 * <p>与原 {@code NimbusJwtDecoder.withJwkSetUri(...)} 的差别：后者 JWKS 不可达即验签失败（fail-closed 到 401）；
 * 本 decoder 在 outage 窗口内 fail-static（用旧密钥），窗口过后才 fail-closed——把「JWKS 不可达如何降级」显式定义。
 *
 * <p>签名算法固定 <b>RS256</b>（Casdoor cert-built-in = RSA）。claims 校验交给 Spring 的 {@code validators}
 * （iss/exp/aud→tenant），与 {@code withJwkSetUri} 一致，故 Nimbus 内置 claims 校验设为放行。
 */
public final class OutageTolerantJwks {

    private OutageTolerantJwks() {}

    public static NimbusJwtDecoder decoder(String jwkSetUri, int fetchTimeoutMs, long cacheTtlMs, long outageTtlMs,
                                           List<OAuth2TokenValidator<Jwt>> validators) {
        try {
            URL url = URI.create(jwkSetUri).toURL();
            ResourceRetriever retriever = new DefaultResourceRetriever(fetchTimeoutMs, fetchTimeoutMs);
            JWKSourceBuilder<SecurityContext> builder =
                    JWKSourceBuilder.<SecurityContext>create(url, retriever)
                            .retrying(true)
                            // 缓存本身已把拉取节流到「每 TTL 一次」，无需 Nimbus 默认限流器(默认最小间隔 30s)与
                            // refresh-ahead(默认 30s)——二者默认值都要求 < cacheTtl，短 TTL 下会冲突；关掉，用按访问刷新即可。
                            .rateLimited(false)
                            .refreshAheadCache(false);
            if (cacheTtlMs > 0) {
                // refresh 超时取 fetch 超时 + 小裕量，避免刷新拉取拖住热路径
                builder = builder.cache(cacheTtlMs, fetchTimeoutMs + 500L);
            }
            if (outageTtlMs > 0) {
                builder = builder.outageTolerant(outageTtlMs); // last-good
            }
            JWKSource<SecurityContext> jwkSource = builder.build();

            DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
            processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, jwkSource));
            // claims 校验交给 Spring validators（下方 DelegatingOAuth2TokenValidator），此处放行避免双重/冲突校验
            processor.setJWTClaimsSetVerifier((claims, context) -> { });

            NimbusJwtDecoder decoder = new NimbusJwtDecoder(processor);
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validators));
            return decoder;
        } catch (Exception e) {
            throw new IllegalStateException("构建 outage-tolerant JWKS decoder 失败: " + jwkSetUri, e);
        }
    }
}
