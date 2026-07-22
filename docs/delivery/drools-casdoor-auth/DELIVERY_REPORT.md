# Delivery Report

## Delivered Outcome

Drools 已在 `http://localhost:8095` 正常运行 Casdoor Authorization Code + PKCE 登录。console 与独立 decision 服务都执行 JWT 验签和 aud→tenant 隔离；统一门户可以把用户带到 Drools 目标登录入口，目标前端完成 allowlist 校验后再跳 Casdoor。

## Runtime Entry Points

- Unified portal: `http://localhost:5274`
- Drools login: `http://localhost:8095/ui/login`
- Drools console: `http://localhost:8095/ui/console`
- Casdoor: `http://localhost:8000`
- Public auth config: `http://localhost:8095/activity-marketing/auth-config`

Local dev users:

- acme: `act-alice` / `act-alice-dev-pass-01`
- beta: `act-bob` / `act-bob-dev-pass-02`

## Main Changes

- 修复 decision 匿名访问安全缺口，并加 JWT integration matrix。
- Compose 默认 auth-on，console/decision 使用同一 issuer/audience/tenant contract；保留显式 auth-off 回滚。
- Casdoor SPA provision 支持 8095 和 Vite `/ui/auth/callback`。
- 真实 OIDC E2E 同时验证 console、decision、403 租户冒充、创建、登出和双租户隔离。
- 门户 Drools catalog 切到 8095，目标侧 clientId allowlist 与 returnTo 清洗生效。
- nginx callback 禁止记录 authorization code、禁缓存并设置防泄露安全头。
- README、部署手册、活动模块文档、QA profile、doc map 和 GitHub Actions CI 已同步。

## Operate

Local clean deployment with Casdoor reconciliation:

```bash
./deploy.sh --provision-auth
```

Focused real auth verification:

```bash
BASE=http://localhost:8095 npm --prefix frontend run e2e:oidc
```

Rollback to header-only dev mode without data migration:

```bash
DROOLS_AUTH_ENABLED=false DROOLS_DEV_DEFAULT_ENABLED=true ./deploy.sh
```

The previous local backend runtime images were retained as:

- `activity-console:pre-auth-backup-20260722`
- `activity-decision:pre-auth-backup-20260722`

## Quality Gate

- Maven: 115 passed, 3 skipped, 0 failed/error.
- Frontend: 59 unit tests, typecheck and production build passed.
- Portal: 13 tests and production build passed.
- Real Casdoor browser flow: 12/12 passed.
- HTTP security matrix, rollback render, nginx syntax/headers/log redaction and diff whitespace checks passed.

## Production Boundary

This delivery is complete for localhost/dev. Before production, supply the real HTTPS portal/Drools/Casdoor domains, create production public clients and redirects, use managed secrets and user lifecycle, define global logout/revocation policy, and generate CSP for the actual IdP/API origins.
