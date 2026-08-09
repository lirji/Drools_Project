# QA 环境档案 · drools-demo（活动营销多租户）

> 首次由 /qa-test 勘察生成（2026-07-19）。人工可改，下次直接复用。
> 最后同步 2026-08-09（权益模型重构 + 前端视觉换代 + 三玩法解锁）：本轮的启动命令、端点、回归数均在本机实跑核对过，非照抄计划文档。

## 技术栈 / 启动
- Java 21 / Spring Boot 3.3.5 / Drools 8.44.2 / Maven wrapper。
- **仓库形态（2026-07 · M2.1 起 = Maven 四模块）**：`activity-common`(库) / `drools-lab`(库,Step1-18) / `activity-console`(app,8081,写面+Step1-18+前端`/ui/`) / `activity-decision`(app,8082,只读决策`/decision/v1`)。根 `./mvnw spring-boot:run` **已失效**（父聚合 pom 无 main）——起服务须 `-pl` 指定 app 模块。
- **dev/header 档（QA 常用，auth 关、dev-default 开）**：
  - 活动写面 + 台 + 旧读端点（console，8081）：`./mvnw -pl activity-console spring-boot:run -Dspring-boot.run.profiles=h2`
  - 只读决策热路径（decision，8082，测 `/decision/v1/*`）：**必须补 `ddl-auto` 覆盖**，见坑3
    ```bash
    ./mvnw -pl activity-decision spring-boot:run -Dspring-boot.run.profiles=h2 \
      -Dspring-boot.run.arguments="--server.port=8099 --spring.datasource.url=jdbc:h2:mem:qadecision;DB_CLOSE_DELAY=-1;MODE=MySQL --spring.jpa.hibernate.ddl-auto=update"
    ```
  - ⚠️ **坑1**：本机 8081/8082 常被 Docker 容器占用 → 换端口：`-Dspring-boot.run.arguments="--server.port=8097"`。
  - ⚠️ **坑2**：`h2` profile 用 **file 库**（console `./data/drools-demo` / decision `./data/decision`，单连接锁）→ **同一 file 库不能两个 app 同开**。QA 起干净实例请覆盖内存库：`--spring.datasource.url=jdbc:h2:mem:qadev;DB_CLOSE_DELAY=-1;MODE=MySQL --spring.jpa.hibernate.ddl-auto=create-drop`。
    - **这条也会咬跑测试**：console 测试里只有 `FixedPriceAndClaimTest` 没有 `@TestPropertySource` 覆盖数据源（其余全部覆到 `jdbc:h2:mem:*`），因此它是唯一去抢 `activity-console/data/drools-demo.mv.db` 的用例。本机有 console 在跑、或上一次 `./mvnw test` 被中断留下锁，它会以 `Unable to determine Dialect without JDBC metadata` 报 9 个 error——**这条报错是症状不是原因**，真因在日志更上面的 `Database may be already in use`。处理：停掉本地 console，`rm -rf activity-console/data` 后重跑（实测清掉即绿）。
  - ⚠️ **坑3（2026-08 新增）**：decision 的 `ddl-auto` 已从 `update` 改成 **`validate`**（只读平面不碰 DDL，建表由 console 独占）。按老命令直接起 decision 连空库会启动失败：`Schema-validation: missing table [activity_artifact]`。本地单起 decision 有两条路：① 加 `--spring.jpa.hibernate.ddl-auto=update` 自建空表（实测可起）；② 用 compose 整套（MySQL 库由 console 建好）。**注意 H2 file 库单进程锁，console + decision 不可能共用同一个 H2 file 库**，要两 app 共库只能走 compose 的 MySQL。
  - **前端 SPA 需先构建**：`-Pfrontend`（`./mvnw -pl activity-console -Pfrontend spring-boot:run …`）把 Vue 产物拷进 `static/ui/`，或本地 `cd frontend && npm run dev`（Vite :5173）。不构建时 `/ui/` 404、根 `/` 只是落地页。
  - **整套微服务编排**（两 app + frontend nginx 网关 host `:8095` + MySQL 单库双账号 + Prometheus `:9090` + Grafana `:3001`）：`./deploy.sh --provision-auth` → 网关 `http://localhost:8095/ui/console`。Compose 默认 auth 开、dev-default 关。
    - **切 header 档**（跑 `e2e:dev` / `e2e:catalog` / `e2e:tablet` / `e2e:phone` / `e2e:bench` / `e2e:playbooks` / `e2e:ruler` / `e2e:visual` 这些走 `tenant-chip` 的脚本必须切，否则被登录守卫弹走）：
      `DROOLS_AUTH_ENABLED=false DROOLS_DEV_DEFAULT_ENABLED=true docker compose -f deploy/docker-compose.yml up -d`
    - ⚠️ **改了代码要重建对的镜像**：前端 `/ui/` 由 **gateway 镜像**托管（`activity-frontend`），不在 console 的 JAR 里 → 改前端要 `--build gateway`；改后端要 `--build console decision`（**decision 是独立镜像，只重建 console 时它仍是旧代码**，新决策端点会 404）。
- **Casdoor 档（auth 开，console + decision API 端到端）**：本机 Casdoor `:8000` 启动后用 `./deploy.sh --provision-auth` 幂等登记 8095 callback；真实浏览器回归为 `BASE=http://localhost:8095 npm --prefix frontend run e2e:oidc`。

## 入口 / 健康检查
- 健康：`GET /actuator/health` → 200（console 8081 / decision 8082 各一个）。
- 前端：根 `GET /` 是**构建无关落地页**（跳 `/ui/`，旧原生台 + `/assets/activity.js|css` 已于 F3 退役删除）；SPA 挂 `/ui/`，活动配置台 `/ui/console`（列表 `/ui/console/activities`、**玩法模板 `/ui/console/playbooks`**、验证 `/ui/console/validate`），18 Step 演示台 `/ui/demos`。
- 活动写面/旧读端点（console，8081）：`/activity-marketing/*`（见 `docs/activity-marketing.md` 接口表）。2026-08 新增两个：
  - `POST /activity-marketing/bulk-status`，体 `{items:[{activityId,version}],targetStatus}` → **一律 200**，回执 `{succeeded[],failed[{activityId,reason}]}`（部分失败是正常结果，别当错误断言）。
  - `POST /activity-marketing/{activityId}/claim?version=&quantity=` — 秒杀库存**权威扣减**（决策只报价、这里才扣）。抢到 200、抢不到 **409**（`{ok:false,reason}`）。
- 只读决策热路径（decision，8082）：`POST /decision/v1/spu-discount`、`POST /decision/v1/gifts`。2026-08 新增四个：
  - `POST /decision/v1/addon/options` — 加价购第一阶段，只列换购选项。**空列表是正常结果**（`{"options":[],"traces":["无生效加价购活动"]}`），不是错误。
  - `POST /decision/v1/addon/quote?activityId=&item=` — 第二阶段权威报价，**签名里没有价格参数**（防改价）。选项失效返回 **409**。
  - `GET /decision/v1/metrics` — 耗时/回退聚合，`scope=single-instance`（本进程视角，跨实例看 Prometheus）。
  - `GET /decision/v1/by-activity` — 按活动命中量，带 `tagCap=200` + `overCapTag=__over_cap__`（超基数上限的活动并入哨兵，总量仍准）。
  - ⚠️ **console 与 decision 的 `traces` 详略可能不同**：两边仍复用同一 `ActivityQueryService`，但 console 的试算显式开 `explain=true`，决策热路径默认 `false`。`explain=false` **只抑制规则/资格的逐条 trace**，结构性与回退 trace（如 `无生效红包活动`、`无生效买赠活动`、`资格规则回退：…`、`折扣规则空决策，回退旧逻辑取最大`）两边都会返回，**`traces` 字段恒存在**（决策平面看到非空 traces 不是缺陷）。空环境下两平面往往逐字节相同；只有走引擎命中路径时 decision 才是 `"traces":[]`。**断言金额一致、不要断言响应体全等**。
- **网关口（8095）与直连端口的前缀不同**，别混：`/api/console/*` → 重写成 console 的 `/activity-marketing/*`；`/api/decision/*` → 重写成 decision 的 `/decision/v1/*`；其余 `/` 一律落 console。所以在 8095 上直接打 `/decision/v1/by-activity` 会 **404**（打到了 console），正确写法是 `/api/decision/by-activity`。`/activity-marketing/*` 在 8095 上可直接打（走 `/` 兜底到 console）。

## 多租户测试要点（本项目特有）
- **租户来源**：dev 档从 `X-Tenant-Id` header；auth 档从 JWT 的 `aud` 解析。
- **决策平面的 header 租户解析是 2026-08 才补上的**：`TenantContextFilter` 原先只挂 `/activity-marketing/*`，header 档下 `/decision/v1/*` 完全不解析租户，`X-Tenant-Id` 被静默忽略、全部落 dev-default 兜底（auth 档不受影响，`JwtTenantFilter` 挂在覆盖两个平面的安全链上）。现已扩到 `/activity-marketing/*` + `/decision/v1/*`，回归由 `DecisionTenantHeaderTest` 钉死。**跨租户隔离用例请对决策平面也各跑一遍**，别只测写平面。
- **前端**：dev 档显示 `X-Tenant-Id` 切换条；auth 档显示 token tenant/actor 与退出按钮，并使用 PKCE + sessionStorage Bearer。
- **隔离断言**：A（X-Tenant-Id: acme）建的活动，B（beta）列表/详情/优惠查询都看不到；detail 越权 → 400。
- **保留值**：`X-Tenant-Id: __no_tenant__` → 400（保留哨兵不可冒充）；非法字符 → 400；无 header + dev-default → 回落 `__dev__`。（两条 400 已在 `/decision/v1/spu-discount` 上实测复现。）

## 凭据
- dev 档无需凭据（header 即身份，仅本地）。浏览器 Casdoor 档：`acme/act-alice`（`act-alice-dev-pass-01`）、`beta/act-bob`（`act-bob-dev-pass-02`）；机器 token 由 M2M provision 脚本铸。

## 测试素材
- 接口 + curl 示例：`docs/activity-marketing.md`。
- 回归单测：`./mvnw test`（2026-08-09 本机实跑 **BUILD SUCCESS**，共 **314 跑 / 3 skip**：common **121**(3 skip) / drools-lab **0**（该模块无测试）/ console **176** / decision **17**）。含 `DecisionAuthIntegrationTest` 的 decision 401/403/200 边界、`DecisionGoldenSetTest` 金标集（Java 与全 Drools 两条路各跑一遍）、`DecisionDdlGuardTest`（decision 的 `ddl-auto` 漂回 `update` 即红）。
  - 跑不绿先看坑2 的 `FixedPriceAndClaimTest` 那条，多半是 H2 file 锁而不是真回归。
- 前端单测：`cd frontend && npm run test`（vitest **154 绿 / 22 文件**，2026-08-09 实跑）。
- 前端 E2E：共 **9 套**，npm 脚本 `e2e:dev` / `e2e:catalog` / `e2e:tablet` / `e2e:phone` / `e2e:ruler` / `e2e:bench` / `e2e:playbooks` / `e2e:visual` / `e2e:oidc`，全部 `BASE=http://localhost:8095 npm --prefix frontend run <script>`。
  - 前 8 套走 header 档（跑前按上面切档）；`e2e:oidc` 走 auth 档 + 真 Casdoor。
  - 最近一次 9 套全绿是 **83 条断言**（visual 10 / dev 7 / catalog 6 / tablet 7 / phone 7 / bench 13 / playbooks 17 / ruler 4 / oidc 12），记录于 `docs/plans/frontend-tech-visual-0809-1424/PROGRESS.md`（提交 `0bd529a`）。
  - ⚠️ 其后的 5 个提交（随机金额 / 一口价+库存 / 第 N 件折 / 加价购 / 命中计数）**只复跑了后端与 vitest，e2e 未重跑**；`frontend/e2e/` 下脚本一个字节没改，但被测应用变了（玩法从「暂不支持」放出来），`e2e:playbooks` 的分组断言值得优先复跑。
  - `e2e:playbooks` **会真的建一条活动**（`E2E折扣券-<时间戳>`），跑完记得清；`e2e:bench` 会批量上下线。别在需要干净数据的回归前跑。
  - UI 定位契约（testid 清单）：`frontend/e2e/data-testid-contract.md`。

## 玩法 / 权益形态造数速查（2026-08 新增）
`redPackageAmountUnit` 从「装饰字段」变成了**权益形态判别位**，同一个 `redPackageAmount` 数字的含义由它决定：

| unit | 形态 | `redPackageAmount` 含义 | 写平面额外校验 |
|---|---|---|---|
| `元` / null | 金额型（默认） | 减多少钱 | 不允许填 `redPackageMaxDiscount` |
| `折` | 折扣型 | 折数，须 (0,10) | **`redPackageMaxDiscount` 必填且 >0**；且不许同时配阶梯分档 |
| `价` | 一口价（秒杀） | 卖多少钱（与原价无关） | 库存扣减走写平面 `claim`，决策侧只做建议性闸门 |
| `件折` | 第 N 件折 | 折数；「第几件」存 `redPackageRangeAmount` 的 `{"nth":2}` | 决策入参**必须带 `lines` 逐行单价**，否则 fail-closed 不给优惠 |

- 白名单外的 unit 一律 400（防拼错的单位被静默当成金额发钱）。
- `redPackageRangeAmount` 是**双用途列**，靠 JSON 顶层类型区分：**数组 = 阶梯分档**，**对象 = 随机红包区间 `{"min":5,"max":20}`**（`redPackageTakeType=2`，确定性随机：同用户同购物车金额固定）。造数时写错类型会被另一条解析路径认领。
- 决策入参 `SpuDiscountRequest` 新增 `storeId` 与 `lines`（`{spuId,unitPrice,quantity}`），均为**纯增量**：不传时老行为一字节不变。配了「店铺」条件的活动此前永远不命中，就是因为入参缺 `storeId`。

## 已知缺口（测到会撞、不是你的环境问题）
- **加价购（activityType=6）建不出来**：`ActivityMarketingService.validateCommon` 仍只放行红包(1)/买赠(5)，`POST /activity-marketing/create` 带 `activityType:6` 实测返回 `400 {"error":"demo 仅支持红包(1) / 买赠(5)，收到: 6"}`。因此前端玩法模板屏里的「加价购」模板点「用它新建」保存会失败，`/decision/v1/addon/*` 也只能靠直接写库造数据来测（后端单测用桩 loader，绕过了这条路）。TODO(待澄清)：是有意保留写侧不放开，还是解锁玩法时漏改校验。
- `frontend/e2e/data-testid-contract.md` 里「`form-take-type` 的『随机金额』选项恒 `disabled`」已过期——随机金额已接入决策链路，该选项现在可选。以代码为准。
