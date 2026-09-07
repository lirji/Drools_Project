# 部署与编排（微服务形态）

> 活文档。最后核对：2026-09-06。网关宿主端口由 `DROOLS_UI_PORT` 控制，默认 8095；若相邻
> `auth-platform` 提供中央端口注册，`deploy.sh` 会自动加载并同步生成 OIDC callback。

> ⚠️ **重建前端的正确顺序**：`cd frontend && npm run build` → `docker compose -f deploy/docker-compose.yml up -d --build gateway`。
> `deploy/Dockerfile.frontend` **不在容器里构建前端**，它只 `COPY frontend/dist/`（dist 由宿主机生成）。
> 漏掉 `npm run build` 时 Docker 发现 dist 未变会复用镜像层——构建 exit 0、镜像时间戳不动、页面还是旧的。
> 只重建 `console` 更是完全无效：前端不在 console 的 JAR 里。

> 本仓库 M2.1 起是 Maven 四模块、两个独立 Spring Boot 应用。本文收敛「更像生产」的本地整套编排（`deploy/`）：两 app + nginx 网关 + MySQL + XXL-JOB + Prometheus/Grafana。设计缘由见 `docs/plans/prod-arch-refactor-0719-1330/`。

## 模块与应用

| 模块 | 类型 | 端口 | 职责 | 依赖 |
| ---- | ---- | ---- | ---- | ---- |
| `activity-common` | 库 | — | 活动引擎共享内核（domain/engine/persistence/tenant + `ActivityQueryService`）；drools 只引 KieHelper 核心 | — |
| `drools-lab` | 库 | — | Step 1–24 教学 + 重 drools 依赖（kie-ci/kie-dmn/decisiontables/xml-support） | — |
| `activity-console` | 应用 | 8081 | 写平面 + Step 1–24 + 前端 `/ui/` + **唯一 DDL 执行者** | common + drools-lab |
| `activity-decision` | 应用 | 8082 | 只读决策热路径 `/decision/v1/*` + 发布代际轮询预热 | 仅 common（更轻，甩掉 kie-ci/dmn） |

两 app 主类 `ConsoleApplication` / `DecisionApplication` 都在根包 `com.lrj.drools`；decision 的 classpath 上无写平面 bean、无 `DroolsConfig`，**结构上就写不了**。可执行 jar：decision ~67MB vs console ~104MB（依赖甩除实证）。

## 本地单机起（不走容器）

```bash
# console（写面 + Step1-24 + 台）；-Pfrontend 顺带把 Vue SPA 构建拷进 static/ui/
./mvnw -pl activity-console -Pfrontend spring-boot:run -Dspring-boot.run.profiles=h2   # http://localhost:8081/ui/
# decision（只读 /decision/v1）
./mvnw -pl activity-decision spring-boot:run -Dspring-boot.run.profiles=h2             # http://localhost:8082/decision/v1/spu-discount
```

h2 profile 用 file 库（console `./data/activity-platform`、decision `./data/decision`）；同一 file 库不能两 app 同开，需要时覆盖内存库（见 `docs/qa/QA_PROFILE.md`）。

> **decision 对着空库起不来是预期行为**（2026-08 起）：`activity-decision/src/main/resources/application.yml` 的 `ddl-auto` 已由 `update` 收敛为 `validate`（只读平面不碰 DDL，见「单库双账号」一节）。空 `./data/decision` 直接起会失败并报 `Schema-validation: missing table [activity_artifact]`。只想单机验证结构时，用 dev-only 环境变量覆盖，**不要改仓库里的值**（`DecisionDdlGuardTest` 会红）：
>
> ```bash
> SPRING_JPA_HIBERNATE_DDL_AUTO=update ./mvnw -pl activity-decision spring-boot:run -Dspring-boot.run.profiles=h2
> ```
>
> 真正的共享数据形态是 MySQL（console 建表 → decision 只读 validate），见下节容器编排。

## 容器编排（`deploy/docker-compose.yml`）

```bash
./deploy.sh --provision-auth
# 网关：http://localhost:${DROOLS_UI_PORT:-8095}/ui/console
```

`--provision-auth` 只用于本机 dev Casdoor：它幂等登记 acme/beta public SPA client、
`http://localhost:${DROOLS_UI_PORT:-8095}/ui/auth/callback` 与测试用户，不把 client secret 写入浏览器或
Compose。若 Casdoor 资源已存在，可直接运行 `./deploy.sh`。

服务与宿主端口：

Compose 项目名在 `deploy/docker-compose.yml` 中固定为 `drools-platform`，Docker Desktop 会将本项目容器归组到该名称下，不再使用目录名 `deploy`。

| 服务 | 镜像 | 宿主端口 | 说明 |
| ---- | ---- | ---- | ---- |
| `mysql` | mysql:8.0 | 127.0.0.1:3307→3306 | 业务库 `activity_platform` + 独立调度库 `xxl_job`；数据保存在 `mysql-data` 命名卷 |
| `xxl-job-admin` | xuxueli/xxl-job-admin:3.4.2 | **127.0.0.1:18088**→8080 | 调度控制台、执行日志、失败重试和人工触发；不保存活动业务状态；可用 `DROOLS_XXL_ADMIN_PORT` 覆盖 |
| `console` | `drools-platform/activity-console:latest`（`--build-arg MODULE=activity-console`） | — | 容器内 `SERVER_PORT=8080`；另在 Docker 内网开放 XXL 执行器端口 9999 |
| `decision` | `drools-platform/activity-decision:latest`（`--build-arg MODULE=activity-decision`） | — | 容器内 8080；`depends_on: console service_healthy` |
| `gateway` | **`drools-platform/activity-frontend:latest`（`deploy/Dockerfile.frontend` 构建，FROM nginx:1.27-alpine）** | **`${DROOLS_UI_PORT:-8095}`**→80 | 前缀分流 + SPA 托管。⚠️ 它是**构建出来的镜像不是直接拉的 nginx**——Vue 产物烤在里面，改前端要 `--build gateway` |
| `prometheus` | prom/prometheus | 9090 | 旧本地指标栈，仅 `legacy-observability` profile 或 `deploy.sh` 显式启动 |
| `grafana` | grafana/grafana | 3001 | 旧本地面板，仅 `legacy-observability` profile 或 `deploy.sh` 显式启动 |

### 统一链路追踪

推荐复用同级 `dev-infra` 的 Grafana + Tempo + OpenTelemetry，而不是另起本项目的旧 Prometheus/Grafana：

```bash
cd ../dev-infra && make marketing-obs
cd ../drools-demo
docker compose -f deploy/docker-compose.yml -f deploy/compose.observability.yml \
  up -d --build mysql xxl-job-admin console decision gateway
```

`console` 与 `decision` 的服务名分别为 `drools-activity-console` 和 `drools-activity-decision`，Grafana 入口是 `http://127.0.0.1:3001`。默认本地全采样；设置 `OTEL_TRACES_SAMPLER=traceidratio`、`OTEL_TRACES_SAMPLER_ARG=0.1` 可降采样，`OTEL_SDK_DISABLED=true` 可停用。直接 HTTP/Kafka 传播 W3C 上下文；grant/AwardIntent outbox 的 relay 会开始新 trace，业务 `traceId/grantNo/sourceRequestId` 用于跨异步边界检索。完整规则见同级 `dev-infra/docs/observability.md`。

**镜像构建**：单个 `deploy/Dockerfile`，一个 build 阶段构建整个 reactor，运行阶段 `ARG MODULE` 选装某 app 的 jar；compose 用不同 `--build-arg MODULE` 出两镜像（build 阶段共享层缓存）。`.dockerignore` 排除 `node_modules`/`target`/`deploy`/`docs` 等，保持上下文精简、避免无谓 rebuild。

### XXL-JOB 活动生命周期任务

Docker 默认设置 `activity.marketing.lifecycle-schedule.mode=xxl`，由 XXL-JOB 每 5 秒触发一次
`activityLifecycleSweep`。本机直接运行默认是 `local`（Spring `@Scheduled`），`off` 完全关闭；模式互斥，
不能同时开两个触发器。XXL 只负责触发、日志、重试和告警，活动状态的事实来源仍是
`activity_platform.activity_manage`，多租户扫描、逐活动事务、悲观锁与发布代际推进都留在 console。

首次创建 MySQL 容器时，`deploy/mysql-init/02-xxl-job.sql` 会建立独立 `xxl_job` 库、账号、执行器组和
唯一的“活动生命周期定时上下线”任务，不导入官方 Sample/Demo 数据。已有 MySQL 容器不会重新执行
`docker-entrypoint-initdb.d`，升级时需幂等执行一次：

```bash
docker compose -f deploy/docker-compose.yml exec -T mysql \
  mysql --default-character-set=utf8mb4 -uroot -prootpass \
  < deploy/mysql-init/02-xxl-job.sql
```

正常使用 `./deploy.sh` 时，这一步已自动幂等执行，并会核对 3.4.2 的 8 张表 / 70 列；旧版或残缺的
`xxl_job` Schema 会 fail-fast，不能把 `CREATE TABLE IF NOT EXISTS` 当成版本升级。历史版本若使用匿名 MySQL
卷，部署脚本也会拒绝自动重建，必须先备份并迁移到 `mysql-data` 命名卷，避免整库丢失。

控制台地址为 `http://localhost:18088/`，本地初始账号是 `admin / xxl-admin-2026`。管理端口和
MySQL 端口默认只绑定宿主机回环地址；该密码与 Compose 默认 AccessToken 仍只用于本地编排，正式环境必须
替换 `DROOLS_XXL_ACCESS_TOKEN`、管理账号密码和数据库凭据。执行器 9999 端口只在服务内网开放，地址由每个
容器自动注册，横向扩容时不会把多个实例折叠成同一地址。任务使用 `FAILOVER + DISCARD_LATER`，不使用广播：
一次 Handler 已经扫描全部租户，而补偿扫描天然幂等；上一轮未完成时丢弃后续触发可避免队列持续积压。
批量结果只要有一项失败，Handler 就抛错让 XXL 正确记录失败并执行重试。

| 环境变量 | Docker 默认值 | 用途 |
| --- | --- | --- |
| `DROOLS_LIFECYCLE_SCHEDULE_MODE` | `xxl` | `local` / `xxl` / `off` 三选一 |
| `DROOLS_LIFECYCLE_SCHEDULE_BATCH_SIZE` | `200` | 单租户单轮最多处理的活动数 |
| `DROOLS_XXL_ACCESS_TOKEN` | `local-xxl-token-change-me` | Admin 与执行器双向校验 Token，正式环境必须覆盖 |

XXL-JOB 还可触发 `grantOutboxRelaySweep`。只有同时设置 `ACTIVITY_GRANT_OUTBOX_ENABLED=true` 与
`ACTIVITY_GRANT_OUTBOX_RELAY_MODE=xxl` 时该 Handler 才实际投递；生命周期与 grant relay 共用执行器，
但业务状态分别存放在 activity 表与 outbox 表，XXL 库不是真相源。

### 发放对账、grant outbox 与权益中台迁移

生产升级不能只依赖 `ddl-auto:update`。console 首次以新版本启动前，按依赖顺序执行：

1. `deploy/mysql-grant-recon-onboarding.sql`：币种、grant_no、不可变 `activity_grant_entry` 与
   `recon_src_marketing` 视图；
2. `deploy/mysql-grant-outbox-propagation.sql`：`activity_grant_outbox`、唯一键、退避/死信字段；
3. `deploy/mysql-benefit-center-connector.sql`：版本化 `activity_award_binding` 与
   `activity_award_intent_outbox`。

脚本要先在备份或测试库演练。console 仍是唯一应用侧 DDL 执行者，decision 必须等 schema 完整后才以
`ddl-auto=validate` 启动。`recon_src_marketing` 来自追加式分录表，HELD 不产生分录，已确认后的 release 保留
ISSUE 与 REVERSAL 两行供红蓝字勾兑。

Compose 当前不替你启用外部 webhook，也不内置 benefit-center 服务。需要在 console 环境显式注入：

| 环境变量 | 应用默认值 | 用途 |
|---|---:|---|
| `ACTIVITY_GRANT_OUTBOX_ENABLED` | `false` | 同时门控 grant 事件入队与 relay |
| `ACTIVITY_GRANT_OUTBOX_RELAY_MODE` | `local` | `local` / `xxl` / `off` |
| `ACTIVITY_GRANT_OUTBOX_WEBHOOK_URL` | 空 | 空时只写日志；非空才 HTTP POST |
| `ACTIVITY_GRANT_OUTBOX_WEBHOOK_HEADER_NAME/VALUE` | 空 | 可选下游鉴权头，值用 secret 注入 |
| `ACTIVITY_AWARD_INTENT_RELAY_ENABLED` | `false` | CENTER AwardIntent relay 总开关 |
| `BENEFIT_CENTER_URL` | `http://localhost:8083` | benefit-center 根地址 |
| `BENEFIT_CENTER_BEARER_TOKEN` | 空 | 生产 secret，不得提交到仓库 |
| `ACTIVITY_AWARD_INTENT_LEASE_MS` | `30000` | 多实例 relay 租约；应大于 HTTP 最坏调用时长 |

两条 relay 都是 at-least-once。grant 下游按 `(grant_no,event_type)` 幂等；benefit-center 按
`sourceRequestId` / `Idempotency-Key` 幂等。先以 logging/SHADOW 验证，再逐租户开启真实发送。

decision 的 `GenerationPollScheduler` 仍保留进程内 Spring 调度：它预热的是每个 decision 实例自己的本地
快照，改成只路由到一个执行器会漏掉其它实例；除非未来明确使用分片广播，否则不要迁移这项任务。

> ⚠️ **改了代码要重建对的镜像，重建错的那个是彻底的无事发生**（与 `docs/qa/QA_PROFILE.md` 同源，两边要一致）：
>
> - 前端 `/ui/` 由 **gateway 镜像**托管、不在 console 的 JAR 里 → 改前端只 `--build console` 页面纹丝不动，要 `--build gateway`（或 `./deploy.sh --frontend-only`）。`deploy/nginx.conf` 也是 COPY 进这个镜像的，同理。
> - **decision 是独立镜像** → 改后端要 `--build console decision`。只重建 console 时 decision 仍跑旧代码，而**控制台优惠验证页默认打的就是 decision**，于是新加的诊断端点（如 `GET /api/decision/snapshot`）会 404、页面判成「决策服务不可达」——症状看起来像网关坏了，实际是你没重建那个进程。

## Casdoor 认证档（默认）

Compose 对 console 与 decision 同时启用 JWT resource server；控制台路径 `/activity-marketing/**` 和决策路径 `/decision/v1/**` 都会验签，并从已验证 token 的 `aud` 解析 tenant。公开 UI、health 与 `GET /activity-marketing/auth-config` 不要求登录。

| 环境变量 | 本地默认值 | 用途 |
| --- | --- | --- |
| `DROOLS_AUTH_ENABLED` | `true` | 同时启停两个后端的 auth |
| `DROOLS_DEV_DEFAULT_ENABLED` | `false` | auth 档必须保持 false |
| `DROOLS_CASDOOR_ISSUER` | `http://localhost:8000` | JWT `iss` 与浏览器可见 issuer |
| `DROOLS_CASDOOR_JWK_SET_URI` | `http://host.docker.internal:8000/.well-known/jwks` | 容器访问 Casdoor JWKS |
| `DROOLS_CASDOOR_AUTHORIZE_ENDPOINT` | `http://localhost:8000/login/oauth/authorize` | 浏览器 authorize 地址 |
| `DROOLS_CASDOOR_TOKEN_ENDPOINT` | `http://localhost:8000/api/login/oauth/access_token` | PKCE 换 token 地址 |
| `DROOLS_UI_PORT` | `8095` | gateway 宿主端口；中央端口注册存在时由 `deploy.sh` 加载 |
| `DROOLS_REDIRECT_URI` | `http://localhost:${DROOLS_UI_PORT}/ui/auth/callback` | 须精确登记在 Casdoor SPA client |

生产环境应把浏览器可见的 issuer/authorize/token/redirect 全部覆盖为同一套 HTTPS 公网域名；JWK URI 可以使用容器内部可达地址，但 issuer 校验值必须与 token 中的 `iss` 完全一致。

> **`console-write-authority` 同时覆盖三类高风险动作**：活动写端点 `create` / `*/status` /
> `bulk-status` / `*/claim` / `*/confirm` / `*/release`，权益中台触发入口 `/activity-awards/v1/intents`，
> 以及 `/decision/v1/snapshot/rollback`。confirm 会落金额和账，AwardIntent 可能触发真实发放，rollback 会
> 改变决策物料；配置文件中的空值仅供本地开发。新增写端点时必须同步安全 matcher 与集成测试。

最小验收：

```bash
curl http://localhost:8095/activity-marketing/auth-config  # 200, authEnabled=true，无 secret
curl -i http://localhost:8095/activity-marketing/list      # 401
curl -i -X POST http://localhost:8095/api/decision/spu-discount \
  -H 'Content-Type: application/json' \
  -d '{"spuIdList":[9001],"userId":1,"userTags":[],"orderAmount":200,"quantity":1}' # 401
BASE=http://localhost:8095 npm --prefix frontend run e2e:oidc
```

本地紧急回滚到 header-only 档（不改镜像、不迁移数据）：

```bash
DROOLS_AUTH_ENABLED=false DROOLS_DEV_DEFAULT_ENABLED=true ./deploy.sh
```

> **回滚到 header 档时注意**：`X-Tenant-Id` 到 2026-08 才在**决策平面**真正生效。`TenantContextFilter` 的 URL 模式原先只有 `/activity-marketing/*`，M1.1 新增 `/decision/v1/*` 时没同步扩——header 档下决策请求会静默忽略 `X-Tenant-Id`，全部落到 dev-default / `NO_TENANT` 兜底（表现为「A 租户查到 dev-default 的活动」）。现已扩为两平面，回归由 `DecisionTenantHeaderTest` 钉死（无 header 且关掉 dev-default 时必须 403）。auth 档一直不受影响（`JwtTenantFilter` 挂在同时匹配两平面的安全链上）。

## 网关前缀分流（`deploy/nginx.conf`）

| 网关路径 | 转发到 | 备注 |
| ---- | ---- | ---- |
| `/api/decision/*` | `decision:8080/decision/v1/*` | 决策热路径。rewrite 是 `(.*)` 通配且保留 query string，故决策平面新增端点（`addon/options`、`addon/quote?activityId=&item=`、`GET metrics`、`GET by-activity`、`GET snapshot?activityId=`、`POST snapshot/rollback?bizLine=`）**无需改网关**即可用。⚠ 最后那条是**这个前缀后面唯一的写动作**（切内存快照指针，立刻改变这条业务线每次决策实际发出的钱），auth 档下与 `create`/`status`/`claim`/`release` 共用 `console-write-authority`，见「Casdoor 认证档」一节。2026-08 起它从「为将来预留的路由」变成**页面硬依赖**：控制台优惠验证页默认打决策平面，前端 `apiClient` 的 `decision` service base 就写死成 `/api/decision`（`frontend/src/shared/apiClient.ts`）。**nginx 里没有 `location /decision/`**，所以谁把 base 改成后端真实路径 `/decision/v1` 都会落到兜底 `location /` 打到 console——而 console 的 classpath 上根本没有 `DecisionPlaneController`（它在 activity-decision 模块），结果是干净的 404，不是报错 |
| `/api/console/*` | `console:8080/activity-marketing/*` | 控制台写面/查询。**至今没有调用方**——前端一直直连 `/activity-marketing/*`（`apiClient` 的 `marketing` base），走的是下面那条兜底路由。这条留着是给「console 也搬到网关前缀后面」用的，改它不会影响现有页面，也别以为改它就能改到前端实际走的那条 |
| `/`（其余） | `console:8080` | SPA `/ui/`、Step 1–24、静态落地页 |

网关**不在网关终结鉴权**（各服务自证 JWKS），只做 header 透传。三条路由都显式 `proxy_set_header` 了 `Authorization` 与 `X-Tenant-Id`，`/api/console/` 与兜底 `/` 另外显式带上 `X-Actor`：

| 路由 | `Authorization` | `X-Tenant-Id` | `X-Actor` |
| ---- | :--: | :--: | :--: |
| `/api/decision/` | ✅ 显式 | ✅ 显式 | ✅ **隐式**（nginx 默认透传，未显式列出） |
| `/api/console/` | ✅ 显式 | ✅ 显式 | ✅ 显式 |
| `/`（其余，含原始 `/activity-marketing/**`） | ✅ 显式 | ✅ 显式 | ✅ 显式 |

> **订正（2026-08）**：本文此前写「`/api/decision/` **不透传** `X-Actor`，排查 decision 拿不到操作者时这是原因不是 bug」——**那是错的，照它排查会白跑一圈**。`deploy/nginx.conf` 全文没有 `proxy_pass_request_headers off`，nginx 默认把客户端请求头**原样转发**；`proxy_set_header` 是覆盖/新增，**不是白名单**。所以 `/api/decision/`（`nginx.conf:87-93`）少写一行 `X-Actor` 并不构成阻断，decision **收得到**这个 header——只是**没人读**：`ActorContext` 由公共的 `TenantContextFilter`/`JwtTenantFilter` 落上下文（两个 app 都有），但 `ActorContext.get()` 的调用点只在 console 写平面的四眼校验里（`ActivityMarketingService.java:318,599`）。真要在网关层拦掉，得显式 `proxy_set_header X-Actor "";` 而不是「不写就等于不传」。

### 静态资源与压缩（2026-08 视觉换代随带）

| 配置 | 值 | 为什么 |
| ---- | ---- | ---- |
| `gzip on` + `gzip_types` | `text/css` / `application/javascript` / `application/json` / `image/svg+xml`；`gzip_min_length 1024`、`gzip_vary on` | 此前 `nginx.conf` 全文无 gzip 指令，CSS 是裸传的。实测入口 `index-*.css` 裸传 29,696B，带 `Accept-Encoding: gzip` 请求即返回 `Content-Encoding: gzip` + `Vary: Accept-Encoding` |
| woff2 **不**列入 `gzip_types` | — | woff2 本身已压缩，再 gzip 只烧 CPU 不省字节。实测 `/ui/assets/*.woff2` 响应无 `Content-Encoding`，`Content-Type: font/woff2` |
| `/ui/assets/` | `Cache-Control: public, max-age=31536000, immutable` | Vite 内容哈希文件名。**自托管字体也落在这条规则下**（`frontend/src/assets/fonts/*.woff2` → `/ui/assets/inter-latin-var-*.woff2` / `jetbrains-mono-latin-var-*.woff2`），前端不连任何外部字体 CDN |

`deploy/nginx.conf` 是 **COPY 进 gateway 镜像**的（`deploy/Dockerfile.frontend`），改它必须重建 gateway：`./deploy.sh --frontend-only`（只重建并发布 Vue + nginx，不重启后端与基础设施）。只重建 console 不会生效。

## 单库双账号（`deploy/mysql-init/01-decision-readonly-user.sql`）

- **console** 用 `root`：读写 + **独占 DDL**（`ddl-auto=update` 建表）。
- **decision** 用 `decision_ro`：`GRANT SELECT ON activity_platform.*`，应用层再叠 `ddl-auto=validate` 双保险。
- 物理只读实证：`decision_ro` `SELECT` 成功，`CREATE TABLE` / `INSERT` 均被 MySQL 拒（`ERROR 1142 command denied`）——比只靠应用层 `validate` 更硬。
- 时序：`mysql-init` 首启建只读账号 → console 建表（healthy）→ decision 才 `validate` 通过（故 decision `depends_on: console service_healthy`，`service_started` 不够会撞 `missing table`）。
- **应用侧 2026-08 才补齐**：decision 的 `application.yml` 此前遗留 `ddl-auto: update`（注释写着要改 validate、值没改），只靠 compose 的 `SPRING_JPA_HIBERNATE_DDL_AUTO=validate` 盖住——这条边界当时**只由部署编排保证、应用自身不保证**，按文档化的本地 `./mvnw -pl activity-decision spring-boot:run` 起就带着 DDL 权限跑。现已改 `validate`，并由 `DecisionDdlGuardTest` 钉死（直接读源文件而非 Spring 环境：环境值会被 profile / 环境变量覆盖，那测的是「本机这次怎么跑」，要钉的是「仓库里写的是什么」）。
- **只读边界 2026-08 再加一层：类型级（编译期）**。决策取数层 `DecisionDataLoader` 与快照构建器 `DecisionSnapshotBuilder` 现在注入的是六个 `*ReadRepository`（Manage / Rule / Condition / Gift / SpuBinding / Strategy），它们继承 `Repository<T, ID>` 而**不是** `JpaRepository`——`save` / `delete` / `flush` **在类型上根本不存在**。此前「decision 写不了库」全部押在本节这两道**运行期**保证上（只读账号 + `ddl-auto=validate`），也就是说读路径上手滑一次 `save(...)` 能编译通过、能跑绿**全部单测**（测试库是可写的 H2），只在生产的只读连接上炸。同批还把 `ActivityPoolMatchService`（含三处 `bindingRepo.save`）从 `activity-common` 上浮到 `activity-console`：它是不折不扣的写路径，留在共享库里意味着这个 `@Service` 也会在只读的 decision 进程里被实例化。`@TenantId` 判别式过滤由 Hibernate 在 SQL 层加，与仓库接口继承谁无关（`DecisionTenantHeaderTest` 守这条）。
- **生产 MySQL 的排序规则会悄悄放宽快照桶归属——判据不能下推给 SQL**：MySQL 8 默认 `utf8mb4_0900_ai_ci` 是**大小写 + 重音不敏感**的（5.7 的 `general_ci` 还额外忽略尾随空格），所以 `where biz_line = 'retail'` 会把 `Retail` / `RETAIL` 的在线活动一并收进 `retail` 桶——**这改变的是「谁在快照里 = 谁能被发钱」**，且不报错、不回退。2026-08 把 bizLine 过滤下推到 SQL（消 N+1）时因此**保留了 Java 侧 `equals` 精确比对**，看着冗余，删不得：SQL 下推只负责省传输量，判据仍在 Java。全部快照测试跑在 H2 上（字符串比较默认大小写敏感），这个差异在既有测试里**永远照不出来**，故由 `SnapshotBizLineCollationTest` 用 `IGNORECASE=TRUE` 的 H2 JDBC URL 复现生产排序规则钉住——那条 URL 本身是断言的一部分，改它等于关掉这道门禁。
- **加列同样受这条启动顺序约束**：本轮新增列 `activity_rule.red_package_max_discount`（折扣型封顶减免）由 console 的 `ddl-auto=update` 建；老库升级时 decision 在该列建好前 `validate` 会失败。现有的 `depends_on: console service_healthy` 已覆盖这种「老库加新列」的冷启，无需改只读授权（`GRANT SELECT ON activity_platform.*` 是库级通配，新表新列自动可读）。
- **加表也一样，而且更容易被漏判**：本轮新增的是**整张表** `activity_grant`（claim 幂等的发放流水，`ActivityGrantEntity`）。它虽然只被 console 的写平面用到，但 `@Entity` 定义在 `activity-common`，而 decision 的 `@EntityScan("com.lrj.drools")`（`DecisionApplication.java:21`）会把它一起扫进来 —— 于是 decision 的 `validate` **同样要求这张表已存在**。老库不经 console 直接拉起 decision 会报 `Schema-validation: missing table [activity_grant]`，这不是只读授权不够（`GRANT SELECT` 是库级通配），是建表还没发生；靠的仍是 `depends_on: console service_healthy` 这条时序。别因为「decision 又不发券」就以为它不关心这张表。

## 观测（Prometheus + Grafana）

- 两 app 各自暴露 `/actuator/prometheus`（micrometer）；Prometheus 抓两个 target（job `activity-console` / `activity-decision`）。
- Grafana 面板 **Activity Services · console / decision**：HTTP 速率/时延、JVM heap、NonHeap(Metaspace = KieBase 缓存足迹)、CPU、线程——按 `application` tag 区分 `activity-console` / `activity-decision`。数据源 + 面板由 `deploy/grafana/provisioning` 自动装配。
- **决策链路自有指标**（2026-08 新增，`activity-common` 的 `DecisionMetrics`）：埋点在共享的 `ActivityQueryService` 上，故 console（legacy 读端点 / 试算）与 decision（热路径）**会出现同名序列**（谁被调用谁有量），靠 `job` / `application` tag 区分。`source` / `duration` / `candidates` / `hit` / `amount` / `reject` 这几条的 `scene` 取值现在只出自 `DecisionScene` 枚举（通道 `spu-discount` / `gifts` / `addon`，外加**阶段**常量 `benefit`），`reason` 只出自 `RejectReason` 枚举——都是编译期封闭集合，标签基数不可能被调用方撑爆。⚠ **`activity_decision_fallback_total` 与 `activity_rule_fire_ceiling_total` 不在这次收敛里**：`DecisionMetrics` 仍保留 `fallback(String, String)`（其 javadoc 自标 TODO(R4·契约变更，独立提交)）与 `fireCeiling(String)` 两个裸 String 重载，生产上由 `ActivityRuleRuntimeService:230/254` 传 `RuleScene.name()`——`safeRun` 是买赠 DRL 的唯一执行路径，所以这两条序列上还会出现 `ELIGIBILITY` / `LADDER` / `GIFT` 这套与 `DecisionScene` 对不上的词汇，拿 `scene` 把它们跟上面几条 join 会落空。这次收敛顺带**改掉了 `activity_decision_source_total` 的 scene 取值**（见该行，是一次有意的契约变更）：

| Prometheus 序列 | 标签 | 用途 |
| --- | --- | --- |
| `activity_decision_duration_seconds_*` | `scene`,`mode` | 决策耗时，`mode` 分 `rule-engine` / `legacy`（与响应体 `mode` 同源） |
| `activity_decision_fallback_total` | `scene`,`reason` | **回退率——头号告警项**（回退会静默改发放金额）。建议告警：`rate(activity_decision_fallback_total[5m]) / rate(activity_decision_duration_seconds_count[5m]) > 0.001` |
| `activity_decision_source_total` | `scene`,`source` | 物料来源 `snapshot`（代际快照，零查询）/ `db`（逐请求查库）；snapshot 占比掉下来即发布传播断了。⚠ **2026-08 起 `scene` 取值变了**：此前这一条（且只有这一条）用的是 `ActivityType.name()` —— `RED_PACKAGE` / `BUY_AND_GET` / `ADD_ON_PURCHASE`，与其余九条的 `DecisionScene` 词汇表对不上，后果是 `activity_decision_source_total{scene="gifts"}` **恒为空**，而「按 scene 把回退率与来源占比 join 起来看」正是这条指标存在的理由——它此前一 join 就空，且空得毫无提示。现统一为 `spu-discount` / `gifts` / `addon`。**`deploy/` 下已核对没有消费者需要同步改**：`deploy/grafana/dashboards/activity-services.json` 只查 JVM 与 HTTP 指标、不碰 `activity_decision_*`，`deploy/prometheus/prometheus.yml` 没有 `rule_files` 因此没有告警规则引用它。要改的只有**个人保存的临时查询/看板**：旧的三条时间序列停止增长、三条新序列开始增长，历史数据仍在旧标签下可查 |
| `activity_decision_candidates_*` | `scene` | 候选活动数分布（折扣合并是 O(N²)，N 要盯） |
| `activity_decision_hit_total` | `scene`,`activityId` | 按活动的命中量；**`activityId` 基数上限 200**，超出一律并进 `__over_cap__`（总量仍准确，只是分不出是哪几个活动——活动数是运营行为，不设上限会把 Prometheus 序列顶爆）。⚠ 这 200 是**跨 scene 共享的一份预算**，而加价购（`scene="addon"`，`AddOnPurchaseService:185`）2026-08 才补上埋点、此前一个标签位都不占——现在它与红包/买赠抢同一份额度，「按活动看命中量」的分辨率会在活动目录变大时**比以前更早**塌进 `__over_cap__`。活动数量级接近 200 时要重新评估这个上限 |
| `activity_decision_amount_yuan_*` | `scene`,`activityId` | **实际发出的减免金额**分布（`BenefitEvaluator` 出口，`ActivityQueryService:99`；**只有红包通道打这条**，买赠/加价购不发现金减免，故实际只有 `scene="spu-discount"` 一格有量）。补它之前，把「满 300 减 50」配成「满 3 减 50」在监控上是**全盘绿灯**：不走回退、耗时正常、命中数只是稍高——因为金额从来没被记过。有了它「客单减免均值突然翻倍」才是个可查询的问题。`activityId` 复用同一套 200 上限。⚠ 代码里带 `baseUnit("yuan")`，Prometheus 命名约定会把它接进名字，**按 `activity_decision_amount` 是搜不到的** |
| `activity_decision_clamped_total` | 无标签 | 减免额超过订单金额被截断的次数（`BenefitEvaluator:473`）。**正常业务恒 0**，能触发的配置几乎一定是错的（门槛写反 / 面额多一个零 / 叠加没上限），所以阈值可以设得极激进：`increase(activity_decision_clamped_total[1h]) > 0` 就该看一眼。刻意不打 `activityId`——是哪个活动在 WARN 日志里（含金额、订单金额、策略），指标只回答「有没有发生」 |
| `activity_decision_reject_total` | `scene`,`reason` | 候选被淘汰的次数，**「配了但不发」的唯一信号**（此前只写在候选的 `rejectReason` 上，而热路径是 `DecisionMode.HOT_PATH`——本轮把裸 boolean `explain` 换成了枚举——那个出口在生产上根本不打开）。它与回退率同级：一个回答「算错了吗」，一个回答「为什么没发」。`reason` 取值以 `RejectReason` 枚举为准（`activity-common` 的 `domain/RejectReason.java`，指标码与给人看的中文文案钉在**同一行**）——**本文不再复述这份清单**：它此前是「指标语句 + rejectReason 文案」两条独立语句手工配对，抄进文档/javadoc 后已经实证漂移过一次（写 `price-above-order`、代码实际发 `price-above-base`）。⚠ **`scene` 这一列在本序列上仍混着两种语义**：资格阶段（`DecisionEligibilityService:116/133/142`，reason `condition-unavailable`/`ineligible`）打的是**通道** `spu-discount`/`gifts`/`addon`，算额阶段（`BenefitEvaluator:283`）打的是**阶段**常量 `benefit`（即 `DecisionScene.BENEFIT`，**刻意保持原值**：换成真实通道会改变已有时间序列，属要与面板同批做的契约变更），三条通道的算额淘汰全并在这一格。所以拿 `scene` 跟别的序列 join、或按 `scene="gifts"` 统计「买赠一共淘汰了多少」都是错的——那样会漏掉全部算额淘汰，而 `benefit` 那一行又分不出是哪个通道 |
| `activity_decision_snapshot_count` | 无标签 | Gauge：本进程持有的快照桶数。**0 = 这台机器上全部决策都在走库**。只有 decision 侧非 0（console 没有快照构建的调用方，store 恒空） |
| `activity_decision_snapshot_age_seconds` | 无标签 | Gauge：最旧快照的年龄，**`-1` = 一个快照都没有**。**下线传播断掉时唯一会动的读数**——快照陈旧是「决策照常成功、只是按旧配置发钱」，回退率/耗时/命中数三条全看不出来。告警阈值取轮询间隔（默认 3s）与兜底重建阈值（默认 60s）的数倍，比如 `> 300` |
| `activity_decision_snapshot_orphan_total` | 无标签 | **2026-08 新增**。每次快照构建时数一遍「`bizLine` 为空（null 或全空白）的**已上线**活动」个数并 `increment(n)`（按 activityId 去重）。它抓的是本仓库唯一一种「provenance 三个值全绿、活动就是不在快照里」的故障：快照按 bizLine 精确匹配收活动，这类活动进不了**任何**桶，而决策照常命中别的活动、代际正常、快照也很新——回退率/耗时/命中数全看不出来。在此之前只能靠 `GET /api/decision/snapshot?activityId=` 一个一个照，前提是你已经怀疑到某个具体活动头上。⚠ **绝对值没有意义**（每轮构建都按当时库存量累加），能用的是 `rate(activity_decision_snapshot_orphan_total[10m]) > 0`：只要还有这种活动它就一直涨，数据补干净后立刻停。刻意不打 tenant/bizLine 标签（同一笔基数账），要定位到具体活动看构建期那条 `[snapshot] 有 N 个已上线活动的 bizLine 为空` WARN 日志 |
| `activity_rule_compile_seconds_*` / `activity_rule_fire_ceiling_total` | `outcome` / `scene` | KieBase 编译耗时与 fire 触顶（runaway 护栏被触发） |
| `activity_rule_cache_entries` / `_hit_ratio` / `_weight_kb` | — | KieBase 缓存条目数 / 命中率 / 足迹（Caffeine stats 绑成 Gauge） |

- **进程内聚合端点**（给控制台指标卡用）：`GET /api/decision/metrics`（按 `scene/mode` 的 count/mean/max + 回退计数）与 `GET /api/decision/by-activity`（`hits` 命中量 + `amounts` **按活动累计发出的减免金额** + `tagCap` + `overCapTag`）。刻意不让浏览器直连 Prometheus：编排里 `:9090` 不对外、生产更不会，且把 PromQL 拼在前端等于让监控查询语言变成前端契约。**两个端点都是单实例视角**（读本进程 MeterRegistry），多实例部署下只反映被路由到的那个实例——两者的响应都自带 `scope: "single-instance"` 这句自述（`by-activity` 也补上了），因为少了它的读数最容易被当成全局真相；跨实例汇总仍看 Prometheus；auth 档下同样需带 Bearer。
- **只读诊断端点**：`GET /api/decision/snapshot[?activityId=]` 返回**本租户**的快照桶清单（`bizLine` / `generation` / `builtAt` / `ageSeconds` / `activityCount`），带 `activityId` 时直接回答「在哪个桶 / 不在任何桶」。它存在的理由是决策响应里的 `provenance`（source/generation/buckets）在最要命的那条故障上**三个值全绿**：活动的 `bizLine` 为空时进不了任何桶（构建期按 bizLine 精确匹配），而兜底重建只遍历**已存在**的桶、永远建不出不存在的那个——此时决策照常走快照、代际是别的业务线的正常数、快照也很新，只是这个活动根本不在里面，与「活动确实不该命中」完全同形。它**不发起决策**，不会把验证流量混进 `activity_decision_{hit,amount}`，也不消耗那 200 个 `activityId` 标签位。⚠ 它的 `ageSeconds` 与 `activity_decision_snapshot_age_seconds` **不是同一个数**：前者是本租户的桶，后者是 `DecisionSnapshotStore.oldestAgeSeconds` 的跨租户统计（调度线程与指标线程没有租户上下文）。多租户下两者永远对不上，别拿来互相印证。
- **代际参照物**：`GET /activity-marketing/generation?bizLine=`（console 侧，读 `activity_generation` 表；行不存在返回 0）。只看决策回显的 `provenance.generation=7` 是判断不了「我刚发布那次进去了没有」的，得跟库里当前代际比。放在 console 是因为那张表由写平面维护。

## 容灾行为（kill-gate，已 live 实证）

下表在 auth 档下调用 decision 时需携带有效 Bearer；匿名请求固定为 401，不能用于判断服务存活。

| 场景 | decision `/api/decision/*` | console `/hello` 等 | 结论 |
| ---- | :--: | :--: | ---- |
| 两服务都在 | 200 | 200 | 正常 |
| `stop console` | **200** | 504 | 决策独立存活（发布传播靠代际轮询，非进程内直调） |
| `stop decision` | 502 | **200** | console + Step 1–24 独立存活 |

## 发布代际轮询预热（M1.4，跨进程 warm）

console 还运行活动生命周期编排：`activity.marketing.lifecycle-schedule.mode=local` 时由 Spring 按
`interval-ms`（默认 5000ms）触发，`mode=xxl` 时由 `activityLifecycleSweep` Handler 触发；二者都跨租户扫描
PENDING_EFFECT 到期上线和 ONLINE 过期下线，`batch-size` 控制单租户单轮上限。它只运行在有写权限的
console，decision 仍保持只读。
每次真实状态变化都会在同事务内推进发布代际，所以 decision 在下一轮代际轮询后切到新快照。
多实例 console 可同时开启：同一活动的所有版本在转换事务中使用悲观写锁，重复执行幂等。

console **任何活动状态变化**（上线 / 下线 / 回待上线）都在同一个事务里 bump `(tenant,bizLine)` 发布代际（`activity_generation` 表，**非 `@TenantId`**——跨租户信号，供无上下文的后台 poller `findAll` 扫）；decision 后台按 `activity.marketing.generation-poll.interval-ms`（默认 3000ms）轮询，见代际增长即预热该 `(tenant,bizLine)` 的全部 ACTIVE artifact。物理拆分后进程内直调已移除，发布预热唯一路径即此轮询。

> **「只在上线时 bump」是本链路唯一「错误无法被终止」的缺陷**（2026-08 已修，`ArtifactService.onStatusChanged`，原名 `onPublish`）：运营点下线 → 列表变「已下线」→ 控制台试算也说不再命中（console 侧 store 恒空、必走库，看到的是 DB 真相）→ 而 decision 的快照收不到信号，**继续按原配置发钱**，直到同 bizLine 恰好有别的活动上线、或 decision 进程重启。止损开关和用来确认止损的仪表盘会一起骗人。同理去掉了原来「artifact 处于 NEEDS_REBUILD 就跳过 bump」的早退：那道守卫拦错了东西（代际驱动的是按数据库真相重建快照，与 artifact 的 DRL 无关），而在上线路径上它有害——发布 v2 会同事务退役 v1，跳过 bump 等于让 decision 永远服务已被退役的 v1。<br>**仍有一个口子**：`activity_manage.biz_line` 可空而 `activity_generation.biz_line` 是 NOT NULL，所以 bizLine 为空时**不 bump**（只打 WARN）——照样插会在同事务抛非空约束违例、把状态变更一起回滚，那是把「下线传播不出去」升级成「下线根本做不到」。这类活动同时也进不了任何快照桶，排查手段是 `GET /api/decision/snapshot?activityId=`。

2026-08（P1-1 快照包）起，代际推进后 poller 做**三件事，且切指针排在最后**：① 在后台线程把整条 `(tenant,bizLine)` 的决策物料捞齐、构建不可变 `DecisionSnapshot`；② 预热 ACTIVE artifact 的资格 DRL；③ 前两步都成功了才 `publish` 原子切指针。命中快照的决策请求零数据库查询。

> **为什么 `publish` 必须排最后**（本轮重构调整）：此前顺序是「先 publish → 再查 ACTIVE artifact → 再预热」，而预热阶段抛异常会被 poller 吞掉并**不更新 `lastSeen`**——于是一次半完成的推进已经被记成一次发布（占掉了回滚槽位），下一轮还会对同一代际再来一遍。挪到最后之后三步共享同一个失败边界：要么整体推进，要么整体留在上一代等下轮重试。「先建好再切，请求线程永远读到自洽物料」这条更早的约束当然仍成立。<br>配套地，`DecisionSnapshotStore` 的一个桶现在是一个不可变 `SnapshotSlot(current, previous)`、靠一次 `compute` 整体替换（此前 current / previous 是两张 map、两条独立语句，中间存在互相矛盾的窗口），并且**只有代际前进时才移交回滚槽位**——同代重发（就是上面那种重试）只替换 current，否则 previous 会被挤成「同一代的旧副本」，回滚从此是空转。

**快照只在进程内存里，没有新表**——decision 重启后要等下一次轮询（≤ 轮询间隔）才重建，这段时间以及从未 bump 过代际的租户走逐请求查库路径；两条路径占比由 `activity_decision_source_total{source="snapshot"|"db"}` 直接可观测（⚠ 这条序列的 `scene` 标签值本轮改过，见「观测」一节）。

轮询每一轮在预热之后还做一件事：**陈旧快照兜底重建**（`GenerationWarmService.rebuildStaleSnapshots`）。凡是年龄超过 `activity.marketing.snapshot.max-age-ms`（默认 **60000**）的桶，哪怕代际没动也按数据库真相重建一遍。它守的不是某个已知 bug，而是「信号漏发」这一整类故障——代际 bump 因异常没提交、轮询线程被拖死后恢复、构建期抛异常导致 `lastSeen` 没更新，三种成因都表现为快照静默过期而**决策照常成功**；有了兜底，后果从**永久**降为**一轮**（本仓库已经在「记得发信号」这条纪律上失手过一次，就是上面那条下线不 bump）。走 `DecisionSnapshotStore.refresh` 而**不是** `publish`：这不是一次发布，不能占用回滚槽位，否则 `rollback` 会退到几十秒前的自己，等于没回滚；代际号也不变。重建失败保留旧快照、下轮再试，`activity_decision_snapshot_age_seconds` 会持续上涨并触发告警。

> **这条链路上的读库压力已收敛成常数**（本轮重构）：一次快照构建固定 **6 次查询**（活动 / 规则 / 赠品 / 条件 / 绑定 / 合并策略），真实桶另加一次孤儿 bizLine 计数 = **7 次**，与活动目录规模无关。此前活动查询捞该租户**全部**在线活动再用 Java 丢掉非本桶的、绑定查询则在 `for (活动)` 循环体里逐个发（N+1）——而兜底重建每 ≤60s 就把它整体重跑一遍，这批读**全打在 decision 的只读账号连接上**。热路径早有 `DecisionQueryCountTest` 钉死 5 次，构建期此前一道门禁都没有，现由 `SnapshotBuildQueryCountTest` 补上；再往构建期加查询前先确认它不随 N 增长。

> ⚠ `activity.marketing.snapshot.max-age-ms` **两份 `application.yml` 里都没有这个 key**，60000 这个值只存在于 `GenerationWarmService` 的 `@Value("${...:60000}")` 默认值里。想调它得自己往 decision 的配置里加一行（或用 `ACTIVITY_MARKETING_SNAPSHOT_MAX_AGE_MS` 环境变量覆盖）；配 0 或负数是**关闭兜底重建**，只有测试需要精确控制重建时机时才这么干。

### 快照回滚（止损按钮，2026-08 才真正接上）

```bash
curl -i -X POST 'http://localhost:8095/api/decision/snapshot/rollback?bizLine=retail' \
  -H 'X-Tenant-Id: acme'            # auth 档改带 Bearer，且该 token 需有 console-write-authority
```

把本租户这条业务线的决策指针切回**上一个发布代际**，立刻生效。在此之前 `DecisionSnapshotStore.rollback` **没有任何生产调用方**（全仓只有测试调它），也就是说本文档一直承诺的「发布出事时切回上一代即可止损」是一张空头支票：previous 槽位维护得再对，运维也按不下去。现由 `SnapshotRollbackEndpointTest` 钉住。运维必须知道的四条：

- **它是写动作，不是诊断**：切指针会立刻改变这条业务线上每一次决策实际发出去的钱。因此它与 `GET /snapshot` 同挂 `/decision/v1/**`（同一道 `RoleGateFilter` 角色门、同一条验签链），并额外要求 `console-write-authority`。
- **它不写数据库**，只动本进程内存里的指针——所以不违反「decision 连只读账号」这条边界，但也意味着**只影响被打到的那个实例**：多实例部署要逐实例调用。
- **下一次代际推进会把它盖掉**。回滚是止血，真正的修复仍是在 console 侧下线/改配置再发布一代。
- **没有上一代时返回 409**（响应里 `rolledBack:false` + `hint`），而不是假装成功。常见于「刚重启、只发布过一代」，以及「上一次推进是兜底重建（`refresh`，按设计不占回滚槽位）」。

## 上线前待接入项

- decision 独立只读账号已示范；生产按最小权限收紧 + 独立 CI/独立扩缩容。
- 网关/限流/灰度/审核队列等运营面 UI 未做（后端端点未就绪，诚实空态）。决策指标是例外：`GET /api/decision/metrics` · `/by-activity` **后端已就绪**，缺的是工作台那块面板本身——`frontend/src/console/pages/ListView.vue` 的说明卡已如实写成「端点已经存在、缺的是这块面板」，不再把它们记成「待建接口」。接之前要先想清楚：优惠验证页的流量也会打进这两个端点，直接渲染成「这个活动花了多少预算」等于拿自己造的验证流量记账。
- 容量：`-XX:MaxMetaspaceSize` 配合 KieBase 缓存足迹预算，见 `docs/plans/activity-engine-platform-0718/50-P0-5-memory-capacity-model.md`。
