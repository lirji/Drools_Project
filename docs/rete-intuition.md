# RETE 算法直觉

> 活文档。最后核对：2026-08-28；示例仍对应当前 `order-discount.drl` 与 Drools 8.44.2.Final。
>
> 用本仓库 `rules/discount/order-discount.drl` 的折扣规则做例子讲，这样脑子里有具象。

## 1. 朴素做法的痛点

如果不用规则引擎，每次 `fireAllRules()` 都从头匹配，伪代码：

```java
for (Rule r : allRules)
  for (FactTuple t : permutations(workingMemory, r.patternCount))
    if (r.lhsMatches(t)) activate(r, t);
```

复杂度 `O(R × F^P)`，R = 规则数，F = fact 数，P = 一条规则的 pattern 数。

更要命的是：fact 集合在每次 `insert` / `update` / `retract` 后会变，但**99% 的 fact 跟变更无关**，每次都全量重算这些是纯浪费。

## 2. RETE 的两个核心想法

**想法 A：把所有规则的 LHS 编译成一张共享 DAG**

不是规则一条一条评估，而是把所有 LHS 条件**拆成原子**，相同的原子复用同一个节点。

**想法 B：每个节点缓存"目前匹配到这里的 fact / fact 组合"**

新 fact 进来时，**只在受影响的路径**上做增量传播；不变的部分一个 CPU 周期都不花。

这两件事合起来，把"每次全量算"变成"只算 delta"。代价是内存——RETE 出名的标签就是 *memory hog*。

## 3. 节点类型（关键就这三种）

| 节点 | 干啥 | 类比 SQL |
|------|------|----------|
| **Alpha** | 单 fact 的单字段判断（type 过滤 + 字段过滤） | 单表 WHERE |
| **Beta (Join)** | 多 fact 的关联匹配（`customer == $c`） | JOIN |
| **Terminal** | 某条规则全部 pattern 都满足 → 产生 activation 入 agenda | SELECT 出结果行 |

Beta 节点很特别：它有**两个输入**（left / right）和**两块 memory**（LeftMemory / RightMemory）缓存各自侧目前通过的 fact (tuple)。

## 4. 拿折扣规则跑一遍

`order-discount.drl` 里的规则 LHS 速览：

```
VIP1:    Customer(vipLevel==1)  +  Order(customer==$c)
VIP2:    Customer(vipLevel==2)  +  Order(customer==$c)
VIP3:    Customer(vipLevel==3)  +  Order(customer==$c)
Loyal:   Customer(years>=3)     +  Order(customer==$c)
Bulk:    Order(totalAmount>=500)
BigOrder:Order(totalAmount>2000)
```

编译出来的网络（简化）：

```
                      ┌──────────────┐
   insert ──────────► │  Root (any)  │
                      └──┬───────────┘
                         │
              ┌──────────┴──────────┐
              ▼                     ▼
        [A1: Customer]         [A2: Order]
              │                     │
     ┌────┬───┼───┬────┐     ┌──────┼──────┐
     ▼    ▼   ▼   ▼    ▼     ▼      ▼      ▼
   [A3] [A4][A5][A6]  ...  [A7]   [A8]    (raw Order for joins)
   vL=1 vL=2 vL=3 y>=3      tA>=500 tA>2000
     │   │   │   │          │      │
     │   │   │   │          ▼      ▼
     │   │   │   │       (Term:  (Term:
     │   │   │   │        Bulk)  BigOrder)
     ▼   ▼   ▼   ▼
   [β1][β2][β3][β4]   ← 左输入是 Alpha 过滤后的 Customer，
     │   │   │   │       右输入是 Order，按 customer==$c JOIN
     ▼   ▼   ▼   ▼
   Term Term Term Term
   VIP1 VIP2 VIP3 Loyal
```

**已经共享的部分**：

- `Customer` 类型判断（A1）只算一次，4 个 VIP/Loyal 规则共用
- `Order` 类型判断（A2）只算一次，6 个规则全部共用
- Bulk 和 BigOrder 都从 A2 出发再各自加阈值过滤

## 5. 增量传播：把一次请求过一遍

跑 Alice VIP2 + 660 元单这个 case：

**`insert(Customer(Alice, vipLevel=2, years=4))`**

1. Root → A1（type=Customer）✓
2. A1 → A3(vL=1) ✗ / A4(vL=2) ✓ / A5(vL=3) ✗ / A6(years>=3) ✓
3. A4 → β2 的**左输入**：β2.LeftMemory 加入 [Alice]，但 β2.RightMemory 还是空 → 没产生 token
4. A6 → β4 的左输入：β4.LeftMemory 加入 [Alice]，同样还在等右侧

**`insert(Order(customer=Alice, totalAmount=660))`**

1. Root → A2 ✓
2. A2 → A7(tA>=500) ✓ / A8(tA>2000) ✗
3. A7 → 直接连 Terminal(Bulk)：**产生 activation** {Bulk + [Order#660]}，入 agenda（salience 50）
4. A2 还要喂 4 个 β 的右输入：
   - β1.RightMemory 加入 [Order]；左侧空 → 无 token
   - β2.RightMemory 加入 [Order]；左侧有 [Alice]，做 join：`Order.customer == Alice` ✓ → **产生 activation** {VIP2 + [Alice, Order]}（salience 100）
   - β3.RightMemory 加入 [Order]；左侧空 → 无
   - β4.RightMemory 加入 [Order]；左侧有 [Alice]，join ✓ → **产生 activation** {Loyal + [Alice, Order]}（salience 10）

**`fireAllRules()`**

agenda 现在有 3 个 activation，按 salience 排：

```
VIP2 (100)  ─►  Bulk (50)  ─►  Loyal (10)
```

逐个执行，每个 `then` 块改 `finalAmount`，但因为没调 `update($o)`，引擎不知道 Order 变了，不重新评估。agenda 清空 → 完。

## 6. 关键直觉总结

**(a) 共享 = 编译期"提公因式"**

所有 LHS 里相同的原子条件只算一次。`Customer(vipLevel==1)` 在 50 条规则里出现，依然只一份 Alpha 节点。

**(b) 状态化 = 把"匹配进度"存下来**

β 节点的 Left/Right memory 是关键。新 fact 来时不重算所有 join，只跟另一侧 memory 里的 fact 做笛卡尔过滤。这就是为啥 RETE 吃内存。

**(c) Push-based ≠ DB 的 pull**

DB 是 query 主动去拉数据；RETE 反过来，fact 主动从 root 往下"渗"，能渗到 terminal 节点就触发规则。所以叫**前向链推理**（forward chaining）。

**(d) Drools 实际用的是 Phreak，不是教科书 RETE**

Phreak 在经典 RETE 上加了 lazy / segmented 传播：fact insert 不立刻传到 terminal，按 segment 攒一批在 `fireAllRules()` 调用时算。直觉一样（共享 + 增量），实现上更省 CPU。所以 Drools 启动慢（编译网络）、insert 快、fireAllRules 偶尔慢一下（在补传播）。

## 7. 这套直觉对你写规则的指导

1. **不要 LHS 里写 Java 函数调用 / 重计算**：`Order( expensiveMethod() == X )` 这种条件 RETE 没法 hash 索引，每个 fact 进来都要重算。能预算好的字段在 Java 侧算完再 `insert`
2. **条件越"分得开"，共享越多**：`Customer(vipLevel >= 1 && yearsSinceRegistration >= 3)` 合在一起写比拆成两个 pattern 难复用，因为后续别的规则只想匹配 `vipLevel` 这部分就用不上这个 alpha 节点
3. **fact 数爆炸 → β memory 爆炸**：n 个 Customer × m 个 Order 在 β 节点最多攒 n×m tuples；如果你往 working memory 塞了 10k Customer + 10k Order 还写 `customer == $c` 的 join，内存会要命。生产场景要么减少 fact 数（每次只 insert 相关的），要么换成 `StatelessKieSession`（不缓存 working memory）
4. **避免 LHS 里关心被自己改的字段**：在 Step 2 已经踩过——会循环。引擎"什么时候停下来"取决于"传播下去后还有没有 activation 产生"
5. **debug 顺序**：先看 alpha（fact 类型 + 简单字段过滤过不过），再看 beta（join 条件对不对），最后才是 `then` 块。开 `org.drools` 的 DEBUG 日志能看到节点激活的痕迹

## 8. 跟实际代码的呼应

| 现象 | RETE 解释 |
| --- | --- |
| `KieContainer` Bean 注成 singleton，启动期 0.5–1s | 编译 RETE 网络，Alpha/Beta/Terminal 节点全部建好 |
| `KieSession.insert(fact)` 极快 | 走 Alpha 网络 hash 查表，只更新少数受影响节点的 memory |
| `fireAllRules()` 偶尔慢一下 | Phreak 在这一步集中算 segment 传播 |
| 改一条规则要重启应用 | RETE 网络是编译产物；要热加载得用 `KieScanner` / 动态加载 DRL 字符串重建网络 |
| 内存随 fact 数线性涨，复杂 join 时超线性 | β memory 是 n × m 量级的 token 集合 |

## 9. 想再深入推荐看的

- 原始论文：Charles Forgy, _"Rete: A Fast Algorithm for the Many Pattern/Many Object Pattern Match Problem"_（1982），22 页，是规则引擎之父
- Drools 官方对 Phreak 的描述：搜 "drools phreak algorithm overview"
- 把 `org.drools` log level 调到 DEBUG，跑一次 `/discount/calculate`，亲眼看 alpha/beta 激活的顺序
