# P0-5 · 多租户内存容量模型（PERF-1）

> 评审 PERF-1 的落地物：**实测**堆/Metaspace 预算表 + `heap=f(tenants,activities,tiers)` 公式 + weigher 修复 + per-tenant 公平份额设计 + 异步/single-flight 接缝 + **诚实延后**。
> 杀掉评审「内存模型是占位符、无 sizing 数学；用『规则数』当 KieBase 权重会系统性误估」。测量脚本：`src/test/java/com/lrj/drools/activity/ActivityKieBaseSizingTest.java`（gated `-Dsizing=true`，可复跑）。

## 0. 两处内存命门（现状定位）

| 命门 | demo 现状 | 本轮处置 |
| --- | --- | --- |
| **KieBase 编译缓存**（`ActivityRuleRuntimeService`，Caffeine） | **已存在**。原 `maximumSize(500)` **按 KieBase 个数**计权 | ✅ 本轮改**足迹加权** + 实测预算表 + 公式（见下） |
| **`DecisionSnapshotRegistry`**（内存只读索引 `selector→handle`，设计 §5.3） | **demo 尚未实现**（现走 DB 查询 `ActivityQueryService`，无常驻内存索引） | ⏳ 组件不存在，无法实测其单项保留堆；sizing 随该组件一并做（**不臆造半成品**，见 §7） |

本轮聚焦**已存在**的 KieBase 缓存——这是当前唯一能实测足迹的内存热点。

## 1. 实测堆预算表（GC-delta 采样，SAMPLES=40，2026-07-19）

`ActivityKieBaseSizingTest`：编译 40 个**不同** KieBase（各自独立 classloader），GC 前后测 `used heap` 与 `Metaspace` 差，除以样本数 = 单 KieBase 保留足迹。

| 形状（DRL 由真 `ActivityDrlBuilder` 生成） | 堆/个 (KB) | Metaspace/个 (KB) | **生成规则数** |
| --- | ---: | ---: | ---: |
| eligibility  1 活动 | 247.6 | 105.7 | 1 |
| eligibility 50 活动 | 1437.6 | 705.9 | 50 |
| ladder  1 活动 ×  10 档 | 436.8 | 146.5 | 10 |
| ladder  1 活动 ×  50 档 | 1484.7 | 653.2 | 50 |
| **ladder  1 活动 × 200 档** | **5374.1** | **2421.0** | **200** |
| **ladder 50 活动 ×   1 档** | **1358.5** | **574.5** | **50** |
| ladder 10 活动 ×  20 档 | 5209.7 | 2423.7 | 200 |

> 绝对值随机器/JVM（G1、`-Xmx1500m`）波动；**稳健结论是各形状间的关系**，非具体数字。生产须在目标机型/JVM 重跑标定系数。

### 命门实证（评审 PERF-1 铁证）

1. **足迹随「生成规则数」走，不随「活动数」走**：
   - 「1 活动 × 200 档」(200 规则，5374 KB) ≈「10 活动 × 20 档」(200 规则，5210 KB)——**相同规则数下，活动怎么切分几乎不影响足迹**。
   - 「1 活动 × 200 档」(5374 KB) vs「50 活动 × 1 档」(1358 KB)——**规则数 4× → 足迹 ~4×**，与活动数（1 vs 50）反向。
2. **按活动数计权会系统性误估**：「1 活动 × 200 档」若按活动数当权重 = `1`，与「1 活动 × 1 档」同重，实际低估 ~20×。噪声邻居（大规则集租户）能悄悄吃爆堆——**这正是原 `maximumSize` 的 bug**。
3. **ladder 每档一整条 rule**：`buildLadderDrl` 每个档位生成一条 `rule "ladder_i"`（含 `!=null / >=min / <max` 3 个 alpha 约束的范围模式）→ **生成规则数 = 总档位数**，运营配置里的「活动数」是误导量。

## 2. 拟合系数与公式

线性拟合（`足迹 ≈ BASE + PER_RULE × 生成规则数`）：

| 池 | BASE (KB) | PER_RULE (KB/规则) |
| --- | ---: | ---: |
| 堆 | ~200 | ~25 |
| Metaspace | ~60 | ~12 |
| **合并（堆+Metaspace）** | **~260** | **~37** |

> 落进代码常量：`ActivityRuleRuntimeService.FOOTPRINT_BASE_KB=260`、`FOOTPRINT_PER_RULE_KB=37`。weigher 用**合并值** → 单个 `maximumWeight` 预算同时封顶堆与 Metaspace 两个池。

### `heap = f(tenants, activities, tiers)`

单个 warm (tenant, bizLine) 的常驻 KieBase 足迹（各场景各一份 KieBase）：

```
F(r)                 ≈ 260 + 37·r           (KB，r = 该 KieBase 的生成规则数)
r_eligibility        ≈ A_c                  (有条件的活动数)
r_ladder             ≈ A_l · T̄             (阶梯活动数 × 平均档位)   ← 主导项
r_discount           ≈ 常数(~3)             (折扣合并自连接规则)
r_gift               ≈ 1

per_tenant_footprint ≈ F(A_c) + F(A_l·T̄) + F(3) + F(1)   (KB)
resident_total       ≈ Σ_over_warm_tenants per_tenant_footprint  ≤  cache-max-weight-kb
max_resident_tenants ≈ cache-max-weight-kb / per_tenant_footprint
```

**样例**（A_c=20，A_l=10，T̄=10 → r_ladder=100）：

```
per_tenant ≈ (260+740) + (260+3700) + (260+111) + (297) ≈ 5.4 MB / warm tenant
预算 256MB → max_resident ≈ 47 warm (tenant,bizLine)
```

超过 max_resident → LRU 淘汰冷租户 → 首请求冷编译尖刺（P99 尾）→ §5 的接缝接管。**主导项是 `A_l·T̄`（档位）**：档位翻倍比活动翻倍贵得多——运营侧限档位数比限活动数更该收紧。

## 3. weigher 修复（本轮落地）

`ActivityRuleRuntimeService`：

- **改**：`maximumSize(500)`（按个数）→ `maximumWeight(cacheMaxWeightKb)` + `weigher = footprintKb(key)`。
- **weigher**：`权重(KB) = 260 + 37 × countRules(DRL)`，`countRules` 数 DRL 里 `rule "…"` 出现次数（足迹主导项）。
- **配置化**：`activity.marketing.rule-engine.cache-max-weight-kb`（默认 `262144`=256MB）。
- **可观测**：`cacheWeightKb()` 返回当前加权总占用（`Caffeine.policy().eviction().weightedSize()`）。
- **回归锁**：`ActivityCacheWeigherTest`（常驻套件）断言「同规则数、不同活动切分 → 权重相等」「200 档权重 >15× 单档」「足迹随档位单调增」——防退回按个数计权。

## 4. Metaspace 入模型

每个 KieBase 编译 = 一个**独立 classloader** + 一批生成的 rule 类 → 落 **Metaspace**（实测 ~60KB + 12KB×规则）。堆与 Metaspace 约 **25:12 ≈ 2:1** 同步增长，故合并计权后，`maximumWeight` 预算里约 **1/3 是 Metaspace 常驻**。

**生产必配**：`-XX:MaxMetaspaceSize ≥ 共享 Drools 基础设施(~80MB，一次性) + 预算的 Metaspace 份额`。例：预算 256MB 合并 → Metaspace 份额 ~85MB → `MaxMetaspaceSize` 建议 ≥ 256MB（含裕量）。**不设 `MaxMetaspaceSize` = Metaspace 无上界**，淘汰不及时 → 原生内存涨爆（比堆 OOM 更难诊断）。

## 5. 噪声邻居封顶 + 异步/single-flight 接缝

| 关注点 | 现状 | 处置 |
| --- | --- | --- |
| 噪声邻居吃爆堆 | 原按个数计权，大规则集租户可悄悄占满 | ✅ 足迹加权后，一个大 KieBase 吃掉的预算 = 它的真实足迹，淘汰对它更"重手"；配合 per-tenant fire budget（复用 Step 14 `fireAllRules(max)`+watchdog `halt()`）封顶执行 |
| 缓存击穿（同 key 并发冷编译） | `cache.get(key, k->compile)` | ✅ Caffeine `get(key, mappingFunction)` **天然 single-flight**：同 key 并发只编译一次，其余等结果（无需自建锁） |
| **双冷 miss 不阻塞热路径** | 冷 miss 在**调用线程**同步编译（100ms~秒级） | ⏳ **接缝已留、机制延后**：`compileOrGet` 是唯一编译入口，未来接「独立限速编译线程池 + 异步重建期 serve last-known / 显式降级」只改此处。当前 demo 单机同步编译，规则/租户规模小，冷编译尖刺可接受 |
| per-tenant 公平份额 | cache key 已含 tenant（天然分片） | ⏳ **设计见 §6，机制延后**（可抢占淘汰需自定义 Caffeine `Policy` 干预，超 demo 范围） |

## 6. per-tenant 公平份额（设计，机制延后）

目标：一个大租户不能把别人的 KieBase 全淘汰。设计（评审 §11:289③）：

- **公平份额** `per-tenant-max-weight` = `总预算 / 常驻租户软上限`，单租户常驻足迹超份额 → 优先淘汰**自己**的冷 KieBase，不动别人。
- **借用空闲配额**：空闲预算允许大租户临时超份额；一旦别的租户来抢，**可抢占**回收借出的份额（借用项标记为可抢占，优先淘汰）。
- **热租户 pin**：发布/pointer 激活时预热并 pin，不被 LRU 淘汰。
- **常驻租户硬上限**：超限拒绝新租户激活并告警（**背压而非 OOM**）。

> Caffeine 单缓存不原生支持 per-key 配额；实现需 per-tenant 子缓存或自定义淘汰 `Policy`。属生产件，本轮只固化设计 + 留 tenant 维度 key。

## 7. 验收对照 + 诚实延后

| 评审验收项 | 状态 |
| --- | --- |
| 堆预算表存在 | ✅ 本文 §1（实测）+ §2 公式 |
| 单 KieBase 保留堆实测（JOL/heap dump 抽样） | ✅ GC-delta 采样（`ActivityKieBaseSizingTest`，可复跑）；JOL/heap-dump 精测可后续升级 |
| `per-tenant-max-weight` = 公平份额 + 借用/抢占定义 | 🟡 设计固化（§6），机制延后 |
| Metaspace 入模型 | ✅ §4 |
| 独立限速编译线程池 + 双冷 miss 不阻塞热路径 | 🟡 接缝已留（§5），线程池机制延后 |
| **目标租户数负载测试在堆预算 + P99 内** | ⏳ **延后**（需目标租户数 + 压测环境；生产件） |
| **淘汰 churn 下 heap-dump 验 classloader/Metaspace 回收** | ⏳ **延后**（需 heap-dump 工具链 + churn 压测；生产件） |
| 单 snapshot 项保留堆 | ⏳ **延后**（`DecisionSnapshotRegistry` demo 未实现，§0） |

**本轮做到**：实测足迹表 + 公式 + Metaspace 模型 + weigher 从「按个数」修成「按实测足迹」（修掉 PERF-1 的系统性误估 bug）+ 配置化预算 + 回归锁 + single-flight 确认 + 公平份额/异步池设计与接缝。
**显式延后（生产大件）**：目标租户数负载测试、churn heap-dump 回收验证、独立异步编译线程池、per-tenant 可抢占淘汰实现、`DecisionSnapshotRegistry` 及其 sizing。

## 8. 复跑

```bash
# 实测足迹表（gated，不进常规 ./mvnw test）
./mvnw test -Dtest=ActivityKieBaseSizingTest -Dsizing=true \
  -DargLine="-Xmx1500m -XX:+UseG1GC -XX:MaxMetaspaceSize=512m"
# weigher 回归锁（常驻套件）
./mvnw test -Dtest=ActivityCacheWeigherTest
```
