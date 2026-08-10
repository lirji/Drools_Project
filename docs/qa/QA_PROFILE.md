# QA 环境档案 · drools-demo（活动营销多租户）

> 首次由 /qa-test 勘察生成（2026-07-19）。人工可改，下次直接复用。
> 能力与端点最后同步 2026-08-10（全玩法优惠验证 + 三通道资格收敛）。下文标注 2026-08-09 的数字仅作历史基线；本批最新实跑（2026-08-10 晚，**返工后复跑**）是 Maven **371 tests / 3 skip / BUILD SUCCESS**（common 150 含 3 skip / console 204 / decision 17）+ vitest **25 文件 270 passed** + `vue-tsc` 与生产构建通过，与 Docker header 档（`DROOLS_FOUR_EYES_ENABLED=true`）`e2e:validate` **pass=472 / fail=0**。
>
> ⚠️ 用例数只认 `./mvnw test` 输出的 `Tests run:` 汇总。**求和 surefire XML 会少数 50 个**（`DroolsBenefitGoldenSetTest` 让父类的 `@Nested` 用例重跑一遍并覆盖同名报告，见 `CLAUDE.md` 坑 14）。本文件早先版本里的 307 与 357 都源自这个陷阱，已作废。

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
    - **切 header 档**（跑 `e2e:dev` / `e2e:catalog` / `e2e:tablet` / `e2e:phone` / `e2e:bench` / `e2e:playbooks` / `e2e:validate` / `e2e:ruler` / `e2e:visual` 这些走 `tenant-chip` 的脚本必须切，否则被登录守卫弹走）：
      `DROOLS_AUTH_ENABLED=false DROOLS_DEV_DEFAULT_ENABLED=true docker compose -f deploy/docker-compose.yml up -d`
      聚焦 `e2e:validate` 还要加 `DROOLS_FOUR_EYES_ENABLED=true`；脚本先断言 AUTHOR 自审发布返回 409，再由 APPROVER 发布成功，未开四眼时会正常失败而不是假绿。
    - ⚠️ **改了代码要重建对的镜像**：前端 `/ui/` 由 **gateway 镜像**托管（`activity-frontend`），不在 console 的 JAR 里 → 改前端要 `--build gateway`；改后端要 `--build console decision`（**decision 是独立镜像，只重建 console 时它仍是旧代码**，新决策端点会 404）。
- **Casdoor 档（auth 开，console + decision API 端到端）**：本机 Casdoor `:8000` 启动后用 `./deploy.sh --provision-auth` 幂等登记 8095 callback；真实浏览器回归为 `BASE=http://localhost:8095 npm --prefix frontend run e2e:oidc`。

## 入口 / 健康检查
- 健康：`GET /actuator/health` → 200（console 8081 / decision 8082 各一个）。
- 前端：根 `GET /` 是**构建无关落地页**（跳 `/ui/`，旧原生台 + `/assets/activity.js|css` 已于 F3 退役删除）；SPA 挂 `/ui/`，活动配置台 `/ui/console`（列表 `/ui/console/activities`、**玩法模板 `/ui/console/playbooks`**、验证 `/ui/console/validate`），18 Step 演示台 `/ui/demos`。
- 活动写面/运营验证端点（console，8081）：`/activity-marketing/*`（见 `docs/activity-marketing.md` 接口表）。2026-08 的关键增量：
  - `POST /activity-marketing/bulk-status`，体 `{items:[{activityId,version}],targetStatus}` → **一律 200**，回执 `{succeeded[],failed[{activityId,reason}]}`（部分失败是正常结果，别当错误断言）。
  - `POST /activity-marketing/{activityId}/claim?version=&quantity=` — 秒杀库存**权威扣减**（决策只报价、这里才扣）。抢到 200、抢不到 **409**（`{ok:false,reason}`）。它与 create/status 同属 `console-write-authority` 保护的写路径；该配置非空时，无 authority token 应断言 403。
  - `POST /activity-marketing/addon/options` 与 `POST /activity-marketing/addon/quote?activityId=&item=` — 加价购验证别名，与 decision 端点复用同一服务、租户/JWT 边界和状态语义；options 200，quote 有效 200、失效/伪造 409，不调用 `claim`。
- 只读决策热路径（decision，8082）的验证接口包括 `POST /decision/v1/spu-discount`、`POST /decision/v1/gifts` 与加价购两阶段；另有两个观测 GET：
  - `POST /decision/v1/addon/options` — 加价购第一阶段，只列换购选项。**空列表是正常结果**（`{"options":[],"traces":["无生效加价购活动"]}`），不是错误。
  - `POST /decision/v1/addon/quote?activityId=&item=` — 第二阶段权威报价，**签名里没有价格参数**（防改价）。选项失效返回 **409**。
  - `GET /decision/v1/metrics` — 耗时/回退聚合，`scope=single-instance`（本进程视角，跨实例看 Prometheus）。
  - `GET /decision/v1/by-activity` — 按活动命中量，带 `tagCap=200` + `overCapTag=__over_cap__`（超基数上限的活动并入哨兵，总量仍准）。
  - ⚠️ **console 与 decision 的 `traces` 详略可能不同**：两边仍复用同一 `ActivityQueryService`，但 console 的试算显式开 `explain=true`，决策热路径默认 `false`。`explain=false` 抑制逐候选 trace，结构性与安全回退 trace 仍可出现；当前折扣回退使用 `BenefitEvaluator` 并保留已解析的 `STACK / PRIORITY / MAX`，不再有“资格翻回 DRL”或“空决策统一取 MAX”语义。**断言类型化字段/金额/策略，不要断言 console 与 decision 响应体全等**。
- **优惠验证屏的可观察验收**：场景选择应包含 12 个 `PLAYBOOKS` 条目 + 1 个 random；切场景只预填输入/选择 discount、gifts 或 addon 通道，不能绑定活动或伪造命中。discount 展示命中与减免金额，gifts 展示赠品行，addon 必须先列 options 再 quote 并显式处理 409；成功与 409 都展示本次 quote `traces`。第 N 件折隐藏手填汇总，只允许编辑订单行，并由行项唯一导出 `spuIdList / orderAmount / quantity / lines`。秒杀与加价购要提示“仅试算/报价，不占库存”，并在 API 详情中比较前后库存、观测整页无 `*/claim` 请求。390/768/1440 必须在第 N 件行项态和 add-on 报价结果态都无溢出。上述断言已在 Docker 真链路一次通过（472/0）。
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
- 回归单测历史基线：`./mvnw test`（**2026-08-09** 本机实跑 **BUILD SUCCESS**，共 **314 跑 / 3 skip**：common **121**(3 skip) / drools-lab **0**（该模块无测试）/ console **176** / decision **17**）。含 `DecisionAuthIntegrationTest` 的 decision 401/403/200 边界、`DecisionGoldenSetTest` 金标集（当时确实 Java 与全 Drools 两条路各跑一遍；灰度开关现已退役，见下「旧 DRL 不能当六形态回退」）、`DecisionDdlGuardTest`（decision 的 `ddl-auto` 漂回 `update` 即红）。这组数字只保留作历史对照；本批当前全反应堆证据是本文顶部记录的 **371 tests / 3 skip**。
  - 跑不绿先看坑2 的 `FixedPriceAndClaimTest` 那条，多半是 H2 file 锁而不是真回归。
- 前端单测历史基线：`cd frontend && npm run test`（vitest **154 绿 / 22 文件**，**2026-08-09** 实跑；不作为本批 ValidateView 新测试的当前总数）。
- 前端 E2E 历史基线为 **9 套**：`e2e:dev` / `e2e:catalog` / `e2e:tablet` / `e2e:phone` / `e2e:ruler` / `e2e:bench` / `e2e:playbooks` / `e2e:visual` / `e2e:oidc`，全部使用 `BASE=http://localhost:8095 npm --prefix frontend run <script>`。
- 前 8 套走 header 档（跑前按上面切档）；`e2e:oidc` 走 auth 档 + 真 Casdoor。
  - 最近一次 9 套全绿是 **83 条断言**（visual 10 / dev 7 / catalog 6 / tablet 7 / phone 7 / bench 13 / playbooks 17 / ruler 4 / oidc 12），记录于 `docs/plans/frontend-tech-visual-0809-1424/PROGRESS.md`（提交 `0bd529a`）。
  - 新增的聚焦命令 `BASE=http://localhost:8095 npm --prefix frontend run e2e:validate` 用于验证上述优惠验证契约；它保持为独立第 10 套，不回写上面的 9 套 / 83 条历史基线。2026-08-10 Docker 实跑结果为 **472/0**。
  - `e2e:playbooks` **会真的建一条活动**（`E2E折扣券-<时间戳>`），跑完记得清；`e2e:bench` 会批量上下线。别在需要干净数据的回归前跑。
  - UI 定位契约（testid 清单）：`frontend/e2e/data-testid-contract.md`。

本批聚焦测试的模块归属（写回归命令时不要放错 `-pl`）：

| 模块 | 测试 | 主要守护 |
| --- | --- | --- |
| `activity-common` | `ActivityQuerySafetyFallbackTest` | 共享资格、六形态安全回退、STACK/PRIORITY 策略保留、旧 false 属性不切路 |
| `activity-common` | `AddOnPurchaseTest` | add-on options/quote 资格重判与 traces |
| `activity-console` | `ActivityMarketingAddOnAliasTest` | console alias 200/409、上下文透传、不调 `claim` |
| `activity-console` | `AddOnPurchaseWritePlaneTest` | 加价购写平面校验 |
| `activity-console` | `ActivityAuthIntegrationTest` | alias JWT 边界与 claim `console-write-authority` |
| `activity-console` | `DecisionGoldenSetTest` / `DroolsBenefitGoldenSetTest` | 金额金标；后者现在验证旧 false 配置不改生产路径，不是“全 Drools 对拍” |
| `frontend` | `ValidateView.test.ts` / `e2e/e2e-validation.mjs` | 三通道组件契约 / Docker 真链路（472/0） |

## 玩法 / 权益形态造数速查（2026-08 新增）
`redPackageAmountUnit` 从「装饰字段」变成了**权益形态判别位**，同一个 `redPackageAmount` 数字的含义由它决定：

| unit | 形态 | `redPackageAmount` 含义 | 写平面额外校验 |
|---|---|---|---|
| `元` / null | 金额型（默认） | 减多少钱 | 不允许填 `redPackageMaxDiscount` |
| `折` | 折扣型 | 折数，须 (0,10) | **`redPackageMaxDiscount` 必填且 >0**；且不许同时配阶梯分档 |
| `价` | 一口价（秒杀） | 卖多少钱（与原价无关） | 库存扣减走写平面 `claim`，决策侧只做建议性闸门 |
| `件折` | 第 N 件折 | 折数；「第几件」存 `redPackageRangeAmount` 的 `{"nth":2}` | 决策入参**必须带 `lines` 逐行单价**，否则 fail-closed 不给优惠 |

- 白名单外的 unit 一律 400（防拼错的单位被静默当成金额发钱）。
- `redPackageRangeAmount` 是**多用途列**，靠 JSON 顶层类型与对象键区分：**数组 = 阶梯分档**；对象里的 `{"min":5,"max":20}` = 随机红包区间（`redPackageTakeType=2`，同用户同购物车金额确定），`{"nth":2}` = 第 N 件折。造数时不要把三种结构混写。
- 决策入参 `SpuDiscountRequest` 新增 `storeId` 与 `lines`（`{spuId,unitPrice,quantity}`），均为**纯增量**：不传时老行为一字节不变。配了「店铺」条件的活动此前永远不命中，就是因为入参缺 `storeId`。

## 已知缺口（测到会撞、不是你的环境问题）
- **报价不等于占库**：优惠验证中的秒杀与加价购只试算/报价，不会调用 `claim`。秒杀必须另调写平面 `/{activityId}/claim` 才权威扣减；该接口当前**不幂等**，同一用户重复调用会重复扣减，且 auth 环境应配 `console-write-authority`。`userInventory` 仍无执行路径。
- **旧 DRL 不能当六形态回退**：它不认一口价 / 第 N 件折 / 随机红包；因此生产已固定 `BenefitEvaluator`。`java-benefit-eval=false` 与 `java-eligibility-eval=false` 只是旧配置兼容，**不会切换主路径**；不要再用它们设计对拍或回滚验收。
- **本批 Docker 运行证据已完成**：四眼发布、13 场景边界、秒杀/加价购库存不变、无 `claim` 与结果态响应式一次实跑 472/0；完成后已恢复默认 auth 档并确认六服务就绪。
