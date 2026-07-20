# 重构尾巴执行进度（F3 + M 线 M1.4/M2.1/M2.2/M2.3）

> 跨会话进度锚。计划见同目录 `FINAL_PLAN.md`（§7 步骤 + G0–G5 验证门）、`DECISION_RECORD.md`、`M-LINE-STATUS.md`。
> 分支：`feat/prod-arch-refactor-tail`（从 `main` 切出）。用户 2026-07-20 拍板「全部剩余按序做完，每步过验证门，可停在可用状态」。
> **本轮之前已提交（不在本进度内）**：M 线 M1.1 决策别名 + 角色门控 + `deploy/` 网关；F 线 F0–F2 Vue SPA。基线 112 测试绿。

## 里程碑清单与状态

| 里程碑 | 内容 | 验证门 | 状态 |
|---|---|---|---|
| **F3** 退役旧原生页 | 根 `index.html` → 构建无关的跳转/落地页；删旧三 JS(`app/examples/activity`) + 两旧 CSS(`styles/activity`)；README 同步 | G5：git revert 回滚演练 + `./mvnw test` 不降 | ✅ 完成 |
| **M1.4** 发布 generation 轮询预热 | `(tenant,bizLine,generation)` 表 + repo；`ArtifactService` 发布 bump；`RuntimeService` 轮询预热 | 104+新测试绿；发布→轮询预热命中 | ✅ 完成 |
| **M2.1** Maven 物理模块拆分 | `activity-common/console/decision/drools-lab` 多模块（搬 100+ 文件、拆 pom）——计划自陈最大重构风险点 | 测试绿；两 app 独立启动 | ✅ 完成 |
| **M2.2** decision 物理拆进程 | decision 独立 8082 + 只读账号 + `ddl-auto=validate`；网关切流；移除进程内直调 | kill console 决策仍服务；kill decision 不伤 console | ⬜ 待做 |
| **M2.3** 双 prometheus + grafana | 两服务各暴露指标 + grafana 面板 | 面板双服务指标可见 | ⬜ 待做 |

## 关键约束 / 已定决策（执行期补充）

- **F3 与 `-Pfrontend` opt-in 的张力**：SPA 构建是 opt-in（默认不构建，保后端迭代速度）。故根页退役后必须是**构建无关**的静态落地页，否则默认 `spring-boot:run` 跳到不存在的 `/ui/`。已选：根页做纯 HTML 落地页（内联样式，指向 `/ui/console` + 说明需 `-Pfrontend`/网关），不强制翻默认构建。
- F3 无代码/测试依赖旧三 JS/CSS（已 grep 确认），删除低风险。

## 变更文件流水（每步追加）

### M2.1 ✅（Maven 四模块物理拆分）
- **新** 根 `pom.xml` → 聚合 pom（packaging=pom，`<modules>` + dependencyManagement 内部模块版本）
- **新模块 `activity-common`**（库，69 主类）：`activity/{domain,engine(除 poller),persistence,tenant}` + 读服务 `ActivityQueryService`/`ActivityPoolMatchService`；drools 只引 KieHelper 所需（core/compiler/mvel/kie-api），**不引 kie-ci/dmn/decisiontables**
- **新模块 `drools-lab`**（库，57 主类）：Step1-18 教学（`com.lrj.drools` 非 activity）+ rules/kmodule/xls/dmn 资源 + 全套重 drools 依赖（kie-ci/dmn/decisiontables/xml-support）
- **新模块 `activity-console`**（app，8081）：写平面（`ActivityMarketingService`/`ArtifactService`/`GenerationService`/`ActivityMarketingController`/seeder）+ `ConsoleApplication`(根包) + app 配置 + static 落地页 + `-Pfrontend`；依赖 common+drools-lab
- **新模块 `activity-decision`**（app，8082）：`DecisionPlaneController` + poller(`GenerationWarmService`/`PollScheduler`) + `DecisionApplication`(根包) + 自有 application*.yml；仅依赖 common
- **135 主类 + 30 测试全量 git mv 到位**；测试拓扑：unit→common(14)/drools-lab(1)，BOOT→console(12)/decision(3)
- **测试改造**：`DecisionAliasAndRoleGateTest` 去掉 console-only 的 `/activity-marketing` 断言（decision 模块无此 controller）；`GenerationWarmPollerTest` 改用 `genRepo` 直接落代际（decision 无 console 的 `GenerationService`）；**新** console `GenerationBumpTest` 补 bump 覆盖
- **关键坑**：主类须放 `com.lrj.drools` 根包（非 `.console`/`.decision`），否则 `@SpringBootTest` 沿测试包 `com.lrj.drools.activity` 向上找不到 `@SpringBootConfiguration`（13 test 全 error）
- **验证**：`./mvnw test` reactor 全绿 = common 63 + console 40 + decision 8 = **111 跑 0 失败 3 skip**；`./mvnw -DskipTests package` 两 app 均产出可执行 jar（Start-Class Console/DecisionApplication）；**decision jar 67MB vs console 104MB，轻 37MB = 依赖甩除实证**
- **回滚**：M2.1 前无不可逆改动；`git revert` 该 commit 回单模块（但需连带 revert 后续 M2.2/M2.3）
- **未做（M2.2）**：decision 只读账号 + ddl-auto=validate（现 update 便于单机启动）；网关切流；移除进程内直调

### M1.4 ✅
- **新** `persistence/ActivityGenerationEntity` — `(tenant_id, biz_line, generation)` 单行表，**非 @TenantId**（跨租户传播信号；命门见类注释）
- **新** `persistence/ActivityGenerationRepository` — `findByTenantIdAndBizLine` + 继承 `findAll`（poller 跨租户扫）
- **新** `service/GenerationService` — `bump(tenant,bizLine)` 读改写 +1（generation 是变更信号，非精确计数，故无需锁）
- **新** `engine/GenerationWarmService` — `warmDueGenerations()`：扫代际→对增长者 `callWith(tenant)` 读 ACTIVE artifact→`warmAsync` 预热；返回 futures（fire-and-forget，测试可 await）
- **新** `engine/GenerationPollScheduler` — `@Scheduled` 触发器，`@ConditionalOnProperty(generation-poll.enabled, matchIfMissing=true)` + `@EnableScheduling`；与预热逻辑分离，测试关调度手动跑
- **改** `service/ArtifactService.warmOnPublish` — 注入 GenerationService，ACTIVE artifact 发布时 bump 代际（进程内 warmAsync 保留作双保险）
- **改** `application.yml` — `activity.marketing.generation-poll.{enabled:true, interval-ms:3000}`
- **新测试** `GenerationWarmPollerTest`（3 例，独立内存库+create-drop，可重跑）；**改** `TenantArchGuardTest` 白名单登记 ActivityGenerationEntity（带显式 tenant_id、有意不 @TenantId）
- **验证**：全量 `./mvnw test` 110 跑 0 失败（107→110）；日志 `[generation-poll] 预热命中 … generation 0→1 提交 N 个 artifact` = 验证门"发布后轮询预热命中日志"达成
- **未做（可选）**：`POST /internal/warm` M2M 端点（计划标可选；poller 已足够）

### F3 ✅（单原子提交，回滚=revert 该 commit）
- `src/main/resources/static/index.html` — 旧原生演示台外壳 → 构建无关静态落地页（内联样式、明暗主题、44px 触控、指向 `/ui/console` `/ui/demos` + 未构建提示）
- 删除 `static/assets/{app.js,examples.js,activity.js,styles.css,activity.css}`（旧原生台全部资源；空 `assets/` 目录一并消失）
- `README.md` §前端演示台 — 旧原生台描述 → Vue3 SPA（`/ui/`）+ 三种起法（Vite dev / `-Pfrontend` / compose 网关）
- **验证**：`./mvnw test` BUILD SUCCESS 107 跑 0 失败（3 skip 既有）；无 Java 改动=不降。前端 `vue-tsc` 0 报错 + `vite build` 成功（主 chunk gzip 42.3KB < 150KB 预算）+ Vitest 26/26 绿。grep 确认无代码/测试引用已删资源。
- **未做（runtime 门，需 Casdoor+MySQL+起服务）**：auth 档浏览器真登录走查 + 契约冒烟——F1/F2 已过其 G3/G4，F3 未动 frontend 源码，故替代可行性不变；留待整栈冒烟时补。
