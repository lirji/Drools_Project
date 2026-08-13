# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

这是给在本仓库工作的 Claude Code 的指南，描述项目用途、技术栈、约定与已踩过的坑。

## 项目概览

Drools 学习脚手架，配合 LangChain4j 项目用，**不是生产代码**。渐进式 demo，从 Hello World 到"引擎安全护栏 / DMN / 真实业务场景"共 18 个 Step，每步一个 REST 入口。

**仓库形态（2026-07 · M2.1 起 = Maven 四模块）**：本仓库已从「纯 Drools 教学脚手架」长成「多租户活动引擎平台 + 教学 Steps」两部分，物理拆成**聚合父 `pom.xml` + 4 个模块**（`org.drools:drools-bom` 与内部模块版本在父 pom 统一管）。下表 Step 1–18 的代码全在 **drools-lab**，由 **activity-console** 暴露：

| 模块 | 类型 | 职责 |
| ---- | ---- | ---- |
| `activity-common` | 库 | 活动引擎共享内核：`activity/{domain,engine,error,persistence,tenant,metrics,snapshot}` + 读路径服务（`ActivityQueryService` 编排 / `DecisionDataLoader` 取数 / `DecisionEligibilityService` 统一上下文与资格 / `AddOnPurchaseService` / `DecisionAuditor` 三通道留痕）。取数层注入的是六个 **`*ReadRepository`**（继承 `Repository<T,ID>` 而非 `JpaRepository`，`save`/`delete` 在**类型上**不存在——「decision 写不了库」从只靠运行期只读账号，提前到编译期）；商品池匹配 `ActivityPoolMatchService` 2026-08 已上浮到 console（只有写平面用它）。用到 Drools 的地方走 `KieHelper` 运行时编译，**不用 kmodule / KieContainer / DMN**；2026-08 起决策主链路默认是纯 Java（见下「决策链路现状」） |
| `drools-lab` | 库 | **下表 Step 1–18 教学代码全在这里**（`config/DroolsConfig`、`rules/`、`META-INF/kmodule.xml`，及 Step7 决策表 / Step16 `kie-ci` / Step17 `kie-dmn` 等重依赖） |
| `activity-console` | 应用 · 8081 | 写平面（含批量上下线 / 秒杀库存 claim）+ 复用 drools-lab 暴露 Step 1–18 全端点 + 前端 SPA 托管（`/ui/`）+ **唯一 DDL 执行者**；依赖 common + drools-lab |
| `activity-decision` | 应用 · 8082 | 只读决策热路径 `/decision/v1`（含加价购两阶段 + 指标聚合）+ 发布代际轮询预热**兼决策快照构建/切换**；**只依赖 common，不依赖 drools-lab**（jar 更轻，甩掉 kie-ci/DMN），M2.2 起连只读 DB 账号（仅 SELECT）、`ddl-auto: validate`，DDL 由 console 独占 |

两 app 主类都放根包 `com.lrj.drools`（`ConsoleApplication` / `DecisionApplication`，`scanBasePackages/@EntityScan/@EnableJpaRepositories = com.lrj.drools`）；decision 的 classpath 上没有写平面 bean / `DroolsConfig`，结构上就写不了。本地整套编排见 `deploy/`（nginx 网关 host `:8095` + 两 app + MySQL 单库双账号 + Prometheus `:9090` + Grafana `:3001`）。

### 决策链路现状（2026-08 · 分层引擎）

活动侧的决策**主链路默认不进 Drools**。判据是「这条规则需不需要*其它规则的结论*」——阶梯落档是标量分段函数、折扣合并是一次 reduce、资格条件树是单事实布尔谓词，三者都不需要，于是移出规则引擎：

| 环节 | 生产固定路径 | 边界 |
| ---- | ---- | ---- |
| 候选装配（走库 / 快照两条路共用） | `OfferSpec.from(manageRow, ruleRow, gifts)` 是「行 → 候选配置」的**唯一入口**，产出不可变 record；`ActivityCandidate` = `OfferSpec`（配置）+ 本轮计算态 | 加配置字段漏填某条路 = **编译失败**（此前是三份手写字段扇出，已实证漂移两次：`scopedSpuIds`、`redPackageMaxDiscount`）。`OfferSpec.Builder` 只用于手工造候选（`src/main` 里唯一调用点是 `ActivityCandidate` 兜空 spec 的 `OfferSpec.builder().build()`，其余在测试与 `examples/capacity/`），**不是第二条装配路径**，且刻意没有 `toBuilder()` |
| 资格淘汰（红包 / 买赠 / 加价购共用） | `DecisionEligibilityService` 构造唯一上下文，再由 `ConditionTreeEvaluator` 直接解释 `condition_tree_json` | 声明了受控约束却拿不到条件树 → fail-closed 淘汰（`RejectReason.CONDITION_UNAVAILABLE`，与正常的 `INELIGIBLE` **分开计数**） |
| 阶梯落档 / 六形态算额 / 合并 | `BenefitEvaluator` + `BenefitMath` 纯 Java；形态分派是**无 `default` 的 switch 表达式** | 加一种 `BenefitForm` 而漏写分支 = 编译失败。两道横切 guard（随机形态、`redPackageAmount == null`）**必须留在 switch 之外且保持顺序**——随机排在空值判定之前，否则「只配了区间没配固定金额」的随机活动会被静默跳过 |
| 买赠 | 共享资格淘汰后走 `ruleRuntime.evalGift`；关闭/失败时只聚合合格候选 | `rule-engine.enabled=false` 触发安全 Java 回退 |
| 加价购 | 共享资格淘汰后由 `AddOnPurchaseService` 列选项、按当前配置重报价 | 不走 Drools；报价不调用 `claim` |

- **别重建已删掉的东西**：旧红包算额/合并 DRL（`buildDiscountDrl` 与三个 eval）与 `java-benefit-eval` / `java-eligibility-eval` 两个属性字段**都已从代码里删除**，仓库里没有第二份算额权威，也没有「配个开关切回去」这种能力。守卫 `ActivityQuerySafetyFallbackTest#legacyFalseFlagsCannotSwitchProductionBackToDrools`（断言红包链路对 `ActivityRuleRuntimeService` **零交互**）。⚠ **不是** `DroolsBenefitGoldenSetTest`——后者自身跑 0 个用例，见坑 14
- **出 bug 时的止损**：部署级回滚上一版 jar + `POST /decision/v1/snapshot/rollback?bizLine=`（只动本进程内存指针、不写库；多实例要逐个调；下一次代际推进就会盖掉它——是**止血不是回退发布**）。发布门禁是金标集 `DecisionGoldenSetTest` **52 例**。进程内没有「切换求值语义」的开关，别往这个方向设计预案
- **取数**：`DecisionDataLoader` 固定 **5 次**查询（`DecisionQueryCountTest` 钉死），出参是顶层值对象 `Materials`；出口 `ordered()` 按 activityId 定序——两条路的天然顺序都不可靠（快照侧 `Set.copyOf` 的迭代序**每次 JVM 启动翻面**），不定序则「金额并列谁赢」既不稳定、两条路也对不上
- **快照**：`DecisionSnapshotBuilder` 后台建、`DecisionSnapshotStore` 把 `SnapshotSlot(current, previous)` **整体原子替换**，且**回滚槽位只在代际真的前进时才占**（同代重发只换 `current`，否则回滚等于退到出事的这一代）。构建期 ≤7 次查询、与活动数无关（`SnapshotBuildQueryCountTest`）。命中快照零查询，无快照自动回落走库；console 恒走库是因为只有 decision 侧的 poller 会调 `publish`。另有**兜底重建**（超龄按库重建，走 `refresh` 不占发布槽位）
- **指标**：`DecisionMetrics` 打 `activity.decision.*` + `activity.rule.*`。**回退率是头号告警项**；`clamped` 正常业务**恒为 0**，出现一次即疑似配错；`amount` 是「满 300 减 50」被配成「满 3 减 50」时**唯一会动**的读数。`scene` 词汇表统一在 `DecisionScene`、淘汰原因码与中文文案统一在 `RejectReason`（两者钉在同一行，此前手工配对已实证漂移过）

### 权益形态（`red_package_amount_unit` 是判别位）

同一个 `redPackageAmount` 字段按单位解释成不同含义，**未知单位一律回落金额型**（脏数据表现为「按旧行为发」，不是「按猜出来的形态发」）：

| 单位 | `BenefitForm` | `redPackageAmount` 的含义 | 备注 |
| ---- | ---- | ---- | ---- |
| `元` / null | `AMOUNT` | 直接减的钱 | 阶梯分档另配 `redPackageRangeAmount` |
| `折` | `RATIO_ZHE` | 折数 (0,10)，8 = 八折 | 打折基数同样是**作用域基数**（口径见下一行），不是无条件的订单金额；写平面**强制** `redPackageMaxDiscount>0`，且不许同时配阶梯 |
| `价` | `FIXED_PRICE` | 一口价（秒杀）卖多少 | 减免 = **作用域基数** − 一口价（作用域 = 请求 SPU ∩ 本活动当前线上版本的绑定；覆盖整单时即订单金额，是真子集时按订单行小计，拿不到行则不适用）；防超发靠写平面 `claim`，决策侧只报价 |
| `件折` | `NTH_ZHE` | 折数，第几件存在 `redPackageRangeAmount` 的 `{"nth":N}` | 需决策入参带 `lines`（订单行）；**只在作用域内的订单行里数「第 N 件」**——活动只绑了 B，车里的 A 不能替它凑出「第二件」；缺行项 fail-closed 不适用，**不拿均价凑** |

`redPackageRangeAmount` 是**一列三载荷**（阶梯数组 / 随机区间 `{"min","max"}` / 第 N 件 `{"nth":N}`），解析只有一个出口 **`RangePayload.parse`**（sealed，`activity-common/engine`），「该期望哪种载荷」的权威只有 `RangePayload.expectedKind` 一处。**刻意不在 `flatten`/`materialize` 装配期预解析**——那会把解析失败的发现时机从「每次决策按候选 fail-closed 并打 `reject`」挪到「快照后台构建时」，告警链会断。详见 `docs/activity-marketing.md`

**作用域基数**（`BenefitEvaluator.baseAmount`）三档，**顺序不能反**：① `scopedSpuIds == null`（作用域未知）→ 整单；② 作用域覆盖请求全部 SPU（今天绝大多数流量）→ 整单；③ 真子集 → 按订单行分摊，**拿不到行就返回 null 让候选淘汰，绝不用整单顶替**（那正是「只绑 B 的 9.9 一口价把 5000 元的车整车按 9.9 成交」的来源）。注意 `AMOUNT` 形态**根本不调它**。

随机红包走 `redPackageTakeType=RANDOM_AMOUNT` + 区间，金额是**确定性随机**（SHA-256 派生自「活动+版本 / 用户 / 购物车指纹」，刷新不变价、可重放对账）。加价购是活动类型 6，走 `/decision/v1/addon/{options,quote}` 两阶段；console 另有 `/activity-marketing/addon/{options,quote}` 同语义验证别名。第二阶段不读客户端传来的价格，并重新跑资格与读取当前配置；秒杀试算和加价购报价都不占库存。

前端把这些形态包装成有名字的玩法：`/console/playbooks` 给每个玩法一句人话 + 预填参数（做不到的标灰不删卡）；`/console/validate` **默认打决策服务**（线上真正跑的那条），可切「控制台走库」或勾对拍。两个易错点：**「决策服务不可达」与「决策未命中」是两种状态**（401/403 单独判，不降级）；**两侧 source 都是 snapshot 时对拍判红**——那是拿快照跟自己比，而永久绿比飘红更彻底地骗人。对拍只能照出**取数层**分歧，两条路共用同一个 `BenefitEvaluator`，**绿 ≠ 算对了**。详见 `docs/activity-marketing.md`

> 写平面 `ActivityMarketingService.validateCommon` 当前放行**红包(1) / 买赠(5) / 加价购(6)**，并在创建时校验加价购至少一个换购品、品名非空且唯一、加价金额大于 0。前端 `CREATABLE_ACTIVITY_TYPES` 同步为 `[1,5,6]`。

### 教学 Steps（代码在 drools-lab，端点由 console 暴露）

| Step | 主题 | 入口 |
| ---- | ---- | ---- |
| 1 | Hello World（facts / when-then / 多规则） | `POST /hello` |
| 2 | 订单折扣（salience / join / 规则叠加） | `POST /discount/calculate` |
| 3 | 购物车（`accumulate` 聚合 + `modify` 级联） | `POST /cart/checkout` |
| 4 | 风控推荐（`not` / `exists` + 标记 fact 自终止） | `POST /risk/evaluate` |
| 5 | agenda-group 流水线（`setFocus` 栈 / `auto-focus` / `lock-on-active`） | `POST /pipeline/run` |
| 6 | 规则可观测性（listener 攒 `AuditEvent[]`） | `POST /pipeline/audit` |
| 7 | 决策表（Excel `.xls` → DTABLE） | `POST /decision/calculate` |
| 8 | CEP 滑窗风控（`@role(event)` / window / pseudo clock） | `POST /fraud/check` |
| 9 | 规则热加载（`KieHelper` 编译 DRL 字符串进 Map 缓存） | `POST /hot/upsert` + `/hot/run/{name}` |
| 10 | KieSession 持久化（`Marshaller` → JPA byte[]） | `POST /loyalty/start` + `/loyalty/{id}/purchase` |
| 11 | StatelessKieSession 对比（复用 discountKBase） | `POST /stateless/calculate` + `/stateless/batch` |
| 12 | TMS（`insertLogical` vs `insert`，自动 retract） | `POST /tms/compare` |
| 13 | 后向链 + query（`isContainedIn` 递归） | `POST /backward/contains` |
| 14 | 引擎安全护栏（`fireAllRules(max)` / `halt()` / `AgendaFilter`） | `POST /guard/runaway` + `/guard/timeout` + `/guard/canary` |
| 15 | 可观测性指标（Micrometer → Prometheus） | `POST /metrics/discount` + `GET /actuator/prometheus` |
| 16 | KieScanner + KJAR（绑 ReleaseId 热替换 KieBase） | `POST /scanner/deploy` + `/scanner/run` |
| 17 | DMN（`.dmn` + FEEL + DRG，走 `DMNRuntime`，非 DRL 体系） | `POST /dmn/price` |
| 18 | 营销活动资格判定（Step 9+10+4 合体，规则即数据 + rehydrate） | `POST /campaign/create` + `/campaign/{id}/check` |

**每个 Step 的详细说明、完整 REST 接口表、各 Step 特有的 DRL 语义 / 实现注意点见 [`docs/steps-guide.md`](docs/steps-guide.md)。** 改某个 Step 前先读那里对应条目。

后续（LLM 联动）按需扩展，**没需求时不要提前加**。Step 16 的 `kie-ci` 是重依赖（拉进 maven-core / aether），且 `installArtifact` 会真写 `~/.m2/repository/com/lrj/rules/`（demo 自己的 GAV，每次 deploy 覆盖，清理 `rm -rf ~/.m2/repository/com/lrj/rules`）。Step 10 / 18 的 JPA 仅服务于持久化 demo，不要扩成"全项目状态都进数据库"。

## 技术栈与版本背景

- Java 21 / Spring Boot 3.3.5 / Drools 8.44.2.Final / Maven (wrapper)
- **为什么选 8.44.2.Final**：Drools 9.x 仍偏 incubator；8.44.2 是社区验证过的 Spring Boot 3.3 + Java 21 稳定组合。Drools 10（Apache KIE 改名后的新线）文档/教程跟不上，本项目不追
- **数据库**：默认 **MySQL**（`mysql` profile + `mysql-connector-j`），备用 **H2 file**（`h2` profile）。Step 10 / 18 的 JPA 持久化用它。两个驱动 pom 都留，靠 `spring.profiles.active` 切换，连接参数全走环境变量（见下）

## 常用命令

> **M2.1 起是 Maven 多模块**（聚合父 pom + 4 模块：`activity-common` / `drools-lab` / `activity-console`(app,8081) / `activity-decision`(app,8082)）。根 `./mvnw spring-boot:run` **不再可用**（父是聚合 pom，无 main）；起服务要 **`-pl` 指定 app 模块**。模块拆分详情见 `docs/plans/prod-arch-refactor-0719-1330/`。

```bash
# 起 console 服务（写平面 + Step1-18 + 前端 /ui/，8081）；连接走环境变量覆盖（不写死真实值）
DB_HOST=localhost DB_PORT=3306 DB_NAME=drools_demo DB_USERNAME=root DB_PASSWORD=yourpass \
  ./mvnw -pl activity-console spring-boot:run
./mvnw -pl activity-console spring-boot:run -Dspring-boot.run.profiles=h2   # 没 MySQL 时切 H2 file
# 起 decision 服务（只读决策热路径 /decision/v1 + 发布代际轮询预热，8082）
./mvnw -pl activity-decision spring-boot:run -Dspring-boot.run.profiles=h2
./mvnw -pl activity-console -Pfrontend spring-boot:run   # 顺带构建 Vue SPA 拷进 static/ui/

./mvnw test                   # 跑全 reactor 测试（common 193（含 3 skipped）+ console 256 + decision 27 = 476，2026-08-12 本机实跑；drools-lab 不产出可执行用例——唯一的 @Test 类 `VipDiscountSheetGenerator` 命名不匹配 surefire 默认模式，从不运行）
# 前端：cd frontend && npx vitest run   # 285 个用例 / 25 个测试文件（2026-08-12 本机实跑）
./mvnw clean package          # 打 4 模块，两 app 出可执行 jar（decision 更轻，甩掉 kie-ci/dmn）
./mvnw clean compile          # 只编译 Java；不会校验 DRL 语法
# 单模块：./mvnw -pl activity-common test
# ⚠ 改了 activity-common 却只跑 `-pl activity-console test`，Maven 会用 ~/.m2 里的**旧 jar**，
#   你的改动根本没进去。表现极具迷惑性：common 的单测绿、console 的集成测试红。
#   跑下游模块前先 `./mvnw -pl activity-common install -DskipTests`，或直接加 `-am`。

# 起整套微服务编排（nginx 网关 :8095 + console + decision + MySQL 单库双账号 + Prometheus :9090 + Grafana :3001）
docker compose -f deploy/docker-compose.yml up --build   # 然后浏览器开 http://localhost:8095/ui/console
```

**测试**：console 只有 `FixedPriceAndClaimTest`（9 个用例）吃 `h2` profile 的**文件库**（`activity-console/data/drools-demo.mv.db`，其余 h2 用例都用 `@TestPropertySource` 覆到内存库），别的进程占着它时（本地起着 console、或另一处并行在跑 `./mvnw test`）会整片报 `Database may be already in use` + `Unable to determine Dialect`——是环境冲突不是代码回归，串行跑即可。
**端口**：console 8081 / decision 8082，跟主项目 LangChain4j (8080) 错开。console 改端口看 `activity-console/src/main/resources/application.yml`，decision 看 `activity-decision/.../application.yml`。
**数据库 profile**：console / decision **各自带一套** `application.yml`（公共配置 + `spring.profiles.active: mysql` 默认）；数据源细节分到 `application-mysql.yml` / `application-h2.yml`。**只有 console 的 mysql URL 带 `createDatabaseIfNotExist=true`**（库不存在自动建）；decision 的刻意不带——它连只读账号、本来也没有建库权限，对着不存在的库起 decision 会直接连接失败而不是自动建好。连接参数 `DB_HOST`/`DB_PORT`/`DB_NAME`/`DB_USERNAME`/`DB_PASSWORD` 都能用环境变量覆盖。

## 已踩过的坑（务必先读，再动 pom / DRL）

> **编号是稳定 ID，被 59 处引用（含三处代码注释），永不重排、永不复用。** 分两档只是排版：
> 「测试抓不住」的必须读完；「有守卫」的知道它存在即可——真踩了会**响亮地失败**，且失败信息自带上下文，
> 完整来龙去脉在代码注释与 `docs/architecture.md`。

### 测试抓不住的，只能靠人记住

3. **不要随便加 `update($fact)` → 会死循环**：
   - `update()` 重新评估所有依赖该 fact 的规则
   - 本项目 LHS 条件都看 `vipLevel` / `totalAmount` / `yearsSinceRegistration`（不可变字段），修改 `finalAmount` 不会让条件失配 → 规则永远满足，被反复触发，请求挂住
   - `no-loop true` 只防"自己 consequence 重新激活自己"，**不防其他规则的 update 间接重新激活自己**
   - cross-rule 防护要用 `lock-on-active true` + `agenda-group`
   - **本 demo 根本不需要 update()**，教学上的"什么时候用 update()"放在 Step 3 真实级联（`modify` + goldStatus）里讲

4. **DRL 是运行时解析**，`mvn compile` 过了不代表规则没语法错。改完 DRL 必须至少启动一次或跑一次冒烟请求

6. **RHS 没有 record accessor 糖** — LHS 引擎自己适配 record，但 RHS 直接编译成 Java 代码，没有适配层。所以 `$p.getMessage()` 对 record `Promotion` 会编译失败，必须写 `$p.message()`。DRL 是 lazy compile，改完重启后**第一次请求**才触发重新编译并报错，冒烟一次比读启动日志可靠

7. **MySQL 下 `@Lob` 大字段会被截断** — `@Lob byte[]`（Step 10 session 快照）在 MySQL 默认建成 64KB `blob`、`@Lob String`（Step 18 DRL 文本）建成 64KB `text`，大会话 / 长 DRL 会超限。本项目改用 `@JdbcTypeCode(SqlTypes.LONGVARBINARY)` / `@JdbcTypeCode(SqlTypes.LONGVARCHAR)`，映射成 MySQL `longblob` / `longtext`，H2 下也够装。H2 时代用 `@Lob` 不暴露这个坑，换 MySQL 才踩到

12. **库存扣减只能是一条原子 UPDATE，且必须在写平面** — `ActivityManageRepository.decrementInventory` 把「判余量」和「减一」压进同一条 `update ... where inventory >= :n`；**绝不能先 SELECT 再 UPDATE**（check-then-act 竞态，低并发测不出、大促必现）。返回 0 = 没抢到，调用方不能忽略返回值。决策服务连只读账号写不了库，所以分工是「决策只报价、`POST /activity-marketing/{id}/claim` 才是提交」；claim **已幂等**：先插 `activity_grant` 发放流水（唯一约束 `tenant+order_id+activity_id`）再原子扣减，重复提交返回首次结果；扣减失败会把刚插的流水删掉，不留「有账无货」。不传 `version` 时解析成**当前 ONLINE 版本**（此前取最高版=草稿，闸门装错了行），扣减谓词另含活动状态与时间窗。冲正走 `POST /{id}/release`。它已列入 `console-write-authority` 保护的写路径；auth 生产环境必须配该 authority，不要依赖 demo 默认空值

14. **别用「求和 surefire XML」来数用例数，会少数 52 个** — `DroolsBenefitGoldenSetTest extends DecisionGoldenSetTest`（全仓库唯一的测试类继承）。父类的用例全在 `@Nested` 内部类里，跑子类时 JUnit 会把这些嵌套类**再发现一遍**，但它们仍按**父类**的 `@TestPropertySource` 执行、并写进**同名** `TEST-…DecisionGoldenSetTest$Ladder.xml`，第二遍直接覆盖第一遍。于是文件求和得 console 204、Maven 自己报 256（差额恒为 52 = 金标集的用例数）。**以 `./mvnw test` 输出的 `Tests run:` 汇总为准**（当前权威数见「常用命令」那行）。历史上文档里的 307 / console 147 就是这么数出来的错数字；另有 371 / 430 那两批只是**过期**的旧总数，别把两类混为一谈（⚠ 那批旧总数里也出现过 `console 204`，与今天 XML 求和恰好同值，纯属巧合）。<br>顺带两个后果：① 那 52 个用例**白跑一遍**（约 10 秒 + 一个多余的 Spring 上下文）；② `DroolsBenefitGoldenSetTest` **自身用例数为 0**——它想验的「不会换回 DRL 求值器」实际由 `ActivityQuerySafetyFallbackTest#legacyFalseFlagsCannotSwitchProductionBackToDrools` 守着（两个开关字段删掉后，它改为断言红包链路对 `ActivityRuleRuntimeService` **零交互**），别把前者当门禁

18. **控制台「优惠验证」页依赖 decision 进程 + 网关 `/api/decision` 前缀，缺哪一样都是静默失败** — 页面默认打决策平面，而 `decision` 这个 service 的 base **必须**是网关前缀 `/api/decision`（`deploy/nginx.conf` 只有 `location /api/decision/` 会 rewrite 到 decision 容器 **8080** 的 `/decision/v1/`——编排里 decision **不对外映射端口**，只经网关可达；`:8082` 是本机 `-pl activity-decision spring-boot:run` 与 vite proxy 的地址，与网关那一跳无关）；写成后端真实路径 `/decision/v1` 会落到兜底 `location /` 打到 console，而 console 的 classpath 上没有 `DecisionPlaneController`，必 404。vite dev 另需一条**独立** proxy `^/api/decision(/|$)` → :8082 带 rewrite（不能复用已有的 `decision` 前缀，那是 Step 7 教学端点 `/decision/calculate` 的，指向 console）。**没配 proxy 时的失败形态极具迷惑性**：dev server 把 `/api/decision/*` 当 SPA 路由返回 `index.html`，于是 `ok:true` 但 `json=null`，页面报「响应为空」而**不是 404**，看起来像后端 bug。同理裸 console（`:8081` / e2e 的 `:8097`）旁边没有 decision 进程，`e2e-tablet-smoke` / `e2e-phone-smoke` 因此显式点「控制台走库」（它们测布局不测平面）。TS 只校验 `BASES` 里有没有 `decision` 这个键、**不校验字符串对不对**，写错了 typecheck 与全部单测都绿，只在真实浏览器里暴露——改这一行必须跑 e2e

### 有具名守卫测试的，知道它存在即可

1. **`org.kie:kie-bom` 在 8.44.2 没发布** —— 根聚合 pom 用 `org.drools:drools-bom`。**构建期响亮失败**，照报错改即可

2. **Drools 8.x 的 XML 解析要显式加 `drools-xml-support`**（只 `drools-lab` 需要，它走 kmodule）。**启动期响亮失败**，报错直接点名该模块

5. **Java record 在 DRL 的 LHS 里能正常用**（`Customer( age >= 18 )`，Drools 8.x 自动试 record accessor）。这条是确认信息，不是坑——与坑 6 配对读

8. **MySQL 的 URL 必须带 `characterEncoding=UTF-8` + `serverTimezone`**（DRL/reason 里有中文、字段是 `Instant`）

9. **新增受租户约束的路径要同步扩 `TenantContextFilter` 的 URL 模式** —— 漏了表现为「A 租户查到别人的活动」，而单测大多跑在 dev-default 下照不出来。守卫 `DecisionTenantHeaderTest`

10. **decision 的 `ddl-auto` 必须是 `validate`**（它连只读账号）。守卫 `DecisionDdlGuardTest`（直接读源文件而非 Spring 环境）

11. **`redPackageAmountUnit` 是「这个数字是什么意思」的判别位**，不是装饰字段；未知单位回落 `AMOUNT` 是刻意的 fail-safe。形态分派现在是**无 `default` 的 switch 表达式**，加形态漏分支 = 编译失败。取整只能在 `BenefitMath` 一处，一律 `RoundingMode.DOWN`

13. **activityId 不能无上限地当 Prometheus 标签**（活动是运营随手能建的，基数爆炸会在大促当天带走整套监控）。`ACTIVITY_TAG_CAP = 200`，超出并入 `__over_cap__`。守卫 `DecisionMetricsTest`

15. **随机红包的种子指纹读 `randomSeedSpu` 而不是 `spuId`**，数值段要过 `canonical()` —— 改一个字节 = 全量重抽、历史对账全崩。键名钉在 `DecisionAttrs`，守卫 `DecisionContextFieldsTest`（用例写死字面量，改常量名照样红）

16. **`ActivityCandidate.scopedSpuIds` 的 `null`（作用域未知 → 按整单算）与空集（已知）语义不同**，两条装配路径都必须填。现在只有 `OfferSpec.from` 一个装配入口，守卫 `OfferSpecArchGuardTest` + `SnapshotParityTest`

17. **走库路径的候选身份必须按当前线上版本收窄** —— 空作用域拦不住 `AMOUNT` 形态（它不调 `baseAmount`），会「走库照发、走快照根本不是候选」。守卫 `SnapshotParityTest#narrowedBindingStopsPayingOnBothPaths`

19. **快照桶归属要留着 Java 侧精确 `equals`，SQL 下推只省传输量** —— 生产 MySQL 的 `utf8mb4_0900_ai_ci` 大小写不敏感，会把 `Retail` 收进 `retail` 桶（**改变谁能被发钱**），而 H2 默认大小写敏感、测不出来。守卫 `SnapshotBizLineCollationTest`（`IGNORECASE=TRUE` 复现生产排序规则）

## 代码结构（按职责，不是按目录）

> 下列前 8 条「职责」均属 **drools-lab**（Step 1–18 教学，包 `com.lrj.drools.*`，路径前缀 `drools-lab/src/main/…`）；活动引擎平台代码在 **activity-common**（包 `com.lrj.drools.activity.*`），两 app 只放各自 controller/service 薄壳（见末尾三条）。

- `domain/` — fact 类型。record (`Customer`, `OrderItem`, `Promotion`) 用于不可变事实；mutable POJO (`Order`, `Cart`) 用于会被规则改字段的事实。`Promotion` 是 Step 4 的"标记 fact"，规则自己 insert 出来给 `not` 检测
- `service/` — KieSession 生命周期。**每次请求 `newKieSession` + `fireAllRules` + `dispose`**，KieSession 线程不安全，不要为了"省"复用。StatelessKieSession（Step 11）/ DMNRuntime（Step 17）线程安全，可当字段缓存复用
- `config/DroolsConfig.java` — `KieContainer` 注成单例 Bean。**程序化 `KieFileSystem` 构建**（不是 `getKieClasspathContainer()`）：扫 `.drl` 自动加 + `.xls` 标 `ResourceType.DTABLE` + `.dmn` 标 `ResourceType.DMN`，因为 8.44.2 的 ClasspathKieProject 不识别决策表 / DMN
- `resources/rules/<kbase>/*.drl` — DRL 文件，**目录名必须和 `kmodule.xml` 里 `<kbase packages="...">` 对齐**
- `resources/META-INF/kmodule.xml` — 声明各 kbase + ksession（`fraudKBase` 用 stream mode + pseudo clock；Step 12 两个 kbase 隔离避免 logical / regular 衍生 fact 互相污染；`dmnKBase` 只声明 kbase 不带 ksession）
- `audit/`（Step 6）/ `metrics/`（Step 15）/ `guard/`（Step 14）— "挂在 session 上的横切组件"，实现 Drools listener / `AgendaFilter` 接口，按请求挂一个实例。读规则元数据走公共 API `Rule.getMetaData()`
- `persistence/`（Step 10 / 18）— JPA entity + Spring Data repo。大字段用 `@JdbcTypeCode`（不是 `@Lob`，见坑 7）
- Step 16 `ScannerService` **不走** `DroolsConfig` 那个 classpath KieContainer——它自己维护一个绑 `ReleaseId` 的 container，好让 KieScanner 热替换 KieBase。<br>Step 17 `DmnService` 恰恰**相反**：它构造注入的就是 `DroolsConfig` 那个唯一的 `KieContainer`，再 `KieRuntimeFactory.of(kieContainer.getKieBase("dmnKBase")).get(DMNRuntime.class)` 派生 `DMNRuntime`，**不绑 ReleaseId**（绑 ReleaseId 的只有 Step 16）。`DMNRuntime` 线程安全，当字段缓存复用

活动引擎平台三模块（`com.lrj.drools.activity.*`）：

- `activity-common · activity/{domain,engine,error,persistence,tenant,metrics,snapshot} + service/` — 活动引擎共享内核：多租户事实 / 规则编译引擎（`KieHelper` 运行时编译 + 足迹加权 LRU 缓存，**非 kmodule/KieContainer**）/ 活动·规则·条件·幂等·发布代际的 JPA / 租户上下文。console 与 decision 共享；Step 10/18 那套教学 JPA 不在这里、仍在 drools-lab。读路径按职责再切三层：
  - `service/ActivityQueryService` 只做**编排**（资格 → 阶梯 → 算额 → 合并 → 回退），`service/DecisionDataLoader` 只做**取数**（快照优先，否则固定 5 次查询）
  - `service/DecisionEligibilityService` 是 discount / gifts / addon 唯一的请求属性映射与资格淘汰（属性键由 `domain/DecisionAttrs` 常量化——`randomSeedSpu` 改一个字节 = 全量随机红包重抽，见坑 15）；`engine/{BenefitEvaluator,BenefitMath,ConditionTreeEvaluator,RandomRangeParser,RangePayload}` 是**纯 Java 求值层**，六形态固定走这里；`engine/{ActivityDrlBuilder,ActivityRuleRuntimeService}` 仍有三条**生产**路径：买赠 `evalGift`（唯一被执行的 DRL）、写平面创建/预览时 `buildEligibilityDrl` → `compileOrGet` 编译校验（产物落 `activity_condition.generated_drl`，是**生产数据**不是对照资产）、decision 侧 `warmAsync` 预热。真正没有生产调用方的只有 `buildLadderDrl`（仅测试与 `examples/capacity/` 当负载生成器）。两个旧 `java-*` 属性**已从代码里删除**，不存在「配了能切回红包 DRL」这回事
  - `snapshot/{DecisionSnapshot,DecisionSnapshotBuilder,DecisionSnapshotStore}` 代际快照包；`metrics/DecisionMetrics` 决策链路指标（含 activityId 标签基数上限）
  - **词汇表与装配**（都在 `domain/`，改它们等于改契约）：`OfferSpec`（行 → 候选配置的唯一装配入口）/ `DecisionScene`（scene 标签）/ `RejectReason`（淘汰码 + 中文文案同源）/ `DecisionMode`（`HOT_PATH` vs `EXPLAIN`，取代裸 boolean `explain`；**四个无参重载已删**，每个调用点必须显式表态）/ `DecisionAttrs`（决策属性键）；`service/{Materials,DecisionAuditor}` 是取数出参与三通道留痕
  - `error/{ActivityException,ActivityErrorCode,ActivityErrorBody}` — 带**分类**的领域异常，HTTP 状态由 `ActivityErrorCode.httpStatus()` 决定，与「抛的是哪个 JDK 异常」脱钩（四眼失败因此从 409 变 403）。它继承 `RuntimeException` 而非 ISE 是刻意的：要能**穿过** console 里迁移期保留的旧 `catch`
  - `persistence/` 两处结构约束：六个 **`*ReadRepository`**（继承 `Repository<T,ID>`，读路径上手滑写库编译不过，`DecisionReadRepositoryGuardTest` 守）；两层 `@MappedSuperclass`（`TenantScopedEntity` 租户 + 双时间戳 / `SoftDeletableTenantEntity` 再加 `is_del`——`activity_grant` 是账不是配置，**没有** `is_del` 列所以只继承第一层）把 11 个实体里各写一遍的这几列与访问器收上来（**没消掉**的是各处 `save*` 里的 `setIsDel(NOT_DEL)` 等调用点，仍逐处显式落值），并靠超类上的 `@JsonPropertyOrder({"id","activityId","version"})` 把响应键序修回（Jackson 默认把超类属性排在子类前，`EntityJsonOrderTest` 钉住）
- `activity-console · activity/{controller,service}`（应用，8081）— 写平面（`ActivityMarketingService` 配置写入口 / **`GrantService`** 发放台账（`claim` / `release` / `grants`，2026-08 从 977 行的 `ActivityMarketingService` 拆出，后者保留同名**委派**方法，调用方不用改）/ `ActivityVersionResolver` / `ActivityPoolMatchService` / `ArtifactService` / `GenerationService` + seeder）+ legacy `/activity-marketing` 读端点（试算显式 `DecisionMode.EXPLAIN`，决策平面走 `HOT_PATH`）；main = `ConsoleApplication`，**唯一 DDL 执行者**。异常出口是 `ActivityExceptionAdvice`（`basePackages` 只圈本包，不误伤 classpath 上 drools-lab 的 Step 教学 controller），它**刻意没有** `IllegalStateException → 409` 兜底：advice 的作用域是整个包，而 `list` / `grants` 上的 ISE 是 bug 不是冲突，报成 4xx 会让写平面的故障从监控上消失、还让调用方去重试一个永远不会成功的请求；create / status 上原有的 per-endpoint `catch` 原样保留，状态码一位不漂。<br>「当前是哪一版」有**两套互斥定义**，现由 `ActivityVersionResolver` 具名化：`latestDraftVersion`（最高未删除版 = 编辑基线 / `changeStatus` 缺省，通常是**草稿**）与 `currentOnlineVersion`（最高 ONLINE 版 = 正在发钱的那版 = `claim` 缺省 = 决策侧认的那版）——把秒杀库存的闸门装在草稿上，等于线上版本一件没少。写平面语义要点：编辑**不下线**正在服务的版本（线上版与草稿并存），上线时在同一事务里把该活动其它 ONLINE 版本退役（原子指针切换）；`changeStatus` 现在过一张**状态迁移表**（from × to，今天所有合法流转都已放行，包括原先那个不对称的 `OFFLINE → ONLINE`，只是让它显式），且 `targetStatus=3`（`PENDING_EFFECT`）在写入口**直接拒**——它是 `fromCode` 认可的合法码却零生产者零消费者，置成它的活动代际照常 bump 但永远进不了任何读路径（控制台显示成草稿、决策永远不命中）；`POST /bulk-status` 批量上下线**逐条处理、失败不影响已成功项 + 部分失败回执**（部分失败仍是 200；但 `targetStatus` **本身**非法时在进循环之前就 400，否则几十条各失败一次、回执里全是同一句话。`version` 允许为 null，但调用方应按列表行传显式 version，否则会打到草稿而不是正在服务的版本）；`POST /{id}/claim` 抢占秒杀库存，**失败按种类分流状态码**（`ClaimResult` 上的 `@JsonIgnore FailureKind`：缺参/数量非正/限领没带 userId → 400，活动或版本不存在 → 404，余量不足/超限领 → 409；响应体一字节没变，映射是无 `default` 的 switch 表达式）——此前恒 409，而 409 的标准语义是「重试可能成功」，参数写错那一类会被下游无限重试到活动结束；`POST /{id}/release?orderId=` 是退款/取消/超时的**冲正**入口（幂等，归还库存并解除该用户的限领占用；**缺参是 400、确实查不到发放记录才是 404**——此前两者都 404，客服无法区分「这一单没领过」与「调用方漏传 orderId」，后者会导致放弃冲正、库存与额度永久漏掉）；`GET /grants?orderId=` 按单查发放记录（客服「这一单用了哪些优惠、各发了多少」的数据源）。create/status/bulk-status/claim/**release** 一并受已配置的 `console-write-authority` 保护（release 不设防的话，反复调它就能把限量活动的库存刷到任意大）；创建入参新增 `userInventory`（每人限领，null/≤0 = 不限；配了它而 claim 不带 `userId` 一律拒绝——无从判断是不是同一个人时放行等于这条限制不存在，计数按流水且排除 `RELEASED`，否则「买了又退」会永久占掉额度）。**任何状态变化都 bump 发布代际**，不只是上线——发布 v2 会在同事务里退役 v1，此时跳过 bump 意味着 decision 永远停在「服务已被退役的 v1」；唯一的例外是 `bizLine` 为空时跳过 bump（`activity_generation.biz_line` NOT NULL，硬插会在同事务抛约束违例把刚写下的状态一起回滚，「下线传播不出去」升级成「下线根本做不到」），状态照常落库、只打 warn。另有 `GET /generation?bizLine=`（行不存在返回 0）暴露库里当前代际，作为决策侧回显 `provenance.generation` 的**参照物**——只看决策那一侧的 7 是判断不了「我刚发布的那次进去了没有」的
- `activity-decision · activity/{controller,engine}`（应用，8082）— 只读 `DecisionPlaneController`：`/decision/v1/{spu-discount,gifts}` 热路径、`/addon/{options,quote}` 加价购两阶段、`GET /metrics` + `/by-activity` 指标聚合（**本进程单实例视角**，跨实例仍看 Prometheus）、`GET /snapshot[?activityId=]` 快照诊断（本租户桶清单 bizLine/generation/builtAt/ageSeconds/activityCount，带 activityId 时回答「在哪个桶 / 不在任何桶」；**只读、不发起决策、不占 `ACTIVITY_TAG_CAP` 的标签位**，所以验证流量不会混进 `activity.decision.{hit,amount}`）、`POST /snapshot/rollback?bizLine=` 快照代际回滚（这条平面上**唯一的写动作**，但它只动本进程内存指针、**不写数据库**，因此不违反「decision 连只读账号」；额外要求 `console-write-authority`，没有上一代可回时 409）；异常出口是 `DecisionExceptionAdvice`，它**刻意没有** `IllegalArgumentException → 400` 兜底（只读热路径的 IAE 只可能是脏数据或真 bug，报成 400 等于把故障伪装成客户端错误：告警不响、调用方去改自己本来没问题的请求），未分类的一律 500 且**不回显 message**；它继承 `ResponseEntityExceptionHandler`，好让 Spring 自己那批本就该 400 的情况（请求体不是合法 JSON、`/addon/quote` 少传 activityId）不被吞成 500。`GenerationWarmService` 轮询发布代际，见涨即**先建快照切指针、再预热 ACTIVE artifact 的 DRL**，每轮另跑一次超龄快照兜底重建；main = `DecisionApplication`，classpath 上无写平面 bean / 无 drools-lab

## 扩展点

> 以下均针对 **drools-lab** 教学模块（`rules/` / `kmodule.xml` / `domain/` 都在 `drools-lab/src/main/…`）。活动引擎那侧「加规则」是「规则即数据」入库（条件树 / 权益配置），不改 classpath 资源；且默认由纯 Java 求值层执行。`KieHelper` 运行时编译仍有三条常态路径——买赠 `evalGift` 执行、写平面创建/预览时的**编译校验**、decision 侧发布**预热**；**没有**「翻回对照开关」这种东西（那些 eval 方法已删，见「决策链路现状」）。

- **加新规则**：在 `rules/<kbase 名>/` 下加 `.drl`，重启生效
- **加新 KieBase**：编辑 `kmodule.xml` 加 `<kbase>` + `<ksession>`，service 里换 `newKieSession("新名字")`
- **加新 fact**：放 `domain/`，POJO / record 都行，DRL 里 `import` 后即可用
- **决策表**：依赖已加 `drools-decisiontables`；放 `.xls` 到 classpath，在 `kmodule.xml` 加 `<ruleTemplate>` 或 `<kbase>` 指 packages

各 Step 特有的 DRL 语义注意点（`accumulate` / `modify` / agenda LIFO 栈 / CEP 时钟 / marshall / TMS / query 位置模式 / DMN key 匹配 等）见 [`docs/steps-guide.md`](docs/steps-guide.md)。

## 配套文档

- [`docs/agent-brief.md`](docs/agent-brief.md) — **派子代理时让它读这份，不要让它读本文件**。只留「任何改动都适用、且没有测试能替你抓住」的铁律，约本文件的 1/9（本文件主会话加载一次很划算，但工作流里 N 个子代理各读一遍就是 N × 16k tokens）。领域细节让 agent 按需读对应的 `docs/*.md`。
  **维护约定**：只往那里加「测试抓不住」的规则；一条规则一旦有了具名守卫测试，就从那里删掉、在本文件的坑列表里留一行指向测试即可——否则它会长回本文件的体量。

- [`docs/architecture.md`](docs/architecture.md) — **架构总览**：模块拓扑 / 读写平面分工 / 决策链路分层 / 发布模型（版本→代际→快照→预热）/ 多租户 / 数据模型 / 部署拓扑 / **关键不变量表**（改代码前先读这张表）
- [`docs/tech-highlights.md`](docs/tech-highlights.md) — **技术点清单**（面试·答辩·onboarding 向）：每条给「问题 → 做法 → 代码位置 → 可被追问」，末尾附**已知落差**（不体面但诚实的部分）
- [`docs/capacity-model.md`](docs/capacity-model.md) — **活动容量评估**：Drools / QLExpress / 纯 Java 三引擎同负载实测（足迹·编译·延迟三条约束线）+ 容量公式 + 各能挂多少活动的量级结论。基准代码 `examples/capacity/`
- [`docs/steps-guide.md`](docs/steps-guide.md) — **各 Step 详解 + REST 接口全表 + 各 Step 的 DRL 语义 / 实现注意点**（本文件的详细配套）
- `README.md` — 每个 Step 的完整请求示例 + 学习观察点 + 下一步指引
- `docs/rete-intuition.md` — RETE 算法直觉（拿本仓库折扣规则当例子）
- `docs/drools-capabilities.md` — Drools 能力地图（七大块 + 每项标注本仓库哪步演示 + 选型决策树）
- `docs/drools-vs-aviator.md` — Drools 与 Aviator（轻量表达式引擎）的选型对照
- `docs/drools-use-cases.md` — Drools 应用场景与定位（风控/保险/信贷/计费，以及什么时候不该上 Drools）
- `examples/aviator/AviatorDemo.java` — Aviator 独立学习示例（**故意放在 Maven 源码根外，不进 `./mvnw compile`、不引 pom 依赖**）
- `examples/capacity/` — 三引擎容量基准 `CapacityBench.java` + `run.sh`（**同样刻意在源码根外**：它引 QLExpress，生产四模块都不该有这个依赖）。跑法 `./examples/capacity/run.sh`；结论见 `docs/capacity-model.md`
- `deploy/` — 微服务本地编排：`docker-compose.yml`（console 8081 / decision 8082 / nginx 网关 host `:8095` / MySQL 单库双账号 / Prometheus `:9090` / Grafana `:3001`）+ `nginx.conf`（API 网关原位替身，文本资源开 gzip）+ `mysql-init/`（decision 只读账号）+ `Dockerfile`
  - **前端 `/ui/` 由 gateway 镜像托管，不在 console 的 JAR 里**（`Dockerfile.frontend` → `activity-frontend:latest`）。改了前端只 `--build console` 是**没用的**，页面纹丝不动——要 `docker compose -f deploy/docker-compose.yml up -d --build gateway`。反过来改了后端才重建 console。<br>⚠️ **但 `--build gateway` 本身也不够**：`Dockerfile.frontend` 不在容器里构建前端，它只 `COPY frontend/dist/`——dist 必须**先在宿主机** `cd frontend && npm run build` 生成。漏了这一步时 Docker 看 dist 内容没变会直接复用镜像层，**构建全程 exit 0、镜像时间戳纹丝不动、页面还是旧的**，极难察觉。正确顺序：`npm run build` → `up -d --build gateway`；验证方式是 `docker images | grep activity-frontend` 看时间戳，或直接 grep 线上 bundle 里有没有你新加的 `data-testid`
  - 编排**默认 auth 档**（`DROOLS_AUTH_ENABLED` 默认 true），需要本机 Casdoor `:8000`；除 `e2e:oidc` 外的脚本（`e2e:dev` / `e2e:catalog` / `e2e:tablet` / `e2e:phone` / `e2e:bench` / `e2e:playbooks` / `e2e:validate` / `e2e:ruler` / `e2e:visual`）走 `tenant-chip` header 档，要切档：`DROOLS_AUTH_ENABLED=false DROOLS_DEV_DEFAULT_ENABLED=true docker compose ... up -d`。`e2e:validate` 还要启用 `DROOLS_FOUR_EYES_ENABLED=true`，因为脚本会先验证提交人自审被拒、再由另一 actor 发布——**自审被拒现在是 403 不是 409**（四眼说的是「不该由你来做」，重试永远不会成功；响应体形状没变，只多了个 `code` 字段）。⚠ 这条 e2e **不在 `./mvnw test` 与 `vitest` 的闸门里**：2026-08 那次状态码变更整轮重构都没照出它，是人工核对时发现的
- `docs/plans/prod-arch-refactor-0719-1330/` — 「微服务化 + 前后端分离」重构的评估 / 决策 / 计划 / 评审归档（模块拆分细节）
- `docs/plans/benefit-model-refactor-0808-2218/` — **本轮后端重构（P0/P1 分层引擎 + 快照 + 指标）的进度锚**：`FINAL_PLAN` / `DECISION_RECORD`(D1–D12) / `REVIEW-FINDINGS` / `PROGRESS.md`（含 docker 全栈 E2E 验证记录与未完成项）
- `docs/plans/activity-design-refactor-0812-1232/` — **2026-08 活动引擎结构性重构**（OfferSpec 单一装配 / 穷尽 switch / RejectReason·DecisionScene 词汇表 / 只读仓储 / 领域异常层 / GrantService 拆分 / 快照可靠性）：`FINAL_PLAN`（方案）· `AUDIT-FINDINGS`（原始审查）· **`BREAKING-CHANGES`（对外可见变更清单——改前先读这份：4 处状态码、1 处指标标签取值、2 处观测口径）**
- `docs/plans/console-ui-coupon-mechanics-0808-2251/` — 控制台「票券工学」视觉与交互设计：`DESIGN_SPEC` / `DECISION_RECORD`（含 PR-0~PR-6 实施记录）/ `BACKEND-GAPS`（设计依赖但后端不存在的接口）
- `docs/plans/frontend-tech-visual-0809-1424/` — 前端科技感视觉换代（令牌/字体/效果层/视觉红线 e2e）的决策 / 计划 / 评审 / 进度
- `docs/activity-marketing.md` — **活动营销模块的用法**（能力范围 / 六形态造数 / REST 接口全表 / 决策平面拆分 / 多租户 / 已知落差）——本仓库另一半的主文档
- `docs/deployment.md` — 部署编排活文档（compose / 网关前缀分流 / 单库双账号 / 观测 / 容灾 kill-gate）
- `docs/qa/QA_PROFILE.md` — QA 环境档案（**活文档**）：启动命令 / 健康检查 / 玩法造数速查 / 已知缺口；`qa-test` skill 复用
- `docs/delivery/` — 交付归档：`drools-casdoor-auth`（认证方案 + 验收证据）、`promotion-validation-all-playbooks`（全玩法验证）
- `docs/doc-map.md` — **代码区域 ↔ 文档映射 + 上次同步点**，由 `/doc-sync` 维护；加/删文档时同步改它
- `docs/interview/` — 面试复习资料（`qlexpress-vs-drools.md` 选型对照、`coding-drills.md` 手写题），与代码结构无强耦合
