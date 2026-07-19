package com.lrj.drools.activity;

import com.lrj.drools.activity.tenant.OutageTolerantJwks;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * P1-12 last-good JWKS 真证明：本地 HttpServer 冒充 Casdoor JWKS。
 * 先验签成功（JWKS 可达）→ 让 JWKS 端点开始 503（Casdoor「挂」）→ 缓存过期后再验签**仍成功**（last-good 兜底）。
 * 对照：不带 outage 容忍的 {@code NimbusJwtDecoder.withJwkSetUri} 在同样「挂」下会抛（fail-closed）。
 */
class LastGoodJwksTest {

    private static String mint(RSAKey rsa) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer("http://issuer")
                .audience("activity-acme-cid")
                .subject("admin/activity-acme")
                .issueTime(new Date())
                .expirationTime(new Date(System.currentTimeMillis() + 600_000))
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rsa.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(rsa));
        return jwt.serialize();
    }

    private HttpServer serveJwks(String jwksJson, AtomicBoolean up) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/jwks", ex -> {
            if (!up.get()) { // 模拟 Casdoor 不可达
                ex.sendResponseHeaders(503, -1);
                ex.close();
                return;
            }
            byte[] body = jwksJson.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        return server;
    }

    @Test
    void lastGood_survivesJwksOutage() throws Exception {
        RSAKey rsa = new RSAKeyGenerator(2048).keyID("lg-key").generate();
        String jwksJson = new JWKSet(rsa.toPublicJWK()).toString();
        AtomicBoolean up = new AtomicBoolean(true);
        HttpServer server = serveJwks(jwksJson, up);
        try {
            String uri = "http://127.0.0.1:" + server.getAddress().getPort() + "/jwks";
            // 短 cache TTL（200ms）逼它过期后重取；充裕 outage 窗口（60s）
            NimbusJwtDecoder decoder = OutageTolerantJwks.decoder(
                    uri, 1000, 200, 60_000, List.of(new JwtTimestampValidator()));

            // 1) JWKS 可达 → 验签成功，密钥集被缓存为 last-good
            Jwt ok = decoder.decode(mint(rsa));
            assertEquals("admin/activity-acme", ok.getSubject());

            // 2) Casdoor「挂」（端点 503）+ 等缓存过期
            up.set(false);
            Thread.sleep(400); // > cache TTL

            // 3) 仍能验签——用 last-good 密钥集（不误 401、不阻塞）
            Jwt afterOutage = decoder.decode(mint(rsa));
            assertNotNull(afterOutage, "P1-12：Casdoor 抖动期应用 last-good 密钥集继续验签");
            assertEquals("admin/activity-acme", afterOutage.getSubject());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void plainDecoder_failsOnOutage_contrast() throws Exception {
        // 对照组：无 outage 容忍。JWKS 从一开始就不可达 → 验签必抛（证 last-good 不是「本来就不校验」）。
        RSAKey rsa = new RSAKeyGenerator(2048).keyID("lg-key2").generate();
        AtomicBoolean up = new AtomicBoolean(false); // 一直挂
        HttpServer server = serveJwks(new JWKSet(rsa.toPublicJWK()).toString(), up);
        try {
            String uri = "http://127.0.0.1:" + server.getAddress().getPort() + "/jwks";
            NimbusJwtDecoder plain = NimbusJwtDecoder.withJwkSetUri(uri).build();
            assertThrows(Exception.class, () -> plain.decode(mint(rsa)),
                    "无 last-good 的 decoder 在 JWKS 不可达时应验签失败");
        } finally {
            server.stop(0);
        }
    }
}
