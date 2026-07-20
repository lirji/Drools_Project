# QA 环境档案 · drools-demo（活动营销多租户）

> 首次由 /qa-test 勘察生成（2026-07-19）。人工可改，下次直接复用。

## 技术栈 / 启动
- Java 21 / Spring Boot 3.3.5 / Drools 8.44.2 / Maven wrapper。
- **仓库形态（2026-07 · M2.1 起 = Maven 四模块）**：`activity-common`(库) / `drools-lab`(库,Step1-18) / `activity-console`(app,8081,写面+Step1-18+前端`/ui/`) / `activity-decision`(app,8082,只读决策`/decision/v1`)。根 `./mvnw spring-boot:run` **已失效**（父聚合 pom 无 main）——起服务须 `-pl` 指定 app 模块。
- **dev/header 档（QA 常用，auth 关、dev-default 开）**：
  - 活动写面 + 台 + 旧读端点（console，8081）：`./mvnw -pl activity-console spring-boot:run -Dspring-boot.run.profiles=h2`
  - 只读决策热路径（decision，8082，测 `/decision/v1/*`）：`./mvnw -pl activity-decision spring-boot:run -Dspring-boot.run.profiles=h2`
  - ⚠️ **坑1**：本机 8081/8082 常被 Docker 容器占用 → 换端口：`-Dspring-boot.run.arguments="--server.port=8097"`。
  - ⚠️ **坑2**：`h2` profile 用 **file 库**（console `./data/drools-demo` / decision `./data/decision`，单连接锁）→ **同一 file 库不能两个 app 同开**。QA 起干净实例请覆盖内存库：`--spring.datasource.url=jdbc:h2:mem:qadev;DB_CLOSE_DELAY=-1;MODE=MySQL --spring.jpa.hibernate.ddl-auto=create-drop`。
  - **前端 SPA 需先构建**：`-Pfrontend`（`./mvnw -pl activity-console -Pfrontend spring-boot:run …`）把 Vue 产物拷进 `static/ui/`，或本地 `cd frontend && npm run dev`（Vite :5173）。不构建时 `/ui/` 404、根 `/` 只是落地页。
  - **整套微服务编排**（两 app + nginx 网关 host `:8095` + MySQL 单库双账号 + Prometheus `:9090` + Grafana `:3001`）：`docker compose -f deploy/docker-compose.yml up --build` → 网关 `http://localhost:8095/ui/console`。
- **Casdoor 档（auth 开，决策 API 端到端）**：加 `--activity.tenant.auth.enabled=true --activity.tenant.dev-default-enabled=false`；
  真 token 端到端由用户 `!` 跑 `scratchpad/casdoor-m2m-verify.sh`（涉及 IdP secret，auto-mode 拦，须用户授权）。

## 入口 / 健康检查
- 健康：`GET /actuator/health` → 200（console 8081 / decision 8082 各一个）。
- 前端：根 `GET /` 是**构建无关落地页**（跳 `/ui/`，旧原生台 + `/assets/activity.js|css` 已于 F3 退役删除）；SPA 挂 `/ui/`，活动配置台 `/ui/console`（列表 `/ui/console/activities`、验证 `/ui/console/validate`），18 Step 演示台 `/ui/demos`。
- 活动写面/旧读端点（console，8081）：`/activity-marketing/*`（见 `docs/activity-marketing.md` 接口表）。
- 只读决策热路径（decision，8082）：`POST /decision/v1/spu-discount`、`POST /decision/v1/gifts`（复用与 console 同一 `ActivityQueryService`，行为等价）。

## 多租户测试要点（本项目特有）
- **租户来源**：dev 档从 `X-Tenant-Id` header；auth 档从 JWT 的 `aud` 解析。
- **前端**：活动台顶部「租户 (X-Tenant-Id)」切换条（输入 + acme/beta/__dev__ 快捷，localStorage 记忆），切租户即换数据视图。
- **隔离断言**：A（X-Tenant-Id: acme）建的活动，B（beta）列表/详情/优惠查询都看不到；detail 越权 → 400。
- **保留值**：`X-Tenant-Id: __no_tenant__` → 400（保留哨兵不可冒充）；非法字符 → 400；无 header + dev-default → 回落 `__dev__`。

## 凭据
- dev 档无需凭据（header 即身份，仅本地）。Casdoor 档：admin/123（Casdoor 后台，`localhost:8000`）；机器 token 由 provision 脚本铸。

## 测试素材
- 接口 + curl 示例：`docs/activity-marketing.md`。
- 回归单测：`./mvnw test`（全 reactor **111 绿** 3 skip：common 63 / console 40 / decision 8；含 `TenantIsolationTest`/`AudienceTenantResolutionTest`/`ActivityAuthIntegrationTest`/`RoleGateDecisionTest`/`GenerationWarmPollerTest` 等）。
- UI：**本环境无 Playwright MCP**，交互式 UI 用例暂只能人工或结构性验证。
