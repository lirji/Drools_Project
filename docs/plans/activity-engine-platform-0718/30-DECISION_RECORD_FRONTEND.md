# 前端控制台 · 决策记录（DECISION RECORD）

> 由 `/frontend-plan` 工作流产出。6 个只读子代理（需求与用户流 / UI·UX 交互状态 / 前端架构 / 可复用组件 / 移动端适配 / 测试风险）并行调查后综合。冲突处已裁决，见各决策"裁决"段。本轮只出方案，获批前不改代码。

输入依据：`00-product-blueprint.md`、`20-frontend-console-design-direction.md`、`../activity-engine-backend-0718-2111/FINAL_PLAN.md`（决策/控制面 API 契约），以及现有前端 `activity.js`(650) / `app.js`(615) / `styles.css`(326) / `activity.css`(107) / `index.html`(40) / `examples.js`(413) 的逐行核对。

---

## 决策 1 · 前端运行时架构

| 备选 | 核心 | 优点 | 代价 | 加权 |
|---|---|---|---|---|
| A 纯原生增强 | 自建微运行时(store + keyed reconcile + hash router + ESM 分片) | 零依赖、守约束、复用 DemoUI/token 全资产、透明 | 需写 ~300-400 行"类框架"基础设施，纪律松则有"手搓烂框架"风险 | 高 |
| B Preact+htm(自托管免构建) | 引入 ~6KB ESM 微框架 | 声明式 + 自动 keyed diff 白嫖、丢焦点 bug 直接消失、boolean prop 顺手修 `el()` 陷阱 | **仍是框架**，顶撞 `FRONTEND_PLAN.md:22`「不引框架」与"学习脚手架非生产代码"定位；与命令式 `app.js` 两套范式共存、无法复用 DemoUI(返回 DOM 非 VNode)需重写 8 helper | 中 |
| **C 原生纪律 + 图表窄口径例外**(A + 唯图表可局部破例) | A 的全部 + 保留"手绘 SVG 或自托管一个免构建微图表文件"的延后权利 | A 的全部优点 + 把最费手工的图表隔离为显式、可延后、窄范围的单点决策 | 同 A（图表例外不扩散） | **最高** |

**裁决：采纳 C。对「不引框架」的立场是坚持、不破例**（图表例外延后、可选、窄口径）。

理由（三个 Agent 独立收敛）：
1. **守约束是可辩护的默认**：`FRONTEND_PLAN.md:22` 与 `CLAUDE.md` 明文反对框架/构建/CDN，仓库自定位"学习脚手架、非生产代码、没需求不提前加"。引 Preact 是一次**政策变更**，不应作为默认。
2. **对本仓库 A 的概念负担反而更低**：~350 行有界基础设施 vs 引入第二套范式与命令式 `app.js` 别扭共存且无法复用 DemoUI——后者长期认知/维护成本更高。
3. **保住最大资产**：DemoUI 语汇(A 原样复用) + `g.external`+`mount(panel)` 隔离/回滚模式(删文件即回退)。控制台做成一组**新原生 ESM 模块**，经同一接缝挂 `#panel`，**Step 1-18 的 `selectDemo/renderSummary/SUMMARY` 逐字节不动**。
4. **ESM + 动态 `import()` 是让原生扩到 5 页的钥匙**：无构建拿到真正的按页代码分割，重页面(尤其图表)懒加载。这是唯一强主张的结构升级——**只升级控制台，不动 `app.js`**。

**逃生口（写清楚，非默认）**：若范围扩到全站重交互/实时流式更新，或团队正式放弃"学习脚手架"定位——那时 **Preact+htm（自托管、免构建）是正确逃生口**，但那是自觉的政策改变。触发条件：监控看板要 WebSocket/SSE 实时刷新，或控制台脱离演示台成为独立产品 `/console`。

**必须解决的架构现实（子代理实证，非臆测）**：
- 现有 `activity.js` **不是 hash router**（`location/hash/history` 零命中），只是 `state.route`+整面板重渲染。"升级路由"实为**从零引入**深链/刷新/前进后退。
- `reTree()` **整树 clear+重建**（`activity.js:382-383`）是丢焦点/丢滚动根因 → keyed reconcile 必须**用稳定节点 id 做 key**（不可用数组下标，否则增删中段串错）。
- `dynRows`（`activity.js:343-364`）已是**组件级局部重建**的良好雏形，证明"局部重渲染"在本仓库可行——泛化它即可。
- `el()` 属性陷阱：`{disabled:false}` 会真设 `disabled`（`app.js:24`），须传 `null`。新组件层继承此坑，store→DOM 绑定需规避。
- 新代码**无 transpile → 锁 ES5/ES2015 子集**（`var/function`，禁 optional chaining 等），纳入 review checklist，否则老浏览器静默炸而无构建报错。

---

## 决策 2 · 移动端与响应式策略

| 备选 | 判断 |
|---|---|
| 全端等价适配 | 成本高、收益低，无移动端流量数据支撑，不选 |
| **桌面优先 / 平板友好 / 手机 non-goal + 优雅降级 + 2 个应急例外** | **选定** |

**裁决**：这是**内部运营/审核/研发后台**，核心工作流（条件树构建、多分区编辑器、版本 diff、监控看板）信息密集、多面板、键鼠导向、坐班可控设备。
- **桌面 ≥1024px**：一等公民，全功能。
- **平板 768–1023px（含 iPad 横屏）**：友好、应完整可用（侧栏收抽屉）。
- **手机 ≤560px**：non-goal，复杂界面仅保证"可读不崩"。
- **手机两个应急例外（必须最低可用）**：①审核队列的**通过/放行** ②活动详情的**一键回滚止血**——on-call 场景，要求状态可读、按钮 ≥44px、二次确认模态小屏完整可点；其余编辑/建树引导"请用电脑操作"。

**断点：沿用 980/560 两档 + 正交的 `(pointer: coarse)`**（触控放大命中区 ≥44px，桌面密度不变），不新增第三档。**最大现存缺口**：`.sidebar` 288px 从不收缩（无任何 `@media` 触及它）→ ≤980 改**顶栏汉堡触发的 off-canvas 抽屉**（遮罩 + Esc/点遮罩关 + 背景滚动锁 + 焦点陷阱）。

---

## 决策 3 · 控制台宿主形态

| 备选 | 取舍 |
|---|---|
| **MVP：寄生在演示台 `#panel` 内的外部子应用**（`g.external` + `mount`），控制台内部用 ESM 模块 + hash 子路由 | **选定**：保住"删 external 组 + 删新文件 = 数据级回退"的隔离性；Step 1-18 不受影响 |
| 中期：独立入口页 `/console`（顶层路由，app.js 也 ESM 化） | 延后。触发条件：Step 1-18 演示台退役，或控制台成为独立产品 |

**裁决**：MVP 走子应用寄生，降低风险、保留回滚；把 `/console` 独立化作为二期显式里程碑。

---

## 决策 4 · 图表实现

| 备选 | 取舍 |
|---|---|
| **手绘 SVG 原语**(line/bar/sparkline，`getComputedStyle` 读 token，`data-theme` 切换重绘) | **MVP 选定**：零依赖，最守约束 |
| 自托管一个免构建微型图表文件 | 延后备选，监控阶段前单独拍板（工作量/维护面更小但引入一个 vendored 文件） |

**裁决**：MVP 手绘 SVG；遵循 `/dataviz`（类别色固定序 `--accent→--blue→--accent-2→--gold`，语义色 `--ok/--warn/--err` 留给告警，构建时跑 `validate_palette.js`，深色另读 dark token，**每图配"表格视图"兜底**满足色盲/forced-colors/移动端）。

---

## 决策 5 · 测试设施（零构建下）

**裁决**：三层，尊重零构建、Playwright 不进仓库。
- **Tier 0（headless 零安装，node v24 已在 PATH）**：`node --check` 语法门 + `*.assert.js` 纯逻辑 `console.assert`（覆盖 `pruneTree/parseLadder/operandOf/toEpoch`、新增的"人读回显"渲染器、keyed-diff、store reducer）。前提：借 ESM 模块拆分暴露 node/window 双导出可测缝。
- **Tier 1（契约冒烟，headless）**：node 脚本打 `/field-dict`、`/preview`、`/decision/v1/evaluate`、`/activity-config/v1/*`，断言响应 shape 与 UI 读取字段一致——防枚举/字段漂移与 409/degraded shape 变更。
- **Tier 2（UI 真点，选择性、外挂）**：走 `qa-test` skill / `npx playwright`，**不写进仓库/package.json**。只覆盖纯逻辑测不到的：树编辑焦点保持、keyed 列表视觉、409 冲突模态流、degraded 橙横幅、主题切换图表重绘、移动视口、模态焦点陷阱。
- **不引入组件测试**（jsdom/vitest 不划算）。

---

## 决策 6 · 前端揭示的后端契约缺口（跨平面，需回补进后端 FINAL_PLAN）

`/frontend-plan` 的一大价值是发现了后端 FINAL_PLAN 的**跨平面缺口**（需求 Agent 与 UI Agent 独立命中）。已回写进 `../activity-engine-backend-0718-2111/FINAL_PLAN.md` 的前端驱动补遗段：

1. **控制面缺查询端点（P0，阻断主界面）**：§6.2/§8.1 只有 create/update/diff，**没有**「按生命周期态列活动」「审核队列列 PENDING_REVIEW」「控制面 get-by-id」「版本历史列表」。旧 `/activity-marketing/list` 返回旧状态码 0/1/2，不含 DRAFT/PENDING_REVIEW/APPROVED/CANARY 新态 → 工作台与审核队列无数据源。**需补 `GET /activity-config/v1/activities?state=&bizLine=` + get-by-id + `/versions`。**
2. **决策 API 已解决 degraded 歧义（确认，非缺口）**：新 `/decision/v1/evaluate` 已有显式 `degraded/degradeReason/decisionVersion/hitActivities[]` → **消解**了旧 `/spu-discount` 里 `mode="legacy"` 三义（开关关/回退/无活动）的脆弱判定。前端一律读新契约的显式字段。
3. **Sandbox 全景需 explain 返回候选评估集（P1）**：§8.1 explain=true 返回 rule trace（仅 Admin），但需**显式**约定 explain 下返回**全部候选活动 + 命中/淘汰原因 + 结构化 trace**，否则 Sandbox"评估全景"（设计最大增量）无数据。
4. **field-dict 候选值（P1/可降级）**：现 `RuleField` 只有 6 字段、`FieldValueType` 只有 NUMBER/STRING/ARRAY，**无 ENUM/BOOLEAN/DATE、无候选值列表**。故设计文档的"可搜索多选下拉（候选来自 field-dict）"当前无数据 → **MVP 降级为自由 chip 输入**；若要枚举候选，需后端给 `field-dict` 补每字段候选集。
5. **preview 节点级定位（P2/前端补偿）**：现 `/preview` 恒 200 返回**单条错误字符串**、无节点定位。**MVP 前端做客户端预校验**（精确对齐 `RuleConditionTranslator` 规则：字段白名单/运算符集/值非空/NUMBER 可解析/between 恰 2 元素/深度≤4）补节点级红框，服务端 message 作卡片级兜底。后端若在 preview 回节点路径更佳，列为增强。

---

## 待澄清问题（汇总，Phase 4 与后端问题一并请用户拍板）

FE-Q1. 审核/灰度/回滚/版本历史/活动级监控这些**后端目标态能力**，本期是否与后端同步落地？若否，对应界面 MVP 就是诚实空态占位屏（可接受？）。
FE-Q2. 控制台鉴权与角色来源（SSO/JWT 未定）：前端如何拿当前角色做导航/动作门控？现状零鉴权。
FE-Q3. degraded 降级 200 时前端是否仍显示折扣金额（带醒目降级标）还是阻断展示？（关联财务签字）
FE-Q4. CAS 409 的 UX：简单"刷新重试"toast 还是"并排 diff 让运营选"？
FE-Q5. 手机是否真有审批/止血需求（决定 2 个例外是否成立）；最小支持宽度 320/360/375？
FE-Q6. 是否接受把控制台迁到原生 ESM `<script type="module">`（无构建代码分割的前提）？
FE-Q7. Playwright 是否允许进仓库（决定 UI 真点能否进 CI 门禁）？
