# 活动营销模块（`com.lrj.drools.activity`）

从 `autolife/mall-shop` 的活动营销部分**收敛移植**进本 demo 的一份可运行副本，用来在本项目前端走一遍"活动创建 → 制定规则 → 上线 → 验证优惠"的完整流程，探索服务端/前端怎么改造。**不是生产代码**，是学习/探索脚手架。

> 规划与实施全过程（Codex 规划 → Claude 跨模型复核 → 分阶段实施 → /frontend-plan）见 `docs/plans/activity-marketing-port-0712-1917/`：`FINAL_PLAN.md` / `FRONTEND_PLAN.md` / `IMPLEMENTATION_PROGRESS.md`。

## 能力范围

覆盖来源的**红包(RED_PACKAGE)** 与 **买赠(BUY_AND_GET)** 两类活动，四个规则场景：

| 场景 | 说明 | 规则实现 |
| ---- | ---- | ---- |
| eligibility | 资格淘汰（可视化条件树 → 受控 Drools 约束，`not ActivityRuleContext(...)` fail-closed） | 运行时编译 DRL |
| discount | 多活动折扣合并（MAX / MUTEX / STACK / PRIORITY，照抄来源 `DiscountDbRuleSource` 模板） | 运行时编译 DRL |
| ladder | 阶梯满减（`redPackageRangeAmount` JSON 分档） | 运行时编译 DRL |
| gift | 买赠奖品保留/汇总 | 运行时编译 DRL |

外加**商品池自动圈选**：规则驱动圈选 `demo_product` 并物化进绑定表（`bind_source=AUTO`，按目标态 diff 幂等刷新）。

**纯 Drools，不引 QLExpress**：来源用 QLExpress 跑资格表达式，这里统一翻译成 Drools LHS 约束（更契合本项目定位）。

## 跑起来

> **M2.1 起是 Maven 四模块**（`activity-common` 共享库：domain/engine/persistence/tenant + 只读查询服务 / `drools-lab` Step1–18 教学 / `activity-console` 写平面 app:8081 / `activity-decision` 只读决策 app:8082）。根 `./mvnw spring-boot:run` **不再可用**（父是聚合 pom，无 main），起服务要 `-pl` 指定 app 模块。拆分详情见下节「决策平面拆分」。

```bash
# 控制台写平面（活动创建/编辑/上下线 + 前端 /ui/ + Step1–18），默认 8081；H2 profile 免装 MySQL
./mvnw -pl activity-console spring-boot:run -Dspring-boot.run.profiles=h2
# 只读决策热路径（/decision/v1/* + 发布代际轮询预热），默认 8082
./mvnw -pl activity-decision spring-boot:run -Dspring-boot.run.profiles=h2
# 顺带把 Vue SPA 构建拷进 static/ui/（否则前端用 frontend/ 的 Vite dev server :5173）
./mvnw -pl activity-console -Pfrontend spring-boot:run
```

浏览器打开 `http://localhost:8081/ui/`（根 `/` 是构建无关落地页，跳 `/ui/`；旧原生演示台已于 F3 退役）→ 活动配置台 `/ui/console/activities`，即可用报表式表单创建活动、拖出资格条件树、上线，在「优惠验证」页 `/ui/console/validate` 查命中。

开关：
- `activity.marketing.rule-engine.enabled`（默认 true）：false 时优惠查询走旧 Java 逻辑（取最大红包），用于灰度对照/回滚。
- `activity.marketing.seed-demo-data`（默认 true）：启动时种入 4 个 demo 商品 + 商品池（poolId=1，圈电子类 100~200 元），让商品池自动圈选在浏览器可演示；测试中不开，不污染断言。
- `activity.tenant.dev-default-enabled`（默认 **false**，`application.yml` dev-run 显式开为 true）：多租户开关，见下节「多租户隔离」。本地开着时不带 `X-Tenant-Id` 也能跑（回落单租户 `__dev__`），下面的 curl 示例照常工作。

## REST 接口（全部在 `/activity-marketing`）

| 方法 | 路径 | 说明 |
| ---- | ---- | ---- |
| POST | `/create` | 创建/编辑（带 `activityId` 即编辑，version+1；`requestId` 幂等） |
| POST | `/{id}/status` | 上下线 `{version,targetStatus}`（0 待上线/1 上线/2 下线） |
| GET | `/list` | 活动列表（当前版本） |
| GET | `/{id}` | 详情（manage/rules/conditions/bindings/gifts/poolRefs） |
| POST | `/spu-discount` | 红包优惠查询（资格→阶梯→折扣合并 + 回退 + trace） |
| POST | `/gifts` | 买赠查询 |
| POST | `/preview` | 资格条件树预览（翻译+试编译，不落库；恒 200 读 `ok`） |
| GET | `/field-dict` | 字段白名单 + 运算符 + 枚举（前端下拉的唯一来源，防漂移） |

创建红包活动示例（资格 orderAmount≥100 + 手动绑定 spu 1001）：

```bash
curl -X POST localhost:8081/activity-marketing/create -H 'Content-Type: application/json' -d '{
  "activityName":"新人红包","bizLine":"mall","activityType":1,
  "activityStartTime":1780000000000,"activityEndTime":1790000000000,
  "activityAreaType":1,"priority":1,"inventory":100,
  "redPackageTakeType":1,"redPackageAmount":50,"redPackageAmountUnit":"元","discountStrategy":"MAX",
  "eligibilityConditionTree":{"logic":"AND","children":[{"field":"orderAmount","op":"ge","value":100}]},
  "spuBindings":[{"storeId":1,"spuId":1001}]
}'
# → {"activityId":"ACT...","version":1,"status":0,"idempotentHit":false,"autoBoundCount":0}
# 上线后 POST /spu-discount {"spuIdList":[1001],"orderAmount":200} → hit=true, amount=50
```

## 决策平面拆分（console / decision）

M2 把本模块沿**读写平面**拆成两个独立 Spring Boot 应用，共用 `activity-common`（domain/engine/persistence/tenant + 只读查询服务 `ActivityQueryService`）：

| 应用 | 端口 | 承载 | Maven 依赖 |
| ---- | ---- | ---- | ---- |
| `activity-console` | 8081 | 写平面（create/status/幂等/四眼）+ Step1–18 教学 + 前端 `/ui/` | `activity-common` + `drools-lab`（全量，含 kie-ci/dmn/decisiontables） |
| `activity-decision` | 8082 | 只读决策热路径 `/decision/v1/*` + 发布代际轮询预热 | 仅 `activity-common`（甩掉 `drools-lab` 带来的 kie-ci/dmn/decisiontables 与全部写面依赖，更轻） |

**`/decision/v1` 是决策热路径将来物理拆出去的稳定契约**——`DecisionPlaneController` 复用与控制台**同一份** `ActivityQueryService`（与 `/activity-marketing/spu-discount` 走同一代码，行为一致）；旧 `/activity-marketing/*` 路径保留、不弃用，前端与旧脚本不受影响。

| 决策平面路径（decision, 8082） | 等价的控制台路径（console, 8081） |
| ---- | ---- |
| POST `/decision/v1/spu-discount` | POST `/activity-marketing/spu-discount` |
| POST `/decision/v1/gifts` | POST `/activity-marketing/gifts` |

- **角色门控**（`RoleGateFilter`，靠 `activity.role`，仅显式设置该属性时才装配）：`decision` 只放行 `/decision/v1/**` + `/actuator/**`；`console` 屏蔽 `/decision/v1/**`、放行写面 + Step1–18 + SPA；`all`（默认，本地/测试）全开。这是**部署角色边界**而非安全边界（同一份代码），真隔离仍靠 Casdoor 验签 + `@TenantId`。
- **发布代际轮询预热（M1.4）**：console 上线活动时 bump `(tenant,bizLine)` 代际；decision 后台按 `activity.marketing.generation-poll.interval-ms`（默认 3000ms）轮询，见代际增长即预热该 `(tenant,bizLine)` 的全部 ACTIVE artifact——物理拆分后 decision 进程无需 console 进程内直调，也能「发布即 warm」。
- **网关**（`deploy/docker-compose.yml`：mysql + console + decision + nginx）：nginx 把 `/api/decision/*`→decision、`/api/console/*`→console、`/ui/*` 及其余→console；host 端口 **8095**（`http://localhost:8095/ui/console`）。`docker compose stop console` 后 `/api/decision/*` 仍可决策，可当场演示拆分价值。

```bash
# 直连 decision 服务的决策别名（等价 console 的 /activity-marketing/spu-discount）
curl -X POST localhost:8082/decision/v1/spu-discount -H 'X-Tenant-Id: acme' \
  -H 'Content-Type: application/json' -d '{"spuIdList":[1001],"orderAmount":200}'
# 经网关：POST localhost:8095/api/decision/spu-discount（同 body）
```

## 多租户隔离（P0-4，Track B）

活动数据按租户隔离，**靠机制不靠纪律**：10 张实体表都加了 `tenant_id` 列（Hibernate `@TenantId` 判别式多租户），引擎对**每条 SQL 自动追加 `tenant_id = ?` 谓词**、insert 自动落租户——业务代码不手动拼 where、不手动 set 租户，漏不掉。

- **租户来源（可插拔接缝，两档）**：`activity.tenant.auth.enabled=false`（默认）时从 HTTP 头 `X-Tenant-Id` 取（dev/本地）；`=true`（P0-3 接 Casdoor）时 `/activity-marketing/**` 需带 Casdoor 验签 JWT，**租户从 `aud`(client_id) 解析**（命脉实测：Casdoor client_credentials 的 `owner`=admin 非组织；`aud` 由 Casdoor 绑定到已认证 client + 独立 secret → 不可伪造，比 owner 更实在），信封 `X-Tenant-Id` 只校验（≠解析出的租户→403）、绝不作来源。两档都写进同一个 `TenantContext`(ThreadLocal)，下游 `@TenantId` 隔离机制一行不动。
  - **aud→tenant 解析**：`AudienceTenantResolver` —— `client-tenant-map` 显式映射优先（生产推荐），`activity-{tenant}-cid` 家族反解兜底；`AudienceTenantValidator` 常开，aud 解析不到租户即拒（401）。
  - **开 Casdoor 档前必做**：跑 `scratchpad/casdoor-m2m-verify.sh` 为每租户建独立 client_credentials 应用（唯一 secret）+ 验命脉 + 跨租户 secret 互斥冒烟 + 打 :8099 端到端。
- **前端（dev 档）**：活动配置台顶部有「租户 (X-Tenant-Id)」切换条（输入 + acme/beta/__dev__ 快捷，localStorage 记忆）；切租户即换数据视图，浏览器里直接看隔离。Casdoor 档需前端接登录换 token（后续）。
- **fail-closed**：`/activity-marketing/*` 上的 `TenantContextFilter` 是面向用户的闸——无 `X-Tenant-Id` 且 dev-default 关时直接 **403**；`X-Tenant-Id` 含非法字符（非 `[A-Za-z0-9_-]{1,64}`）**400**。其它 Step（1~18）不挂此过滤器、不受影响。
- **dev-only 默认租户**：`activity.tenant.dev-default-enabled=true` 时，不带头的请求回落到单租户 `__dev__`，方便本地/前端手点；**生产必须关**（默认就是关 = 无头即 403）。
- **只做数据行隔离**：字段 schema（`/field-dict` 白名单）当前仍全租户共享（`RuleSchemaRegistry` 仍走 `DEFAULT_TENANT`）；按租户定制字段元数据属后续（P0-1 的 Track B 扩展），不在 P0-4。
- **结构守卫**：`TenantArchGuardTest` 钉死两条不变量——每个 `@Entity` 必带 `@TenantId`（全局表走白名单显式豁免）、仓库不得用 `nativeQuery`（原生 SQL 会绕过租户过滤）。加了 `@TenantId` 后裸 `findAll()` 已被机制自动加谓词、本身安全。

```bash
# 显式带租户（生产形态）：acme 建的活动，globex 看不到
curl -X POST localhost:8081/activity-marketing/create -H 'X-Tenant-Id: acme' -H 'Content-Type: application/json' -d '{...}'
curl localhost:8081/activity-marketing/list -H 'X-Tenant-Id: acme'    # 含刚建的
curl localhost:8081/activity-marketing/list -H 'X-Tenant-Id: globex'  # []
```

## 来源字段映射（收敛，非 1:1）

| 本 demo | 来源（mall-shop / mall-common） | 取舍 |
| ---- | ---- | ---- |
| `ActivityManageEntity` (activity_manage) | `ActivityAdminPlatformManage` | 去掉合伙人/审核/权益系统 id 等 |
| `ActivityRuleEntity` (activity_rule) | `ActivityDynamicRules` | 保留红包/阶梯字段 |
| `ActivitySpuBindingEntity` | `ActivityAdminStoreSpuProduct` | 保留 bindSource/effective/poolId |
| `ActivityConditionEntity` | `ActivityRuleExpression` + 条件树 | 存条件树 JSON + 翻译后 DRL |
| `ActivityStrategyEntity` | `ActivityRuleStrategy` | bizLine 级合并策略 |
| `ActivityGiftEntity` | `BuyAndGetConfig.GiftConfig`（来源存 extraData JSON） | 拆成结构化行 |
| `ProductPool*/PoolRef` | `ActivityProductPool(Item/Rule)/ActivityPoolRef` | 圈选维度简化为 价格/类目/标签 |
| `DemoProductEntity` (demo_product) | 真实商品/车辆表 | **替身表**，仅供圈选演示 |
| `RuleSchemaRegistry` + `SchemaField` | `activity_rule_field_dict` 表 | 内置白名单（原 `RuleField` 枚举），按 (tenant,bizLine) 解析、单租户 stub |

facts（`ActivityCandidate/ActivityRuleContext/ActivityRuleResult/GiftResult`）与来源 `engine/fact/*` 对齐。

## 本次未迁移（来源存在）

砍价/拼团/门店拼团/抽奖等其它玩法、CPS 订单分润、红包合伙人签名校验、真实商品/权益/钉钉集成、版本历史浏览/回滚、鉴权。`COUPONS/CPS/RIGHT_COUPON` 三类活动类型保留枚举位但未实现（后端 400、前端禁用）。
