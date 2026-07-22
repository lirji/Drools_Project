# Review Report

## Scope

Review 覆盖 Drools Casdoor 认证边界、PKCE/portal adapter、tenant 解析、nginx Bearer 透传、Compose 默认值、Casdoor provision、测试与运维文档。既有未提交的前端/部署重构变更被视为用户工作并保留。

## Outcome

- Verdict: **approved after repair**
- Open P0/P1 findings: **0**
- Open P2 findings: **0**
- Delivery-blocking risk: **none for localhost/dev environment**

## Findings And Repairs

| ID | Severity | Finding | Repair | Evidence |
| --- | --- | --- | --- | --- |
| SEC-01 | P0 | 拆分后的 `/decision/v1/**` 未匹配 JWT security chain，会落入 permit-all chain | resource-server matcher 同时覆盖 console 与 decision；新增 decision RSA/JWT 集成测试 | 无 token 401、未知 aud 401、合法 acme 200、信封冒充 403；live E2E 同样通过 |
| OPS-01 | P0 | 生产样式 Compose 实际运行 auth-off/dev-default，UI 登录不代表后端受保护 | console/decision 同步默认 auth-on、dev-default=false，issuer 与容器 JWKS 地址分离 | auth-config=true；两个服务 JWKS warmup 成功；匿名 console/decision 均 401 |
| OIDC-01 | P1 | Casdoor SPA client 缺少 `8095/ui/auth/callback`，E2E 回跳硬编码 8099 | provision 幂等追加 8095 callback；E2E 从 BASE 派生 origin | provision pass=4/fail=0；真实 PKCE E2E 12/12 |
| SEC-02 | P1 | callback query 中的一次性 authorization code 可能进入 nginx access log；缺少基础防泄露头 | exact callback 使用静态 alias、`access_log off`、`no-store`；加入 `no-referrer`、nosniff、DENY frame 与 Permissions-Policy | sentinel code 未出现在容器日志；callback 200 `text/html`；nginx config test 通过 |
| PORTAL-01 | P1 | 门户携带的 clientId/returnTo 若直接信任会形成 OAuth client 注入或开放跳转 | 目标 Drools 仅接受 auth-config allowlist clientId，并将 returnTo 限制为站内单斜杠路径 | Vitest 覆盖危险值；live allowlisted 请求跳 :8000、未知 client 留在 :8095 |

## Security Invariants Confirmed

- 浏览器不使用或下发 client secret；auth-config 和前端产物中无 secret。
- JWT 校验包含 signature、exp、issuer 和 aud→tenant validator；tenant 不来自 query/body。
- auth 档不发送 `X-Tenant-Id`；若外部调用同时发送冲突信封，后端返回 403。
- token 存在 sessionStorage；state 与 PKCE S256 生效；登出清本 tab token。
- nginx 只透传 Bearer，不复制应用层 issuer/audience/tenant 决策。

## Residual Production Notes

- `scratchpad/casdoor-spa-provision.sh` 和固定测试账号仅用于本机 dev Casdoor，不能用于生产身份生命周期。
- 生产需要替换为 HTTPS 域名、生产 SPA client/redirect、正式凭据管理和企业 IdP；issuer 必须与 token `iss` 精确一致。
- 当前“登出”清理 Drools SPA session token，不执行 Casdoor 全局 SSO 注销或 token revocation。
- nginx 已有基础安全头；生产 CSP 需结合实际 Casdoor/API 域名生成，不能照抄 localhost allowlist。
