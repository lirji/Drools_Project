# 独立计划评审记录与处置

> 评审方式：只读子代理，抽查 **47 处**引用、独立复算 **23 组** WCAG 对比度、实测产物体积与 DOM 几何。
> 处置原则：**全部采纳**。已逐条改进 `DECISION_RECORD.md` 与 `FINAL_PLAN.md`，无一条以「注明不改的理由」结案。

---

## 评审确认属实的部分（避免实施时被当成"存疑项"重新推翻）

**逐字属实的引用**：`tokens.css:2/14-16/21/38/151/177-180/231-234/250-260/267-275`；`Button.vue:30,35`；`HomeView.vue:168`、`:29`；`IdentityBar.vue:96`；`DetailView.vue:120` 确实缺 `v-else`；`TierRuler.vue:157`；`DemoHome.vue:186,220`；`SidebarNav.vue:144-152`；`Gauge.vue:40`；`AppLayout.vue:57`；`SidePanel.vue:28`；`EmptyState.vue:12`；`ListView.vue:348-356`、`:740-772`；`LoginView.vue:154`；`LoginView.test.ts:59-64`；以及全部 e2e DOM 锚点（`.tr` / `.activity-name` / `.text-box` / `.no-body` / `.knob` / `#login-tenant` / 「详情」exact）。

**计数属实**：`--radius-sm` 85 处、`color-mix` 26 处、`cubic-bezier` 0 处、`<Icon` 135 处、Icon 默认 stroke 1.75、**109 处 ≤10px 字号且四文件分布逐个吻合**、`--fs-2xl` 用 1 次 / `--fs-3xl` 用 0 次、viz 27 例、11 个可达路由、ListView 791 行、`Gauge`/`Sparkline`/`Field` 生产零引用、`nginx.conf` 无 gzip。

**附录 A 的 WCAG：复算 23 组，20 组差值 ≤0.01。**

**明确核实为安全、实施时不要多改的四项**：
1. `--shadow-sm` 改 `inset` **安全**——18 个使用点全核过。暗色旧值打在 `#0b1211` 上本就不可见；Toast 走 `--shadow-md`、SidePanel/ConfirmDialog/抽屉走 `--shadow-lg`（仍保留外投影），都不会失去视觉分离。（想要 Raycast 顶栏效果需写 `inset 0 -1px 0`，因为顶栏贴视口、上边缘的内高光看不见。）
2. `--shell-topbar-h` 52→56 **安全**——全部消费点走令牌（`AppLayout.vue:32,39,53,61`、`SidePanel.vue:160,172,173,177`、`BulkBar.vue:57`），**全仓无为顶栏硬编码的 52px**。
3. `--radius-sm` 4→7 **撑不破布局**——`border-radius` 不参与盒模型。
4. `--notch: 0` 后 **Seam 自动退化成点线**——点线是 `.seam` 自身 background（`:22`），独立于 `::before/::after` 齿孔；`viz.test.ts:117` 只断言 `role="separator"`。

---

## 🔴 六项必改 —— 处置

| # | 问题 | 处置 |
|---|---|---|
| **R1** | `TopBar.vue:14-16` 在 dark-first 下必错，且是**默认路径**：属性缺席 → 按裸 `:root` 渲深色，但 `dark.value===false` → 首次点击写 `dark`，**视觉零变化**，要点两次才能切浅色 | ✅ 列为缺陷 **F9**，进 §8 **P0** 与步骤 1。修法：属性缺席时从 `matchMedia('(prefers-color-scheme: light)')` 反推 + 补单测 |
| **R2** | `#fff` 清单漏 10 处（实测 **13 处 / 9 文件**），R-1 的整个缓解逻辑失效。实算白字 on `#8B7BFF`=**3.29**、on `#22D3EE`=**1.81**，而 `.run`/`.primary` 是两色渐变 → 按钮右半边 1.81 | ✅ §8 P0 与 D10 F1 全量列出 13 处；A-1 增加 `grep -rn 'color: *#fff'` 归零判据；F1 原写的 "1.66" 更正为 **1.81** |
| **R3** | 玻璃化外壳 vs §10.4 sticky 禁令 vs F5 修法**三方互斥**；且「当前 3 个」是把全仓计数当同屏计数，**`/demos` 实际同屏 4 个** | ✅ 新增 **D8-a 裁决**：顶栏玻璃化**保留**（56px 窄条，Linear/Vercel 标准做法）、**侧栏玻璃化取消**、`.catalog-tools` 去玻璃、hero-stats 与登录卡保留 → `/demos` 同屏 = 2。§10.4 红线改写为「允许 ≤56px 的 sticky 窄条，禁止侧栏与滚动容器内大面积 sticky」 |
| **R4** | `--shadow-lg` 的 60px blur 违反自己的 ≤40px 红线；且它是全局令牌，手机 off-canvas 抽屉必然带 accent 辉光，§7.4「<768 关辉光」**物理做不到** | ✅ blur 降到 **40px**；辉光拆成独立的 **`--shadow-lg-glow`**，模态/侧板叠加使用，抽屉不用，§7.4 得以按视口关闭 |
| **R5** | R-6「接不上就删」会打掉 `viz.test.ts` 5 条（Gauge `:56/:67/:72`、Sparkline `:102/:108`），与 D6「27 条继续绿」和 N-8 冲突 | ✅ R-6 增加**第三选项并设为默认**：保留组件 + 保留测试 + 本轮不接数据。若仍选「删」，须显式授权改这 5 条 |
| **R6** | CSS 预算量法错误：`cat *.css \| gzip` 得 21.5KB 是共享字典幻觉，**逐 chunk 求和 = 30.4KB**，对 32KB 上限真实余量仅 1.6KB | ✅ 口径写死为「逐 chunk gzip 求和」，上限提到 **40KB**；**D11 的 nginx gzip 从「可选」升为 P0 硬前置**（今天线上 CSS 是 137KB 裸传） |

---

## 🟡 十二项应改 —— 处置

| # | 问题 | 处置 |
|---|---|---|
| **Y1** | F3 机制诊断✅正确，但修法落点错：真实是 `:660` 桌面 `0 1 360px` → `:744`(≤1023) `1 1 240px` → `:779`(**≤560**) 转 column。按 767px 开新块会污染 561–767 区间。且 A-6 的 ≤160px **不可能达到**（coarse 下 44×3+gap+padding ≈174，带筛选 226） | ✅ 修法改为 `@media (max-width:560)` 内加 `.search-box { flex: 0 0 auto }`；A-6 改为断言 **`.search-box` 高度 ≤56px**（直接钉缺陷本体）+ toolbar ≤240px |
| **Y2** | `DemoHome.vue:220` 真缺陷不是 backdrop-filter 而是 **`top:0`** 与顶栏同 z-index 且 DOM 在后 → **盖住顶栏**。同类漏网 `EditorView.vue:647` `.rail{top:16px}` 被遮 40px | ✅ F5 改写根因；EditorView rail 偏移进步骤 8 |
| **Y3** | 附录 A 浅色 `--accent-2` 三行是按**旧色** `#0E7490` 算的（差 0.35–0.58）；且「`#0E7490` 只有 4.43 不达标」的论据不成立——4.43 是按**旧** bg-hover 算的，新 bg-hover 上是 **4.67 达标** | ✅ 三行更正为 **5.29 / 5.58 / 6.07**；删除两处错误论据并注明「取 `#0C6B85` 只为留余量，不是因为前者不合格」 |
| **Y4** | `--focus-ring` 第一层用 `var(--bg)` 会在卡内控件上画可见暗环——8 个使用点**全部**坐在 `--bg-elev`/`--bg-soft` 上 | ✅ 改用 `var(--bg-elev)`；并注明全局键盘环仍走既有 `outline + outline-offset` |
| **Y5** | §4/§8 完全漏掉 **9 个组件文件**；effects.css 少收 2 组重复关键帧（`pulse` 与 `breathe` **逐字相同**）+ `bulk-in` + `aurora-float`，全仓共 **9 个 keyframes** 只点了 7 个 | ✅ §8 P1 新增漏网文件条目；effects.css 目标改为收敛全部 9 个 |
| **Y6** | G-5「消灭 109 处」与 §8 覆盖的 80 处对不上 | ✅ 目标改为 80 处，剩余 29 处逐个列出去向（viz 6 处被 D6 挡着本轮不动） |
| **Y7** | R-3 的莫尔纹缓解实现不了：ListView 根是无 class 的 `<section>`，`.shell-content` 有 padding + 居中 → 加底色只会四周留网格边、内容不满屏时下方仍是网格 | ✅ 网格改铺 `.shell-content`，由 `AppShell.vue` 按 `route.name` 切 `data-grid="off"`；§8 P1 新增 AppShell 条目 |
| **Y8** | D0 只落盘 frontend，但**全仓 43 改 / 41 未跟踪**且前后端耦合（`activityApi.ts` 调的 `/bulk-status` 实现在未提交的 Controller 里）；后端未跟踪文件同样没被 `git checkout --` 的理由保护 | ✅ 步骤 0 改为「提交整棵工作树」 |
| **Y9** | §10.1 漏 4 条会红的断言：`DemoPanel.test.ts:28` `.response-card`+`aria-busy`、`DemoNav` 的 `.nav-search input`、`LoginView.test.ts:75,81` 的 `[role=status]`（**不在 A5 授权内**）、`viz.test.ts:104-105` 的**第一个** path | ✅ 四条全部补进契约表并各给处置；授权例外从 `LoginView.test.ts:59-64` 扩到该文件全文；明确「面积 path 必须插在折线之后」 |
| **Y10** | 「132 条 testid」是**文件行数**（实际契约表 69 行 / token 94 个 / src 130 个 / e2e 消费 78 个）；横向溢出断言是 **10 个**不是 5 个，阈值是 **`<=4`** 不是 `<=0` | ✅ 计数全部更正；A-7 阈值改 `<=4`（写 0 会误报） |
| **Y11** | 全仓零 `color-scheme`。dark-first 后 `<select>` 弹层、`<input type=search>` 清除键、**全站滚动条**都是浅色 UA 样式压在深空底上 | ✅ 列为缺陷 **F10**，进 §2.2 令牌表与 P0 |
| **Y12** | A-6/A-8/A-9/A-12 的判定方式写了 Playwright，但 §8/§9 **没有任何一步产出这些断言** → 验收与实施脱节 | ✅ 新增**步骤 9「补自动化验收断言」**（改 `e2e-phone-smoke.mjs` + 新增 `e2e-visual-guard.mjs`） |

---

## 🔵 四项建议 —— 处置

| # | 问题 | 处置 |
|---|---|---|
| **B1** | 步骤 2 单独 commit 后会明显破相（10 处 `#fff`、旧靛蓝渐变、终端面硬编码、硬编码圆角失配），R-10 的「80% 观感」过于乐观 | ✅ 新增**步骤 2b**「收编深色面硬编码」；**中途确认点从步骤 2 后挪到步骤 5 后** |
| **B2** | 12 项行号/计数偏差（`DetailView.vue:235→242`、`EditorView.vue:599→654`、`min-width` 声明 `:677`/解除 `:746`、LoginView blur `:275→279`、EditorView 607→**662** 行、gradient 39→**43**、裸 ease 48→**54**、`:hover` 60→**62**、vitest 142→静态 **146**、断点 9→**8 个值**、LoginView「5 个硬编码色」→实测 **32 处 / 9 个色值**）；且 **F8「全仓均未写 `-webkit-`」不成立**——`LoginView.vue:240-241` 已双写，只有 `DemoHome.vue:186` 没写 | ✅ 全部更正；F8 改写并消除与 §10.5 的自相矛盾 |
| **B3** | 字体预算 65KB 偏乐观：Inter var latin 实际 45–90KB；**JetBrains Mono 不是可变字体**且 `DemoPanel.vue:311` 需要 bold → 至少 2 个文件；且 `@font-face` 写在 CSS 里必然闪一次，hash 文件名让手写 preload 困难 | ✅ 预算上调到 **100KB / 3 个文件**；preload 列为实施期开放项 |
| **B4** | 指出四项「核实安全、不要多改」 | ✅ 已抄进本文档顶部，防止实施时被误改。另记录 5 处**不跟随令牌的硬编码圆角**（`DemoHome.vue:199` 12px、`ValidateView.vue:199` 12px、`DetailView.vue:242` 14px、`DemoPanel.vue:311` 5px、`:355` 4px）需一并清理 |

---

## 评审总评

> 「**现在不能开工。**……改完这 8 条，这份计划就是可以照着干的。」

评审要求的开工前最小集 **8 条已全部落实**：R3 玻璃裁决 ✅ / R2 十三处 `#fff` ✅ / R1 TopBar 初值进 P0 ✅ / `--shadow-lg` 拆辉光 ✅ / R-6 第三选项 ✅ / CSS 预算口径 + gzip 升 P0 ✅ / 附录 A `--accent-2` 三行更正并删错误论据 ✅ / 新增步骤 9 补 e2e 断言 ✅。
