# CLAUDE.md

这份文档给在本仓库工作的 Claude Code 使用，描述项目用途、技术栈、约定与已踩过的坑。

## 项目概览

Drools 学习脚手架，配合 LangChain4j 项目，学 Drools 规则引擎用，不是生产代码。三阶段：

- **Step 1 / Hello World**：`POST /hello` → 跑 `rules/hello/hello.drl`，演示 facts / when-then / 多规则独立触发
- **Step 2 / 订单折扣**：`POST /discount/calculate` → 跑 `rules/discount/order-discount.drl`，演示 salience 优先级、跨事实 join、规则叠加
- **Step 3 / 购物车**：`POST /cart/checkout` → 跑 `rules/cart/cart-rules.drl`，演示 `accumulate` (按品类 sum/count 聚合) 和 `modify` (修改 fact 字段 → 触发依赖该字段的规则级联)

后续（agenda-group / 决策表 / update 真实级联 / Phreak 内部 / 规则热加载）按需扩展，不要在没有需求时提前加。

## 技术栈

- Java 21
- Spring Boot 3.3.5（spring-boot-starter-parent）
- Drools 8.44.2.Final（kie-api / drools-core / drools-compiler / drools-mvel / drools-xml-support / drools-decisiontables）
- Maven（带 wrapper）

**版本选择背景**：Drools 9.x 仍偏 incubator，8.44.2.Final 是社区验证过的 Spring Boot 3.3 + Java 21 稳定组合。Drools 10 是 Apache KIE 改名后的新线，本项目不追，以免文档/教程跟不上。

## 目录结构

```text
src/main/
├── java/com/lrj/drools/
│   ├── DroolsDemoApplication.java            启动类
│   ├── config/DroolsConfig.java              KieContainer Bean（扫 classpath 的 META-INF/kmodule.xml）
│   ├── domain/
│   │   ├── Customer.java                     record (DRL 通过 record accessor 读字段 OK)
│   │   ├── OrderItem.java                    record，含 category 字段给 accumulate 用
│   │   ├── Order.java                        Step 2 fact，mutable
│   │   └── Cart.java                         Step 3 fact，mutable + 含 goldStatus (modify 演示用)
│   ├── service/
│   │   ├── DiscountService.java              Step 1/2 的 KieSession 生命周期
│   │   └── CartService.java                  Step 3 的 KieSession 生命周期
│   └── controller/
│       ├── DiscountController.java           REST: /hello, /discount/calculate
│       └── CartController.java               REST: /cart/checkout
└── resources/
    ├── application.yml                       端口 8081（跟 LangChain4j 8080 错开）
    ├── META-INF/kmodule.xml                  声明 hello/discount/cart 三个 kbase
    └── rules/
        ├── hello/hello.drl                   Step 1
        ├── discount/order-discount.drl       Step 2
        └── cart/cart-rules.drl               Step 3 (accumulate + modify)
```

## 运行

```bash
./mvnw spring-boot:run

# Step 1
curl -X POST 'http://localhost:8081/hello' -H 'Content-Type: application/json' \
  -d '{"name":"Bob","age":65,"vipLevel":1,"yearsSinceRegistration":5}'
# → {"rulesFired":2, ...}  规则触发的 println 打在控制台

# Step 2
curl -X POST 'http://localhost:8081/discount/calculate' -H 'Content-Type: application/json' \
  -d '{
    "customer":{"name":"Alice","age":30,"vipLevel":2,"yearsSinceRegistration":4},
    "items":[{"name":"Laptop","quantity":1,"unitPrice":600},{"name":"Mouse","quantity":2,"unitPrice":30}]
  }'
# → finalAmount=516.8, discountReasons=["VIP 2 级 9 折","原价满 500 减 50","老用户额外 95 折"]
```

完整 README（含 3 个 case + 学习观察点 + 下一步指引）在 `README.md`。

## REST 接口

| 方法 | 路径                    | 说明 |
| ---- | ----------------------- | ---- |
| POST | `/hello`                | Step 1：插一个 Customer，跑 helloSession，返回触发条数 |
| POST | `/discount/calculate`   | Step 2：插 Customer + Order，跑 discountSession，返回带折扣的 Order |
| POST | `/cart/checkout`        | Step 3：插 Cart（内含 items + customer），跑 cartSession，返回带折扣 + goldStatus 的 Cart |

## 配套文档

- `docs/rete-intuition.md` — RETE 算法直觉（拿本仓库折扣规则当例子，讲网络结构 + 增量传播 + 写规则原则）

## Drools 核心概念速查

- **KieServices**：全局入口，单例
- **KieContainer**：内存中的规则容器，启动时扫 classpath 编译 DRL（贵，注成 Bean）
- **KieBase**：一组规则的集合，由 kmodule.xml 里的 `<kbase>` 描述
- **KieSession**：运行时会话，从 KieContainer 按名字派生（廉价，**线程不安全**，每次请求新建+dispose）
- **working memory**：session 里 `insert()` 进去的 fact 集合
- **LHS / pattern**：`when` 部分，描述 fact 应该满足的条件，`$var: Type( cond )` 是变量绑定
- **RHS / consequence**：`then` 部分，匹配后跑的 Java；常用 `update(fact)` / `modify(fact){...}` / `insert()` / `retract()` 改 working memory
- **salience**：规则优先级，越大越先（默认 0）
- **agenda**：所有"已激活但未执行"的规则队列；`fireAllRules()` 一次清空（除非规则又激活了新的）

## 约定与扩展点

- **加新规则**：在 `rules/<kbase 名>/` 下加 `.drl` 文件即可，重启生效。新 package 路径要跟 `kmodule.xml` 里 `<kbase packages="...">` 的目录对得上
- **加新 KieBase**：编辑 `kmodule.xml` 加 `<kbase>` + `<ksession>`，service 里换 `newKieSession("新名字")`
- **加新 fact 类型**：放 `domain/` 下，普通 POJO / record 都行，DRL 里 `import` 后即可用
- **决策表**：依赖已加 `drools-decisiontables`；放 `.xls` 到 classpath，在 `kmodule.xml` 加 `<ruleTemplate>` 或者直接 `<kbase>` 里指 packages

## Step 3 关键语法速查（accumulate / modify）

**accumulate**: 对一个 Java 集合或 working memory 里的 fact 做聚合（sum / count / max / min / average / collectList / collectSet）

```drl
$cart: Cart()
Number(intValue >= 5) from accumulate(
    $item: OrderItem(category == "BOOK", $q: quantity) from $cart.getItems(),
    sum($q)
)
```

- `$result` 类型是 `Number`，所以写 `intValue >= 5` 或 `doubleValue >= 1000`，不能直接 `$result >= 5`
- `from $cart.getItems()` 锁定数据源到 Java 集合，不扫整个 working memory（多个 Cart 并发不会窜户）
- 但 list 内部增删不被 working memory 感知，要么 Java 侧改完显式 `update(cart)`，要么把 OrderItem 也 `insert` 成独立 fact

**modify**: 比 `update` 精准，告诉引擎"具体哪些属性变了" → Phreak 能更精确地触发依赖规则、剪枝传播

```drl
modify($cart) {
    setGoldStatus(true)
}
// 不需要再调 update($cart)，modify 块结束自动通知引擎
```

- modify 仍不防死循环，要靠"LHS 自然不再满足"或 `no-loop` 终止
- 改的属性如果是某条规则 LHS 的判断字段（goldStatus），那条规则会被重新评估 → 这就是 Step 3 的 Promote→Gold extra 级联

## 已踩过的坑（搭脚手架时踩出来的，写进注释了，别再踩）

1. **`org.kie:kie-bom` 在 8.44.2 没发布** → 用 `org.drools:drools-bom` 才对。`pom.xml` 里 dependencyManagement 已经是 `drools-bom`
2. **Drools 8.x 把 XML 解析拆成独立模块** → 必须显式加 `drools-xml-support`，否则启动报 `Unable to build index of kmodule.xml ... add module org.drools:drools-xml-support`
3. **多条规则都调 `update($o)` → 死循环 / 请求挂住**。原因：
   - `update()` 重新评估所有依赖该 fact 的规则
   - 本项目的 LHS 条件都看 `vipLevel` / `totalAmount` / `yearsSinceRegistration`（不可变字段），改 `finalAmount` 不会让条件失配，所以规则永远满足，被反复触发
   - `no-loop true` 只能防"自己 consequence 重新激活自己"，**不防其他规则的 update 间接重新激活自己**
   - 真要 cross-rule 防护用 `lock-on-active true` + `agenda-group`
   - **本 demo 根本不需要 update()**，因为没有规则读被修改的字段。教学上"什么时候用 update()"留到 Step 3 真实级联场景里讲
4. **Customer / OrderItem 是 record，DRL 里 `Customer( age >= 18 )` 能正常用**。Drools 8.x 的属性访问会自动尝试 record accessor（`age()`），不只是 `getAge()`。这条算确认信息，不是坑

## 注意事项

- `KieSession` 不是线程安全的，service 里每次请求 `newKieSession` + `dispose`，不要为了"省"而复用
- DRL 是运行时解析的，`mvn compile` 过了不代表规则没语法错。改完 DRL 一定要至少启动一次 / 跑一次冒烟请求
- 端口 8081 跟 LangChain4j 主项目（8080）错开，要同时跑两个 demo 不冲突
- 这是学习项目，**不要引入持久化、观测性、复杂部署相关的东西**，除非 owner 明确要做某个 Step 才加
