# QLExpress / Drools 手写代码题

> 面试白板 + 现场编码陪练。每题给 **题目 → 参考答案 → 考官会追什么**。
> 概念部分见 [`qlexpress-vs-drools.md`](qlexpress-vs-drools.md)。Drools 侧答案的语法都对齐本仓库中已跑通的 DRL（`drools-lab/src/main/resources/rules/`），可以直接抄来验证。

**怎么练**：先盖住答案自己写一遍，写完再对照。重点不是背 API 名，而是**说得出每一行为什么这么写**——面试官几乎必追"这行去掉会怎样"。

---

## 目录

- [A. QLExpress 手写题（8 题）](#a-qlexpress-手写题)
- [B. Drools DRL 手写题（8 题）](#b-drools-drl-手写题)
- [C. Drools Java API 手写题（5 题）](#c-drools-java-api-手写题)
- [D. 找茬 / 推演题（纸上题，4 题）](#d-找茬--推演题)
- [E. 白板速查卡](#e-白板速查卡)

---

## A. QLExpress 手写题

### A1. 最小闭环：跑通一个业务判断

**题**：用 QLExpress 判断"订单金额 > 500 且会员等级 ≥ 2"，脚本从外部传入。写出 3.x 和 4.x 两版。

<details><summary>参考答案</summary>

```java
// ---------- 3.x ----------
// ExpressRunner 线程安全，做成单例 Bean；isPrecise=true 走 BigDecimal，金额场景必开
private final ExpressRunner runner = new ExpressRunner(true, false);

public boolean eval(String script, Order order) {
    // context 每次新建：3.x 里脚本内定义的变量会写回 context，复用会串数据
    DefaultContext<String, Object> ctx = new DefaultContext<>();
    ctx.put("amount", order.getAmount());
    ctx.put("vipLevel", order.getVipLevel());

    // 参数依次是：脚本 / 上下文 / 错误列表 / 是否走指令集缓存 / 是否 trace
    Object r = runner.execute(script, ctx, null, true, false);
    return Boolean.TRUE.equals(r);
}
// script = "amount > 500 && vipLevel >= 2"

// ---------- 4.x ----------
private final Express4Runner runner4 = new Express4Runner(InitOptions.DEFAULT_OPTIONS);

public boolean eval4(String script, Order order) {
    Map<String, Object> ctx = new HashMap<>();
    ctx.put("amount", order.getAmount());
    ctx.put("vipLevel", order.getVipLevel());

    return (Boolean) runner4.execute(script, ctx,
            QLOptions.builder().cache(true).precise(true).timeoutMillis(200L).build())
            .getResult();
}
```
</details>

**追问**：`isCache=true` 缓的是什么？（答：**指令集** `InstructionSet`，key 是脚本文本，命中就跳过词法/语法/编译三步）→ 那 key 会不会爆？（答：会，所以规则必须参数化，绝不能把用户 ID 拼进脚本）

---

### A2. 自定义操作符

**题**：让脚本能写 `"abcdef" 包含 "cd"`。

<details><summary>参考答案</summary>

```java
public class ContainsOperator extends Operator {
    @Override
    public Object executeInner(Object[] list) throws Exception {
        Object src = list[0], target = list[1];
        if (src == null || target == null) return false;   // null 安全，别让规则抛异常
        return String.valueOf(src).contains(String.valueOf(target));
    }
}

// 注册成中缀操作符
runner.addOperator("包含", new ContainsOperator());
// 脚本：orderRemark 包含 "加急"
```
</details>

**追问**：`addOperator` 和 `addFunction` 区别？（答：`addOperator` 注册**中缀操作符**，写法 `a 包含 b`；`addFunction` 注册**函数**，写法 `包含(a, b)`。两者都收 `Operator` 实现，差别只在语法位置）

---

### A3. 把 Spring Bean 暴露给脚本

**题**：让业务规则里能直接写 `查会员等级(userId) >= 3`。

<details><summary>参考答案</summary>

```java
@Component
public class RuleRunnerFactory {

    private final ExpressRunner runner = new ExpressRunner(true, false);

    public RuleRunnerFactory(MemberService memberService, RiskService riskService) throws Exception {
        // 参数：脚本里的函数名 / 服务对象 / 方法名 / 参数类型 / 错误提示
        runner.addFunctionOfServiceMethod("查会员等级", memberService,
                "getLevel", new Class[]{String.class}, null);
        runner.addFunctionOfServiceMethod("命中黑名单", riskService,
                "inBlacklist", new Class[]{String.class}, null);
        // 静态方法用 addFunctionOfClassMethod
        runner.addFunctionOfClassMethod("取绝对值", Math.class.getName(),
                "abs", new String[]{"double"}, null);
    }
}
```
</details>

**追问**：这样做的风险？（答：脚本里能调这些 Bean，等于把 RPC / DB 查询塞进了规则执行路径——**规则耗时不可控**，且同一个字段可能被多条规则重复查。生产更稳的做法是**先 `getOutVarNames` 算出要哪些数据，Java 侧批量预取塞进 context**，脚本里只做纯计算）

---

### A4. 中文 DSL：让业务方能读懂规则

**题**：用别名 + 宏，把规则写成业务方看得懂的样子。

<details><summary>参考答案</summary>

```java
runner.addOperatorWithAlias("如果", "if", null);
runner.addOperatorWithAlias("则", "then", null);
runner.addOperatorWithAlias("否则", "else", null);
runner.addOperatorWithAlias("并且", "&&", null);
runner.addOperatorWithAlias("或者", "||", null);

runner.addMacro("是成年人", "age >= 18");
runner.addMacro("是大额订单", "amount > 5000");

// 业务方写的规则：
//   如果 (是成年人 并且 是大额订单) 则 { return "需要人工审核"; } 否则 { return "自动通过"; }
```
</details>

**追问**：宏和函数怎么选？（答：宏是**表达式文本替换**，零调用开销，适合"命名一段条件"；函数有独立作用域和参数，适合可复用的计算。宏改了要清缓存）

---

### A5. `getOutVarNames` 做按需取数

**题**：规则脚本由业务方配置，你不知道它要哪些字段。怎么避免"为跑一条规则把整个用户画像都查出来"？

<details><summary>参考答案</summary>

```java
public DefaultContext<String, Object> buildContext(String script, String userId) throws Exception {
    // 静态分析：不执行脚本，只解析指令集，取出它引用的外部变量名
    String[] needed = runner.getOutVarNames(script);
    Set<String> keys = Set.of(needed);

    DefaultContext<String, Object> ctx = new DefaultContext<>();
    if (keys.contains("会员等级"))  ctx.put("会员等级", memberService.getLevel(userId));
    if (keys.contains("历史订单数")) ctx.put("历史订单数", orderService.countOf(userId));
    if (keys.contains("风险分"))    ctx.put("风险分", riskService.score(userId));
    return ctx;
}
```

配套：规则**保存时**就把 `getOutVarNames` 的结果存进 DB 的 `required_vars` 字段，运行期直接读，连解析都省了。
</details>

**追问**：这个能力 Drools 有对应物吗？（答：概念上对应"这条规则依赖哪些 fact 类型"，Drools 可以遍历 `KiePackage.getRules()` 拿规则元数据，但没有这么直接的"外部变量清单"API——因为 Drools 的数据是 insert 进去的 fact，不是按名取的变量）

---

### A6. 写一个生产可用的封装

**题**：把 QLExpress 封装成一个可以上线的 `RuleEvaluator`：单例、缓存、超时、精度、安全、fail-safe。

<details><summary>参考答案</summary>

```java
@Component
public class QlRuleEvaluator {

    private static final Logger log = LoggerFactory.getLogger(QlRuleEvaluator.class);

    /** 线程安全，单例复用；内部指令集缓存是 ConcurrentHashMap */
    private final ExpressRunner runner = new ExpressRunner(true, false);   // isPrecise=true

    /** 3.x 没有内置超时，用线程池 + Future 兜住 while(true) 这类失控脚本 */
    private final ExecutorService pool = new ThreadPoolExecutor(
            4, 16, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>(1000),
            new ThreadPoolExecutor.CallerRunsPolicy());

    public QlRuleEvaluator() {
        // 禁掉 Runtime.exec / System.exit 之类的高危方法
        QLExpressRunStrategy.setForbidInvokeSecurityRiskMethods(true);
    }

    /** 规则入库前调用：只编译不执行，语法错直接拒绝保存 */
    public void validate(String script) {
        try {
            runner.parseInstructionSet(script);
        } catch (Exception e) {
            throw new IllegalArgumentException("规则语法错误: " + e.getMessage(), e);
        }
    }

    /** 执行；任何异常都 fail-safe 返回 defaultValue，规则引擎不能成为单点 */
    public boolean evalBoolean(String script, Map<String, Object> vars, boolean defaultValue) {
        long start = System.nanoTime();
        try {
            DefaultContext<String, Object> ctx = new DefaultContext<>();
            vars.forEach(ctx::put);

            Future<Object> f = pool.submit(() -> runner.execute(script, ctx, null, true, false));
            Object r = f.get(200, TimeUnit.MILLISECONDS);          // 超时熔断
            return r instanceof Boolean b ? b : defaultValue;

        } catch (TimeoutException e) {
            log.warn("[rule] 执行超时，走兜底. script={}", abbreviate(script));
            return defaultValue;
        } catch (Exception e) {
            log.warn("[rule] 执行异常，走兜底. script={}", abbreviate(script), e);
            return defaultValue;
        } finally {
            metrics.timer("rule.eval").record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }

    /** 规则发布后调用，防止旧指令集残留 */
    public void onRulePublished() {
        runner.clearExpressCache();
    }
}
```
</details>

**追问**：`Future.get` 超时后那个线程还在跑吗？（答：**还在**，`cancel(true)` 只是打 interrupt 标志，纯计算的 `while(true)` 不响应中断。所以超时只是让调用方不被拖死，真正的防线是**规则审核时禁死循环语法** + 独立线程池隔离 + 池满走 `CallerRuns` 或直接拒绝。4.x 的 `timeoutMillis` 是引擎在指令循环里检查，能真正停下来）

---

### A7. 规则集调度器（QLExpress 没有 Agenda，手写一个）

**题**：有一批优惠规则，每条带优先级和叠加策略（互斥 / 可叠加 / 取最大）。QLExpress 只能算单条，怎么组织？

<details><summary>参考答案</summary>

```java
public record RuleDef(String id, int priority, String condition, BigDecimal reward,
                      StackStrategy strategy) {}

public enum StackStrategy { MUTEX, STACK, MAX }

public List<RuleDef> resolve(List<RuleDef> rules, Map<String, Object> vars) {
    // 1) 逐条求值，筛出命中的（这一步 QLExpress 只负责"这条成不成立"）
    List<RuleDef> hit = rules.stream()
            .filter(r -> evaluator.evalBoolean(r.condition(), vars, false))
            .sorted(Comparator.comparingInt(RuleDef::priority).reversed())   // 手写 salience
            .toList();

    if (hit.isEmpty()) return List.of();

    // 2) 手写冲突消解 —— 这部分正是 Drools 的 Agenda 免费给你的
    return switch (hit.get(0).strategy()) {
        case MUTEX -> List.of(hit.get(0));                                    // 只取最高优先级
        case MAX   -> List.of(hit.stream()
                        .max(Comparator.comparing(RuleDef::reward)).orElseThrow());
        case STACK -> hit;                                                    // 全部叠加
    };
}
```
</details>

**追问**：这套和 Drools 比差在哪？（答：差三样——① 规则之间**不能互相激活**（A 命中后让 B 的条件成立，这里要自己再跑一轮）；② 没有跨 fact 的自动 join 和聚合，得自己在 Java 里预算好塞进 context；③ 规则数 × 数据量大时是 `O(N)` 全量求值，没有增量匹配。**反过来，它赢在确定性强、无副作用、易测试、易灰度**）

---

### A8. 规则热更新链路

**题**：业务后台改了规则，怎么让 20 台机器秒级生效且能回滚？

<details><summary>参考答案（说思路 + 关键代码）</summary>

```java
@Scheduled(fixedDelay = 3000)
public void poll() {
    long remoteGen = ruleRepo.currentGeneration();     // DB 里的发布代际号
    if (remoteGen == localGen) return;                 // 没变就什么都不做

    List<RuleDef> fresh = ruleRepo.loadByGeneration(remoteGen);
    // 1) 先全部预编译，任何一条失败 → 整代不生效，保持旧代继续服务
    for (RuleDef r : fresh) evaluator.validate(r.condition());
    // 2) 原子替换本地快照（volatile 引用整体切换，不做增量改，避免中间态）
    this.snapshot = Map.copyOf(fresh.stream().collect(toMap(RuleDef::id, identity())));
    this.localGen = remoteGen;
    log.info("[rule] 切到代际 {}, 规则数 {}", remoteGen, fresh.size());
}
```

要点：**代际号**（不是逐条更新）→ **预编译全量校验**（不合格整代不上）→ **引用原子切换**（无中间态）→ **回滚 = 把代际号指回上一版**。
</details>

**追问**：为什么不用 MQ 推？（答：可以，推 + 轮询兜底最稳。纯推有丢消息和机器晚上线的问题，纯轮询有延迟。生产一般"MQ 推触发立即拉 + 定时轮询兜底"）

---

## B. Drools DRL 手写题

> 以下 DRL 的语法都对齐本仓库已跑通的规则文件，可直接拷进 `drools-lab/src/main/resources/rules/` 验证。

### B1. 跨 fact join + salience

**题**：写一条规则——VIP2 客户下单金额超 500 时打八折。

<details><summary>参考答案</summary>

```java
package rules.discount

import com.lrj.drools.domain.Customer
import com.lrj.drools.domain.Order
import java.math.BigDecimal

rule "VIP2 满 500 打八折"
    salience 100                       // 同组内优先级，越大越先
    when
        $c: Customer( vipLevel == 2 )                       // 绑定变量供 join / RHS 用
        $o: Order( customer == $c, totalAmount > 500 )      // 跨 fact join
    then
        $o.setFinalAmount($o.getTotalAmount().multiply(new BigDecimal("0.8")));
end
```
</details>

**追问**：为什么不写 `update($o)`？（答：LHS 看的是 `vipLevel` / `totalAmount`（不可变），RHS 改的是 `finalAmount`。`update` 会让所有依赖 `$o` 的规则重新评估，而条件依旧满足 → **无限循环**。这个 demo 根本不需要 update）
→ 那真要级联怎么办？（答：用 `modify($o){ setXxx(...) }`，并确保改完 LHS 会自然失配，或加 `no-loop` / `lock-on-active`）

---

### B2. accumulate 聚合

**题**：购物车里图书类商品满 5 本立减 20。

<details><summary>参考答案</summary>

```java
rule "图书满 5 本减 20"
    salience 50
    when
        $cart: Cart()
        // accumulate 返回 Number，所以条件写 intValue >= 5，不能直接写 $result >= 5
        Number( intValue >= 5 ) from accumulate(
            $item: OrderItem( category == "BOOK", $q: quantity ) from $cart.getItems(),
            sum($q)
        )
    then
        $cart.applyFixedDiscount(20, "图书满 5 本减 20");
end
```
</details>

**追问**：`from $cart.getItems()` 去掉会怎样？（答：accumulate 会扫**整个 working memory** 里的 OrderItem，多个购物车同时存在时会"窜户"。加了 `from` 就锁定数据源）
→ `from` 的代价？（答：list 内部变化 working memory 感知不到，Java 侧改完要显式 `update(cart)`；生产更常见的是把 OrderItem 也 insert 成独立 fact，让引擎自己管）
→ 为什么是 `Number` 不是 `int`？（答：accumulate 的结果类型是 `Number`，所以用 `intValue` / `doubleValue` 做约束）

---

### B3. not / exists 自终止

**题**：风险客户推荐一次风控套餐，但同一个客户只能推一次。

<details><summary>参考答案</summary>

```java
rule "高风险客户推荐风控套餐"
    when
        $c: Customer( riskScore > 80 )
        not Promotion( customerId == $c.getId() )    // 还没推过
    then
        insert(new Promotion($c.getId(), "RISK_PACKAGE", "高风险客户风控套餐"));
end
```

关键：RHS `insert` 的 `Promotion` 是**标记 fact**，插进去后上面的 `not` 立刻失配 → 规则自动终止，不需要 `no-loop`。
</details>

**追问**：`not` 和 `exists` 区别？（答：`not` 是"不存在"，`exists` 是"至少存在一个，且**只触发一次**"——直接写 `Promotion(...)` 不加 `exists` 会**每匹配一个就触发一次**，这是 `exists` 存在的意义）
→ RHS 里 `Promotion` 是 record 的话怎么读字段？（答：**RHS 没有 record accessor 糖**，必须写 `$p.message()` 而不是 `$p.getMessage()`——LHS 引擎会自动适配，RHS 直接编译成 Java 不会）

---

### B4. agenda-group 三阶段流水线

**题**：把规则切成 校验 → 打分 → 通知 三个阶段，严格按顺序执行。

<details><summary>参考答案</summary>

DRL：

```java
rule "校验：金额必须为正"
    agenda-group "validate"
    lock-on-active true            // 本组持有焦点期间只触发一次，防跨规则重激活
when
    $o: Order( totalAmount <= 0 )
then
    $o.reject("金额非法");
end

rule "打分：大额加分"
    agenda-group "score"
when
    $o: Order( totalAmount > 5000, rejected == false )
then
    modify($o) { setScore($o.getScore() + 10) }
end

rule "通知：被拒单发消息"
    agenda-group "notify"
    auto-focus true                // 被激活时自动把自己所在的 group 压栈
when
    $o: Order( rejected == true )
then
    notifier.send($o);
end
```

Java 侧压栈——**LIFO，所以反着压**：

```java
KieSession s = kieBase.newKieSession();
s.getAgenda().getAgendaGroup("notify").setFocus();     // 最后执行
s.getAgenda().getAgendaGroup("score").setFocus();
s.getAgenda().getAgendaGroup("validate").setFocus();   // 最先执行（最后压栈 = 栈顶）
s.insert(order);
s.fireAllRules();
```
</details>

**追问**：`no-loop` 和 `lock-on-active` 区别？（答：`no-loop` 只防**本规则 RHS 重新激活自己**；`lock-on-active` 是"该 agenda-group 持有焦点期间本规则只触发一次"，能防**其他规则的 modify 间接把我重新激活**。跨规则循环必须用后者）
→ `auto-focus` 干嘛的？（答：规则被激活时自动把自己的 group 压上焦点栈，省得 Java 侧显式 `setFocus`，适合"异常兜底组"）

---

### B5. TMS：结论自动撤销

**题**：传感器温度超 80 度告警，温度降下来告警要自动消失，不许手写撤销逻辑。

<details><summary>参考答案</summary>

```java
rule "超温告警"
when
    $s: Sensor( temperature > 80 )
then
    insertLogical(new Alert($s.getId(), "OVERHEAT"));   // 注意是 insertLogical
end
```

`insertLogical` 把 `Alert` 与"导出它的那次 LHS 匹配"绑定：一旦 `Sensor.temperature` 降到 80 以下、LHS 失配，引擎**自动 retract** 这个 Alert。用普通 `insert` 则 Alert 与前提解耦，得自己写规则删。
</details>

**追问**：TMS 生效的前提？（答：前提 fact 的字段**必须可变**且改动要让引擎知道——`Sensor` 得是 mutable POJO 且用 `modify` / `update` 修改，否则 LHS 永远不会失配，TMS 无从触发）
→ 同一个 Alert 被两条规则 `insertLogical` 呢？（答：引擎做引用计数，**所有**支撑它的匹配都失效后才真正 retract）

---

### B6. CEP 滑窗风控

**题**：同一客户 5 分钟内下单 ≥ 3 次告警，且一次爆发只告一次。

<details><summary>参考答案</summary>

```java
package rules.fraud

import com.lrj.drools.domain.OrderEvent
import com.lrj.drools.domain.BurstAlert

// 把 POJO 标成事件：用业务字段当事件时间，10 分钟后自动过期
// （过期时间要 > 规则滑窗，留余量给评估；窗口越大 β memory 越大）
declare OrderEvent
    @role(event)
    @timestamp(timestamp)
    @expires(10m)
end

rule "5 分钟内同一客户下单 >= 3 次"
when
    $event: OrderEvent( $cust: customerName )
    $count: Number( intValue >= 3 ) from accumulate(
        OrderEvent( customerName == $cust ) over window:time(5m),
        count(1)
    )
    not BurstAlert( customerName == $cust )       // 自终止：告过就不再告
then
    insert(new BurstAlert($cust, $count.intValue(), $event.timestamp()));
end
```

kmodule.xml 侧必须开 stream mode：

```xml
<kbase name="fraudKBase" packages="rules.fraud" eventProcessingMode="stream">
    <ksession name="fraudSession" clockType="pseudo"/>
</kbase>
```

测试里推时钟：

```java
SessionPseudoClock clock = session.getSessionClock();
session.insert(new OrderEvent("alice", clock.getCurrentTime()));
clock.advanceTime(2, TimeUnit.MINUTES);
```
</details>

**追问**：为什么必须 stream mode？（答：cloud mode 下引擎不维护事件时间线，`over window:time` / `@expires` 都不生效）
→ 为什么测试用 pseudo clock？（答：realtime 时钟下滑窗测试不可重现、还得真 sleep；pseudo 由 Java 侧 `advanceTime` 精确推进。生产改回 realtime）

---

### B7. 后向链 query

**题**：给定一堆 `Location(thing, container)` 直接包含关系，问"Office 是否（递归地）在 Country 里"。

<details><summary>参考答案</summary>

```java
query isContainedIn(String x, String y)
    Location(x, y;)                       // 基础情形：直接包含（位置参数解构，注意末尾分号）
    or
    (   Location(z, y;)                   // 递归情形：存在中间容器 z
        and
        isContainedIn(x, z;)
    )
end
```

Java 侧调用——**query 不进 agenda，不需要 fireAllRules**：

```java
QueryResults results = session.getQueryResults("isContainedIn", "Office", "Country");
boolean contained = results.size() > 0;
```
</details>

**追问**：前向链能做吗？（答：能，但要自己写规则把**传递闭包**算出来并 insert 一堆中间 fact，working memory 会膨胀；后向链只声明"传递性"本身，引擎递归证明，不落 fact）
→ `Location(x, y;)` 那个分号是什么？（答：**位置参数模式**，按类型字段声明顺序绑定，等价于 `Location(thing == x, container == y)`）

---

### B8. 灰度：规则元数据 + AgendaFilter

**题**：新规则先只对金丝雀流量生效，怎么做到不改 DRL 内容就能开关？

<details><summary>参考答案</summary>

DRL 里打元数据：

```java
rule "新版大额风控"
    @stage("canary")
when
    $o: Order( totalAmount > 10000 )
then
    $o.markReview("canary 规则命中");
end
```

Java 侧按元数据放行：

```java
public class ReleaseAgendaFilter implements AgendaFilter {
    private final Set<String> allowedStages;

    public ReleaseAgendaFilter(Set<String> allowedStages) {
        this.allowedStages = allowedStages;
    }

    @Override
    public boolean accept(Match match) {
        // Rule.getMetaData() 是公共 API，读 @stage("...") 的值
        Object stage = match.getRule().getMetaData().get("stage");
        return stage == null || allowedStages.contains(String.valueOf(stage));  // 没标的默认放行
    }
}

// 灰度流量：放行 GA + canary；正常流量只放行 GA
session.fireAllRules(new ReleaseAgendaFilter(isCanary ? Set.of("GA", "canary") : Set.of("GA")));
```
</details>

**追问**：AgendaFilter 是在匹配前还是匹配后？（答：**匹配已经发生了**，Filter 只决定这个 activation 要不要执行 RHS。所以它省的是 RHS 的副作用，不省匹配开销）

---

## C. Drools Java API 手写题

### C1. 运行时编译 DRL + 缓存（"规则即数据"最小实现）

**题**：DRL 存在数据库里，写一个服务：编译、校验、缓存、执行。

<details><summary>参考答案</summary>

```java
@Service
public class HotRuleService {

    // 单例 Service + 并发 upsert → 用 ConcurrentHashMap（生产要换有界缓存，见 C2）
    private final Map<String, KieBase> registry = new ConcurrentHashMap<>();

    public void upsert(String name, String drl) {
        KieHelper helper = new KieHelper();
        helper.addContent(drl, ResourceType.DRL);

        // 先 verify 再 build：拿得到行号级报错，能直接回显给配规则的人
        Results results = helper.verify();
        if (results.hasMessages(Message.Level.ERROR)) {
            String detail = results.getMessages(Message.Level.ERROR).stream()
                    .map(m -> "line " + m.getLine() + ": " + m.getText())
                    .collect(Collectors.joining("\n"));
            throw new IllegalArgumentException("DRL 编译失败:\n" + detail);
        }
        registry.put(name, helper.build());
    }

    public int execute(String name, Object... facts) {
        KieBase base = registry.get(name);
        if (base == null) throw new IllegalArgumentException("未注册的规则: " + name);

        KieSession session = base.newKieSession();
        try {
            for (Object f : facts) session.insert(f);
            return session.fireAllRules();
        } finally {
            session.dispose();       // 必须
        }
    }
}
```
</details>

**追问**：`registry.put` 换掉 KieBase 时，正在跑的请求会怎样？（答：**不受影响**。KieSession 持有的是它创建时那个 KieBase 的引用，老 session 用老 KieBase 跑完再 dispose——这正是热加载安全性的关键）
→ `KieHelper` 有什么问题？（答：在 `org.kie.internal.utils` 包下，**internal 表示稳定性弱于公共 API**；生产更稳的是 `KieFileSystem` + `KieBuilder` 全流程）

---

### C2. KieBase 缓存怎么做才不 OOM

**题**：多租户场景，每个租户每套规则一个 KieBase。用 `ConcurrentHashMap` 缓存有什么问题？怎么改？

<details><summary>参考答案</summary>

问题：**无界**。DRL 随租户 / schema / 活动 / 档位组合膨胀，`ConcurrentHashMap` 只会更快 OOM。

改法要点：

```java
private final Cache<String, KieBase> cache = Caffeine.newBuilder()
        // ① 不是 maximumSize —— 按"个数"计权会系统性误估
        .maximumWeight(maxWeightKb)
        .weigher((String key, KieBase base) -> footprintKb(base))
        .recordStats()
        .build();

/** 实测：KieBase 足迹 ≈ 260KB 基座 + 37KB × 规则数（堆 + Metaspace 合计） */
private int footprintKb(KieBase base) {
    int rules = base.getKiePackages().stream().mapToInt(p -> p.getRules().size()).sum();
    return 260 + 37 * rules;
}

// ② cache key 显式带租户，不跨租户共享 KieBase，也为 per-tenant 配额留口
String key = tenantId + "::" + drlText;
```

为什么按规则数计权：足迹由**生成的规则数**主导，不是活动数——「1 活动 × 200 档」(200 规则) ≈「10 活动 × 20 档」(200 规则)。按个数计权会把前者当 1 单位，比小 KieBase **低估约 20 倍**，噪声租户能悄悄吃爆堆。

另外：每个 KieBase 自带 ClassLoader，生成的规则类落 **Metaspace**（实测 ~12KB/规则），所以生产必须配 `-XX:MaxMetaspaceSize`，且要验证淘汰 churn 下 ClassLoader 能被回收。
</details>

**追问**：为什么不用 KieContainer 而是裸 KieBase？（答：KieContainer 是"部署单元"，绑 ReleaseId，适合 KJAR/KieScanner 路线；规则即数据这条路上我们只需要编译产物本身，KieBase 更轻）

---

### C3. Stateless + global 收结果

**题**：决策服务是无状态热路径，QPS 高。怎么写？

<details><summary>参考答案</summary>

```java
// StatelessKieSession 本身线程安全（stateful 的不是）；但一旦要 setGlobal，
// 就每次新建一个 —— 创建极便宜，贵的是 KieBase
StatelessKieSession session = kieBase.newStatelessKieSession();

ResultHolder holder = new ResultHolder();
session.setGlobal("result", holder);           // global 注入外部收集器

List<Object> facts = new ArrayList<>(candidates);
facts.add(context);
session.execute(facts);                        // 一次性执行，内部自动 dispose

return holder.getHits();
```

DRL 侧：

```java
global com.x.ResultHolder result;

rule "命中活动 A"
when
    ActivityRuleContext( numberAttr("orderAmount") >= 100 )
    $c: ActivityCandidate( activityId == "A" )
then
    result.hit($c, "满 100");
end
```
</details>

**追问**：`setGlobal` 在 Stateless 上是线程安全的吗？（答：**不是**——`setGlobal` 会污染共享 session。高并发下要么每次 `newStatelessKieSession()`（很便宜，KieBase 才贵），要么用 `session.execute(CommandFactory.newBatchExecution(...))` 把 global 绑进单次命令批。面试说得出这个坑加分）

---

### C4. 生产护栏三件套

**题**：怎么防止一条写错的规则把线上打挂？

<details><summary>参考答案</summary>

```java
// ① fire 次数硬熔断：失控规则最多跑 max 次就截断
int fired = session.fireAllRules(10_000);
if (fired >= 10_000) {
    log.error("[guard] 疑似失控规则，已截断. session={}", sessionId);
}

// ② 超时打断：另一个线程调 halt()，fireAllRules 会优雅返回（已执行的 RHS 不回滚）
ScheduledFuture<?> killer = timer.schedule(session::halt, 500, TimeUnit.MILLISECONDS);
try {
    int n = session.fireAllRules();
} finally {
    killer.cancel(false);
    session.dispose();
}

// ③ AgendaFilter 灰度：见 B8
session.fireAllRules(new ReleaseAgendaFilter(Set.of("GA")));
```
</details>

**追问**：`halt()` 之后已经执行的 RHS 副作用怎么办？（答：**不回滚**，Drools 没有事务语义。所以 RHS 里尽量只改 fact / 往 global 收集结果，**不要直接发消息、写库、扣款**——副作用统一放到 fire 结束后由 Java 侧根据结果执行）

---

### C5. 规则可观测

**题**：线上要能回答"哪条规则触发了几次、这次请求走了哪些规则"。

<details><summary>参考答案</summary>

```java
// 单请求放大镜：攒轨迹
List<String> trace = new ArrayList<>();
session.addEventListener(new DefaultAgendaEventListener() {
    @Override
    public void afterMatchFired(AfterMatchFiredEvent e) {
        trace.add(e.getMatch().getRule().getName());
    }
});

// working memory 变化（insert / update / delete）
session.addEventListener(new DefaultRuleRuntimeEventListener() {
    @Override
    public void objectInserted(ObjectInsertedEvent e) {
        trace.add("+fact " + e.getObject().getClass().getSimpleName());
    }
});

// 跨请求仪表盘：同一套 listener，出口换成 Micrometer
meterRegistry.counter("drools.rule.fired", "rule", ruleName).increment();
meterRegistry.timer("drools.fire.duration").record(cost, TimeUnit.NANOSECONDS);
```
</details>

**追问**：listener 会不会拖慢 fire？（答：会，尤其是每次 match 都拼字符串。生产上 trace 要做成**构建期开关**——大租户大规则集下，规则生成时就不 emit trace 语句，比"响应期再过滤"省掉每次 fire 的字符串累积和 GC）

---

## D. 找茬 / 推演题

### D1. 找出死循环

```java
rule "VIP 折扣"
when
    $c: Customer( vipLevel >= 2 )
    $o: Order( customer == $c )
then
    $o.setFinalAmount($o.getTotalAmount().multiply(new BigDecimal("0.8")));
    update($o);
end
```

<details><summary>参考答案</summary>

**死循环。** `update($o)` 让引擎重新评估所有依赖 `$o` 的规则；本规则 LHS 只看 `customer`（不可变），条件**依旧满足** → 反复触发，请求挂住。

三种修法，按推荐度排：

1. **去掉 `update`**（本例根本不需要——没有别的规则依赖 `finalAmount`）
2. 改成 `modify($o) { setFinalAmount(...) }` 并让 LHS 加终止条件，比如 `Order(customer == $c, discounted == false)`，RHS 里把 `discounted` 置 true
3. 加 `no-loop true`——**但只防自己激活自己**；如果是别的规则 `update($o)` 把我重新激活，`no-loop` 无效，要用 `lock-on-active true` + `agenda-group`

**陷阱**：面试官常追"加了 `no-loop` 就够了吧？"——不够，这是最容易答错的点。
</details>

---

### D2. 推演 agenda 执行顺序

给定：

```java
rule A  salience 10   agenda-group "g1"
rule B  salience 100  agenda-group "g2"
rule C  salience 50   agenda-group "g1"
rule D  (无 agenda-group，即 MAIN)  salience 1
```

Java 侧：

```java
s.getAgenda().getAgendaGroup("g2").setFocus();
s.getAgenda().getAgendaGroup("g1").setFocus();
s.fireAllRules();
```

假设 A/B/C/D 全部匹配成功，执行顺序是？

<details><summary>参考答案</summary>

**C → A → B → D**

推演：
1. **agenda-group 是焦点栈，LIFO**。先压 g2 再压 g1 → 栈顶是 **g1**，先跑 g1。
2. g1 内按 salience 降序：C(50) → A(10)。
3. g1 清空 → 弹栈 → 焦点回到 **g2** → B。
4. g2 清空 → 弹栈 → 回到 **MAIN** → D。

**结论**：salience 只在**同一个 agenda-group 内**比较。B 的 salience 100 全场最高，但因为在栈更深的组里，反而排到第三。
</details>

---

### D3. RETE 节点共享推演

给定六条规则的 LHS：

```
VIP1:     Customer(vipLevel==1)  + Order(customer==$c)
VIP2:     Customer(vipLevel==2)  + Order(customer==$c)
VIP3:     Customer(vipLevel==3)  + Order(customer==$c)
Loyal:    Customer(years>=3)     + Order(customer==$c)
Bulk:     Order(totalAmount>=500)
BigOrder: Order(totalAmount>2000)
```

问：① 有几个 Beta(join) 节点？② `Customer` 的类型判断算几次？③ insert 一个 `Customer(vipLevel=2, years=4)` 会发生什么？

<details><summary>参考答案</summary>

① **4 个** Beta 节点（VIP1/VIP2/VIP3/Loyal 各一个）。Bulk 和 BigOrder 只有单 fact 条件，Alpha 直连 Terminal，没有 join。

② **1 次**。`Customer` 的 ObjectTypeNode 是所有规则共享的——这就是 RETE "编译期提公因式"：同一个原子条件在 50 条规则里出现也只有一份节点。同理 `Order` 的类型判断也只算一次，6 条规则全部共用。

③ 传播过程：
```
Root → A1(type=Customer) ✓
     → A3(vL==1) ✗ / A4(vL==2) ✓ / A5(vL==3) ✗ / A6(years>=3) ✓
     → A4 命中 → β2.LeftMemory 加入 [Customer]，右侧（Order）为空 → 无 token
     → A6 命中 → β4.LeftMemory 加入 [Customer]，右侧为空 → 无 token
```
**没有任何 activation 产生**，只是在两个 Beta 节点的左记忆里留下了"匹配进度"。等 `Order` insert 进来喂到右输入、join 成功，才产生 activation 入 agenda。

**这就是 RETE 吃内存的原因**：β memory 存的是匹配进度，n 个 Customer × m 个 Order 最多攒 n×m 个 tuple。
</details>

---

### D4. 选型题（最常见的开放题）

**题**：我们有个营销系统，运营在后台配活动规则（满减、阶梯奖励、人群圈选），规则一天改几十次，要求秒级生效。用 Drools 还是 QLExpress？

<details><summary>参考答案（结构化答法）</summary>

先**拆需求**再给结论，别直接站队：

| 需求特征 | 指向 |
| --- | --- |
| 规则一天改几十次、秒级生效 | 偏 QLExpress（零编译）；Drools 需要热加载工程化 |
| 阶梯奖励 = 一堆区间规则，条数会膨胀 | 偏 Drools（增量匹配，规则多才划算） |
| 满减叠加 / 互斥 / 取最大 = **规则之间有关系** | **强烈偏 Drools**（Agenda 免费给你冲突消解） |
| 人群圈选跨多个事实（用户 + 订单 + 历史） | 偏 Drools（LHS 原生 join / accumulate） |
| 规则由运营配置（半可信） | 两者都要做 DRL/脚本模板化，不让人写自由文本 |

**结论**：整体偏 Drools，但必须把"动态性"这块工程化补上——**规则即数据存 DB + 运行时 `KieHelper` 编译 + 有界加权缓存 + 发布代际号轮询预热 + fail-safe 回退旧 Java 逻辑**。
**如果**规则始终只是"一条条独立的布尔判断、彼此不影响"，那 QLExpress 更划算，别为了用而用。

> 加分收尾："我实际做的就是前者，踩过的最大的坑是 KieBase 缓存按个数计权导致低估 20 倍，后来改成按规则数加权的 Caffeine 才稳住。"
</details>

---

## E. 白板速查卡

**QLExpress（3.x）**

```java
ExpressRunner runner = new ExpressRunner(isPrecise, isTrace);       // 单例
DefaultContext<String,Object> ctx = new DefaultContext<>();          // 每次新建
runner.execute(script, ctx, errList, isCache, isTrace);
runner.parseInstructionSet(script);                                  // 只编译，做校验
runner.getOutVarNames(script);                                       // 静态分析外部变量
runner.addFunctionOfServiceMethod(名, bean, 方法, 参数类型, null);
runner.addOperator("包含", new ContainsOperator());                  // extends Operator
runner.addOperatorWithAlias("如果", "if", null);
runner.addMacro("是成年人", "age >= 18");
runner.clearExpressCache();
QLExpressRunStrategy.setForbidInvokeSecurityRiskMethods(true);
```

**Drools 构建**

```java
KieServices ks = KieServices.get();
KieFileSystem kfs = ks.newKieFileSystem();                 // 静态资源路线
KieBuilder kb = ks.newKieBuilder(kfs).buildAll();
KieContainer c = ks.newKieContainer(kb.getKieModule().getReleaseId());

KieHelper h = new KieHelper();                             // 运行时 DRL 字符串路线
h.addContent(drl, ResourceType.DRL);
h.verify();  h.build();
```

**Drools 执行**

```java
KieSession s = kieBase.newKieSession();                    // 线程不安全，每请求新建
try { s.setGlobal("result", r); s.insert(fact); s.fireAllRules(max); }
finally { s.dispose(); }

StatelessKieSession ss = kieBase.newStatelessKieSession(); // 线程安全，可复用
ss.execute(facts);

s.getAgenda().getAgendaGroup("g").setFocus();              // LIFO，反着压
QueryResults qr = s.getQueryResults("queryName", args);    // 后向链，不进 agenda
SessionPseudoClock clock = s.getSessionClock();            // CEP 测试
```

**DRL 属性**

| 属性 | 作用 |
| --- | --- |
| `salience N` | 同组内优先级，大者先 |
| `agenda-group "g"` | 分组，配 `setFocus`（LIFO 焦点栈） |
| `auto-focus true` | 被激活时自动压栈 |
| `no-loop true` | 防自己 RHS 重激活自己（**不防跨规则**） |
| `lock-on-active true` | 本组持有焦点期间只触发一次（防跨规则） |
| `@key("value")` | 自定义元数据，`Rule.getMetaData()` 读，做灰度 |

**LHS 关键字**：`$x: Type(field == v)` 绑定 · `not` / `exists` / `forall` · `from` 锁数据源 · `accumulate(... , sum/count/collectList)` · `over window:time(5m)` · `eval`（慢，慎用）
**RHS 关键字**：`insert` · `insertLogical`（TMS 自动撤销）· `modify($f){ setX() }`（优于 `update`）· `delete`

---

## 练习建议

1. **B 组的 DRL 直接拷进 `drools-lab/src/main/resources/rules/<kbase>/`**，加个 controller 打一次请求——DRL 是 lazy compile，**第一次请求**才报语法错，光看启动日志不可靠。
2. **D1 的死循环真跑一次**，感受"请求挂住"是什么样，比背结论管用。
3. C 组的代码可以直接对照本仓库现成实现：`HotReloadService`（C1）、`ActivityRuleRuntimeService`（C2/C3）、`ReleaseAgendaFilter`（C4/B8）、`RuleAuditListener`（C5）。
