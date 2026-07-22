# Delivery Status

## Goal

使 Drools 在 production-style localhost `:8095` 下完成 Casdoor PKCE 登录、console/decision JWT 鉴权、tenant 隔离、登出、门户跳转和可回滚部署。

## State

- Phase: Phase 5 — delivered
- Status: complete
- Last updated: 2026-07-22 Asia/Taipei

## Completed

- 读取交付技能、artifact contract、仓库规则与当前 worktree。
- 核对现有 frontend PKCE/state/sessionStorage、auth-config、JWT decoder/audience resolver/JwtTenantFilter 和 Casdoor provision/E2E。
- 运行态确认 Casdoor `:8000`、Drools `:8095` 可达，但 auth-config 为 `authEnabled=false`。
- 发现并记录拆分后 security gap：resource-server matcher 只保护 `/activity-marketing/**`，`/decision/v1/**` 当前 permit-all。
- 完成 AC-01~10、实现顺序、验证、回滚和 CI 设计；用户“现在开始做”视为批准执行。
- resource-server chain 已同时保护 `/activity-marketing/**` 与 `/decision/v1/**`，修复独立 decision 服务匿名访问缺口。
- 新增 decision auth 集成测试，覆盖无 token、未知 audience、合法 tenant token、tenant header 冒充。
- Compose 已默认 auth-on，并为 console/decision 配置浏览器公开 OIDC 地址、容器 JWKS 地址和显式 auth-off 回滚参数。
- Casdoor SPA 应用已幂等并入 `8095/ui/auth/callback`，acme/beta dev 用户已就绪。
- 门户 Drools 入口已切到 `8095/ui/login`；OIDC E2E 回跳不再硬编码端口。
- auth-enabled Docker stack 已重建并保持运行；console/decision JWKS warmup 和健康检查通过。
- 真实 Casdoor Playwright E2E 12/12：console/decision Bearer、403 冒充、acme 创建、logout、新 context、beta 隔离全部通过。
- nginx callback 已 no-store/no-referrer、不记 access log，避免 authorization code 落网关日志。
- CI、README、deployment/activity/QA/doc-map 和最终 review/QA/delivery artifacts 已同步。

## Changed Files

- `docs/delivery/drools-casdoor-auth/DELIVERY_PLAN.md` — approved delivery design。
- `docs/delivery/drools-casdoor-auth/DELIVERY_STATUS.md` — workflow state。
- `activity-common/src/main/java/com/lrj/drools/activity/tenant/ActivityResourceServerConfig.java` — console + decision JWT boundary。
- `activity-decision/src/test/java/com/lrj/drools/activity/DecisionAuthIntegrationTest.java` — decision auth integration matrix。
- `deploy/docker-compose.yml` — auth-on deployment and rollback variables。
- `scratchpad/casdoor-spa-provision.sh` — 8095/dev callback reconciliation。
- `frontend/e2e/e2e-oidc-v2.mjs` — BASE-aware callback wait。
- `auth-platform/project-portal/public/config/catalog.json` — production-style Drools gateway entry。

## Verification Log

| Command or check | Result | Notes |
| --- | --- | --- |
| branch/status/rules discovery | pass | branch main；保留既有 deploy/frontend/user changes |
| live `:8095/activity-marketing/auth-config` | pass (evidence) | returned `{"authEnabled":false}`，证明部署缺口 |
| security chain inspection | finding | matcher only `/activity-marketing/**`; open chain permits decision path |
| SPA/provision/E2E inspection | pass | reusable PKCE implementation exists; 8095 callback missing |
| `DecisionAuthIntegrationTest` | pass | 4/4; JWT 401/403/200 matrix verified |
| frontend Vitest | pass | 13 files / 59 tests |
| frontend typecheck + production build | pass | vue-tsc clean; Vite build succeeded |
| Compose render | pass | auth=true, dev-default=false, issuer/JWKS split on both services |
| Casdoor provision | pass | 2 apps updated + 2 users present; fail=0 |
| full Maven package | pass | 115 passed, 0 failures/errors, 3 skipped |
| real Casdoor browser E2E | pass | 12/12 at localhost:8095 |
| live HTTP matrix | pass | config/UI/health 200; anonymous console/decision 401; valid decision 200; mismatch 403 |
| portal live adapter | pass | allowlisted client redirects to :8000; unknown stays :8095 |
| nginx callback hardening | pass | text/html, no-store/no-referrer, sentinel absent from logs |
| rollback render | pass | auth=false + dev-default=true rendered for both apps |
| CI/docs/reports | pass | workflow syntax valid; active docs synchronized |

## Decisions And Deviations

- 不另造认证框架；修复并启用现有 Casdoor resource-server + SPA PKCE。
- 目标包含 console 与 decision 两个物理服务，避免“只有 UI 登录、热路径匿名”。
- Compose 默认 auth-on，显式环境变量保留 dev/header 回滚。

## Blockers And Residual Risks

- No implementation blocker.
- Production domains/clients/credentials/IdP policy remain environment inputs and are not guessed in this localhost delivery.
- Existing uncommitted deployment/frontend changes belong to user and must remain intact.

## Next Action

Delivery complete. Keep the local stack running; use `DELIVERY_REPORT.md` for operation and production follow-up.
