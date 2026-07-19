# 交接单 · 活动引擎「通用化 + 多租户 + auth 接入」（Track A + B）

> **状态：✅ 可交接**（2026-07-19）。本文是**当前状态的自包含快照**——新窗口只读这一份即可接手。
> 历史详录（逐轮）见同目录 `review-handoff.md`；专题详情见 `50`(容量模型)/`51`(生产尾项)/`52`(前端 OIDC)/`53`(online-DDL)。
>
> **一句话现状**：把学习脚手架 `com.lrj.drools.activity` 演进成「多租户 · 元数据驱动的营销活动决策 SaaS」。
> **Track A（单租户通用化）+ Track B（多租户/auth/护栏/容量）+ 前端 OIDC 浏览器登录已全部实现并测试**（`./mvnw test` = **104 绿**，0 失败；浏览器 E2E 9/9）。
> 只剩需**真生产环境**执行的项（生产库在线迁移 / 租户注销编排 / 目标规模压测），均已备脚本/设计/runbook。

---

## 一、任务目标与验收标准

- **目标**：`com.lrj.drools.activity` → 多租户、元数据驱动、可鉴权的营销活动决策中台（电商+出行双业务线验证通用性；鉴权接既有 `/Users/liruijun/personal/LLM/auth-platform` 的 Casdoor）。
- **验收（均已达成，见 §七 验证）**：
  - [x] 通用化：Map fact + 数据驱动 schema 白名单，typed→Map 后旧金额语义回归绿。
  - [x] 多租户：`@TenantId` 机制隔离，跨租户读/写 fail-closed（**真 Casdoor token 端到端 12/12 绿**）。
  - [x] 容量：实测堆预算表 + weigher 足迹加权 + 负载测试实测/公式≈1.0 + churn 下 classloader 回收。
  - [x] 护栏：per-artifact fire 上界、四眼发布分离、每租户限流、last-good JWKS。
  - [x] 平台能力：artifact 冻结 + pinned-schema 硬失效、发布异步预热。
- **相关设计**：本目录 `40-ROADMAP`/`11-generalization-architecture`/`12-auth-platform-integration`/`41-REVIEW-FINDINGS`；后端 `../activity-engine-backend-0718-2111/FINAL_PLAN.md`（§17）。

---

## 二、评审结论落地状态（全项矩阵）

> 完整评审证据见 `41-REVIEW-FINDINGS-generalization.md`。此处只给**落地状态**。✅=已实现+测试；📄=脚本/设计已出，待真外部环境执行。

| ID | 主题 | 状态 | 落点 |
|----|------|------|------|
| P0-1 | Map fact 通用化（命门） | ✅ | `RuleSchemaRegistry`/`SchemaField`/`RuleConditionTranslator`/`ActivityDrlBuilder` |
| P0-2 | 否定运算符 fail-closed 护栏 | ✅ | `RuleConditionTranslator`（`ActivityEligibilityGuardTest`） |
| P0-3 | M2M 决策身份 / 接 Casdoor（真 token） | ✅ | `tenant/`(Jwt/Audience/ResourceServer)、`scratchpad/casdoor-m2m-verify.sh` |
| P0-4 | 租户隔离机制化 `@TenantId` | ✅ | 10+实体 `@TenantId`、`tenant/`、`TenantArchGuardTest` |
| P0-5 | 多租户内存容量模型 | ✅ | `ActivityRuleRuntimeService`(weigher+fire+编译池)、`50-*.md`、`ActivityCapacityAcceptanceTest` |
| P1-6/7 | 有界缓存 / trace 构建期 explain | ✅ | `ActivityRuleRuntimeService`/`ActivityDrlBuilder` |
| P1-8 | 四眼职责分离（应用层） | ✅ | `ActorContext`、`ActivityManageEntity.submitted_by`、`ActivityFourEyesTest` |
| P1-9 | pinned-schema 校验 + 硬失效 | ✅ | `ArtifactService`/`ActivityArtifactEntity`、`ActivityArtifactTest` |
| P1-10 | 跨异构 benefit MVP 不支持（文档） | ✅ | `ActivityRuleResult`/`BenefitOutcome` |
| P1-11 | 自管 API Key 作废 → Casdoor | ✅ | （P0-3 兑现） |
| P1-12 | JWKS 轮转兜底 + last-good | ✅ | `OutageTolerantJwks`、`LastGoodJwksTest` |
| P1-13 | 每租户限流基座 | ✅ demo 切片 | `TenantQuotaService`/`TenantRateLimitConfig`（生产 Redis 见 `51-*.md §4`） |
| P1-14 | 反查归属 IDOR | ✅ | `@TenantId` 机制（`TenantIsolationTest`） |
| P1-15 | 热表 online-DDL + 回滚锚点 | 📄 | `53-P1-15-online-ddl-runbook.md`（真库执行需 DBA） |
| P1-16 | 租户注销级联清理 + PII | 📄 | `51-*.md §3`（runbook，需 artifact/审计编排） |
| P1-17 | per-artifact fire 上界 | ✅ | `ActivityRuleRuntimeService`(FireCeilingListener)、`ActivityFireCeilingTest` |
| ISSUE-07 | 编辑后幂等重放 | ✅ | `ActivityIdempotencyEntity`/`Repository`、`ActivityIdempotencyTest` |
| 前端 OIDC | 授权码+PKCE 浏览器登录 | ✅ | `AuthConfigController`+`activity.js` OIDC 段、`scratchpad/casdoor-spa-provision.sh`、E2E `scratchpad/e2e-oidc.mjs` 9/9 绿（2026-07-19，本机 Casdoor+Playwright） |

---

## 三、待办清单（仅剩真外部依赖项）

> demo 内可实现的已全部完成。以下每项都**已备脚本/设计/runbook**，只差真外部环境执行——不是"没做"，是"不在单机 demo 内可跑"。

- [x] **前端 OIDC 浏览器 E2E**（✅ 2026-07-19 完成）：本机 Casdoor 建 SPA 公有应用 `activity-{acme,beta}-web-cid`（`scratchpad/casdoor-spa-provision.sh`，幂等）+ 测试用户 `acme/act-alice`、`beta/act-bob`；后端加匿名 `GET /activity-marketing/auth-config`（链一 permitAll + JwtTenantFilter 跳过）+ `web-client-map`（反向自动并入 aud→tenant map 级，防 `-web-` 被模板误反解成租户 `acme-web`）；`activity.js` 落码 PKCE 登录/回调/Bearer/silent-refresh/登出（`authEnabled=false` 时 dev 租户栏一行不变）。**验证**：Playwright E2E `scratchpad/e2e-oidc.mjs` 9/9（登录→Bearer 列表→UI 建活动→登出→换租户→隔离可见）+ dev 档回归 `e2e-dev.mjs` 3/3 + M2M 冒烟 12/12 不回归。注：dev Casdoor 里有一个本轮误建后无法删的闲置用户 `beta/bob`（无引用，可手动清）。
- [ ] **P1-16 租户注销演练**：按 `51-*.md §3` 编排级联清 ~13 表 + 不可变凭证退役 + 缓存/Casdoor org/SpiceDB 元组清理 + PII 擦除。需 artifact/审计系统配套。
- [ ] **P1-15 真库在线迁移**：按 `53-*.md` 用 gh-ost/pt-osc 跑热表 PK 升维（低峰窗口+DBA 值守）。demo `ddl-auto` 已用 `@TenantId`+复合索引建对新表，无升维负担。
- [ ] **P0-5 目标规模真压测**：demo 已用进程内 gated 测试验证「公式≈实测(0.97~1.09) + churn 回收」；生产按目标租户数在真集群压 P99（需压测环境）。

---

## 四、涉及文件清单（当前实现地图）

**引擎/规则**
| 文件 | 说明 |
|------|------|
| `engine/RuleSchemaRegistry.java` | 数据驱动字段白名单 + `schemaVersion`/`fieldsBrokenAgainstCurrent`（P0-1/P1-9） |
| `engine/RuleConditionTranslator.java` | 条件树→受控 Drools 约束，否定运算符 fail-closed（P0-1/P0-2） |
| `engine/ActivityDrlBuilder.java` | 生成 elig/ladder/discount/gift DRL，ladder 每档一条 rule（P0-1/P1-7） |
| `engine/ActivityRuleRuntimeService.java` | KieBase 缓存**足迹加权**淘汰 + **per-artifact fire 上界** + **异步预热编译池**（P0-5/P1-6/P1-17） |
| `domain/ActivityRuleContext.java` | Map fact + `numberAttr/textAttr/listAttr/boolAttr`（P0-1） |

**多租户/鉴权/护栏（`tenant/`）**
| 文件 | 说明 |
|------|------|
| `TenantContext`/`TenantIdentifierResolver`/`MultiTenancyConfig` | `@TenantId` 判别式多租户（P0-4） |
| `TenantContextFilter`/`JwtTenantFilter` | 租户来源：dev=header / auth=JWT aud；并落 `ActorContext`（P0-3/P0-4/P1-8） |
| `AudienceTenantResolver`/`AudienceTenantValidator` | aud→租户解析+校验，家族外拒（P0-3） |
| `ActivityResourceServerConfig`/`PermitAllSecurityConfig` | 只护 `/activity-marketing/**` 两条安全链（P0-3） |
| `OutageTolerantJwks`/`JwksWarmupRunner` | last-good JWKS + 启动自检（P1-12） |
| `ActorContext` | 操作者身份（四眼，P1-8） |
| `TenantQuotaService`/`TenantRateLimitConfig` | 每租户限流（P1-13） |
| `TenantProperties`/`TenantIds` | 配置绑定 + 租户 grammar/保留值（含前端 OIDC 公开参数 + web-client-map） |
| `AuthConfigController` | 前端 OIDC 匿名配置端点 `/activity-marketing/auth-config`（52 §2） |

**服务/持久化**
| 文件 | 说明 |
|------|------|
| `service/ActivityMarketingService.java` | create/edit（幂等表+artifact 冻结）、changeStatus（四眼+发布预热） |
| `service/ArtifactService.java` | artifact 冻结/发布预热/schema 硬失效（P0-5/P1-9） |
| `service/ActivityQueryService.java` | 决策热路径（资格→阶梯→折扣），引擎失败回退 legacy |
| `persistence/ActivityManageEntity.java` | +`submitted_by`（P1-8）；`@TenantId`+复合唯一约束 |
| `persistence/ActivityIdempotencyEntity/Repository` | 独立幂等表（ISSUE-07） |
| `persistence/ActivityArtifactEntity/Repository` | 不可变 artifact（P1-9） |

**测试（23 个）**：`Activity{Marketing*,Eligibility,Idempotency,FireCeiling,FourEyes,Warm,Artifact,Auth,CacheWeigher,KieBaseSizing(gated),CapacityAcceptance(gated)}Test`、`Tenant{Isolation,ContextFilter,ArchGuard,IdentifierResolver,Quota}Test`、`{Audience,JwtTenant,LastGoodJwks,RuleConditionTranslator,RuleSchemaRegistry,OwnerBoundAudience}*Test`。

**文档**：`50`(容量模型)/`51`(生产尾项)/`52`(前端 OIDC)/`53`(online-DDL)/`review-handoff.md`(历史详录)。

---

## 五、已确认无需改的点（别重复纠结）

- **Map fact 选型 (a) 正确性+性能已实证**（spike 14/14 + 基准非悬崖）→ 不切 declared-type、不切 attrs[] 映射。
- **上下文 fact 单例 → alpha/beta 索引收益近零**，typed 与 map 都线性。
- **同类折扣合并 MAX/MUTEX/STACK/PRIORITY 不被 `List<BenefitOutcome>` 破坏**（合并跑在 computedAmount+accumulate）。
- **`LadderRangeParser` 一行不改**（只解析 min/max/reward）。
- **`@TenantId` 已把裸 `findAll()`/反查自动加租户谓词** → P1-14 IDOR 本身已安全（`TenantArchGuardTest` 守新表必带 `@TenantId` + 禁 nativeQuery）。
- **weigher 必须按生成规则数计权，不能按活动数**（实测「1活动×200档」≈「10活动×20档」，按活动数低估 ~20×）。
- **四眼/pinned-schema 的后端已做**；剩前端登录人流是 UI 前置，非后端缺失。

---

## 六、关键约束与用户纠正

- **节奏**：用户历次裁决「一口气做完、自排优先级」；「Track B 余下的都做」；「解锁点能做的都做，剩下能做的也都做」。**demo 内可落地的做实现+测试，纯外部运维给脚本/runbook，不臆造无法验证的半成品。**
- **默认安全**：所有新开关默认关/安全值——`four-eyes-enabled=false`、`auth.enabled=false`、`quota.enabled=false`；`dev-default-enabled` 代码默认 false（仅 dev-run yml 显式开 true）。「忘了配」的方向是拒绝不是放行。
- **执行边界（本会话已澄清）**：铸真 Casdoor token / 建 IdP 应用在**本机 dev Casdoor**上合法，已自跑；真库在线迁移/浏览器 E2E 需真外部环境，归执行方。
- **项目既有坑（务必先读 CLAUDE.md）**：不加 `update($fact)`（死循环）；DRL 运行时解析，改完必启动/冒烟一次；RHS 无 record accessor 糖；MySQL 大字段用 `@JdbcTypeCode` 非 `@Lob`；`drools-bom`+`drools-xml-support`。
- **已否决**：自管 API Key 基座（P1-11，改接 Casdoor）；owner 作租户来源（改 aud，命脉实测 owner=admin）。

---

## 七、验证方式

```bash
# 全套件（当前基线 104 绿，0 失败）
./mvnw test
# P0-5 容量/负载/churn 验收（gated，不进常规套件）
./mvnw test -Dtest="ActivityKieBaseSizingTest,ActivityCapacityAcceptanceTest" -Dsizing=true \
  -DargLine="-Xmx2g -XX:+UseG1GC -XX:MaxMetaspaceSize=512m"
# 起服务（默认 :8081；h2 免 MySQL）
./mvnw spring-boot:run -Dspring-boot.run.profiles=h2
# auth 档 + 真 Casdoor 端到端冒烟（:8099，需本机 Casdoor 在跑）
./mvnw spring-boot:run -Dspring-boot.run.profiles=h2 \
  -Dspring-boot.run.arguments="--server.port=8099 --activity.tenant.dev-default-enabled=false --activity.tenant.auth.enabled=true"
bash scratchpad/casdoor-m2m-verify.sh     # provision M2M 应用 + 铸真 token + 跨租户冒烟（幂等可复跑）
# 前端 OIDC：provision SPA 应用/用户 + 浏览器 E2E（需 npm i playwright）
bash scratchpad/casdoor-spa-provision.sh
node scratchpad/e2e-oidc.mjs              # auth 档 :8099 起着时跑；9/9 绿
node scratchpad/e2e-dev.mjs               # dev 档 :8098 起着时跑；3/3 绿（auth 关前端不变回归）
```
- **判读标准**：`./mvnw test` = 104 绿；`casdoor-m2m-verify.sh` = 12/12（MINT 4 + SMOKE 8）；`e2e-oidc.mjs` = 9/9；容量测试「实测/预测≈1.0 + churn 增长<20MB」。
- **已验证**：全套件 104 绿；真 Casdoor token 端到端 12/12；**浏览器 OIDC 登录 E2E 9/9（真 Casdoor + Playwright，跨租户隔离浏览器可见）**；dev 档回归 3/3；容量 ratio 0.97~1.09；churn 回收。
- **尚未验证（需真生产环境）**：生产库在线迁移；目标规模真压测 P99；P1-16 租户注销全链路演练。

---

## 八、新窗口启动指令（复制到首条消息）

```
读 docs/plans/activity-engine-platform-0718/HANDOFF.md。Track A + Track B 里能在 demo 落地的项已全部实现（98 测试绿）。
剩下的是「三、待办清单」里需真外部环境的项（前端 OIDC 浏览器 E2E / P1-16 租户注销编排 / P1-15 真库在线迁移 / 目标规模真压测），
均已备脚本/设计/runbook（52/51/53/50）。我要推进其中哪项先跟我确认——若涉及真外部环境（浏览器/生产库/Casdoor 建应用），
先复述你的理解和执行步骤让我确认，遇到与「五、已确认无需改的点」或「六、约束」冲突先停下问我。改动务必：改代码→跑 ./mvnw test→回填本文状态。
```
