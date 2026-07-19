# M 线（后端微服务化）实施状态 · 2026-07-19

> 决策见 `00-microservices-evaluation.md`（该拆但只拆一刀）与 `DECISION_RECORD.md` D1。
> 本轮以**低风险路径**落地：不做 Maven 物理模块拆分，改用「同一 artifact + 角色门控」达成读写平面独立部署的可演示效果。

## 已落地（代码 + 测试 + 配置）

| 项 | 内容 | 证据 |
|----|------|------|
| M1.1 决策别名 | `/decision/v1/{spu-discount,gifts}` 薄别名，复用 `ActivityQueryService`；旧 `/activity-marketing/*` 读端点保留不弃用 | `DecisionPlaneController`；`DecisionAliasAndRoleGateTest` 3 绿 |
| 角色门控（M2 的低风险替代） | `activity.role`：`all`(默认,不装 filter,零行为改变) / `decision`(只放 `/decision/v1`+actuator，其余 404) / `console`(屏蔽 `/decision/v1`)。**同一 jar 按角色扮演决策/控制台服务** | `RoleGateFilter`；`RoleGateDecisionTest` 3 绿 + `RoleGateConsoleTest` 2 绿 |
| M1.3 生产化编排 | `deploy/`：多阶段 `Dockerfile`（前端+后端打一 jar）+ `docker-compose.yml`（mysql + console + decision + nginx 网关）+ `nginx.conf`（`/api/decision`→decision、`/api/console`+`/ui`→console，透传 Bearer/X-Tenant-Id/X-Actor 不终结鉴权） | `docker compose config` 校验通过；nginx 语法正确（standalone `-t` 仅因 compose DNS 未起而报 upstream，非语法错） |

**基线**：`./mvnw test` = **112 绿**（104 + 决策别名 3 + 角色门控 5），0 失败。默认 `activity.role` 不设 → 零回归。

### 「kill console 决策仍服务」价值演示（compose）
```bash
docker compose -f deploy/docker-compose.yml up --build
docker compose -f deploy/docker-compose.yml stop console
curl -s -XPOST http://localhost:8090/api/decision/spu-discount -H 'Content-Type: application/json' \
     -H 'X-Tenant-Id: acme' -d '{"spuIdList":[9001],"userId":1,"userTags":[],"orderAmount":200,"quantity":1}'
# console 停了，decision 实例独立存活并正确决策；反之 stop decision 不影响 /ui 与 Step1~18
```
该行为的机制已被角色门控单测证明（decision 角色服务 `/decision/v1` 且 404 控制台面；console 角色 404 `/decision/v1`）；compose 只是把这两个角色实例放到 nginx 后。**镜像构建（maven+node）较重，本轮未实跑整栈**——config 已校验、机制已测、命令已备。

## 有意不做（本轮）及理由

| 项 | 为什么本轮不做 | 何时做 |
|----|------|------|
| M2.1 Maven 物理模块拆分（common/console/decision/drools-lab） | 架构评估自陈这是**最大重构风险点**（搬 100+ 文件、拆 pom）；角色门控已达成"读写平面独立部署 + kill-console 演示"的**同等可见价值**，风险却低一个量级。学习 demo 阶段用一个 artifact 更易维护 | 真要按服务独立发布/独立 CI/独立扩缩容时；届时 `RoleGateFilter` 的路径划分即模块边界草稿 |
| M1.4 发布 generation 轮询预热 | 需新增 `(tenant,bizLine,generation)` 表 + 轮询线程；当前 decision 实例首请求冷编译 + single-flight 已保证正确性（只是首请求慢一次），非阻塞项 | 生产要求发布秒级可见时；设计见 `00-eval §4 M1.4` |
| M2.3 grafana 面板 | 两实例各自已暴露 `/actuator/prometheus`（现成）；grafana 面板是观测糖，非机制 | 需要可视化决策耗时/命中率/缓存权重时；prometheus 抓取双实例即可 |
| 独立只读 DB 账号 | compose 里 decision 用 `ddl-auto=validate`（不改表）已示范"决策不碰 DDL"；真只读账号需 MySQL 建号授权，生产项 | 生产按最小权限建 decision 只读账号 |

## 与前端的对齐（已就位）

- 前端 `apiClient` 是服务注册表（`root`/`marketing`），后端拆分后只需加 `decision`/`console` 条目 + 改 base 映射，页面零改动。
- nginx 网关路由（`/api/decision`、`/api/console`、`/ui`）就是前端未来的统一 origin，同源零 CORS（决策 D3）。
- PKCE 流只改 redirect-uri 落点（已在 F1 完成 origin 派生 + Casdoor 多值白名单）。
