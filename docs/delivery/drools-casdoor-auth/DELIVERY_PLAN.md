# Drools Casdoor Auth Delivery Plan

## Requirement

启用并补齐 Drools 活动引擎的 Casdoor 认证能力，使生产样式本地入口 `http://localhost:8095` 可完成 SPA Authorization Code + PKCE 登录、后端 JWT 验签、tenant/audience 隔离、控制台与决策热路径保护、登出和回滚，并可被统一能力门户正常打开。

## Repository Evidence

- `activity-common/.../ActivityResourceServerConfig.java` 已有 JWT issuer/exp/audience 校验和 `JwtTenantFilter`，但 security matcher 只覆盖 `/activity-marketing/**`；拆分后的 `/decision/v1/**` 当前会落到 permit-all 链。
- `activity-common/.../AuthConfigController.java` 已提供匿名公开 OIDC 配置，不下发 secret。
- `frontend/src/auth/authClient.ts` 已实现 PKCE S256、state 校验、sessionStorage token 和 refresh；`LoginView.vue` 已支持 auth-config allowlist 与 portal auto launch。
- `deploy/docker-compose.yml` 当前明确运行 dev/header 档；console/decision 没有打开 `activity.tenant.auth.enabled`，容器也没有指向宿主 Casdoor 的 JWKS 地址。
- `scratchpad/casdoor-spa-provision.sh` 已能幂等创建 acme/beta SPA client 和测试用户，但 callback 只覆盖 8099/5173，未覆盖生产样式网关 8095。
- 当前 localhost Casdoor `:8000`、Drools gateway `:8095` 可达；gateway auth-config 实测为 `authEnabled=false`。

## Feasibility

- Verdict: **go**
- Constraints:
  - 仅操作 localhost 开发环境，不接触生产。
  - 复用现有 Casdoor dev org/client/user，不把 client secret 写入前端、Compose、日志或交付文档。
  - 保留用户已有 `deploy.sh`、独立 frontend image 和 nginx 拆分改动。
- Dependencies:
  - Casdoor `http://localhost:8000`、Docker、Java 21、Node/npm、MySQL compose profile。
- Risks and mitigations:
  - console 开 auth 但 decision 仍匿名：扩大同一安全链 matcher并加 decision 集成测试。
  - 容器无法用 `localhost` 拉 JWKS：issuer 保持浏览器/token 的 `localhost:8000`，仅 `jwk-set-uri` 使用 `host.docker.internal`。
  - callback mismatch：provision 脚本幂等并入 `8095/ui/auth/callback`，E2E 不再硬编码 8099。
  - Casdoor 不可用：JWKS warmup fail-fast；compose 保留显式 auth-off 回滚参数。

## Product Design

- Actors and goals:
  - 未登录用户：从门户或 Drools URL 进入后看到租户登录入口，选择/预选租户后跳 Casdoor。
  - 已登录运营用户：callback 后进入控制台，以 token audience 对应 tenant 访问活动数据。
  - 决策调用方：必须携带可映射 tenant 的有效 Casdoor Bearer，匿名不能调用热路径。
  - 运维人员：通过 Compose 环境变量启停 auth，不改镜像源码或泄露 secret。
- Scope:
  - console 与 decision JWT 鉴权、SPA PKCE 登录、8095 callback、双租户隔离、门户入口、部署/测试/文档/CI。
- Out of scope:
  - 生产域名、生产用户、上游企业 IdP、M2M client secret 轮换、auth-platform ReBAC 授权策略。
- Business rules:
  - tenant 只来自已验签 JWT `aud` 的显式 map/受控模板，不信 query/header/body。
  - `X-Tenant-Id` 在 auth 档只能作为一致性信封；与 token tenant 不同返回 403。
  - auth-config 与静态页面匿名，活动 API 和 decision API 需认证；Step 1–18 教学端点维持公开。
  - 浏览器 SPA client 是 public metadata，前端永不使用 client secret。

## Acceptance Criteria

| ID | Observable behavior | Priority | Verification |
| --- | --- | --- | --- |
| AC-01 | `8095/activity-marketing/auth-config` 匿名返回 `authEnabled=true`、公开端点和 allowlisted acme/beta clients，不含 secret | P0 | curl + controller tests |
| AC-02 | 未带 Bearer 调 `/activity-marketing/list` 和 `/api/decision/spu-discount` 均返回 401；auth-config/health/UI 仍可访问 | P0 | HTTP matrix + integration tests |
| AC-03 | 有效 acme/beta token 的 audience 映射到正确 tenant；未知 audience 401，冲突 `X-Tenant-Id` 403 | P0 | JWT integration tests + live smoke |
| AC-04 | `8095/ui/login` 选择 allowlisted client 后生成 PKCE/state 并跳 Casdoor，callback 换 token后回安全站内路径 | P0 | frontend tests + browser E2E |
| AC-05 | acme 创建的活动 beta 不可见；console 与 decision 均使用同一 tenant 语义 | P0 | real two-tenant E2E/API smoke |
| AC-06 | 登出清 session token；新 context 未登录；非法 state/returnTo fail-closed | P1 | existing/new frontend tests + E2E |
| AC-07 | Compose 默认 auth 档可健康启动；容器从宿主 Casdoor 拉 JWKS；Casdoor 不可达时 warmup 失败而非静默放行 | P0 | compose run/log/health evidence |
| AC-08 | `DROOLS_AUTH_ENABLED=false DROOLS_DEV_DEFAULT_ENABLED=true` 可回滚到原 header 档，既有测试/Step 端点不回归 | P1 | config render + regression tests |
| AC-09 | 统一门户 Drools 卡片指向 `8095/ui/login?...clientId=...` 且目标 allowlist 校验后自动发起 | P1 | catalog parse + target component test/live check |
| AC-10 | CI 覆盖 Maven tests/package 与 frontend tests/typecheck/build，配置不含 secret | P1 | workflow syntax + local parity |

## UI/UX Design

- Applicability: 使用现有 Vue 登录页与身份条，不做视觉重设计。
- Flow and component map:
  - portal → `/ui/login?source=portal&auto=1&clientId=...` → `ensureConfig` → allowlist → `beginLogin` → Casdoor → `/ui/auth/callback` → safe return path。
  - 直接访问 `/ui/console` → router guard → 登录页 tenant buttons。
- State matrix:
  - config loading；config failure 显示错误；auth disabled 不 auto；unknown client 保留选择页；redirecting 禁止重复点击；callback/state/token error 显示错误并可回登录页。
- Responsive/accessibility: 延续现有按钮、错误文案和 390/768/desktop 规则；登录操作可键盘触发，状态不只靠颜色。

## Technical Solution

- Chosen approach:
  - 扩展既有 resource-server chain 同时匹配 `/activity-marketing/**` 与 `/decision/v1/**`。
  - Compose 对 console/decision 同步注入 auth、dev-default、issuer/JWKS 参数；使用同一 token/tenant contract。
  - Casdoor provision 并入 8095 callback；E2E 从 `BASE` 派生回跳 origin。
  - gateway 保持 Bearer 透传；门户切到 production-style 8095 target route。
- Alternatives rejected:
  - 只在 nginx 校验 token：会复制 issuer/audience/tenant 逻辑，破坏应用 fail-closed 语义。
  - 只保护 console：decision 热路径匿名，违反物理拆分后的数据边界。
  - 门户直跳 Casdoor：目标 origin 无 PKCE verifier/state。
  - 把 client secret 放 SPA：public client 不需要且会泄露。
- Modules and file map:
  - `activity-common/.../ActivityResourceServerConfig.java` — console+decision matcher。
  - `activity-decision/src/test/.../DecisionAuthIntegrationTest.java` — decision JWT matrix。
  - `deploy/docker-compose.yml` — auth profile/JWKS/rollback vars。
  - `scratchpad/casdoor-spa-provision.sh` — 8095 callback 幂等并集。
  - `frontend/e2e/e2e-oidc-v2.mjs` — BASE-aware real E2E。
  - `deploy.sh`, `README.md`, auth/runbook docs — operator workflow。
  - `.github/workflows/ci.yml` — Maven/frontend CI（若当前仓库仍无 provider pipeline）。
  - `auth-platform/project-portal/public/config/catalog.json` — Drools 8095 launch URL。
- Contracts and data:
  - JWT: `iss=http://localhost:8000`, `aud=activity-<tenant>-web-cid|activity-<tenant>-cid`, `sub` actor。
  - Public config: authEnabled/issuer/authorizeEndpoint/tokenEndpoint/scope/webClients；无 secret。
  - No DB migration; existing tenant-scoped entities unchanged.
- Security and reliability:
  - JWKS signature + exp + issuer + audience validator；JwtTenantFilter fail-closed。
  - sessionStorage token、PKCE S256、state CSRF、safe internal returnTo。
  - warmup/last-good JWKS behavior沿用；auth-off为显式回滚而非静默降级。
- Observability:
  - health、container logs、401/403 matrix；不记录 token/password。
- Compatibility and migration:
  - Step 1–18、health、static 保持公开；auth disabled 行为保持现状。

## Implementation Sequence

1. Security boundary slice: protect decision path and add tests（AC-02/03/05/08）。
2. Deployment slice: Compose auth/JWKS/rollback and Casdoor 8095 provision（AC-01/07/08）。
3. UI/E2E slice: BASE-aware E2E, deploy current frontend, portal 8095 launch（AC-04/06/09）。
4. Quality slice: full tests/build, live Casdoor dual-tenant E2E, review/repair, docs/CI（AC-01~10）。

## Verification Plan

| AC/Risk | Test level | Case or command | Required evidence |
| --- | --- | --- | --- |
| AC-01/02/03 | backend integration | Maven auth endpoint + JWT security tests | status/body/401/403 assertions |
| AC-02/05 | decision integration | new decision auth tests + live gateway curl | anonymous 401, token tenant correctness |
| AC-04/06/09 | frontend | Vitest LoginView/authClient/portal tests | PKCE single start, state/returnTo, allowlist |
| AC-04/05/06 | browser E2E | `BASE=http://localhost:8095 npm run e2e:oidc` | login/create/logout/beta isolation results |
| AC-07/08 | deployment | compose config/up/health + auth-off render | true profile healthy; rollback vars rendered |
| AC-10 | full regression | `./mvnw test`, frontend test/typecheck/build | zero failures |

## Documentation Plan

- 更新 README 的默认 auth 部署、dev 账号、回滚和门户入口。
- 新增/更新 auth 操作手册：Casdoor provision、Compose 参数、HTTP 验证、故障排查。
- 维护本目录 plan/status/review/QA/delivery artifacts。

## CI Plan

- GitHub remote 若确认存在则新增最小 workflow：Java 21 Maven tests/package；Node frontend install/test/typecheck/build；不使用本地 Casdoor secret、不做部署。

## Rollout And Rollback

1. 先修 security matcher/tests；provision 8095 callback。
2. 重建 console/decision/frontend，默认 auth=true，验证 health/auth-config/401。
3. 跑 acme/beta 真登录和隔离 E2E，再把门户 Drools URL 切 8095。
4. 回滚：`DROOLS_AUTH_ENABLED=false DROOLS_DEV_DEFAULT_ENABLED=true ./deploy.sh`；门户可暂设 maintenance。无数据迁移。

## Assumptions And Open Decisions

- Assumption A1: localhost Casdoor 的 dev acme/beta org 和测试应用允许由现有幂等脚本更新。
- Assumption A2: “系统正常使用”包含 console 与 decision 两个物理服务，不能只验证登录页面。
- Open production inputs: 真实 HTTPS origin、生产 client/user/redirect URI 和企业 IdP；不在本地交付中猜测。

## Approval

- Status: **approved**
- Approved scope: Drools auth capability through usable local system, including security gap repair, Casdoor/Compose/frontend/portal integration and full verification
- Evidence: user message on 2026-07-22 — “现在开始做Drools的auth能力，最后使得系统能正常使用”
