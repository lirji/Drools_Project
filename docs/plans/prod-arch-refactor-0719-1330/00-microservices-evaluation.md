# 服务端微服务拆分评估（Software Architect 咨询结论）

> 2026-07-19 由独立架构代理只读评估产出，主流程校对收录。裁决进 `DECISION_RECORD.md` D1。
> 一句话结论：**该拆，但只拆一刀**——决策面独立成只读服务，控制台 + 18 Step 留在原单体；不做细粒度拆分。

## 1. 现状架构地图

一个进程里的两个世界：

| 世界 | 包 | 性质 |
|---|---|---|
| 教学世界（Step 1–18） | `com.lrj.drools.{controller,service,config,…}`，16 个 controller | 每 Step 一个 REST 入口；走 `DroolsConfig` classpath KieContainer；Step 16 独占重依赖 kie-ci，Step 17 独占 kie-dmn |
| 产品世界（activity SaaS） | `com.lrj.drools.activity.*` | 多租户元数据驱动决策平台；**不走** kmodule，KieHelper 运行时编译；104 测试绿 |

两个世界代码零互相 import，共享：进程 / pom / DB schema / actuator / 静态资源目录。

activity 内部真实接缝（已核实代码）：

- **写平面**（低频事务重）：`ActivityMarketingService`（幂等表+四眼+artifact 冻结）+ `ArtifactService`
- **读平面**（决策热路径，无写）：`ActivityQueryService` → 3 趟引擎 → fail-safe 回退 legacy
- **引擎状态**：`ActivityRuleRuntimeService`——Caffeine 足迹加权缓存（key=tenant+DRL 全文，**内容寻址→天然可多实例**）、预热编译池、per-artifact fire 上界
- **唯一的写→读进程内直连**：`ArtifactService.warmOnPublish → ruleRuntime.warmAsync`（拆分要断开的唯一一条线）
- **安全链已按前缀分面**：`@Order(1)` 只护 `/activity-marketing/**`，Step 1–18 全在 permitAll 链

关键判断：既有「方案 B = 模块化单体带接缝，二期物理拆决策服务」的接缝（artifact 冻结落库、异步预热、内容寻址缓存、fail-safe、租户机制化）**已经全部兑现**——"能不能拆"不是问题，只剩"值不值得、拆到什么粒度"。

## 2. 三个备选

- **A · 单体 + 生产化包装**：不拆进程，只加 nginx 网关 / docker-compose / 独立前端 / 观测面板。
- **B · 最小二分**（推荐）：
  - `activity-decision-svc`(8082)：`/decision/v1/{spu-discount,gifts}` = QueryService + engine/ + tenant/ 验签 + KieBase 缓存整体；依赖集甩掉 kie-ci/dmn/decisiontables；**只读 DB 账号 + ddl-auto=validate**
  - `activity-console-svc`(8081，现单体瘦身)：全部写面 + list/detail/preview/field-dict/auth-config + **Step 1–18 原样保留** + 唯一 DDL 执行者
  - `edge`(nginx, 8090)：托管前端静态产物 + `/api/decision/*`→8082、`/api/console/*`→8081，透传 Authorization 不终结鉴权
  - **单库双账号不拆库**；发布传播 = `(tenant,bizLine,generation)` 轮询 + artifact 不可变兜底（无分布式事务、延迟期语义安全）；两服务各自 JWKS 验签
- **C · 细粒度四分**（明确不做）：规则编译服务是**伪服务**（KieBase 绑 classloader 不可搬运；KJAR 路线复活 kie-ci 已被否决）；租户身份服务=重复建设（Casdoor 就是）；C 实际是 B + 两个无内聚职责的盒子，**负学习价值**。

## 3. 权衡矩阵（1–5，5 好）

| 维度 | A | B | C |
|---|:-:|:-:|:-:|
| 实现复杂度 | 5 | 3.5 | 1.5 |
| 本机运维成本 | 4 | 3 | 1 |
| **学习价值**（"更像生产"是显式目标） | 3 | **5** | 2 |
| 决策热路径延迟 | 5 | 4.5（引擎与缓存仍在决策进程内，仅多网关一跳） | 3 |
| 数据一致性 | 5 | 4.5（写侧事务原样；artifact 不可变→读侧无撕裂读） | 2 |
| 与前后端分离协同 | 3 | 5 | 3 |

## 4. 推荐：B，按「先 A 后 B」两里程碑走

| 步 | 动作 | 验证 |
|---|---|---|
| M1.1 | 单体内加 `/decision/v1/*` 薄别名 controller（复用 QueryService），旧路径 deprecated 不删 | 104 绿 + 两路径 curl 一致 |
| M1.2 | 前端独立框架应用（见 FINAL_PLAN F 阶段）；Casdoor redirectUris 多值登记 | e2e-oidc 改指新入口 9/9 绿 |
| M1.3 | docker-compose：mysql + casdoor + 后端 + nginx 网关（托管前端产物，两 API 前缀先都路由同一后端） | compose up 走网关跑全套 E2E |
| M1.4 | 发布传播：generation 单行表 + console 发布 bump + 引擎侧轮询预热（进程内直调保留作双保险） | 发布后轮询预热命中日志 |
| M2.1 | Maven 多模块：`activity-common`(domain/engine/tenant，含 JPA 实体)/`activity-console`/`activity-decision`/`drools-lab`(Step 1–18 原样) | 104 绿；两 app 各自可启动 |
| M2.2 | 物理拆分：decision 独立部署(8082) 只读账号 + validate；网关 `/api/decision`→8082；移除进程内直调 | **kill console 决策仍在服务**；kill decision 不影响 console 与 Step |
| M2.3 | 观测收尾：双服务 prometheus + grafana 面板 | 面板见双服务指标 |

Step 1–18 处置：整体进 `drools-lab` 模块留在 console 进程，一行不改，不拆第三个服务。
H2 处置：拆分形态一律 MySQL；H2 只用于单模块开发与测试（file 锁不容双进程）。

## 5. 前后端接口面

nginx 边缘网关、**不做 BFF**（console API 已为 UI 塑形，无跨服务聚合诉求）。路由：`/` → SPA、`/api/decision/*`、`/api/console/*`。生产同源零 CORS；仅前端 dev server 直连时需要（用 Vite proxy 规避，后端保持零 CORS）。PKCE 流程不变，只改 redirect-uri 落点。

## 6. 风险与不做清单（摘）

风险：双 app DDL 打架（铁律：仅 console 执行 DDL）；实体漂移（进 common）；发布传播延迟被误当 bug（文档写明语义）；限流拆后 N×配额（demo 接受，文档标注）。

不做：注册中心/配置中心/K8s/服务网格/MQ/KJAR 分发/独立编译服务/自建身份/Redis/链路追踪后端/熔断框架——各有轻量替身（compose DNS、yml+env、nginx、generation 轮询、artifact 表、决策进程内编译、Casdoor、进程内桶、X-Request-Id、fire 上界+回退）。

关键文件坐标：`ActivityQueryService.java`（热路径）、`ActivityRuleRuntimeService.java`（拆分核心搬运物）、`ArtifactService.java`（warmOnPublish 直连断开点）、`ActivityResourceServerConfig.java`（安全链已分面）。
