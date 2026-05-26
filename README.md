# drools-demo

Drools 学习脚手架 — Hello World + Spring Boot 订单折扣示例。

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
mvn spring-boot:run
```

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

## 下一步预告

- LLM × Drools: LLM 生成 DRL → `POST /hot/upsert` 即时校验 + 上线; 或用 Drools 做 LLM 输出的硬约束验证 (本 Step 9 已铺好基础设施)
- StatelessKieSession 跟 KieSession 对比 (无 working memory 缓存, 一次 execute(list))
- 持久化 + 事务: JPA persistence of KieSession state for long-running flows
- 完整 KieScanner + KJAR: 把"上传 → upsert" 换成 Maven repo 轮询

需要时再喊我。
