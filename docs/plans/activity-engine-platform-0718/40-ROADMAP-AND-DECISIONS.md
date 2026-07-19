# 活动引擎平台 · 统一落地路线图与待决策清单

> Phase 4 汇总。把产品 / 后端架构 / 前端控制台三条方案拧成一份可执行落地路线图,并集中列出所有待用户拍板的决策。**本轮为完整设计蓝图,不写代码。**

## 文档地图（本次规划产出）

| 文件 | 内容 | 产出方 |
|---|---|---|
| `00-product-blueprint.md` | 产品定位·角色·品类分期·生命周期·16 用户故事·电商接入旅程·KPI·路线图 | Product Manager Agent |
| `10-architecture-gap-and-options.md` | 现状→生产差距分级·目标架构 2-3 方案·决策 API 契约草案 | Software Architect Agent |
| `20-frontend-console-design-direction.md` | 控制台 IA·四类高难界面线框·设计系统基线 | UI Designer Agent |
| `../activity-engine-backend-0718-2111/FINAL_PLAN.md` | **后端架构 FINAL_PLAN**（含 Claude 复核 §15 + 前端驱动补遗 §16） | /codex-plan(Codex→Claude 复核) |
| `30-DECISION_RECORD_FRONTEND.md` | 前端 6 决策 + 后端缺口回写 | /frontend-plan |
| `31-FRONTEND_CONSOLE_PLAN.md` | **前端控制台实施计划**（含独立评审吸收 §14） | /frontend-plan |
| `40-ROADMAP-AND-DECISIONS.md` | 本文件：统一路线图 + 决策清单 | Phase 4 汇总 |

## 一句话蓝图

**电商的营销决策中台**:运营在控制台把「什么人、买什么、满多少、给多少优惠/送什么赠品」配成**可审核·可灰度·可回滚**的规则(运营永不写 DRL,只拼白名单条件树→受控 Drools);电商系统**一次 API 接入** `POST /decision/v1/evaluate`,在结算热路径实时拿到「命中哪些活动、优惠多少、赠品是什么」,**引擎故障 fail-open 降级、绝不阻塞下单**。

- **形态**:决策中台 + 运营控制台(单租户为主,多租户二期)。
- **架构**:方案 B(不可变内容 artifact + release manifest + CAS 版本指针),MVP 模块化单体 + 决策/配置面可拆分接缝,二期物理拆分。
- **前端**:方案 C(纯原生纪律 + 图表窄口径例外,不引框架),寄生现有演示台子应用槽,删文件即回退。
- **地基复用**:现有 `version+1/requestId/409`、条件树白名单翻译、圈选、fail-safe、DemoUI/token 全部复用;护栏走 Step 14、指标走 Step 15。

## ⬆ 升级:通用化 + 多租户（2026-07-18 用户拍板,覆盖上文"单租户电商优先"的范围）

用户推翻"单租户 MVP、SaaS 二期",目标升级为**多租户 · 元数据驱动的营销活动决策 SaaS**:任何业务线**零平台代码**自助注册上下文 schema + 活动/权益类型 + 合并策略即可接入。第二业务线**出行(司机激励)**验证通用性。详见 `01-platform-generalization-product.md`(产品)、`11-generalization-architecture.md`(架构)、后端 FINAL_PLAN `§17`。

- **新对象层级**:Tenant(隔离/配额/计费)→ BizLine(schema 建模,`release_key=tenantId:bizLine`)→ Activity → Version。
- **三大通用抽象(M0 就留接缝,不欠债)**:①上下文 schema 数据驱动(替 `RuleField` 硬编码枚举,加 ENUM+候选值)②**Map 支撑通用 fact + 强类型访问器**(替固定 `ActivityRuleContext` POJO;命门)③benefit-type 注册表(DISCOUNT/GIFT/**CASH_REWARD**)。
- **ladder 泛化**:阈值字段参数化(orderAmount↔completedTrips),同一机器两条线共用;平台**无状态**,出行累积在 caller 侧预聚合传入,不引 CEP。
- **多租户**:行级隔离(tenant_id + release_key 二元组)为主线,全链路强制租户过滤;决策 API 用**通用 context 信封**;`field-dict` 变 `?tenant=&bizLine=` 数据驱动。

### 落地节奏(架构 Agent 强推,已纳入):两个硬骨头不同时上

```
Track A 通用化(单租户内先跑通)：schema 驱动 + 通用 fact + benefit 插件 → 对齐旧行为(回归证明金额语义不变) + 电商&出行两条线端到端
        │  (先证明通用抽象正确、再引入隔离维度,风险可控)
        ▼
Track B 多租户化：tenant_id 全链路 + 行级隔离 + release_key 二元组 + API Key scope + 配额 + 计量
```

M0 薄片仍最简,但**从第一天留接缝**:tenant_id 列、release_key 二元组、翻译器字段来源接口、**所有缓存/registry/查询 key 带 tenant(单租户时常量)**。**Map fact 是否进 M0 由下面的 spike 闸决定**(不绿则 M0 保留 typed 基线)。下表 M0-M4 据此重读:M0/M1 = Track A(单租户下通用化 + 两条线);多租户维度(Track B)叠加在 M2 之后。

### ⛔ 落地前必过的闸(三视角独立评审,详见 `41-REVIEW-FINDINGS-generalization.md` / 后端 §17.8)

三视角对抗评审结论:**骨架/方向对且诚实,但当前"策略充分、机制缺位 + 命门写成已核实实则未证"。开建前必须先过:**
- **【P0·先做】Map fact spike**:验 `numberAttr>=`/`in`/`contains`/`between`/`not RuleContext(...)` 真能编译执行(官方只背书 `[]` 映射访问,未背书方法左值集合运算符);不绿切 declared-type/typed。**这是全局最高优先,排在 M0 之前。**
- **【P0·Track B 前】** M2M 身份不可伪造(每租户独立 app 唯一 secret,禁共享 secret)/ 租户隔离机制化(ORM `@TenantId`/RLS + CI 阻断裸查询)/ aud 校验器自写(owner↔aud 绑定)/ 多租户内存容量模型(含 Metaspace + 公平份额淘汰)。
- **【P1】** 否定运算符 fail-OPEN 已修(§17.4);ReBAC 做不了 D2 四眼→应用层强制;JWKS 轮转热路径兜底;分布式限流基座;租户注销级联清理。

### 新增待决策(叠加在 D1-D8 之上)
- **D9 · 落地节奏**:确认"先通用化(单租户)对齐旧行为,再叠多租户"这个分步顺序(架构强推,防风险失控)。
- **D10 · schema 演进 vs 冻结 artifact**:推荐 schema 版本化 + artifact pin schema 版本 + 加字段友好/删改字段标记受影响活动重建;需确认。
- **D11 · schema 值类型范围**:ENUM(+候选值)出行硬需必加;是否还要 BOOLEAN/DATE/GEO。
- **D12 · 跨 benefit-type 合并语义**:多单+阶梯同时命中 STACK 还是 MAX;现金+赠品异构权益能否合并(现有 StackStrategy 仅同类折扣金额)。
- **D13 · 租户隔离强度/合规**(部分解决):认证/授权侧隔离已由 auth-platform 承接(Casdoor org + SpiceDB `<tenantId>_` 前缀 + 独立 SpiceDB 实例);数据侧仍是行级 tenant_id 过滤(§17.3),大客/合规客留 schema-per-tenant 逃生舱。仍待确认:有无数据驻留/物理隔离/删除权硬诉求 → 决定是否需 db-per-tenant。
- **P0 压测项**:`DecisionSnapshotRegistry` + KieBase 缓存的**多租户内存模型**(常驻租户上限/权重淘汰/冷租户重建尖刺 = OOM+P99 双命门)。

> 上述 D9-D13 我按推荐默认可推进设计;真正该你尽快拍的是 **D9 落地节奏**(架构安全前提)与 **D13 隔离强度/合规**(可能影响隔离选型)。

---

## 统一分阶段路线图（后端 + 前端共同落地）

> 前端界面紧跟对应后端端点;后端未就绪的界面先"诚实空态占位",端点就绪再充实。每个 FE 步标「占位可交付 / 真数据可验」两个里程碑。

| 阶段 | 后端(activity-engine-backend FINAL_PLAN) | 前端(FRONTEND_CONSOLE_PLAN) | 里程碑 |
|---|---|---|---|
| **M0 薄片**（建议第一刀，见后端 §15） | 数据层 baseline(Flyway) + 单活动 artifact + stable pointer(无 canary/shadow) + **GuardedRuleExecutor(stateful 护栏)** + `POST /decision/v1/evaluate` 骨架 + fail-open 降级 | FE-0 基础设施(store/router/reconcile/ui + AppShell + ESM 分片 + ≤980 抽屉) | 热路径正确性 + 护栏 + 决策 API 端到端立住 |
| **M1 配置闭环** | 后端阶段一/二:workflow/strategy/idempotency/artifact/manifest 实体 + WorkflowService + ArtifactBuilder + ManifestBuilder + ReleaseService CAS + 统一 DecisionService(修买赠资格/阶梯覆盖/tie-break) | FE-1a 编辑器(复用条件树+dynRows,条件树重做①,**仍走旧 `/activity-marketing/create`+`/preview`**,权威写入端=旧 create) | 建活动→资格/阶梯/折扣/买赠决策正确 |
| **M2 治理上线** | 后端阶段三:config/release/decision/effect controller + **§16 控制面查询端点(list-by-state/queue/get/versions,P0)** + 安全过滤器 + 低基数指标 + 异步日志/outbox 轮询 | FE-1b(翻到 config API + 提交审核,权威写入端切 config) + FE-2 列表/工作台 + FE-3 审核发布(状态机②+diff+放量+回滚+409 CAS) | 草稿→审核→发布→灰度→回滚全生命周期 |
| **M3 验证与洞察** | 决策 explain 返回评估全景(§16-#2) + effect 日聚合 + experiment comparison | FE-4 Sandbox 全景③(degraded 区分) + FE-5 监控看板(手绘 SVG+表格兜底+灰度对照) | Sandbox 可复现 + 效果/灰度可观测 |
| **M4 加固与二期接缝** | 测试(护栏压测 ADR/多实例/迁移/故障注入) + 文档/Runbook + 决策/配置面物理拆分依赖清单 | FE-6 移动端 + 可访问性收尾(AC-M1~M5) + Tier0/1/2 测试矩阵 | SLO/回滚演练<5min + 二期拆分就绪 |

**跨阶段贯穿**:可观测(Micrometer,tag 仅 scene/strategy/mode/reason,禁 activityId/规则名高基数)、审计(不可变追加)、灰度(pointer 层 SHA-256 固定分桶)、fail-open 降级(常开)。

## 复用 vs 新建 一览

- **直接复用**:条件树白名单翻译(`RuleConditionTranslator`)、`ActivityDrlBuilder`、圈选 diff、`version+1/requestId/409`、DemoUI/token、`dynRows`、整套条件树 UI、Step 14 护栏三件套、Step 15 指标。
- **改造**:决策路径 stateless→**stateful 护栏**(硬前提);`changeStatus` 任意跳转→**受控状态机**;全局 `rule-engine.enabled`→只留 kill switch,灰度改 pointer 层;`saveStrategyIfPresent` 静默 upsert→独立策略版本随发布单审核;买赠补资格;阶梯 tie-break 修复;前端 `reTree` 整树重建→keyed reconcile。
- **全新**:artifact/manifest/pointer/outbox/audit 协议 + ~13 张表 + Flyway;审核/灰度/回滚;RBAC + API Key;效果聚合;前端 store/router/reconcile/ESM 分片 + 统一反馈层 + 图表。

---

## 待决策清单（去重后,含推荐默认）

> 三条方案共识出的待澄清已去重。**每条给了推荐默认**——不选也能按默认推进设计;但下列 D1-D3 触及**财务/组织流程/下一步**,是真正该你拍板的。D4-D8 我按推荐默认走并在方案中标注为假设,你随时可推翻。

**D1 · 决策降级默认 + 库存/预算边界（触及财务与 Non-goal）— ✅ 已定(2026-07-18)**
- **决定:`NO_PROMOTION` 降级(引擎故障时不发优惠,防超发)+ 库存仅配置展示不扣减(守 Non-goal)。** 与后端 §2/§8.2 既有默认一致,锁定。上线前仍需产品/财务签字 + 金额上限告警。

**D2 · 审核工作流 + 灰度维度（组织流程）— ✅ 已定(2026-07-18):选更完整一档**
- **决定:多级审核 + 强制职责分离;灰度多维(userId 哈希 + 地域/门店/cohort)+ A/B 对照组度量。**
- **范围增量(诚实标注,覆盖后端 §12.1/§12.2 的"单级/userId"默认)**:
  1. **状态机**:审核态从单级 `PENDING_REVIEW→APPROVED` 扩为**多级签署链**(如 初审→复审→终审,每级 actor/time/comment + 可退回),`ActivityLifecycleState` 与 `activity_version_workflow` 表需支持多级审批记录(增审批环节表或多行审批流水)。
  2. **灰度分桶**:`bucketKey` 从"userId 优先"扩为**多维策略**——pointer/experiment 配置需带 `dimension∈{userId,district,store,cohort}` + 对应固定哈希输入;`ReleaseRouter#route` 按维度取 bucketKey。地域/门店/cohort 需决策请求上下文已带这些字段(现有 `userDistrictId`/`storeId` 已在,cohort=userTags 可用)。
  3. **A/B**:需**固定实验框架**(experimentId + salt + 双臂/多臂分配 + 窗口 + 指标),`activity_effect_daily` 的 `route_group` 扩为实验臂维度,effect experiment comparison 做统计对照(区分 canary 放量 vs 严格 A/B,见后端 §5.5)。
  4. **前端**:审核界面出现**多级签署视图**;编辑器/发布控制台的灰度分区需选维度 + A/B 实验配置;Sandbox 分桶预演支持多维输入;监控看板做 A/B 对照。
  - **对路线图的影响**:M2/M3 工作量上升;建议 M0/M1 薄片仍按最简(单活动 artifact + stable pointer,不含灰度),**多级审核 + 多维灰度 + A/B 集中在 M2/M3 落地**,避免薄片被撑大。

**D3 · 本轮之后的下一步 — ✅ 已定(2026-07-18):到此为止,用户 review**
- 本轮交付完整设计蓝图(7 份文档),不写代码。后续动手时按本路线图 M0 薄片起步。

**D4 · 决策延迟预算/容量**(默认 P99<50ms、可用性≥99.9%;maxFires/timeout/bulkhead 压测定)——后端 §12.4。
**D5 · 核销/效果数据来源**(默认:平台只记决策命中;核销率/GMV 需电商回传订单事件,列 v2)——产品 Q4 / 后端 §12.5。
**D6 · 鉴权基础设施 + 前端角色来源 — ✅ 已定(2026-07-18):接入既有 auth-platform**。控制台 OIDC SSO(Casdoor)+ 决策 API 本地 JWKS 验签 + 细粒度判权走 SpiceDB(仅控制台);租户身份 = `token.owner`;不再自建 API Key/密钥。前端角色来自 token `groups` + SpiceDB 判权。详见 `12-auth-platform-integration.md` / 后端 §17.7。
**D7 · 多实例收敛窗口**(默认:可监控可暂停放量的收敛窗口,非零窗口;二期再评估两阶段激活)——后端 §12.8。
**D8 · 前端零构建口径**(默认:坚持不引框架=方案 C;允许控制台迁原生 ESM module;Playwright 不进仓库走外挂)——前端 FE-Q6/Q7。

> **注**:计划自称"生产级控制台/平台",与仓库"学习脚手架、非生产代码"定位是一次**定位扩张**(非技术硬伤)。请知悉并认可这次扩张,后续所有生产化投入以此为前提。
