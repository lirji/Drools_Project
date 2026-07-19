# 交接文档 · 活动引擎「通用化 + 多租户 + auth 接入」评审结论

> **给新窗口/接手人**:本文自包含,可直接据此执行。三视角对抗评审(正确性/可行性、安全/多租户隔离、性能/内存/运维)+ 两个实证 spike 的**可执行结论**。行号以评审时(2026-07-18/19)为准,改前先 `grep` 确认未漂移。**本轮只出结论,未改任何业务代码。**

## 落地进度(实现窗口回写)

> 按底部「执行建议」节奏推进 Track A → Track B。每完成一项:改代码 + 真跑 Drools 测试 + 回写此处。

### Track A（单租户内通用化）—— ✅ 全部完成（2026-07-19，25 测试绿，BUILD SUCCESS）

用户确认节奏：一口气做完 Track A、tenant 维度先 stub 成单租户常量。全程 typed 语义作回归基线，逐层跑绿。

- **[✅] P0-2 否定运算符 fail-open 护栏** — `RuleConditionTranslator` NE/NOT_IN/NOT_CONTAINS emit 加 `(acc != null && …)`，缺字段短路 fail-closed。测 `ActivityEligibilityGuardTest`（notIn 缺 district + notContains 缺 userTags → 均淘汰）。
- **[✅] P0-1 Map fact 通用化（命门）** — `RuleField` 枚举 → `RuleSchemaRegistry`(按 (tenant,bizLine) 解析，`DEFAULT_TENANT` stub) + `SchemaField`(record，`accessor()` 由 valueType 派生)；`ActivityRuleContext` typed→Map 支撑 + `numberAttr/textAttr/listAttr/boolAttr`（照 spike）；`RuleConditionTranslator` 改纯函数 `translate(tree, schema)`，emit 方法左值形态；`ActivityDrlBuilder` 资格/阶梯改访问器。默认 schema = 原 6 字段，行为等价，10 旧测试全绿。
- **[✅] P2-18 ladder 字段参数化** — `LadderActivityDef` 加 `ladderField`，阶梯闸门 emit `numberAttr("<field>")`（Track A 固定 orderAmount，出行可换 completedTrips）。`LadderRangeParser` 确认一行未改。
- **[✅] P2-19 emit 规范 key + P2-21 标识符转义** — 访问器 key/activityId/ladderField 全过 `^[A-Za-z0-9_]+$` 白名单发射器（翻译器 `KEY_PATTERN` + builder `ID_PATTERN`），非法即抛，不静默拼接。
- **[✅] P2-20 FieldValueType 加 ENUM + BOOLEAN** — ENUM 走 textAttr + 候选值白名单校验（值不在候选内翻译期抛）；BOOLEAN 走 boolAttr + true/false 不加引号。纯单元测 `RuleConditionTranslatorTest`（13 例）锁定所有 emit 形态。
- **[✅] P1-6 有界 DRL 缓存** — `ActivityRuleRuntimeService` 无界 `ConcurrentHashMap` → Caffeine `maximumSize=500` + LRU + `recordStats`。pom 加 caffeine（BOM 管版本）。cache key 含 tenant（DRL 全文因 schema 而异，天然分片）。
- **[✅] P1-7 trace 构建期开关** — builder/runtime 加 `explain` 参数，`explain=false` **构建期不 emit** `result.trace(...)`（非响应期过滤）。默认 true 保留旧行为；request 级透传（SpuDiscountRequest 加 explain 字段）留作后续小改，不改记录契约。
- **[✅] P1-10 BenefitOutcome 前瞻结构** — `ActivityRuleResult` 加 `List<BenefitOutcome> benefits`（`hit()` 追加），`hitAmount/gifts` 保留兼容投影；`BenefitOutcome` javadoc 明标 **MVP 单场景单 benefit-type、跨异构权益合并不支持**（同类折扣合并跑在 `computedAmount` 上不受影响）。
- **[✅] P2-22 O(N²) 自连接兜底告警** — `ActivityQueryService` 候选数 > `MAX_CANDIDATES(200)` 时 warn（不静默截断，符合 no-silent-caps）。
- **[✅] P2-23 逃生舱迁移非零成本（文档纠正）** — 已在 `SchemaField.accessor()` / 翻译器注释点明：方法左值访问器 emit 与将来 declared-type 的属性访问 emit 不同，(a)→(c) 迁移翻译器 emit **必改**，非零成本。

**P1-9 部分落地 + 部分显式延后**：已落地=缺字段归一化（`putAttr` 跳过 null → "键不存在"与"值 null"统一为访问器 null → fail-closed，不静默改金额）+ 字段/ENUM 值 create 期按 schema 校验。**显式延后**=「信封按 artifact 的 pinned schema 校验、删字段/改类型硬失效引用它的 artifact」——demo 尚无 artifact/manifest 冻结系统（那是平台化/Track B 的产物），schema 在 Track A 是静态默认，故此部分随 artifact 系统一并做，不在此臆造半成品。

**改动文件**：新增 `SchemaField`/`RuleSchemaRegistry`/`BenefitOutcome`；重写 `ActivityRuleContext`/`RuleConditionTranslator`/`ActivityDrlBuilder`/`ActivityRuleRuntimeService`；改 `FieldValueType`/`ActivityRuleResult`/`ActivityMarketingService`/`ActivityQueryService`/`ActivityMarketingController`/`ConditionNode`/`pom.xml`/`docs/activity-marketing.md`；删 `RuleField`；测试新增 `RuleConditionTranslatorTest` + 扩 `ActivityEligibilityGuardTest`。全量 `./mvnw test` = 25 绿。

- **[✅ 已完成] Track A → Track B 过渡** — 用户确认节奏：**机制先行 + tenant 来源可插拔 + 留 dev-only 默认租户**。Track B 从 P0-4 起做。

### Track B（多租户）—— 🚧 进行中（P0-4 ✅，2026-07-19，34 测试绿，BUILD SUCCESS）

用户拍板：tenant 来源二选一 → **先 header stub、后换 Casdoor**（auth-platform 本机可跑，但 P0-4 隔离机制与"tenant 从哪来"正交，先把机制做成自包含可测，P0-3 再把来源从 header 换成 JWT `token.owner`，隔离机制一行不动）。并**留 dev-only 默认租户**（本地/前端手点方便，生产 fail-closed）。

- **[✅] P0-4 租户隔离机制化（SEC-2）** — 隔离**靠机制不靠纪律**：
  - **机制** = Hibernate 6 `@TenantId`（判别式多租户）。全部 **10 个实体**加 `tenant_id` 列，引擎对每条 SQL **自动追加 `tenant_id = ?` 谓词** + insert **自动落 tenant**（业务代码不手动 set）。原「裸 `findAll()`」隐患（`ActivityPoolMatchService:60`）现由机制自动加谓词，**本身已安全**，无需改写。
  - **来源接缝** = `TenantContext`(ThreadLocal) ← `TenantContextFilter` 从 `X-Tenant-Id` header 写入、请求结束 finally 清除（防线程池串租户）。**P0-3 接 auth-platform 时只把过滤器"读 header"换成"读 JWT owner claim"，`TenantContext` / resolver 不动**——正是 Track A 留的"造机制、stub 源、后续换源"接缝。
  - **resolver 失效模式（命门，别改）** = **永不抛、永不返回 null**。null 会被 Hibernate `isRoot` 当 root（看所有租户）；抛异常会误伤**非活动** DB Step（10 loyalty / 18 campaign，它们的实体无 `@TenantId`，但仍开 Session 触发 resolver）。故无租户时：`TenantContext` 有值→用它 → dev-default 开→dev-default → 否则哨兵 `__no_tenant__`（读隔离到空，**warn 不静默**）。
  - **面向用户 fail-closed** 放在过滤器（只挂 `/activity-marketing/*`，不波及 Step 1~18）：无 header + dev-default 关 → **403**；非法 header（非 `[A-Za-z0-9_-]{1,64}`）→ **400**。`activity.tenant.dev-default-enabled` 默认 **false**（不配即安全），`application.yml`(dev-run) 与测试显式置 true。
  - **选型偏差（诚实记录）**：交接原验收「CI 阻断裸 `findAll()`」——加 `@TenantId` 后 `findAll()` 已被机制自动加谓词、**本身安全**，"禁 findAll" 已非对症。改为更对症的结构守卫 `TenantArchGuardTest`：① 每个 `@Entity` 必带 `@TenantId`（全局表放 `GLOBAL_ENTITIES` 白名单显式豁免）② 仓库无 `@Query(nativeQuery=true)`（原生 SQL 绕过 `@TenantId` 过滤 = 隔离漏洞）。这守的是真 footgun（新表忘加租户列 / 原生 SQL 旁路），比"禁 findAll"值钱。
  - **`activity_benefit_type` 全局行例外**：demo 尚无此表 → `GLOBAL_ENTITIES` 白名单留空占位，待该表加入时登记（不臆造半成品）。
  - **验证**：`./mvnw test` = **34 绿**（旧 25 + 新 9：跨租户读隔离 3 + 过滤器 fail-closed 4 + arch guard 2）。**真跑 HTTP 冒烟**（h2 / dev-default 关 / :8099）：无 header→403、有 header→200、非法 header→400、非活动 Step `/hello`→200（不受影响）、`acme` 建活动后 `globex` 列表空 + 详情 400（跨租户 fail-closed）。隔离测试是**真证明**：若 `@TenantId` 没接上，B 会看到 A 的数据、断言必红。
  - **改动文件**：新增 `tenant/`（`TenantContext`/`TenantProperties`/`TenantIdentifierResolver`/`TenantContextFilter`/`MultiTenancyConfig`）；10 个 `persistence/*Entity` 加 `@TenantId` 列；`application.yml` 加 `activity.tenant` 段；`ActivityDemoSeeder` 在 dev-default 租户下播种；测试新增 `TenantIsolationTest`/`TenantContextFilterTest`/`TenantArchGuardTest`。
  - **本项显式未做/延后**：① **schema-per-tenant 不在 P0-4**——`RuleSchemaRegistry.resolve` 仍传 `DEFAULT_TENANT`，字段 schema 仍全局共享；P0-4 只做**数据行隔离**，字段元数据按租户隔离属 P0-1 的 Track B 扩展。② **P1-15 PK 改造**（pointer PK→`(tenant_id,bizLine)` + `tenant_id` 复合索引）是独立热表 DDL 项，未叠加；当前仅加列不改 PK/索引。③ `softDeleteVersion` 的 bulk HQL 是否带租户谓词**未显式验证**——`activityId` 全局唯一（`ACT`+epochMillis+seq）使其实务安全，已记为待验证点。

- **[✅ 全部闭环（2026-07-19 真 Casdoor 端到端 12/12 绿）] P0-3 M2M 决策身份 / 接 Casdoor（SEC-1/SEC-3）** — 把 P0-4 留的"来源接缝"从 header 换成**验证过的 JWT，租户从 `aud` 解析**（命脉修正，见下）：
  - **✅ 真 token 端到端收口（本会话自跑，无需用户 `!`）**：脚本 `scratchpad/casdoor-m2m-verify.sh`（重建版，含 provision+mint+smoke 三段，幂等）在真 Casdoor(:8000) 造 3 个独立 client_credentials 应用（`activity-acme-cid`/`activity-beta-cid` + 非家族 `decision-alien-cid`，各自唯一 secret），铸真 token 打运行中的 app(:8099, auth 档)：**MINT 4/4 + SMOKE 8/8 = 12/12 全绿**。逐条实证：铸出的 `aud=["activity-{tenant}-cid"]`/`owner=admin`（与命脉实测一致）；**acme client_id + beta secret 换不出 token**（不可伪造）；无 token/垃圾 token/非家族 aud → 401；**acme 建活动(ACT…001) → beta 列表看不到 + beta 取详情 400**（真 token 跨租户读隔离，@TenantId 端到端接通）；信封 `X-Tenant-Id=beta` + acme token → 403。JWKS 启动自检从真 Casdoor 取到 1 个公钥。（上会话记的"分类器拦截铸 token"在本会话未复现，故此步不再归用户。）
  - **⚡ 命脉实测结果 = NO**：Casdoor 的 **client_credentials token `owner`=`admin`（应用 owner 字段），不是组织**。租户信息在 `aud`=client_id（`activity-acme-cid`）与 `sub`=`admin/activity-acme`。跨租户冒烟 ✅：acme 的 secret 换不出 beta 的 token（每应用独立 secret → **aud 不可伪造**）。**结论：租户从 `aud` 解析（而非 owner），比 owner 更实在（等于显式白名单 + 不可伪造）**，正是设计文档预留的"client→tenant 映射兜底"。
  - **勘察实测**：Casdoor 本机在跑（`localhost:8000`，容器 `authz-casdoor`）。issuer=`http://localhost:8000`、jwks=`/.well-known/jwks`；claim 契约 `owner`=org=tenant、`aud`=client_id（`docs/统一登录平台接入手册.md`）。已有 org `acme/beta/recsys`，但其 app（`rag-acme/rag-beta`）**只授 authorization_code/password，无 client_credentials**——印证 P0-3「M2M 须每租户独立注册带 client_credentials 的应用 + 唯一 secret」。
  - **来源接缝兑现**：`activity.tenant.auth.enabled`（默认 **false**=保持 P0-4 header 来源）。开启后 `/activity-marketing/**` 需 Casdoor 验签 JWT，`JwtTenantFilter` 用 `AudienceTenantResolver` 从 **`aud` 解析租户**落进 `TenantContext` → 接上 P0-4 的 `@TenantId` 机制**一行不改**。header/query/body 的 tenantId 一律不作来源；信封 `X-Tenant-Id` 若带则只校验（≠解析出的租户→**403**）。
  - **自写 audience 校验器**（`AudienceTenantValidator` + `AudienceTenantResolver`，SEC-3）：**不抄**参考 `SecurityConfig` 的「可选+精确单值+默认空即不校验」。常开 + **aud 必须解析到某个已知租户**（`client-tenant-map` 显式映射优先，`activity-{tenant}-cid` 家族反解兜底），未知/家族外 aud → 拒（401）。安全依据：aud 由 Casdoor 绑定到已认证 client + 独立 secret，不可伪造。
  - **不锁死 demo**：引 `oauth2-resource-server` 会把 Spring Security 带上 classpath，故 auth 关时提供 `PermitAllSecurityConfig`（全放行，Step1~18/静态页/h2-console 不受影响）；auth 开时 `ActivityResourceServerConfig` 只护 `/activity-marketing/**`。
  - **离线验证（48 绿）**：`AudienceTenantResolutionTest`(6，aud→tenant map/pattern/未知拒) + `JwtTenantFilterTest`(4，aud→tenant + 信封校验) + `ActivityAuthIntegrationTest`(4，**本地 RSA 自签 JWT 跑真 Security 链 + MockMvc，token 刻意 owner=admin 复现实测**：无 token→401、未知 aud→401、acme 建活动 beta 看不到、信封≠租户→403)。
  - **真 Casdoor 面（已收口，2026-07-19）**：app 以 `auth.enabled=true` 起在 :8099 → `JwksWarmupRunner` **从真 Casdoor `/.well-known/jwks` 取到 1 个签名公钥**（app 确实绑定真 Casdoor 密钥）；**真 token 端到端 12/12 绿**（见上 ✅ 项）：`scratchpad/casdoor-m2m-verify.sh` 自跑造应用+铸真 token+跨租户冒烟全通过，`activity-acme-cid`/`activity-beta-cid` M2M 应用已在 Casdoor 注册（幂等，可复跑复验）。
  - **改动文件**：pom 加 `spring-boot-starter-oauth2-resource-server`；新增 `tenant/OwnerBoundAudienceValidator`、`JwtTenantFilter`、`ActivityResourceServerConfig`、`PermitAllSecurityConfig`；`TenantProperties` 加 `Auth` 段；`MultiTenancyConfig` 的 header 过滤器改 `@ConditionalOnProperty(auth.enabled=false)`；`application.yml` 加 `activity.tenant.auth`；新增测试 3 个。
  - **前端（P0-4/P0-3 dev 档）**：`activity.js` 加租户切换条（`X-Tenant-Id` 写进所有请求 + localStorage 记忆 + acme/beta/__dev__ 快捷），切租户即换数据视图，浏览器里可见隔离。auth 档需前端登录换 token（授权码+PKCE，抄 auth-console），本 demo 未接前端登录，记为后续。
  - **改动文件**：pom 加 `oauth2-resource-server`；新增 `tenant/AudienceTenantResolver`、`AudienceTenantValidator`、`JwtTenantFilter`、`ActivityResourceServerConfig`、`PermitAllSecurityConfig`、`JwksWarmupRunner`；`TenantProperties` 加 `Auth`；`MultiTenancyConfig` header 过滤器 `@ConditionalOnProperty(auth.enabled=false)`；`application.yml` 加 `activity.tenant.auth`；前端 `activity.js`/`activity.css` 加租户条；测试 `AudienceTenantResolutionTest`/`JwtTenantFilterTest`/`ActivityAuthIntegrationTest`。
  - **未做/延后**：P1-12 **部分落地**（✅ JWKS 启动预热 `JwksWarmupRunner` + ✅ 有界 fetch 超时 `jwks-fetch-timeout-ms`；⏳ last-good 缓存——Casdoor 抖动时用旧密钥集，需自定义 outage-tolerant JWKSource，剩余项）；控制台人流（授权码+PKCE 登录、SpiceDB `@CheckAccess` 判权、P1-8 四眼）——P0-3 只做**机器决策平面**的身份，控制台平面与细粒度授权是后续。

### Track B 评审修复轮（codex-review 独立审查 → 逐条核验 → 修，2026-07-19，55 测试绿）

用户裁决「连生产硬化一起做」。Codex 独立审出 P0×4/P1×10/P2×1，逐条核验后修如下（含实证结论）：

- **[✅] 保留哨兵不可外部触达**（原 P0）：`__no_tenant__` 曾能经 header 正则 / aud 模板反解触达孤儿行 → `TenantContextFilter` 拒绝该保留值 header(400)、`AudienceTenantResolver` 剔除解析出的哨兵。
- **[✅] 多 aud 身份歧义 → 拒**（原 P0）：resolver 改为「恰好解析出一个租户才可信」，多 aud 解析到不同租户 → 空（拒）；map/pattern 跨 aud 也统一。
- **[✅] JwtTenantFilter fail-closed**（原 P1）：解析不出租户从「pass-through 放行」改为 **403 拒绝** + 清理，拒绝分支不残留 ThreadLocal。
- **[✅] bulk update 租户隔离已实证**（原 P1）：新增 `bulkUpdateIsTenantScoped` 直测 `softDeleteVersion` —— **@TenantId 确实给 bulk JPQL update 加租户谓词**（跨租户影响 0 行），此前"未验证"点闭环。补跨租户写测试（B 编辑/上下线 A → fail-closed）。
- **[✅] 生产 DB 硬化**（原 P1）：10 实体热点索引一律以 `tenant_id` 打头；`activity_manage` 加 `(tenant_id,request_id)` 唯一约束 + `saveAndFlush` 捕获 `DataIntegrityViolation` → 并发重复 requestId 由 DB 兜底转 409（非仅 check-then-insert）；requestId 只在新建落，避免版本化编辑撞唯一约束。
- **[✅] schema/缓存接 tenant**（原 P1）：create/preview/field-dict 改用当前租户（`TenantContext`）而非 `DEFAULT_TENANT`；`RuleSchemaRegistry` 支持 per-(tenant,bizLine) 覆盖（`register`，默认回落共享 schema）；DRL 缓存 key 显式含 tenant（`tenant + DRL`）。
- **[✅] 决策/控制台分权 seam**（原 P1）：加 `JwtAuthenticationConverter`（scope→SCOPE_*、groups→权限）；`console-write-authority` 配了则 create/status 需该权限（默认空=仅 authenticated，不破坏 demo）。真正 scope 分权需 M2M 应用按最小权限发 scope。
- **[✅] 诚实化**（原 P1/P2）：JWKS「预热」措辞改成「连通/fail-fast 自检」（不预热 decoder 缓存，如实）；清 owner 残留注释/文档 + 删 `OwnerBoundAudienceValidator` 引用；`application.yml` 明标「dev-run 档 header 即权威、非 fail-closed，生产须关 dev-default + 开 auth」。
- **显式仍延后**（生产大件，非 demo 一轮能做完，文档标注）：P0-5 内存容量模型；前端 Casdoor 登录（授权码+PKCE，auth 档浏览器控制台才可用）；schema-per-tenant 的字段编辑系统（现只留 `register` 口）；last-good JWKS 缓存（P1-12 剩余）；租户注销 runbook（P1-16）。~~真 Casdoor 端到端仍待用户 `!` 跑脚本收口~~ → **已收口（2026-07-19 12/12 绿）**。

### Track B 测试轮（qa-test 黑盒 + codex-test 单测，2026-07-19，70 测试绿）

- **qa-test（黑盒 API，15/15 绿）**：dev/header 档全链路真发请求——field-dict、创建、列表隔离、优惠验证隔离、详情越权 fail-closed、非法/保留哨兵 header 400、dev-default 回落。**过程发现并修复 1 个 P0 回归 BUG-1**：auth 档 `JwtTenantFilter` 的 fail-closed(403) 误挂全链，把 health/其它 Step/静态页也 403 → 安全链拆两条（@Order1 `securityMatcher("/activity-marketing/**")` 只护活动端点、@Order2 兜底 permitAll）。报告 `docs/qa/activity-multitenant-0719/`。UI Playwright 本环境不可用，如实登记（结构性验证前端资源 + 租户条代码存在）。
- **codex-test（Codex 独立设计 → Claude 核验 → 修 → 验收）**：Codex 出 9 疑点，核验后 **7 个真 bug 修生产 + 加测试**：ISSUE-01（dev-default 绕过校验 + resolver 可 null）、02（filter 早退不清 ThreadLocal）、03（aud map value 未校验/`__single__` 未保留）、05（空白 requestId 撞唯一约束）、06（所有 DataIntegrityViolation 误判并发重复）、09（null 模板元素 NPE）、04（field-dict bizLine 维度）。收口手段：新增 `TenantIds`（统一 grammar+保留值）；`ActivityIdempotencyTest`/`TenantIdentifierResolverTest`/`RuleSchemaRegistryTest` + 扩 resolver/filter 测试。**ISSUE-07（编辑后幂等重放）延后**（需独立幂等表，demo over-scope，现 at-most-once 409 可接受）。详见 `docs/tests/tenant-multitenancy-0719-0252/TEST_PROGRESS.md`。
- **测试基线**：`./mvnw test` = **70 绿**（25→48→55→70，逐轮加固）。

### Track B · P0-5 多租户内存容量模型（PERF-1）—— ✅ 本轮落地（2026-07-19，79 测试绿，BUILD SUCCESS）

用户裁决「先收口 P0-3，再直接开 P0-5」。P0-5 是评审自标「生产大件、非 demo 一轮能做完」，本轮做**可实测 + 可操作的核心**，生产尾项如实延后。

- **✅ 实测堆预算表（杀「无 sizing 数学」）**：`ActivityKieBaseSizingTest`（gated `-Dsizing=true`，GC-delta 采样）实测单 KieBase 保留堆 + Metaspace。**铁证 PERF-1**：足迹随**生成规则数**（≈ ladder 总档位）走、**不随活动数**——「1 活动×200 档」(200 规则,5374KB) ≈「10 活动×20 档」(200 规则,5210KB)，而「50 活动×1 档」(50 规则) 仅 1358KB。按活动数计权会把前者当 1、**低估 ~20×**。
- **✅ weigher 修复（修系统性误估 bug）**：`ActivityRuleRuntimeService` 的 `maximumSize(500)`（按**个数**）→ `maximumWeight` + weigher=**按实测足迹**（`260KB + 37KB×生成规则数`，含堆+Metaspace）。预算配置化 `activity.marketing.rule-engine.cache-max-weight-kb`（默认 256MB）。加 `cacheWeightKb()` 可观测 + `countRules/footprintKb` 纯函数。
- **✅ Metaspace 入模型**：每 KieBase 自带 classloader → 生成 rule 类落 Metaspace（实测 ~12KB/规则，堆:meta≈2:1）；合并计权 → 单预算封顶两池；生产须配 `-XX:MaxMetaspaceSize ≥ 共享基础设施~80MB + 预算 Metaspace 份额`。
- **✅ 回归锁 + 文档**：`ActivityCacheWeigherTest`（3 绿，常驻套件，断言「同规则数不同活动切分→权重相等」「200 档 >15× 单档」防退回按个数计权）；容量模型全文 = `docs/plans/activity-engine-platform-0718/50-P0-5-memory-capacity-model.md`（预算表 + `heap=f(tenants,activities,tiers)` 公式 + Metaspace + 公平份额设计 + 异步接缝 + 诚实延后）。
- **接缝已留（机制延后）**：single-flight 确认（Caffeine `get(k,fn)` 天然同 key 只编译一次）；「双冷 miss 不阻塞热路径」的异步限速编译线程池 + per-tenant 可抢占公平份额淘汰 = **设计固化、实现延后**（见 doc §5/§6）。
- **显式延后（生产大件）**：目标租户数负载测试（堆预算+P99）、churn heap-dump 验 classloader/Metaspace 回收、独立异步编译线程池实现、per-tenant 可抢占淘汰实现、`DecisionSnapshotRegistry`（demo 未实现，无法测其单项保留堆）。
- **改动文件**：改 `engine/ActivityRuleRuntimeService.java`（weigher+config+可观测）、`application.yml`（`cache-max-weight-kb`）；新增测试 `ActivityKieBaseSizingTest`（gated 测量）/`ActivityCacheWeigherTest`（回归锁）；新增 doc `50-P0-5-memory-capacity-model.md`。`./mvnw test` = **79 绿**。

### Track B 余下项收尾轮 —— ✅ demo 可落地全实现 + 生产尾项设计（2026-07-19，88 测试绿，BUILD SUCCESS）

用户裁决「Track B 余下的都做，自排优先级」。按 安全/正确性 > 韧性/护栏 > 限流 > 性能机制 > 前端 > 生产文档 排序，demo 可落地的全做实（带测试），生产大件（依赖 artifact/控制台人流/压测环境）做设计+文档+接缝。

- **[✅] P1-14 反查归属 IDOR（SEC-5）** — 核验即已完成：`@TenantId` 机制把 getDetail/edit/changeStatus/bulk 的 activityId 反查全部租户作用域化，`TenantIsolationTest` 已覆盖跨租户 fail-closed（越权取详情/编辑/上下线/bulk 全拒），P0-3 冒烟亦证。无需新代码。
- **[✅] ISSUE-07 编辑后幂等重放** — 新增独立幂等表 `activity_idempotency`（`@TenantId` + `(tenant_id,request_id)` 唯一），create/edit 统一「查表命中即返回首次结果、成功后同事务登记」，解耦幂等与版本化行的唯一约束——**编辑重放不再无限 version+1**。并发相同 requestId 撞唯一约束→整事务回滚(无孤儿)→409。测 `ActivityIdempotencyTest.editReplay_*`（+扩现有 3 例仍绿）。改 `ActivityMarketingService` + 新 `ActivityIdempotencyEntity/Repository`。
- **[✅] P1-17 per-artifact fire 上界（PERF-4）** — 决策 `run()` 挂 `FireCeilingListener`：fire 超 `base+perRule×编译规则数`（**per-artifact，非全局常量**）即 `halt()` 停火 + warn，`safeRun` 兜底回退旧逻辑。配置化 `max-fires-base/per-rule`。测 `ActivityFireCeilingTest`（充裕→非null、上界=0→halt→null）。
- **[✅] last-good JWKS（P1-12 剩余）** — 新 `OutageTolerantJwks`：Nimbus `JWKSourceBuilder` 组 cache+retrying+**outageTolerant**，Casdoor 抖动/不可达时用**上次成功密钥集**续验签（不误 401、不阻塞），窗口过后才 fail-closed。配置 `jwks-cache-ttl-ms/jwks-outage-ttl-ms`。测 `LastGoodJwksTest`（本地 HttpServer 模拟 Casdoor 挂→仍验签 + 对照组 plain decoder 挂时失败）。接进 `ActivityResourceServerConfig`。
- **[✅] P1-13 每租户限流（PERF-6，demo 切片）** — 新 `TenantQuotaService`（进程内 per-tenant token bucket，Caffeine 有界桶）+ `TenantRateLimitConfig`（`@ConditionalOnProperty` 挂 `/activity-marketing/**`，超配额 429）。配置 `activity.tenant.quota.*`（默认关，不改 demo）。测 `TenantQuotaServiceTest`（突发/拒/按租户隔离/随时间补充/未启用放行）。**生产须换 Redis token bucket** + 定义 Redis 宕机开/闭（见收尾 doc §4）。
- **[⏳ 设计+文档] 生产尾项**（`51-track-b-remaining-and-production-tail.md`）：P0-5 异步编译池+可抢占公平份额+压测/heap-dump 验收、P1-15 PK online-DDL+回滚锚点、P1-16 租户注销 runbook+PII、P1-8 四眼(应用层，依赖控制台人流)、P1-9 pinned-schema(依赖 artifact 系统)——设计固化、接缝已留，依赖 artifact/控制台/压测环境，非单机 demo 一轮能做完。
- **[⏳ 设计] 前端 OIDC 登录**（`52-frontend-oidc-login-design.md`）：授权码+PKCE 实现级设计（PKCE/authorize/callback/Bearer/租户从 aud/silent refresh，含 activity.js 代码骨架）。**未落码**：本环境无法浏览器 E2E + 依赖未建的 Casdoor SPA 应用与控制台人流，落地时机 = 控制台人流启动时。
- **测试基线**：`./mvnw test` = **88 绿**（70→79→82→88，逐项加固，0 失败）。改动文件：`ActivityMarketingService`/`TenantProperties`/`ActivityResourceServerConfig`/`application.yml` + 新增 `ActivityIdempotencyEntity`/`ActivityIdempotencyRepository`/`OutageTolerantJwks`/`TenantQuotaService`/`TenantRateLimitConfig` + 测试 `ActivityFireCeilingTest`/`LastGoodJwksTest`/`TenantQuotaServiceTest` + 扩 `ActivityIdempotencyTest` + 文档 `51/52`。

- **[✅ 收口] P0-3 真 Casdoor 端到端冒烟**（`casdoor-m2m-verify.sh` 打 :8099，12/12 绿，2026-07-19）。
- **[✅ 落地] P0-5 多租户内存容量模型**（实测预算表 + weigher 足迹加权 + 容量模型 doc，生产尾项如实延后）。
- **[✅ 落地] Track B 余下项**：P1-14/ISSUE-07/P1-17/P1-12(last-good)/P1-13 全实现（88 绿）；生产尾项(P0-5 异步/P1-15/16/8/9)+前端 OIDC 已出实现级设计。

### Track B 解锁轮 —— ✅ 把「有依赖」的解锁点能做的全做了（2026-07-19，98 测试绿，BUILD SUCCESS）

用户裁决「解锁点能做的都做，剩的能做的也都做，自排优先级」。三个"基础"里能在 demo 落地+测试的做实现，纯生产运维（真库在线迁移/浏览器 E2E）给可跑脚本+runbook。

- **[✅] P1-8 四眼职责分离（控制台人流可落地核心）** — 新 `ActorContext`（auth 档=JWT `sub` / dev 档=`X-Actor` header，由 `JwtTenantFilter`/`TenantContextFilter` 落值）；`activity_manage` 加 `submitted_by`（本版本提交人）；`changeStatus→ONLINE` 时若 `four-eyes-enabled`（默认关）则要求审批人身份存在且 ≠ 提交人（提交人不能自审自发，fail-closed）。测 `ActivityFourEyesTest`(4)。
- **[✅] P0-5 负载测试 + heap-dump churn 验收（兑现延后验收）** — gated `-Dsizing`。①负载：20 租户×(资格10规则+阶梯20档)=40 KieBase，**实测常驻堆+Metaspace / 生产公式预测 = 1.09**（模型可外推、量级对）；②churn：12 轮×30 KieBase 反复建/弃，Metaspace 仅涨 ~2.4MB（泄漏会 100MB+）→ **classloader/Metaspace 回收确认**。测 `ActivityCapacityAcceptanceTest`（预测用生产同款 `footprintKb`）。
- **[✅] P0-5 发布异步预热 + 独立编译线程池** — `ActivityRuleRuntimeService` 加限速编译池（与决策线程隔离，`CallerRunsPolicy` 兜底）+ `warmAsync(tenant,drl)`（single-flight），`@PreDestroy` 关池；发布(ONLINE)按 artifact 冻结 DRL 异步预热，冷编译不落决策热路径。测 `ActivityWarmTest`(2)。
- **[✅] artifact 系统切片 + P1-9 pinned-schema** — `RuleSchemaRegistry` 加 `schemaVersion`（字段集确定性哈希）+ `fieldsBrokenAgainstCurrent`；新不可变 `activity_artifact`（`@TenantId`，pin schema 版本+引用字段+冻结 DRL，状态 ACTIVE/NEEDS_REBUILD/RETIRED）+ `ArtifactService`（create 时冻结 `snapshot`、发布时 `warmOnPublish`、schema 删字段/改类型时 `revalidateOnSchemaChange` 硬失效标 NEEDS_REBUILD，不静默沿用旧 pin）。测 `ActivityArtifactTest`(2)。
- **[✅ 脚本+runbook] P1-15 热表 online-DDL**（`53-P1-15-online-ddl-runbook.md`）— 真 DDL（PK 升维 `(tenant_id,biz_line)`）+ gh-ost/pt-osc 在线迁移命令 + 回滚锚点（schema tag+快照+保留旧表+只读双跑+rename 回滚）。本项目新表已 `@TenantId`+复合索引无升维负担，接缝(key 带 tenant)已规避最高危返工。真库执行需 DBA 值守，不在 demo 跑。
- **仍属设计（硬依赖未消）**：前端 OIDC 浏览器 E2E（需真 Casdoor SPA 应用 + 浏览器，`52-*.md` 已出实现级设计）；P1-16 租户注销 runbook（`51-*.md §3`，需 artifact/审计编排）。**四眼的后端已做**（P1-8），前端登录人流是其 UI 前置。
- **测试基线**：`./mvnw test` = **98 绿**（88→98，0 失败）。新增 `ActorContext`/`ArtifactService`/`ActivityArtifactEntity`+Repo + `warmAsync`/编译池 + `schemaVersion`/`submitted_by`；测试 `ActivityFourEyesTest`/`ActivityCapacityAcceptanceTest`(gated)/`ActivityWarmTest`/`ActivityArtifactTest`；文档 `53`。

- **[⏳ 仅剩真外部依赖]**：前端 OIDC 浏览器 E2E（真 Casdoor SPA 应用+浏览器）、P1-16 租户注销演练（生产编排）、P1-15 真库在线迁移执行（DBA+低峰窗口）——均已出脚本/设计/runbook，只差外部环境执行，无 demo 内可再做的实现。

#### Track B 新窗口启动指令（复制到新窗口首条消息）

```
读 docs/plans/activity-engine-platform-0718/review-handoff.md，Track A 已全部落地（见「落地进度」）。
现在做 Track B 多租户：按 §① 的 P0-3 / P0-4 / P0-5 + §「执行建议」推进，先 P0-4 租户隔离机制化最自包含。
关键接缝 Track A 已留好：RuleSchemaRegistry.resolve(tenant,bizLine) 的 DEFAULT_TENANT 常量、
DRL 缓存 key 已含 tenant 维度、ActivityQueryService.buildContext。tenant 来源二选一先跟我确认：
真接 auth-platform/Casdoor（P0-3）还是本地 TenantContext(header)stub。
每完成一项：改代码 + 真跑 ./mvnw test（现基线 25 绿）+ 回写本文「落地进度」。
```

**Track B 起手前必读**：本文 §① P0-3~5（问题+处置+验收）、§③ 涉及文件、§④ 已确认无需改的点（避免重复纠结）、后端 `../activity-engine-backend-0718-2111/FINAL_PLAN.md` §17.7（auth 接入）。`auth-platform` 在本机 `/Users/liruijun/personal/LLM/auth-platform`。现 demo 无任何 auth/tenant 脚手架（greenfield），10 个 Entity 待加 tenant_id。

## 0. 背景与必读

把学习脚手架 `com.lrj.drools.activity` 演进成**多租户 · 元数据驱动的营销活动决策 SaaS**(决策中台 + 运营控制台;电商 + 出行[司机激励]双业务线验证通用性;鉴权接入既有 `/Users/liruijun/personal/LLM/auth-platform`)。

先读(同目录):`40-ROADMAP-AND-DECISIONS.md`(路线图 + 决策 D1-D13 + 落地闸)、`11-generalization-architecture.md`(通用化架构,**顶部有评审吸收注**)、`12-auth-platform-integration.md`(auth 接入)、`41-REVIEW-FINDINGS-generalization.md`(评审全文逐条证据)、后端 `../activity-engine-backend-0718-2111/FINAL_PLAN.md`(**§17 = 通用化+多租户增量,§17.8 = 评审吸收**)。

**总体裁决**:骨架/方向对且诚实,承重论证(机器复用/单例免索引/同类合并不破)经核验站得住;但当前"**策略充分、机制缺位**"。命门 Map fact 的**正确性 + 性能已 spike/基准实证通过**(不用退 declared-type);**其余 P0 集中在安全/隔离/内存的"机制"上**。

**两个已跑的验证(spike 在 `scratchpad/spike/`,throwaway,可复跑/扩展)**:
- **正确性 spike**:14/14 编译+执行通过——方法左值 `numberAttr(...)>=`、`textAttr(...)==/in`、`listAttr(...) contains/not contains`、containsAny、`between`、`not(...)` 淘汰包裹、出行 `completedTrips` 阶梯全绿。**结论:Map 通用 fact 选型 (a) 成立。**
- **性能基准**:最坏形状(1 ctx + 10 候选 + M 档)下 typed 与 map **都线性 O(M)**;map 只多付 ~0.12µs/档、200 档 1.15x,**非悬崖**,真实规模可忽略。成本驱动是**规则数**非 fact 表示法。

---

## ① 需要改的问题清单(按优先级,含文件+行号 / ② 修改方向 / 验收标准)

> 优先级:**P0 = 多租户落地前必过的闸**;P1 = 重要,实现期必做;P2 = 增强/收尾。标注 `[设计已改]` 表示对应设计文档已按评审修正,实现时照新版落即可。

### P0

**P0-1 · Map fact 通用化改造(命门,已实证可行,现在是 BUILD 任务)**
- 文件:`domain/RuleField.java`(硬编码 6 字段枚举 → 每 (tenant,bizLine) schema 注册表)、`domain/FieldValueType.java`(仅 NUMBER/STRING/ARRAY → 加 ENUM+候选值)、`domain/ActivityRuleContext.java`(固定 typed POJO → Map 支撑 `RuleContext` + `numberAttr/textAttr/listAttr/boolAttr`)、`engine/RuleConditionTranslator.java:57`(`RuleField.fromKey` → schema 查表)、`:65`(`field.factField()` 直属性 → 方法左值访问器)、`engine/ActivityDrlBuilder.java:165`(ladder `orderAmount` → 参数化 `<ladderField>`)。
- 修改方向:枚举白名单 → 数据驱动 schema(**仍白名单、仍 fail-closed、运营永不写 DRL**);翻译器 emit 形态照 spike 已验证的方法左值约束。
- 验收:现有 10 个 activity 测试在 typed→Map 改造后仍绿(把旧 typed 作回归基线对齐金额语义);spike 里 14 种形态是真实 emit 的子集,可直接引 spike 复跑。

**P0-2 · 否定运算符 fail-OPEN 存在性护栏(spike 已验证修复有效)** `[设计已改 §17.4]`
- 文件:`engine/RuleConditionTranslator.java:70`(NE)、`:82`(NOT_IN)、`:84`(NOT_CONTAINS)。
- 问题:缺字段时 `null not in(黑名单)` 判 true → 候选不淘汰 → **放行=静默超发**,违背 D1。
- 修改方向:所有否定运算符 emit `(field != null && <否定约束>)`;Map fact 归一化区分"键不存在"与"值 null"。
- 验收:spike 已证 `field != null && field not in(...)` 缺字段时**不 FIRE**(fail-closed);补测:缺字段 + notIn → 候选被拒非放行。

**P0-3 · M2M 决策身份不可伪造(SEC-1/SEC-3)** `[设计已改 §17.7 / 12]`
- 文件:决策鉴权过滤器(新建,替 §6.2 `ActivityApiKeyFilter`);`auth-platform/deploy/casdoor-tenant-provision.sh:54,78`(共享 secret 事实源)。
- 问题:Shared Application 派生 client 共用**同一 secret**,派生 client_id 可猜 → 任一租户可换出 `owner=别租户` 的 token → 无判权的决策热路径跨租户冒充;且 owner 是否写入 client_credentials token **未证实**。
- 修改方向:M2M **每租户独立注册 Casdoor Application(独立 client_id + 唯一 secret),禁用共享 secret 派生**(Shared Application 仅控制台人用);**自写 audience 校验器**(常开、前缀家族匹配 + `owner↔aud` 绑定),**不抄** auth 参考 `SecurityConfig` 的默认空 aud;决策信封 `tenantId` 只作校验(须=token.owner 否则 403),绝不作租户来源。
- 验收:冒烟"租户 A 的 secret 换不出 owner=B 的 token"必须失败;实测 Casdoor client_credentials token 是否写 `owner`(不写则退 client→tenant 映射表,不默认放行);aud 校验器拒家族外 token。

**P0-4 · 租户隔离机制化(SEC-2)**
- 文件:所有 `persistence/*Repository.java`(~40 方法无租户维度)、`service/ActivityPoolMatchService.java:60`(裸 `findAll()`)、`:72-96`(跨表 join 无租户)。
- 问题:隔离**只有纪律没有机制**,靠人肉记得加 where,漏一处=串租户=SaaS 致命事故;grep `tenantId/TenantContext/SecurityConfig` 零命中(全 greenfield)。
- 修改方向:enforcement 下沉 ORM——Hibernate 6 `@TenantId`(discriminator 自动追加租户谓词)**或** DB RLS + `@Filter` 常开;仓库基类/切面 `TenantContext` 未设即 fail-closed;**裸 `findAll()`/跨表查询列 CI 阻断**;`activity_benefit_type` 全局行(tenant_id 空)作显式例外(`=? OR IS NULL`)单独测。
- 验收:无任何查询不带租户谓词;跨租户读测试 fail-closed;CI 规则阻断裸 `findAll()`。

**P0-5 · 多租户内存容量模型(PERF-1)**
- 文件:`engine/ActivityRuleRuntimeService.java:41`(无界缓存)、`DecisionSnapshotRegistry`(设计 §5.3,新建)。
- 问题:自标"命门"却**通篇无 sizing 数学**;用"规则数"当 KieBase 权重会系统性误估(ladder 每档一个 alpha 节点主导);Metaspace 未入模型;噪声邻居没真封顶。
- 修改方向:产出堆预算表(单 snapshot 项 + 单 KieBase 实测保留堆,JOL/heap dump 抽样)+ `heap=f(tenants,activities,tiers)` 公式 + `per-tenant-max-weight`=公平份额(定义大租户能否借用空闲配额+可抢占淘汰)+ **Metaspace 入模型**;独立限速编译线程池承接预热/重建,**双冷 miss 不阻塞热路径**(异步重建期 serve last-known 或显式降级)。
- 验收:堆预算表存在;目标租户数下负载测试在堆预算 + P99 内;淘汰 churn 下 heap-dump 验 classloader/Metaspace 回收。

### P1

**P1-6 · 无界 DRL 缓存替换(PERF-10)**:`engine/ActivityRuleRuntimeService.java:41` 无界 `ConcurrentHashMap` 永不淘汰 → 有界缓存(Caffeine,key 含 tenant)是通用化**同步前置**非后续优化(否则 Track A 就是更快的 OOM)。验收:缓存有界 + 权重淘汰 + 命中率指标。

**P1-7 · trace 构建期按 explain 关(PERF-11)**:`engine/ActivityDrlBuilder.java:58,67,106,139,151,173,191` 每条 RHS `result.trace(...)` 与 explain 无关地攒串;改为 explain 开关在**构建期**决定是否 emit trace,非响应期过滤。验收:explain=false 时执行层不攒 trace。

**P1-8 · ReBAC 做不了 D2 四眼 → 应用层(SEC-4)** `[设计已改 §17.8]`:`recsys.zed` 只给粗粒度角色分离,四眼/自审阻断是动态主体约束(要写活动级 `edited_by` 元组,违反"高频子资源不写元组")。改:职责分离在**应用层**强制(`ActivityWorkflowService` 持久化提交人,`actor==submitter` 拒 approve/publish)。验收:提交人=审批人时拒绝。

**P1-9 · schema 演进 pinned-schema 校验 + 硬失效(COR-2/PERF-7)** `[设计已改 §17.4]`:信封校验必须按 artifact 的 **pinned schema** 非 live schema(否则冻结 artifact 跑不到);删字段/改类型**硬失效**引用它的 artifact(标"需重建/退役"),缺字段显式分类(拒绝/降级带 reason),禁止静默淘汰。验收:改 schema 后旧 artifact 行为可预测、不静默改金额。

**P1-10 · 跨异构 benefit 合并 MVP 不支持(COR-3)**:`domain/ActivityRuleResult.java:16-38` typed `hitAmount/gifts` → `List<BenefitOutcome>`(保留兼容投影,**同类折扣合并不受影响**——见 ④-3);现金+赠品+折扣同场命中合并语义 **MVP 明确不支持**(单场景单 benefit-type),别当已解。验收:文档标注 + 两条验证线不触发异构合并。

**P1-11 · `11` 鉴权部分作废(COR-4)** `[设计已改:11 顶部注]`:`11:348`(X-Api-Key)、`:448`(TenantContext 过滤器/`activity_api_client`)已被 §17.7 推翻;实现时**照 §17.7 接 auth-platform**,别建自管 API Key 基座。

**P1-12 · JWKS 轮转热路径兜底(PERF-5)**:决策 resource-server 启动**预热 JWKS** + 短的有界 fetch 超时 + 缓存 **last-good JWKS**(轮转/Casdoor 抖动先用旧集验签);把"JWKS 不可达如何降级"写进 fail-open。验收:Casdoor 抖动时决策不阻塞在 JWKS 拉取。

**P1-13 · 分布式限流基座(PERF-6)**:`TenantQuotaService`(设计 `11:452`)只有类名。每租户 QPS 在无状态多实例下需明确基座(网关层近似 **或** Redis token bucket + **计入延迟预算 + 定义 Redis 宕时开/闭**)。验收:限流基座选型明确 + 降级定义。

**P1-14 · 反查归属 IDOR(SEC-5)**:activityId→bizLine/tenant 反查必须从**资源权威行**取作用域 + 本身带租户过滤 + **不信** body 的 parent/bizLine。验收:用自己作用域操作别租户 activityId → fail-closed。

**P1-15 · Track B PK 改造 + 回滚锚点(PERF-8)**:pointer PK `bizLine`→`(tenant_id,bizLine)` = 热表锁表 DDL(M2 后有流量),预留 online-DDL(gh-ost/pt-osc)+ 定义 Track B 回滚锚点;**接缝**:所有缓存/registry/查询 key 从 Day1 带 tenant(单租户时常量),否则最高危返工。验收:Track B 有回滚路径 + 无 key 缺 tenant。

**P1-16 · 租户注销级联清理 + PII(SEC-6)**:GA 前补 runbook——级联清 ~13 表 + 不可变 artifact/manifest/decision-log/audit + KieBase/snapshot 缓存 + SpiceDB `<tenant>_*` 元组 + Casdoor org;PII(driverId 等)留存/擦除策略。验收:租户注销可演练。

**P1-17 · 护栏阈值 per-artifact(PERF-4)**:`FINAL_PLAN §8.2` `max-fires-per-request` 全局占位符 → 由 artifact/manifest 编译期规则数派生(per-tenant 上界),非全局常量(基准已证成本随规则数线性)。验收:大租户不因全局 maxFires 误撞降级。

### P2

**P2-18 · ladder 泛化波及面(COR-6)**:除 `ActivityDrlBuilder:165` 外,`LadderActivityDef` record 加 `ladderField` + `buildLadderDrl` 签名 + `evalLadder`(`ActivityRuleRuntimeService.java:60`)+ 上游校验 ladderField 是该 schema 的 NUMBER 字段。`LadderRangeParser` **确认一行不改**(见 ④-4)。
**P2-19 · emit 规范 key(COR-7)**:`RuleConditionTranslator.java:65` 必须 emit schema 存储的**规范 key**,绝不把 `leaf.getField()` 用户输入原样拼进 `numberAttr("...")`。
**P2-20 · DATE 别提前(COR-8)**:`FieldValueType` MVP **只加 ENUM**(BOOLEAN 零成本可顺带);DATE 推迟到真采用 declared-type 时,因选型 (a) 恰做不好 DATE 区间。
**P2-21 · activityId 未转义拼 DRL(SEC-7)**:`ActivityDrlBuilder.java:54,58,167,171` 把 activityId 直接拼进规则体/名/trace;**所有**拼进 DRL 的标识符(访问器 key、ladderField、selector、activityId)过**同一**审计正则发射器(`^[A-Za-z0-9_]+$`);activityId 保持系统生成。
**P2-22 · O(N²) 自连接兜底上限(PERF-12)**:`ActivityDrlBuilder.java:102,116-118` 折扣 MAX/PRIORITY 自连接 O(N²);`FINAL_PLAN §5.3` "全量载入"兜底对大租户是陷阱 → 设活动数上限,超限强制要求 selector。
**P2-23 · 逃生舱迁移非零成本(COR-9)**:文档纠正——(a)→(c) declared-type 迁移时翻译器 emit **必改**(方法调用 vs 属性访问),别画成零成本。

---

## ③ 涉及的文件清单

**现有代码(改造点)**
- `src/main/java/com/lrj/drools/activity/domain/RuleField.java` — 枚举→schema(P0-1)
- `src/main/java/com/lrj/drools/activity/domain/FieldValueType.java` — 加 ENUM(P0-1/P2-20)
- `src/main/java/com/lrj/drools/activity/domain/ActivityRuleContext.java` — typed→Map fact(P0-1)
- `src/main/java/com/lrj/drools/activity/domain/ActivityRuleResult.java` — →List<BenefitOutcome>(P1-10)
- `src/main/java/com/lrj/drools/activity/engine/RuleConditionTranslator.java` — schema 查表 + 方法左值 emit + 否定护栏 + 规范 key(P0-1/P0-2/P2-19)
- `src/main/java/com/lrj/drools/activity/engine/ActivityDrlBuilder.java` — ladder 参数化 + trace 构建期 + activityId 转义 + 自连接上限(P0-1/P1-7/P2-21/P2-22)
- `src/main/java/com/lrj/drools/activity/engine/ActivityRuleRuntimeService.java` — 有界缓存 + evalLadder 签名(P0-5/P1-6/P2-18)
- `src/main/java/com/lrj/drools/activity/engine/LadderRangeParser.java` — **确认不改**(④-4)
- `src/main/java/com/lrj/drools/activity/persistence/*Repository.java`(全部)+ `service/ActivityPoolMatchService.java` — 租户隔离(P0-4)
- `src/main/java/com/lrj/drools/activity/service/ActivityMarketingService.java` — changeStatus 状态机/幂等(见后端 §6.1)

**新建(实现期)**:决策鉴权过滤器 + 自写 aud 校验器(P0-3)、schema/benefit-type 注册表 + 表(P0-1)、`ActivityWorkflowService` 四眼(P1-8)、`TenantQuotaService` 限流(P1-13)、`DecisionSnapshotRegistry`(P0-5)、租户注册表(替 activity_api_client)。

**设计文档(本轮已改,实现照新版)**:`activity-engine-backend-0718-2111/FINAL_PLAN.md`(§17.4/§17.5/§17.7/§17.8)、`11-generalization-architecture.md`(顶部注)、`12-auth-platform-integration.md`(M2M 段)、`40-ROADMAP-AND-DECISIONS.md`(落地闸)、`41-REVIEW-FINDINGS-generalization.md`(全文)。

**验证脚手架**:`scratchpad/spike/`(`SpikeCtx/TypedCtx/Cand/MapFactSpike/MapFactBench`)——throwaway,可复跑;正式落地建议升级为 JMH + 覆盖 in/contains 大规模 + 分配量。

**auth-platform 侧(接入方双侧清单见其 `docs/新项目接入指南.md`)**:每租户独立 Casdoor Application(P0-3)、独立 SpiceDB 实例 + `.zed`(照 `recsys.zed` 范式建 tenant→bizLine→activity)。

---

## ④ 评审中已确认「无需改」的点(别在新窗口重复纠结)

1. **Map fact 选型 (a) 正确性 + 性能均已实证**(spike 14/14 + 基准非悬崖)→ **不切 declared-type、不切 `attrs[]` 映射式**。declared-type 仅作将来要 DATE 真区间/真索引时的 ADR 逃生舱,MVP 不建。
2. **"上下文 fact 单例 → alpha/beta 索引无意义"论证成立**(正确性评审确认;基准也显示 typed 并未拿到免费索引共享,双方都线性)。
3. **同类折扣合并 MAX/MUTEX/STACK/PRIORITY 不被 `List<BenefitOutcome>` 破坏**——合并数学跑在 `ActivityCandidate.computedAmount` + accumulate 上,与 result 形状无关(`ActivityDrlBuilder.java:97-153`);`hit()/setHitAmount()` 保留兼容投影即可。
4. **`LadderRangeParser` 一行不改**——只解析 min/max/reward,与"比哪个字段"无关(grep 全模块确认 orderAmount 硬编码仅 `ActivityDrlBuilder:165` 一处)。
5. **cache-key 加租户维度枚举无漏项**——release_key=(tenant,bizLine)、artifact/manifest/pointer/decision-log/effect + KieBase key + snapshot 分区 + schema 缓存 key 都已落 tenant(安全评审确认)。
6. **字段/值层注入防线扎实**——`scalar()` 转义 + `RuleOperator.fromCode` fail-closed + MAX_DEPTH/新增 MAX_NODES/MAX_LIST_LEN + `field_key` 正则白名单 + "模板仅平台注册、运营永不写 DRL"(前提:模板解析是代码级查表,现状即是)。**注意仍要守 P2-19(emit 规范 key)+ P2-21(activityId 转义)这两处运行期防线**。
7. **"决策热路径只本地 JWKS 验签、不碰 SpiceDB"分层正确**——独立核验 auth-platform `RemoteAuthzEngine` 每次 check 是网络调用、SDK 零判权缓存、其《性能规划》明列判权缓存为反模式;此分层与 auth-platform 自身纪律一致。
8. **RHS 不读上下文 → CLAUDE.md 坑 6(RHS 无 record accessor 糖)不触发**——`RuleContext` 是 POJO 非 record,且 MVP reward 是编译期常量。**提醒**:未来若某 benefit 要 RHS 读上下文(如"每单奖励=completedTrips×单价"),discount/eligibility 场景里 context 只在 `not` CE 中**绑不出可用变量**,需补正向 `$ctx : RuleContext()` 模式。
9. **绑定变量退路可用**(spike 已验 `$t : listAttr(...), $t contains ...`)——若将来某运算符直接方法左值形态在某 Drools 版本失效,有现成退路。
10. **release/manifest/pointer/outbox/audit/护栏(Step 14 三件套)这套机器业务线无关、租户无关**——全部复用,只加 tenant/bizLine key 维度,协议不改。

---

## 执行建议(节奏)

**Track A(先做,单租户内)**:通用化(P0-1/P0-2 + P1-6/P1-7/P1-9/P1-10 + P2-18~23),用现有 typed 作回归基线对齐旧金额语义。→ **Track B(再叠)**:多租户(P0-3/P0-4/P0-5 + P1-11~17)。两个硬骨头**不同时上**(见 §17.5)。P0-1/P0-2 的正确性已被 spike 消解,可放心先做。
