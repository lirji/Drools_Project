# 前端控制台现状

> 活文档。最后核对：2026-08-28，基于 `frontend/package.json`、`frontend/src/router/index.ts`、
> `frontend/vite.config.ts` 与当前组件/测试目录。带日期的 `docs/plans/frontend-*` 是设计与交付快照，
> 不能代替本页描述当前实现。

## 技术基线

- Vue 3.5 + TypeScript 5.6 + Vite 6，状态管理用 Pinia 2，路由用 Vue Router 4。
- SPA 固定挂在 `/ui/`；开发服务器默认 `http://localhost:5173/ui/`，生产产物为 `frontend/dist/`。
- 后端写平面默认代理到 `VITE_PROXY_TARGET=http://localhost:8081`；只读决策平面单独由
  `VITE_DECISION_TARGET=http://localhost:8082` 接管 `/api/decision/*`，并重写为 `/decision/v1/*`。
- 网关端口由 `DROOLS_UI_PORT` 决定，未设置时为 8095。`deploy.sh` 会优先读取相邻
  `auth-platform/deploy/load-platform-ports.sh` 的中央端口注册；独立 checkout 不依赖该文件。

## 页面与路由

| URL（均在 `/ui/` 下） | 路由名 | 作用 |
|---|---|---|
| `/home` | `home` | 平台概览 |
| `/login` | `login` | OIDC 登录 |
| `/auth/callback` | `callback` | PKCE 回调 |
| `/console/activities` | `activities` | 活动列表、状态与批量操作 |
| `/console/playbooks` | `playbooks` | 玩法模板 |
| `/console/activities/new` | `activity-new` | 新建活动 |
| `/console/activities/:id` | `activity-detail` | 活动详情 |
| `/console/activities/:id/edit` | `activity-edit` | 编辑并生成新版本 |
| `/console/validate` | `validate` | 写平面/决策平面验证与对拍 |

认证开启时，全局路由守卫先加载 `/activity-marketing/auth-config`，再刷新会话；未登录访问业务页会跳到
`/login?returnTo=...`。Token 失效后 `AppShell` 负责回到登录页。开发 header 档与 OIDC 档的边界见
[`deployment.md`](deployment.md)。

## 本地开发与构建

```bash
cd frontend
npm ci
npm run dev
npm run typecheck
npm test -- --run
npm run build
```

生产网关镜像不会在容器里执行前端构建：`deploy/Dockerfile.frontend` 只复制 `frontend/dist/`。因此发布 UI 的
顺序必须是 `npm run build`，再执行 `docker compose -f deploy/docker-compose.yml up -d --build gateway`。

## 测试入口

`package.json` 当前提供 9 个 E2E 脚本：

- header/dev 档：`e2e:dev`、`e2e:tablet`、`e2e:phone`、`e2e:ruler`、`e2e:bench`、
  `e2e:playbooks`、`e2e:validate`、`e2e:visual`；
- OIDC 档：`e2e:oidc`。

`e2e:catalog` 已随教学 Demo catalog UI 删除，不再是有效 npm script。稳定选择器契约见
[`frontend/e2e/data-testid-contract.md`](../frontend/e2e/data-testid-contract.md)。E2E 会创建或变更活动数据，
只应在本地/测试环境运行。

## 维护约束

- 新增业务 API 前缀时同步 `frontend/vite.config.ts` 与 `deploy/nginx.conf`；`/decision/calculate` 是 console
  内的教学端点，而 `/api/decision/*` 属于独立 decision 服务，不能合并代理。
- UI 自动化优先使用 `data-testid`，新增/删除契约时同步 `frontend/e2e/data-testid-contract.md`。
- 主题、间距、颜色优先复用 `frontend/src/shared/styles/tokens.css`；视觉效果集中在 `effects.css`。
- 路由或页面能力变化时同步本页、README 和 `docs/doc-map.md`。
