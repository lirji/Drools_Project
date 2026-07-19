# 活动引擎平台 · 接入 auth-platform（认证 + 多租户身份 + 授权）

> 只读调研 `/Users/liruijun/personal/LLM/auth-platform`(Codebase Onboarding Agent 逐条对照文档 + 五模块源码 + `.zed` schema)后的接入方案。结论回写后端 FINAL_PLAN §17.7。**本轮设计,不写码。**

## auth-platform 是什么
内部统一 IAM:**Casdoor(身份/登录/SSO/OIDC)+ SpiceDB(Zanzibar/ReBAC 细粒度授权)**,配 Spring Boot Starter SDK(`@CheckAccess` 切面)。模块 `protocol/core/sdk/server/admin` + `auth-console` 前端。已有《新项目接入指南》《统一登录平台接入手册》。recsys 是首个照指南落地的外部消费方,可当样板。

## 一句话结论
**活动平台的租户/认证/授权直接接入 auth-platform,不自建。** 多租户是它一等公民(Casdoor org = 租户),认证可离线验签(RS256+JWKS),细粒度判权是网络调用且平台层刻意不缓存——正好逼出"判权只在控制台、决策热路径只验签"的分层,与本平台 P99 约束天生一致。

## 关键事实(带 auth-platform 文件路径)
- **Casdoor org = 租户**,`token.owner` = tenant_id;"只信 token owner 不信 header/query"是既有约定(`docs/统一登录平台接入手册.md:71`、`docs/新项目接入指南.md:108`、`deploy/casdoor-tenant-provision.sh:4-7`)。
- **RS256 JWT + discovery/JWKS**(`/.well-known/openid-configuration`、`/.well-known/jwks`),后端 `NimbusJwtDecoder.withJwkSetUri` 缓存公钥离线验签,校 iss/aud/exp(`auth-platform-admin/.../SecurityConfig.java:59-72`)。
- **token claim**:`sub`(用户 UUID,非 username)、`owner`(org=租户)、`groups`(`<org>/<group>` 全路径)、`roles/permissions`、`iss/aud/exp`(`docs/统一登录平台接入手册.md:68-77`)。
- **多租户 SaaS = Shared Application**:派生 client_id `<base>-org-<tenant>`、共用 shared secret,消费方 aud allowlist 认 `<base>-org-*` 家族 → 新增租户零改动(`deploy/casdoor-tenant-provision.sh`、`docs/新项目接入指南.md:66-72`)。
- **client_credentials(M2M)**:文档实测支持(`docs/统一登录平台接入手册.md:279-291`);⚠ Casdoor 本体不在仓库,**代码不可验**。
- **SpiceDB check = 两跳网络调用(sdk→server→SpiceDB),平台层刻意不做判权结果缓存**(`auth-platform-sdk/.../RemoteAuthzEngine.java:212-223`、`docs/性能与容量规划.md:13-20、49`);ZedToken 水位缓存只保证写后读一致,不缓存"谁能不能"。
- **`.zed` 授权样板**:`recsys.zed` 广告主作用域——`platform`(admin/operator→administrate/review/view_reports)+ `advertiser`(manage=owner+platform->administrate、edit=member+manage、view=edit+platform->view_reports),子资源不进 SpiceDB、反查归属再判(`auth-platform-core/src/main/resources/schemas/recsys.zed:1-40`)。独立 SpiceDB 实例(:8544)。

## 接入方案(两个平面)
| 平面 | 认证 | 授权 | tenant 来源 |
|---|---|---|---|
| **控制台(人)** | 授权码+PKCE 登 Casdoor → OIDC resource-server 本地验签 | 引 SDK `@CheckAccess`/`checkBulk` 调 SpiceDB | `token.owner` |
| **决策 API(机器,热路径)** | **只本地 JWKS 验签,零判权网络调用** | **不碰 SpiceDB** | `token.owner`(每租户在自己 org 内的 client_credentials 机器客户端) |

**SpiceDB 授权模型(照 recsys.zed 范式)**:`tenant → bizLine → activity` 作用域继承 + 角色 `operator/reviewer/publisher`(支撑 D2 多级审核 + 职责分离);活动/规则等高频子资源**不逐条写元组**,权限锚在作用域对象、反查归属再判;**用活动平台独立 SpiceDB 实例**。

## 替换掉的自建件(后端 §6.2)
- `ActivityApiKeyFilter`(自管 Key)→ OIDC/JWT 验签过滤器(oauth2-resource-server + 缓存 JWKS)。
- `activity_api_client`(存 secret)→ 瘦身成**租户注册表**(tenant_id[=org]→schema/配额/启用 bizLine/状态,不存凭证)。
- `ActivitySecurityConfig` → 抄 auth-platform 的 resource-server 配置。
- D6/D13 从"自建/待定"→复用 auth-platform。

## M2M 租户身份(评审修正:是真洞,须每租户独立 app)
本平台 **tenant = 接入的业务方 = 一个 Casdoor org**,决策 API 读 token `owner` 即得租户。但安全评审点破两层缺口,原"不是缺口"的判断过于乐观:
- **owner 未证实**:手册只说 client_credentials 的 `sub` 代表机器,对 `owner` 只字未提;PHASE0 里"claim 能否表达目标 tenant"是未决目标。
- **共享 secret 下 owner 可伪造**:Shared Application 派生 client(`<base>-org-<tenant>`)**共用同一 secret**,派生 client_id 可猜(`<base>-org-<victim>`)→ 任一持共享 secret 的租户可换出 owner=别租户的 token → 在无判权的决策热路径**跨租户冒充**。
- **修正**:**M2M 决策平面强制每租户独立注册 Casdoor Application(独立 client_id + 唯一 secret),禁用 Shared Application 共享 secret 派生**。Shared Application 仅用于控制台(人,授权码流)。冒烟不仅测"owner 存在",更要测**"租户 A 的 secret 换不出 owner=B 的 token"**。
- **aud 校验**:参考 `SecurityConfig` 的 aud 是可选 + 精确单值 + **默认空 client-id→根本不校验**,且仓库内无家族通配(`<base>-org-*` 只在仓库外 edge)。**必须自写 audience 校验器**:常开、前缀家族匹配 + **owner↔aud 绑定**、家族外拒绝。
- **tenant 只从 token.owner 派生**:"只信 owner 不信 header/query"是 auth-platform 文档约定,真 enforcer 在仓库外 edge,活动平台**零继承 100% 自建**;决策信封的 `tenantId` 只作校验(须=owner,否则 403),body/header/query 的 tenantId 一律忽略。

## 必须实测 / 注意的坑
1. **【命脉待实测】** Casdoor 对 **client_credentials token 是否写 `owner`=app 的 org**——决策 API M2M 拿租户的前提。取一个机器 token 看 claim 冒烟确认;若不写,则需 client→tenant 映射表兜底。
2. 后端配 `server.max-http-request-header-size: 64KB`(大 token 否则 400)。
3. 用户主键切 `sub`;`SubjectResolver` 返回 Casdoor `sub`,否则判权空转。
4. SpiceDB 对象 id 带 `<tenantId>_` 前缀;资源创建/删除双写关系元组(最易漏)。
5. 高频子资源不逐条写元组(权限锚作用域对象)。
6. SDK `0.1.0-SNAPSHOT` 未发远程仓库,需本地 install(双仓库带 `-Dmaven.repo.local`)。
7. 生产开 server 写端 `authz.server.security.enabled=true`。

## 落地顺序建议(叠加在 Track B 多租户上)
接 auth 属**多租户化(Track B)**的一部分,在 Track A(单租户通用化)跑通后引入:先接**控制台 OIDC SSO + owner 取租户**(认证层,零判权依赖)→ 再引 SDK 做控制台 SpiceDB 判权 → 决策 API 加机器客户端 M2M 验签。认证层与授权层可分开接(auth-platform SDK 分层设计支持)。
