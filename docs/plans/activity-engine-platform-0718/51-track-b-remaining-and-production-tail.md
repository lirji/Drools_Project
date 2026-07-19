# Track B 收尾 · 剩余项现状 + 生产尾项设计

> 承接 `review-handoff.md`。用户裁决「Track B 余下的都做」。本文一处集中：① 全 Track B 项的**现状矩阵**；② demo 无法一轮做完的**生产尾项**（P0-5 异步池/可抢占淘汰、P1-15 PK online-DDL、P1-16 租户注销、P1-8 四眼、P1-9 pinned-schema）的**设计固化 + 落地计划**，供后续实现直接照做（不臆造半成品：这些依赖 artifact/manifest 系统、控制台人流、压测/heap-dump 工具链或多实例基座，均超单机 demo）。

## 现状矩阵（截至 2026-07-19）

| 项 | 主题 | 状态 | 落点 |
| --- | --- | --- | --- |
| P0-3 | M2M 决策身份 / Casdoor | ✅ 闭环（真 token 12/12） | `casdoor-m2m-verify.sh`、`tenant/*` |
| P0-4 | 租户隔离机制化 `@TenantId` | ✅ | 10 实体 + `tenant/`、`TenantArchGuardTest` |
| P0-5 | 内存容量模型 | ✅ 核心（测量+weigher+文档），🟡 生产尾项见 §1 | `50-P0-5-*.md`、`ActivityRuleRuntimeService` |
| P1-6 | 有界 DRL 缓存 | ✅ | Caffeine 足迹加权 |
| P1-7 | trace 构建期 explain | ✅ | `ActivityDrlBuilder` |
| P1-10 | 跨异构 benefit MVP 不支持 | ✅（文档标注 + `BenefitOutcome`） | `ActivityRuleResult` |
| P1-11 | 自管 API Key 作废 → Casdoor | ✅（P0-3 兑现） | — |
| P1-12 | JWKS 轮转兜底 + last-good | ✅ | `OutageTolerantJwks`、`LastGoodJwksTest` |
| P1-13 | 每租户限流基座 | ✅ demo 切片（进程内），生产 Redis 见 §4 | `TenantQuotaService`、`TenantRateLimitConfig` |
| P1-14 | 反查归属 IDOR | ✅（`@TenantId` 机制化，`TenantIsolationTest` 覆盖） | — |
| P1-17 | per-artifact fire 上界 | ✅ | `ActivityRuleRuntimeService` fire 上界 |
| ISSUE-07 | 编辑后幂等重放 | ✅ 独立幂等表 | `ActivityIdempotencyEntity` |
| **P1-15** | Track B PK 改造 + 回滚锚点 | ⏳ 设计（§2） | 生产 online-DDL |
| **P1-16** | 租户注销级联清理 + PII | ⏳ runbook（§3） | GA 前 |
| **P1-8** | D2 四眼（应用层） | ⏳ 设计（§5，依赖控制台人流） | `ActivityWorkflowService`（未建） |
| **P1-9** | pinned-schema 校验 + 硬失效 | ⏳ 设计（§6，依赖 artifact 系统） | — |
| **前端 OIDC** | 授权码+PKCE 登录 | 见 `52-frontend-oidc-*.md` | `activity.js` |

---

## 1. P0-5 生产尾项：异步编译池 + 可抢占公平份额 + 压测/heap-dump

`50-P0-5-memory-capacity-model.md` 已落地测量 + 足迹加权 weigher + fire 上界。剩余生产件：

### 1.1 独立限速编译线程池（双冷 miss 不阻塞热路径）
- **现状**：`ActivityRuleRuntimeService.compileOrGet` 是唯一编译入口，Caffeine `get(k, fn)` 天然 single-flight（同 key 只编译一次）。冷 miss 在**调用线程**同步编译（~100ms~秒级）。
- **设计**：
  1. 注入独立 `ThreadPoolExecutor`（`corePoolSize` = min(2, cores/4)，有界队列，`CallerRunsPolicy` 兜底），**与决策 tomcat 线程隔离**——预热/重建的编译 CPU 不抢决策核。
  2. **发布预热**：`changeStatus(ONLINE)` 成功后，`executor.submit(() -> warm(该活动的 elig/ladder/discount DRL))` 异步编译入缓存 → 首个决策请求命中 warm。
  3. **双冷 miss 降级**：热路径若遇未预热的冷 miss，可选「同步编译（当前行为，简单）」或「提交异步编译 + 本次显式降级走 legacy（P99 有上界）」。demo 保持同步；生产按 SLA 选降级。
- **接缝**：已就位（compileOrGet 单入口 + fail-safe 回退）。实现只加 executor + warm 方法 + ONLINE 钩子，不动决策语义。

### 1.2 per-tenant 可抢占公平份额淘汰
- **现状**：cache key 含 tenant（天然分片）+ 足迹加权全局预算。**未做** per-tenant 配额。
- **设计**（评审 §11:289③）：`per-tenant-max-weight = 总预算 / 常驻租户软上限`；单租户超份额优先淘汰**自己**的冷 KieBase；空闲时大租户可**借用**空闲配额，别租户来抢时**可抢占**回收（借用项标记优先淘汰）；热租户 `pin`；常驻租户硬上限，超限拒绝新租户激活并告警（背压非 OOM）。
- **实现要点**：Caffeine 单缓存不支持 per-key 配额 → 需 per-tenant 子缓存 或 自定义淘汰 `Policy`。属生产件。

### 1.3 目标租户数负载测试 + churn heap-dump（验收项，需环境）
- **负载测试**：目标 `N` 租户 × `M` 活动 × `T` 档，按 §2 公式预估堆预算，压测验证常驻堆 ≤ 预算 + 决策 P99 达标；冷租户 churn 下观察冷编译尖刺。
- **heap-dump churn 回收**：反复淘汰/重建 KieBase，`jmap`/`jcmd` 抓 heap dump，验 classloader 被回收、Metaspace 不涨（否则 classloader 泄漏）。`-XX:MaxMetaspaceSize` 设为 §4 预算值触发回收。
- **门槛**：需多租户压测环境 + heap-dump 工具链，非单机 demo 一轮能出，留作 GA 前性能验收。

---

## 2. P1-15 · Track B PK 改造 + 回滚锚点（热表 online-DDL）

- **现状**：demo 用 `@TenantId tenant_id` 列 + 复合唯一约束/索引（热点索引已以 `tenant_id` 打头，见各 Entity）。pointer/release_key 维度在代码里已带 tenant（cache/registry/查询 key 从 Day1 带 tenant）。
- **生产改造**：若某热表 PK 仍是 `bizLine`（单租户遗留），升为 `(tenant_id, bizLine)` = **锁表 DDL**，M2 后有流量时不可直接 `ALTER`。
- **计划**：① 用 **online-DDL**（`gh-ost` / `pt-osc`）影子表 + 增量回放 + 原子切换，避免锁表；② **回滚锚点**：改造前打 schema 版本 tag + 数据快照点，失败可回旧表；③ 灰度：先只读双写校验，再切写。
- **接缝已留**：所有缓存/registry/查询 key 已含 tenant（单租户时常量），避免「最高危返工」（评审 P1-15）。

## 3. P1-16 · 租户注销级联清理 + PII runbook（GA 前）

租户注销演练手册（照评审清单）：
1. **级联清理**（同租户所有行，`@TenantId` 下按 `tenant_id` 批删）：`activity_manage / activity_rule / activity_condition / activity_spu_binding / activity_gift / pool_ref / activity_strategy / activity_idempotency / product_pool / product_pool_rule / demo_product` 等 ~13 表。
2. **不可变凭证**：artifact / manifest / decision-log / audit —— **不物理删**，标 `退役 + 租户注销`（合规留痕），按留存期到期再擦。
3. **内存/缓存**：驱逐该租户的 KieBase 缓存条目（key 含 tenant）、snapshot 分区、schema registry 覆盖、限流桶。
4. **外部系统**：Casdoor org + 该租户 M2M 应用（`activity-<tenant>-cid`）删除；SpiceDB `<tenant>_*` 元组清理。
5. **PII**：`driverId` 等按留存/擦除策略处理（匿名化或到期删）。
- **门槛**：需 artifact/审计系统（demo 未建）+ 生产编排，故为 runbook 文档，非 demo 代码。

## 4. P1-13 生产限流基座（承接 demo 切片）

- **demo 现状**：`TenantQuotaService` 进程内 token bucket（仅本实例）。
- **生产**：无状态多实例 → 换 **Redis token bucket**（Bucket4j-Redis / Redisson）或**网关层**（如 APISIX/Kong 的 limit-count）。
- **必须定义**：① **延迟预算**——每请求多一次 Redis 往返，计入决策 P99；② **Redis 宕机策略**——`fail-open`（放行保可用，配额短暂失效）或 `fail-closed`（拒绝保配额，牺牲可用）二选一并在配置显式声明。demo 进程内实现天然 fail-open（无外部依赖）。

## 5. P1-8 · D2 四眼职责分离（应用层，依赖控制台人流）

- **背景**：ReBAC（SpiceDB `recsys.zed`）只给粗粒度角色分离；四眼/自审阻断是**动态主体约束**（活动级 `edited_by`），写元组违反「高频子资源不写元组」。故放**应用层**。
- **设计**：`ActivityWorkflowService`（未建）在 create/edit 时持久化**提交人** `submitted_by`（从 JWT `sub`）；`approve/publish` 时校验 `actor != submitter`，相等则拒（提交人不能自审）。
- **依赖**：P0-3 只做了**机器决策平面**的身份；控制台**人流**（授权码+PKCE 登录 + 用户身份 + 审批状态机）是前置。四眼随控制台人流一并做。

## 6. P1-9 · pinned-schema 校验 + 硬失效（依赖 artifact 系统）

- **背景**：信封校验须按 artifact **pinned schema**（冻结时的字段集）而非 live schema，否则改 schema 后冻结 artifact 跑不到。
- **设计**：artifact 编译时把 schema 版本 pin 进 manifest；决策按 pinned schema 校验信封；删字段/改类型 → **硬失效**引用它的 artifact（标「需重建/退役」），缺字段**显式分类**（拒绝 / 降级带 reason），**禁止静默淘汰**（不静默改金额）。
- **依赖**：demo 无 artifact/manifest 冻结系统（`RuleSchemaRegistry` 现是静态默认 + `register` 口）。pinned-schema 随 artifact 系统一并做。P0-1 已留 `register` 接缝。

---

## 落地顺序建议（后续窗口）
控制台人流（授权码+PKCE 登录 → 用户身份 → 审批状态机）是 P1-8 四眼 + 前端 OIDC 的共同前置，**先建控制台平面**；artifact/manifest 冻结系统是 P1-9 pinned-schema + P0-5 异步预热（按 artifact 预热）的前置，**再建 artifact 平面**；两者就绪后压测环境做 P0-5 §1.3 验收 + P1-15 online-DDL。P1-16 runbook 与 P1-13 Redis 基座可独立随时做。
