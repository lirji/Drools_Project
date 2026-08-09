# 决策记录 · 前端「科技感」视觉换代

> 触发：用户原话「前端页面不好看，没有科技感。我给你提供几个地址，你参考一下：
> https://motionsites.ai/ 、https://saasinterface.com/ 、https://land-book.com/」
> 日期：2026-08-09 · 基线 commit `2c1f49d`（frontend 工作树有 23 改 + 17 未跟踪，见 D0）
> 勘察方式：6 路只读子代理（需求流 / UIUX 与既有视觉语言 / 前端架构 / 可复用资产 / 测试与风险 / 视觉参考站）
> ＋ 主代理亲自跑 Playwright 抓「改造前」基线截图与几何测量（响应式那一路子代理因会话额度中断，由主代理亲测补齐）。

---

## 0. 诊断：为什么现在「不好看、没科技感」

这不是"审美见仁见智"，有可指认的成因。每条都带证据。

| # | 成因 | 证据 |
|---|---|---|
| G1 | **三套视觉语言并存且互相打架** | 控制台 = 米青纸底 `--bg:#edf1f0` + 32° 斜纹防伪底纹 + 复写紫红 `#a4256b`（`tokens.css:21,38,231-234`）；登录页 = 靛蓝极光玻璃，硬编码 `#4f46e5/#6366f1/#22d3ee` + `blur(20px)`（`LoginView.vue:222-255,275,293`）；能力中心 = 深靛渐变 hero，硬编码 `#171a34→#312e81`（`DemoHome.vue:179-196`）。**最像科技感的两屏用的是被废弃的靛蓝语言，天天用的控制台反而最平。** |
| G2 | **整页发灰的直接元凶是 body 斜纹** | `tokens.css:231-234` 两道 32° `repeating-linear-gradient`。截图上肉眼可见网纹，读起来是"旧办公纸"而不是"产品" |
| G3 | **暗色档没有层次** | 暗色四档阴影全是 `rgba(0,0,0,.35~.55)` 纯黑投影（`tokens.css:177-180`）——黑投影打在 `#0b1211` 上物理不可见。暗色的 elevation 必须靠面色亮度阶 + 1px 内高光，现在两样都没有 |
| G4 | **零发光、零渐变令牌** | 全仓 39 处 gradient 全是各页手写、无一走 token；4 处 accent 发光（`DetailView.vue:235`/`EditorView.vue:599`/`ValidateView.vue:201`/`DemoPanel.vue:362`）各写各的 `color-mix` |
| G5 | **形制偏硬、层级读不出** | `--radius-sm:4px`（85 处引用）、`--radius:6px`。当代做法是"面大圆角、控件小圆角、两者差 ≥4px"，现在几乎没有差 |
| G6 | **排版没有节奏** | 109 处 ≤10px 硬编码微型字号（ValidateView 24 / DetailView 21 / DemoPanel 16 / ListView 11）；而 `--fs-2xl` 只用 1 次、`--fs-3xl` 从未使用。视觉全被压在 9–14px |
| G7 | **字体零投入** | `index.html` 无任何 web font，`--font-ui` 是系统 UI 栈。这是科技感投入产出比最高、却完全没做的一项 |
| G8 | **动效几乎不存在且各写各的** | 全仓零 `cubic-bezier`、48 次裸 `ease`；`@keyframes spin` 被复制 5 份、shimmer 2 份；`DESIGN_SPEC.md:258-266` 定义过 `--dur-*`/`--ease-*` 但从未落地 |
| G9 | **工作台首屏一张图都没有** | `ListView.vue:348-356` 是一块"决策指标尚未接入"的说明卡（按 D6「不画假图」原则拒绝画图）。同时 `Gauge.vue` / `Sparkline.vue` 写好了却**全仓零引用** |
| G10 | **手机端有可见布局缺陷** | 主代理实测 390px：`.toolbar` 小屏切 `flex-direction: column` 后，`.search-box` 仍写 `flex: 1 1 240px`——240px 从"宽度基准"变成"高度基准"，搜索框被撑成 **316×240px** 的空盒（实测 `.toolbar` 342×372）。见 §7 截图 |

---

## D0 · 前置条件：先落盘，再换代

**决策：开工前必须把整棵工作树提交（不只是 frontend），然后开 `feat/visual-tech-refresh` 分支。**

**范围修订（评审 Y8）**：最初只写了 frontend 的 23 改 + 17 未跟踪，这是错的——**全仓是 43 改 + 41 未跟踪（+2014/-407）**，且前后端**耦合**：`frontend/src/console/activityApi.ts` 的 diff 新增了对 `/bulk-status` 的调用，其实现在**未提交的** `activity-console/.../ActivityMarketingController.java` 里。只提交 frontend 会产出一个「接口不存在」的 commit。

同理，D0 自己的理由（`git checkout --` 会销毁未跟踪文件）对后端那批未跟踪文件（`BenefitForm.java` / `ConditionTreeEvaluator.java` / `BenefitMath.java` / `snapshot/` / `metrics/` / `DecisionDataLoader.java`…）**同样成立却没被保护**。

理由：上一轮 `DECISION_RECORD` 已把「token 换代与结构改造永不混在同一 diff」写成教训。把功能改动与视觉换代焊死在一个 diff 里，等于两边都 revert 不了。

**这是硬前置，不接受"边做边提"。**

---

## D1 · 换代范围：全站 11 个路由，但分三档投入

| 档 | 页面 | 投入 | 允许的手法 |
|---|---|---|---|
| **Tier A 门面** | `/login`、`/home`、`/demos` | 重投入 | 全屏 hero、极光光晕、玻璃、渐变文字、入场 stagger |
| **Tier B 展示型工作页** | `/demos/:id`、`/console/validate`、`/console/activities/:id`、`/console/playbooks` | 中等 | 结果区可做高光/发光数据、终端风代码面板 |
| **Tier C 密度优先** | `/console/activities`（791 行表格）、`/console/activities/new｜:id/edit`（607 行长表单） | 只换配色/描边/字体/圆角 | **禁止**加大留白、禁止装饰性动效、禁止在此铺网格底 |

**备选与否决**：
- ❌「只改控制台四页」——会让 G1 的割裂更严重（登录页与能力中心是另外两套语言）。
- ❌「全站一刀切同等投入」——ListView 是 791 行、`min-width:994px` 的密度屏，`saasinterface.com` 收录的 dashboard 样本同样不在表格区堆装饰。

---

## D2 · 落地架构：令牌换代为主 + 一层极薄效果层。**不做双皮肤**

三个备选：

| 备选 | 做法 | 改动面 | 天花板 | 回滚 |
|---|---|---|---|---|
| **A 纯令牌换代** | 只改 `tokens.css` 的值 + 加新名 | 1 文件覆盖 **93%** 表面（1215 条规则绝大多数走 `var()`） | 做不出玻璃/渐变描边/发光边——组件里没有承载结构层（`Card.vue` 是单层 div） | `git revert` 一个 commit |
| **B ✅ 令牌换代 + 效果层** | A ＋ 新增 `shared/styles/effects.css`（收敛 5 份重复 `@keyframes spin` + 2 份 shimmer + 少量 utility）＋ 给 `Card.vue`/`Button.vue` 加 `::before` 承载层 | A + 约 6 个共享组件 | 能做玻璃、渐变描边、发光、扫光、脉冲 | 分 commit revert |
| **C 双皮肤 `data-skin` 并存** | 保留纸质皮，新增科技皮，`<html data-skin>` 切换 | 调色板从 4 段变 **8 段**（+80 行重复），且 index.html 内联脚本要加第三条根属性 | 同 B | 一键切回 |

**选 B。** 否决 C 的理由：① 用户诉求是"现在不好看"，不是"想要两种可选"，保留旧皮是纯负债；② 结构性改版（hero 重画、登录页重排）本来就**不能**靠 `data-skin` 回退，双皮肤只覆盖 token 层，会给人"能一键回滚"的错觉；③ 8 段调色板是维护灾难。回滚走"每阶段一个独立 commit + `git revert`"。

**硬纪律（沿用 `tokens.css:2` 既定约束）**：变量名**只增不删不改**。90 个既有名全部保留原名改值，新增 6 个名：`--border-ctl` / `--glow` / `--glow-strong` / `--surface-glass` / `--dur-fast|mid|slow` / `--ease-out|spring`。

---

## D3 · 视觉方向：三个候选，推荐「深空遥测」

三者差别在**机制**不在色相：A 靠面色亮度阶、B 靠栅格与等宽、C 靠渐变与模糊。

### 候选 1 ★推荐 ·「深空遥测」Deep Space Telemetry

- **调性**：安静的深空底 + 靛紫签名色 + 青色数据色。层次靠面色亮度阶与 1px 内高光，数据靠等宽字与发光序列线。
- **对标与借鉴点**：**Linear**（面色亮度阶做 elevation、侧栏 active = soft 底 + 左强调条 —— `SidebarNav.vue:144-152` 已经是这个结构，只需换色）／**Vercel Dashboard**（可辨中性描边、近零装饰）／**Grafana**（发光序列线 + 阈值虚线 —— `Gauge.vue` 的临界线正好对上）／**Raycast**（控件 1px 顶部内高光）
- **为什么是它**：① 本产品实质是「规则引擎 + 决策热路径 + 发布代际 + 指标」，**遥测语义与业务同源**，不是硬贴一层与业务无关的皮；② 靛紫 `#4f46e5→#8B7BFF` 与 `LoginView.vue` 现有品牌面板同源，**不是另起炉灶而是把已有的科技感升到令牌层**；③ 规避了纯 cyan 方案在浅色档的对比度天坑（见下）。
- **暗色主色** `--accent: #8B7BFF`／**浅色** `#5B4BE8`；`--accent-2` = cyan `#22D3EE`（暗）/`#0C6B85`（浅），只作数据与二级强调
- **形制**：`--radius 10px` / `--radius-sm 7px` / `--radius-lg 14px` / `--notch 0`
- **动效**：位移与不透明度为主，160–180ms；脉冲指示点与扫光允许循环（"仪器在跑"有信息量）；**表格页零循环动画**

### 候选 2 ·「遥测栅格」Telemetry Grid（更硬核）

- cyan `#22D3EE` 作主色、方角（`--radius 8px`）、等宽字当主角、40px 网格更明显。
- **风险**：① cyan 在浅色档天生偏弱——`#0E7490` 落在 `--bg-hover` 上只有 **4.43:1**（不达标，需改 `#0C6B85`）；② 网格底与 `ListView.vue:683-687` 每 5 行一道加重线会产生**莫尔纹拍频**；③ 等宽字当主角会让中文 fallback 到 Windows 新宋体。

### 候选 3 ·「极光玻璃」Aurora Glass（最"好看"）

- violet `#A78BFA` + magenta 渐变、`--radius 14px`、mesh 光晕 + 玻璃 + 渐变描边 + 渐变标题。最贴 land-book 的 Gradient/Dark Colors 标签。
- **风险**：① 低端安卓/微信 WebView 掉帧（topbar+侧板+模态三处 `backdrop-filter`）；② `--accent-2` 是 magenta `#FF6FB0`，实现时一旦误当主色，观感直接退回今天的复写紫红——用户会说"你没改"；③ 大面积低对比渐变在 8-bit 屏必出色带。

> **三个候选的完整令牌映射表（覆盖全部 90 个变量名 × 明暗两档）与 WCAG 精算结果见 `FINAL_PLAN.md` §3 与附录 A。**

---

## D4 · 深色是否设为默认 —— **是，dark-first**

- 参考站三家均以深色为主场；科技感的全部手法（辉光、网格、玻璃）**只在暗色下成立**，浅色下辉光变脏晕、网格变方格纸。
- **但浅色档不降级、不删**：办公投影、打印、白天长时间看表格都需要它。浅色档的正确做法是**主动放弃特效**，退回干净描边 + 极轻投影，而不是把暗色效果调低透明度硬移植。
- 实现方式：改 `tokens.css` 裸 `:root` 为深色基线，`:root[data-theme="light"]` 与 `@media (prefers-color-scheme: light)` 承载浅色。**不**在 `index.html` 内联脚本里写死 `data-theme=dark`——那会覆盖用户系统偏好。
- 追加：`@media print { body { background:#fff !important; background-image:none !important } }`，否则暗色打印出一张全黑纸。

---

## D5 · 字体 —— **拉丁自托管，中文吃系统栈**

| 角色 | 取值 | 体积 |
|---|---|---|
| UI 拉丁 | Inter var（latin 子集 woff2） | ~35KB |
| 等宽 | JetBrains Mono（latin 子集） | ~28KB |
| 中文 | **不引 web font**：PingFang SC / HarmonyOS Sans SC / 微软雅黑 / Noto Sans SC | 0 |

- 硬理由：CJK web font 即使子集化也是 3–10MB 级，这是内网 docker 编排 + 网关托管的 B 端控制台，不划算。
- **字族顺序必须拉丁在前**，浏览器逐字符 fallback → 英文数字走 Inter、汉字走苹方，混排自然。
- **落点必须是 `frontend/src/assets/`，不是 `frontend/public/`** —— public 下的文件落到 `/ui/fonts/`，命中 `deploy/nginx.conf:69` 的 `Cache-Control: no-cache`，每次导航重下字体。放 `src/assets/` 让 Vite 出到 `dist/assets/` 带 hash。
- 预期割裂："英文很潮、中文还是雅黑"是这条路线的**已知代价**，用可变字重 + 字距 + 字号层级弥补。

---

## D6 · 票券工学（Seam 撕线 / Receipt 会计双线 / WindowBar 甘特 / TierRuler 刻度尺）—— **保几何，换语汇**

上一轮为它们写的是**可用性论证**而非审美论证（撕线作语义分段器、量筒临界线的前注意力可读性、甘特条跨行共享轴、金额小数点对齐），且被 27 条单测钉死（`vizMath.test.ts` 14 + `viz.test.ts` 13）。

**决策：保留 viz 层的几何与数学，只换配色与线型语汇**——`--notch: 0` 熄灭齿孔后 Seam 自动退化成点线；`--seam` 改义为"栅格/扫描线"、`--rule` 改义为"高亮基准线"、`--grain` 改义为"网格线色"。**改语义不改名**，27 条单测全部继续绿。

否决"全部删掉"：会丢掉 PlaybooksView / DetailView 的分段语义，且要重画 4 个组件。

---

## D7 · 「不画假图」原则 —— **保留，不为科技感造假数据**

`ListView.vue:348-356` 明确拒绝画没有数据源的图表，并把待建接口写在界面上。这是本项目最值钱的产品立场之一。

**决策：不推翻。** 工作台的"科技感"用**已有真实数据**兑现：生效窗甘特条（真）、额度与状态（真）、活动计数（真）、`Sparkline`/`Gauge` 只在**有真实数据的地方**启用（如 DemoPanel 的 `elapsedMs` 耗时、ValidateView 的命中金额）。缺接口的地方继续如实说明，只是把那张说明卡从"记账便签"改成"终端风待接入面板"。

---

## D8 · 反向验收禁令的处置

上一轮 `DESIGN_SPEC.md` 写死了三条禁令，本轮**部分作废并给出新边界**：

| 旧禁令 | 新裁决 | 新边界 |
|---|---|---|
| 禁止 >10px 圆角 | **作废** | 面 10–14px、控件 6–8px、hero 18–24px、徽章 pill。⚠ 同时要清掉 5 处**不跟随令牌的硬编码圆角**：`DemoHome.vue:199`(12px)、`ValidateView.vue:199`(12px)、`DetailView.vue:242`(14px)、`DemoPanel.vue:311`(5px)、`:355`(4px)——否则换代后参差可见 |
| 禁止 `backdrop-filter` | **有条件解禁（评审 R3 后收窄）** | 见下方「D8-a 玻璃化边界裁决」 |
| 禁止静止态循环动画 | **有条件解禁** | 仅限脉冲指示点与骨架扫光；同屏 ≤2 层；只动 `transform`/`opacity`；**`/console/activities` 与 `/console/activities/new` 两条最重路线上零循环动画**；`prefers-reduced-motion` 全局闸（`tokens.css:267-275`）保持。⚠ `LoginView.vue:254,255` 两个 `.aurora-blob` 共用 `:249` 的 11s infinite + `blur(16px)`，**登录页现在就已吃满 2 层配额**，重设计时要减到 1 层 |

### D8-a · 玻璃化边界裁决（评审 R3：三方互斥，必须裁决）

评审指出原计划自相矛盾：§4/§8 要玻璃化 topbar **与** sidebar；§10.4 红线又写「backdrop-filter 不得放 sticky/fixed 或滚动容器上」；D10 F5 又把 `DemoHome.vue:220` 的 sticky backdrop-filter 当缺陷要修。**三者不可能同时成立。**

而且评审实测纠正了我的计数口径：全仓 backdrop-filter 只有 3 处（`LoginView.vue:279`、`DemoHome.vue:198` `.hero-stats`、`DemoHome.vue:220` `.catalog-tools`），登录页是**裸壳路由**（`App.vue:23,28-29`）永远只有 1 个；**真正超标的是 `/demos`**——玻璃 topbar + 玻璃 sidebar + hero-stats + catalog-tools = 同屏 **4 个**。

**裁决**：

| 目标 | 裁决 | 理由 |
|---|---|---|
| `.shell-topbar` 玻璃化 | ✅ **保留** | 56px 窄条、全站唯一常驻、面积最小。Linear / Vercel 均如此。**这是行业标准做法，原红线的「禁止 sticky」写得过宽** |
| `.shell-sidebar` 玻璃化 | ❌ **取消** | 竖向长条面积大、收益低、是同屏配额的主要浪费。改回不透明 `--bg-elev` |
| `DemoHome .catalog-tools` | ❌ **去掉 backdrop-filter**，改不透明 | 它是**滚动容器内的大面积 sticky**，正是红线要防的形态 |
| `DemoHome .hero-stats` | ✅ 保留（非 sticky） | |
| `LoginView` 玻璃卡 | ✅ 保留，blur 20→14 | 裸壳路由，同屏仅 1 |

→ 新的同屏最大值：`/demos` 上 topbar + hero-stats = **2**，达标。

**§10.4 红线改写为**：`backdrop-filter` 同屏 ≤2；blur ≤14px；**允许**用于高度 ≤56px 的 sticky 窄条（顶栏）；**禁止**用于侧栏与滚动容器内部的大面积 sticky 元素；必须 `@supports` 包裹 + `-webkit-` 双写；`(max-width:767px)` 与 `(pointer:coarse)` 下整体关闭。

---

## D9 · 不引动效库（motion-v / @vueuse/motion）

理由不是体积，而是**它会绕开 `tokens.css:267-275` 的全局 `prefers-reduced-motion` 闸**——现在这道闸靠 `* { animation-duration:.001ms !important }` 一处兜住全站；换成 JS 驱动后，无障碍面从"一处兜底"退化成"N 处各自兜底"。

参考站的全部效果（网格/噪点背景、渐变描边、glow、hover 位移与光泽、入场 stagger）**纯 CSS 都能做**——`LoginView.vue` 已经用纯 CSS 做出了 aurora + 网格 + 玻璃，是本仓库现成的证据。

滚动进场若要做，用 15 行 `IntersectionObserver` 自写 `shared/useReveal.ts`（与 `useScrollLock`/`useDensity` 同构），并在 composable 内部先判 `prefers-reduced-motion` 直接跳过。

---

## D10 · 顺带修掉的既有缺陷（不属于纯视觉，但换代必然碰到）

| # | 缺陷 | 位置 |
|---|---|---|
| **F1**（评审 R2 扩容） | 硬编码 `#fff` 压在 accent / accent 渐变上。**实测 13 处 / 9 个文件**，不是最初写的 3 处。实算：白字 on 新暗色 `--accent #8B7BFF` = **3.29:1**（不达标）；on `--accent-2 #22D3EE` = **1.81:1**。而 `.run`/`.primary` 是 `linear-gradient(var(--accent), var(--accent-2))`——**按钮右半边就是 1.81:1** | `Button.vue:30,35`、`HomeView.vue:168`、`IdentityBar.vue:96`、`ConditionGroup.vue:84`、`EditorView.vue:639,654`、`ValidateView.vue:199,201`、`DemoPanel.vue:311,362,392`、`DetailView.vue:242`、`LoginView.vue:362,365` → 全改 `var(--text-invert)` |
| F2 | `--accent-ink` 未定义，`TierRuler.vue:157` 永远吃内联 fallback 的旧玫红，暗色不跟随 | 补定义或改 `color-mix` |
| **F3**（评审 Y1 修正落点） | 手机端 `.search-box` 被撑成 316×240px（G10）。机制诊断经评审复核**正确**：column 容器高度 auto，`flex-basis:240px` 即 hypothetical main size，`flex-grow:1` 无自由空间可分 | 真实 CSS：`ListView.vue:660` 桌面 `flex: 0 1 360px` → `:744`（≤1023）覆写 `flex: 1 1 240px` → `:779`（**≤560**）才转 column。**修法必须落在 `@media (max-width:560)` 块内加 `.search-box { flex: 0 0 auto }`**；若按 767px 开新块会污染 561–767 区间（那里仍是 row + wrap，basis 改 auto 会让搜索框不再稳定独占一行） |
| F4 | `DetailView.vue:120` 缺 `v-else`，manage 为空时整页空白 | 补 `v-else` |
| **F5**（评审 Y2 改正根因） | `DemoHome.vue:220` 的真缺陷**不是** backdrop-filter，是 **`top: 0`**：文档级滚动下它与 `.shell-topbar`（`AppLayout.vue:31`）**同 z-index 且 DOM 在后** → 滚动时**盖住顶栏** | 改 `top: var(--shell-topbar-h)`；backdrop-filter 按 D8-a 一并去掉。**同类漏网**：`EditorView.vue:647` `.rail { sticky; top: var(--sp-4) }`=16px，rail 顶部被顶栏遮 40px（顶栏 56 后） |
| F6 | `HomeView.vue:29` `rows.slice(0,8)` 未排序未归并，同活动的 v1+v2 重复且 `:key` 撞车 | 复用 `benchModel.ts` 的 `mergeRows` |
| F7 | `EmptyState.vue:12` 的 ICON_NAMES 白名单硬编码 9 个名，改图标不同步会静默渲染成文本 | 换代改图标时同步 |
| **F8**（评审 B2 修正） | ~~全仓~~ `mask-image` **只有 `DemoHome.vue:186`** 未写 `-webkit-` 前缀（`LoginView.vue:240-241` 已双写）。原表述与 §10.5 互相矛盾，以此条为准 | `DemoHome.vue:186` 补 `-webkit-mask-image` |
| **F9** 🆕（评审 R1） | **`TopBar.vue:14-16` 在 dark-first 下必然错，且变成默认路径**：`dark.value = getAttribute('data-theme')==='dark'`。新用户无存储偏好 → 属性缺席 → 按裸 `:root` 渲成深色，而 `dark.value===false` → 按钮显示月亮、`aria-pressed=false`；**第一次点击写入 `dark`，视觉零变化，要点两次才能切浅色** | 属性缺席时从 `matchMedia('(prefers-color-scheme: light)')` 反推初值 + 补单测钉住。**这是 D4 的地基漏洞，列 P0** |
| **F10** 🆕（评审 Y11） | 全仓（含 tokens.css）**零 `color-scheme` 声明**。`:root` 裸块改深色后，原生控件全是浅色 UA 样式压在深空底上：`ListView.vue:377` `<select>` 下拉弹层、`:371` `<input type="search">` 的清除按钮、EditorView 原生 select、**全站滚动条** | `:root { color-scheme: dark }`；`:root[data-theme="light"]` 与 light media query 加 `color-scheme: light` |

---

## D11 · nginx 开 gzip —— **从「可选」升为 P0 硬前置**（评审 R6）

`deploy/nginx.conf` 全文无 `gzip`/`brotli` 指令，今天线上 CSS 是 **137,492 B 裸传**。

**升级理由（评审实测纠正了我的预算量法）**：我原先用 `cat *.css | gzip -9` 量出"全站 CSS gzip 21.5KB"——**这个口径不成立**。Vite 把 CSS 按路由切成 30+ 个 chunk **分别下发**，合并压缩省下的 9KB 是共享字典幻觉。**逐 chunk gzip 求和 = 30,409 B ≈ 30.4KB**，对着原定 32KB 上限，真实余量只有 **1.6KB**——而这次要加 `effects.css`、玻璃、渐变、发光、Hero、Stat，**第一天就撞线**。

**决策**：① 预算口径写死为「逐 chunk gzip 求和」，上限提到 **40KB**；② 在 http 块加 `gzip on; gzip_types text/css application/javascript image/svg+xml; gzip_min_length 1024;`，**列为步骤 0 的一部分**，否则 §11 的 A-10 从第一天起就是空谈。

---

## 用户批准结果（2026-08-09，全部已确认）

| # | 假设 | **裁决** |
|---|---|---|
| A1 | 视觉方向 | ✅ **「深空遥测」候选 1**（靛紫签名色 + 青色数据色） |
| A2 | 默认主题 | ✅ **dark-first，浅色保留不降级**。牵出的 F9（TopBar 主题初值）与 F10（`color-scheme` 缺失）随之成为必修项 |
| A3 | 自托管字体 | ✅ **允许，≤100KB / 3 个 woff2**（Inter var latin + JetBrains Mono Regular/Bold），中文一律系统栈 |
| A4 | 只动观感层，不改路由 / API / store / 业务逻辑 / IA | ✅ 确认（原为假设，未被推翻） |
| A5 | 授权修改 `LoginView.test.ts` | ✅ 确认，且按评审 Y9 **授权范围扩到该文件全文**（`:75/:81` 的 `[role=status]` 也在内） |
| A6 | 实施范围与节奏 | ✅ **按计划走完 10 步**（含步骤 2b 与步骤 9），中途确认点在步骤 5 之后 |
