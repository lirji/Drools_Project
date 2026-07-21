# FINAL_PLAN · 前端 UX 重设计（体验 / 流畅度 / 菜单自解释）+ Calm Precision 皮肤合并轮

> 2026-07-21。决策依据见同目录 `DECISION_RECORD.md`（D1–D11）。本计划**取代** `docs/plans/frontend-visual-refresh-0720-1404/`（其相容内容已吸收）。
> **获批前不改任何代码。** 分支建议：`feat/frontend-ux-redesign`。

## 0. 规模与节奏

按 **Phase A→G** 推进，每阶段独立可停、可回退：

| Phase | 内容 | 风险 |
|---|---|---|
| A | token 换代 + faint 对比度修复（单文件） | 极低 |
| B | Icon.vue + 外壳三件套增强（侧栏图标/说明/active 条、TopBar、IdentityBar） | 低 |
| C | 概览首页 `/home` + 路由重定向 | 低 |
| D | 交互一致性：ConfirmDialog + 反馈规范 + 登录页细节 | 低-中 |
| E | 断点统一 + ListView 卡片化 + 四页打磨（Field/Section 落地、条件树行内校验） | 中 |
| F | 演示台：DemoHome 目录页 + 人话文案 + DemoNav 打磨 | 低 |
| G | 流畅度：路由过渡 + FOUC 内联脚本 + toast 入场动画 | 低 |

建议 A+B+C 先到验收门绿（外观与导航焕然一新、首页落地），再 D/E/F/G。

## 1. Goals / Non-goals

**Goals**
- G-1 **菜单一眼可懂**：侧栏图标 + 每项一句说明 + active 左强调条；概览首页介绍两大区域并给快捷入口；演示台目录页人话化；消除「左侧/右侧」矛盾文案。
- G-2 **流畅度**：路由过渡（transform+opacity ≤160ms）、FOUC 消除、toast 入场动画、登录/提交按钮 loading 态。
- G-3 **交互一致**：反馈规范（toast=动作结果 / Banner=面板状态）、破坏性操作二次确认（自绘 ConfirmDialog）、条件树逐叶行内校验、字段级校验红框（Field 落地）。
- G-4 **Calm Precision 皮肤落地**（吸收 v2 计划）：token 换代（中性冷色阶 + indigo 强调 + 4 档阴影 + 圆角 10）、Icon.vue 内联 SVG 替换全站 emoji/字形、排版层级（页面标题 24–26/700、mono+tabular-nums）、`--text-faint` 修到 ≥4.5:1。
- G-5 **断点治理**：页面级断点收敛到正典 `<1024 / <768 / ≤560`；ListView `<1024` 卡片化补字段标签。
- G-6 **零业务回归**：路由 name 不改（`/home` 纯新增）、API/store/存储 key 不动、全部 data-testid 逐字保留（只增不改）、5 E2E + 3 Vitest 保绿、768/390 溢出 ≤4px。

**Non-goals**（详见 DECISION_RECORD）：不动后端/API；不收编 18-Step 目录进侧栏；catalog.ts 不进主包；768 保持 docked + tablet-smoke 零改；不引 UI 库/View Transitions/Suspense；不删改既有 testid 与 `.tr`/`.text-box`/`.no-body` class；token 只加不改名。

## 2. 视觉方向与设计参考（继承 v2 · Calm Precision）

参考映射沿用 `frontend-visual-refresh-0720-1404/FINAL_PLAN.md` §2（R1–R7：LangSmith 的中性底+mono 数据、Linear 的字重层级+侧栏 soft active+左强调条、Vercel 的可辨边框+方正、shadcn/ui 的 token 语义+三态、Supabase 的面板密度、Tremor 的 stat 卡（可选项）、lucide 线型图标）。

**Token 具体值**（Phase A 落 `frontend/src/shared/styles/tokens.css`，改值不改名 + 新增；暗色同名变量同步给值）：

```
--bg:#f4f5f7  --bg-elev:#fff  --bg-soft:#f7f8fa  --bg-hover:#eef0f4
--border:#e2e5ea  --border-strong:#cdd2db
--text:#1a1d26  --text-soft:#59606e  --text-faint:#6b7280   /* 白底 4.83:1 ✓（评审 S1：v2 计划的 #757c8a 实测仅 4.19:1 不达标） */
--accent:#4f46e5（假设，备选 cobalt/violet）  --accent-soft:#eef2ff
新增：--accent-hover:#4338ca  --accent-line:#c7d2fe  --fw-bold:700  --z-modal:950
--shadow-sm/-/-md/-lg 四档全部按语义启用（值见 v2 计划 §3）
--radius:10px  --radius-sm:7px  --radius-lg:14px（假设，可回退 14/9/18）
断点正典（注释声明 + 全站执行）：<1024 塌单列 / <768 抽屉 / ≤560 极窄
```

**待用户验收确认的假设**：indigo 强调色、圆角 14→10（均单文件改值可回退）。

**对比度验收口径**（评审 S1）：faint/soft 文字按**实际底色**（`--bg`/`--bg-soft`）实测 ≥4.5:1，不按纯白；暗色档同样实测，不照抄 v2 数值。

**Icon 清单（B 阶段范围与「清零」验收依据，评审 M3）**：`menu`(☰ 汉堡)、`sun`/`moon`(◐ 主题)、`logo`(◆ 品牌 mark)、`chevron-down`(▾ IdentityBar)、`log-in`(🔐 登录)、`scale`(⚖ 验证空态)、`home`(概览)、`list`(活动列表)、`plus`(＋ 新建)、`badge-check`(优惠验证)、`flask`(演示台)、`x`(✕ 删除/关闭)、`trash`(🗑 删组)、`play`(▶ 发送请求)、`alert-triangle`(确认弹窗)、`inbox`(◍ EmptyState 通用)。共 16 个 lucide 线型手抄 path。`EmptyState` 的 `icon` prop 改收 Icon name（保留字符串字形回退兼容，调用点同步更新）。

## 3. 路由与页面流

```
/            → redirect /home                    （改，原指 /console）
/home        → HomeView（新增，懒加载，受 auth 守卫保护）
/console     → redirect /console/activities     （不动）
/console/... → 四页不动（activities / new / :id / :id/edit / validate）
/demos       → DemoHome（升级为分组目录页）
/demos/:id   → DemoPanel（不动）
/login /auth/callback → 不动（bare 裸壳）
/:pathMatch  → redirect /home                   （改，原指 /console）
```

页面流：首访 → `/home`（两区介绍卡 + 快捷入口 + 最近活动）→ 点卡片进列表/新建/验证/演示台。演示台：`/demos` 目录页（分组卡片，人话标题）→ 点 Step 进 `/demos/:id`（DemoNav 二级导航仍在左列）。e2e 全部直跳 `/ui/console`、`/ui/demos/:id`，不受影响（勘察确认零脚本走裸根路径）。

## 4. 组件树（复用 vs 新建）

| 层 | 文件 | 动作 |
|---|---|---|
| Token | `shared/styles/tokens.css` | **改值+新增**（A） |
| **新建** | `shared/ui/Icon.vue` | 内联 SVG 原语：`props{name,size?}`，本地 path map（lucide 线型手抄），默认 `aria-hidden`，透传 attrs（B） |
| **新建** | `shared/ui/ConfirmDialog.vue` | `role="dialog"` `aria-modal`，props{title,body,confirmText,danger?}，Esc/scrim 关，Promise 化 `open()` **单例化**（新开自动取消旧挂起，评审轻微 7）；弹窗开时 AppShell 抽屉 Esc 让位；`--z-modal:950`（D） |
| **新建** | `shared/useScrollLock.ts` | 计数式 body scroll-lock 小工具；AppShell 抽屉与 ConfirmDialog 共用，替代两处直写 `body.overflow` 互踩（评审 M4）（D） |
| **新建** | `shared/ui/PageTransition.vue` | 路由过渡包装件（v-slot + `<transition name="page" mode="out-in">`，transform+opacity ≤160ms）；**AppShell / ConsoleShell / DemoShell 三处出口统一接入**——console 五页与 demos 面板切换走嵌套出口，只挂 AppShell 不生效（评审 S2）（G） |
| **新建** | `home/HomeView.vue` | 概览首页：区域介绍卡×2 + 快捷入口 + 最近活动（复用 activityApi.listActivities + useDictStore + Skeleton/Banner/EmptyState/Badge/Card）。定死放 `home/`——`views/` 仅放裸壳页（评审轻微 1）（C） |
| 外壳 | `shared/layout/SidebarNav.vue` | 每项 Icon + 第二行小字说明 + active 左 3px 条、演示台组文案改为指向目录页（B）；「概览」入口 `nav-home` **随 Phase C 路由同 commit 加**（评审 M1：避免指向未注册路由）；**testid `nav-console/nav-demos/tab-*` 逐字保留** |
| 外壳 | `shared/layout/TopBar.vue` | 品牌 mark 变 `/home` 链接；汉堡/主题按钮换 Icon；theme-btn 补 `aria-label`+`aria-pressed`；**`nav-toggle`/`theme-btn` 保留**（B） |
| 外壳 | `shared/layout/IdentityBar.vue` | chip 质感 + `▾`→Icon；**全部 testid + 561/560 内联/popover 行为不动**（B） |
| 外壳 | `shared/layout/AppShell.vue` | 顶层出口接 PageTransition（仅覆盖跨区切换）；抽屉 scroll-lock 迁 `useScrollLock`；状态机其余不动（D/G） |
| 出口 | `console/ConsoleShell.vue`、`demos/DemoShell.vue` | 嵌套 RouterView 改 v-slot + PageTransition（ConsoleShell 从 `<RouterView/>` 简写展开）（G） |
| 路由 | `router/index.ts` | `+/home` 路由、`/` 与 catch-all 改指 `/home`；**新增 `scrollBehavior`**（切页滚顶、后退恢复位置，评审轻微 6）；守卫不动（C/G） |
| auth | `auth/authClient.ts`、`views/LoginView.vue` | returnTo 兜底默认值 `'/console'`→`'/home'`（改值不改 sessionStorage key；直接访问 /login 无 query 时登录后落概览，评审 M5） |
| 原语 | `shared/ui/{Button,Badge,Card,EmptyState,Segmented,Banner,ToastHost,Skeleton,PageHeader}.vue` | 三态补齐（hover/active/focus）、纵深、Icon 接入、toast 入场动画、Segmented 补 `aria-pressed`（B/D/G 分摊） |
| 原语落地 | `shared/ui/{Field,Section}.vue` | **启用死原语**：接管 EditorView/ValidateView 裸 `.fg label`/`.sec-title`（E） |
| console | `pages/ListView.vue` | `<1024` 卡片化（补字段标签、**行根保留 `.tr` class**）；上下线接 ConfirmDialog；Badge 状态点（D/E） |
| console | `pages/EditorView.vue` | Field/Section 迁入；三组裸 chip 统一 Segmented（补新增 testid）；离开守卫换 ConfirmDialog；断点 980→1023（D/E） |
| console | `pages/{DetailView,ValidateView}.vue` | mono 数据、traces 时间线、空态 Icon；断点 980→1023；Validate `.fg` `<768` 塌 1 列（E） |
| console | `console/condition-tree/{ConditionLeaf,ConditionGroup}.vue` + `console/logic.ts` | `invalidNodeIds()` 纯函数 + 叶子 `.has-error` 红框 + 行内原因（失焦/提交后显）；**错误集以可选 prop（默认空集）下发或叶子本地 touched 态自算，不引入 store/必填 prop**（保 `ConditionGroup.test.ts` 独立挂载零警告，评审 M6）；**testid 与元素语义逐字保留**（E） |
| demos | `DemoHome.vue` | 升级分组卡片目录页（人话标题+行话副标题+Step 直达）；`demo-home` testid 保留（F） |
| demos | `catalog.ts` | 仅 GROUPS title/subtitle 文案改（**id/group/结构冻结**）；**只做字符串字面量替换，禁止整文件重排/重新格式化**——e2e 正则对引号/键序/换行敏感且失败模式是静默假绿（评审 M7）（F） |
| demos | `DemoNav.vue`、`DemoPanel.vue` | 分组标题同步；方法圆点补 GET/POST 文字徽记；面板质感；`.text-box`/`.no-body` class 保留（F） |
| views | `LoginView.vue` | 按钮 loading/禁用态；emoji→Icon（D） |
| 入口 | `index.html` | `<head>` 内联主题回读脚本消 FOUC——**普通 `<script>` 非 module**（Vite 会把内联 module 抽成外部 chunk 使 FOUC 消除失效，评审轻微 2）；main.ts 原逻辑保留（幂等）（G） |
| 文档 | `e2e/data-testid-contract.md` | 顶表位置栏更新 + 登记新增 testid（G） |

## 5. 状态与边界（逐页）

- **HomeView（新）**：loading=Skeleton；error=Banner+重试（后端不可达时页面自身降级，不白屏——对齐 App.vue 门控降级）；empty=EmptyState+「新建活动」CTA；success=最近活动行（点击进详情）。介绍卡为静态内容不依赖请求，**请求失败时快捷入口仍可用**。
- **ListView**：三态已齐（保持）；新增确认弹窗的 pending 态（确认按钮 loading，防双击）。
- **EditorView**：既有校验/409/幂等/成功卡不动；新增字段级 has-error（Field）+ 条件树逐叶红框；离开确认弹窗替换原生 confirm（取消=留在页）。
- **DetailView/ValidateView/DemoPanel**：三态已齐，仅视觉打磨；Banner 语义不变。
- **LoginView**：新增点击后按钮 loading+禁用（防重复跳转）；beginLogin 异常时恢复可点。
- **过渡动画**：`prefers-reduced-motion` 全局规则自动降级为即时切换（已验证 tokens.css:158 规则覆盖 CSS transition）。
- **ConfirmDialog**：打开时焦点移入、Esc/scrim=取消；scroll-lock 走计数式 `useScrollLock`（与抽屉共用，避免两写者互踩）；弹窗开时抽屉 Esc 让位；`open()` 单例化（新开自动取消旧挂起）；z 序 drawer 900 < modal 950 < toast 1000。抽屉开着经导航触发弹窗时：抽屉先关（SidebarNav emit navigate 即关）、弹窗后开，现有时序保证。
- **401 与离开确认并发**：脏编辑器遇 401 登出兜底会被离开确认拦一下——与现状原生 confirm 行为一致，非回归；可选优化（离开守卫加 `!auth.loggedIn` 直放）不列硬门（评审轻微 8）。

## 6. API 契约（零新增，全部复用）

- HomeView 最近活动：`GET /activity-marketing/list`（`activityApi.listActivities`，按租户 header 隔离），前端取前 8 条；标签走 `useDictStore`（`/field-dict`）。
- 其余页面 API 一律不动；DemoPanel 仍走 `api('root', ...)` 散点端点。
- header 注入 / 401 处理 / silent refresh 机制零改动。

## 7. 响应式与移动端适配策略

**断点表（治理后正典，字面量）**：

| 档 | 条件 | 行为 |
|---|---|---|
| 桌面 | ≥1024 | 侧栏 docked 248px；多栏布局；ListView 表格 |
| 平板 | 768–1023 | 侧栏 docked（**不动，保 tablet-smoke 零改**）；**所有多栏塌单列**（原 980）；**ListView 卡片化**（原 760 表格降列 → 卡片补字段标签） |
| 手机 | <768 | 侧栏 off-canvas 抽屉（不动）；DemoShell 目录纵向堆叠（不动） |
| 极窄 | ≤560 | IdentityBar popover（不动） |

- **逐页小屏**：HomeView 介绍卡/快捷入口 `<1024` 塌单列、390 下全部单列；ListView 卡片 390 下一列；Editor/Detail/Validate/DemoPanel 断点值 980→1023 语义不变；ValidateView `.fg` 补 `<768` 塌 1 列。
- **交互替换**：无 hover-gated 功能（勘察确认）；ConfirmDialog 按钮 ≥44px（吃全局 pointer:coarse 兜底）；侧栏说明文字在抽屉内同样显示（抽屉宽 min(85vw,320px) 容得下两行项）。
- **移动端验收硬门**：768 & 390 下 `body.scrollWidth - innerWidth ≤ 4px`（含新增 `/ui/home`）；44px 触控保留；抽屉滑入/scrim/锁滚/safe-area 不动；侧栏加说明文字后 248px 宽度不变（说明文字小字号换行，不撑宽）。
- **风险点**：侧栏第二行说明在 768 docked 下的纵向长度（6 项×2 行仍远小于视口高，可控）；ListView 卡片化后 `.tr` 元素语义保持（display 改 grid→flex 卡片，class 名不动）；**HomeView 最近活动行渲染 mono 活动 ID，必须沿用 ListView 的 `min-width:0` + `overflow-wrap:anywhere` 教训**，护住 390 下 `/ui/home` ≤4px 硬门（评审轻微 9；`/ui/home` 深链已核由 `SpaForwardController` 兜底可直达）。

## 8. 文件级改动清单

1. `frontend/src/shared/styles/tokens.css` — 色阶/阴影/圆角/排版改值 + 新增 `--accent-hover/--accent-line/--fw-bold/--z-modal`（A）
2. `frontend/src/shared/ui/Icon.vue` — **新建**（B）；`Icon.test.ts` **新建**
3. `frontend/src/shared/layout/{SidebarNav,TopBar,IdentityBar}.vue` — 图标/说明/active 条/aria（B）
4. `frontend/src/home/HomeView.vue` — **新建**（C）；`frontend/src/router/index.ts` — `/home` + 两处 redirect + `scrollBehavior`（C/G）；`frontend/src/auth/authClient.ts` + `LoginView.vue` — returnTo 兜底默认值改 `/home`（C）
5. `frontend/src/shared/ui/ConfirmDialog.vue` + `frontend/src/shared/useScrollLock.ts` — **新建**（D）；`ConfirmDialog.test.ts` **新建**；`frontend/src/shared/ui/PageTransition.vue` — **新建**（G）；`frontend/src/console/ConsoleShell.vue`、`frontend/src/demos/DemoShell.vue` — 接 PageTransition（G）
6. `frontend/src/console/pages/{ListView,EditorView,DetailView,ValidateView}.vue` — 确认弹窗/Field·Section 落地/卡片化/断点统一/打磨（D/E）
7. `frontend/src/console/logic.ts` — `invalidNodeIds()` 纯函数（E）；`logic.test.ts` 补用例
8. `frontend/src/console/condition-tree/{ConditionLeaf,ConditionGroup}.vue` — 行内错误态（E）
9. `frontend/src/demos/{DemoHome,DemoNav,DemoPanel}.vue` + `catalog.ts`（仅文案）（F）
10. `frontend/src/views/LoginView.vue` — loading 态 + Icon（D）
11. `frontend/src/shared/layout/AppShell.vue` — 路由过渡（G）；`frontend/index.html` — FOUC 内联脚本（G）
12. `frontend/src/shared/ui/{Button,Badge,Segmented,Banner,ToastHost,EmptyState,PageHeader,Card}.vue` — 三态/Icon/动画增量（B–G 分摊）
13. `frontend/e2e/e2e-dev-v2.mjs` — **追加**首页着陆断言块；`e2e-phone-smoke.mjs` — **追加** `/ui/home` 溢出断言；`e2e-catalog-v2.mjs` — **追加** `ids.length ≥ 30` 下限断言（防 catalog 格式变动导致抽取为空的静默假绿，评审 M7）（既有断言零改）
14. `frontend/e2e/data-testid-contract.md` — 更新位置栏 + 登记新增 testid（`nav-home`/`home-view`/`confirm-dialog`/`confirm-ok`/`confirm-cancel` 等）

## 9. 按依赖排序的实施步骤

1. **A**：`tokens.css` 改值+新增 → `typecheck`+`build` 绿，全站换肤基线目检（明+暗）。
2. **B1**：`Icon.vue` + 单测。
3. **B2**：外壳三件套接 Icon + 侧栏说明/active 条 + aria（同 commit；**不含 nav-home**）→ vitest 绿 + 768/390 截图核 4px。
4. **C**：`HomeView.vue` + 路由改（`/home`+redirect+scrollBehavior）+ 侧栏 `nav-home` 入口 + returnTo 兜底默认值（同 commit，评审 M1）→ 手动核首跳 `/ui/` → home、后端停机时 home 不白屏、深链 `/ui/home` 可达。
5. **D**：`ConfirmDialog.vue`+单测 → ListView 上下线接入 → EditorView 离开守卫替换 → LoginView loading。
6. **E**：Field/Section 落地 EditorView/ValidateView → `invalidNodeIds` + 条件树行内校验（先跑 `ConditionGroup.test.ts` 确认绿）→ ListView 卡片化 + 全站断点统一。
7. **F**：catalog 文案 + DemoHome 目录页 + DemoNav/DemoPanel 打磨 → `e2e:catalog` 实跑绿。
8. **G**：PageTransition 落**三处出口**（AppShell/ConsoleShell/DemoShell）+ FOUC 内联脚本（非 module）+ toast 动画 + scrollBehavior + contract.md 更新。每落一处出口跑一次 `e2e:dev` 核时序。
9. **验收门**（每阶段后跑增量，最终全量）：见 §11。

## 10. 测试策略（含移动端视口矩阵）

- **Vitest**：既有 3 个保绿（ConditionGroup 是条件树改动的守门员）；新增 `Icon.test.ts`（name→svg 渲染、attrs 透传）、`ConfirmDialog.test.ts`（Esc/scrim/confirm 回调、aria 属性）、`logic.test.ts` 补 `invalidNodeIds` 用例（空树恒通过/缺值命中/嵌套组）。
- **E2E 视口矩阵**（起 console `-Pfrontend` + dev 档 :8097 实跑）：`e2e:dev`（桌面 + **新增首页块**：goto `/ui/` → `home-view` → 快捷入口点到 `list-view`）/ `e2e:oidc`（auth :8099）/ `e2e:catalog`（33 demo 全跑 + `→`/`.text-box`/`.no-body` 断言）/ `e2e:tablet`（768 docked 溢出 ≤4）/ `e2e:phone`（390 抽屉溢出 ≤4 + **新增** `/ui/home` 溢出断言）。**全绿为门。**
- **体积门**：build 后核对 chunk 列表——catalog 仍为独立懒 chunk、主 chunk gz 相对 43.9KB 基线增量 <8KB（Icon+ConfirmDialog+HomeView 路由表增量）。
- **动效降级**：手动（或 playwright `emulateMedia({reducedMotion:'reduce'})` 快速核）确认过渡被禁用、导航即时。
- **视觉自查**：明+暗 × Home/四控制台页/目录页/DemoPanel/Login 手过；≤560 popover 档人工核。

## 11. 验收标准

- [ ] 5 E2E（含新增断言块）全绿；Vitest（3 旧 + 3 新）全绿；`vue-tsc` 0 错。
- [ ] **移动端**：768 & 390 视口下 `/ui/console`、`/ui/home` 均 `body.scrollWidth - innerWidth ≤ 4px`；44px 触控保留；抽屉行为不变。
- [ ] 首跳 `/ui/` 落概览首页；后端停机时首页降级显示错误态而非白屏；快捷入口全部可达。
- [ ] 侧栏每项有图标+一句说明；active 项有左强调条；「演示台」入口与目录页文案一致（无左/右矛盾）。
- [ ] 18-Step 目录页分组主标题为人话、副标题保留术语；DemoNav 方法有文字徽记。
- [ ] 上/下线与编辑器离开均弹自绘确认框（Esc/scrim 可取消）；原生 `window.confirm` 清零。
- [ ] 条件树错误可定位到具体叶子（红框+原因）；EditorView 字段级红边生效。
- [ ] **装饰性** emoji/几何字形清零（统一 Icon.vue，按 §2 清单核对）；**豁免**：OrderSummary `→`（e2e 硬断言）及「← 返回列表」「＋ 新建活动」类文案性字符（建议换 Icon+文字，不列硬门）（评审 M2）；`--text-faint` 对比度 ≥4.5:1 **按实际底色实测**；页面标题 ≥24px/700。
- [ ] 全部既有 data-testid 逐字保留（diff 中零删除）；`.tr`/`.text-box`/`.no-body` 保留；catalog 仍在独立懒 chunk；主 chunk gz 增量 <8KB（归因：Icon + 外壳增量；HomeView/ConfirmDialog 均落懒 chunk，评审轻微 5）。
- [ ] 路由过渡在 console 页间切换（tab-list↔tab-new 等嵌套出口路径）真实生效，且 `prefers-reduced-motion` 下即时切换。
- [ ] 明+暗双主题手过无错色；`prefers-reduced-motion` 下动效禁用。

## 12. 风险与回滚

- **风险**：① 条件树 DOM 改动碰 `ConditionGroup.test.ts`（缓解：只加错误态样式与文案节点，不动 testid/元素语义，每步跑单测）；② ListView 卡片化碰 e2e `.tr` 计数（缓解：行根元素 class 不改名，先跑 `e2e:dev` 核）；③ 路由过渡落在三出口后，e2e 的 tab 切换（dev/oidc/tablet/phone 的 `tab-list→tab-new`）正走嵌套出口，`waitForSelector` 时序抖动从理论变为现实路径（缓解：`mode="out-in"` + ≤160ms + transform/opacity；仍抖则缩到 100ms 或降为仅淡入，每落一处出口即跑 `e2e:dev` 验证，评审 S2）；④ `/` redirect 改动的未知消费方（缓解：勘察已确认零 e2e/零内链依赖裸根路径；回退=改回一行）；⑤ 暗色下新阴影/强调色需另调（v2 计划已给暗色配套值）；⑥ Icon 手抄 path 出错（缓解：Icon.test + 逐个目检）。
- **回滚**：分支开发，Phase 粒度 commit。A 单文件 `git checkout tokens.css` 秒退；C 回退=删路由三行+删 HomeView；D/E/F 按 commit 回退；G 各项独立可摘。最难回滚的是 E（多文件断点统一），故 E 放后段且单独 commit。
