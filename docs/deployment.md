# 部署与编排（微服务形态）

> 本仓库 M2.1 起是 Maven 四模块、两个独立 Spring Boot 应用。本文收敛「更像生产」的本地整套编排（`deploy/`）：两 app + nginx 网关 + MySQL 单库双账号 + Prometheus/Grafana。设计缘由见 `docs/plans/prod-arch-refactor-0719-1330/`。

## 模块与应用

| 模块 | 类型 | 端口 | 职责 | 依赖 |
| ---- | ---- | ---- | ---- | ---- |
| `activity-common` | 库 | — | 活动引擎共享内核（domain/engine/persistence/tenant + `ActivityQueryService`）；drools 只引 KieHelper 核心 | — |
| `drools-lab` | 库 | — | Step 1–18 教学 + 重 drools 依赖（kie-ci/kie-dmn/decisiontables/xml-support） | — |
| `activity-console` | 应用 | 8081 | 写平面 + Step 1–18 + 前端 `/ui/` + **唯一 DDL 执行者** | common + drools-lab |
| `activity-decision` | 应用 | 8082 | 只读决策热路径 `/decision/v1/*` + 发布代际轮询预热 | 仅 common（更轻，甩掉 kie-ci/dmn） |

两 app 主类 `ConsoleApplication` / `DecisionApplication` 都在根包 `com.lrj.drools`；decision 的 classpath 上无写平面 bean、无 `DroolsConfig`，**结构上就写不了**。可执行 jar：decision ~67MB vs console ~104MB（依赖甩除实证）。

## 本地单机起（不走容器）

```bash
# console（写面 + Step1-18 + 台）；-Pfrontend 顺带把 Vue SPA 构建拷进 static/ui/
./mvnw -pl activity-console -Pfrontend spring-boot:run -Dspring-boot.run.profiles=h2   # http://localhost:8081/ui/
# decision（只读 /decision/v1）
./mvnw -pl activity-decision spring-boot:run -Dspring-boot.run.profiles=h2             # http://localhost:8082/decision/v1/spu-discount
```

h2 profile 用 file 库（console `./data/drools-demo`、decision `./data/decision`）；同一 file 库不能两 app 同开，需要时覆盖内存库（见 `docs/qa/QA_PROFILE.md`）。

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
# 网关：http://localhost:8095/ui/console
```

`--provision-auth` 只用于本机 dev Casdoor：它幂等登记 acme/beta public SPA client、`http://localhost:8095/ui/auth/callback` 与测试用户，不把 client secret 写入浏览器或 Compose。若 Casdoor 资源已存在，可直接运行 `./deploy.sh`。

服务与宿主端口：

| 服务 | 镜像 | 宿主端口 | 说明 |
| ---- | ---- | ---- | ---- |
| `mysql` | mysql:8.0 | 3307→3306 | 单库 `drools_demo`；`mysql-init/` 首启建 decision 只读账号 |
| `console` | activity-console（`--build-arg MODULE=activity-console`） | — | 容器内 `SERVER_PORT=8080`；healthcheck `/actuator/health` |
| `decision` | activity-decision（`--build-arg MODULE=activity-decision`） | — | 容器内 8080；`depends_on: console service_healthy` |
| `gateway` | nginx:1.27-alpine | **8095**→80 | 前缀分流 + SPA 托管（8090/8091 常被本机占，故用 8095） |
| `prometheus` | prom/prometheus | 9090 | 抓 console + decision 的 `/actuator/prometheus` |
| `grafana` | grafana/grafana | 3001 | 自动装配数据源 + 面板（匿名 Viewer） |

**镜像构建**：单个 `deploy/Dockerfile`，一个 build 阶段构建整个 reactor，运行阶段 `ARG MODULE` 选装某 app 的 jar；compose 用不同 `--build-arg MODULE` 出两镜像（build 阶段共享层缓存）。`.dockerignore` 排除 `node_modules`/`target`/`deploy`/`docs` 等，保持上下文精简、避免无谓 rebuild。

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
| `DROOLS_REDIRECT_URI` | `http://localhost:8095/ui/auth/callback` | 须精确登记在 Casdoor SPA client |

生产环境应把浏览器可见的 issuer/authorize/token/redirect 全部覆盖为同一套 HTTPS 公网域名；JWK URI 可以使用容器内部可达地址，但 issuer 校验值必须与 token 中的 `iss` 完全一致。

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
| `/api/decision/*` | `decision:8080/decision/v1/*` | 决策热路径。rewrite 是 `(.*)` 通配且保留 query string，故决策平面新增端点（`addon/options`、`addon/quote?activityId=&item=`、`GET metrics`、`GET by-activity`）**无需改网关**即可用 |
| `/api/console/*` | `console:8080/activity-marketing/*` | 控制台写面/查询 |
| `/`（其余） | `console:8080` | SPA `/ui/`、Step 1–18、静态落地页 |

网关透传 `Authorization`（Bearer）/ `X-Tenant-Id` / `X-Actor`，**不在网关终结鉴权**（各服务自证 JWKS）。

### 静态资源与压缩（2026-08 视觉换代随带）

| 配置 | 值 | 为什么 |
| ---- | ---- | ---- |
| `gzip on` + `gzip_types` | `text/css` / `application/javascript` / `application/json` / `image/svg+xml`；`gzip_min_length 1024`、`gzip_vary on` | 此前 `nginx.conf` 全文无 gzip 指令，CSS 是裸传的。实测入口 `index-*.css` 裸传 29,696B，带 `Accept-Encoding: gzip` 请求即返回 `Content-Encoding: gzip` + `Vary: Accept-Encoding` |
| woff2 **不**列入 `gzip_types` | — | woff2 本身已压缩，再 gzip 只烧 CPU 不省字节。实测 `/ui/assets/*.woff2` 响应无 `Content-Encoding`，`Content-Type: font/woff2` |
| `/ui/assets/` | `Cache-Control: public, max-age=31536000, immutable` | Vite 内容哈希文件名。**自托管字体也落在这条规则下**（`frontend/src/assets/fonts/*.woff2` → `/ui/assets/inter-latin-var-*.woff2` / `jetbrains-mono-latin-var-*.woff2`），前端不连任何外部字体 CDN |

`deploy/nginx.conf` 是 **COPY 进 gateway 镜像**的（`deploy/Dockerfile.frontend`），改它必须重建 gateway：`./deploy.sh --frontend-only`（只重建并发布 Vue + nginx，不重启后端与基础设施）。只重建 console 不会生效。

## 单库双账号（`deploy/mysql-init/01-decision-readonly-user.sql`）

- **console** 用 `root`：读写 + **独占 DDL**（`ddl-auto=update` 建表）。
- **decision** 用 `decision_ro`：`GRANT SELECT ON drools_demo.*`，应用层再叠 `ddl-auto=validate` 双保险。
- 物理只读实证：`decision_ro` `SELECT` 成功，`CREATE TABLE` / `INSERT` 均被 MySQL 拒（`ERROR 1142 command denied`）——比只靠应用层 `validate` 更硬。
- 时序：`mysql-init` 首启建只读账号 → console 建表（healthy）→ decision 才 `validate` 通过（故 decision `depends_on: console service_healthy`，`service_started` 不够会撞 `missing table`）。
- **应用侧 2026-08 才补齐**：decision 的 `application.yml` 此前遗留 `ddl-auto: update`（注释写着要改 validate、值没改），只靠 compose 的 `SPRING_JPA_HIBERNATE_DDL_AUTO=validate` 盖住——这条边界当时**只由部署编排保证、应用自身不保证**，按文档化的本地 `./mvnw -pl activity-decision spring-boot:run` 起就带着 DDL 权限跑。现已改 `validate`，并由 `DecisionDdlGuardTest` 钉死（直接读源文件而非 Spring 环境：环境值会被 profile / 环境变量覆盖，那测的是「本机这次怎么跑」，要钉的是「仓库里写的是什么」）。
- **加列同样受这条启动顺序约束**：本轮新增列 `activity_rule.red_package_max_discount`（折扣型封顶减免）由 console 的 `ddl-auto=update` 建；老库升级时 decision 在该列建好前 `validate` 会失败。现有的 `depends_on: console service_healthy` 已覆盖这种「老库加新列」的冷启，无需改只读授权（`GRANT SELECT ON drools_demo.*` 是库级通配，新表新列自动可读）。

## 观测（Prometheus + Grafana）

- 两 app 各自暴露 `/actuator/prometheus`（micrometer）；Prometheus 抓两个 target（job `activity-console` / `activity-decision`）。
- Grafana 面板 **Activity Services · console / decision**：HTTP 速率/时延、JVM heap、NonHeap(Metaspace = KieBase 缓存足迹)、CPU、线程——按 `application` tag 区分 `drools-demo`(console) / `drools-decision`(decision)。数据源 + 面板由 `deploy/grafana/provisioning` 自动装配。
- **决策链路自有指标**（2026-08 新增，`activity-common` 的 `DecisionMetrics`）：埋点在共享的 `ActivityQueryService` 上，故 console（legacy 读端点 / 试算）与 decision（热路径）**会出现同名序列**（谁被调用谁有量），靠 `job` / `application` tag 区分：

| Prometheus 序列 | 标签 | 用途 |
| --- | --- | --- |
| `activity_decision_duration_seconds_*` | `scene`,`mode` | 决策耗时，`mode` 分 `rule-engine` / `legacy`（与响应体 `mode` 同源） |
| `activity_decision_fallback_total` | `scene`,`reason` | **回退率——头号告警项**（回退会静默改发放金额）。建议告警：`rate(activity_decision_fallback_total[5m]) / rate(activity_decision_duration_seconds_count[5m]) > 0.001` |
| `activity_decision_source_total` | `scene`,`source` | 物料来源 `snapshot`（代际快照，零查询）/ `db`（逐请求查库）；snapshot 占比掉下来即发布传播断了 |
| `activity_decision_candidates_*` | `scene` | 候选活动数分布（折扣合并是 O(N²)，N 要盯） |
| `activity_decision_hit_total` | `scene`,`activityId` | 按活动的命中量；**`activityId` 基数上限 200**，超出一律并进 `__over_cap__`（总量仍准确，只是分不出是哪几个活动——活动数是运营行为，不设上限会把 Prometheus 序列顶爆） |
| `activity_rule_compile_seconds_*` / `activity_rule_fire_ceiling_total` | `outcome` / `scene` | KieBase 编译耗时与 fire 触顶（runaway 护栏被触发） |
| `activity_rule_cache_entries` / `_hit_ratio` / `_weight_kb` | — | KieBase 缓存条目数 / 命中率 / 足迹（Caffeine stats 绑成 Gauge） |

- **进程内聚合端点**（给控制台指标卡用）：`GET /api/decision/metrics`（按 `scene/mode` 的 count/mean/max + 回退计数）与 `GET /api/decision/by-activity`（命中量 + `tagCap` + `overCapTag`）。刻意不让浏览器直连 Prometheus：编排里 `:9090` 不对外、生产更不会，且把 PromQL 拼在前端等于让监控查询语言变成前端契约。**它是单实例视角**（读本进程 MeterRegistry），多实例部署下只反映被路由到的那个实例（只有 `metrics` 的响应自带 `scope: single-instance`，`by-activity` 的响应只有 `hits`/`tagCap`/`overCapTag`，识别不出单实例语义），跨实例汇总仍看 Prometheus；auth 档下同样需带 Bearer。

## 容灾行为（kill-gate，已 live 实证）

下表在 auth 档下调用 decision 时需携带有效 Bearer；匿名请求固定为 401，不能用于判断服务存活。

| 场景 | decision `/api/decision/*` | console `/hello` 等 | 结论 |
| ---- | :--: | :--: | ---- |
| 两服务都在 | 200 | 200 | 正常 |
| `stop console` | **200** | 504 | 决策独立存活（发布传播靠代际轮询，非进程内直调） |
| `stop decision` | 502 | **200** | console + Step 1–18 独立存活 |

## 发布代际轮询预热（M1.4，跨进程 warm）

console 上线活动时 bump `(tenant,bizLine)` 发布代际（`activity_generation` 表，**非 `@TenantId`**——跨租户信号，供无上下文的后台 poller `findAll` 扫）；decision 后台按 `activity.marketing.generation-poll.interval-ms`（默认 3000ms）轮询，见代际增长即预热该 `(tenant,bizLine)` 的全部 ACTIVE artifact。物理拆分后进程内直调已移除，发布预热唯一路径即此轮询。

2026-08（P1-1 快照包）起，代际推进后 poller 做**两件事且顺序不可反**：先在后台线程把整条 `(tenant,bizLine)` 的决策物料捞齐、构建不可变 `DecisionSnapshot` 并原子切指针（先建好再切，请求线程永远读到自洽物料），再预热 ACTIVE artifact 的资格 DRL。命中快照的决策请求零数据库查询。**快照只在进程内存里，没有新表**——decision 重启后要等下一次轮询（≤ 轮询间隔）才重建，这段时间以及从未 bump 过代际的租户走逐请求查库路径；两条路径占比由 `activity_decision_source_total{source="snapshot"|"db"}` 直接可观测。

## 生产化尾项（本 demo 未做）

- decision 独立只读账号已示范；生产按最小权限收紧 + 独立 CI/独立扩缩容。
- 网关/限流/灰度/审核队列等运营面 UI 未做（后端端点未就绪，诚实空态）。决策指标是例外：`GET /api/decision/metrics` · `/by-activity` **后端已就绪**，但前端工作台那块卡仍写着「待建」尚未接上（`frontend/src/console/pages/ListView.vue`）。
- 容量：`-XX:MaxMetaspaceSize` 配合 KieBase 缓存足迹预算，见 `docs/plans/activity-engine-platform-0718/50-P0-5-memory-capacity-model.md`。
