# FINAL_PLAN · 前端控制台重设计（可读性 + 信息架构）

> 2026-07-20。决策依据见同目录 `DECISION_RECORD.md`（D1–D8）。**本计划获批前不改任何代码。**
> 形态：纯前端（`frontend/src` + `index.html`），路由 / API 契约 / 业务逻辑零改动；重心在**统一生产级外壳 + 逐页页头 + 设计系统扩展 + 响应式抽屉**。

## 0. 规模与节奏（诚实）
按 **Phase 0→1→2** 推进，每阶段自成可停的绿色状态；**Phase 3 为可选降级闸门**（默认不做）。建议先做 **Phase 0（token 地基，零风险）+ Phase 1（外壳/IA，收益最大）**，到验收门全绿后再做 Phase 2（一致性打磨）。

## 1. Goals / Non-goals

**Goals**
- G-1 用**单一持久左侧栏 + 顶部工具条 + 内容区**的生产级外壳，取代当前「双壳 + 双层导航 + 首屏身份条」，让功能分区一眼可辨。
- G-2 设计 token **只加不改**地补齐排版/纵深/焦点/布局尺寸 + 加大明色背景分离，提升可读性；补全局焦点环与触控兜底。
- G-3 抽公共展示原语（PageHeader/Toolbar/Section/EmptyState/Button/Badge/Field/Segmented）收敛 8+ 处重复样式，各页套页头/面包屑。
- G-4 采纳 1024/768/560 三档断点（收敛现有 760/900/980），`<1024` 侧栏 off-canvas 抽屉，`e2e-tablet-smoke` 无横向溢出保持绿。
- G-5 **零业务回归**：路由/API/store/逻辑不动，全部 data-testid 逐字保留，Vitest + 4 个 E2E 保持绿（仅一处主动改 E2E，见 D6/B）。

**Non-goals**
- 不加新功能、不改路由 name/path 与 API 契约、不动条件树语义 / field-dict 白名单 / 四眼 / 多租户机制 / 校验与提交逻辑。
- 不引 UI/图标/CSS 组件库（继续手写 scoped SFC + tokens.css）。
- 不做 `DataTable`(ListView 表格重写)、`StatCard`/工作台概览仪表盘、服务端分页排序（Phase 3 可选，默认不做）。
- 手机（≤560）仅「可读不崩」，非独立移动产品；不做 PWA/手势。
- 不改后端、不改 Casdoor 登录页。

## 2. 路由与页面流（**完全不变**）
`router/index.ts` 一字不改。`/`→`/console`→`/console/activities`；`/console`(ConsoleShell 子路由 activities/new/:id/edit/validate)；`/demos`(DemoShell 子路由 home/:demoId)；`/login`、`/auth/callback` 顶层。
- 外壳分流：`App.vue` 对**应用路由**渲染 `<AppShell>`、对 `login/callback` 渲染裸 `<router-view>`（登录页无侧栏）。判定用 `route.name in {login,callback}`（不动 router 文件，路由零破坏）。
- 页面流不变量：dev 档进 `/console` 直接可用；auth 档未登录→`/login`；登录后回 returnTo；401→清 token→`/login`（watch 上提到 AppShell）。

## 3. 组件树（复用现有 vs 新建）
```
App.vue（改：应用路由包 AppShell / login·callback 裸渲染；主题 toggle 迁走）
├─ AppShell.vue（新 · shared/layout/）        ← 承 401 watch + doLogout（自 ConsoleShell 上提，覆盖两区）
│  └─ AppLayout.vue（新 · CSS grid 原语，slot: #sidebar/#topbar/默认=内容）
│     ├─ SidebarNav.vue（新）  ← nav-console/nav-demos + tab-list/tab-new/tab-validate（testid 逐字迁自 ConsoleShell）；窄屏→抽屉
│     ├─ TopBar.vue（新 · 瘦）
│     │  ├─ IdentityBar.vue（新）  ← auth-bar/tenant-bar/actor-bar 逐字迁自 ConsoleShell（复用 3 store，零改），窄屏收进 popover
│     │  ├─ theme-btn（自 App.vue 迁；保留 testid）
│     │  └─ 汉堡按钮（新 testid，<1024 出现，切抽屉）
│     ├─ <router-view/>（level-0）
│     │  ├─ ConsoleShell.vue（瘦身：删身份条/tabs/watch/logout，仅留 <router-view/> outlet）
│     │  │  └─ ListView / EditorView / DetailView / ValidateView（复用，套 PageHeader/Section/EmptyState，逻辑不动）
│     │  └─ DemoShell.vue（复用）→ DemoNav（复用·原样，懒加载不动）+ DemoPanel/DemoHome（复用）
│     └─ ToastHost.vue（复用，自 App.vue 迁入 AppShell）
└─ <router-view/>（bare）→ LoginView / CallbackView（复用，不动）

shared/ui 新增展示原语：PageHeader / Toolbar / Section / EmptyState / Button / Badge · StatusDot / Field / Segmented
shared/styles/tokens.css：扩展（排版/纵深/焦点/布局尺寸/明色分离/coarse 兜底）
```
**复用零改**：`logic.ts`/`activityApi.ts`/`apiClient.ts`/`useToast.ts`/`types.ts`/`catalog.ts`/`summaries/*`/全部 store/`authClient.ts`/`Card·Kv·Banner·Skeleton·ToastHost`/`DynRowTable`/`condition-tree/*`。

## 4. 状态与边界（逐页；现状 → 重设计后，逻辑不变仅呈现升级）
| 页面 | loading | empty | error | 其它边界 |
|---|---|---|---|---|
| 列表 ListView | Skeleton（保留） | `EmptyState`（替一行灰字；区分「无数据+新建 CTA」vs「无匹配」） | Banner+重试（保留 `list-error`） | 保 `.tr` 类名与 `activity-row-{id}`；上下线补行内 disable（可选，不改逻辑）；筛选态保 URL |
| 详情 Detail | Skeleton（保留） | — | Banner | 加面包屑替孤立返回；卡片包 `Section`；`generatedDrl` 专家级折叠 |
| 编辑 Editor | **补 loadForEdit loading 态**（现无，先显默认值）→ Skeleton/禁用 | — | Banner + 409 特判（保留 `conflict-hint`） | ①~⑥ `.sec-title`→`Section`；字段级校验用 `Field.error`（保留右栏汇总 `validation-errs`）；离开拦截换设计系统弹层（替原生 confirm，可选）；类型「买赠」chip→`Segmented`+补 `type-chip-5`（见 §7 F2b/D6-B） |
| 验证 Validate | busy 换 Skeleton（现为 Banner「查询中」） | 无命中态用 `EmptyState` | Banner（保留 `v-error`） | 双按钮防并发（保留） |
| 条件树 | 预览 pending（保留） | 空树恒通过提示 | 预览失败定位（保留 `cond-group` 等全 testid） | `:key=node.id` 不动；≤560 缩进收敛 + 叶子纵堆 |
| 登录/回调 | 跳转中 | 无 webClients 横幅 | state 不匹配→toast（保留 `login-page`/`login-{tenant}`/`callback-page`） | 裸壳无侧栏 |
| 演示面板 | 请求中（保留） | — | 保留 `.text-box`/`.no-body`/`demo-error` | DemoNav/DemoPanel 结构不动（e2e-catalog 依赖） |
| 横切 | — | — | 401→清 token→/login（watch 上提 AppShell） | ToastHost 挂 AppShell 一份；`--z-toast` 与抽屉分层 |

## 5. API 契约
**完全不变**。不动 `apiClient` service 注册表（root/marketing）、`/activity-marketing/*`、18-Step 端点、`field-dict`、`/decision/v1`、header 注入规则（auth 档不发 X-Tenant-Id）、storage key（`actTenant/actActor/drools-theme/actOidc*`）。`types.ts` 不改。

## 6. 响应式与移动端适配策略
**断点正典**（字面量约定，tokens.css 顶部注释；收敛现有 760/900/980）。**关键修订（评审 B1/B2）：抽屉阈值定在 `<768`，平板 768–1023 侧栏保持 docked（仅收窄）+ 身份条内联**——因为 `e2e-tablet-smoke.mjs`（768×1024）**不做任何汉堡/抽屉交互**就直接点 `tab-new`、等 `tenant-bar` 可见；若 768 走抽屉/popover，这些锚点离屏/隐藏即超时红。docked-at-768 让该脚本**零改动保持绿**。

| 档 | 范围 | 侧栏 | 身份条 | 内容网格 | 触控 |
|---|---|---|---|---|---|
| desktop | ≥1024 | 常驻 docked（`--shell-sidebar-w`） | 内联 | 表单 2 列+rail、详情/验证/demo 2 列 | 鼠标基线 |
| tablet | 768–1023 | **docked（收窄，不抽屉）** | **内联可见（`tenant-bar`/`auth-bar` 不折叠）** | 全部单列；行表横滚 | coarse 起 44px |
| phone | **<768** | **off-canvas 抽屉 + 汉堡 + scrim** | ≤560 收进 popover；560–767 内联换行 | 单列 | `(pointer:coarse)` 全量 44 |
| phone-narrow | ≤560（叠加） | 抽屉 ~85vw | **IdentityBar 收进 popover** | 列表卡片化(补字段标签)、条件树最小缩进、赠品行表横滚 | 44 |

- 抽屉（仅 `<768`）：`transform:translateX` 离屏（不改布局宽度）+ scrim + 锁滚 + Esc/点 scrim 关 + 路由跳转自动关 + 焦点管理（零新依赖）；汉堡按钮补新 testid（如 `nav-toggle`，不在既有契约，安全）。
- **IdentityBar popover 阈值钉死 ≤560**（该档无任何自动化 e2e，仅 360×640 人工走查）；≥768 `tenant-bar`/`auth-bar` 保持内联可见（守 tablet-smoke L14/L16）。
- **C2 约束**：actor 文本必须仍在带 `data-testid="auth-bar"` 的元素**内部**（`e2e-oidc-v2.mjs:52` 断言 auth-bar innerText 含 `act-alice`）。
- `index.html` 补 `viewport-fit=cover`；ToastHost 加 `env(safe-area-inset-*)` + `min(360px, calc(100vw - 2*sp-4))`；固定/粘性件用 `100dvh`。
- 触控兜底：tokens.css 全局 `(pointer:coarse)` min-height 44（堵 EditorView chip/mini、theme-btn、tabs、pager 缺口）。
- **移动端验收**（§9）：768×1024 `e2e-tablet-smoke` 零改保持绿（docked 侧栏，表单可提交、无横向溢出）；**新增** phone 视口 smoke（如 390×844）验汉堡→抽屉→点导航项；360×640「可读不崩」人工走查。

## 7. 文件级改动清单 + 实施步骤（按依赖排序）

**Phase 0 · token 地基（零风险，先行）**
| 文件 | 改动 |
|---|---|
| `src/shared/styles/tokens.css` | 追加排版 scale（fs/lh/fw）、纵深（radius-lg/pill、shadow-md/lg、focus-ring、z-index scale）、布局尺寸（topbar-h/sidebar-w/content-max/page-gutter/sp-7/sp-8）、`--bg-hover`、加深浅色 `--bg` 分离；加全局 `:focus-visible` 焦点环 + `(pointer:coarse)` 44 兜底 + 断点注释约定 |
| `index.html` | viewport 补 `viewport-fit=cover` |

**Phase 1 · 生产级外壳 + IA（收益最大；testid 逐字迁移）**
| 步 | 改动 |
|---|---|
| F1a | 新增 `src/shared/layout/AppLayout.vue`（grid slot 原语）、`SidebarNav.vue`（收编 nav-*/tab-* testid + active 态 + `<768` 抽屉）、`TopBar.vue`（瘦条 + 汉堡`nav-toggle` + 主题；**迁入 App.vue onMounted 回读 data-theme 校准 dark 态**，见 I6）、`IdentityBar.vue`（逐字承接三条身份条 + 全部身份 testid，**actor 文本留在 auth-bar 元素内**，popover 仅 ≤560）、`AppShell.vue`（组合 + 承 401 watch/logout；**分流门槛加 `router.isReady()`**，resolve 前不渲染 chrome，避免登录页闪外壳/dev 身份条，见 I5） |
| **F1b+F1c（不可分割的一次变更，同 commit）** | `App.vue` 应用路由包 `<AppShell>`、login/callback 裸渲染、移除「旧页」死链、ToastHost 迁入 AppShell **与** `ConsoleShell.vue` 瘦身为 `<router-view/>` outlet（同步删身份条/tabs/watch/logout）**必须一次做完**——否则中途 `tab-*`/`tenant-bar`/`auth-bar`/ToastHost/401-watch **各出现两份**，触发 Playwright strict-mode「2 elements」+ 双重 router.push（评审 I3）。`DemoShell.vue` 套统一壳；**`DemoNav.vue` 删 external 组渲染**（跳回 console 的 `demo-nav-activity`，无 e2e 断言，安全）——注意此项动 `DemoNav.vue`+`catalog.ts:52`，非「零改」，但不动 `demo-nav-{id}`/`demo-panel-*` |
| F1d | 响应式抽屉：**`<768`** 汉堡+抽屉+scrim+锁滚+Esc/路由关；断点统一为 **≥768 docked / <768 抽屉 / ≤560 身份 popover**（收敛现有 760/900/980） |

**Phase 2 · 页头 + 展示原语一致性**
| 步 | 改动 |
|---|---|
| F2a | 新增 `shared/ui/{PageHeader,Toolbar,Section,EmptyState,Button,Badge,StatusDot,Field,Segmented}.vue`（scoped + 仅 token） |
| F2b | 各 console 页套 `PageHeader`（列表主操作/详情·编辑面包屑）+ 空态换 `EmptyState`；Editor ①~⑥→`Section`、字段→`Field`、类型选择器→`Segmented`（**补 `type-chip-5` testid + 同步 `e2e-dev-v2.mjs`/契约**，见 D6-B）；按钮/徽章/段控替换为 `Button/Badge/Segmented`（保留脆弱类名 `.tr`/`.text-box`/`.no-body` 不动，保留全部 testid） |
| F2c | demos 区：DemoHome 用 `EmptyState`、DemoPanel head 用 `Badge`（结构/ testid 不动） |

**Phase 3 · 可选（默认不做，降级闸门）**
ListView `DataTable` 化（触 `.tr`，需改 e2e-dev）、StatCard/工作台概览。仅在 Phase 1+2 全绿且用户点头后议。

## 8. 测试策略（含移动端视口矩阵）
- **每阶段**：`npm run typecheck`（vue-tsc）+ `npm test`（Vitest 三件套：authClient/logic/ConditionGroup——纯外壳/CSS 改动零影响，必须保持 26 绿）。
- **Phase 1/2 后跑全套 E2E**（需后端起 dev :8097 / auth :8099 + Casdoor :8000 + 前端 bundle 进 static/ui）：`e2e:dev`、`e2e:oidc`、`e2e:catalog` + `node e2e/e2e-tablet-smoke.mjs`（768，docked 侧栏，**零改保持绿**）。
- **保留基线以 `e2e/*.mjs` 脚本为真值源**（`grep -rhoE 'data-testid="[^"]+"' e2e src` 双向比对），**不以过时的 `data-testid-contract.md` 当保留清单**（评审 I2：契约表缺 `list-search/list-empty/cond-group/demo-panel-*/validation-errs` 等被 e2e 实际断言的锚点）。改完 grep 核对：迁移后 testid 集合 = 迁移前，且**无重复**。
- **本次碰测试的完整清单（诚实口径，评审 B1/C4）**：① `e2e-dev-v2.mjs` —— 给「买赠」chip 补 `type-chip-5` 后把 L78 的 `.chip:has-text("买赠")` 换成 testid；② **新增** `e2e-phone-smoke.mjs`（390×844：汉堡→抽屉→点导航项→表单提交）并挂 npm script；③ `data-testid-contract.md` 补登记。**`e2e-tablet-smoke.mjs` 不改**（docked-768 使其零改保持绿）。
- **视口矩阵**：1280×800 主（全布局回归）+ 768×1024 平板 smoke（docked + 表单提交 + 无横向溢出）+ 390×844 手机 smoke（抽屉）+ 360×640 走查。
- **体积门**：build 后首屏 JS gzip ≤150KB（现 ~42KB，抽屉为 CSS+极少 JS，余量足）。

## 9. 验收标准
1. 视觉/分区：应用任意页呈现「常驻左侧栏 + 顶部工具条（含身份/主题）+ 逐页页头 + 内容区」，首屏不再被身份条挤占；控制台与演示台同一外壳范式。
2. 可读性：卡片/内容区/侧栏三层明色可辨；正文不再用 `--text-faint`；全站可交互元素有 `:focus-visible` 焦点环。
3. 零业务回归：`npm run typecheck` 过、Vitest 26 绿、`e2e:dev`/`e2e:oidc`/`e2e:catalog` 全绿（仅 e2e-dev 因 `type-chip-5` 同步改）。
4. **移动端（硬指标）**：`e2e-tablet-smoke` 768×1024 零改保持绿（docked 侧栏、body 无横向溢出 `scrollWidth-innerWidth≤4`、活动表单可完整填写并提交）；**新增** `e2e-phone-smoke` 390×844 验汉堡→抽屉可开合→导航→表单提交。
5. 触控：`(pointer:coarse)` 下 button/select/input/nav-item ≥44px（含 EditorView chip/mini、theme-btn、pager）。
6. 契约：除 `type-chip-5` 外，全部 data-testid、路由 name/path、API/ storage key、主题机制不变（代码审查项）。
7. 首屏 JS gzip ≤150KB。

## 10. 风险与回滚
| 风险 | 缓解/回滚 |
|---|---|
| `catalog.ts`(1351行) 被 eager 拉进主包（代码分割退化） | DemoNav 保留在 demos 懒 chunk，全局侧栏只放「演示台」入口不展开 demo；build 后查 chunk 归属 |
| 身份条迁移误伤 auth 档 header 注入（误发 X-Tenant-Id→403） | IdentityBar 逐字搬 `v-if authEnabled` + 复用同 store；header 注入在 useAuthStore 与 UI 无关；e2e-oidc 守 |
| 抽屉在 768 造成横向溢出（tablet-smoke 红） | transform 离屏不改布局宽度；每次改完跑 tablet-smoke |
| 条件树重演「渲染期 mutation/丢焦点」坑 | 不动 condition-tree 逻辑与 `:key=node.id`；ConditionGroup.test 守 |
| 「买赠」chip 换 Segmented 断 e2e-dev | 同步补 `type-chip-5` + 改 e2e-dev L78 + 登记契约（需授权，见批准问题 B） |
| **F1b/F1c 中途 testid 出现两份**（新壳+旧壳并存）→ Playwright strict-mode「2 elements」+ 双 401-push | **F1b+F1c 声明为同 commit 不可分割变更**；改完 grep 断言「无重复 data-testid」（评审 I3） |
| 页面套壳误删/漏迁 testid | 以 `e2e/*.mjs` 脚本（非过时契约表）为真值源双向 grep 比对；迁移后集合 = 迁移前 |
| ToastHost `z-index:1000` 与抽屉分层 | tokens.css 定数值序 `--z-sticky(100)<--z-dropdown(200)<--z-drawer(900)<--z-toast(1000)`；**抽屉 900 < ToastHost 硬编码 1000 → ToastHost 零改仍在最上层**（评审 I4） |
| 冷启动在登录页闪外壳/dev 身份条 | AppShell 分流加 `router.isReady()`，config resolve 前不渲染 chrome（评审 I5） |
| **总回滚** | 纯前端、无路由/API/store/逻辑/数据改动：`git revert`/`git checkout` 外壳与页面文件 + tokens.css 增量即回现状；旧壳（ConsoleShell/App.vue）在 Phase 1 前逐字可复原；无不可逆改动 |
