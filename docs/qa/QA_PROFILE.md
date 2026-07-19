# QA 环境档案 · drools-demo（活动营销多租户）

> 首次由 /qa-test 勘察生成（2026-07-19）。人工可改，下次直接复用。

## 技术栈 / 启动
- Java 21 / Spring Boot 3.3.5 / Drools 8.44.2 / Maven wrapper。
- **dev/header 档（QA 常用，auth 关、dev-default 开）**：
  `./mvnw spring-boot:run -Dspring-boot.run.profiles=h2`（默认 8081）。
  - ⚠️ **坑1**：本机 8081 常被 Docker 容器占用 → 换端口跑：`-Dspring-boot.run.arguments="--server.port=8097"`。
  - ⚠️ **坑2**：`h2` profile 用 **file 库** `./data/drools-demo`（单连接锁）→ **不能同时跑两个 h2 app**。
    QA 起干净实例请覆盖内存库：`--spring.datasource.url=jdbc:h2:mem:qadev;DB_CLOSE_DELAY=-1;MODE=MySQL --spring.jpa.hibernate.ddl-auto=create-drop`。
- **Casdoor 档（auth 开，决策 API 端到端）**：加 `--activity.tenant.auth.enabled=true --activity.tenant.dev-default-enabled=false`；
  真 token 端到端由用户 `!` 跑 `scratchpad/casdoor-m2m-verify.sh`（涉及 IdP secret，auto-mode 拦，须用户授权）。

## 入口 / 健康检查
- 健康：`GET /actuator/health` → 200。
- 前端：`GET /`（活动营销台在侧栏「活动营销」组）；资源 `/assets/activity.js`、`/assets/activity.css`。
- 活动 API 全在 `/activity-marketing/*`（见 `docs/activity-marketing.md` 接口表）。

## 多租户测试要点（本项目特有）
- **租户来源**：dev 档从 `X-Tenant-Id` header；auth 档从 JWT 的 `aud` 解析。
- **前端**：活动台顶部「租户 (X-Tenant-Id)」切换条（输入 + acme/beta/__dev__ 快捷，localStorage 记忆），切租户即换数据视图。
- **隔离断言**：A（X-Tenant-Id: acme）建的活动，B（beta）列表/详情/优惠查询都看不到；detail 越权 → 400。
- **保留值**：`X-Tenant-Id: __no_tenant__` → 400（保留哨兵不可冒充）；非法字符 → 400；无 header + dev-default → 回落 `__dev__`。

## 凭据
- dev 档无需凭据（header 即身份，仅本地）。Casdoor 档：admin/123（Casdoor 后台，`localhost:8000`）；机器 token 由 provision 脚本铸。

## 测试素材
- 接口 + curl 示例：`docs/activity-marketing.md`。
- 回归单测：`./mvnw test`（55 绿，含 `TenantIsolationTest`/`AudienceTenantResolutionTest`/`ActivityAuthIntegrationTest` 等）。
- UI：**本环境无 Playwright MCP**，交互式 UI 用例暂只能人工或结构性验证。
