# 返工批 Review（2026-08-10 晚）

## 为什么有这份报告

首轮交付的 `REVIEW_REPORT.md` 与 `QA_REPORT.md` 写于 **14:42–14:44**。之后 **14:58–15:10** 又改了一批代码：

- 删除已退役的 DRL 求值面：`evalEligibility` / `evalDiscount` / `evalLadder` / `buildDiscountDrl`（"D1 追认"）
- `ActivityQueryService` 重写（−182/+79）：`safeFallback` 取代 `legacyMax`，回退保留真实合并策略
- 写权限矩阵补 `bulk-status` 与 `*/claim`（原先 `/activity-marketing/*/status` 这个单星号模式匹配不到两段路径的 `bulk-status`）
- 新增 CI `validation-e2e` job
- `DecisionPlaneController` 决策热路径 `explain=false`
- 前端 ValidateView / EditorView / DetailView / PlaybooksView 与 `e2e-validation.mjs`

**首轮的 review 与 E2E 结论对这批代码不成立**，故补跑一轮：6 个维度并行 review，每条发现由 2 个不同视角的怀疑者对抗验证（22 个 agent）。结论 **4 条成立 / 4 条被反驳**。

## 复验证据（返工后，对当前工作树）

| 项 | 结果 |
| --- | --- |
| `./mvnw test` | **371 tests / 3 skipped / 0 failures / 0 errors / BUILD SUCCESS**（common 150 含 3 skip + console 204 + decision 17） |
| `npm test`（前端） | **25 文件 / 274 用例通过**（含本轮新增的 4 条阶梯往返用例） |
| `vue-tsc --noEmit` / `npm run build` | 均通过 |
| Docker header 档整栈重建 + `e2e:validate` | **pass=472 / fail=0** |

> 用例数只认 `./mvnw test` 的 `Tests run:` 汇总。求和 surefire XML 会**少数 50 个**，原因见 `CLAUDE.md` 坑 14。文档里历史上出现的 307 / 357 / 310 都是这个陷阱的产物。

## 两条实质发现（H-1 已修 · L-1 未动）

### H-1 · 阶梯未落档的候选留成「0 元合格候选」，PRIORITY/MUTEX 下挤掉真优惠 — **已修**

- 位置：`activity-common/.../engine/BenefitEvaluator.java`（`applyLadder` 与 `computeAmounts:121`）
- 性质：**既有缺陷，非本批引入**。上一提交 `0ebc9bb`「算不出金额必须真的淘汰候选」把 fail-closed 落到了随机 / 第 N 件折 / 一口价 / 折扣四个分支，**漏了阶梯这一支**。
- 机制：`applyLadder` 在 `tierOf` 返回 null（订单金额没落进任何档）时只 `continue`，不 reject；`computeAmounts:121` 的 `if (c.getRedPackageAmount() == null) continue;` 也是裸 `continue`（纯阶梯活动的 `redPackageAmount` 本就为 null，且写平面合法允许）。候选于是带着 `eligible=true / computedAmount=0 / rejectReason=null` 进入 merge。
- 后果：`pickByPriority` 只比 priority、平级才比金额 → PRIORITY / MUTEX 业务线上，0 元幽灵候选凭 priority 击败真能减 10 元的活动，响应是 `hit=true / amount=0`，日志干净、监控连 fallback 都不计。默认策略 MAX 下不丢钱，但单候选时会出现「命中 X，减 0 元」的假命中（新上线的 `/console/validate` 会把它显示出来）。
- 可达性：写平面 `validateCommon` 只要求 `hasFixed || hasLadder`，档位不要求从 0 起覆盖——仓库自带 playbook `ladder` 首档就是 `min=300`。`e2e-validation.mjs:198` 的 ladder fixture **额外注入了 `orderAmount>=300` 的资格条件**来回避这个语义（脚本注释已写明），所以 E2E 绿不代表引擎正确。

- 修复：见文末「H-1 修复记录」。改法不是直觉的那个——三种看起来更简单的写法都会踩到既有语义，
  对抗验证在实施前就把它们逐一挡掉了，那张对照表是本次 review 最有价值的产出。

### L-1 · 平台对任何形态都表达不了「优惠只作用于活动绑定的商品」 — **未动**

- 原始发现是「第 N 件折跨 SPU 超发」，**对抗验证后重新定性**：这不是第 N 件折的 bug，而是**跨形态的产品建模缺口**。
- `ActivityCandidate` 全表无 SPU 字段——SPU 绑定在架构上只是**触发器**（选候选），从来不是**作用范围**。同一辆混绑购物车下，`FIXED_PRICE` 的超发比第 N 件折大一个量级（2200 元车配「9.9 秒杀」→ 减 2190.10），`RATIO_ZHE` / 阶梯同样吃整单。
- 结论：一次请求 = 一个订单上下文，圈定范围是调用方的责任，这是一致（若不理想）的契约。**只修第 N 件折反而会让六形态各按一套口径发钱。** 要做需给 `ActivityCandidate` 带绑定 SPU 集合并统一六形态作用域——产品范围决策，不是本次提交的阻断项。

## 文档与前端侧的修正（均已完成）

| 编号 | 问题 | 处理 |
| --- | --- | --- |
| D-1 | 交付文档用返工前证据宣称「已验证 / 门已关」，接手人会据此直接合并未验证过的代码 | `DELIVERY_REPORT` / `DELIVERY_STATUS` / `QA_REPORT` 全部标注首轮证据的时间边界，并补上返工后的复验数字 |
| D-2 | `QA_PROFILE.md` 同一文件里给出 307 与 357 两个互相矛盾的基线，且都不对 | 统一为 371，并写明「求和 surefire XML 会少数 50 个」的陷阱 |
| D-3 | `CLAUDE.md` 要求「同步维护 DRL 里的 `discount-compute-ratio`」，但该 DRL 已随 `buildDiscountDrl` 删除；照做会复活刚被删掉的第二权威 | 改为只指向 `BenefitMath`，并加历史注记明确「别再去找它」 |
| D-4 | `CLAUDE.md` 称 `DroolsBenefitGoldenSetTest` 守着「旧开关配 false 也不换求值器」——该类**自身跑 0 个用例** | 改为指向真正在守的 `ActivityQuerySafetyFallbackTest#legacyFalseFlagsCannotSwitchProductionBackToDrools`，并把成因记进坑 14 |
| F-1 | `logic.ts` 的「阶梯 + 底价」往返丢 `redPackageAmount` | 见下 |

### F-1 的准确定性

对抗验证澄清了两点，**它不是本批引入的回归**：

- HEAD 版 `EditorView.vue:331/389` 行为逐字节相同（同样不回填 amount、同样对 ladder 硬编码 null），本批只是把这条链忠实搬进 `logic.ts`。
- 该前置配置 `{unit:'元', amount:7, range:[TIERS]}` **SPA 自身产不出来**（六个形态 chip 互斥），只有绕过 SPA 直接调写平面（写平面 `hasFixed || hasLadder` 放行）才存得进去。全仓库唯一构造它的地方是后端金标 fixture `DecisionGoldenSetTest.ladderSkippedWhenOrderAmountMissing`。

仍然修了，因为「编辑器零改动往返不得改变已存配置」是无歧义的正确性不变量，且修复对 SPA 能创建的一切零行为变化（新建纯阶梯时 `numOrNull('') === null`，已加用例钉死）。REST 集成方能造出该配置，所以不是纯理论问题。

改动：`benefitDraftFromRule` / `benefitRequestFields` 的 ladder 分支一并带上 `redPackageAmount`；`logic.roundtrip.test.ts` 新增「阶梯带底价」正逆映射 + 2 条专项回归。

## 被反驳（记录在案，避免重复上报）

| 发现 | 反驳要点 |
| --- | --- |
| 第 N 件折跨 SPU 超发 | 见 L-1：跨形态建模缺口，非该形态的 bug；只修它会让六形态口径不一 |
| `logic.ts` 阶梯往返丢字段 | 见 F-1：与 HEAD 逐字节等价，非本批回归；前置配置 SPA 产不出 |
| ValidateView「一口价试算」面板按场景而非真实赢家展示 | 三格数字对任何赢家形态都正确（`payable = orderAmount − hitAmount` 恒等），真实赢家以更醒目的形式显示在该卡正上方，且 `v-scenario-note` 已明写「场景只准备输入、不保证命中」。仅卡片标题措辞按场景框定，低危 |
| CLAUDE.md 关于旧 DRL 的表述自相矛盾 | 其中一处指控（「只保留买赠运行时与隔离对照资产」）本身是准确的；仍按 D-3 修正了确实过期的那处 |

## H-1 修复记录（2026-08-10 晚）

### 改法

给 `ActivityCandidate` 加一个 `ladderApplied` 标记，由 `applyLadder` 在**落档成功时**置位；
`computeAmounts` 里那句裸 `continue` 改成：没有固定金额、又没落过档 → `notApplicable("阶梯未落档且无固定金额")`。
`ActivityQueryService.safeFallback` 清算额状态时一并把该标记清掉（否则回退重算会用上一轮的陈旧标记）。

为什么必须是新加一个标记，而不是三种更直觉的写法：

| 直觉写法 | 为什么不行 |
| --- | --- |
| 在 `computeAmounts` 那行无条件 `notApplicable` | **会打死所有正常阶梯活动**——`applyLadder` 只设 `computedAmount`、不设 `amountComputed`，所以落档成功的纯阶梯候选走的也是同一行 `continue` |
| 用 `computedAmount == 0` 判别 | 首档 `reward=0` 是运营配得出来的**合法 0 元优惠**，会被误杀（`NotApplicableCandidateTest#legitimateZeroSurvives` 正是钉这条） |
| 让 `applyLadder` 设 `amountComputed=true` | `computeAmounts` 会因此跳过该候选，**破坏两条既有覆盖语义**：「固定金额覆盖阶梯」（金标 `ladderSkippedWhenOrderAmountMissing` 期望 7）与「折扣覆盖阶梯」（金标 `ratioOverridesLadder` 期望 20） |

顺带修好的同类场景：缺 `orderAmount` 且无底价的阶梯活动、以及**规则行缺失**（权益字段全空）的候选——
它们过去也从这条裸 `continue` 溜进合并集，现在一并按「算不出金额」淘汰。

### 守卫

- `NotApplicableCandidateTest` 新增 7 例（未落档淘汰 / PRIORITY 与 MUTEX 下不许挤掉真优惠 /
  落档发 0 元仍命中 / 带底价仍发底价 / 缺订单金额且无底价 / 规则行缺失）。
  **撤回主代码改动后其中 5 例转红**，另 2 例是防误修的护栏，前后都应绿。
- `DecisionGoldenSetTest$Ladder` 新增 2 例端到端（发布门禁）。原 `TIERS` 覆盖 `[0,∞)`，
  照不出「没落档」，故新增首档从 300 起的 `GAPPED_TIERS`。

### 未随此修复解决的

`applyLadder` 的负奖励分支现在也会走到淘汰（无底价时），但 `NotApplicableCandidateTest#negativeLadderTierIsNotApplied`
仍只断言 `computedAmount == 0`、不断言 `eligible == false`——它只调 `applyLadder` 不调 `computeAmounts`，
属于覆盖偏弱而非行为错误。L-1（SPU 作用域建模缺口）仍未动，性质见上文。

> ⚠️ 验证时踩到的坑：`./mvnw -pl activity-console test` **会用 `~/.m2` 里的旧 `activity-common` jar**，
> 改在 common 的修复根本没进去，表现为「单测绿、集成红」。先 `./mvnw -pl activity-common install -DskipTests`
> 或加 `-am`。这条已在 `CLAUDE.md` 常用命令段写明。

## 提交前待办

1. ~~决定 H-1 是否本批修~~ → 已修，见上。
2. 本机编排停在 **header 档**；回默认 auth 档：`./deploy.sh --skip-build --core-only`。
