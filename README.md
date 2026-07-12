# drools-demo

Drools 学习脚手架 — Hello World + Spring Boot 订单折扣示例。

> 想先看 Drools 到底能干什么、各能力在哪一步演示？看 **[docs/drools-capabilities.md](docs/drools-capabilities.md)**（能力地图 + 选型决策树）。
> 想理解引擎底层匹配原理？看 **[docs/rete-intuition.md](docs/rete-intuition.md)**。
> 纠结用 Drools 还是轻量表达式引擎（Aviator）？看 **[docs/drools-vs-aviator.md](docs/drools-vs-aviator.md)**（选型对照）+ 可运行的 **[examples/aviator/AviatorDemo.java](examples/aviator/AviatorDemo.java)**（Aviator 独立示例，含跟 Step 2 折扣同题对照）。
> Drools 是不是只配营销活动平台用？看 **[docs/drools-use-cases.md](docs/drools-use-cases.md)**（应用场景与定位，澄清"营销专用"误区 + 什么时候不该上 Drools）。

## 技术栈

- Java 21 / Spring Boot 3.3.5
- Drools 8.44.2.Final (kie-api, drools-core/compiler/mvel/decisiontables)

## 项目结构

```
src/main/
├── java/com/lrj/drools/
│   ├── DroolsDemoApplication.java            启动类
│   ├── config/DroolsConfig.java              KieContainer Bean (扫 classpath 上的 DRL)
│   ├── domain/
│   │   ├── Customer.java                     用户事实 (record)
│   │   ├── OrderItem.java                    订单行项 (record)
│   │   └── Order.java                        订单事实 (mutable，被规则改 finalAmount)
│   ├── service/DiscountService.java          KieSession 生命周期 (insert → fireAllRules → dispose)
│   └── controller/DiscountController.java    REST: /hello, /discount/calculate
└── resources/
    ├── application.yml                       端口 8081 (跟 langchain4j 8080 错开)
    ├── META-INF/kmodule.xml                  声明两个 kbase: helloKBase + discountKBase
    └── rules/
        ├── hello/hello.drl                   Step 1 入门规则
        └── discount/order-discount.drl       Step 2 订单折扣 (VIP/满减/老用户/大单提示)
```

## 运行

```bash
cd /Users/liruijun/personal/LLM/drools-demo

# 默认连 MySQL (mysql profile)。连接参数用环境变量覆盖, 不写死:
DB_HOST=localhost DB_PORT=3306 DB_NAME=drools_demo \
DB_USERNAME=root DB_PASSWORD=yourpass \
  ./mvnw spring-boot:run

# 没装 MySQL? 切 H2 file 跑 (状态落 ./data/, 不依赖外部库):
./mvnw spring-boot:run -Dspring-boot.run.profiles=h2
```

> **数据库**: Step 10 (会话持久化) 和 Step 18 (活动规则) 都用 JPA 落库。默认 profile 是 **MySQL**;
> URL 带 `createDatabaseIfNotExist=true`, 库不存在会自动建。连接细节见 `application-mysql.yml`,
> 全部支持环境变量覆盖 (`DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USERNAME` / `DB_PASSWORD`)。
> 不想装 MySQL 就加 `-Dspring-boot.run.profiles=h2` 退回 H2 file 模式。

## 🖥 前端演示台（在浏览器里看规则效果）

起服务后，浏览器打开 **<http://localhost:8081/>** 即可。一个内置的静态演示台，把
**全部 Step 1–18 的 REST 端点**都做成了可点选、可编辑、可运行的面板——不用记 `curl`，
直接在页面上选示例、改 JSON、点「运行」，右侧看**结构化摘要**（折扣账本、推荐、审计时间线、
TMS 前后对比、会员升级、DMN 决策链、活动资格……）+ 原始响应 + HTTP 状态。

```bash
# 想快速体验、又不想装 MySQL，推荐 H2 profile 起：
./mvnw spring-boot:run -Dspring-boot.run.profiles=h2
# 然后浏览器打开 http://localhost:8081/
```

- **零后端改动**：纯静态资源（`src/main/resources/static/`），跟后端**同源**托管，无需 CORS、无需 Node/构建工具。
- **左侧按 Step 分组导航**，覆盖后端每一个功能端点 + `/actuator/prometheus`。
- **看得见的效果**：每个 demo 内置多组示例 payload（从本 README 的 curl 转写），命中规则、
  推荐、审计栈时序、logical/regular 撤销对比等都有专门的可视化摘要。
- **失败也看得见**：编译错误 400（含行号）、未知会话 404、活动已结束 409 都会原样展示状态码与错误体。
- 右上角可切换**明/暗主题**。持久化类 demo（Step 10 会话、Step 18 活动）需要数据库，用上面的 H2 profile 最省事。

> 提示：演示台是学习/本地用途，热加载（`/hot/*`、`/scanner/*`）能运行时编译任意 DRL，**不要把它裸露到公网**。

## 数据库配置 (MySQL / H2)

只有 **Step 10**（会话持久化）和 **Step 18**（活动规则）需要数据库；其余 Step 纯内存，不连库也能跑。

### 两个 profile

| profile | 用途 | 数据落哪 | 配置文件 |
| --- | --- | --- | --- |
| `mysql` (默认) | 正式用法 | 外部 MySQL 的 `drools_demo` 库 | `application-mysql.yml` |
| `h2` | 无 MySQL 时备用 | `./data/drools-demo.mv.db` (file, 重启不丢) | `application-h2.yml` |

`application.yml` 是公共配置 + `spring.profiles.active: ${SPRING_PROFILES_ACTIVE:mysql}`，所以默认走 MySQL，`SPRING_PROFILES_ACTIVE=h2` 或 `-Dspring-boot.run.profiles=h2` 切回 H2。

### MySQL 连接 (全走环境变量, 仓库不留明文密码)

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `DB_HOST` | `localhost` | |
| `DB_PORT` | `3306` | |
| `DB_NAME` | `drools_demo` | URL 带 `createDatabaseIfNotExist=true`, 库不存在自动建 |
| `DB_USERNAME` | `root` | |
| `DB_PASSWORD` | `root` | |

URL 还带了 `characterEncoding=UTF-8`（中文规则名 / reason 不乱码）+ `serverTimezone=Asia/Shanghai`（`Instant` 字段时区）。要改时区或 SSL 直接编辑 `application-mysql.yml`。

### 大字段为什么不用 `@Lob`

`SessionSnapshot.data`（Step 10 的 session `byte[]`）和 `CampaignEntity.eligibilityDrl`（Step 18 的 DRL 文本）用的是 `@JdbcTypeCode(LONGVARBINARY/LONGVARCHAR)` 而非 `@Lob`：MySQL 下 `@Lob` 默认建成 64KB 的 `blob`/`text`，大会话或长 DRL 会被**截断**；显式声明长类型映射成 `longblob`/`longtext`（H2 下也是大对象），两个 profile 都够装。H2 时代用 `@Lob` 不暴露这个坑，换 MySQL 才踩到。

### 常见报错排查

- `Communications link failure` / `Connection refused` → MySQL 没起，或 `DB_HOST`/`DB_PORT` 不对
- `Access denied for user` → `DB_USERNAME`/`DB_PASSWORD` 不对
- `Unknown database 'drools_demo'` → 一般不会遇到（URL 自动建库）；若 MySQL 账号没有建库权限会报这个，手动 `CREATE DATABASE drools_demo;` 即可
- 中文 name / reason 显示成 `???` → 检查 MySQL 服务端 `character_set_server`，建议建库时指定 `CREATE DATABASE drools_demo CHARACTER SET utf8mb4;`

> `ddl-auto: update`（学习场景）会按实体自动建表 / 加列；生产不要用，应走 Flyway/Liquibase 等迁移工具。

## Step 1: Hello World

只插一个 Customer，跑 helloKBase 里的规则。规则触发在控制台打印，HTTP 返回触发条数。

```bash
# 普通新成年用户 → 触发 Adult check + Welcome new user
curl -X POST 'http://localhost:8081/hello' \
  -H 'Content-Type: application/json' \
  -d '{"name":"Alice","age":20,"vipLevel":0,"yearsSinceRegistration":0}'

# 老年人 → 触发 Adult check + Senior discount eligible
curl -X POST 'http://localhost:8081/hello' \
  -H 'Content-Type: application/json' \
  -d '{"name":"Bob","age":65,"vipLevel":1,"yearsSinceRegistration":5}'
```

**注意点**:
- 同一个 fact 命中多个 rule 时，**所有**命中的 rule 都会触发 (RETE 帮你穷举，不是 if-else)
- 触发顺序默认不保证，要排序看 `salience` (越大越先)
- 看 `hello.drl` 文件，每段 `when/then/end` 是一条独立规则

## Step 2: 订单折扣

塞一个 Customer + 一个 Order，跑 discountKBase 里所有匹配的规则，逐层叠加折扣。

```bash
# 案例 1: VIP 2 级 + 老用户 + 单价超 500 (三条规则全中)
#   原价 660 → VIP 2 (×0.9) = 594 → 满 500 减 50 = 544 → 老用户 ×0.95 = 516.80
curl -X POST 'http://localhost:8081/discount/calculate' \
  -H 'Content-Type: application/json' \
  -d '{
    "customer": {"name":"Alice","age":30,"vipLevel":2,"yearsSinceRegistration":4},
    "items": [
      {"name":"Laptop","quantity":1,"unitPrice":600},
      {"name":"Mouse","quantity":2,"unitPrice":30}
    ]
  }'

# 案例 2: 非会员小额 (只命中 0 条规则，原价返回)
curl -X POST 'http://localhost:8081/discount/calculate' \
  -H 'Content-Type: application/json' \
  -d '{
    "customer": {"name":"Charlie","age":25,"vipLevel":0,"yearsSinceRegistration":0},
    "items": [{"name":"Pen","quantity":1,"unitPrice":10}]
  }'

# 案例 3: VIP 3 大客户 (触发 8.5 折 + 满减 + 老用户折扣 + 大单提示)
curl -X POST 'http://localhost:8081/discount/calculate' \
  -H 'Content-Type: application/json' \
  -d '{
    "customer": {"name":"Diana","age":40,"vipLevel":3,"yearsSinceRegistration":5},
    "items": [{"name":"Server","quantity":1,"unitPrice":3000}]
  }'
```

返回 JSON 里 `discountReasons` 是被命中的规则按执行顺序记录的"账本"，调试规则冲突时直接看这个。

## 学习时的关键观察点

1. **`salience` 改优先级会改最终金额** — 试着把 VIP 规则的 salience 改成 1，把满减改成 100，看金额变化 (基数从 VIP 折后变成原价折)
2. **试着加 `update($o)` 看死循环** — 在任意一条折扣规则 `then` 块里加一行 `update($o);`，重启请求一次 VIP 用户，会发现请求挂住 (server 进入无限循环)。这是 Drools 新手最常踩的坑：`update()` 会重新评估所有依赖该 fact 的规则，而本例规则的 LHS 条件 (vipLevel/totalAmount) 不会因为修改 finalAmount 而失配，所以一直重复触发。DRL 注释里详细解释了 `no-loop` / `lock-on-active` 两种正确防护方式
3. **新增规则不用动 Java** — 在 `rules/discount/` 下加新 `.drl` 文件，重启即生效
4. **KieSession 不是线程安全** — 看 `DiscountService` 为什么每次请求都 `newKieSession` + `dispose`

## Step 4: 风控 + 推荐 (not / exists)

`POST /risk/evaluate` 接受跟 `/cart/checkout` 相同的 payload，但跑 `riskKBase` 里的 4 条规则：

```bash
# 案例 A: 电子产品但没买保险 → 触发"建议加购意外险"
curl -X POST 'http://localhost:8081/risk/evaluate' \
  -H 'Content-Type: application/json' \
  -d '{
    "customer": {"name":"Alice","age":30,"vipLevel":2,"yearsSinceRegistration":4},
    "items": [
      {"name":"Laptop","quantity":1,"unitPrice":600,"category":"ELECTRONICS"},
      {"name":"Mouse","quantity":1,"unitPrice":30,"category":"ELECTRONICS"}
    ]
  }'

# 案例 B: 电子产品 + 已含保险 → not 失配, 不再推荐保险
curl -X POST 'http://localhost:8081/risk/evaluate' \
  -H 'Content-Type: application/json' \
  -d '{
    "customer": {"name":"Bob","age":30,"vipLevel":0,"yearsSinceRegistration":2},
    "items": [
      {"name":"Phone","quantity":1,"unitPrice":3000,"category":"ELECTRONICS"},
      {"name":"AppleCare+","quantity":1,"unitPrice":299,"category":"INSURANCE"}
    ]
  }'

# 案例 C: 3 本书 + 新用户 → exists 只触发一次书签 + 新人券触发一次
curl -X POST 'http://localhost:8081/risk/evaluate' \
  -H 'Content-Type: application/json' \
  -d '{
    "customer": {"name":"Charlie","age":22,"vipLevel":0,"yearsSinceRegistration":0},
    "items": [
      {"name":"Book A","quantity":1,"unitPrice":40,"category":"BOOK"},
      {"name":"Book B","quantity":1,"unitPrice":50,"category":"BOOK"},
      {"name":"Book C","quantity":1,"unitPrice":60,"category":"BOOK"}
    ]
  }'
# 期望 recommendations: ["赠送精美书签 1 枚（不论几本）","新人首单立减 20 元"]
# 注意: 不会重复加 3 条书签 — exists 只触发 1 次, 跟普通 pattern 对每个 fact 触发一次形成对比
```

**学习观察点 (Step 4)**:

1. **`exists` vs 普通 pattern** — 试着把 `Free bookmark for any book` 的 `exists` 拿掉，改成 `$item: OrderItem(category == "BOOK") from $cart.getItems()`，重启请求案例 C，会看到 recommendations 里出现 3 条重复的书签文案
2. **`not` 是双向的** — 在 `then` 块前后 print 一次 working memory 里的 Promotion 数，会发现规则 1 `insert(INSURANCE_RECO)` 之后，如果再 retract 这个 Promotion，规则 1 会**重新激活**。这是普通规则做不到的"反向触发"
3. **自终止比 no-loop 更优雅** — `First-time buyer coupon` 规则没有任何 `no-loop` 标记，但靠 LHS 自然失配做到了"只触发一次"。这是生产规则的推荐模式

## Step 5: agenda-group 流水线 (validate → discount → risk → notify)

`POST /pipeline/run` 跑 `pipelineKBase`，规则按 4 个 agenda-group 分阶段执行，Java 侧用 `setFocus` 链显式驱动，notify 阶段用 `auto-focus` 自动挂载。

```bash
# 案例 A: 普通 VIP1 用户 + 电子产品 → validate 不拒, discount 进 VIP+满减,
# risk 推荐保险, notify 不触发 (totalAmount 没破 5000)
curl -X POST 'http://localhost:8081/pipeline/run' \
  -H 'Content-Type: application/json' \
  -d '{
    "customer": {"name":"Alice","age":30,"vipLevel":1,"yearsSinceRegistration":2},
    "items": [
      {"name":"Laptop","quantity":1,"unitPrice":600,"category":"ELECTRONICS"}
    ]
  }'

# 案例 B: 空购物车 → validate 阶段拒单, 后续阶段也照常跑 (demo 没 retract)
curl -X POST 'http://localhost:8081/pipeline/run' \
  -H 'Content-Type: application/json' \
  -d '{
    "customer": {"name":"Bob","age":30,"vipLevel":0,"yearsSinceRegistration":0},
    "items": []
  }'

# 案例 C: 大额订单 → notify 阶段被 auto-focus 自动激活, 最后打印审计日志
curl -X POST 'http://localhost:8081/pipeline/run' \
  -H 'Content-Type: application/json' \
  -d '{
    "customer": {"name":"Diana","age":40,"vipLevel":3,"yearsSinceRegistration":5},
    "items": [
      {"name":"Server","quantity":1,"unitPrice":6000,"category":"ELECTRONICS"}
    ]
  }'
# 控制台应能看到执行顺序: [discount] VIP 3 折扣 → [discount] 满 500 减 50
#                          → [risk] 建议加购意外险 → [notify] 大额订单审计
```

**学习观察点 (Step 5)**:

1. **agenda 是 LIFO 栈** — 看 `PipelineService.run()` 的 setFocus 顺序：写的是 `risk → discount → validate`，实际执行是反过来的 `validate → discount → risk`。这是 Drools 最反直觉的点之一
2. **auto-focus 不需要 Java 侧管** — `notify` 阶段没在 PipelineService 里 setFocus，但 `Big spender audit` 规则带 `auto-focus true`，案例 C 会自动触发并出现在最后
3. **lock-on-active 跟 no-loop 的差异** — `Apply VIP discount once` 用了 `lock-on-active true`：在 discount 阶段内只跑一次，即使其他规则改了 cart 并 update，它也不会重激活。改成 `no-loop true` 只能防"自己重激活自己"
4. **salience 还有用** — 在 agenda-group 内部，salience 仍然控制队列顺序（`Apply VIP discount once` salience 100 比满减 50 先跑），不要把 salience 和 agenda-group 当二选一

## Step 6: 规则可观测性 (AgendaEventListener + RuleRuntimeEventListener)

`POST /pipeline/audit` 跟 `/pipeline/run` 跑同样的 pipeline，但响应里多一个 `auditTrail` 数组，包含从 fact insert 到 group 弹栈的完整事件序列。

```bash
curl -s -X POST 'http://localhost:8081/pipeline/audit' -H 'Content-Type: application/json' \
  -d '{
    "customer":{"name":"Diana","age":40,"vipLevel":3,"yearsSinceRegistration":5},
    "items":[{"name":"Server","quantity":1,"unitPrice":6000,"category":"ELECTRONICS"}]
  }' | python3 -m json.tool
```

`auditTrail` 关键观察点 (大额 VIP3 case)：

```
seq 1  OBJECT_INSERTED   Cart=...
seq 2  MATCH_CREATED     rule='Bulk discount in pipeline'
seq 3  GROUP_PUSHED      group='notify'              ← auto-focus 早于显式 setFocus
seq 4  MATCH_CREATED     rule='Big spender audit'
seq 5  GROUP_PUSHED      group='risk'                ← service.setFocus("risk")
seq 6  GROUP_PUSHED      group='discount'            ← service.setFocus("discount")
seq 7  GROUP_PUSHED      group='validate'            ← service.setFocus("validate") 栈顶
seq 8  MATCH_CREATED     rule='Apply VIP discount once'
seq 9  GROUP_POPPED      group='validate'            ← 栈顶先弹 (没匹配)
seq 10 MATCH_FIRED       rule='Apply VIP discount once'
seq 11 MATCH_FIRED       rule='Bulk discount in pipeline'
seq 12 GROUP_POPPED      group='discount'
seq 13 MATCH_CREATED     rule='Insurance reco in pipeline'
seq 14 MATCH_FIRED       rule='Insurance reco in pipeline'
seq 15 GROUP_POPPED      group='risk'
seq 16 MATCH_FIRED       rule='Big spender audit'    ← notify 虽然最早压栈, 最后才执行
seq 17 GROUP_POPPED      group='notify'
```

**学习观察点 (Step 6)**:

1. **栈语义可视化** — Step 5 里只能脑补的"setFocus 反向压栈"，在 audit trail 里看得清清楚楚：notify 因 auto-focus 最早进栈但最后弹出
2. **`MATCH_CANCELLED` 是 `not` 反向触发的视觉证据** — 在 Step 4 `riskKBase` 上挂 listener，跑"先有 ELECTRONICS 后 insert INSURANCE"的场景，能看到原本的 INSURANCE_RECO activation 被 cancelled (本 demo 暂没暴露 risk audit endpoint，加一个很简单)
3. **listener 是 cross-cutting** — 一个 listener 实例可以同时实现 `AgendaEventListener` + `RuleRuntimeEventListener`，挂载方式：`session.addEventListener(listener)` 两次。生产里常见做法是抽 `KieSessionFactory`，统一挂载 audit / metrics / trace
4. **`Rule` 公共 API 没暴露 agendaGroup** — 想知道某条 MATCH 属于哪个 group，看附近最近的 `GROUP_PUSHED` 事件，那就是当前栈顶

## Step 7: 决策表 (Excel 维护规则)

`POST /decision/calculate` 跑 `decisionKBase`，VIP 折扣档位维护在 `src/main/resources/rules/decision/vip-discount.xls`。

```bash
# VIP 2: 1000 × 0.9 = 900
curl -X POST 'http://localhost:8081/decision/calculate' -H 'Content-Type: application/json' \
  -d '{"customer":{"name":"Alice","vipLevel":2,"age":30,"yearsSinceRegistration":0},
       "items":[{"name":"X","quantity":1,"unitPrice":1000,"category":"ELECTRONICS"}]}'

# VIP 4: 表里加的新档位, 1000 × 0.8 = 800. 没改 Java/DRL 也生效
curl -X POST 'http://localhost:8081/decision/calculate' -H 'Content-Type: application/json' \
  -d '{"customer":{"name":"Eve","vipLevel":4,"age":50,"yearsSinceRegistration":10},
       "items":[{"name":"Y","quantity":1,"unitPrice":1000,"category":"ELECTRONICS"}]}'
```

**生成/重新生成 XLS**：

```bash
./mvnw test -Dtest=VipDiscountSheetGenerator
```

生成器在 `src/test/java/com/lrj/drools/tools/VipDiscountSheetGenerator.java`，用 Apache POI 写 .xls（HSSF 格式）。改档位推荐两种路径：

- 直接用 Excel/Numbers 打开 `vip-discount.xls` 改，业务方友好
- 改生成器 Java 代码再 `./mvnw test -Dtest=...` 重新生成，git 友好

**学习观察点 (Step 7)**:

1. **决策表 → DRL 是编译期转换** — Drools 启动时把表格里的数据行展开成等价 DRL 规则进 KieBase。运行时跟手写 DRL 完全一样
2. **schema 五行规矩** — `RuleTable Name` 之后必须按顺序排：列类型(CONDITION/ACTION) → **对象声明** (`$cart: Cart()`) → 约束片段 (`customer.vipLevel == $param`) → 标签 → 数据。漏对象声明那行会报 "snippets in the row that is meant for object declarations"
3. **Drools 8 不会自动 pick up `.xls`** — `KieServices.get().getKieClasspathContainer()` 只扫 `.drl`，看 `DroolsConfig.kieContainer()` 怎么程序化把决策表标 `ResourceType.DTABLE` 加进 `KieFileSystem`

## Step 8: CEP 滑窗风控 (事件 + 时间窗 + pseudo clock)

`POST /fraud/check` 接收一批 `OrderEvent`，按 timestamp 排序，逐个推进 pseudo clock + insert + fireAllRules。规则：同一 customer 5 分钟滑窗内 ≥ 3 单 → 发 `BurstAlert`。

```bash
# Case A: Alice 3 单全在 5 min 内 → 告警
curl -X POST 'http://localhost:8081/fraud/check' -H 'Content-Type: application/json' \
  -d '{"events":[
        {"orderId":"a1","customerName":"Alice","amount":100,"timestamp":0},
        {"orderId":"a2","customerName":"Alice","amount":200,"timestamp":60000},
        {"orderId":"a3","customerName":"Alice","amount":300,"timestamp":120000}
      ]}'
# → alerts: [{customerName:"Alice", eventCount:3, detectedAt:120000}]

# Case B: 第 3 单滑出 5 min 窗口 (t=400s, 窗口 [100s,400s], 前两单都掉出去) → 无告警
curl -X POST 'http://localhost:8081/fraud/check' -H 'Content-Type: application/json' \
  -d '{"events":[
        {"orderId":"b1","customerName":"Alice","amount":100,"timestamp":0},
        {"orderId":"b2","customerName":"Alice","amount":200,"timestamp":60000},
        {"orderId":"b3","customerName":"Alice","amount":300,"timestamp":400000}
      ]}'

# Case C: Alice 4 单 + Bob 2 单 → 只 Alice 一条告警 (not BurstAlert 阻止重复)
curl -X POST 'http://localhost:8081/fraud/check' -H 'Content-Type: application/json' \
  -d '{"events":[
        {"orderId":"c1","customerName":"Alice","amount":100,"timestamp":0},
        {"orderId":"c2","customerName":"Bob","amount":150,"timestamp":30000},
        {"orderId":"c3","customerName":"Alice","amount":200,"timestamp":60000},
        {"orderId":"c4","customerName":"Bob","amount":250,"timestamp":90000},
        {"orderId":"c5","customerName":"Alice","amount":300,"timestamp":120000},
        {"orderId":"c6","customerName":"Alice","amount":400,"timestamp":180000}
      ]}'
```

**学习观察点 (Step 8)**:

1. **`eventProcessingMode="stream"` 启用什么** — 事件时间线（`@timestamp` 排序而不是 insert 顺序）、滑窗 `over window:time(5m)`、`@expires` 自动过期。没有 stream mode 这些都不工作
2. **pseudo clock 让测试可重现** — `clockType="pseudo"` 时机器时间不参与决策，Java 侧 `SessionPseudoClock.advanceTime(...)` 决定"引擎眼里的 now"。生产改 `realtime`，行为一样但靠机器时钟
3. **事件必须按时间戳升序 insert** — stream mode 不允许时间倒退，否则窗口判断会乱
4. **fact 跟 event 是两种角色** — DRL 里 `declare OrderEvent @role(event) @timestamp(timestamp) end` 把普通 Java POJO 升级成事件；不加 `@role(event)` 它就是个普通 fact，没法用 over window
5. **5 分钟窗 vs 10 分钟 @expires** — 窗口决定哪些事件参与规则计算，`@expires` 决定何时从 working memory retract。后者要 ≥ 前者，否则规则还没来得及评估窗内的旧事件已经被 retract

## Step 9: 规则热加载 (运行时 DRL 编译)

`POST /hot/upsert` 把 DRL 字符串运行时编译成 KieBase 缓存到 registry，`POST /hot/run/{name}` 用对应 KieBase 跑 cart。同名 upsert 替换旧 KieBase，进行中的请求不受影响。

```bash
# 1. 推 v1 (任何 cart 都 7 折)
curl -X POST 'http://localhost:8081/hot/upsert' -H 'Content-Type: application/json' \
  -d '{"name":"flatDiscount","drl":"package hot.flat;\nimport com.lrj.drools.domain.Cart;\nrule \"Flat 70%\"\n    when\n        $c: Cart()\n    then\n        $c.applyRatioDiscount(0.7, \"v1: flat 30 off\");\nend"}'

# 2. 跑 v1 → finalAmount=700
curl -X POST 'http://localhost:8081/hot/run/flatDiscount' -H 'Content-Type: application/json' \
  -d '{"customer":{"name":"A","vipLevel":0,"age":30,"yearsSinceRegistration":0},
       "items":[{"name":"x","quantity":1,"unitPrice":1000,"category":"X"}]}'

# 3. 同名 upsert 替换成 v2 (8 折)
curl -X POST 'http://localhost:8081/hot/upsert' -H 'Content-Type: application/json' \
  -d '{"name":"flatDiscount","drl":"package hot.flat;\nimport com.lrj.drools.domain.Cart;\nrule \"Flat 80%\"\n    when\n        $c: Cart()\n    then\n        $c.applyRatioDiscount(0.8, \"v2: flat 20 off\");\nend"}'

# 4. 跑 v2 → finalAmount=800 (老规则消失, 不是 700)
curl -X POST 'http://localhost:8081/hot/run/flatDiscount' -H 'Content-Type: application/json' \
  -d '{"customer":{"name":"A","vipLevel":0,"age":30,"yearsSinceRegistration":0},
       "items":[{"name":"x","quantity":1,"unitPrice":1000,"category":"X"}]}'

# 5. 推编译错误的 DRL → 400 + 行号
curl -X POST 'http://localhost:8081/hot/upsert' -H 'Content-Type: application/json' \
  -d '{"name":"broken","drl":"rule THIS IS SYNTAX BROKEN end"}'

# 6. 查已注册名
curl http://localhost:8081/hot/list
```

**学习观察点 (Step 9)**:

1. **运行时编译 ≠ 重新启动** — DRL 字符串通过 `KieHelper.build()` 直接产出 KieBase，秒级生效。生产规则可以存数据库，应用启动时拉一遍 upsert
2. **KieBase 替换是引用切换，不打断进行中请求** — 老 KieSession 持有的是创建时的 KieBase 引用，registry 里 `put(name, newBase)` 只动 map 不动老对象。任何在跑的 fireAllRules 跑完它的活，下一个新请求才用新 KieBase
3. **错误反馈是 UX 重点** — 400 + 行号 + ANTLR 解析错误，让用户能在 curl/Postman 里直接修。LLM 生成 DRL 的场景，这个错误回路就是 reprompt 的输入
4. **跟 KieScanner 的关系** — KieScanner 是"KJAR + Maven repo + 定时轮询版本号 + 自动调 upsert"。本 demo 把"上传 + upsert"暴露成 HTTP；加个 `@Scheduled` 轮询数据库就是 KieScanner 等价物
5. **`KieHelper` 是 internal API** — 包名带 `org.kie.internal.utils`，稳定性弱于公共 API。生产更稳的是 `KieFileSystem + KieBuilder`（`DroolsConfig.kieContainer()` 走的就是这条路径）

## Step 10: KieSession 持久化 (Marshaller + JPA)

把 working memory + agenda state 序列化成 byte[], 经 Spring Data JPA 存 H2 file (`./data/drools-demo.mv.db`)。同一 sessionId 跨请求、跨重启接着上次的状态继续累积。

**场景**: 用户积分会员。`PurchaseEvent` 进来攒分, 累计到阈值解锁徽章; `LoyaltyState` 一直留在 working memory, 等级从 NONE 单调推进到 BRONZE → SILVER → GOLD。

```bash
# 1. 起新会话 alice (积分 0, NONE)
curl -X POST 'http://localhost:8081/loyalty/start' -H 'Content-Type: application/json' \
  -d '{"sessionId":"alice"}'
# → {"totalPoints":0,"tier":"NONE","unlockedBadges":[],"lastEarned":0}

# 2. 买 50 块 (够不上 BRONZE 100 分门槛)
curl -X POST 'http://localhost:8081/loyalty/alice/purchase' -H 'Content-Type: application/json' \
  -d '{"amount":50}'
# → {"totalPoints":50,"tier":"NONE",...}

# 3. 再买 60 块 → 累计 110 分, 解锁 BRONZE
curl -X POST 'http://localhost:8081/loyalty/alice/purchase' -H 'Content-Type: application/json' \
  -d '{"amount":60}'
# → {"totalPoints":110,"tier":"BRONZE","unlockedBadges":["BRONZE"],...}

# 4. 一笔大单 1000 块 → 1110 分, BRONZE→SILVER→GOLD 单次 fire 内链式触发
curl -X POST 'http://localhost:8081/loyalty/alice/purchase' -H 'Content-Type: application/json' \
  -d '{"amount":1000}'
# → {"totalPoints":1110,"tier":"GOLD","unlockedBadges":["BRONZE","SILVER","GOLD"],...}

# 5. 只读 peek (不 fire, 不写回)
curl 'http://localhost:8081/loyalty/alice'

# 6. 杀掉 app, 重启, 再 peek → 状态还在 (跨重启验证)
#    ./mvnw spring-boot:run
curl 'http://localhost:8081/loyalty/alice'

# 7. 未知 session
curl -o /dev/null -w '%{http_code}\n' 'http://localhost:8081/loyalty/ghost'        # → 404
curl -o /dev/null -w '%{http_code}\n' -X POST 'http://localhost:8081/loyalty/ghost/purchase' \
  -H 'Content-Type: application/json' -d '{"amount":10}'                            # → 400
```

**学习观察点 (Step 10)**:

1. **整段 working memory 一次性序列化** — `Marshaller.marshall(out, session)` 把 fact + agenda + activation 全压成 byte[]; `unmarshall(in)` 构造一个**新**的 KieSession 把状态填进去。不是"会话恢复活了过来", 是"在新 session 里重放状态"
2. **fact 类必须 `implements Serializable`** — record 不自动实现, `PurchaseEvent` / `LoyaltyState` 都显式声明。漏了的话 marshall 抛 `NotSerializableException`
3. **`MarshallerFactory` 在 internal 包** — Drools 8 路径是 `org.kie.internal.marshalling.MarshallerFactory` (不是 `org.kie.api.marshalling`), 还要加 `drools-serialization-protobuf` 依赖才有实现
4. **链式升级跨 fire 边界仍工作** — 单次购买 1000 元就同时解锁 BRONZE/SILVER/GOLD: `modify($s)` 让下一级规则的 LHS 重新评估, 整条链在一次 `fireAllRules` 内跑完。下次购买的 fire 开始时, tier 已经是 GOLD, 升级规则自然全部不再匹配
5. **跨重启状态留存** — H2 用 `jdbc:h2:file:./data/drools-demo`, 物理文件 `./data/drools-demo.mv.db` 留着 byte[]。停 app → 重启 → `GET /loyalty/alice` 还是 GOLD, 因为 unmarshall 拿到的是关停前一次 marshall 的字节
6. **跟 Drools 官方 `drools-persistence-jpa` 的差异** — 官方走 JTA + 多张表 + 自动事务边界, 复杂; 本 demo 一张 `session_snapshot` 单表 + 手动 marshall 边界 + Spring `@Transactional`。教学概念一致, 工程复杂度差一个数量级
7. **改 DRL 后老快照可能 unmarshall 失败** — 规则签名或 fact 字段变了, 旧 byte[] 反序列化对不上号。学习场景手动 `rm -rf ./data/` 清掉; 生产要做"快照版本号 + 迁移脚本", 超出本 demo 范围

## Step 11: StatelessKieSession 对比

复用 Step 2 的 `discountKBase` (同一组规则), 但派生 `type="stateless"` 的 ksession。同输入 stateful / stateless 业务结果完全等价; 教学要点在两边 **API 形态** 和 **状态管理** 的差异。

| 维度 | KieSession (stateful) | StatelessKieSession |
| --- | --- | --- |
| API 形态 | `newKieSession → insert × N → fireAllRules → dispose` (try/finally) | `execute(Iterable)` 一行 |
| 实例复用 | 不可跨请求复用 (非线程安全, 每请求新建) | 线程安全, **注成 Spring 单例反复用** |
| 跨调用记忆 | working memory 持续存在, 可跟 Marshaller (Step 10) 持久化 | 完全无状态, 两次 execute 互不知情 |
| 干预 agenda | 可 `setFocus` / `fireUntilHalt`, 分阶段 fire (Step 5 流水线) | 不行——`execute` 一次走完 fire |
| CEP stream | 支持 (Step 8) | 不支持, 没有时间线累积 |
| 适用场景 | 长寿命会话 / agenda 编排 / 跨调用累积 / CEP | RPC 单笔 / 批处理 / 任何"喂数据 → 拿结果"的无状态评估 |

```bash
# A. stateful (Step 2 老接口)
curl -X POST 'http://localhost:8081/discount/calculate' -H 'Content-Type: application/json' \
  -d '{"orderId":"o1","customer":{"name":"Alice","vipLevel":2,"age":35,"yearsSinceRegistration":5},
       "items":[{"name":"x","quantity":1,"unitPrice":1000,"category":"X"}]}'
# → finalAmount: 807.5, 3 条 discountReasons

# B. stateless (Step 11, 同输入应输出完全一致)
curl -X POST 'http://localhost:8081/stateless/calculate' -H 'Content-Type: application/json' \
  -d '{"orderId":"o1","customer":{"name":"Alice","vipLevel":2,"age":35,"yearsSinceRegistration":5},
       "items":[{"name":"x","quantity":1,"unitPrice":1000,"category":"X"}]}'
# → finalAmount: 807.5, 同样 3 条 discountReasons (跟 A 一模一样)

# C. stateless 批处理: 3 单独立计算, 互不串户
curl -X POST 'http://localhost:8081/stateless/batch' -H 'Content-Type: application/json' \
  -d '{"orders":[
        {"orderId":"b1","customer":{"name":"Alice","vipLevel":3,"age":35,"yearsSinceRegistration":1},
         "items":[{"name":"x","quantity":1,"unitPrice":600,"category":"X"}]},
        {"orderId":"b2","customer":{"name":"Bob","vipLevel":0,"age":40,"yearsSinceRegistration":5},
         "items":[{"name":"y","quantity":1,"unitPrice":300,"category":"Y"}]},
        {"orderId":"b3","customer":{"name":"Cathy","vipLevel":1,"age":28,"yearsSinceRegistration":0},
         "items":[{"name":"z","quantity":1,"unitPrice":2500,"category":"Z"}]}
      ]}'
# → [b1 finalAmount=460 (VIP3+满减), b2=285 (老用户), b3=2325 (VIP1+满减)]
```

**学习观察点 (Step 11)**:

1. **同 KBase 挂两种 ksession** — `kmodule.xml` 里 `discountKBase` 下并列两条 `<ksession>`: `discountSession` (默认 stateful) + `discountStatelessSession` (`type="stateless"`)。规则零改动两边都能跑, 选用哪个由调用方决定
2. **API 简洁度差距悬殊** — stateless `execute(List.of(c, o))` 一行搞定; stateful 要 newKieSession + try/finally + dispose, 漏 dispose 会泄漏 RETE 节点。**stateless 永远不会漏 dispose, 因为根本没暴露 dispose 给你调**
3. **实例复用是真的** — `StatelessDiscountService` 把 `StatelessKieSession` 注成 final 字段, 整个应用生命周期一个实例反复 `execute`。线程安全靠的是"每次 execute 内部新建一个干净的 stateful session 跑完即弃"
4. **结果获取靠 mutable fact 引用** — stateless 没有 `getObjects()` 给你扫 working memory (内部 session 已 dispose 拿不到), 所以传进去的 `Order` 必须是 mutable, 规则 `applyRatioDiscount` 改的是同一个 Java 对象, execute 返回后直接读它
5. **批处理零隔离成本** — `for (Order o : orders) stateless.execute(...)`, 第 N 单的 working memory 跟第 N-1 单完全独立, 不需要任何"清空"操作。stateful 想做到这个等价要每次 newKieSession + dispose, 写起来啰嗦得多
6. **何时**不能**用 stateless** — ① Step 5 那种 `setFocus("validate") → setFocus("discount")` 分阶段触发的编排; ② Step 8 CEP 的 stream mode + pseudo clock 重放; ③ Step 10 那种"会话持续存在跨请求累积"的场景。本质都是"fire 之间需要干预" — stateless 一次 execute 走完不给你这个口子
7. **跟 Step 10 持久化的关系** — stateless 天生不需要 marshall: 没有跨调用状态可保。Step 10 的 Marshaller / Spring Data JPA 那套只对 stateful 长寿命会话有意义

## Step 12: TMS (Truth Maintenance System)

`POST /tms/compare` 用一组 `Sensor` 在两个对照 kbase 各跑两阶段 fire，专门看 `insertLogical` 跟普通 `insert` 在"前提失配后是否撤销衍生 fact"上的差别。

阶段 1: Sensor.value = hotValue (默认 95) → fire → 触发 HIGH + CRITICAL 两条规则，衍生出 2 个 Alert。
阶段 2: modify Sensor.value = coolValue (默认 50) → 再 fire → 两条规则的 LHS (`value > 70` / `value > 90`) 全部失配。

```bash
curl -s -X POST 'http://localhost:8081/tms/compare' -H 'Content-Type: application/json' \
  -d '{"sensorName":"boiler-a","hotValue":95,"coolValue":50}' | python3 -m json.tool
```

期望响应 (关键字段):

```json
{
  "logical": {
    "phase1Alerts": [<HIGH>, <CRITICAL>],
    "phase2Alerts": []                       // ← TMS 引擎自动 retract 两个 Alert
  },
  "regular": {
    "phase1Alerts": [<HIGH>, <CRITICAL>],
    "phase2Alerts": [<HIGH>, <CRITICAL>]     // ← 普通 insert 跟前提解耦, Alert 还在
  }
}
```

**学习观察点 (Step 12)**:

1. **`insertLogical` 把"前提-结论"因果链交给引擎维护** — 业务规则只需要声明"什么情况下应当有 Alert", 撤销由 TMS 负责。普通 `insert` 要业务自己写一条"value 正常 → retract Alert"反向规则
2. **失配的精确性** — 把 coolValue 改成 80 (仍 > 70 但 < 90)，再调一遍，会看到 logical.phase2Alerts 只剩 HIGH, CRITICAL 被撤销。TMS 按"每个衍生 fact 各自的前提链"独立管理生命周期
3. **跟 Step 4 标记 fact 的语义差** — Step 4 `insert(Promotion) + not Promotion` 只是"防重入"; Step 12 `insertLogical(Alert)` 是"随前提进退"。两者完全不同, 按业务诉求选
4. **fact 字段必须可变 TMS 才有意义** — `Sensor` 是 mutable POJO; record 不可变, 没法 modify, 也就没有"前提变化"可言。这是 domain 设计跟规则语义绑定的典型例子
5. **两个 kbase 隔离的工程含义** — 单 kbase 同时跑 logical + regular 两条规则会让 Alert 互相污染 (logical 撤销了, regular 还在, 谁是谁?), 教学场景隔离是必须的; 生产里只会选一种

## Step 13: 后向链 + query

`POST /backward/contains` 给一组 Location 直接关系 + 一组查询，用递归 query `isContainedIn` 反向证明每条查询是否成立。

跟前面所有步骤的本质差异：**前向链 (Step 1-12) 是数据驱动 push** — 数据进 working memory，RETE 增量算出所有结论；**后向链 (Step 13) 是查询驱动 pull** — 给定目标，引擎反向递归找前提。后向链调用 `getQueryResults` 时不需要 `fireAllRules`，跟 agenda 解耦。

```bash
# 嵌套层级: Office → House → City → Country → Continent
curl -s -X POST 'http://localhost:8081/backward/contains' -H 'Content-Type: application/json' \
  -d '{
    "locations": [
      {"thing":"Office","container":"House"},
      {"thing":"House","container":"City"},
      {"thing":"City","container":"Country"},
      {"thing":"Country","container":"Continent"}
    ],
    "queries": [
      {"thing":"Office","container":"Country"},
      {"thing":"Office","container":"Continent"},
      {"thing":"House","container":"Office"},
      {"thing":"City","container":"House"}
    ]
  }' | python3 -m json.tool
```

期望响应:

```json
{
  "answers": [
    {"thing":"Office","container":"Country","contained": true},      // 3 跳证明
    {"thing":"Office","container":"Continent","contained": true},    // 4 跳证明
    {"thing":"House","container":"Office","contained": false},       // 方向反了
    {"thing":"City","container":"House","contained": false}          // 方向反了
  ],
  "ancestorsLookup": [
    {"thing":"Office","ancestors":["House","City","Country","Continent"]},
    ...
  ]
}
```

**学习观察点 (Step 13)**:

1. **递归 query 的结构** — `query isContainedIn(x, y) Location(x, y;) or (Location(z, y;) and isContainedIn(x, z;)) end`。基础情形 (直接事实) `or` 递归情形 (链一步 + 递归调用)。引导 z 是 query body 内自动绑定的中间变量, 不出现在参数表
2. **`@Position` 不能漏** — `Location(x, y;)` 末尾分号是位置模式标记, fact 类字段必须有 `@Position(N)` 注解。漏了报 "Unable to find @Positional field 0 for class Location"。record 组件上加 `@Position(0)` / `@Position(1)` 即可
3. **后向链不消耗 agenda** — 调 `session.getQueryResults("isContainedIn", "Office", "Country")` 直接拉证明结果, 不需要 `fireAllRules`。这是 push (前向) vs pull (后向) 的硬差别
4. **同一规则集可以前向 + 后向混用** — DRL 里既可以写 `rule ... when ... then ... end` 走前向链, 也可以写 `query ... end` 给后向链用; 规则 LHS 里还能用 `?queryName(...)` 把后向链嵌进前向链推理。本 demo 只走 Java API 演示, 保持简洁
5. **"输出绑定"模式没在本 demo 用** — Drools 支持把 query 参数当 unbound output (用 `Variable.v` 占位) 自动列出所有满足条件的绑定, 但那条 API 在 internal 包。本 demo 改成"枚举候选容器 + 逐个 boolean 后向链证明", 演示 query 是可复用的"证明子程序"
6. **跟前向链的传递闭包对比** — 用前向链算"间接包含"要写一条规则把 (A,B), (B,C) join 成 (A,C) 并 insert 新 Location, 还要处理 N 层递归的物化爆炸; 后向链按需展开, 不物化中间结果 (代价是每次查询都要重算)。N 跟"事实-查询比例"是选边的依据

## Step 14: 引擎安全护栏 (熔断 + AgendaFilter)

第一个偏"生产工程"的 Step。规则集是会被改错的代码 (业务方/运维都能动), 引擎必须有兜底, 不能指望"规则都写对"。三个生产必备护栏:

| 护栏 | API | 防的事故 |
| --- | --- | --- |
| 硬上限熔断 | `fireAllRules(maxFires)` | 失控循环规则 fire 满 N 条强制返回, 请求线程不挂死 |
| 超时熔断 | 另一线程 `session.halt()` | 按挂钟时间兜底, 跑到 timeout 优雅打断 (非 kill) |
| 灰度放行 | `fireAllRules(AgendaFilter)` | 按 `@release` 元数据运行时放行规则, 灰度/金丝雀/紧急下线不重编译 |

```bash
# A. 失控自增规则被 fireAllRules(maxFires) 截断在 50 (不传 maxFires 默认 100)
curl -s -X POST 'http://localhost:8081/guard/runaway' -H 'Content-Type: application/json' \
  -d '{"startValue":0,"maxFires":50}'
# → {"fireCount":50,"finalValue":50,...}  规则本会无限自增, 被硬上限按住

# B. 失控规则裸跑, watchdog 在 200ms 后 halt() 打断 (不传默认 200ms)
curl -s -X POST 'http://localhost:8081/guard/timeout' -H 'Content-Type: application/json' \
  -d '{"startValue":0,"timeoutMillis":200}'
# → {"fireCount":<几十万到上百万, 取决于机器>,"elapsedMillis":~200,...}  超时优雅返回

# C1. 灰度: 只放行 stable 通道 (不传 allowedReleases 默认就是 {"stable"})
curl -s -X POST 'http://localhost:8081/guard/canary' -H 'Content-Type: application/json' \
  -d '{"customer":{"name":"Tom","age":30,"vipLevel":0,"yearsSinceRegistration":1},
       "items":[{"name":"book","quantity":1,"unitPrice":200,"category":"BOOKS"}]}'
# → finalAmount=190 (满100减10), recommendations 含 baseline,
#    skipped=["Canary promo (release=canary)"]  ← canary 规则被拦, 编译进了 KieBase 但没生效

# C2. 灰度放量: 白名单加上 canary, 实验规则立即生效, 全程不重启不重编译
curl -s -X POST 'http://localhost:8081/guard/canary' -H 'Content-Type: application/json' \
  -d '{"customer":{"name":"Tom","age":30,"vipLevel":0,"yearsSinceRegistration":1},
       "items":[{"name":"book","quantity":1,"unitPrice":200,"category":"BOOKS"}],
       "allowedReleases":["stable","canary"]}'
# → finalAmount=152 ((200-10)*0.8), discountReasons 含 stable + canary 两条, skipped=[]
```

**学习观察点 (Step 14)**:

1. **`fireAllRules()` 默认就该带上限** — 裸 `fireAllRules()` 遇到失控规则永不返回, 请求线程挂死, 连锁打满线程池。生产里几乎所有调用都应写成 `fireAllRules(上限)`。"Runaway increment" 故意不写 no-loop 当失控靶子
2. **`halt()` 是优雅中断, 不是 kill** — watchdog 线程调 `session.halt()`, 引擎跑完当前 activation 后返回, 不留脏状态。`halt()` 是 KieSession 上少数能跨线程调的方法。按时间兜底比按次数更通用 (有的规则一次 fire 就很慢)
3. **AgendaFilter 在 fire 前拦截** — `accept(match)` 在每条 activation 真正执行 RHS 前被调, 返回 false 就跳过。规则全量编译进 KieBase, 运行时按白名单决定谁生效——这就是金丝雀/灰度/紧急下线, 不用动 DRL 不用重启
4. **读规则元数据走公共 API** — `Rule.getMetaData()` 返回 `Map<String,Object>` (`@release("canary")` → key=`release`)。对照 Step 6: `Rule` 公共接口**没有** `getAgendaGroup()` (只在 internal RuleImpl 上), 元数据却是公共的
5. **没标 @release 默认放行** — `ReleaseAgendaFilter` 把无标记规则当稳定基线永远放行 (C1 里 baseline 推荐就在)。这样灰度只控带标记的实验规则, 不会因为忘标把基线一起拦掉
6. **跟 Step 9 热加载的分工** — Step 9 是"换一整套规则" (KieBase 整体替换); Step 14 灰度是"同一套规则里运行时开关某几条"。生产里两者搭配: 热加载推新规则 (标 canary) → AgendaFilter 先小流量放行 → 验证 OK 再加进白名单放量

## Step 15: 规则可观测性指标 (Micrometer / Prometheus)

把 Step 6 的 listener 思路从"攒事件数组随请求返回"升级成"打 Micrometer 指标进全局 registry"。`/metrics/discount` 跟 Step 2 的 `/discount/calculate` 同入参同折扣逻辑 (复用 discountKBase 零改动), 但每次调用会累加规则指标, 经 `GET /actuator/prometheus` 抓取。

发出的指标 (都带 `session` tag, fired 额外带 `rule` tag):

| Prometheus 指标名 | 类型 | 含义 |
| --- | --- | --- |
| `drools_rules_fired_total{rule}` | counter | 每条规则触发次数 → 看**哪条规则最热** |
| `drools_matches_total` | counter | agenda 上产生的 activation 数 |
| `drools_matches_cancelled_total` | counter | 被撤销的 activation (not 反向触发 / retract / LHS 失配) |
| `drools_facts_total{op}` | counter | working memory 增/改/删, op=inserted\|updated\|deleted |
| `drools_session_fire_seconds` | summary | fireAllRules 整段耗时 + p50/p95/p99 分位 |

```bash
# 1. 打几次 metered 折扣 (同 Step 2 的 VIP2 老用户大单, finalAmount=807.5)
for i in 1 2 3; do
curl -s -X POST 'http://localhost:8081/metrics/discount' -H 'Content-Type: application/json' \
  -d '{"customer":{"name":"Alice","age":35,"vipLevel":2,"yearsSinceRegistration":5},
       "items":[{"name":"Laptop","quantity":1,"unitPrice":1000,"category":"X"}]}'
echo; done
# → 每次 {"order":{...,"finalAmount":807.5},"rulesFired":3}

# 2. 抓取 drools.* 指标
curl -s http://localhost:8081/actuator/prometheus | grep drools_
```

打 3 次后预期看到 (数值随调用累积):

```
drools_rules_fired_total{rule="VIP level 2 - 9 fold",session="discountSession"} 3.0
drools_rules_fired_total{rule="Bulk amount discount",session="discountSession"} 3.0
drools_rules_fired_total{rule="Loyal customer extra",session="discountSession"} 3.0
drools_matches_total{session="discountSession"} 9.0                  # 3 activation × 3 次
drools_facts_total{op="inserted",session="discountSession"} 6.0      # 2 insert × 3 次
drools_session_fire_seconds{session="discountSession",quantile="0.95"} 0.019...
drools_session_fire_seconds_count{session="discountSession"} 3
```

**学习观察点 (Step 15)**:

1. **指标分两层挂** — listener 层出 counter (`afterMatchFired` → fired、`matchCreated` → matches、`object*` → facts); service 层出 Timer (`fireAllRules` 整段耗时)。Timer 不能放 listener 里, 因为回调只看得到"单条 match fire 前后", 拿不到"整段 fire"的起止边界
2. **跟 Step 6 audit 的分工** — 同一套 listener 接口, 区别只在输出去向: Step 6 攒 `List<AuditEvent>` 跟单次结果返回 (**单请求放大镜**: 这一次到底怎么跑的), Step 15 累加进全局 registry (**跨请求仪表盘**: 整体趋势 / 报警阈值)。生产里两者都要
3. **Prometheus 会改名** — 代码里 `drools.rules.fired` (点分) 在 `/actuator/prometheus` 输出成 `drools_rules_fired_total` (下划线 + counter 补 `_total`); Timer 的 `drools.session.fire` 出 `drools_session_fire_seconds` (补单位)。grep 用下划线名
4. **tag 基数要克制** — `rule` 当 tag 没问题 (规则名有限可控)。但**千万别**把 orderId / customerId 这种无界值塞 tag, 每个唯一值都生成一条新时序, 会把 Prometheus 打爆。这是指标设计第一红线
5. **`rules_fired` vs `matches`** — 本例两者相等 (每个 activation 都 fire 了)。挂个 AgendaFilter (Step 14) 拦掉一部分, 或规则间 `matchCancelled`, 两个数就会分叉: matches 是"产生了多少候选", fired 是"实际执行了多少", 差值就是被撤销/拦截的
6. **暴露端点要显式开** — `management.endpoints.web.exposure.include` 默认只有 `health`, application.yml 里显式加了 `prometheus`。生产通常把 management 放单独 port + 加鉴权, 别把 `/actuator/**` 裸露公网

## Step 16: KieScanner + KJAR (规则跟代码独立发版)

Step 9 (`/hot/*`) 是"DRL 字符串 → Map 缓存"的应用内临时热加载。这一步是工业路径: DRL 打成 **KJAR** (带 kmodule.xml + pom 的标准 Maven 构件) → 装进本地 `~/.m2` → `KieContainer` 绑 **ReleaseId** 而非 classpath → `KieScanner` 轮询/scanNow 发现同 GAV 新内容就**热替换 KieBase**, 应用零改动零重启。

```bash
# 0. 还没 deploy → run 报 400
curl -s http://localhost:8081/scanner/status
# → {"releaseId":"com.lrj.rules:scanner-cart-rules:1.0.0-SNAPSHOT","containerReady":false,"generation":0,"polling":false}

# 1. deploy v1: 满 100 打 9 折 (注意 package 必须是 rules.scanner)
curl -s -X POST http://localhost:8081/scanner/deploy -H 'Content-Type: application/json' \
  -d '{"drl":"package rules.scanner\nimport com.lrj.drools.domain.Cart;\nrule \"v1 9 fold\"\n when $c: Cart(totalAmount >= 100)\n then $c.applyRatioDiscount(0.9, \"v1: 9 fold\");\nend"}'
# → {"generation":1,"action":"container 首次创建 ..."}

# 2. run → finalAmount=180 (200×0.9), generation=1
curl -s -X POST http://localhost:8081/scanner/run -H 'Content-Type: application/json' \
  -d '{"customer":{"name":"A","age":30,"vipLevel":0,"yearsSinceRegistration":0},"items":[{"name":"x","quantity":1,"unitPrice":200,"category":"X"}]}'

# 3. deploy v2: 同一个 SNAPSHOT GAV, 改成 8 折 → scanNow 热替换
curl -s -X POST http://localhost:8081/scanner/deploy -H 'Content-Type: application/json' \
  -d '{"drl":"package rules.scanner\nimport com.lrj.drools.domain.Cart;\nrule \"v2 8 fold\"\n when $c: Cart(totalAmount >= 100)\n then $c.applyRatioDiscount(0.8, \"v2: 8 fold\");\nend"}'
# → {"generation":2,"action":"scanner.scanNow() 热替换 KieBase ..."}

# 4. run 同一个 cart → finalAmount=160 (200×0.8), generation=2
#    应用没重启、container 没重建, v1 规则消失 v2 生效 — 这就是热替换
curl -s -X POST http://localhost:8081/scanner/run -H 'Content-Type: application/json' \
  -d '{"customer":{"name":"A","age":30,"vipLevel":0,"yearsSinceRegistration":0},"items":[{"name":"x","quantity":1,"unitPrice":200,"category":"X"}]}'

# 5. 看 KJAR 真的装进了本地 Maven 仓库
ls ~/.m2/repository/com/lrj/rules/scanner-cart-rules/1.0.0-SNAPSHOT/
# → scanner-cart-rules-1.0.0-SNAPSHOT.jar  .pom  maven-metadata-local.xml

# 6. 生产形态: 开自动轮询 (默认 5000ms), 之后 deploy 无需手动 scanNow
curl -s -X POST http://localhost:8081/scanner/poll/start -H 'Content-Type: application/json' -d '{"intervalMillis":3000}'
curl -s -X POST http://localhost:8081/scanner/poll/stop

# 7. 编译错误 → 400 + 行号
curl -s -X POST http://localhost:8081/scanner/deploy -H 'Content-Type: application/json' -d '{"drl":"this is not valid drl"}'
```

**学习观察点 (Step 16)**:

1. **KJAR = 版本化的规则构件** — 规则不再"长"在应用 classpath 上, 而是个独立的 Maven artifact (`group:artifact:version`)。规则团队的发版动作就是 `mvn deploy` 一个新 KJAR, 跟应用代码发版彻底解耦
2. **必须用 SNAPSHOT 才能滚动** — release 固定版本内容不可变 (Maven 契约), KieScanner 对它不触发更新。demo 固定一个 `1.0.0-SNAPSHOT` 反复 install 新内容; 生产用递增 release 版本 + `KieContainer.updateToVersion(newReleaseId)`
3. **scanNow vs start(interval)** — `scanNow()` 同步立即扫 (本 demo deploy 内部调它, 保证 HTTP 响应里立刻看到新内容); `start(ms)` 后台线程周期轮询, 才是生产无人值守形态 (`/scanner/poll/start` 演示)。这正是 Step 9 注释里说的"@Scheduled 轮询 = KieScanner 等价物"的真身
4. **热替换不打断进行中的请求** — 跟 Step 9 同理: `newKieSession` 拿的是当前 KieBase, scanNow 替换后只影响**之后新建的** session, 在跑的 fire 跑完老的。`generation` 字段让你肉眼确认切换发生在哪一次
5. **跟 Step 9 的取舍** — Step 9 轻 (无 Maven 依赖、规则即数据、应用自控编译时机), 适合"规则存数据库 / LLM 即时生成"; Step 16 重 (`kie-ci` 一票传递依赖 + 写 ~/.m2), 但换来标准化的版本/产物/多实例一致性, 适合"规则作为正式制品独立发版治理"
6. **副作用提示** — `installArtifact` 会真写 `~/.m2/repository/com/lrj/rules/`。这是 demo 自己的 GAV、每次 deploy 覆盖, 清理直接 `rm -rf ~/.m2/repository/com/lrj/rules`

## Step 17: DMN (Decision Model and Notation)

**第一个非 DRL 体系的 Step**。前面 Step 1-16 全是 Drools 私有的 DRL (when/then + RETE 前向链); DMN 是 OMG 跨厂商标准: `.dmn` XML 模型 + FEEL 表达式 + 决策需求图 (DRG)。两套引擎并存, 业务诉求选边。

`POST /dmn/price` 跑 `rules/dmn/vip-pricing.dmn` 模型, 决策需求图:

```
Customer ─────┐
              ├──> Discount Rate ──┐
Order Amount ─┼────────────────────┴──> Final Price
              └──> Membership Tier
```

- **Discount Rate**: DMN **原生**决策表 (vipLevel → 折扣率, hitPolicy UNIQUE)
- **Final Price**: FEEL 字面表达式 `Order Amount * (1 - Discount Rate)`, 依赖 Discount Rate → 决策链
- **Membership Tier**: FEEL if/else 链

```bash
# vipLevel 0 → rate 0, final 1000, 普通
curl -s -X POST http://localhost:8081/dmn/price -H 'Content-Type: application/json' \
  -d '{"customer":{"name":"Tom","age":30,"vipLevel":0,"yearsSinceRegistration":1},"orderAmount":1000}'
# → {"decisions":{"Discount Rate":0,"Final Price":1000,"Membership Tier":"普通"}}

# vipLevel 2 → rate 0.10, final 900, 会员
curl -s -X POST http://localhost:8081/dmn/price -H 'Content-Type: application/json' \
  -d '{"customer":{"name":"Amy","age":40,"vipLevel":2,"yearsSinceRegistration":3},"orderAmount":1000}'
# → {"decisions":{"Discount Rate":0.10,"Final Price":900.00,"Membership Tier":"会员"}}

# vipLevel 4 → rate 0.20, final 800, 钻石
curl -s -X POST http://localhost:8081/dmn/price -H 'Content-Type: application/json' \
  -d '{"customer":{"name":"Max","age":50,"vipLevel":4,"yearsSinceRegistration":8},"orderAmount":1000}'
# → {"decisions":{"Discount Rate":0.20,"Final Price":800.00,"Membership Tier":"钻石"}}
```

**学习观察点 (Step 17)**:

1. **DMN 是独立引擎, 不走 KieSession** — DRL 靠 `insert + fireAllRules`; DMN 靠 `DMNRuntime.evaluateAll(model, context)`, 按决策需求图 (DRG) 拓扑顺序求值 (Final Price 自动等 Discount Rate 先算完)。`DmnService` 把 DMNRuntime 当字段缓存, 线程安全可复用 (跟 Step 11 StatelessKieSession 同理)
2. **跟 Step 7 决策表的本质区别** — Step 7 是 Excel → 编译成 DRL → 跑 RETE (还是 DRL); Step 17 是 DMN 标准模型 → 独立求值引擎。两者都能给业务方表格维护, 但 DMN 跨厂商可移植、自带 FEEL 表达式、原生支持决策链, 表达力和标准化程度更高
3. **FEEL 表达式** — `Final Price` 是 `Order Amount * (1 - Discount Rate)`, `Membership Tier` 是 `if ... then ... else ...`。FEEL 是 DMN 标准内建的表达式语言, 不需要写 Java/DRL。number 类型底层是 BigDecimal (所以返回 `0.10` / `900.00`)
4. **结构化输入** — `Customer` 在 DMN 里是带 schema 的 `tCustomer` (name/vipLevel/age), 不是裸 fact。Java 侧灌 `Map`, FEEL 里 `Customer.vipLevel` 按 key 取值
5. **context key 要一字不差** — `DMNContext.set("Order Amount", ...)` 的 key 必须跟 `.dmn` 里 inputData 的 name 完全一致, **含空格**。写成 `"OrderAmount"` 那个输入就是 null
6. **.dmn 要显式标 ResourceType** — 跟 `.xls` 一样, Drools 8.44 不自动识别 `.dmn`, `DroolsConfig` 里扫 `.dmn` 标 `ResourceType.DMN` 编进 `dmnKBase`。模型语法错在**启动时**就暴露 (KieBuilder.buildAll 编译, 不是 lazy)

## Step 18: 营销活动资格判定 (一个真实业务场景)

**第一个把多步拼成完整业务流的 Step**, 不引入新机制, 演示"怎么组合"。场景: 运营创建营销活动时**绑定一段资格规则**, 用户申请参加时判定够不够格——"满足规则的才能参加这个活动"。

三步合体:
- **创建活动绑规则** = **Step 9** (`KieHelper` 把 DRL 字符串运行时编译成 KieBase)
- **规则持久化** = **Step 10** 的思路 (JPA + H2, 但存的是 DRL 源文本, 不是 marshall 的 session)
- **够格判定** = **Step 4** 的白名单标记 fact (默认不够格, 规则命中才 `insert(Eligibility(true, reason))`)

为什么这个场景非 Drools 不可: 活动天天新建、每个活动一套门槛、运营要自己改且不能等发版——规则必须"即数据"动态编译, 不能写死在代码里。

```bash
# 1. 创建活动 + 绑定资格规则 (DRL 由运营提供, import 用全限定类名)
curl -s -X POST http://localhost:8081/campaign/create -H 'Content-Type: application/json' -d '{
  "campaignId": "newuser-2026",
  "name": "新人专享活动",
  "eligibilityDrl": "package campaign.newuser;\nimport com.lrj.drools.domain.UserProfile;\nimport com.lrj.drools.domain.Eligibility;\n\nrule \"新人专享: 注册<30天 且 未消费过\"\nwhen\n    UserProfile(registrationDays < 30, totalSpent == 0)\nthen\n    insert(new Eligibility(true, \"新用户且未消费过\"));\nend\n\nrule \"一线城市新人加码\"\nwhen\n    UserProfile(registrationDays < 30, city in (\"北京\",\"上海\",\"广州\",\"深圳\"))\nthen\n    insert(new Eligibility(true, \"一线城市新人\"));\nend"
}'
# → {"campaignId":"newuser-2026","name":"新人专享活动","status":"ACTIVE"}

# 2. 够格用户: 注册10天 + 未消费 + 上海 → 两条规则都命中
curl -s -X POST http://localhost:8081/campaign/newuser-2026/check -H 'Content-Type: application/json' \
  -d '{"userId":"u1","age":25,"vipLevel":0,"registrationDays":10,"totalSpent":0,"city":"上海"}'
# → {"campaignId":"newuser-2026","userId":"u1","eligible":true,
#    "reasons":["新用户且未消费过","一线城市新人"],"firedCount":2}

# 3. 不够格: 老用户 + 已消费 + 非一线 → 一条没中
curl -s -X POST http://localhost:8081/campaign/newuser-2026/check -H 'Content-Type: application/json' \
  -d '{"userId":"u2","age":40,"vipLevel":2,"registrationDays":400,"totalSpent":5000,"city":"杭州"}'
# → {"campaignId":"newuser-2026","userId":"u2","eligible":false,"reasons":[],"firedCount":0}

# 4. 编译失败的 DRL → 400 + 行号 (绝不落库)
# 5. 看活动列表 (含 status + 是否已编译进内存缓存)
curl -s http://localhost:8081/campaign/list
# 6. 结束活动 → 之后 check 返回 409
curl -s -X POST http://localhost:8081/campaign/newuser-2026/end
```

**学习观察点 (Step 18)**:

1. **白名单式判定** — working memory 默认没有 `Eligibility`, 规则只在满足条件时 insert; fire 完 service 用 `session.getObjects(o -> o instanceof Eligibility)` 收集, 有 `eligible==true` 才算够格。安全默认是"漏写规则 → 没人够格", 不是"放所有人进来"
2. **多活动天然隔离** — 一个 `campaignId` 一个独立 KieBase (内存 `ConcurrentHashMap` 缓存), 各跑各的 working memory, 不像 Step 12 要担心衍生 fact 互相污染, 也不用 agenda-group 划分
3. **重启不丢活动 (rehydrate)** — 这是"Step 9 + 持久化"比纯 Step 9 多的能力。DRL 文本存 H2 `campaign` 表; 重启后内存缓存空 (list 里 `cached:false`), 第一次 check 触发 `computeIfAbsent` 从 DB 捞 DRL 重新编译 (`cached` 翻 `true`)。编译贵所以缓存, DB 是真相源所以重启不丢
4. **DRL 即数据** — 运营写的规则不进 `resources/rules/` 也不进 `kmodule.xml`, 走 KieHelper 编译, 加活动不用改配置、不用重启。编译失败返回 400 + 行号, 跑不起来的规则绝不落库
5. **跟 Step 16 的取舍** — 同样是"规则动态上线", Step 16 (KJAR + Maven repo) 适合"规则作为正式制品、多实例一致、版本治理"; Step 18 这种 "DRL 存业务库 + 应用内编译" 更轻, 适合"活动/规则就是业务数据"的高频小规则场景

## 下一步预告

- LLM × Drools: LLM 生成 DRL → `POST /hot/upsert` 或 `POST /scanner/deploy` 即时校验 + 上线; 或用 Drools/DMN 做 LLM 输出的硬约束验证 (Step 9 / Step 16 / Step 17 已铺好基础设施)
- 加 BatchExecutionCommand: stateless 也能拿命名结果 / 调用 query, 不局限于 mutable fact
- DMN 进阶: PMML 接入 (规则里嵌 ML 模型评分) / DMN 决策表的其他 hitPolicy (FIRST/PRIORITY/COLLECT)

需要时再喊我。
