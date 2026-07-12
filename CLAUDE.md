# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

这是给在本仓库工作的 Claude Code 的指南，描述项目用途、技术栈、约定与已踩过的坑。

## 项目概览

Drools 学习脚手架，配合 LangChain4j 项目用，**不是生产代码**。渐进式 demo，从 Hello World 到"引擎安全护栏 / DMN / 真实业务场景"共 18 个 Step，每步一个 REST 入口。

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

```bash
# 默认 MySQL profile，连接走环境变量覆盖（不写死真实值）
DB_HOST=localhost DB_PORT=3306 DB_NAME=drools_demo DB_USERNAME=root DB_PASSWORD=yourpass \
  ./mvnw spring-boot:run               # 起 web 服务 (默认 8081)
./mvnw spring-boot:run -Dspring-boot.run.profiles=h2   # 没 MySQL 时切 H2 file 模式
./mvnw test                   # 跑测试 (目前只有 spring-boot-starter-test，没业务测试)
./mvnw clean package          # 打 jar
./mvnw clean compile          # 只编译 Java；不会校验 DRL 语法
```

**端口 8081** 跟主项目 LangChain4j (8080) 错开，方便两个 demo 同时跑。改端口看 `application.yml`。
**数据库 profile**：`application.yml` 是公共配置 + `spring.profiles.active: mysql` 默认；数据源细节分到 `application-mysql.yml`（带 `createDatabaseIfNotExist=true`，库不存在自动建）/ `application-h2.yml`。连接参数 `DB_HOST`/`DB_PORT`/`DB_NAME`/`DB_USERNAME`/`DB_PASSWORD` 都能用环境变量覆盖。

## 已踩过的坑（务必先读，再动 pom / DRL）

1. **`org.kie:kie-bom` 在 8.44.2 没发布** → `pom.xml` 必须用 `org.drools:drools-bom`
2. **Drools 8.x 把 XML 解析拆成独立模块** → 必须显式加 `drools-xml-support`，否则启动报 `Unable to build index of kmodule.xml ... add module org.drools:drools-xml-support`
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

## 代码结构（按职责，不是按目录）

- `domain/` — fact 类型。record (`Customer`, `OrderItem`, `Promotion`) 用于不可变事实；mutable POJO (`Order`, `Cart`) 用于会被规则改字段的事实。`Promotion` 是 Step 4 的"标记 fact"，规则自己 insert 出来给 `not` 检测
- `service/` — KieSession 生命周期。**每次请求 `newKieSession` + `fireAllRules` + `dispose`**，KieSession 线程不安全，不要为了"省"复用。StatelessKieSession（Step 11）/ DMNRuntime（Step 17）线程安全，可当字段缓存复用
- `config/DroolsConfig.java` — `KieContainer` 注成单例 Bean。**程序化 `KieFileSystem` 构建**（不是 `getKieClasspathContainer()`）：扫 `.drl` 自动加 + `.xls` 标 `ResourceType.DTABLE` + `.dmn` 标 `ResourceType.DMN`，因为 8.44.2 的 ClasspathKieProject 不识别决策表 / DMN
- `resources/rules/<kbase>/*.drl` — DRL 文件，**目录名必须和 `kmodule.xml` 里 `<kbase packages="...">` 对齐**
- `resources/META-INF/kmodule.xml` — 声明各 kbase + ksession（`fraudKBase` 用 stream mode + pseudo clock；Step 12 两个 kbase 隔离避免 logical / regular 衍生 fact 互相污染；`dmnKBase` 只声明 kbase 不带 ksession）
- `audit/`（Step 6）/ `metrics/`（Step 15）/ `guard/`（Step 14）— "挂在 session 上的横切组件"，实现 Drools listener / `AgendaFilter` 接口，按请求挂一个实例。读规则元数据走公共 API `Rule.getMetaData()`
- `persistence/`（Step 10 / 18）— JPA entity + Spring Data repo。大字段用 `@JdbcTypeCode`（不是 `@Lob`，见坑 7）
- Step 16 `ScannerService` / Step 17 `DmnService` 不走 `DroolsConfig` 那个 classpath KieContainer，各自维护绑 ReleaseId 的 container / `DMNRuntime`

## 扩展点

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
