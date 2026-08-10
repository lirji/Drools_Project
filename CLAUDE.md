# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

这是给在本仓库工作的 Claude Code 的指南，描述项目用途、技术栈、约定与已踩过的坑。

## 项目概览

Drools 学习脚手架，配合 LangChain4j 项目用，**不是生产代码**。渐进式 demo，从 Hello World 到"引擎安全护栏 / DMN / 真实业务场景"共 18 个 Step，每步一个 REST 入口。

**仓库形态（2026-07 · M2.1 起 = Maven 四模块）**：本仓库已从「纯 Drools 教学脚手架」长成「多租户活动引擎平台 + 教学 Steps」两部分，物理拆成**聚合父 `pom.xml` + 4 个模块**（`org.drools:drools-bom` 与内部模块版本在父 pom 统一管）。下表 Step 1–18 的代码全在 **drools-lab**，由 **activity-console** 暴露：

| 模块 | 类型 | 职责 |
| ---- | ---- | ---- |
| `activity-common` | 库 | 活动引擎共享内核：`activity/{domain,engine,persistence,tenant,metrics,snapshot}` + 读路径服务（`ActivityQueryService` 编排 / `DecisionDataLoader` 取数 / `DecisionEligibilityService` 统一上下文与资格 / `AddOnPurchaseService`）。用到 Drools 的地方走 `KieHelper` 运行时编译，**不用 kmodule / KieContainer / DMN**；2026-08 起决策主链路默认是纯 Java（见下「决策链路现状」） |
| `drools-lab` | 库 | **下表 Step 1–18 教学代码全在这里**（`config/DroolsConfig`、`rules/`、`META-INF/kmodule.xml`，及 Step7 决策表 / Step16 `kie-ci` / Step17 `kie-dmn` 等重依赖） |
| `activity-console` | 应用 · 8081 | 写平面（含批量上下线 / 秒杀库存 claim）+ 复用 drools-lab 暴露 Step 1–18 全端点 + 前端 SPA 托管（`/ui/`）+ **唯一 DDL 执行者**；依赖 common + drools-lab |
| `activity-decision` | 应用 · 8082 | 只读决策热路径 `/decision/v1`（含加价购两阶段 + 指标聚合）+ 发布代际轮询预热**兼决策快照构建/切换**；**只依赖 common，不依赖 drools-lab**（jar 更轻，甩掉 kie-ci/DMN），M2.2 起连只读 DB 账号（仅 SELECT）、`ddl-auto: validate`，DDL 由 console 独占 |

两 app 主类都放根包 `com.lrj.drools`（`ConsoleApplication` / `DecisionApplication`，`scanBasePackages/@EntityScan/@EnableJpaRepositories = com.lrj.drools`）；decision 的 classpath 上没有写平面 bean / `DroolsConfig`，结构上就写不了。本地整套编排见 `deploy/`（nginx 网关 host `:8095` + 两 app + MySQL 单库双账号 + Prometheus `:9090` + Grafana `:3001`）。

### 决策链路现状（2026-08 · 分层引擎）

活动侧的决策**主链路默认不进 Drools**。判据是「这条规则需不需要*其它规则的结论*」——阶梯落档是标量分段函数、折扣合并是一次 reduce、资格条件树是单事实布尔谓词，三者都不需要，于是移出规则引擎：

| 环节 | 生产固定路径 | 兼容属性 / 边界 |
| ---- | ---- | ---- |
| 资格淘汰（红包 / 买赠 / 加价购共用） | `DecisionEligibilityService` 构造唯一上下文，再由 `ConditionTreeEvaluator` 直接解释 `condition_tree_json` | `java-eligibility-eval` 仅保留配置兼容；即使 false 也不改变主路径 |
| 阶梯落档 / 六形态算额 / 合并 | `BenefitEvaluator` + `BenefitMath` 纯 Java | `java-benefit-eval` 仅保留配置兼容；即使 false 也不切回旧 DRL |
| 买赠 | 共享资格淘汰后走 `ruleRuntime.evalGift`；关闭/失败时只聚合合格候选 | `rule-engine.enabled=false` 触发安全 Java 回退 |
| 加价购 | 共享资格淘汰后由 `AddOnPurchaseService` 列选项、按当前配置重报价 | 不走 Drools；报价不调用 `claim` |

- 旧红包 DRL 仅保留作隔离参考/教学资产，**不是生产回滚路径**（它不认一口价 / 第 N 件折 / 随机红包，翻回去会按错误形态发钱——这正是开关退役的原因）。“旧环境即使仍配 false，生产也必须继续走共享资格 + 六形态 Java 求值”这条由 `ActivityQuerySafetyFallbackTest#legacyFalseFlagsCannotSwitchProductionBackToDrools` 守（**不是** `DroolsBenefitGoldenSetTest`，后者自身跑 0 个用例，见坑 14）；`SnapshotParityTest` 继续守快照/走库等价性
- **`BenefitEvaluator` 出 bug 时的回滚手段**（开关退役后）：部署级回滚上一版 jar + decision 侧快照代际 `rollback`（保留上一代指针）；金标集（`DecisionGoldenSetTest` 39 例）是发布门禁。进程内已没有「切回另一套求值语义」的开关，不要再往这个方向设计预案
- `rule-engine.enabled=false` 或空决策的安全回退仍先跑共享资格，再用 `BenefitEvaluator` 重算六形态，并保留从当前 bizLine 解析出的 `STACK / PRIORITY / MAX`（`MUTEX` 与 PRIORITY 同类单选语义），不能统一退化成 MAX
- **取数**：`DecisionDataLoader` 固定 5 次查询（原来 3N+2，N = 候选活动数）
- **快照**：decision 侧代际推进时，`DecisionSnapshotBuilder` 在**后台线程**把整条业务线的物料建成不可变 `DecisionSnapshot`，就绪后由 `DecisionSnapshotStore` 原子切指针（保留上一代供 `rollback`）。命中快照的决策**零数据库查询**；没有快照自动回落走库（console 没有构建器，store 恒空，天然走库）
- **指标**：`DecisionMetrics` 打 `activity.decision.{duration,fallback,candidates,source,hit}` + `activity.rule.{compile,fire.ceiling,cache.*}`。**回退率是头号告警项**——回退会静默改变实际发放金额

### 权益形态（`red_package_amount_unit` 是判别位）

同一个 `redPackageAmount` 字段按单位解释成不同含义，**未知单位一律回落金额型**（脏数据表现为「按旧行为发」，不是「按猜出来的形态发」）：

| 单位 | `BenefitForm` | `redPackageAmount` 的含义 | 备注 |
| ---- | ---- | ---- | ---- |
| `元` / null | `AMOUNT` | 直接减的钱 | 阶梯分档另配 `redPackageRangeAmount` |
| `折` | `RATIO_ZHE` | 折数 (0,10)，8 = 八折 | 写平面**强制** `redPackageMaxDiscount>0`，且不许同时配阶梯 |
| `价` | `FIXED_PRICE` | 一口价（秒杀）卖多少 | 减免 = 订单金额 − 一口价；防超发靠写平面 `claim`，决策侧只报价 |
| `件折` | `NTH_ZHE` | 折数，第几件存在 `redPackageRangeAmount` 的 `{"nth":N}` | 需决策入参带 `lines`（订单行）；缺行项 fail-closed 不适用，**不拿均价凑** |

随机红包走 `redPackageTakeType=RANDOM_AMOUNT` + 区间，金额是**确定性随机**（SHA-256 派生自「活动+版本 / 用户 / 购物车指纹」，刷新不变价、可重放对账）。加价购是活动类型 6，走 `/decision/v1/addon/{options,quote}` 两阶段；console 另有 `/activity-marketing/addon/{options,quote}` 同语义验证别名。第二阶段不读客户端传来的价格，并重新跑资格与读取当前配置；秒杀试算和加价购报价都不占库存。

前端把这些形态包装成有名字的玩法：控制台 `/console/playbooks`（`frontend/src/console/playbooks.ts`）给每个玩法一句人话 + 预填参数，**做不到的玩法不删卡、标灰并写明缺什么**。`/console/validate` 从 12 份 playbook 派生场景并补 random，按 discount / gifts / addon 三通道展示结构化结果；第 N 件折的 `spuIdList / orderAmount / quantity / lines` 只从订单行导出。

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

./mvnw test                   # 跑全 reactor 测试（common 143（含 3 skipped）+ console 200 + decision 17 = 360，2026-08-10 本机实跑；drools-lab 不产出可执行用例——唯一的 @Test 类 `VipDiscountSheetGenerator` 命名不匹配 surefire 默认模式，从不运行）
./mvnw clean package          # 打 4 模块，两 app 出可执行 jar（decision 更轻，甩掉 kie-ci/dmn）
./mvnw clean compile          # 只编译 Java；不会校验 DRL 语法
# 单模块：./mvnw -pl activity-common test（-am 连带先构建依赖模块）

# 起整套微服务编排（nginx 网关 :8095 + console + decision + MySQL 单库双账号 + Prometheus :9090 + Grafana :3001）
docker compose -f deploy/docker-compose.yml up --build   # 然后浏览器开 http://localhost:8095/ui/console
```

**测试**：console 只有 `FixedPriceAndClaimTest`（9 个用例）吃 `h2` profile 的**文件库**（`activity-console/data/drools-demo.mv.db`，其余 h2 用例都用 `@TestPropertySource` 覆到内存库），别的进程占着它时（本地起着 console、或另一处并行在跑 `./mvnw test`）会整片报 `Database may be already in use` + `Unable to determine Dialect`——是环境冲突不是代码回归，串行跑即可。
**端口**：console 8081 / decision 8082，跟主项目 LangChain4j (8080) 错开。console 改端口看 `activity-console/src/main/resources/application.yml`，decision 看 `activity-decision/.../application.yml`。
**数据库 profile**：console / decision **各自带一套** `application.yml`（公共配置 + `spring.profiles.active: mysql` 默认）；数据源细节分到 `application-mysql.yml`（带 `createDatabaseIfNotExist=true`，库不存在自动建）/ `application-h2.yml`。连接参数 `DB_HOST`/`DB_PORT`/`DB_NAME`/`DB_USERNAME`/`DB_PASSWORD` 都能用环境变量覆盖。

## 已踩过的坑（务必先读，再动 pom / DRL）

1. **`org.kie:kie-bom` 在 8.44.2 没发布** → **根聚合 `pom.xml`** 的 `dependencyManagement` 用 `org.drools:drools-bom`（各子模块 import 继承，不各自锁版本）
2. **Drools 8.x 把 XML 解析拆成独立模块** → 必须显式加 `drools-xml-support`（在 `drools-lab/pom.xml`——只有 drools-lab 走 kmodule；activity-common 走 `KieHelper` 运行时编译不吃这条），否则启动报 `Unable to build index of kmodule.xml ... add module org.drools:drools-xml-support`
3. **不要随便加 `update($fact)` → 会死循环**：
   - `update()` 重新评估所有依赖该 fact 的规则
   - 本项目 LHS 条件都看 `vipLevel` / `totalAmount` / `yearsSinceRegistration`（不可变字段），修改 `finalAmount` 不会让条件失配 → 规则永远满足，被反复触发，请求挂住
   - `no-loop true` 只防"自己 consequence 重新激活自己"，**不防其他规则的 update 间接重新激活自己**
   - cross-rule 防护要用 `lock-on-active true` + `agenda-group`
   - **本 demo 根本不需要 update()**，教学上的"什么时候用 update()"放在 Step 3 真实级联（`modify` + goldStatus）里讲
4. **DRL 是运行时解析**，`mvn compile` 过了不代表规则没语法错。改完 DRL 必须至少启动一次或跑一次冒烟请求
5. **Customer / OrderItem 是 Java record，DRL 里 `Customer( age >= 18 )` 能正常用** — Drools 8.x 的 LHS 会自动尝试 record accessor (`age()`)。这条算确认信息，不是坑
6. **RHS 没有 record accessor 糖** — LHS 引擎自己适配 record，但 RHS 直接编译成 Java 代码，没有适配层。所以 `$p.getMessage()` 对 record `Promotion` 会编译失败，必须写 `$p.message()`。DRL 是 lazy compile，改完重启后**第一次请求**才触发重新编译并报错，冒烟一次比读启动日志可靠
7. **MySQL 下 `@Lob` 大字段会被截断** — `@Lob byte[]`（Step 10 session 快照）在 MySQL 默认建成 64KB `blob`、`@Lob String`（Step 18 DRL 文本）建成 64KB `text`，大会话 / 长 DRL 会超限。本项目改用 `@JdbcTypeCode(SqlTypes.LONGVARBINARY)` / `@JdbcTypeCode(SqlTypes.LONGVARCHAR)`，映射成 MySQL `longblob` / `longtext`，H2 下也够装。H2 时代用 `@Lob` 不暴露这个坑，换 MySQL 才踩到
8. **MySQL 中文乱码 / 时区** — `application-mysql.yml` 的 URL 必须带 `characterEncoding=UTF-8`（DRL / reason 里有中文）+ `serverTimezone`（`Instant` 字段），否则中文乱码 / 时间偏移。`createDatabaseIfNotExist=true` 让库不存在时自动建，学习省事；生产应预建库 + 收紧账号权限
9. **新增一个受租户约束的路径，必须同步扩 `TenantContextFilter` 的 URL 模式** — 该过滤器（header 档）原来只挂 `/activity-marketing/*`，M1.1 加决策平面时漏了 `/decision/v1/*`：`X-Tenant-Id` 被**静默忽略**，全部请求落到 dev-default / `__no_tenant__` 兜底，表现为 A 租户查到别人的活动。auth 档不受影响（`JwtTenantFilter` 挂在同时匹配两个平面的安全链上）。现已扩成两条，回归由 `DecisionTenantHeaderTest` 钉死。单元测试大多跑在 dev-default 下，**这类缺口只有端到端才照得出来**
10. **decision 的 `ddl-auto` 必须是 `validate`** — 它连的是只读账号（`deploy/mysql-init/` 只 GRANT SELECT）。此处曾遗留 `update`，只被 compose 的环境变量盖住；按文档里那条本地命令 `./mvnw -pl activity-decision spring-boot:run` 起，只读平面就带着 DDL 权限跑了。现由 `DecisionDdlGuardTest` 钉死，别再改回 update
11. **`redPackageAmountUnit` 现在是「这个数字是什么意思」的开关，不再是装饰字段** — 元/折/价/件折 四种受控取值（见上「权益形态」表），未知取值回落金额型而不是报错。改这块要同时看 `BenefitForm` / `BenefitEvaluator` / `BenefitMath`。**取整逻辑只能有一份**（`BenefitMath` 的静态方法），各写一遍迟早漂移，表现是同一张券在两条路上少发/多发几分钱。<br>（历史注记：这条原先要求「同步改 DRL 里的 `discount-compute-ratio`」——那条 DRL 算额规则已随 `buildDiscountDrl` 一起删除，**别再去找它**，也别为了让文档成立而重新造一条 DRL 算额路径，那等于复活刚被删掉的第二权威。）减免额一律 `RoundingMode.DOWN`（四舍五入会系统性多发）
12. **库存扣减只能是一条原子 UPDATE，且必须在写平面** — `ActivityManageRepository.decrementInventory` 把「判余量」和「减一」压进同一条 `update ... where inventory >= :n`；**绝不能先 SELECT 再 UPDATE**（check-then-act 竞态，低并发测不出、大促必现）。返回 0 = 没抢到，调用方不能忽略返回值。决策服务连只读账号写不了库，所以分工是「决策只报价、`POST /activity-marketing/{id}/claim` 才是提交」；claim **不幂等**（没有领取流水表，连点两次扣两次）。它已列入 `console-write-authority` 保护的写路径；auth 生产环境必须配该 authority，不要依赖 demo 默认空值
13. **activityId 不能无上限地当 Prometheus 标签** — 活动是运营随手能建的，序列数不受工程控制，基数爆炸的代价是大促当天整套监控一起挂。`DecisionMetrics.ACTIVITY_TAG_CAP = 200`，超出部分并入 `__over_cap__` 哨兵（总量仍准，只是分不出是哪几个），响应里原样带出不隐藏
14. **别用「求和 surefire XML」来数用例数，会少数 50 个** — `DroolsBenefitGoldenSetTest extends DecisionGoldenSetTest`（全仓库唯一的测试类继承）。父类的用例全在 `@Nested` 内部类里，跑子类时 JUnit 会把这些嵌套类**再发现一遍**，但它们仍按**父类**的 `@TestPropertySource` 执行、并写进**同名** `TEST-…DecisionGoldenSetTest$Ladder.xml`，第二遍直接覆盖第一遍。于是文件求和得 console 150、Maven 自己报 200。**以 `./mvnw test` 输出的 `Tests run:` 汇总为准。** 历史上文档里的 307 / console 147 就是这么数出来的错数字。<br>顺带两个后果：① 那 50 个用例**白跑一遍**（约 10 秒 + 一个多余的 Spring 上下文）；② `DroolsBenefitGoldenSetTest` **自身用例数为 0**——它想验的「旧开关配 false 也不换求值器」实际由 `ActivityQuerySafetyFallbackTest#legacyFalseFlagsCannotSwitchProductionBackToDrools` 守着（反射把两个字段置 false 再断言行为），别把前者当门禁

## 代码结构（按职责，不是按目录）

> 下列前 8 条「职责」均属 **drools-lab**（Step 1–18 教学，包 `com.lrj.drools.*`，路径前缀 `drools-lab/src/main/…`）；活动引擎平台代码在 **activity-common**（包 `com.lrj.drools.activity.*`），两 app 只放各自 controller/service 薄壳（见末尾三条）。

- `domain/` — fact 类型。record (`Customer`, `OrderItem`, `Promotion`) 用于不可变事实；mutable POJO (`Order`, `Cart`) 用于会被规则改字段的事实。`Promotion` 是 Step 4 的"标记 fact"，规则自己 insert 出来给 `not` 检测
- `service/` — KieSession 生命周期。**每次请求 `newKieSession` + `fireAllRules` + `dispose`**，KieSession 线程不安全，不要为了"省"复用。StatelessKieSession（Step 11）/ DMNRuntime（Step 17）线程安全，可当字段缓存复用
- `config/DroolsConfig.java` — `KieContainer` 注成单例 Bean。**程序化 `KieFileSystem` 构建**（不是 `getKieClasspathContainer()`）：扫 `.drl` 自动加 + `.xls` 标 `ResourceType.DTABLE` + `.dmn` 标 `ResourceType.DMN`，因为 8.44.2 的 ClasspathKieProject 不识别决策表 / DMN
- `resources/rules/<kbase>/*.drl` — DRL 文件，**目录名必须和 `kmodule.xml` 里 `<kbase packages="...">` 对齐**
- `resources/META-INF/kmodule.xml` — 声明各 kbase + ksession（`fraudKBase` 用 stream mode + pseudo clock；Step 12 两个 kbase 隔离避免 logical / regular 衍生 fact 互相污染；`dmnKBase` 只声明 kbase 不带 ksession）
- `audit/`（Step 6）/ `metrics/`（Step 15）/ `guard/`（Step 14）— "挂在 session 上的横切组件"，实现 Drools listener / `AgendaFilter` 接口，按请求挂一个实例。读规则元数据走公共 API `Rule.getMetaData()`
- `persistence/`（Step 10 / 18）— JPA entity + Spring Data repo。大字段用 `@JdbcTypeCode`（不是 `@Lob`，见坑 7）
- Step 16 `ScannerService` / Step 17 `DmnService` 不走 `DroolsConfig` 那个 classpath KieContainer，各自维护绑 ReleaseId 的 container / `DMNRuntime`

活动引擎平台三模块（`com.lrj.drools.activity.*`）：

- `activity-common · activity/{domain,engine,persistence,tenant,metrics,snapshot} + service/` — 活动引擎共享内核：多租户事实 / 规则编译引擎（`KieHelper` 运行时编译 + 足迹加权 LRU 缓存，**非 kmodule/KieContainer**）/ 活动·规则·条件·幂等·发布代际的 JPA / 租户上下文。console 与 decision 共享；Step 10/18 那套教学 JPA 不在这里、仍在 drools-lab。读路径按职责再切三层：
  - `service/ActivityQueryService` 只做**编排**（资格 → 阶梯 → 算额 → 合并 → 回退），`service/DecisionDataLoader` 只做**取数**（快照优先，否则固定 5 次查询）
  - `service/DecisionEligibilityService` 是 discount / gifts / addon 唯一的请求属性映射与资格淘汰；`engine/{BenefitEvaluator,BenefitMath,ConditionTreeEvaluator,RandomRangeParser}` 是**纯 Java 求值层**，六形态固定走这里；`engine/{ActivityDrlBuilder,ActivityRuleRuntimeService}` 只保留买赠运行时与隔离对照资产，两个旧 `java-*` 属性不再把生产切回红包 DRL
  - `snapshot/{DecisionSnapshot,DecisionSnapshotBuilder,DecisionSnapshotStore}` 代际快照包；`metrics/DecisionMetrics` 决策链路指标（含 activityId 标签基数上限）
- `activity-console · activity/{controller,service}`（应用，8081）— 写平面（`ActivityMarketingService` / `ArtifactService` / `GenerationService` + seeder）+ legacy `/activity-marketing` 读端点（试算显式 `explain=true`，决策平面走 false）；main = `ConsoleApplication`，**唯一 DDL 执行者**。写平面语义要点：编辑**不下线**正在服务的版本（线上版与草稿并存），上线时在同一事务里把该活动其它 ONLINE 版本退役（原子指针切换）；`POST /bulk-status` 批量上下线**逐条处理、失败不影响已成功项 + 部分失败回执**（一律 200；`version` 允许为 null，但调用方应按列表行传显式 version，否则会打到草稿而不是正在服务的版本）；`POST /{id}/claim` 抢占秒杀库存，并与 create/status 一样受已配置的 `console-write-authority` 保护
- `activity-decision · activity/{controller,engine}`（应用，8082）— 只读 `DecisionPlaneController`：`/decision/v1/{spu-discount,gifts}` 热路径、`/addon/{options,quote}` 加价购两阶段、`GET /metrics` + `/by-activity` 指标聚合（**本进程单实例视角**，跨实例仍看 Prometheus）；`GenerationWarmService` 轮询发布代际，见涨即**先建快照切指针、再预热 ACTIVE artifact 的 DRL**；main = `DecisionApplication`，classpath 上无写平面 bean / 无 drools-lab

## 扩展点

> 以下均针对 **drools-lab** 教学模块（`rules/` / `kmodule.xml` / `domain/` 都在 `drools-lab/src/main/…`）。活动引擎那侧「加规则」是「规则即数据」入库（条件树 / 权益配置），不改 classpath 资源；且默认由纯 Java 求值层执行，只有买赠和翻回对照开关时才 `KieHelper` 运行时编译（见「决策链路现状」）。

- **加新规则**：在 `rules/<kbase 名>/` 下加 `.drl`，重启生效
- **加新 KieBase**：编辑 `kmodule.xml` 加 `<kbase>` + `<ksession>`，service 里换 `newKieSession("新名字")`
- **加新 fact**：放 `domain/`，POJO / record 都行，DRL 里 `import` 后即可用
- **决策表**：依赖已加 `drools-decisiontables`；放 `.xls` 到 classpath，在 `kmodule.xml` 加 `<ruleTemplate>` 或 `<kbase>` 指 packages

各 Step 特有的 DRL 语义注意点（`accumulate` / `modify` / agenda LIFO 栈 / CEP 时钟 / marshall / TMS / query 位置模式 / DMN key 匹配 等）见 [`docs/steps-guide.md`](docs/steps-guide.md)。

## 配套文档

- [`docs/steps-guide.md`](docs/steps-guide.md) — **各 Step 详解 + REST 接口全表 + 各 Step 的 DRL 语义 / 实现注意点**（本文件的详细配套）
- `README.md` — 每个 Step 的完整请求示例 + 学习观察点 + 下一步指引
- `docs/rete-intuition.md` — RETE 算法直觉（拿本仓库折扣规则当例子）
- `docs/drools-capabilities.md` — Drools 能力地图（七大块 + 每项标注本仓库哪步演示 + 选型决策树）
- `docs/drools-vs-aviator.md` — Drools 与 Aviator（轻量表达式引擎）的选型对照
- `docs/drools-use-cases.md` — Drools 应用场景与定位（风控/保险/信贷/计费，以及什么时候不该上 Drools）
- `examples/aviator/AviatorDemo.java` — Aviator 独立学习示例（**故意放在 Maven 源码根外，不进 `./mvnw compile`、不引 pom 依赖**）
- `deploy/` — 微服务本地编排：`docker-compose.yml`（console 8081 / decision 8082 / nginx 网关 host `:8095` / MySQL 单库双账号 / Prometheus `:9090` / Grafana `:3001`）+ `nginx.conf`（API 网关原位替身，文本资源开 gzip）+ `mysql-init/`（decision 只读账号）+ `Dockerfile`
  - **前端 `/ui/` 由 gateway 镜像托管，不在 console 的 JAR 里**（`Dockerfile.frontend` → `activity-frontend:latest`）。改了前端只 `--build console` 是**没用的**，页面纹丝不动——要 `docker compose -f deploy/docker-compose.yml up -d --build gateway`。反过来改了后端才重建 console
  - 编排**默认 auth 档**（`DROOLS_AUTH_ENABLED` 默认 true），需要本机 Casdoor `:8000`；除 `e2e:oidc` 外的脚本（`e2e:dev` / `e2e:catalog` / `e2e:tablet` / `e2e:phone` / `e2e:bench` / `e2e:playbooks` / `e2e:validate` / `e2e:ruler` / `e2e:visual`）走 `tenant-chip` header 档，要切档：`DROOLS_AUTH_ENABLED=false DROOLS_DEV_DEFAULT_ENABLED=true docker compose ... up -d`。`e2e:validate` 还要启用 `DROOLS_FOUR_EYES_ENABLED=true`，因为脚本会先验证提交人自审被拒、再由另一 actor 发布
- `docs/plans/prod-arch-refactor-0719-1330/` — 「微服务化 + 前后端分离」重构的评估 / 决策 / 计划 / 评审归档（模块拆分细节）
- `docs/plans/benefit-model-refactor-0808-2218/` — **本轮后端重构（P0/P1 分层引擎 + 快照 + 指标）的进度锚**：`FINAL_PLAN` / `DECISION_RECORD`(D1–D12) / `REVIEW-FINDINGS` / `PROGRESS.md`（含 docker 全栈 E2E 验证记录与未完成项）
- `docs/plans/console-ui-coupon-mechanics-0808-2251/` — 控制台「票券工学」视觉与交互设计：`DESIGN_SPEC` / `DECISION_RECORD`（含 PR-0~PR-6 实施记录）/ `BACKEND-GAPS`（设计依赖但后端不存在的接口）
- `docs/plans/frontend-tech-visual-0809-1424/` — 前端科技感视觉换代（令牌/字体/效果层/视觉红线 e2e）的决策 / 计划 / 评审 / 进度
- `docs/interview/` — 面试复习资料（`qlexpress-vs-drools.md` 选型对照、`coding-drills.md` 手写题），与代码结构无强耦合
