# 活动引擎平台 · 运营控制台前端实施计划（FRONTEND CONSOLE PLAN）

> `/frontend-plan` 产出。配套决策记录见 `30-DECISION_RECORD_FRONTEND.md`。本轮只出方案，获批前不改代码。技术取向：**决策 1 = 方案 C（原生纪律 + 图表窄口径例外，不引框架）**。

---

## 1. Goals / Non-goals

**Goals**
- 把现有报表式活动子应用升级为承载「工作台 / 活动管理(编辑器) / 审核与发布 / 决策验证 Sandbox / 效果监控」五类界面的生产级运营控制台。
- 落三个最难界面到组件级：①条件树构建器（就地校验+错误定位+类型感知值控件+人读回显+防抖预览）②生命周期状态机看板（七态+版本时间线）③决策 Sandbox 全景。
- 补齐生产必需的前端基础设施：集中 store、真 hash router（深链/刷新/前进后退）、keyed reconcile（治丢焦点）、原生 ESM 按页分片、统一反馈层（toast/modal/drawer 替换 `alert()`）、就地校验、骨架屏、RBAC 感知导航。
- 全程复用现有 DemoUI 语汇 + design token + 隔离/回滚模式；Step 1-18 演示台逐字节不动。

**Non-goals（本期前端不做，标注依据）**
- 引入前端框架/构建工具/CDN（决策 1，坚持约束）。
- 多租户 SaaS 控制台、COUPONS/CPS/RIGHT_COUPON/秒杀/拼团/砍价/抽奖玩法 UI（后端 §1 非目标）。
- 真实库存扣减 / 预算控制 UI（库存仅配置展示，后端非目标）；券码发放/核销 UI；复杂履约 UI。
- **运营写 DRL 的任何入口**（安全红线，永不做）。
- 手机端复杂界面完整可用（决策 2：手机 non-goal + 2 应急例外）。
- 独立入口页 `/console`（决策 3：二期里程碑）。

---

## 2. 路由与页面流

控制台内部 **hash 路由**（`#/…`，控制台自有命名空间，不影响演示台）：

| 路由 | 页面 | 主 API | 角色可见 |
|---|---|---|---|
| `#/` 或 `#/workbench` | 工作台首页（KPI+待办+告警） | 控制面 list(按态聚合) + effect overview | 全部 |
| `#/activities` | 活动列表（状态/类型/业务线筛选） | `GET /activity-config/v1/activities?state=` *(需后端补)* | 全部 |
| `#/activities/new` | 活动编辑器（新建草稿） | `POST /activity-config/v1` createDraft | 运营 |
| `#/activities/:id/edit` | 活动编辑器（版本化编辑） | updateDraft + preview + field-dict | 运营 |
| `#/activities/:id` | 活动详情（含版本时间线、生命周期看板） | 控制面 get-by-id *(需后端补)* + versions | 全部 |
| `#/review` | 审核队列（PENDING_REVIEW） | 队列 list *(需后端补)* | 审核 |
| `#/review/:id` | 审核详情（版本 Diff + 批准/驳回） | `getVersionDiff` + submit/approve/reject | 审核 |
| `#/release/:id` | 发布控制台（prepare/canary/放量/promote/rollback/offline） | `/activity-config/v1` release 命令 | 发布(审核/运营，按职责分离) |
| `#/sandbox` | 决策验证 Sandbox | `POST /decision/v1/evaluate` | 研发/管理员 |
| `#/monitoring` | 效果监控看板 | effect overview + experiment comparison | 全部 |

**页面流（四角色，详见需求子代理产出）**：
- 运营：`#/activities/new` 建草稿 → 配基础/规则/条件树/绑定/策略 → 保存(DRAFT, version+1) → 提交审核(PENDING_REVIEW，冻结)。
- 审核：`#/review` 队列 → `#/review/:id` 看 diff → approve(APPROVED)/reject(带 comment，回 DRAFT)。
- 发布：`#/release/:id` prepare(→PREPARED) → startCanary(1%/5%) → changeTraffic(20/50/100) → promote(ONLINE)/rollback(止血)/offline；每命令带 requestId + expected generation → 409 CAS。
- 研发/管理员：`#/sandbox` 构造上下文(含必填 bizLine + scene + explain) → 调决策 API → 看全景评估集/decisionVersion/route/degraded/timings。

---

## 3. 组件树（标注复用 vs 新建）

```
Console（新，ESM 入口 console/main.js，经 ActivityApp.mount 接管 #panel）
├─ AppShell（新）
│   ├─ TopBar（新：汉堡[≤980] + 业务线切换 + 角色标识 + 主题按钮[复用 initTheme]）
│   ├─ SideNav（改造 renderNav 思路：RBAC 感知，off-canvas 抽屉）
│   └─ RouteOutlet（新：hash router → import() 懒加载页面模块）
├─ 基础设施（新，~350 行有界）
│   ├─ store.js（createStore：getState/setState/subscribe）
│   ├─ router.js（hash 解析 → {route,params}）
│   ├─ reconcile.js（keyed 列表/树 diff，稳定 id 做 key）
│   ├─ api.js（复用 activity.js:17-30 的 api()，加 401/403/409/429 分支 + 重试）
│   └─ ui/（toast.js / modal.js / drawer.js / skeleton.js —— 全新，替换 alert()）
├─ 复用原语（原样复用，来自 window.DemoUI）
│   └─ el / clear / $ / card / kv / tagList / boolPill / fmtMoney（app.js:604）
├─ 页面模块（新 ESM，import() 分片）
│   ├─ workbench.js（新：StatTile[扩展 .dmn-cell] + TodoList + AlertList，全空态友好）
│   ├─ activities.js（改造 renderList：FilterBar[新] + 卡片化列表 + LifecycleBadge[新]）
│   ├─ editor.js（大量复用 activity.js form 视图）
│   │   ├─ 基础信息分区（复用 labeled/inputEl/selectEl/segToggle）
│   │   ├─ 红包/阶梯规则（复用 dynRows 阶梯档）
│   │   ├─ 买赠赠品（复用 dynRows）
│   │   ├─ 商品绑定/池圈选（复用 dynRows + autoBoundCount 展示）
│   │   ├─ ConditionTreeBuilder（重做①，见 §4.1）
│   │   └─ 合并策略 + 提交/预览（sticky 底栏[新]）
│   ├─ review.js（新：ReviewQueue + VersionDiff[新] + 裁决动作）
│   ├─ release.js（重做②生命周期看板：Stepper[新] + VersionTimeline[复用 .timeline] + 放量控件[新] + 危险操作 modal）
│   ├─ sandbox.js（重做③：复用 renderValidate 骨架 + 全景评估集[新] + degraded 横幅[新] + curl 导出[新]）
│   └─ monitoring.js（新：StatTile 行 + SvgChart[新，手绘] + 表格视图兜底 + 灰度对照）
└─ 图表原语（新，charts/svg.js：line/bar/sparkline，getComputedStyle 读 token）
```

**复用清单（子代理带行号确认，直接搬）**：`api()`(activity.js:17-30)、`labeled/inputEl/selectEl/segToggle/primaryBtn/banner`(:49-80)、`dynRows`(:343-364)、**整套递归条件树**(:367-471)、时间戳工具(:38-46)、全套 CSS 状态类（`.status-pill/.err-card/.act-banner/.field-invalid/.field-error/.tag-*/.pill-*/.timeline/.row-empty/.ctree-*/.price-row/.dmn-cell`）。

**不可破坏（6 条契约）**：Step 1-18 的 `selectDemo/runDemo/renderSummary/SUMMARY` + examples 28 demo 逐字节不变；`renderNav` external 分支须在 `if(!demos.length)return`(app.js:73) 之前；脚本序 examples→app→activity；examples 只加 `external:true` 组不加 demo；`window.DemoUI` 8 helper 名字/签名纯增量；主题走 `data-theme`+localStorage `drools-theme`。

---

## 4. 三个最难界面（组件级方案）

### 4.1 条件树构建器（重做①）
- **就地校验**：叶子 `blur/change` 客户端预校验，**精确对齐** `RuleConditionTranslator`（字段∈白名单 / op∈`field.allowedOps` / value 非空 / NUMBER 可解析 BigDecimal / between 恰 2 元素 / in·notIn·containsAny 非空 List / 深度≤4）。违规加 `.field-invalid`(红框，已有类) + `.field-error`(红字，已有类) + `aria-invalid` + `aria-describedby`。
- **错误定位**：顶部汇总"N 处待修正"，点击 `scrollIntoView` + focus 首个 `.field-invalid`。
- **类型感知值控件（按真实 valueType，非设计文档的 ENUM/DATE）**：NUMBER→`type=number`（between 双数字）；STRING(userDistrictId)→文本，in/notIn→**自由 chip 多选**（`.combo-multi` 新，因 field-dict 无候选值）；ARRAY(userTags)→自由 chip。**枚举候选下拉降级为自由输入**（决策 6-#4）。
- **焦点保持**：废弃 `reTree()` 整树重建 → keyed reconcile 只重渲染受影响节点，**稳定节点 id 做 key**。
- **人读回显**：纯前端把树渲染成中文自然语言（`orderAmount ≥ 100 且 (userTags 含 vip 或 …)`），无后端依赖。
- **防抖预览**：停 500ms 自动 `POST /preview`；进行中 `.status-pending "编译中…"`；ok=true→`.status-ok`+DRL 折叠；ok=false→`.status-error`+`.act-banner.err` 显 message（卡片级兜底，节点级靠客户端预校验）。
- 三视图切换 `[人读][JSON][DRL]`。

### 4.2 生命周期状态机看板（重做②）
- **Stepper 七态**：DRAFT→PENDING_REVIEW→APPROVED→CANARY→ONLINE→OFFLINE→ROLLBACK（枚举由后端字典下发，前端不硬编码）。状态**色+文字+形状**三重编码（○未达/●当前/✓完成/✕异常）。
- **降级现实**：若后端本期只到 0/1/2，未接入的态标灰弱"未接入"（`--text-faint` + ○），操作按钮 disabled + tooltip"能力待接入"——**不画假数据**。
- **版本时间线**：复用 `.timeline`；`getDetail` 只回当前版本 → 时间线需后端 `/versions` 支持（否则 empty 文案"版本历史需后端支持，当前仅 v{n}"）。
- **危险操作**（下线/回滚）：`.modal` 二次确认（替代 `alert`），`role="dialog" aria-modal` + 焦点陷阱 + Esc + 明确后果文案。放量/回滚命令带 expected generation → 409 走冲突流。

### 4.3 决策 Sandbox 全景（重做③）
- **输入**：userId/userTags/userDistrictId/spuIdList/orderAmount/quantity + **必填 bizLine** + scene(ALL/SPU_DISCOUNT/BUY_AND_GET_GIFT) + options.explain（Admin）+ options.dryRun + options.pinVersion（Admin）。
- **输出全景**：`hitActivities[]`(命中) + **全部候选 + 淘汰原因**(需 explain 返回，决策 6-#3) + `discount{finalAmount,strategy,currency}` + `gifts[]` + `route{group,experimentId}` + **`decisionVersion`** + `engineMode` + `degraded/degradeReason` + `timings`。
- **degraded 态**：读**新契约显式 `degraded` 字段**（不再靠 `mode="legacy"` 猜）→ `degraded=true` 显橙 `.act-banner.warn` + `degradeReason`；命中金额是否仍展示见 FE-Q3。
- **可复现**：`[复制为 curl]` 纯前端。分桶预演：手填 userId/requestId 看命中 canary/stable。

---

## 5. 逐页状态与边界（loading / empty / error / success）

| 页面 | loading | empty | error | success |
|---|---|---|---|---|
| 工作台 | 骨架卡（非文字） | 恒空友好：KPI"—"+"指标接入中"，待办/告警空文案 + **每卡带 CTA** | 单卡降级 `.err-card`+重试，不阻断整屏 | StatTile `tabular-nums` + ▲▼ 方向符 |
| 活动列表 | 3-5 骨架行 | ①无数据→CTA"新建" ②筛选无结果→独立文案+清除按钮（不共用） | 网络失败整块 `.err-card`+重载 | LifecycleBadge 三重编码 + 按态渲染合法操作 |
| 编辑器 | 字典加载 `banner("加载字段字典…")` | 空条件树→`.status-pending "恒通过"`（合法非错误） | 字典失败→`banner(...,"err")`+**重试**（治单点故障）；提交 400→字段级红框，409→版本冲突卡 | `card("活动已保存")`+`status-ok`；幂等命中 `tag-gold` |
| 审核 | 队列骨架 | 队列空"无待审" | diff 加载失败 `.err-card` | 版本 Diff 两栏高亮 + 裁决动作 |
| 发布 | 状态骨架 | — | 放量 409→冲突流 | Stepper 推进 + generation 状态 |
| Sandbox | `.status-pending "决策中…"`+按钮 disabled（避 `el()` disabled 坑） | 无生效活动→居中提示"检查 SPU 绑定/上线/时间"（**非降级**） | 接口恒 200，仅网络异常→toast | 全景 + decisionVersion + degraded 区分 |
| 监控 | 图表骨架（reduced-motion 关动画） | 长期默认空态"该范围暂无决策数据"+放宽建议，诚实标"指标接入中" | 图表卡级降级不整屏崩 | 类别色固定序 + dark 重绘 + 表格兜底 |

**校验三层**：①就地字段级（`.field-invalid`+`.field-error`+aria）②卡片级（`.err-card`+HTTP 状态+可读 hint：400/409）③全局 toast（`role="status"`，替 `alert()`）。
**可访问性**：全键盘可达 + 焦点陷阱 + 删除后焦点回落；`:focus` → `:focus-visible`；三重编码；异步区 `aria-live="polite"`；裸 select 补 `aria-label`；grid 伪表补 role 或改真 table；模态 `role="dialog"`；全局 `@media (prefers-reduced-motion)`。

---

## 6. API 契约（前端视角）

**决策面**：`POST /decision/v1/evaluate`（§8.1）——请求含必填 `bizLine`+`requestId`+`options{explain,dryRun,pinVersion,timeoutMs}`+`scene`；响应读 `requestId/traceId/decisionVersion/engineMode/degraded/degradeReason/hitActivities[]/discount{finalAmount,strategy,currency}/gifts[]/route{group,experimentId}/timings`。**外部默认无 trace**；explain 仅 Admin。
**控制面**：`/activity-config/v1/*` createDraft/updateDraft/getVersionDiff/submit/approve/reject/prepare/startCanary/changeTraffic/promote/rollback/offline —— 所有命令带 `requestId` + expected version/generation → 409 CAS。
**字典**：`GET /field-dict`（下拉唯一源，含 workflow 字典扩展），前端不硬编码枚举。
**预览**：`POST /preview` 恒 200 读 `ok/message/drl`。

**⚠ 前端驱动的后端补遗（决策 6，已回写后端 FINAL_PLAN）**：控制面查询端点（list by state / review queue / get-by-id / versions）= **P0 阻断**；explain 返回候选评估集 = P1；field-dict 候选值 = P1/可降级；preview 节点定位 = P2/前端补偿。

---

## 7. 响应式与移动端适配策略

**设备定位**（决策 2）：桌面优先 / 平板友好 / 手机 non-goal + 2 应急例外。

**断点表**：

| 断点 | 承担行为 |
|---|---|
| ≥980 | 侧栏常驻 288px；网格双栏；`.act-rail` sticky |
| ≤980 | 内容单列（现有）+ **侧栏→汉堡 off-canvas 抽屉**（新，补最大缺口）+ 提交/预览转 sticky 底栏 |
| ≤560 | 表格卡片化；条件树纵向堆叠；状态机竖排 stepper；Sandbox 三段堆叠；图表单列+简化 |
| `(pointer: coarse)` | 正交：`.row-del/.ctree-mini/.alist-acts/.chip/.nav-item` 命中区 ≥44px |

**逐界面小屏**：列表→卡片化（**不横向滚动**，避免状态/操作被推出）；条件树→**绝不横滚**，改纵向堆叠 + 最小缩进（靠 border-left 颜色 + "深度 3/4"文字指示层级）+ 折叠；编辑器→单列 + sticky 底栏 + 手风琴分区；状态机→竖排 stepper；Sandbox→优先级堆叠 + 运行后 `scrollIntoView` 结论；图表→`auto-fit` KPI + 单列 + 简化 + 表格兜底。

**移动端验收（≥1，给 5）**：AC-M1 375px 列表状态/操作无横滚可见可点、按钮 ≥44×44；AC-M2 ≤980 侧栏抽屉可开关+遮罩/Esc 关+背景滚动锁+焦点陷阱；AC-M3 375px 三层条件树无横滚、纵向堆叠可读、折叠可用；AC-M4（例外）审核放行 + 回滚在 375px 可读可点、确认模态完整 ≥44px；AC-M5 图表 ≤560 单列 + 表格兜底 + reduced-motion 无动画。

---

## 8. 文件级改动清单

**新增（控制台，原生 ESM，`static/assets/console/`）**：
- `console/main.js`（入口，`ActivityApp.mount` 接管 #panel）、`shell.js`（AppShell/TopBar/SideNav/RouteOutlet）
- `console/core/{store,router,reconcile,api}.js`
- `console/ui/{toast,modal,drawer,skeleton}.js`
- `console/pages/{workbench,activities,editor,review,release,sandbox,monitoring}.js`
- `console/components/{condition-tree,lifecycle-stepper,version-diff,version-timeline,filter-bar,stat-tile,lifecycle-badge,combo-multi}.js`
- `console/charts/svg.js`
- `console/console.css`（延续"纯增量选择器 + 全 token + 删文件即回退"约定，新增 `.lifecycle-badge/.stepper/.stat-tile/.chart-card/.filter-bar/.drawer/.modal/.toast/.combo-multi/.skeleton`）
- 测试：`console/**/*.assert.js`（Tier 0）、`scripts/contract-smoke.mjs`（Tier 1）

**修改（最小、隔离）**：
- `examples.js`：external 组 `activity` 的 label/入口指向新控制台（不动其它 demo）。
- `app.js`：仅 `renderNav` external 分支的 onclick 指向新 `ConsoleApp.mount`；`DemoUI` 导出**纯增量**（如需再暴露 helper）；**`init()` 允许最小改动**——启动时检测 `location.hash` 命中控制台命名空间(`#/`)则挂 `ConsoleApp` 接管 `#panel` 并同步 `state.demoId`/nav 高亮，而非无条件 `selectDemo(first)`（几行，**不碰 selectDemo/SUMMARY/Step1-18 逻辑**）。这是 P0-1 修正：深链/刷新头号验收要求 `init()` 读 hash，原"完全不动 app.js"承诺与之矛盾，此处放宽到"不动 Step1-18 渲染，但允许 init 路由接管"。**不动** selectDemo/SUMMARY/Step1-18 本身。
- `index.html`：新增控制台 ESM 入口 `<script type="module" src="assets/console/main.js">`（在 app.js 之后）；保留现有普通 script 装配。
- `activity.js`：**逐步取代**（编辑器逻辑迁入 `console/pages/editor.js`），迁移期可并存；最终删除时走数据级回退验证。

**回退**：删 external 组数据 + 删 `console/` 目录 + `git checkout` 三个改动文件 = 完全回到现状（无构建产物、无迁移）。

---

## 9. 按依赖排序的实施步骤（与后端分阶段共同落地）

> 前端界面**紧跟对应后端端点**落地。后端未就绪的界面先做"诚实空态占位"，端点就绪再充实。

- **FE-0 基础设施（无后端依赖）**：core/{store,router,reconcile,api} + ui/{toast,modal,drawer,skeleton} + AppShell（含 ≤980 抽屉）+ ESM 分片接缝 + `*.assert.js` 冒烟。验收：hash 路由/深链/前进后退可用；Step 1-18 不受影响。
- **FE-1 编辑器迁移（依赖现有 `/activity-marketing` + 后端阶段一/二 config API）**：editor.js 复用条件树/dynRows/字典驱动；条件树重做①（就地校验+焦点保持+人读回显）；保存迁到 config createDraft/updateDraft + 提交审核。验收：建草稿→提交审核闭环；条件树改字段不丢焦点；就地校验对齐后端翻译规则。
- **FE-2 活动列表 + 工作台（依赖后端补 list-by-state 端点 P0）**：FilterBar + 卡片化列表 + LifecycleBadge + 工作台空态友好。验收：按态筛选；无端点时降级占位并明确标注。
- **FE-3 审核与发布（依赖后端阶段二 workflow/release + diff/versions 端点）**：ReviewQueue + VersionDiff + 生命周期看板②（Stepper + 时间线 + 放量控件 + 危险 modal + 409 CAS 流）。验收：审核 diff→批准/驳回；prepare→canary→promote/rollback；409 冲突不覆盖。
- **FE-4 决策 Sandbox（依赖后端阶段三 `/decision/v1/evaluate` + explain 评估集）**：sandbox.js 全景③ + degraded 横幅 + curl 导出。验收：全景评估集+decisionVersion+degraded 区分 empty/降级。
- **FE-5 效果监控（依赖后端阶段三 effect 聚合端点）**：手绘 SVG 图表 + 表格兜底 + 灰度对照 + dark 重绘 + palette 校验。验收：类别色合规；主题切换重绘；空态诚实。
- **FE-6 移动端 + 可访问性收尾（正交，贯穿）**：断点/抽屉/触控 44px/reduced-motion/aria/focus-visible + 移动视口矩阵。验收：AC-M1~M5。

---

## 10. 测试策略（含移动端视口矩阵）

- **Tier 0 headless（node 已在 PATH）**：`node --check` 全静态 JS；`*.assert.js` 覆盖 pruneTree/parseLadder/operandOf/人读回显/keyed-diff/store reducer/条件树客户端校验规则。
- **Tier 1 契约冒烟**：`contract-smoke.mjs` 打 field-dict/preview/decision/config，断言 shape 与 UI 读取字段一致（防漂移 + 409/degraded shape）。
- **Tier 2 UI 真点（qa-test/npx，不进仓库）**：焦点保持、keyed 视觉、409 模态流、degraded 橙横幅、主题切换图表重绘、移动视口、模态焦点陷阱。
- **视口矩阵**：320 / 375 / 390–414 / 768 / **979 与 981（980 断点两侧）** / 1280 + 横屏 667×375。重点屏：条件树、Sandbox、看板。
- **正交维度**：文字 200% 不破版；`prefers-color-scheme` × `data-theme` 双路径；`prefers-reduced-motion`；forced-colors/打印看板兜底；触控 ≥44px；**新代码锁 ES5/ES2015 子集**（review checklist 硬项）。
- **图表专项**：`/dataviz` `validate_palette.js` CVD 校验 + 表格视图兜底。

---

## 11. 验收标准

- [ ] 工作台/列表/编辑器/审核/发布/Sandbox/监控 七路由可深链、刷新保持、前进后退可用。
- [ ] 条件树改字段/运算符/增删节点**不丢焦点/滚动**（keyed reconcile 稳定 id）；就地校验精确对齐后端翻译规则并可跳首错。
- [ ] 运营**无任何裸 DRL 入口**；所有下拉源自 `/field-dict`。
- [ ] 生命周期看板七态三重编码；未接入态灰弱不可误点；危险操作二次确认模态。
- [ ] Sandbox 读**新契约显式 `degraded`**，正确区分"无生效活动(empty)"与"降级(warn)"，展示 decisionVersion + route。
- [ ] 409 CAS 冲突走冲突流（不 `alert`、不覆盖对方版本）。
- [ ] field-dict 拉取失败有重试，不整台白屏。
- [ ] 监控图表类别色合规 + 主题切换重绘 + 表格兜底。
- [ ] Step 1-18 演示台逐字节不变；删 external 组 + 删 console/ = 完全回退。
- [ ] light/dark 双主题全界面正确；`prefers-reduced-motion` 生效。
- [ ] **移动端 AC-M1~M5 至少全过**（≥1 移动视口验收）。

---

## 12. 风险与回滚

| 风险 | 缓解 |
|---|---|
| 无构建下模块加载/命名冲突/ES 子集 | ESM 模块隔离作用域；`node --check` 门；review checklist 锁 ES5 子集 |
| keyed diff 用下标做 key 串错 | **稳定节点 id 做 key**（决策 1 硬约束）；`*.assert.js` 覆盖增删中段 |
| 后端目标态端点未就绪 → 界面空壳 | 分阶段共同落地（§9），未就绪界面诚实空态占位，不假数据 |
| 大 trace/大评估集/无分页列表卡顿 | keyed reconcile 局部更新；列表服务端分页（后端补）；虚拟滚动作为增强 |
| 主题切换 × SVG 图表颜色错乱 | 图表 `getComputedStyle` 读 dark token + `data-theme` 监听重绘（看板必测） |
| `saveScalars` DOM 真值源脏读 | 迁 store：输入即写 store，弃"从 DOM 回读" |
| 引框架的诱惑扩散 | 决策 1 明确逃生口触发条件；图表例外窄口径、延后 |
| **回滚：低（强项）** | 子应用寄生 + 数据级回退（删 external 组）+ `git checkout` 静态文件；无构建产物、无迁移 |

---

## 13. 假设与待澄清

**假设**：控制台仍寄生 `#panel` 子应用槽、复用 DemoUI/token、Step 1-18 不动；后端会分阶段落地 §8 新契约（否则对应界面空态占位）；单级审核 + 强制职责分离；灰度按 userId 哈希；库存仅展示；手机 non-goal + 2 应急例外。
**待澄清**：见 `30-DECISION_RECORD_FRONTEND.md` FE-Q1~Q7（后端目标态是否同步落地 / 鉴权角色来源 / degraded 是否仍显金额 / 409 UX / 手机需求与最小宽度 / ESM 迁移许可 / Playwright 是否进仓库）。这些不阻塞设计与 FE-0/FE-1a 开工，但阻塞依赖后端端点的 FE-2~FE-5 和生产验收。

---

## 14. 独立评审吸收与计划修正（2026-07-18）

独立评审子代理对照事实源逐项核验：**可核验的复用引用（api/dynRows/条件树/renderNav external 位置/复用 CSS 类）与 §8 后端契约均真实一致，约束守住，可作为落地依据**。以下逐条吸收其发现：

- **P0-1（已修，见 §8）· 深链/刷新 vs 不动 init 矛盾**：app.js `init()`(app.js:608-614) 无条件 `selectDemo(first)`、不读 hash，而 §11/FE-0 头号验收要"深链/刷新保持"。→ **§8 已放宽**：允许 `init()` 最小改动做启动 hash 路由接管（不碰 Step1-18 渲染逻辑）。此为 FE-0 开工前须落的第一改动点。
- **P1-2（已修，见 §9 拆分）· FE-1 依赖标错阶段 + 迁移期双写**：控制面 config/release controller 属**后端阶段三**（非阶段一/二），且迁移期新编辑器写 config(DRAFT) 而旧列表读 `/activity-marketing/list`(旧态 0/1/2) 会 split-brain。→ **FE-1 拆为**：
  - **FE-1a（无新后端依赖）**：编辑器 UI + 条件树重做① + 统一反馈层，**仍走现有 `/activity-marketing/create`+`/preview`**，不引状态机；权威写入端 = 旧 create。
  - **FE-1b（依赖后端阶段三）**：翻到 `/activity-config/v1` createDraft/updateDraft + 提交审核；切换当天起**唯一权威写入端 = config API**，旧 create 停用，列表同步切控制面 list（避免双写）。
- **P1-3（已修，见下）· RBAC 感知导航无角色来源**：零鉴权现状下无角色数据源。→ **RBAC 感知导航从 MVP Goal 降级为占位**：MVP **不做真门控**，全导航可见，角色用**客户端 dev 切换器**（仅演示审核/发布动作差异），真门控 + 职责分离按钮可见性**延后至 FE-Q2 定案**。§1 Goal 的"RBAC 感知导航"据此改读为"RBAC 感知导航（MVP 占位，真门控延后）"，不作 MVP 验收项。
- **P2-4 · 基础设施预算上修**：keyed **树** reconcile(100-150 行) + modal/drawer 各需 focus-trap → 实际 **~500-600 行**（非 ~350）。抽出共享 `focus-trap` 小工具；**reconcile 收窄口径**：只做"叶子内容 keyed 更新 + 兄弟增删"，move 用重建兜底。决策 1 理由 2 的量化前提据此修正（架构结论不变，仍显著轻于引第二套范式 + 重写 8 helper）。
- **P2-5 · api() 不能原样复用**：现有 `api()` 硬编码 `BASE="/activity-marketing"`(activity.js:11)，新端点在 `/activity-config/v1`、`/decision/v1` 不同 base。→ api.js **改为接收完整路径或多 base 前缀**；§6 的 `field-dict`/`preview` 现网实为 `/activity-marketing/field-dict|preview`，迁移期沿用旧路径，FE-1b 起再评估是否并入 `/activity-config/v1`。
- **P2-6 · 条件树节点 id**：现有模型与后端 `conditionTreeJson` 均**无节点 id**（emptyLeaf/pruneTree 不含 id）。→ **节点 id 为前端临时键**：建/载时客户端铸 id 全程维护，**`pruneTree` 提交前剥离 id**（不污染后端）；`*.assert.js` 覆盖"载入无 id 树→铸 id→编辑→prune 去 id"。
- **P2-7 · P0 查询端点排在后端末阶段**：决策 6-#1 的 P0 读端点属后端阶段三。→ **§9 每个 FE 步注明两个里程碑**：「占位可交付」（空态占位屏，演示可用）/「真数据可验」（依赖端点就绪）。空壳不计作验收通过。
- **P2-8 · 编辑器复用 vs store 反转**：现标量靠 `saveScalars` 从 DOM 回读(activity.js:474-486)，与"输入即写 store"冲突 → 编辑器是**部分复用**（控件搬运 + 数据流反转，工作量别低估）。reconcile **拆 diff(纯)/apply(DOM) 两层**，diff 层喂 Tier-0 headless。
- **澄清（评审确认，非扣分）**：① 经核验**无 Spring Security、无 CSP**，Spring 默认 `.js→text/javascript` module 不会被拦 → **MIME/CSP 不是风险**；真正的启动时序问题就是 P0-1（deferred module vs init）。§12 的"MIME/CSP"风险项据此撤下。② sandbox/看板小屏有策略但无独立 AC，在"手机 non-goal + 2 例外"口径下**可辩护**——补一句：**sandbox/看板小屏仅保证"可读不崩"，不作移动端验收**。③ 计划自称"生产级控制台"与仓库"学习脚手架"定位有政策张力——落地大型控制台是一次**定位扩张**，非技术硬伤，需用户明确认可（已纳入 Phase 4 呈报）。

**评审总评（原文要点）**：架构选型(方案 C)与复用策略扎实、约束守得住、回退可信，可作落地依据；修掉上述 P0-1 + 两个 P1 + 随手收 P2，即可开工。
