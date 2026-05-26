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
2. **`update($o)` 不写会怎样** — 注释掉 `order-discount.drl` 里的 `update($o)`，看依赖 finalAmount 的规则会不会被忽略 (本例规则都看 totalAmount 所以可能感觉不出，第 3 步会专门玩这个)
3. **新增规则不用动 Java** — 在 `rules/discount/` 下加新 `.drl` 文件，重启即生效
4. **KieSession 不是线程安全** — 看 `DiscountService` 为什么每次请求都 `newKieSession` + `dispose`

## 下一步 (第 3 步预告)

学完这两步后建议依次玩:

- `accumulate` / `collect`: 对一组 fact 做聚合 (sum / count / list)
- `not` / `exists`: 否定条件 (没有满足 X 的 fact 时才触发)
- `agenda-group` + `setFocus`: 显式分组规则 (而不是只靠 salience)
- 决策表 (Excel): 业务方维护规则的方式 (已加 `drools-decisiontables` 依赖)
- 规则热加载: KieScanner + Maven 仓库 / 动态加载 DRL 字符串

需要时再喊我。
