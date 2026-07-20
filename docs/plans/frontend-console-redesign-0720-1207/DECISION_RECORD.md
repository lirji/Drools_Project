# 决策记录 · 前端控制台重设计（可读性 + 信息架构，2026-07-20）

> 输入：6 个只读子代理（需求用户流 / UI-UX 与交互状态 / 前端架构 / 仓库约束与可复用组件 / 移动端与响应式 / 测试风险与边界）。
> 用户诉求原文：「前端还是有些不像生产环境那样易读、分区也不是很明显，重新设计——提升可读性、让功能分区/信息架构更清晰；偏视觉设计 + 布局/分区/导航；复用既有组件与 token，桌面优先，沿用既有路由与 API 契约，不加新功能」。
> 现状基线：`frontend/`（Vue3+Vite+TS+Pinia+vue-router，挂 `/ui/`），F0–F2 已落地。

## 诊断（重设计要解决的「病灶」，带证据）

1. **两套不一致的外壳范式**：控制台 `ConsoleShell.vue`（居中 1200px、**无侧栏**、顶部 tab）vs 演示台 `DemoShell.vue`（**已有 268px 左侧栏**、1360px）。同一产品一半是生产范式一半是居中单列，宽度也不统一（1200/1360/App 全宽/Login 460）。
2. **双层导航职责混乱**：`App.vue` 顶栏 nav（工作台/演示台）+ `ConsoleShell` tab（列表/新建/验证）上下叠放、视觉语汇几乎相同；且「新建活动」是一个**动作**却与两个**视图** tab 并列，detail/edit 又归回列表 → tab 语义不齐、入口重复（列表右上也有「+新建」）。顶栏「工作台」高亮在 `/console/validate` 时还会消失。
3. **身份条占据首屏**：`ConsoleShell.console-head` = 全局 h2 标题 + desc + **dev 档 2 条全宽身份条（tenant + actor）/ auth 档 1 条**，把真正的操作区压到首屏之下。租户/操作者本质是**全局会话上下文**（对所有活动 API 生效、跨页），却被渲染成页面级 hero band。
4. **无页面头范式**：全局标题写死在壳里、非逐页；各页各搓临时 toolbar；详情/编辑无面包屑只有孤立「← 返回」按钮；双标题冗余（顶栏品牌 + ConsoleShell h2）。
5. **设计 token 缺三个维度**（颜色够、可读性不够）：**无字号/行高/字重 scale**（全站硬编码 11–22px）；**明色三层背景过近**（`--bg`#f4f5fb / `--bg-elev`#fff / `--bg-soft`#f7f8fc 明度差极小，卡片与页面几乎不分离）；**全站零 `:focus-visible`/焦点环**；无 radius-lg、无 shadow-lg、无 z-index scale、无布局尺寸 token；spacing 最大仅 32px。
6. **可达性**：`--text-faint`#98a0b3 在浅底 ≈**2.4:1 不达 WCAG AA**，却用于大量 11–12px 次要正文；tab 非 `role=tablist`；身份输入靠相邻 span 非 `<label for>`。
7. **样式重复**：按钮 `.primary/.ghost/.mini/.danger` 散落 8 文件、段控 `.seg/.chip` 3 处、徽章/圆点 4 处、表单 `.fg` 3 处各写一套 → 一致性差。
8. **响应式碎**：现有断点 760/900/980 三值并存、与既有计划 §6 的 1024/768/560 不对齐；**off-canvas 抽屉从未实现**（DemoShell 只是 flex→column）；触控放大靠各组件手写、**EditorView 整页漏**（chip/mini 触屏仍 ~30px）、theme-btn 34px、pager 用 36 非 44；`index.html` 缺 `viewport-fit=cover`。
9. **死链/穿插**：顶栏「旧页」→ `/index.html`（F3 已退役的落地页）；DemoNav 里塞一条跳回 `/console` 的 external 链接，模糊两区边界。

## 决策

### D1 · 外壳架构：全局 AppShell（架构备选 A 骨架 + C 原语，合成）
- **备选**：A 全局 AppShell 在 App.vue 承载 / B 保留双壳各自内部重构 / C 共享布局原语 slot 化。
- **裁决：A+C 合成。** `App.vue` 应用路由渲染 `<AppShell>`、`login/callback` 渲染裸 `<router-view>`（登录页无壳）；抽 `AppLayout`(CSS grid)/`SidebarNav`/`TopBar`/`IdentityBar` 到 **`src/shared/layout/`**，AppShell 组合它们 + `<router-view>` + `ToastHost`。
- **否决 B**：侧栏 chrome 两处重复、身份/主题不统一（demos 仍无身份条）、两套 max-width 并存——不满足「生产级统一分区」诉求。
- 理由：演示台**已经是**侧栏范式，A+C 是把控制台并到它、并抽成共享原语，不是从零发明；路由/懒加载/testid 全不动。

### D2 · 导航 IA：单一持久左侧栏收编两层导航；DemoNav 留 demos 内容区
- 左侧栏（`SidebarNav`）收编 `App.vue` 顶部 nav + `ConsoleShell` 三 tab：一级「控制台 / 演示台」，控制台下挂子项「活动列表 / 新建活动 / 优惠验证」。**`tab-list/tab-new/tab-validate/nav-console/nav-demos` testid 逐字搬入**。
- 「新建活动」从一级 tab 语义降为「列表主按钮 + 路由入口」的**上下文子项**（可达性不减，`tab-new` 锚点保留）。
- **关键约束（架构+测试代理共识）**：`demos/catalog.ts`（1351 行含全部 demo 数据）**只被 demos 区懒加载引用**（`DemoNav.vue`/`DemoHome.vue`/`DemoPanel.vue`）；若把 `DemoNav` 塞进 eager 的全局 `SidebarNav` 会把 catalog 拉进主包 → 代码分割退化。**故 18-Step 目录（DemoNav）保留在 demos 内容区做次级导航**，全局侧栏只放「演示台」入口，不展开 30 条 demo。`DemoPanel` 原样不动（`e2e-catalog` 直达 `/ui/demos/{id}` 断言 `demo-panel-*`，**不走 DemoNav**）；`DemoNav.vue` **仅删 external 组渲染**（跳回 console 的 `demo-nav-activity` + `catalog.ts:52` 的 `external:true`，无 e2e 断言，安全），`demo-nav-{id}` 不动。
- 演示台仍是与控制台平级的一级区（**假设**：弱化为教学/开发者沙盒但不裁掉），共享统一外壳。

### D3 · 身份/上下文归属：抽 `IdentityBar` 到全局 TopBar
- `ConsoleShell` 的 auth-bar / tenant-bar / actor-bar **逐字抽到** `IdentityBar`（放 TopBar 右侧），全局显示、移出内容流；`v-if auth.authEnabled` 逻辑与三个 store 接口零改；**所有身份 testid 保留**（`tenant-bar/tenant-input/tenant-chip-*/auth-bar/auth-tenant/logout/actor-bar/actor-input`）。
- `ConsoleShell` 的「401 watch + doLogout」上提到 `AppShell`（覆盖 console+demos 两区，属登出重定向的扩面，非新功能）。
- **安全不变量守死**：header 注入仍由 `useAuthStore` 首次调用触发（在 `beforeEach`），与 UI 位置无关；auth 档**绝不发 X-Tenant-Id**。
- e2e-oidc 登录后在 `/console` 立刻等 `auth-bar` → IdentityBar 必须在应用路由（含 console）可见，满足。

### D4 · 设计 token 扩展（只加不改，保留全部现名现值）
- **排版**：`--fs-xs(12)/sm(13)/md(14)/lg(16)/xl(20)/2xl(26)`、`--lh-tight/normal/relaxed`、`--fw-medium(500)/semibold(600)`。
- **纵深**：`--radius-lg(18)`、`--radius-pill(999)`、`--shadow-md/--shadow-lg`、`--focus-ring`(`0 0 0 3px var(--accent-soft)` + outline accent)、z-index scale `--z-sticky/--z-dropdown/--z-drawer/--z-toast`。
- **明色背景分离**：把浅色 `--bg` 压深一档（或 Card 改 `--border-strong`+`--shadow`），让卡片/内容区/侧栏三层可辨；暗色本已够，仅动浅色档。
- **布局尺寸**：`--shell-topbar-h(~52)`、`--shell-sidebar-w(~248)`、`--content-max(~1280)`、`--page-gutter`、`--sp-7(40)/--sp-8(48)`；新增 `--bg-hover`。
- **可达性**：`--text-faint` 仅用于装饰；次要正文改 `--text-soft`（或把 faint 压深到 ≥4.5:1）。补 `:focus-visible` 全局焦点环、`index.html` 加 `viewport-fit=cover`。
- 全部追加到**唯一** token 文件 `src/shared/styles/tokens.css`，不新建第二个 token 源、不引组件库。

### D5 · 布局/展示原语：抽公共件到 `shared/ui`，收敛重复；重写限外壳与页头
- **新增布局原语**（`src/shared/layout/`）：`AppLayout`、`AppShell`、`SidebarNav`、`TopBar`、`IdentityBar`。
- **新增展示原语**（`src/shared/ui/`，与现有 Card/Kv/Banner 同构，scoped + 仅消费 token）：`PageHeader`（面包屑+标题+描述+actions 槽）、`Toolbar`（搜索/筛选/操作行）、`Section`（带编号徽章/描述的分组头）、`EmptyState`（图标+标题+提示+可选操作）、`Button`（variant/size，收敛 8 处）、`Badge`/`StatusDot`（收敛 4 处）、`Field`（label+control+hint+error，收敛 .fg 三处 + 字段级校验）、`Segmented`（收敛 .seg/.chip 三处）。
- **页面改造限「套壳 + 加页头 + 换原语」**：各 console 页包 `PageHeader`（列表主操作「+新建」落页头右上；详情/编辑加面包屑替代孤立返回）、Editor 的 ①~⑥ `.sec-title` 升级为 `Section`、空/闲置态换 `EmptyState`。**页面内业务逻辑（校验/条件树/apiClient/分页过滤）零改动。**
- **明确 non-goal / 降级闸门**：`DataTable`（ListView 重写会触碰脆弱选择器 `.tr`）、`StatCard`/工作台概览仪表盘 = **本次不做**（属新功能 / 高风险），列 Phase 3 可选。

### D6 · 测试契约：testid 逐字保留；仅一处主动碰 E2E（消除最高危易碎点）
- **全部 `data-testid` 逐字保留**（换 DOM/class 可以，testid 锚点不动），尤其 e2e 实际断言子集。
- **脆弱的非 testid 选择器尽量保留原类名**：`.tr`（列表行，`e2e-dev` 用）、`.text-box`/`.no-body`（DemoPanel，`e2e-catalog` 用）——本次不重写 ListView 表格结构 / DemoPanel 内部，故这些类名保留、E2E 不受影响。
- **本次碰测试的完整清单（评审 B1/C4 修正「唯一一处」的口径）**：① `e2e-dev-v2.mjs`——给「买赠」chip 补 `type-chip-5`（当前无 testid、`e2e-dev` 靠 `.chip:has-text("买赠")` 定位，最高危易碎点）后换掉 L78 选择器；② **新增** `e2e-phone-smoke.mjs`（390×844 抽屉）并挂 npm script；③ `data-testid-contract.md` 补登记。**`e2e-tablet-smoke.mjs` 不改**（docked-768 保其零改绿）。均需用户授权（批准问题 B）。
- **保留清单以 `e2e/*.mjs` 脚本为真值源**（评审 I2：`data-testid-contract.md` 已过时、缺 `list-search/list-empty/cond-group/demo-panel-*/validation-errs` 等被实际断言的锚点）；改完双向 grep 比对「集合不变且无重复」。
- 逻辑/数据层（`logic.ts`/`authClient.ts`/`activityApi.ts`/`apiClient.ts`/stores/router 逻辑）零改动 → Vitest 三件套零回归。条件树沿用 `:key=node.id`（禁 index key），不重演旧「渲染期 mutation/丢焦点」坑。

### D7 · 响应式：桌面优先；**抽屉阈值 `<768`（评审 B1/B2 修订）**
- 断点正典（字面量约定写进 tokens.css 注释，CSS var 不能用于 @media）：**≥1024 桌面（侧栏常驻）/ 768–1023 平板（侧栏 docked 收窄、身份条内联）/ `<768` 手机（侧栏 off-canvas 抽屉 + 汉堡 + scrim）/ ≤560（身份条收 popover）**。
- **为何抽屉阈值是 `<768` 而非 768–1023**：`e2e-tablet-smoke.mjs`（768×1024）不做任何抽屉交互就直接点 `tab-new`、等 `tenant-bar` 可见；768 走抽屉/popover 会让这些锚点离屏/隐藏而超时红。docked-at-768 使该脚本**零改保持绿**（评审阻断项 B1/B2）。抽屉行为改由**新增的 390×844 phone smoke** 覆盖。
- 抽屉（仅 `<768`）：`position:fixed` + `transform:translateX(-100%)` 离屏（不改布局宽度 → body 无横向溢出）+ scrim + 锁 body 滚动 + Esc/点 scrim 关 + 路由跳转自动关 + 焦点管理（零新依赖）。z-index 数值序：`--z-sticky(100)<--z-dropdown(200)<--z-drawer(900)<--z-toast(1000)`——**抽屉 900 低于 ToastHost 硬编码 1000，ToastHost 零改仍在最上层**（评审 I4）。
- ≤560 逐区：列表卡片化**补字段标签**（现隐藏表头后值无标签）、条件树第 4 层缩进收敛、赠品行表沿用横滚容器、身份条换行不崩、Editor 提交用「表单末尾提交 + 顶部校验计数」避虚拟键盘（不做粘性底栏与键盘打架）。
- **触控统一**：tokens.css 加全局兜底 `@media (pointer:coarse){ button,select,input,textarea,[role=button],.nav-item{ min-height:var(--touch-min) } }`，一处堵住 EditorView/topbar/tabs/pager 的缺口；ListView 里 36px 统一成 44。
- 手机深度优化 = non-goal，仅「可读不崩」。

### D8 · 主题与杂项
- 沿用 `data-theme` + localStorage `drools-theme` 机制（挂 `<html>`，与外壳解耦）；toggle 从 App.vue 迁入 `TopBar`（保留 `theme-btn` testid；可选抽 `useTheme` composable，非必需）。
- **移除顶栏「旧页」死链**（指向 F3 已退役页）；移除 DemoNav 里跳回 console 的 external 链接（归属另一区）。
- 命名统一：产品名统一为「活动引擎控制台」，演示台标注「Drools 18 Step 演示台」为其子区标题。

## 待用户拍板的开放项（已给默认，批准时可调）
- **A**·范围深度：默认「Phase 0 token + Phase 1 外壳/IA + Phase 2 页头/原语一致性 + 响应式抽屉」为本次范围；DataTable/仪表盘为 Phase 3 可选（默认不做）。
- **B**·是否授权唯一一处碰 E2E（给「买赠」chip 补 testid + 改 e2e-dev）——默认「是」（消除最高危易碎点）；若「否」，Segmented 化 EditorView 类型选择器时保留 `.chip`+「买赠」文本，不动 E2E。
- **C**·演示台定位：默认「平级两大区、统一外壳、DemoNav 留 demos 次级导航」；可选「演示台降级为顶栏右侧开发者入口」。
- **D**·可达性目标：默认「补焦点环 + text-faint 不再用于正文 + tab role 语义化」这三项明确增益；不做全站 WCAG AA 逐项审计。

评审意见见 `REVIEW-FINDINGS.md`，实施计划见 `FINAL_PLAN.md`。
