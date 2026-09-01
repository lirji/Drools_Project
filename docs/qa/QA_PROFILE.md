# QA 环境档案 · Activity Rule Platform（活动营销多租户）

> 首次由 /qa-test 勘察生成（2026-07-19）。人工可改，下次直接复用。
> 能力与端点最后同步 **2026-08-28**：新增 confirm/不可变分录、grant outbox 与企业权益中台连接器；
> 前端旧 Demo catalog 已删除。当前实跑为 Maven **524 tests / 3 skip / BUILD SUCCESS**（common **203**
> 含 3 skip / drools-lab 0 / console **294** / decision **27**）+ Vitest **28 文件 / 383 passed**。
> 下文更早日期的数字仅作历史证据，不是当前基线。
>
> 🚩 **开测前先读 [`docs/plans/activity-design-refactor-0812-1232/BREAKING-CHANGES.md`](../plans/activity-design-refactor-0812-1232/BREAKING-CHANGES.md)**：那份是本轮**对外可见变更**的权威清单（4 处 HTTP 状态码、1 处写入口新增拒绝、1 处指标标签取值、2 处观测口径）。**发钱金额零变化**——金标集 52 例、`SnapshotParityTest`、`DecisionQueryCountTest` 全程绿，所以本轮**不需要**重跑金额类回归；要重点回归的是**状态码断言**与**监控查询**。
>
> ⚠️ **`e2e:validate` 的 `pass=472 / fail=0` 是 2026-08-10 的结果，那一次脚本打的是 console 的 `/activity-marketing/*`——走库路径。** 脚本已改打 `/api/decision/*` 并新增 `waitForSnapshot`（见 `frontend/e2e/e2e-validation.mjs` 的 `ENDPOINTS`），**尚未在新端点上复跑**。别把 472/0 当成快照路径的验收证据：陈旧快照、绑定按版本收窄、轮询延迟这些只在快照侧出现的问题，那 472 条断言一条都照不到。
>
> ⚠️ 用例数只认 `./mvnw test` 输出的 `Tests run:` 汇总。**求和 surefire XML 会少数 52 个**（`DroolsBenefitGoldenSetTest` 让父类的 `@Nested` 用例重跑一遍并覆盖同名报告，见 `CLAUDE.md` 坑 14；52 这个数是本机 `./mvnw -pl activity-console test -Dtest=DecisionGoldenSetTest` 实跑出来的 `Tests run: 52`）。本文件早先版本里的 307 / 357 / 371 都源自这个陷阱或已过期，已作废。

## 技术栈 / 启动
- Java 21 / Spring Boot 3.3.5 / Drools 8.44.2 / Maven wrapper。
- **仓库形态（2026-07 · M2.1 起 = Maven 四模块）**：`activity-common`(库) / `drools-lab`(库,Step1-24) / `activity-console`(app,8081,写面+Step1-24+前端`/ui/`) / `activity-decision`(app,8082,只读决策`/decision/v1`)。根 `./mvnw spring-boot:run` **已失效**（父聚合 pom 无 main）——起服务须 `-pl` 指定 app 模块。
- **dev/header 档（QA 常用，auth 关、dev-default 开）**：
  - 活动写面 + 台 + 旧读端点（console，8081）：`./mvnw -pl activity-console spring-boot:run -Dspring-boot.run.profiles=h2`
    - ⚠️ **单起 console 会让「优惠验证」页三通道全废**（2026-08-11 起）。该页默认打**决策平面**，请求走网关前缀 `/api/decision/*`；裸 console 上没有 `DecisionPlaneController`（它在 `activity-decision` 模块），这些请求一律 **404**，页面停在红色「决策服务不可达」而拿不到任何结果卡。这不是页面 bug，是环境不全——详见下面「优惠验证屏的前置条件」。
  - 只读决策热路径（decision，8082，测 `/decision/v1/*`）：**必须补 `ddl-auto` 覆盖**，见坑3
    ```bash
    ./mvnw -pl activity-decision spring-boot:run -Dspring-boot.run.profiles=h2 \
      -Dspring-boot.run.arguments="--server.port=8099 --spring.datasource.url=jdbc:h2:mem:qadecision;DB_CLOSE_DELAY=-1;MODE=MySQL --spring.jpa.hibernate.ddl-auto=update"
    ```
  - ⚠️ **坑1**：本机 8081/8082 常被 Docker 容器占用 → 换端口：`-Dspring-boot.run.arguments="--server.port=8097"`。
  - ⚠️ **坑2**：`h2` profile 用 **file 库**（console `./data/activity-platform` / decision `./data/decision`，单连接锁）→ **同一 file 库不能两个 app 同开**。QA 起干净实例请覆盖内存库：`--spring.datasource.url=jdbc:h2:mem:qadev;DB_CLOSE_DELAY=-1;MODE=MySQL --spring.jpa.hibernate.ddl-auto=create-drop`。
    - **这条也会咬跑测试**：console 测试里只有 `FixedPriceAndClaimTest` 没有 `@TestPropertySource` 覆盖数据源（其余全部覆到 `jdbc:h2:mem:*`），因此它是唯一去抢 `activity-console/data/activity-platform.mv.db` 的用例。本机有 console 在跑、或上一次 `./mvnw test` 被中断留下锁，它会以 `Unable to determine Dialect without JDBC metadata` 报 9 个 error——**这条报错是症状不是原因**，真因在日志更上面的 `Database may be already in use`。处理时先停掉本地 console，再备份或清理该测试数据目录后重跑。
  - ⚠️ **坑3（2026-08 新增）**：decision 的 `ddl-auto` 已从 `update` 改成 **`validate`**（只读平面不碰 DDL，建表由 console 独占）。按老命令直接起 decision 连空库会启动失败：`Schema-validation: missing table [activity_artifact]`。本地单起 decision 有两条路：① 加 `--spring.jpa.hibernate.ddl-auto=update` 自建空表（实测可起）；② 用 compose 整套（MySQL 库由 console 建好）。**注意 H2 file 库单进程锁，console + decision 不可能共用同一个 H2 file 库**，要两 app 共库只能走 compose 的 MySQL。
  - **前端 SPA 需先构建**：`-Pfrontend`（`./mvnw -pl activity-console -Pfrontend spring-boot:run …`）把 Vue 产物拷进 `static/ui/`，或本地 `cd frontend && npm run dev`（Vite :5173）。不构建时 `/ui/` 404、根 `/` 只是落地页。
  - **整套微服务编排**（两 app + frontend nginx 网关 `${DROOLS_UI_PORT:-8095}` + MySQL 单库双账号 + Prometheus `:9090` + Grafana `:3001`）：`./deploy.sh --provision-auth`。Compose 默认 auth 开、dev-default 关。
    - **切 header 档**（跑 `e2e:dev` / `e2e:tablet` / `e2e:phone` / `e2e:bench` / `e2e:playbooks` / `e2e:validate` / `e2e:ruler` / `e2e:visual` 这些走 `tenant-chip` 的脚本必须切，否则被登录守卫弹走）：
      `DROOLS_AUTH_ENABLED=false DROOLS_DEV_DEFAULT_ENABLED=true docker compose -f deploy/docker-compose.yml up -d`
      聚焦 `e2e:validate` 还要加 `DROOLS_FOUR_EYES_ENABLED=true`；脚本先断言 AUTHOR 自审发布返回 **403**（**2026-08-12 起，此前是 409**——四眼拒绝是「不该由你来做」，不是「冲突、重试可能会成」；旧 409 纯属实现细节泄漏：写平面用 `IllegalStateException` 表达它、controller 把所有 ISE 一律映射成 409），再由 APPROVER 发布成功，未开四眼时会正常失败而不是假绿。<br>⚠️ 这条断言**不在 `./mvnw test` 与 `vitest` 的闸门里**（e2e 需要真链路），整轮重构没有照出它，是人工核对时发现并改的——你自己写的四眼用例若还断言 409，请一并改成 403。
    - ⚠️ **改了代码要重建对的镜像**：前端 `/ui/` 由 **gateway 镜像**托管（`drools-platform/activity-frontend`），不在 console 的 JAR 里 → 改前端要 `--build gateway`；改后端要 `--build console decision`（**decision 是独立镜像，只重建 console 时它仍是旧代码**，新决策端点会 404）。
- **Casdoor 档（auth 开，console + decision API 端到端）**：本机 Casdoor `:8000` 启动后用 `./deploy.sh --provision-auth` 幂等登记当前 `DROOLS_UI_PORT` callback；真实浏览器回归为 `BASE=http://localhost:${DROOLS_UI_PORT:-8095} npm --prefix frontend run e2e:oidc`。

## 入口 / 健康检查
- 健康：`GET /actuator/health` → 200（console 8081 / decision 8082 各一个）。
- 前端：根 `GET /` 是构建无关落地页；SPA 挂 `/ui/`，当前页面为概览、活动列表/编辑/详情、玩法模板与优惠验证。旧 `/ui/capabilities` Demo catalog 已删除，Step 1–24 只保留 REST 接口与文档示例。
- 活动写面/运营验证端点（console，8081）：`/activity-marketing/*`（见 `docs/activity-marketing.md` 接口表）。2026-08 的关键增量：
  - `POST /activity-marketing/{activityId}/status`，体 `{version,targetStatus}` — 使用显式状态迁移表（from × to），非法流转 400。`targetStatus=3`（待生效）表示显式预约上线：只接受未来时间窗，预约时执行四眼校验；到开始时间由 console 调度器自动上线，结束时间后自动下线，改回 0 可取消。ONLINE 不能原地改为 3，未来切版应先编辑出新版本。`OFFLINE → ONLINE` 与 `X → X` 同态自转仍是有意保留行为。四眼开启时提交人自审发布或预约返回 **403**。
  - `POST /activity-marketing/bulk-status`，体 `{items:[{activityId,version}],targetStatus}` → 部分失败**仍是 200** + 回执 `{succeeded[],failed[{activityId,reason}]}`（别当错误断言）。`targetStatus` 允许 0/1/2/3；3 是显式预约上线，非法值才在进循环前返回 400。
  - `POST /activity-marketing/{activityId}/claim?version=&quantity=&userId=&orderId=` — 秒杀库存**权威扣减**（决策只报价、这里才扣）。抢到 200；**没抢到按失败种类分流状态码（2026-08-12 起，此前恒 409）**：**400** = 缺 `activityId` / 数量非正 / 限领活动没带 `userId`；**404** = 活动不存在或当前没有上线版本、版本不存在；**409** = 余量不足或不在可用窗口、超出每人限领。**响应体一字节没变**（仍是 `{ok:false,reason}`，分流用的 `FailureKind` 标了 `@JsonIgnore` 不出参），所以旧用例若断言的是 `ok/reason` 不受影响，**断言 409 的会红——那是预期结果，不是回归**。它与 create/status 同属 `console-write-authority` 保护的写路径；该配置非空时，无 authority token 应断言 403。四个 query 参数全可选，但**行为随传不传变**：不传 `version` 打到**当前 ONLINE 版本**（不是最高版）；带 `orderId` 才幂等（幂等键 = 租户+orderId+activityId）；配了每人限领的活动不传 `userId` 直接拒。
  - `POST /activity-marketing/{activityId}/confirm?orderId=&amount=&decisionId=` — HELD→CONFIRMED 的 CAS 确认；首次成功同事务追加正数 ISSUE 分录，重复调用 replay 且保留首次金额。缺参/亚分/溢出 400，未 claim 404，已 RELEASED 409。
  - `POST /activity-marketing/{activityId}/release?orderId=` — 冲正（退款/取消/超时）：归还库存并释放该用户的限领额度。**幂等**（重复释放返回 200 且不重复加库存）。**缺参/空串 `orderId` 现在是 400**（2026-08-12 起），**确实查不到发放记录才是 404**——此前两者都是 404，客服拿到 404 分不清「这一单没领过」和「调用方漏传了参数」，进而**放弃冲正**、库存与限领额度永久漏掉。完全不传 `orderId` 一直是 400（`required=true`，Spring 直接挡）。归还不判活动状态与时间窗——活动结束之后仍会有退款进来。
  - **错误响应体新增 `code` 字段**（2026-08-12 起，**但只在部分出口上有**）：形如 `{"error":"…中文说明…","code":"FOUR_EYES_REQUIRED"}`。`error` 字段名与取值**逐字未变**（前端 `apiClient` 读的就是它），`code` 是纯附加的机器可读分类（`INVALID_ARGUMENT` / `FOUR_EYES_REQUIRED` / `VERSION_CONFLICT` / `DUPLICATE_REQUEST` / `INTERNAL`；`ActivityErrorCode` 里还有一个 `STATE_CONFLICT`，但**全仓 main 源码零抛出点**——`ActivityException` 只有 `versionConflict` / `duplicateRequest` / `fourEyesRequired` 三个工厂方法——按 `code="STATE_CONFLICT"` 写用例永远等不到这个值）。**只有走 `ActivityExceptionAdvice` 的响应才带 `code`**：`ActivityMarketingController` 的 create / status / bulk-status / detail 仍保留迁移期的 per-endpoint `catch`（私有 `bad()` / `conflict()` → 控制器自己的 `record ErrorResponse(String error)`），所以 QA 最常打的三类 400（create 参数非法 / status 非法流转 / bulk-status `targetStatus` 非法）与 create/status 的 409，响应体仍是 `{"error":"…"}`、**没有 `code`**。反过来 `ActivityException` 不是 IAE/ISE 的子类，会**穿过**那些 catch 落到 advice，因此四眼 403、版本冲突、幂等重复这三类即使在 create/status 上也带 `code`。**按端点选断言字段，别一律押 `code`**——`ActivityErrorMappingTest` 就是这么分的：403 那条断 `$.code`，「参数非法仍是 400」「既有端点状态码一位不漂」两条只断 `$.error`。
  - `GET /activity-marketing/grants?orderId=` — 这一单发放了哪些优惠（客服查单）。返回 `activity_grant` 行的列表；`orderId` 必传（缺参 400），传空串返回空列表。
  - `POST /activity-awards/v1/intents` — 企业权益中台内部触发。LEGACY 不入队、SHADOW 只组装/哈希、CENTER 幂等写 AwardIntent outbox；只接受 ONLINE 明确版本，受 `console-write-authority` 保护。relay 默认关闭。
  - `GET /activity-marketing/generation?bizLine=` — 库里当前发布代际。它是决策响应里 `provenance.generation` 的**参照物**：只看决策侧那个数判断不了「我刚发布的那次进去了没有」。**行不存在返回 0**（代际从 1 起，0 = 这条业务线还没发布过任何东西）。
  - `POST /activity-marketing/addon/options` 与 `POST /activity-marketing/addon/quote?activityId=&item=` — 加价购验证别名，与 decision 端点复用同一服务、租户/JWT 边界和状态语义；options 200，quote 有效 200、失效/伪造 409，不调用 `claim`。
- 只读决策热路径（decision，8082）的验证接口包括 `POST /decision/v1/spu-discount`、`POST /decision/v1/gifts` 与加价购两阶段；另有三个观测 GET 与一个**写动作** POST（快照回滚，见下）：
  - `POST /decision/v1/addon/options` — 加价购第一阶段，只列换购选项。**空列表是正常结果**（`{"options":[],"traces":["无生效加价购活动"]}`），不是错误。
  - `POST /decision/v1/addon/quote?activityId=&item=` — 第二阶段权威报价，**签名里没有价格参数**（防改价）。选项失效返回 **409**。
  - `GET /decision/v1/metrics` — 耗时/回退聚合，`scope=single-instance`（本进程视角，跨实例看 Prometheus）。
  - `GET /decision/v1/by-activity` — 按活动的 `hits`（命中次数）**与 `amounts`（发出去的钱）**，带 `tagCap=200` + `overCapTag=__over_cap__`（超基数上限的活动并入哨兵，总量仍准）+ `scope=single-instance`。命中次数回答不了「这个活动花了多少预算」，两个数要一起看。<br>⚠️ **2026-08-12 起加价购也埋点了**，于是它与红包/买赠**共用同一份 200 个 activityId 的预算**（`ACTIVITY_TAG_CAP` 是跨 scene 的全局值）。总量仍准，但「按活动看命中/金额」的分辨率会在活动目录变大时**提前**塌进 `__over_cap__`；造数时一次性建几百个活动，别惊讶于分不出是哪几个。
  - `GET /decision/v1/snapshot[?activityId=]` — **快照探针**：回本租户的桶清单（`bizLine / generation / builtAt / ageSeconds / activityCount`），`bucketCount=0` 表示该租户目前**全部走库**。带 `activityId` 时直接回答 `inSnapshot` + `hostedByBizLines`，即「在哪个桶 / 不在任何桶」。**只读、不发起决策、不占 `ACTIVITY_TAG_CAP` 的标签位**，可放心反复打。<br>⚠️ 但它**不是匿名端点**：`/decision/v1/**` 落在 `ActivityResourceServerConfig` 的 `anyRequest().authenticated()` 里，auth 档（编排默认）无 Bearer 打它是 **401**——别把这个 401 读成「decision 挂了」，那正是本页反复强调的「可达但未授权 ≠ 不可达」。<br>⚠️ 这里的 `ageSeconds` 是**本租户**的桶，与 Prometheus 上的 `activity.decision.snapshot.age.seconds` gauge（`DecisionSnapshotStore.oldestAgeSeconds`，**跨租户**统计）不是同一个数——多租户下两者永远对不上，别拿来互相印证。
  - `POST /decision/v1/snapshot/rollback?bizLine=` — **快照回滚（2026-08-12 新增，决策平面上唯一的写动作）**：把本租户这条业务线的决策指针切回**上一个发布代际**，立刻生效。此前 `DecisionSnapshotStore.rollback` **零生产调用方**（全仓只有测试在调），文档里承诺的「回滚是止损手段」是一张按不下去的按钮。成功 200（`{rolledBack:true,fromGeneration,toGeneration,activityCount,hint}`），**没有上一代时 409**（`{rolledBack:false,hint}`，而不是假装成功）。测它必须知道的四条：① 它**只动本进程内存指针、不写库**（不违反 decision 只读账号边界），因此**只影响被打到的那个实例**，多实例要逐实例调；② **下一次代际推进会把它盖掉**，回滚是止血、真修复仍是 console 侧改配置再发一代；③ **兜底重建（`refresh`）与同代重发（预热失败后的重试）按设计都不占回滚槽位**，所以「刚重启、只发布过一代」必然 409，而「上一次推进只是超龄兜底重建」不会新增可回滚的一代（回滚要么落到更早那次真发布留下的代、要么 409——**它绝不会把你退到几十秒前的自己**）；④ 回滚后 previous 清空，**连按两次第二次必然 409**（不会静默成功）。auth 档它与 create/status/claim/confirm/release、AwardIntent 触发入口共用 `console-write-authority`（配了才生效），走网关是 `POST /api/decision/snapshot/rollback?bizLine=`。
  - ⚠️ **decision 的错误出口口径（2026-08-12 起，与 console 刻意不同）**：决策平面**没有** `IllegalArgumentException → 400` 兜底——它只读、入参极简，抛出的 IAE 只可能是脏数据或真 bug，报成 400 会让告警不响、调用方去改自己那条没问题的请求。所以**未分类异常一律 500 + `{"code":"INTERNAL"}` 且不回显 message**（异常文案里可能带活动 id / SQL 片段，细节只进日志）。反过来 Spring 自己的语义 400 **原样保留**：请求体不是合法 JSON、`/addon/quote` 少传 `activityId`/`item` 仍是 400。**在 decision 上看到 500 就是后端故障，不要当成"我请求写错了"**（回归由 `DecisionErrorMappingTest` 钉死）。<br>⚠️ 但**验证页会把这个 500 显示成红色「决策服务不可达」**（`ValidateView` 按 `status===404 || status===0 || status>=500` 一起判不可达）——所以看到「不可达」时**先看一眼 decision 容器日志**：可能不是进程没起/镜像没重建，而是它真抛了个 `INTERNAL`。两者的处理完全不同。
  - ⚠️ **console 与 decision 的 `traces` 详略可能不同**：两边仍复用同一 `ActivityQueryService`，但 console 的试算走 `DecisionMode.EXPLAIN`、决策热路径走 `DecisionMode.HOT_PATH`（**2026-08-12 起是枚举而不是裸 boolean，且省掉档位的无参重载已删除——两侧都必须显式写出来**，因此不会再有「忘了传、悄悄按默认档跑」的情况）。`HOT_PATH` 抑制逐候选 trace，结构性与安全回退 trace 仍可出现；当前折扣回退使用 `BenefitEvaluator` 并保留已解析的 `STACK / PRIORITY / MAX`，不再有“资格翻回 DRL”或“空决策统一取 MAX”语义。**断言类型化字段/金额/策略，不要断言 console 与 decision 响应体全等**。
- **决策审计日志现在三通道齐全（2026-08-12 起）**：logger `activity.decision.audit` 打单行 JSON，`scene` 取值与指标同一套词汇（`spu-discount` / `gifts` / `addon`，加价购另带 `phase` = `options`|`quote`）。此前**只有红包通道落日志**：买赠生成了 `decisionId` 却从不写，加价购的两个响应连 `decisionId` 字段都没有——拿这两个通道的 id 去日志里查会一无所获。现在 `AddOnOptions` / `AddOnQuote` 都带 `decisionId`（quote 的两阶段共用同一个 id），工单可以按 id 直接检索。日志同时落 `source`（snapshot|db）与 `generation`，「活动版本对、但快照是旧代」这类问题在日志里就能判。
- **优惠验证屏的前置条件（2026-08-11 起，先读这条再去测那一页）**：该页默认打**决策平面**（`plane='decision'`，请求 `/api/decision/*`），因为线上真正跑的是它——此前它固定打 console 的 legacy 读端点，而 console 进程里 store 恒空、必然走库，于是「用来自证优惠有没有生效的工具」恰好是唯一照不到快照侧问题的那条路。所以：
  - **要么起全栈**：`docker compose -f deploy/docker-compose.yml up`（网关 `:8095`，`/api/decision/*` 才有人接），页面在 `http://localhost:8095/ui/console/validate`。
  - **要么在页面上显式切到「控制台走库」**（`data-testid=v-plane-console`）。这样能在裸 console 上跑通，但**那一侧看不到任何快照问题**：陈旧快照、绑定按版本收窄后的候选差异、代际轮询延迟，走库路径一条都不会暴露。切了就别把结论当线上结论。
  - **改后端必须 `--build console decision` 两个都重建**——decision 是**独立镜像**，只重建 console 时它仍是旧代码，`/decision/v1/snapshot` 这类新端点会 404，页面表现为「决策服务不可达」。这条以前是脚注，现在是这一页能不能用的前提。
  - **「决策服务不可达」与「决策未命中」是两种状态，别混为一谈**：404 / 5xx / 网络错误落红色「不可达」横幅；**401/403 不算不可达**（那是「可达但没授权」，页面不降级，退回走库只会把权限配置问题掩盖掉）；能拿到响应但 `hit=false` 才是「未命中」。测试报 bug 时要写清楚是哪一种。
  - **e2e 的取向差异**：`e2e:validate` 走决策平面并显式 `waitForSnapshot`（上限 20s，> 3s 轮询间隔且 < 60s 兜底重建阈值）；`e2e:tablet` / `e2e:phone` 测的是布局不是平面，脚本里会显式点「控制台走库」（它们默认 BASE 是裸 console）。别照着后两者的写法去断言决策结果。
- **优惠验证屏的可观察验收**：场景选择应包含 12 个 `PLAYBOOKS` 条目 + 1 个 random；切场景只预填输入/选择 discount、gifts 或 addon 通道，不能绑定活动或伪造命中。discount 展示命中与减免金额，gifts 展示赠品行，addon 必须先列 options 再 quote 并显式处理 409；成功与 409 都展示本次 quote `traces`。第 N 件折隐藏手填汇总，只允许编辑订单行，并由行项唯一导出 `spuIdList / orderAmount / quantity / lines`。秒杀与加价购要提示“仅试算/报价，不占库存”，并在 API 详情中比较前后库存、观测整页无 `*/claim` 请求。390/768/1440 必须在第 N 件行项态和 add-on 报价结果态都无溢出。上述断言在 Docker 真链路一次通过（472/0），但那是**走库端点**上的结果，见文首告示。<br>2026-08-11 新增的可观察项：**物料来源徽章**（`快照`/`走库` + 代际号 + 「落后 N 代」，N 由 `GET /activity-marketing/generation` 做参照物算出）、**逐活动明细表**（`data-testid=v-items`，**被淘汰的候选也在表里、带 `rejectReason`**——「配了却不发」的答案通常就在这一列）、**快照探针卡**（`v-snapshot-probe`，仅决策平面下出现）、**双打对拍**（默认关）。对拍**排除**五类正常差异：`decisionId`（每次新 UUID）、`traces`（两侧 explain 档位不同）、`mode`、`items` 顺序、`strategy`（策略行 create 时就 upsert，代际只在状态流转时推进，属合法瞬态）；**两侧 `source` 都是 snapshot 时判红**——那说明对拍失效了，在拿快照跟自己比。页面自己也写明：对拍只能照出取数层分歧，两条路共用同一份求值器，**绿 ≠ 算对了**。
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
- 当前后端基线：`./mvnw -q test` 于 **2026-08-28** 实跑成功，**524 tests / 3 skip**：common 203
  （3 skip）/ drools-lab 0 / console 294 / decision 27。新增覆盖包括生命周期调度、分录账、grant outbox
  退避/死信/redrive、AwardIntent 组装与 relay 租约。
  - 跑不绿先看坑2 的 `FixedPriceAndClaimTest` 那条，多半是 H2 file 锁而不是真回归。
- 当前前端单测基线：`cd frontend && npm test -- --run` 于 **2026-08-28** 实跑 **28 文件 / 383 passed**。
- 当前 `package.json` 有 9 个 E2E 脚本：header 档 8 个（dev/tablet/phone/ruler/bench/playbooks/validate/visual）
  + OIDC 档 1 个。`e2e:catalog` 已随旧 Demo catalog UI 删除；旧文档里的 9 套/83 条只作历史记录。
  `e2e:validate` 改打决策平面后仍缺新的全栈实跑证据，不能复用 2026-08-10 走库路径的 472/0。
  - `e2e:playbooks` **会真的建一条活动**（`E2E折扣券-<时间戳>`），跑完记得清；`e2e:bench` 会批量上下线。别在需要干净数据的回归前跑。
  - UI 定位契约（testid 清单）：`frontend/e2e/data-testid-contract.md`。

本批聚焦测试的模块归属（写回归命令时不要放错 `-pl`）：

| 模块 | 测试 | 主要守护 |
| --- | --- | --- |
| `activity-common` | `ActivityQuerySafetyFallbackTest` | 共享资格、六形态安全回退、STACK/PRIORITY 策略保留；「旧开关配 false 也不切回 Drools」现由断言**规则运行时零交互**来守（两个属性本身已删，见下「已知缺口」） |
| `activity-common` | `AddOnPurchaseTest` | add-on options/quote 资格重判与 traces |
| `activity-console` | `ActivityMarketingAddOnAliasTest` | console alias 200/409、上下文透传、不调 `claim` |
| `activity-console` | `AddOnPurchaseWritePlaneTest` | 加价购写平面校验 |
| `activity-console` | `ActivityAuthIntegrationTest` | alias JWT 边界与 claim `console-write-authority` |
| `activity-console` | `DecisionGoldenSetTest` | 金额金标（52 例：Ladder 12 / Ratio 13 / Merge 9 / Eligibility 6 / Precision 6 / Lifecycle 6） |
| `activity-console` | ~~`DroolsBenefitGoldenSetTest`~~ | **不是门禁**：它自身 `Tests run: 0`（继承来的用例全在父类的 `@Nested` 里，跑子类只是把父类那 52 例重跑一遍并覆盖同名报告，见 `CLAUDE.md` 坑 14）。「旧 false 开关不得切回 Drools」真正由 `activity-common` 的 `ActivityQuerySafetyFallbackTest#legacyFalseFlagsCannotSwitchProductionBackToDrools` 守 |
| `activity-console` | `SnapshotParityTest` | 快照 / 走库两条路等价；含 `narrowedBindingStopsPayingOnBothPaths`（绑定收窄后旧 SPU 不得再发钱） |
| `activity-console` | `ActivityErrorMappingTest`（2026-08-12 新增） | 异常 → HTTP **出口**：四眼 403，其余状态码一位不漂（service 层看不出这个差别，只有打到端点才验得出） |
| `activity-console` | `ClaimResultContractTest`（新增） | `FailureKind` 不出参（`@JsonIgnore`）+ 旧兼容构造器（7 参 / 5 参，未标种类）沿用 409 |
| `activity-console` | `GrantLedgerTest` | claim/confirm/release 状态机、首次金额、不可变 ISSUE/REVERSAL、币种与 recon 视图 |
| `activity-console` | `GrantOutboxTest` / `GrantOutboxGatingTest` | 同事务入队、幂等事件、relay 成败、指数退避、DEAD 与 redrive、默认关门控 |
| `activity-console` | `ActivityAwardIntentServiceTest` / `AwardIntentOutboxLeaseTest` | LEGACY/SHADOW/CENTER、payload 幂等冲突、ONLINE 版本约束、relay 租约 CAS |
| `activity-console` | `SnapshotBizLineCollationTest`（新增） | 桶归属按 Java 精确相等：`Retail` 不得漏进 `retail` 桶（用 `IGNORECASE=TRUE` 模拟生产 MySQL 排序规则） |
| `activity-console` | `SnapshotBuildQueryCountTest`（新增） | 快照**构建期**查询数上限（消 N+1）；与守热路径 5 次查询的 `DecisionQueryCountTest` 分工不同 |
| `activity-common` | `EntityJsonOrderTest` / `DecisionReadRepositoryGuardTest` / `OfferSpecArchGuardTest`（新增） | 响应体键序（`id/activityId/version` 在队首）/ 决策侧只读仓储不得出现写方法 / 候选装配唯一入口 |
| `activity-decision` | `DecisionErrorMappingTest`（新增） | 决策平面故障是 **500 + code=INTERNAL 且不回显 message**；Spring 自己的 400（坏 JSON、缺必填 query）不被吞成 500 |
| `activity-decision` | `SnapshotRollbackEndpointTest`（新增） | `POST /decision/v1/snapshot/rollback` 存在、按租户隔离、切指针；**同代重发不得占用回滚槽位** |
| `frontend` | `ValidateView.test.ts` / `e2e/e2e-validation.mjs` | 三通道 + 平面选择 + 对拍组件契约 / Docker 真链路（改打决策平面后待复跑；四眼断言已改 403） |

## 玩法 / 权益形态造数速查（2026-08 新增）
`redPackageAmountUnit` 从「装饰字段」变成了**权益形态判别位**，同一个 `redPackageAmount` 数字的含义由它决定：

| unit | 形态 | `redPackageAmount` 含义 | 写平面额外校验 |
|---|---|---|---|
| `元` / null | 金额型（默认） | 减多少钱 | 不允许填 `redPackageMaxDiscount` |
| `折` | 折扣型 | 折数，须 (0,10) | **`redPackageMaxDiscount` 必填且 >0**；且不许同时配阶梯分档 |
| `价` | 一口价（秒杀） | 卖多少钱（与原价无关） | 必填 `redPackageAmount>0` **且 `inventory≥1`**（否则 400：库存为 null/0 时 `claim` 永远抢不到）。库存扣减只在写平面 `claim`，**决策侧完全不读 `inventory`、没有任何闸门**（连建议性的也没有）——超发路径必须专门覆盖 |
| `件折` | 第 N 件折 | 折数；「第几件」存 `redPackageRangeAmount` 的 `{"nth":2}` | 折数必填且须在 (0,10)（10 折=不打折、0 折=白送，都按配置错误 400）。决策入参**必须带 `lines` 逐行单价**，否则 fail-closed 不给优惠 |

- 白名单外的 unit 一律 400（防拼错的单位被静默当成金额发钱）。
- `redPackageRangeAmount` 是**多用途列**，靠 JSON 顶层类型与对象键区分：**数组 = 阶梯分档**；对象里的 `{"min":5,"max":20}` = 随机红包区间（`redPackageTakeType=2`，同用户同购物车金额确定），`{"nth":2}` = 第 N 件折。造数时不要把三种结构混写。
- 决策入参 `SpuDiscountRequest` 新增 `storeId` 与 `lines`（`{spuId,unitPrice,quantity}`），均为**纯增量**：不传时老行为一字节不变。配了「店铺」条件的活动此前永远不命中，就是因为入参缺 `storeId`。

### 作用域 / 封顶 / 幂等造数（2026-08-11 新增，三类最容易漏测且直接改钱的行为）

- **权益作用域**：减免的基数是「本活动**当前线上版本**绑到的 SPU ∩ 本次请求的 SPU」，不是整单。造数：同一购物车里放「活动绑了的 A」+「没绑的高价 B」，断言减免只按 A 的行小计算。三档语义（`BenefitEvaluator.baseAmount`）——① 候选没有作用域信息 → 按整单（兼容旧装配路径）；② 作用域**覆盖**本次请求的全部 SPU → 按整单（单 SPU 查询、全场券都落这档）；③ 作用域是**真子集** → 必须靠 `lines` 分摊，**拿不到行就判「本活动不适用」，绝不拿整单顶替**。所以「活动只圈了部分 SPU 且请求没带 `lines`」的正确结果是**不适用**，不是按整单算。
- **绑定收窄**：`v1 绑 A/B → 编辑成 v2 只绑 A`，之后单查 B。**正确结果是不命中**。这条以前是真 bug：绑定查询不带 version、旧版本绑定行也不软删，走库路径仍把它当候选（作用域为空），而 AMOUNT（直减/满减）形态不看基数、直接把 `redPackageAmount` 发出去——于是走库照发 50 元、走快照根本不是候选，**两条路发不同的钱**。守卫是 `SnapshotParityTest#narrowedBindingStopsPayingOnBothPaths`；QA 侧建议在两条平面上各跑一遍（用验证页的双打对拍最省事）。
- **出口封顶**：减免额一律不得超过订单金额。造数：三张「满 100 减 50」+ `STACK` 打 120 元订单 → `hitAmount=120` 且 `clamped=true`（并在 decision 日志里有一条 `[clamp]` warn）。**边界**：订单金额缺省或 ≤0 时**不封顶**（AMOUNT 型本就不要求上游传订单金额，此时无从判断是否超发）——这是有意保留的落差，不要当 bug 报。
- **claim 幂等**：同一 `orderId` 重复 `POST /{id}/claim` → 两次都 200，第二次 `replay=true`，且**库存只减一次**。不传 `orderId` 会退化成不幂等（没有订单号就无从判断是不是同一单），造幂等用例时必须传。
- **每人限领**：活动配了 `userInventory` 时，`claim` 不带 `userId` 直接拒（`ok=false`，理由里带上限）；同一 `userId` 累计超过上限也拒。已释放（`/release`）的份额会把限领额度**一起还回**。
- **抢不到时不留脏账**：扣减失败（余量不足 / 已下线 / 不在活动期）会把刚插的发放流水删掉。断言点：失败后 `GET /grants?orderId=` **不应**出现这条记录，否则该用户的限领额度会被永久占掉、这一单也再也 claim 不了（会被幂等分支命中）。

### 「我配了活动却什么都没返回」——标准排查顺序

这是验证页上最高频的困惑，也是最容易被误报成 bug 的一类。按下面的顺序走，每一步都有一个明确的观察点：

1. **先分清是不是「不可达」**：页面出红色「决策服务不可达」= 环境问题（decision 没起 / 镜像没重建 / 走了裸 console），不是活动问题。401/403 是「可达但没授权」，去查 token 与 `console-write-authority`，别切平面绕过去。
2. **看物料来源徽章**：显示「走库」时下面第 4 步整段跳过（走库不存在快照问题）；显示「快照」且带「落后 N 代」时，先等一轮轮询（`generation-poll.interval-ms=3000`）再重打——刚发布的那次可能还没传播到。参照物来自 `GET /activity-marketing/generation?bizLine=`。
3. **看逐活动明细表**：被淘汰的候选**也在表里、带 `rejectReason`**。绝大多数「配了却不发」的答案就在这一列。**2026-08-12 起淘汰原因是一份封闭枚举 `RejectReason`（码与文案钉在同一行，不再是两条手工配对的语句）**，共 8 种——报 bug 时请直接引用码：<br>资格阶段（文案无前缀）：`ineligible`「不满足资格条件」= **正常业务**、`condition-unavailable`「资格条件不可判定」= **故障**（声明了受控约束却拿不到条件树，fail-closed）；算额阶段（文案带「本活动不适用：」前缀）：`no-ladder-tier`、`bad-random-range`、`missing-lines`（第 N 件折缺行项）、`price-above-base`（一口价高于作用域基数或缺订单金额）、`bad-ratio`、`out-of-scope`（作用域基数不可知——活动只圈了部分商品而请求没带 `lines`；**它优先于形态自己的码**，「算不出基数」与「基数不够」是两个排查方向）。<br>同一份码进 Prometheus 的 `activity.decision.reject{reason=…}`，所以指标与页面现在必然对得上；**旧文档里写过的 `price-above-order` 是错的**（代码实际发 `price-above-base`，这正是当年"两处字面量各写一遍"漂移的实证）。表里根本没有这个活动才继续往下走。
4. **用快照探针**：把活动 ID 粘进验证页的「快照里有没有这个活动」，或直接 `GET /api/decision/snapshot?activityId=<id>`。`inSnapshot=false` 而其它一切正常，最常见的根因是**活动的 `bizLine` 为空**（写平面不强制必填）：构建期按 `bizLine` 精确匹配，空的进不了任何桶；而兜底重建（`activity.marketing.snapshot.max-age-ms`，默认 60000）只遍历**已存在**的桶，永远建不出不存在的那个。**这条故障靠等、靠重启、靠兜底都好不了**，只能补 `bizLine` 重发。<br>注意 `provenance` 的三个值（source / generation / buckets）在这条故障上**全绿**——决策照常走快照、代际是别的业务线的正常数、快照也很新，只是这个活动不在里面。这正是探针端点存在的理由。<br>**2026-08-12 起这条故障终于有了主动信号**（不必先怀疑到某个具体活动头上）：每次快照构建会数一次孤儿并打 WARN 日志（`[snapshot] 有 N 个已上线活动的 bizLine 为空…`；日志格式串只带**孤儿数量**与本次构建的 `bizLine`，**没有任何活动 id**——数据源是 `countOrphanBizLine` 那条 `select count(distinct e.activityId)` 计数查询），同时累加计数器 **`activity.decision.snapshot.orphan`**。**它按每次构建的当时存量 `increment(n)`，所以绝对值没有意义**——能用的判据是 `rate(...) > 0`：只要还有这种活动它就一直涨，数据修干净后立刻停。它**刻意不带 tenant / bizLine 标签**（基数账与 `ACTIVITY_TAG_CAP` 同一本）。**计数器与 WARN 都只回答「有几个」，回答不了「是哪几个」**——定位到具体活动仍得靠 `GET /decision/v1/snapshot?activityId=` 逐个探（下面第 4 步）。<br>⚠️ **`bizLine` 的桶归属是大小写敏感的**（Java `equals` 精确相等）：`Retail` 与 `retail` 是**两个桶**，活动写错大小写就进不了你以为的那个桶。别被数据库骗了——生产 MySQL 的 `utf8mb4_0900_ai_ci` 排序规则大小写**不敏感**，SQL 层看起来能匹配上，Java 侧那道兜底比较才是最终判据（而测试跑在 H2 上大小写敏感，照不出这个差异，故由 `SnapshotBizLineCollationTest` 用 `IGNORECASE=TRUE` 专门钉住）。造数时 `bizLine` 请全仓统一小写。
5. **切「控制台走库」再打一次**：走库能出结果而决策不能 ⇒ 问题在取数层（快照没建 / 建漏了），不在配置。两边都不出 ⇒ 回到第 3 步看资格与作用域。

## 已知缺口（测到会撞、不是你的环境问题）
- **报价不等于占库**：优惠验证中的秒杀与加价购只试算/报价，不会调用 `claim`。秒杀必须另调写平面 `/{activityId}/claim` 才权威扣减，auth 环境应配 `console-write-authority`。<br>⚠️ 这条此前写的「`claim` 不幂等 / `userInventory` 无执行路径」**已经反转，别再照着它设计用例**：claim **已幂等**（幂等键 = 租户 + `orderId` + `activityId`，重复提交返回首次结果且不再扣减，`replay=true`）；`userInventory` **已由 `activity_grant` 流水计数执行**（配了限领的活动 claim 不带 `userId` 一律拒）；冲正走 `POST /{activityId}/release?orderId=`，同样幂等，库存与限领额度一起还回。按旧文档写的「重复 claim 应扣两次」会稳定误报为 bug。
- **旧 DRL 不能当六形态回退**：它不认一口价 / 第 N 件折 / 随机红包；因此生产已固定 `BenefitEvaluator`。`java-benefit-eval` 与 `java-eligibility-eval` 这两个开关**2026-08-12 起已从代码里删除**——此前它们是「绑定但不读取」，现在是**根本不绑定**：写进 yml / `-D` 里既不报错也不生效，Spring 直接忽略。不要再用它们设计对拍或回滚验收（进程内已经没有「切到另一套求值语义」的开关了）。<br>**求值出 bug 时的止损手段**是：部署级回滚上一版 jar，或调 `POST /decision/v1/snapshot/rollback` 把物料切回上一代（注意它只切物料、**不换求值器**，且只影响单实例）。
- **指标 `scene` 标签取值变了（2026-08-12，需要改手写的 PromQL）**：`activity_decision_source_total` 的 `scene` 从 `ActivityType.name()` 改成决策通道词汇表——`RED_PACKAGE`→**`spu-discount`**、`BUY_AND_GET`→**`gifts`**、`ADD_ON_PURCHASE`→**`addon`**。此前它与本类另外九个指标的 scene 词汇对不上，后果是 `activity_decision_source_total{scene="gifts"}` **恒为空**，而「按 scene 把回退率与来源占比 join 起来看」正是这条指标存在的理由。**`deploy/` 下没有消费者需要改**（Grafana 面板只查 JVM/HTTP 指标，Prometheus 没有 `rule_files`）；受影响的只有个人保存的临时查询——旧的三条序列会停止增长、三条新序列开始增长，历史数据仍在旧标签下可查。<br>另有一条**已知未收敛**：`activity_decision_fallback_total` 里由规则执行失败产生的那些，`scene` 仍是 `RuleScene.name()`，实际**只可能是 `GIFT`**——裸 String 版 `metrics.fallback` 的唯一调用点是 `ActivityRuleRuntimeService.safeRun`，而 `safeRun` 在整个 main 源码里只被 `evalGift` 以 `RuleScene.GIFT` 调用一次（红包的 `evalDiscount` / `evalLadder` / `evalEligibility` 已随旧 DRL 删除，资格侧回退改走 `DecisionScene` 类型重载，见 `DecisionEligibilityService`），所以 `ELIGIBILITY` / `LADDER` 两条序列**不可能存在**，别去查。按 `scene="gifts"` 统计买赠回退**会漏掉 `scene="GIFT"` 这一类**。它要与 Grafana 同批改，不在本轮范围。
- **买赠命中口径收紧（2026-08-12，看板上像回归，其实是修正）**：`activity.decision.hit{scene="gifts"}` 从「资格通过的候选」改成「**实际出了赠品**的活动（去重）」。引擎分支本来就是这个口径（DRL 的 LHS 要求 `gifts.size()>0`），**变的是回退分支**：一个「资格通过但一件赠品都没配」的活动，回退时命中量会从 1 掉到 0。它本来就没发出任何东西，别报成「回退后活动不命中了」。
- **2026-08-10 那批 Docker 运行证据只覆盖走库路径**：四眼发布、13 场景边界、秒杀/加价购库存不变、无 `claim` 与结果态响应式一次实跑 472/0，但脚本当时打的是 console 的 `/activity-marketing/*`。脚本已改打 `/api/decision/*`，**改后尚未复跑**——快照侧（陈旧快照 / 绑定收窄 / 轮询延迟）目前**没有** e2e 证据。
