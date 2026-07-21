# FINAL_PLAN · 前端控制台视觉重设计 v2（Calm Precision）

> 2026-07-20。决策依据见同目录 `DECISION_RECORD.md`（D1–D10）。可视化提案见 Artifact「活动引擎控制台前端视觉重设计 v2」。
> **本计划获批前不改任何代码。** 形态：纯前端视觉皮肤层（`frontend/src` + `index.html`），路由 / API / 业务逻辑 / 全部 data-testid **零改动**；重心＝设计 token 换代 + 外壳三件套质感 + 原语三态打磨 + 内联 SVG 图标系统。

## 0. 规模与节奏
按 **Phase A→B→C→D** 推进，每阶段自成可停的绿色状态：
- **A（token 换代）**：改 1 文件、零组件改、全站自动升级——零风险、收益立现。
- **B（外壳三件套 + Icon.vue）**：每屏可见的公共面，ROI 最高。
- **C（6 原语三态打磨 + 启用死原语）**：一致性收敛。
- **D（收尾增量·可选）**：手搓盲区下沉（demos/condition-tree/Login）+ stat 条 + FOUC 加固。
建议先 A+B 到验收门全绿，再按需 C、D。

## 1. Goals / Non-goals

**Goals**
- G-1 用 **Calm Precision** 视觉方向（中性为主 + 单一克制强调 + 分层纵深 + 强排版层级 + mono 数据一等公民）替换当前「扁平线框感」，达到「生产级 admin console」观感。
- G-2 **图标系统化**：新建本地内联 SVG `Icon.vue`（零依赖），替换全站 18+ 处 emoji/几何字形。
- G-3 **纵深与层级**：4 档阴影全部按语义启用；页面标题 24–26/700；修 `--text-faint` 到 WCAG AA。
- G-4 **补齐微交互**：hover 抬升 / 按压 / focus 环 / 卡片阴影过渡，覆盖所有可交互件。
- G-5 **零业务回归**：路由 / API / store / 逻辑不动，全部 data-testid 逐字保留，5 E2E + 3 Vitest 保持绿；768 & 390 横向溢出 ≤4px。

**Non-goals**（见 DECISION_RECORD「非目标」，摘要）
- 不加功能、不改路由/API/testid/类选择器/tokens 变量名；不引 UI/CSS 库；不重写 ListView 表格；不改后端/登录页；不做移动端独立产品。

## 2. 视觉方向与设计参考（Calm Precision · 每条：参考 → 借鉴模式 → 落到本项目 token/组件）

> 用户点名 langchain-platform / recsys；参考库首选站（Mobbin / SaaS Interface / Dribbble-Dashboard / Land-book）+ 可抄设计系统（shadcn/ui、Tremor、Ant Design Pro）。以下不是「参考某站做好看点」，而是具体映射。

| # | 参考 | 借鉴的具体模式 | 落到本项目 |
|---|---|---|---|
| R1 | **LangSmith / LangGraph Platform** | 清爽中性底 + 单一 brand accent；trace / run 用等宽字体呈现 | 中性色阶（冷偏）+ 单强调；活动 ID / DRL / 决策轨迹 / 版本号一律 `--mono` + `tabular-nums`（`ListView .asub`、`DetailView` DRL 盒、`ValidateView` traces） |
| R2 | **Linear** | 近单色中性、层级靠**字重+颜色**而非字号；侧栏选中态＝soft 填充 + 极快微交互 | `--text/-soft/-faint` 三档拉开；侧栏 active＝`accent-soft` 底 + **左 3px accent 条** + 图标；transition 120ms |
| R3 | **Vercel Dashboard** | 高对比、克制、可见边框、mono 数据、方正 | 边框 `#e2e5ea` 比现在更可辨；圆角 14→10 更方正精密；数据 mono |
| R4 | **shadcn/ui** | token 语义（surface/muted/border/card）、subtle 卡片阴影、`muted-foreground` 次要文字、清晰组件三态 | `--bg-elev`(surface)/`--bg-soft`(muted)/`--border`/`--shadow`(card) 语义对齐；`--text-soft` 当 muted-foreground；Button/Badge/Field 补三态 |
| R5 | **Supabase Studio** | 面板密度、暗色一等公民 | `DemoPanel` 请求/响应面板质感；暗色主题与浅色同等打磨（不裸反相） |
| R6 | **Tremor** | dashboard KPI stat 卡（数值大 + label + 趋势） | 可选 ListView 顶 stat 条（D7）：26px `tabular-nums` 数值 + 12px label + 语义色趋势 |
| R7 | **lucide（图标）** | 线型、`stroke=currentColor`、1.75–2 描边 | `Icon.vue` 手抄 path；导航/操作/空状态/状态点全换 |

**强调色**（D2，待确认，Artifact 可实时切）：默认 `indigo #4f46e5`（hover `#4338ca` / soft `#eef2ff` / line `#c7d2fe`）；备选 `cobalt #2563eb` / `violet #7c3aed`。语义色（ok/warn/err/info）**独立于强调色**。

## 3. 具体 token 值（Phase A 落到 `tokens.css`，改值不改名 + 少量新增）

> 下为浅色；`[data-theme=dark]` / `@media(prefers-color-scheme:dark)` 同名变量并给暗色值（见 Artifact 内 `:root[data-theme="dark"]` 块，已配好）。

```
/* 中性色阶（冷偏，非纯灰） */
--bg:#f4f5f7;  --bg-elev:#ffffff;  --bg-soft:#f7f8fa;  --bg-hover:#eef0f4;
--border:#e2e5ea;  --border-strong:#cdd2db;
--text:#1a1d26;  --text-soft:#59606e;  --text-faint:#757c8a;   /* faint 4.5:1 ✓ 修 AA */
/* 强调（默认 indigo，三选一） */
--accent:#4f46e5;  --accent-2:#7c74ec;  --accent-soft:#eef2ff;  /* +新增 --accent-hover:#4338ca; --accent-line:#c7d2fe */
/* 语义（微调对齐色阶） */
--ok:#15803d; --ok-soft:#dcfce7;  --warn:#b45309; --warn-soft:#fef3c7;
--err:#b91c1c; --err-soft:#fee2e2;  --blue/info:#1d4ed8; --blue-soft:#dbeafe;
/* 纵深（4 档全部启用） */
--shadow-sm:0 1px 2px rgba(16,24,40,.06);
--shadow:0 1px 3px rgba(16,24,40,.09),0 1px 2px rgba(16,24,40,.05);
--shadow-md:0 4px 12px rgba(16,24,40,.10),0 2px 4px rgba(16,24,40,.05);
--shadow-lg:0 14px 34px rgba(16,24,40,.16),0 4px 10px rgba(16,24,40,.07);
/* 形状 */
--radius:10px; --radius-sm:7px; --radius-lg:14px; --radius-pill:999px;
/* 排版（新增/启用） */
--fs-2xl:26px;  /* +新增 --fs-3xl? 用于页面标题；+新增 --fw-bold:700 */
```
新增 token（只加不改名）：`--accent-hover`、`--accent-line`、`--fw-bold:700`、（可选）`--fs-3xl`。**变量名一律不删不改**（约束子代理红线）。

## 4. 路由与页面流（**完全不变**）
`router/index.ts` 一字不改。10 个渲染出口（login/callback 裸壳 + console 四页 + demos 两页，套 AppShell）全部沿用。外壳分流、401→login watch、抽屉开合状态机（`AppShell`）不动。

## 5. 组件树（复用现有 vs 新建）

| 层 | 文件 | 动作 |
|---|---|---|
| Token | `shared/styles/tokens.css` | **改值 + 新增**（Phase A） |
| **新建** | `shared/ui/Icon.vue` | 内联 SVG 图标原语（Phase B）：`props { name, size?, stroke? }`，本地 path map，`aria-hidden` 默认、可透传 `aria-label`/`data-testid` |
| 外壳 | `shared/layout/TopBar.vue` | 品牌 mark（Icon）+ 主题按钮（sun/moon Icon）+ 汉堡（menu Icon）+ 身份 chip 质感（Phase B）。**保 `nav-toggle`/`theme-btn` testid + toggleTheme 逻辑** |
| 外壳 | `shared/layout/SidebarNav.vue` | 导航项加图标 + active 态（左 3px 条 + soft 填充）（Phase B）。**保 nav-console/tab-* testid + DOM 结构** |
| 外壳 | `shared/layout/AppLayout.vue` | topbar 高 52→56、阴影/边框微调（Phase B）。**抽屉 translateX/scrim/safe-area 不动** |
| 外壳 | `shared/layout/IdentityBar.vue` | chip 质感（Phase B/C）。**保 auth-bar/tenant-* testid + ≥561 内联 + actor 留 auth-bar 内 + ≤560 popover** |
| 原语 | `Button/Badge/Card/EmptyState/Segmented`（+启用 `Section`/`Field`） | 三态 + 纵深 + 图标接入（Phase C）。**props/variant/slot/testid 冻结** |
| 盲区 | `demos/*`、`condition-tree/*`、`DynRowTable`、`views/Login\|Callback`、`EditorView` 残留 `.fg/.sec-title` | 下沉到原语 / 单独打磨（Phase D 增量）。**保 `.text-box/.no-body` 类 + 全 testid + `→` 字符** |
| 结构 | `index.html` | 可选：`<head>` 内联主题脚本（FOUC，Phase D）+ 已有 `viewport-fit=cover` 不动 |

## 6. 状态与边界（逐页，视觉层保留既有覆盖，只升级表现）

- **ListView**：Skeleton(5行) / EmptyState / Banner(err) / 表格 hover 行 / Badge 状态 / 分页——全保留；升级＝行 hover 抬升 + Badge 加状态点 + mono ID + 操作按钮 hover 显形 +（可选）stat 条。
- **EditorView**：6 段表单 / 右轨校验 Banner / 提交「提交中…」/ save-success Card / `type-chip-5` Segmented——全保留；升级＝`.sec-title`→`Section`、`.fg`→`Field`（focus 环 + err 态）、chip 三态。**`type-chip-5` 由 Segmented option.testid 产出，不可动。**
- **DetailView**：Skeleton(6) / 3+2 Card / KV / DRL mono 盒 / green·red tag——升级＝Card 纵深 + tag 用 Badge + KV 对齐。
- **ValidateView**：查询中 Banner / 命中 Card+KV / traces 列表 / EmptyState(⚖→Icon)——升级＝traces 时间线 mono + 空状态图标化。
- **DemoPanel**：method 徽章 / mono 路径 / HTTP 状态 / 响应区（保 `.text-box/.no-body`）/ `▶`→send Icon——升级＝面板质感（Supabase 风）。**catalog:60 断言 `→` 字符：`OrderSummary` 的 `→` 保留。**
- **Login/Callback**：`🔐`→shield Icon；卡片质感。

## 7. 响应式与移动端适配策略（原样保留，见 D10）
- **断点表**（字面量不改）：≥1024 & 768–1023 侧栏常驻（同 248px）/ `<768` off-canvas 抽屉 / ≤560 IdentityBar popover。触发变化沿用现状。
- **逐页小屏**：980 断点内容多栏塌单列（Detail/Editor/Validate/DemoPanel）；760 断点 ListView 表格降列——**沿用，不新增断点**。
- **交互替换**：hover 态在触屏由 `(pointer:coarse)` 44px 命中 + active 兜底；抽屉汉堡开合不变。
- **移动端验收**：768 & 390 下 `body.scrollWidth - innerWidth ≤ 4px`（硬门）；44px 触控保留；抽屉滑入/scrim/锁滚/safe-area 不变。
- **风险点**：新增 padding / 更大图标 / chip 变宽不得撑破 4px——尤其 IdentityBar 内联 chip（768）、ListView 760 单元格、stat 条（若做，小屏 2 列）。

## 8. 文件级改动清单
1. `frontend/src/shared/styles/tokens.css` —— 色阶 / 阴影 / 圆角 / 排版 改值 + 新增 4 token（A）
2. `frontend/src/shared/ui/Icon.vue` —— **新建**（B）
3. `frontend/src/shared/layout/{TopBar,SidebarNav,AppLayout,IdentityBar}.vue` —— 质感 + 接 Icon（B/C）
4. `frontend/src/shared/ui/{Button,Badge,Card,EmptyState,Segmented,Section,Field}.vue` —— 三态 + 纵深 + Icon（C）
5. `frontend/src/console/pages/{ListView,EditorView,DetailView,ValidateView}.vue` —— 接 Icon / 用 Section·Field / mono·Badge（C，+可选 stat 条 D）
6. `frontend/src/{demos/*,console/condition-tree/*,console/DynRowTable.vue,views/LoginView.vue}` —— Icon + 原语下沉（D 增量）
7. `frontend/index.html` —— 可选 FOUC 内联脚本（D）

## 9. 按依赖排序的实施步骤
1. **A**：改 `tokens.css`（值 + 新增）。`typecheck` + `build` 绿。**此时全站已自动换肤**——先跑一次看基线观感。
2. **B1**：建 `Icon.vue` + 单测（渲染 name→svg、透传 testid/aria）。
3. **B2**：`TopBar`/`SidebarNav`/`AppLayout` 接 Icon + active 态（同 commit，避免中途 testid 双份）。跑 `vitest` + 768/390 截图核 4px。
4. **C**：6 原语三态 + 启用 `Section`/`Field` + 四控制台页接入。逐页保 testid。
5. **验收门**：`typecheck` 0 + `vitest` 绿 + 5 E2E 实跑绿 + 768/390 溢出 ≤4px + build 体积核对（主 chunk gz 基线 43.9KB，Icon 内联增量应 <2KB）。
6. **D（可选）**：盲区下沉 + stat 条 + FOUC。每项独立可停。

## 10. 测试策略（含移动端视口矩阵）
- **Vitest**（不改）：`logic/authClient` 纯逻辑无关；`ConditionGroup` 断 DOM/testid——重构条件树须保 `leaf-del/scalar-val/add-cond/add-group/logic-*/cond-group` + 元素语义。新增 `Icon.test.ts`。
- **E2E 视口矩阵**（起 console `-Pfrontend` h2 mem + 种子实跑）：`e2e:dev`(桌面) / `e2e:oidc`(auth) / `e2e:catalog`(demos，含 `→` 断言) / `e2e:tablet`(768 docked，溢出 ≤4) / `e2e:phone`(390 抽屉，溢出 ≤4)。**全绿为门**。
- **视觉自查**：明 + 暗 × 3 强调色手过四控制台页 + demo 面板 + 登录页；≤560 popover 档人工核（无 e2e）。

## 11. 验收标准
- [ ] 5 E2E 全绿；3 Vitest（+Icon.test）全绿；`typecheck` 0。
- [ ] **移动端**：768 & 390 视口 `body.scrollWidth - innerWidth ≤ 4px`；44px 触控保留；抽屉滑入/scrim/锁滚正常。
- [ ] 全站 emoji/字形图标清零，统一 `Icon.vue`；按钮内 testid/aria 保留。
- [ ] 页面标题 ≥24px；`--text-faint` ≥4.5:1；4 档阴影按语义启用；所有可交互件有 hover/focus/active。
- [ ] 明 + 暗双主题、3 强调色均无对比/错色；`data-testid` 逐字全在；`.tr/.text-box/.no-body` 类未改名。
- [ ] 主入口 chunk gz 相对 43.9KB 增量可控（Icon 内联 <2KB）。

## 12. 风险与回滚
- **风险**：① 新 padding/图标撑破 4px 溢出（缓解：B/C 后立即跑 tablet+phone）；② 条件树重构碰 Vitest DOM 断言（缓解：只改样式/加 Icon，不动 testid 与元素语义）；③ 暗色下渐变/阴影 alpha 需另调（Artifact 已配双主题值）；④ Icon 手抄 path 出错（缓解：Icon.test 快照 + 逐个目检）。
- **回滚**：分支开发（如 `feat/frontend-visual-refresh`）。Phase A 单文件可 `git checkout tokens.css` 秒退；B/C 按 commit 粒度回退；圆角/stat/FOUC 均为独立可选项，可单独不做或还原。
