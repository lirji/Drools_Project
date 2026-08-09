# 实施计划 · 前端「科技感」视觉换代

> 配套 `DECISION_RECORD.md`（诊断 G1–G10、决策 D0–D11）。**本计划获批前不修改任何代码。**
> 基线 commit `2c1f49d`；目标分支 `feat/visual-tech-refresh`。

---

## 1. Goals / Non-goals

### Goals

| # | 目标 | 可验证的完成信号 |
|---|---|---|
| G-1 | **三套割裂的视觉语言收敛成一套**，且全部走令牌 | 全仓硬编码颜色从 93 处（11 文件）降到 **0**；`grep -nE '#[0-9a-fA-F]{3,8}\b' src --include=*.vue` 只剩注释 |
| G-2 | 深色成为主场（dark-first），暗色下有真实层次 | 暗色 elevation 由面色亮度阶 + 1px 内高光承担；`--shadow-sm` 在暗色档不再是不可见的纯黑投影 |
| G-3 | 建立「发光 / 玻璃 / 网格 / 渐变 / 动效」五类令牌 | 新增 `--glow` `--glow-strong` `--surface-glass` `--border-ctl` `--dur-*` `--ease-*`；4 处各写各的 accent 发光收敛成 1 处 |
| G-4 | 拉丁字体升级带来「技术调性」 | Inter var + JetBrains Mono（latin 子集，合计 ≤65KB）自托管；全站 ID / 金额 / 代际号 / endpoint / 时间戳统一 `mono + tabular-nums` |
| G-5 | 排版有节奏 | 消灭 ≤10px 硬编码字号 **80 处**（ValidateView 24 + DetailView 21 + DemoPanel 16 + ListView 11 + EditorView 8）。<br>**评审 Y6 更正**：全仓共 **109 处**，剩余 29 处分布在 LoginView(6)、DemoNav(6)、DemoHome(5)、TierRuler(3)、Gauge(2)、PlaybooksView(2)、WindowBar(1)、ToastHost(1)、SidePanel(1)、PageHeader(1)、DynRowTable(1)——其中 **viz 三件的 6 处被 D6「几何不动」挡着，本轮不动**；其余 23 处随步骤 5/6 顺带处理。<br>`--fs-2xl/3xl` 真正用起来；Tier A 页有 hero 级标题 |
| G-6 | 动效从「几乎没有」到「有编排」 | 5 份重复 `@keyframes spin` + 2 份 shimmer 收敛进 `effects.css`；Tier A 有入场 stagger；hover 有微交互 |
| G-7 | 顺带修掉 8 个既有缺陷 | D10 表 F1–F8 全部闭环 |

### Non-goals（本轮明确不做）

| # | 不做 | 理由 |
|---|---|---|
| N-1 | 不改路由、不改 API 契约、不改 store、不改业务逻辑 | 用户诉求是观感层 |
| N-2 | 不重排信息架构 | EditorView 的步骤条 + 完成度侧板、ListView 的表格 + 侧板、DemoHome 的 hero + 目录，IA 本身是合理的 |
| N-3 | **不为科技感造假数据**（D7） | `ListView.vue:348-356` 的「不画假图」立场保留 |
| N-4 | 不引入 UI 组件库、不引入动效库（D9） | 保住 `prefers-reduced-motion` 一处兜底 |
| N-5 | 不引入 CJK web font | 3–10MB，内网部署不可接受 |
| N-6 | 不做 `data-skin` 双皮肤（D2） | 结构性改版本来就不能靠它回退 |
| N-7 | 不补后端指标接口（`/decision/v1/metrics`、`by-activity`） | 属后端排期，本轮把那块说明卡改成终端风面板即可 |
| N-8 | 不改 132 条 data-testid、不改 6 个测试耦合组件的 class 名与 DOM 层级 | 见 §10 契约清单；**唯一例外是 LoginView（A5 已获授权）** |

---

## 2. 视觉方向：「深空遥测」Deep Space Telemetry

### 2.0 样板屏（可直接打开对比三个候选）

`style-tile.html`（本目录，自包含、零依赖，浏览器直接打开）按同一块「活动工作台」屏渲染三个候选：
`.skin-a` 深空遥测 / `.skin-b` 遥测栅格 / `.skin-c` 极光玻璃。

> ⚠ **样板屏里那排 KPI（决策 QPS / 规则命中率 / 决策 P99）是纯占位示意，不代表实现承诺。**
> 按 D7「不画假图」与 N-7，后端目前没有 `GET /decision/v1/metrics` 与 `by-activity`，
> 真实实现里这块**仍是「指标尚未接入」的诚实面板**（只是换成终端风），不会画没有数据源的图表。
> 样板屏保留它，只是为了展示"数字 + 发光折线"这一层视觉语言长什么样——**接口补齐之日即可原样启用**。

### 2.1 调性与参考的具体借鉴点

| 参考 | 借鉴的**具体模式** | 落到本项目哪里 |
|---|---|---|
| **Linear** | ① 面色亮度阶做 elevation（不用投影）② 侧栏 active = soft 底 + 左侧 3px 强调条 ③ 用字重而非字号拉层级 | ① `--bg`/`--bg-elev`/`--bg-soft`/`--bg-hover` 四档亮度阶 ② `SidebarNav.vue:144-152` **结构已经就位，只换色** ③ `--fw-medium/semibold/bold` 全用满 |
| **Vercel Dashboard** | 可辨的中性描边 + 近零装饰的密度屏 | Tier C（ListView / EditorView）的处理方式 |
| **Grafana** | 发光序列线 + 阈值虚线 + 深蓝灰底网格 | `Sparkline.vue` 加 `drop-shadow` + 渐变面积；`Gauge.vue:40` 的 `--rule` 临界线正好对上 |
| **Raycast** | 控件顶部 1px 内高光；小圆角控件配大圆角面 | `Button.vue` / `Field` 类控件 `inset 0 1px 0 rgba(255,255,255,.06)`；`--radius-sm 7px` vs `--radius-lg 14px` |
| **Warp / iTerm** | 终端面 + 绿点指示灯 | `DemoPanel.vue:350-353` **已有**，收编成令牌后复用到 DetailView 的 DRL 预览、ValidateView 的决策轨迹 |
| **motionsites.ai** | 暗色主场 + 动效预览的"命名语义场"（Signal / Relay / Nocturne） | 只取"暗色是主场、光取代投影"两条；**不取**它的营销落地页排版——它卖的是 landing page，不是产品内页 |
| **saasinterface.com** | 收的是**产品内页**：dashboard / forms / side panel / filter / bulk action | 证明诉求不止首页——本仓库恰好有 `SidePanel.vue` / `BulkBar.vue` / `DynRowTable.vue`，它们同样要好看 |
| **land-book.com** | 把 **Dark Colors** 与 **Gradient** 并列为独立 Style 标签 | 即"科技感"的最大公约数；渐变只在 Tier A 与主按钮上用 |

> **签名色的来源不是凭空选的**：靛紫取自 `LoginView.vue:222` 已有的品牌面板 `#4f46e5`——本轮是把已经存在于登录页的科技感**升到令牌层并铺开**，而不是另起炉灶。

### 2.2 完整令牌映射表

> 纪律：**90 个既有变量名一个不删不改**，只改值；新增 12 个名。
> 「票据语义」的 5 个名（`--seam` / `--rule` / `--rule-faint` / `--grain` / `--notch`）**就地改语义、不改名**（D6）。

#### (a) 主题相关 · 暗色档（`:root` 裸块 = 新的基线）

| 变量 | 旧值 | **新值** | 语义 |
|---|---|---|---|
| `--bg` | `#0b1211` | `#0A0B10` | 页底：深空（色相 232°，非纯黑） |
| `--bg-elev` | `#131c1a` | `#12141B` | 卡片 / 表格 / 侧板 |
| `--bg-soft` | `#182220` | `#171A22` | 工具条 / 表头 |
| `--bg-hover` | `#1f2b29` | `#1F2330` | 悬停面 |
| `--bg-sunken` | `#0e1615` | `#06070C` | 轨道底 / 终端面 |
| `--border` | `#243230` | `#272B38` | 常规分隔 |
| `--border-strong` | `#354744` | `#3A4054` | 强分隔 |
| **`--border-ctl`** 🆕 | — | `#5C6478` | **输入控件边界专用**（≥3:1，见附录 A） |
| `--seam` | `#5b736e` | `#2F3546` | 改义：栅格 / 扫描线 |
| `--rule` | `#c9d6d3` | `#C9D0E4` | 改义：高亮基准线（全站权重最高的线） |
| `--rule-faint` | `#242935` | `#1C2029` | 表格行线（**不许更浅**，办公 TN 屏会消失） |
| `--grain` | `rgba(200,255,246,.030)` | `rgba(150,165,220,.055)` | 改义：网格线色 |
| `--text` | `#e4ecea` | `#E8EAF2` | |
| `--text-soft` | `#a0b0ac` | `#A9B0C4` | |
| `--text-faint` | `#8b9c98` | `#8A93AC` | bg 6.41 / elev 6.00 / soft 5.67 ✓ |
| `--text-invert` | `#1a0510` | `#0A0713` | 压在 accent 上的字 |
| `--accent` | `#f45ca0` | **`#8B7BFF`** | 签名色：靛紫 |
| `--accent-2` | `#ff86bb` | **`#22D3EE`** | 二级 / 数据色：青（遥测） |
| `--accent-soft` | `#2a1420` | `#191631` | |
| `--accent-hover` | `#ff86bb` | `#A594FF` | |
| `--accent-line` | `#5e2340` | `#3B3470` | |
| `--dv-1..5` | 粉/蓝/青/金/灰 | `#8B7BFF` `#22D3EE` `#3DDC97` `#F5B544` `#94A1BF` | 图表 5 序列 |
| `--ramp-1..5` | 青灰阶 | `#2B3044` `#3A4054` `#4B5268` `#5F6780` `#78819C` | 占比条中性明度阶 |
| `--ok` / `--ok-soft` | `#4fc07f` / `#0f2419` | `#3DDC97` / `#0C2A1D` | |
| `--warn` / `--warn-soft` | `#e0a33a` / `#2a1f0c` | `#F5B544` / `#2A2009` | |
| `--err` / `--err-soft` | `#f4756b` / `#2c1513` | `#FF7A72` / `#2E1512` | |
| `--blue` / `--blue-soft` | `#6ba1f5` / `#12203a` | `#5AA9FF` / `#0E1E38` | |
| `--green/--gold/--red`(别名) | 转发 | **不动，继续转发** | 保既有组件零改动 |
| `--shadow-sm` | `0 1px 2px rgba(0,0,0,.35)` | `inset 0 1px 0 rgba(255,255,255,.055)` | **暗色下 elevation 靠内高光** |
| `--shadow` | `0 1px 3px…` | `inset 0 1px 0 rgba(255,255,255,.06), 0 1px 2px rgba(0,0,0,.5)` | |
| `--shadow-md` | `0 4px 12px…` | `inset 0 1px 0 rgba(255,255,255,.07), 0 8px 24px rgba(0,0,0,.55)` | |
| `--shadow-lg` | `0 14px 34px…` | `inset 0 1px 0 rgba(255,255,255,.08), 0 24px 40px rgba(0,0,0,.66)` | **blur 从 60px 降到 40px**（评审 R4：原值违反本计划自己的「≤40px」红线）。**辉光已拆出**，见下行 |
| **`--shadow-lg-glow`** 🆕 | — | `0 0 40px color-mix(in srgb,var(--accent) 8%,transparent)` | **单独一个名**，只给模态/侧板叠加用：`box-shadow: var(--shadow-lg), var(--shadow-lg-glow)`。<br>**必须拆开的理由（评审 R4）**：`--shadow-lg` 的使用点包含 `AppLayout.vue:56` 的**手机 off-canvas 抽屉**与 `IdentityBar.vue:115` popover；不拆的话 §7.4「<768 关闭辉光」在全局令牌上**物理做不到** |
| **`--glow`** 🆕 | — | `0 0 16px color-mix(in srgb,var(--accent) 32%,transparent)` | 收编 4 处手写发光（真实行号见 §8 修正） |
| **`--glow-strong`** 🆕 | — | `0 0 28px color-mix(in srgb,var(--accent) 45%,transparent)` | |
| **`--surface-glass`** 🆕 | — | `color-mix(in srgb, var(--bg-elev) 72%, transparent)` | 玻璃面底色。**仅顶栏与 hero-stats 使用**，见 D8-a |
| `--scrim` | `rgba(0,0,0,.58)` | `rgba(4,5,10,.72)` | |
| `--focus-ring` | `0 0 0 3px var(--accent-soft)` | `0 0 0 2px var(--bg-elev), 0 0 0 4px var(--accent)` | 暗色下旧值等于没有焦点环。<br>**第一层用 `--bg-elev` 不用 `--bg`（评审 Y4）**：实测 8 个使用点（`ListView.vue:661`、`ValidateView.vue:201`、`EditorView.vue:635`、`DemoHome.vue:222`、`DemoNav.vue:140`、`DemoPanel.vue:347`、`BulkConfirm.vue:154`、`LoginView.vue:354`）**全部是坐在 `--bg-elev`/`--bg-soft` 上的输入容器**，用页底色会画出一圈可见暗环。<br>全局键盘环仍走 `tokens.css:243-247` 的 `outline + outline-offset`（不占布局、不被 overflow 裁剪） |
| **`color-scheme`** 🆕 | 全仓零声明 | `:root { color-scheme: dark }` / light 档 `color-scheme: light` | **评审 Y11 / 缺陷 F10**：不加则 dark-first 后 `<select>` 弹层、`<input type=search>` 清除按钮、**全站滚动条**都是浅色 UA 样式压在深空底上 |

#### (b) 主题相关 · 浅色档（`:root[data-theme="light"]` + `@media (prefers-color-scheme: light)`）

| 变量 | 旧值 | **新值** |
|---|---|---|
| `--bg` | `#edf1f0` | `#F4F5FA` |
| `--bg-elev` | `#ffffff` | `#FFFFFF` |
| `--bg-soft` | `#f4f7f6` | `#F8F9FD` |
| `--bg-hover` | `#e4eae8` | `#EDEFF7` |
| `--bg-sunken` | `#e8edec` | `#E9EBF4` |
| `--border` | `#d8e0de` | `#E1E4F0` |
| `--border-strong` | `#b6c3c0` | `#C3C8DC` |
| **`--border-ctl`** 🆕 | — | `#868DA6` |
| `--seam` | `#8fa19d` | `#CBD1E2` |
| `--rule` | `#2b3937` | `#1B1F33` |
| `--rule-faint` | `#e3e5ea` | `#E7E9F2` |
| `--grain` | `rgba(16,45,42,.028)` | `rgba(40,50,110,.035)` |
| `--text` | `#0e1917` | `#0D1020` |
| `--text-soft` | `#43514e` | `#4A5169` |
| `--text-faint` | `#5f6d6a` | `#5F677F` |
| `--text-invert` | `#ffffff` | `#FFFFFF` |
| `--accent` | `#a4256b` | **`#5B4BE8`** |
| `--accent-2` | `#c2528d` | **`#0C6B85`**（青，压暗一档求余量）<br>⚠ **评审 Y3 更正**：原写「`#0E7490` 在 hover 面只有 4.43 ❌ 不达标」是**错的**——那 4.43 是按**旧**浅色 `--bg-hover #e4eae8` 算的；在**新** `--bg-hover #EDEFF7` 上实为 **4.67，达标**。`#0E7490` 本可用，仍取 `#0C6B85` 只是为留余量，不是因为前者不合格 |
| `--accent-soft` | `#fbe9f2` | `#EEECFF` |
| `--accent-hover` | `#86164f` | `#4A3AD4` |
| `--accent-line` | `#edbcd5` | `#C7C2FA` |
| `--dv-1..5` | — | `#5B4BE8` `#0C6B85` `#0F7A48` `#8A5300` `#5A6076` |
| `--ramp-1..5` | — | `#D5D9E8` `#B9BFD4` `#9BA2BC` `#7D85A2` `#5F6784` |
| `--ok` / `--warn` / `--err` / `--blue` | — | `#0F7A48` / `#8A5300` / `#B3261E` / `#1A4FBE` |
| `--*-soft`（语义色） | — | **沿用现值**（`#dcf0e3` / `#f8ecd3` / `#fae3e1` / `#dee8fb`）——它们不是问题所在，改动只增风险 |
| `--shadow-*` | 中性黑 | **数值不动**（浅色档主动放弃发光，退回干净描边 + 极轻投影） |
| `--glow` / `--glow-strong` | — | 同暗色公式（自动跟随浅色 accent，强度天然更弱） |
| `--surface-glass` | — | `color-mix(in srgb, var(--bg-elev) 78%, transparent)` |
| `--scrim` | `rgba(16,24,40,.40)` | `rgba(13,16,32,.44)` |
| `--focus-ring` | — | 同暗色双环公式 |

#### (c) 主题无关

| 变量 | 旧值 | **新值** | 备注 |
|---|---|---|---|
| `--radius` | 6px | **10px** | 面 |
| `--radius-sm` | 4px | **7px** | 控件（**85 处引用，这一处改值收益最大**） |
| `--radius-lg` | 10px | **14px** | 大面 |
| `--radius-pill` | 999px | 不动 | Badge/chip 依赖 |
| `--notch` | 13px | **0px** | 熄灭票券齿孔；Seam 自动退化成点线 |
| `--hairline` | 1px/0.5px@2dppx | 不动 | 125% 缩放机器仍吃 1px |
| `--font-ui` | 系统栈 | `"Inter var","Inter",-apple-system,BlinkMacSystemFont,"Segoe UI","PingFang SC","HarmonyOS Sans SC","Microsoft YaHei","Noto Sans SC",Arial,sans-serif` | **拉丁必须排在中文前** |
| `--font-code` | 系统 mono | `"JetBrains Mono","SFMono-Regular","Cascadia Code",Menlo,Consolas,monospace` | |
| `--sans` / `--mono` | 转发 | 不动 | |
| `--sp-1..8` / `--gap-*` | — | **全部不动** | 间距体系没问题 |
| `--touch-min` | 44px | 不动 | |
| `--fs-xs..2xl` | — | 不动 | |
| `--fs-3xl` | 30px | **36px** | Tier A hero |
| **`--fs-4xl`** 🆕 | — | **44px** | 仅 `/login`、`/home`、`/demos` 的 h1 |
| `--lh-*` / `--fw-*` | — | 不动 | |
| `--shell-topbar-h` | 52px | **56px** | 给玻璃条留厚度 |
| `--shell-sidebar-w` / `--content-max` / `--content-max-wide` / `--page-gutter` | — | 不动 | |
| `--z-*`（6 个） | — | **全部不动** | 880<900<950<1000 是踩过坑的序 |
| `--row-h` / `--row-pad-y` / `--tbl-fs` / `--meter-h` / `--panel-w` | — | 不动 | 密度体系不动 |
| **`--dur-fast/mid/slow`** 🆕 | — | `120ms` / `180ms` / `320ms` | |
| **`--ease-out`** 🆕 | — | `cubic-bezier(.2,.7,.3,1)` | |
| **`--ease-spring`** 🆕 | — | `cubic-bezier(.34,1.56,.64,1)` | 仅入场，不用于 hover |
| **`--accent-ink`** 🆕 | 未定义（缺陷 F2） | `color-mix(in srgb, var(--accent) 14%, transparent)` | 修 `TierRuler.vue:157` |

#### (d) body 背景换代（G2 的直接解法）

```css
/* 删除 tokens.css:231-234 的两道 32° 斜纹，改为： */
body {
  background-color: var(--bg);
  background-image:
    linear-gradient(var(--grain) 1px, transparent 1px),
    linear-gradient(90deg, var(--grain) 1px, transparent 1px);
  background-size: 44px 44px;
}
/* 光晕独立成层：绝不用 background-attachment: fixed（iOS Safari 退化，光斑跟着滚） */
body::before {
  content: ''; position: fixed; inset: 0; z-index: -1; pointer-events: none;
  background:
    radial-gradient(60rem 40rem at 12% -10%, color-mix(in srgb, var(--accent) 12%, transparent), transparent 70%),
    radial-gradient(50rem 36rem at 92%  8%,  color-mix(in srgb, var(--accent-2) 9%, transparent), transparent 70%);
}
```
**网格底的正确载体是 `.shell-content`，不是 `body`（评审 Y7）**：

原方案想「在 ListView/EditorView 根元素加 `background: var(--bg)` 盖掉网格」——**实现不了**。ListView 的根是无 class 的 `<section data-testid="list-view">`（`:322`），且 `.shell-content` 带 `padding: var(--page-gutter)` + `max-width` 居中（`AppLayout.vue:46-47`）：给 section 加底色只会 ① 页面四周留一圈可见网格边框；② 内容不满一屏时下方仍是网格。

**改为**：网格铺在 `.shell-content` 上，由 `AppShell.vue` 按 `route.name` 切一个 `data-grid="off"` 属性，Tier C 两条路线关闭。
> ⚠ 不用 `:has()`——Firefox <121 静默失效（`tokens.css:151` 已记载）。用显式属性选择器。

---

## 3. 路由与页面流

**路由零改动**（N-1）。11 个可达页面按 D1 分三档：

```
/                       → redirect /home
/home          Tier A   概览：hero + 两大区域 + 最近活动
/login         Tier A   登录：已是科技风，收编硬编码色 + 降 blur 到 14px
/auth/callback   —      中转页（仅补一个 loading 视觉，36 行）
/console/activities        Tier C  活动工作台（791 行表格 + 侧板）
/console/playbooks         Tier B  玩法模板卡片网格
/console/activities/new    Tier C  新建（4 步 + 完成度侧板）
/console/activities/:id    Tier B  详情（hero + 代码面板）
/console/activities/:id/edit Tier C 编辑（复用 EditorView）
/console/validate          Tier B  优惠验证（左表单 / 右决策结果 + 轨迹）
/demos                     Tier A  规则能力中心（已有深色 hero）
/demos/:demoId             Tier B  在线调用面板（已有终端块）
```

---

## 4. 组件树

```
App.vue
├── [login/callback] 裸渲染
│   ├── LoginView.vue ................ ✎ 重设计（收编 5 个硬编码色 / blur 20→14 / 同步 4 条 class 断言）
│   └── CallbackView.vue ............. ✎ 加 loading 视觉（现为纯文字）
└── AppShell.vue
    ├── AppLayout.vue ................ ✎ topbar/sidebar 玻璃化（@supports + -webkit- 双写）
    ├── TopBar.vue / IdentityBar.vue . ✎ 换样式（IdentityBar #fff → --text-invert）
    ├── SidebarNav.vue ............... ✎ 只换色（active 结构已是 Linear 式，class 名钉死不改）
    ├── PageTransition.vue ........... ✎ 时长走 --dur-mid + --ease-out
    └── <router-view>
        ├── HomeView.vue ............. ✎ Tier A：加 Hero + 修 F6（mergeRows + 排序）
        ├── ListView.vue ............. ✎ Tier C：只换色/描边/字体；修 F3 手机 toolbar
        ├── EditorView.vue ........... ✎ Tier C：同上
        ├── DetailView.vue ........... ✎ Tier B：hero + 代码面板令牌化；修 F4 缺 v-else
        ├── ValidateView.vue ......... ✎ Tier B：结果区高光；消灭 24 处 ≤10px 字号
        ├── PlaybooksView.vue ........ ✎ Tier B：券卡改光卡
        ├── DemoHome.vue ............. ✎ Tier A：hero 硬编码色收编；修 F5 sticky backdrop-filter
        └── DemoPanel.vue ............ ✎ Tier B：终端块令牌化并复用

shared/ui/（复用为主）
├── 只换样式，结构够用 ── Badge / Banner / Kv / Segmented / PageHeader / Section /
│                        EmptyState / ToastHost / ConfirmDialog / SidePanel / Skeleton
├── 需加结构层 ────────── Card.vue（加 ::before 渐变描边承载层 + variant + hover）
│                        Button.vue（primary 渐变 + hover 辉光 + 顶部内高光）
│                        Icon.vue（stroke 默认 1.75 → 1.5，全站 135 处自动跟随）
├── 🆕 Hero.vue ───────── Tier A 三页共用的 hero 原语（kicker / title / desc / actions / stats 插槽）
├── 🆕 Stat.vue ───────── KPI 数字块（tabular-nums + 可选 glow + 脉冲指示点）
└── ⌦ Field.vue ───────── 零引用死组件：本轮**删除**（同时删 Gauge/Sparkline 的"零引用"状态：见下）

shared/viz/（保几何换语汇，D6）
├── Seam.vue / Receipt.vue / WindowBar.vue / TierRuler.vue ── ✎ 只换配色线型；class 名与几何不动（27 条单测继续绿）
└── Gauge.vue / Sparkline.vue ── ✎ 加发光/渐变面积，并**接入真实数据**：
                                   Sparkline → DemoPanel 的 elapsedMs 历史；Gauge → ValidateView 命中金额占比
                                   （若不接入则按 §11 R-6 删除，不留零引用组件）

shared/styles/
├── tokens.css ....... ✎ 全令牌换值 + 新增 12 个名 + body 背景换代
└── 🆕 effects.css ... 收敛 5 份 spin + 2 份 shimmer；新增 sweep / ping / rise；
                       utility：.u-glass / .u-grid / .u-glow / .u-gradient-text / .u-stagger

shared/
└── 🆕 useReveal.ts ... 15 行 IntersectionObserver（内部先判 prefers-reduced-motion 直接跳过）
                        **仅 Tier A 使用**；与 useScrollLock/useDensity 同构的模块单例风格
```

---

## 5. 逐页状态与边界

现状四态覆盖度已勘察清楚（见下表"现状"列）。**本轮不新增业务态，只做两件事**：① 把 5 套 loading / 6 套 empty 收敛成统一表达；② 补 3 个真实缺口。

| 页面 | loading 现状 | empty 现状 | error 现状 | 本轮动作 |
|---|---|---|---|---|
| HomeView | `Skeleton :rows=4` | `EmptyState inbox` | `Banner err` + 重试 | 骨架换扫光样式；**修 F6** |
| ListView | `Skeleton :rows=5` **仅首次**；刷新只有 icon spin | `EmptyState`（区分无匹配/无数据） | `Banner err` + retry | 刷新态补顶部 2px 进度条（不遮内容）；**修 F3** |
| EditorView | `Skeleton :rows=7` | N/A | `Banner` + dict-warning + conflict-hint | preview 三态由纯文字色改为「点 + 色 + 文案」三编码 |
| DetailView | `Skeleton :rows=7` | **缺** | `Banner` + 重新加载 | **修 F4**：补 `v-else` → `EmptyState` |
| ValidateView | **自绘** 470px 呼吸块 | `EmptyState scale` + `.no-trace` | `Banner err` | 自绘 loading → 统一 `Skeleton`；消灭 24 处 ≤10px 字号 |
| PlaybooksView | **无** | **无** | **无** | 数据来自静态 `playbooks.ts`，**保持无**（不造不存在的态），但补一条注释说明「接后端时必须补三态」 |
| DemoHome | 无 | 自绘 `.empty-search` | 无 | 空态改用 `EmptyState`（统一） |
| DemoPanel | **自绘** breathe+shimmer | 自绘 `.idle` / `.notfound` | `Banner err` | loading 统一 `Skeleton`；`.idle` 改终端风「等待输入」提示 |
| LoginView | `.status-spinner` | N/A | `.login-alert` ×4 | alert 统一走 `Banner`；spinner 走 `effects.css` |
| CallbackView | **纯文字** | N/A | `.cb-err` | 补 spinner + 品牌感中转视觉 |

**边界情况（逐条）**：
- 后端不可达 → 全部数据页显示 `Banner err` + 重试，**不白屏**（现状已满足，换代不得破坏）
- 租户切换中途 → 请求 `AbortController` 取消（现状已有），骨架不得残留
- `prefers-reduced-motion` → 所有新增动效被 `tokens.css:267-275` 全局灭掉，且**不得有只由动效传达的信息**
- 打印 / 投影 → 新增 `@media print` 强制白底、去背景图、去发光
- 125%/150% 缩放（DPR 1.25/1.5，不进 `2dppx` 分支）→ 网格线 alpha ≤.07、`background-size` ≥40px、辉光 ≤3/屏

---

## 6. API 契约

**零改动。** 本轮不新增、不修改任何请求。以下为受影响页面所依赖的现有契约（只作回归核对用）：

| 页面 | 依赖 | 说明 |
|---|---|---|
| HomeView / ListView | `GET /activity-marketing/list`（`listActivities`） | 返回的是**行**不是活动，同活动 v1+v2 会出现两行 → F6 用 `benchModel.ts` 的 `mergeRows` 归并 |
| EditorView | 活动增改 + `useDictStore` 字典 | 不动 |
| DetailView | 活动详情 + manage | 不动（只补 `v-else`） |
| ValidateView | 决策计算 / 赠品查询 | 不动 |
| DemoPanel | `demos/catalog.ts` 里 33 个端点 | 不动 |
| LoginView | `auth/config` + Casdoor OIDC PKCE | 不动；`#login-tenant` 这个 **DOM id 是 e2e 载荷**（`e2e-oidc-v2.mjs:31`），重设计不得改 |
| **未接入** | `GET /decision/v1/metrics`、`GET /decision/v1/by-activity` | N-7：本轮不补，说明卡改成终端风面板 |

---

## 7. 响应式与移动端适配策略

### 7.1 断点正典与现存偏离

**正典（`tokens.css:14-16`，本轮不改）**：

| 区间 | 行为 |
|---|---|
| ≥1024 | 桌面：侧栏常驻、多栏 |
| 768–1023 | 平板：侧栏 docked、多栏塌单列、列表卡片化 |
| <768 | 手机：侧栏 off-canvas 抽屉 |
| ≤560 | 身份条收 popover |
| ≥1280 | SidePanel 由 overlay 改 push（`SidePanel.vue:28`） |

**⚠ 实测偏离**：正典外散着 **8 个不同值**——`460` / `640` / `680` / `700` / `760` / `820` / `1100` / `1180`(×4)。（`767`(×2) 是 `<768` 的正确写法，`1023`(×6) / `560`(×6) / `1280` 都属正典，不算偏离。）

> **本轮动作**：不做大规模断点重整（超出观感层），但**新写的任何样式一律只用正典四档**；并在 `tokens.css` 断点注释下追加一行「偏离清单」，把这 9 个记录在案供后续收敛。

### 7.2 逐页小屏策略

| 页面 | 768–1023（平板） | <768（手机） | 本轮新增/修改 |
|---|---|---|---|
| 全局外壳 | 侧栏 docked | 侧栏 off-canvas 抽屉（`translateX`，不占布局宽度）+ scrim | 玻璃 topbar **在 <768 与 `(pointer:coarse)` 下退回不透明**（D8） |
| HomeView | 两张区域卡塌单列（`max-width:1023`） | 同 | hero 字号 `--fs-4xl → --fs-2xl`；光晕缩小 |
| ListView | 整表塌成券卡（`:740-772`）；`min-width:994px` 声明在 `:677`、解除在 **`:746`** | 同 | **修 F3——落点是 `@media (max-width:560)` 块（`:779`），加 `.search-box { flex: 0 0 auto }`**。<br>⚠ **不要**按 767px 开新块：561–767 区间 `.toolbar` 仍是 row + wrap，把 basis 从 240 改成 auto 会让 `.search-box input{width:100%;min-width:0}` 解成不可预测值，搜索框不再稳定独占一行。桌面 `:660` 的 `flex: 0 1 360px` 完全不碰 |
| EditorView | 双栏塌单列，完成度侧板下沉 | 同 | 步骤条横向滚动而非换行 |
| DetailView | 双栏塌单列 | 同 | 代码面板 `overflow-x:auto` + 字号不缩到 <12px |
| ValidateView | 左右栏塌上下 | 同 | 消灭 ≤10px 字号后手机可读性直接提升 |
| PlaybooksView | 卡片网格 2 列 → 1 列 | 1 列 | 卡片 hover 位移在触屏改为 `:active` 反馈 |
| DemoHome | hero 两栏塌单列；`.filters` 横向滚动 | 同 | hero stats 3 格 → 横向滚动条；**修 F5** |
| DemoPanel | 请求/响应上下排 | 同 | 终端面 `overflow-x:auto` |
| LoginView | 左右分栏塌成单栏（`:379-399`） | 单栏 | blur 20→14；<768 关闭 `backdrop-filter` |

### 7.3 触屏交互替换（hover-only 的处置）

实测全仓 **60 条 `:hover` 规则，其中 5 条带位移**。换代会新增 hover 辉光/位移，**必须逐条给触屏替代**：

| 桌面交互 | 触屏替代 |
|---|---|
| 卡片 hover 位移 + 辉光 | `:active` 时给 `transform: scale(.985)` + 保留辉光；`@media (hover: hover)` 包裹纯 hover 效果 |
| 表格行 hover 高亮 | 行本身可点（现状已是），`:active` 给 `--bg-hover` |
| 主按钮 hover 辉光 | `:active` 给 `--glow`（触屏无 hover，但有 active） |
| 图标按钮 tooltip（若有） | 不新增依赖 hover 才能获知的信息 |

**硬规则**：`@media (hover: hover) and (pointer: fine)` 包裹所有**纯装饰性** hover；功能性反馈一律同时提供 `:active`。

### 7.4 移动端硬约束（不可倒退）

1. `(pointer: coarse)` 下所有可交互件 ≥44px（`tokens.css:250-260` 全局兜底 + 12 个组件各自补）；紧凑密度下强制回落 44px
2. **零横向溢出**：**10 个**断言点（不是 5 个）断言 `document.body.scrollWidth - window.innerWidth`，**阈值 `<= 4`**，视口 1440 / 768 / 390
3. safe-area：现仅 `AppLayout.vue:57` 一处 `padding-bottom: env(safe-area-inset-bottom)`。**本轮追加**：抽屉顶部 `env(safe-area-inset-top)`、固定底部元素（BulkBar）`env(safe-area-inset-bottom)`
4. `<768` 与 `(pointer:coarse)`：**整体关闭 `backdrop-filter`**（顶栏退回不透明 `--bg-elev`）、关闭 body 光晕层（改纯色）、**关闭 `--shadow-lg-glow`**（这就是 R4 要把辉光从 `--shadow-lg` 拆出来的原因——手机 off-canvas 抽屉走 `--shadow-lg`，不拆则辉光关不掉）、循环动画只保留脉冲点
5. 低端安卓 / 微信 WebView：`backdrop-filter` 与大面积 radial 是掉帧主因 → 由第 4 条兜住

---

## 8. 文件级改动清单

| 阶段 | 文件 | 改什么 |
|---|---|---|
| **P0** | `frontend/src/shared/styles/tokens.css` | 全令牌换值（§2.2 a/b/c）+ 新增 13 名 + body 背景换代（§2.2 d）+ `--focus-ring` 双环 + **`color-scheme`（F10）** + 重算 WCAG 数字写回注释 |
| **P0**（评审 R2 扩容） | **13 处 / 9 文件**的硬编码 `#fff`：`Button.vue:30,35`、`HomeView.vue:168`、`IdentityBar.vue:96`、`console/condition-tree/ConditionGroup.vue:84`、`console/pages/EditorView.vue:639,654`、`console/pages/ValidateView.vue:199,201`、`demos/DemoPanel.vue:311,362,392`、`console/pages/DetailView.vue:242`、`views/LoginView.vue:362,365` | → `var(--text-invert)`。**必须先于换 accent**：白字 on `#8B7BFF` = 3.29:1，on `#22D3EE` = **1.81:1**，而 `.run`/`.primary` 是两色渐变——**按钮右半边就是 1.81** |
| **P0** 🆕（评审 R1 / F9） | `frontend/src/shared/layout/TopBar.vue:14-16` | 主题 toggle 初值：`data-theme` 属性缺席时从 `matchMedia('(prefers-color-scheme: light)')` 反推 + 补单测。**不改则 dark-first 上线当天，每个新用户第一次点主题键都「视觉零变化」** |
| **P0** | `frontend/src/console/benefit/TierRuler.vue:157` | `--accent-ink` 修 F2 |
| **P0** 🆕（评审 R6） | `deploy/nginx.conf` | 开 gzip（D11 已从可选升为硬前置，否则性能红线是空谈） |
| **P1** | 🆕 `frontend/src/shared/styles/effects.css` + `main.ts` 加一行 import | 收敛**全仓 9 个 `@keyframes`**（评审 Y5 补齐）：spin ×5、shimmer ×2、**`ValidateView.vue:202` `pulse` 与 `DemoPanel.vue:372` `breathe` 逐字相同**、`BulkBar.vue:65` `bulk-in`、`LoginView.vue:376` `aurora-float`；新增 sweep/ping/rise；utility class |
| **P1** | `frontend/src/shared/ui/{Card,Button,Icon,Skeleton}.vue` | 结构层 + 渐变主按钮 + stroke 1.75→1.5（`Icon.test.ts:17,21` 测的是显式传 `stroke:2`，**改默认值不破测试**）+ 扫光骨架 |
| **P1** | `frontend/src/shared/layout/AppLayout.vue` | **仅 `.shell-topbar` 玻璃化**（D8-a：侧栏玻璃化已取消）；`@supports` + `-webkit-` 双写 + `<768`/`coarse` 关闭 |
| **P1** 🆕（评审 Y7） | `frontend/src/shared/layout/AppShell.vue` + `AppLayout.vue` | 网格底改铺 `.shell-content`（不是 body），由 AppShell 按 `route.name` 切 `data-grid="off"`。**原计划「在 ListView 根 section 加底色」实现不了**——根是无 class 的 `<section data-testid="list-view">`（`:322`），且 `.shell-content` 有 padding + `max-width:auto` 居中，加底色只会四周留一圈网格边、内容不满屏时下方仍是网格 |
| **P1** | 🆕 `frontend/src/assets/fonts/*.woff2`（**3 个**：Inter var latin、JetBrains Mono Regular、Mono Bold）+ tokens.css `@font-face` | **必须放 `src/assets/` 不是 `public/`**（`nginx.conf:47` 的 `location ^~ /ui/assets/` 才有 `immutable` 长缓存；public 下的文件落到 `/ui/fonts/` 会掉进 `:69` 的 `no-cache` 块）。<br>**评审 B3 更正**：JetBrains Mono 标准发行**不是可变字体**，而 `DemoPanel.vue:311` `.method` 需要 bold → 必须两个文件；Inter var latin 子集实际常在 45–90KB。预算相应上调（§10.4）。⚠ **preload 待解**：`@font-face` 写在 CSS 里意味着要先下 CSS 才发现字体，`swap` 会实打实闪一次；hash 文件名让手写 `<link rel=preload>` 困难——列为实施期开放项 |
| **P1** 🆕（评审 Y5 漏网文件） | `demos/DemoNav.vue`、`console/ConsoleShell.vue`、`demos/DemoShell.vue`、`console/BulkBar.vue`、`console/BulkConfirm.vue`、`console/DynRowTable.vue`、`console/condition-tree/{ConditionGroup,ConditionLeaf,ValueControl}.vue` | 原计划**完全没出现这 9 个文件**。DemoNav 有 6 处 ≤10px 字号 + `--shadow-sm` + `--focus-ring`，且 `DemoShell.test.ts`/`DemoNavigation.test.ts` 断言 `.nav-search input` |
| **P2 门面** | `frontend/src/views/LoginView.vue`（+ 同步 `LoginView.test.ts:59-64`） | 收编 5 个硬编码色；blur 20→14；`#login-tenant` id 不动 |
| **P2 门面** | 🆕 `frontend/src/shared/ui/Hero.vue` + `HomeView.vue` + `DemoHome.vue` | hero 原语；DemoHome 硬编码渐变/六色收编；修 F5 |
| **P2 门面** | 🆕 `frontend/src/shared/ui/Stat.vue`、🆕 `frontend/src/shared/useReveal.ts` | KPI 块 + 入场（仅 Tier A） |
| **P2 门面** | `frontend/src/views/CallbackView.vue` | 补 loading 视觉 |
| **P2 门面** | `frontend/src/home/HomeView.vue` | 修 F6（mergeRows + 排序 + `:key` 去重） |
| **P3 工作区** | `frontend/src/console/pages/ListView.vue` | 只换色/描边/字体；修 F3；「指标未接入」卡改终端风；根元素加 `background: var(--bg)` 盖网格 |
| **P3 工作区** | `frontend/src/console/pages/EditorView.vue` | 同上；preview 三态三编码 |
| **P3 工作区** | `frontend/src/console/pages/{DetailView,ValidateView,PlaybooksView}.vue` | 代码面板/决策结果/券卡换语汇；修 F4；消灭 ≤10px 字号 |
| **P3 工作区** | `frontend/src/demos/DemoPanel.vue` | 终端块令牌化并复用；loading 统一 |
| **P3 工作区** | `frontend/src/shared/viz/*.vue` | 只换配色线型（class 名与几何不动）；Sparkline/Gauge 接真实数据或删除 |
| **P4 可选** | `deploy/nginx.conf` | 开 gzip（D11，需用户点头） |
| **P4 清理** | 删 `frontend/src/shared/ui/Field.vue`（零引用） | 连同其测试引用 |

---

## 9. 实施步骤（按依赖排序，一步一 commit）

> **每步结束都必须能独立 `git revert`**。这是上一轮 `DECISION_RECORD D1` 的教训。

| # | 步骤 | 依赖 | 产出 commit |
|---|---|---|---|
| **0** | **前置（评审 Y8 扩容）**：提交**整棵工作树**——全仓 43 改 + 41 未跟踪（+2014/-407），**不能只提 frontend**（`activityApi.ts` 调的 `/bulk-status` 实现在未提交的后端 Controller 里）。同时 `deploy/nginx.conf` 开 gzip（D11 已升 P0）。然后开 `feat/visual-tech-refresh` | — | `feat: PR-0~PR-6 工作台与玩法模板（前后端）` + `perf(deploy): 网关开启 gzip` |
| **1** | **13 处 / 9 文件**的 `#fff` → `--text-invert` + 修 F2 `--accent-ink` + **修 F9 TopBar 主题初值（+单测）** | 0 | `fix(frontend): 反色文字/accent-ink/主题初值走令牌` |
| **2** | **令牌换代**：tokens.css 全值 + 13 新名 + body 背景 + focus-ring + **color-scheme(F10)** + WCAG 注释重算 | 1 | `feat(ui): 令牌换代为深空遥测配色` |
| **2b** 🆕（评审 B1） | **收编深色面硬编码**：`DemoHome.vue:183` 靛蓝渐变、`LoginView.vue:293` 三段渐变、`DemoPanel.vue:350-357` 与 `DetailView.vue:248` 的终端面 `#11131a/#181b24/#292d3a/#d7dbea` | 2 | `refactor(ui): 硬编码深色面收编进令牌`<br>**为什么必须紧跟步骤 2**：只做步骤 2 的话，旧靛蓝会与新 `#8B7BFF` 并排读成「两套紫」，终端面在新 `#0A0B10` 底上明显偏亮偏蓝 |
| **3** | `effects.css` + 收敛**全部 9 个 keyframes** + main.ts import | 2b | `refactor(ui): 收敛动效关键帧到 effects.css` |
| **4** | 字体自托管（`src/assets/fonts/` 3 个 woff2 + `@font-face` + mono/tabular-nums 铺开） | 2b | `feat(ui): 自托管 Inter/JetBrains Mono 拉丁子集` |
| **5** | 共享组件形制：Card/Button/Icon/Skeleton + **仅顶栏**玻璃化 + AppShell 网格开关 + Y5 的 9 个漏网组件 | 3,4 | `feat(ui): 共享组件形制换代` |
| **⏸ 中途确认点** | **给用户看截图再决定是否继续**（评审 B1：原定放在步骤 2 之后过于乐观——那时硬编码色未收编、组件未改，会明显破相） | 5 | — |
| **6** | Tier A 门面：Hero + Stat + useReveal + HomeView + DemoHome + LoginView(+test) + CallbackView + 修 F5/F6 | 5 | `feat(ui): 门面三屏重设计` |
| **7** | Tier B 展示页：DetailView / ValidateView / PlaybooksView / DemoPanel + viz 语汇 + 修 F4 | 5 | `feat(ui): 展示型工作页视觉换代` |
| **8** | Tier C 密度屏：ListView / EditorView + **修 F3（落点 `@media (max-width:560)`）** + 修 EditorView rail sticky 偏移 + 消灭 ≤10px 字号 | 5 | `feat(ui): 工作台与编辑器视觉换代` |
| **9** 🆕（评审 Y12） | **补自动化验收断言**：`e2e-phone-smoke.mjs` 加 `.search-box` 高度断言（A-6）、`backdrop-filter` 计算值断言（A-8）、reduced-motion 断言（A-9）；新增 `e2e-visual-guard.mjs` | 6,7,8 | `test(e2e): 补移动端与视觉红线断言`<br>**没有这一步，A-6/A-8/A-9 只能靠人工目测** |
| **10** | 清理：删 `Field.vue`（**评审核实：全仓零引用且无测试**，原写「连同其测试引用」没有对象）；Sparkline/Gauge 按 R-6 三选一；补断点偏离清单注释 | 9 | `chore(ui): 清理零引用组件与死令牌` |

**两轮回归门**：步骤 2b 后跑第一轮（颜色）、步骤 8 后跑第二轮（形制）——见 §10。

---

## 10. 测试策略

### 10.1 不可改的契约（改了必红）

| 类别 | 清单 |
|---|---|
| **data-testid** | **（评审 Y10 更正计数）** 原写「132 条」是**文件行数**被当成了条数。实测：契约表 **69 行**、去重 token **94 个**；`src` 里静态 `data-testid` 去重 **130 个**（出现 134 次）；e2e 消费去重 **78 个**（原写 76）。**全部逐字不变** |
| **非 testid 的 DOM 锚点** | `.tr`、`.activity-name`（`e2e-dev-v2.mjs:31`/`e2e-bench.mjs:128`）、`.text-box`、`.no-body`（`e2e-catalog-v2.mjs:73,84`）、`.knob`（`e2e-tier-ruler.mjs:23`）、DOM id `#login-tenant`（`e2e-oidc-v2.mjs:31`） |
| **可访问名** | ListView 行内「详情」按钮的可访问名**精确等于「详情」**（`e2e-tablet-smoke.mjs:37` exact 匹配）；EditorView 里精确文本「阶梯分档」可点 |
| **vitest 断言的 class** | `.login-brand` `.login-form-panel` `.login-compact-head` `.login-primary` `.demos-side` `.demos-panel` `.mode-picker > button`（直接子关系）`.filters button` `.side` `group-link` `.scrim` `.d-ic.danger` `blocked` `active`；viz 的 `.wb/.track/.bar/.now/.cut.l/.cut.r/.tube/.crit/.lab/.amt/.row.hit/.total/.seam/path.empty` |
| **文案禁词** | 新文案**不准出现**「实验 / 学习 / 教程 / Step」（`DemoNavigation.test.ts:17,47`、`DemoPanel.test.ts:26` 三条 `not.toMatch`）。科技感 hero 最容易踩「规则实验室」 |
| **计数断言** | DemoHome 恰好 33 张能力卡、「实时事件」筛出恰好 2 张（`DemoNavigation.test.ts:18,28`）；`DynRowTable` 3 行时 `button[aria-label]` 恰为 3 个（`DynRowTable.test.ts:51`）——**不要给新增按钮加 aria-label** |
| **行文本格式** | ListView 行必须保留 `ID · v版本` 的中点分隔与「草稿 vN」「生效中」字样 |

**评审 Y9 补漏：4 条会被换代打破、但原清单没点名的断言**

| 断言 | 为什么会红 | 处置 |
|---|---|---|
| `DemoPanel.test.ts:28` `get('.response-card').attributes('aria-busy') === 'true'` | §5 要把 DemoPanel loading 统一成 `Skeleton`，`.response-card` 与 `aria-busy` 会一起没 | **保留 `.response-card` 外壳与 `aria-busy`**，只把内部换成 Skeleton |
| `DemoShell.test.ts` / `DemoNavigation.test.ts` 的 `.nav-search input` | 挂在 `DemoNav.vue`——原计划里这个文件不存在 | 加入 §8 P1，class 名不动 |
| `LoginView.test.ts:75,81` `get('[role="status"]')` 必须「加载中存在、加载后消失」 | §5 要把 login alert 统一走 `Banner`；A5 只授权了 `:59-64`，**`:75/:81` 不在授权内** | **授权扩到整个 `LoginView.test.ts`**，或 `Banner` 保证透传 `role="status"` |
| `viz.test.ts:104-105` `find('path')`（**第一个** path）断言 `class=empty` 与 `d="M0,15L100,15"` | §4 要给 Sparkline 加渐变面积。**面积 path 若插在折线 path 之前，两条立刻红** | 面积 path **必须插在折线之后**，或授权改这两条 |

> **授权例外（更新）**：`LoginView.test.ts` 全文（原只授权 `:59-64`）。其余一律不改——**若 R-6 走「删 Sparkline/Gauge」分支，则须额外授权 `viz.test.ts` 的 5 条**（`:56/:67/:72` Gauge、`:102/:108` Sparkline），见 §12 R-6。

### 10.2 回归矩阵

| 轮次 | 时机 | 命令 |
|---|---|---|
| 单元 | 每步 | `cd frontend && npm test`（142 例）+ `npm run typecheck` + `npm run build` |
| e2e · header 档 | 步骤 2 后、步骤 8 后 | 起 8097：`./mvnw -pl activity-console spring-boot:run -Dspring-boot.run.profiles=h2 -Dspring-boot.run.arguments="--server.port=8097"` → `npm run e2e:dev` / `e2e:catalog` / `e2e:tablet` / `e2e:phone` |
| e2e · 编排档 | 同上 | **必须先切 header 档**：`DROOLS_AUTH_ENABLED=false DROOLS_DEV_DEFAULT_ENABLED=true docker compose -f deploy/docker-compose.yml up -d` → `e2e:bench` / `e2e:playbooks` / `e2e:ruler`（默认 BASE=8095） |
| e2e · auth 档 | 步骤 6 后（改了登录页） | 需本机 Casdoor `:8000` → `npm run e2e:oidc` |

### 10.3 移动端视口测试矩阵（必做）

| 视口 | 主题 | 页面 | 断言 |
|---|---|---|---|
| 1440×900 | dark + light | 全 11 页 | 零横向溢出；对比度抽检；辉光 ≤3/屏 |
| 768×1024（平板，`pointer: coarse`） | dark | home / activities / new / validate / demos | 侧栏 docked 不变抽屉；表格卡片化；触控 ≥44px；零横向溢出 |
| 390×844（手机） | dark + light | home / activities / new / demos / login | **`.toolbar` 高度 ≤160px**（F3 回归断言）；抽屉可开合；`backdrop-filter` 已关闭；零横向溢出 |
| 390×844 + `prefers-reduced-motion: reduce` | dark | home / demos | 无循环动画在跑；信息不缺失 |
| 1440×900 @ DPR 1.25 | light | activities | 网格线不糊、发丝线可见 |
| 打印预览 | — | detail | 白底、无背景图、无发光、文字可读 |

### 10.4 性能红线（超了就砍）

| 项 | 当前（实测） | 上限 |
|---|---|---|
| 入口 `index.js + index.css` gzip | `59,176 + 5,145 = 64,321 B` | **75 KB** |
| **全站 CSS gzip（口径已改：逐 chunk 求和）** | **30,409 B ≈ 30.4 KB** | **40 KB**（评审 R6：原 21.5KB 是 `cat *.css \| gzip` 的**错误量法**——Vite 按路由切 30+ 个 chunk 分别下发，合并压缩省下的 9KB 是共享字典幻觉。原 32KB 上限对应真实余量仅 1.6KB，第一天就撞线） |
| Web 字体总量 | 0 | **100 KB**（评审 B3：JetBrains Mono **不是可变字体**且 `DemoPanel.vue:311` 需要 bold → 至少 2 个文件；Inter var latin 子集实际 45–90KB。原 65KB 过于乐观）。仅 latin 子集、自托管、`font-display: swap` |
| 同屏 `backdrop-filter` 元素 | **`/demos` 上会有 4 个**（玻璃 topbar + 玻璃 sidebar + hero-stats + catalog-tools）——原写「当前 3」是把**全仓**计数当成了同屏计数 | **≤2**。按 D8-a 裁决后：`/demos` = topbar + hero-stats = 2 ✅。<br>**允许**高度 ≤56px 的 sticky 窄条（顶栏）；**禁止**侧栏与滚动容器内部的大面积 sticky 元素 |
| `box-shadow` 单元素层数 / blur | — | **≤3 层 / ≤40px**（`--shadow-lg` 已从 60px 降到 40px，辉光拆成 `--shadow-lg-glow`）；**不得对 `box-shadow` 做 transition** |
| 同屏无限循环动画 | **2 个元素**（`LoginView.vue:254,255` 两个 `.aurora-blob` 共用 `:249` 的 11s infinite，各带 `blur(16px)`）——**登录页现在就已吃满配额** | **≤2 层，只动 transform/opacity**。登录页重设计时减到 1 层 |
| canvas / rAF 背景动画 | 0 | **0**（默认零预算） |
| `background-attachment: fixed` | 0 | **禁用**（iOS Safari 退化） |

### 10.5 浏览器兼容判定（按本项目实际底线）

| 特性 | 判定 | 依据 |
|---|---|---|
| `color-mix()` | ✅ 放心用 | 全仓已用 26 处，既成事实 |
| `backdrop-filter` | ✅ 可用，**必须双写 `-webkit-`** | 全仓现在一个都没写，Safari 17- 静默失效，且无 autoprefixer |
| `mask-image` | ✅ 同理必须双写 | `DemoHome.vue:186` 现在只写了无前缀 |
| `:has()` | ❌ 禁用 | Firefox <121 静默失效（`tokens.css:151` 已记载） |
| `@property` | ❌ 禁用 | Firefox 128 才支持，失效方式是"渐变动画完全不动"。**边框流光改用静态渐变描边** |
| OKLCH | ❌ 禁用 | 与已精算的 WCAG 数字无法对账 |
| `text-wrap: balance` | ✅ 仅作渐进增强 | 不支持时只是不平衡 |
| `background-clip: text` | ✅ 但必须 `@supports not` 兜底 | 失效时 `color:transparent` 会让标题**消失** |

---

## 11. 验收标准

| # | 标准 | 判定方式 |
|---|---|---|
| **A-1** | 全仓硬编码颜色 = 0（注释除外）；**其中 `color: #fff` 必须归零** | `grep -rnE '#[0-9a-fA-F]{3,8}\b' frontend/src --include='*.vue'` + `grep -rn 'color: *#fff' frontend/src --include='*.vue'` |
| **A-2** | vitest + typecheck + build 全绿（静态数 **146** 个 `it()`，原写 142；**以实跑 `npm test` 的输出为准**） | 命令输出 |
| **A-3** | 8 个 e2e 脚本全绿（header 档 4 + 编排档 3 + auth 档 1） | 命令输出 |
| **A-4** | 9 组对比度（`--text`/`--text-soft`/`--text-faint` × `--bg`/`--bg-elev`/`--bg-soft`）**全部 ≥4.5:1**，且新数字写回 `tokens.css` 注释 | 附录 A 精算表 + 复算脚本 |
| **A-5** | `--border-ctl` 在两档主题、两个底色上均 ≥3.0:1 | 同上 |
| **A-6**（评审 Y1 重定阈值） | **【移动端】** 390×844 下 `/console/activities` 的 **`.search-box` 高度 ≤56px**（直接钉住 F3 的缺陷本体，实测缺陷值 240px）；`.toolbar` 高度 ≤240px。<br>**原写「toolbar ≤160px」不可能达到**：`tokens.css:250-260` 在 `(pointer:coarse)` 下把 button/select/input 全顶到 44px，列向堆叠后 = 44×3 + gap 16 + padding 24 + border ≈ **174px**，带筛选时 **226px** | 步骤 9 新增的 Playwright 断言 |
| **A-7**（评审 Y10 修正） | **【移动端】** 390 / 768 / 1440 三视口零横向溢出。**阈值是 `<= 4` 不是 `<= 0`**——现有 **10 个**断言点（`e2e-bench.mjs:136`、`e2e-tablet-smoke.mjs:25,39,52`、`e2e-phone-smoke.mjs:19,40,51`、`e2e-playbooks.mjs:101,108`、`e2e-tier-ruler.mjs:37`，原写 5 个）统一用 `<= 4`。写成 0 会在换代后误报 | 10 个 e2e 已有断言 |
| **A-8** | **【移动端】** 390 下 `backdrop-filter` 计算值为 `none`；触控目标全部 ≥44px | Playwright `getComputedStyle` 抽检 |
| **A-9** | `prefers-reduced-motion: reduce` 下无循环动画在跑，且无信息缺失 | Playwright emulate + 人工核 |
| **A-10** | 性能红线全部不超（§10.4 八项） | `npm run build` 产物 + `gzip -9` 估算 |
| **A-11** | 三套视觉语言收敛：login / demos / console 三屏截图放一起，色板与形制一致 | 人工对比「改造前/后」截图 |
| **A-12** | 打印预览白底可读 | 浏览器打印预览 |
| **A-13** | 缺陷 F1–F8 全部闭环 | 逐条核 |

---

## 12. 风险与回滚

| # | 风险 | 影响 | 缓解 | 回滚 |
|---|---|---|---|---|
| R-1 | **换 accent 后白字压彩底不可读** | 主按钮/徽章直接不可读（cyan 上白字仅 1.66:1） | 步骤 1 **先于** 步骤 2 做掉 `#fff → --text-invert` | revert 步骤 1 |
| R-2 | **改中性色阶作废已有 WCAG 结论** | 无障碍倒退且无人察觉 | A-4/A-5 强制重算并写回注释 | revert 步骤 2 |
| R-3（评审 Y7 改正实现） | **网格底与表格行线打莫尔纹** | Tier C 长表格页视觉噪声 | 网格铺 `.shell-content`，`AppShell.vue` 按 `route.name` 切 `data-grid="off"`（**不是**加在页面根 section 上，也**不用 `:has()`**） | 删属性绑定 |
| R-4 | **玻璃/辉光在低端安卓与 125% 缩放上翻车** | 掉帧、糊边 | `<768` + `(pointer:coarse)` 整体关闭；网格线 alpha ≤.07；辉光 ≤3/屏；blur ≤14px 且 ≤2 元素 | revert 步骤 5 |
| R-5 | **登录页重设计必然弄红 4 条 class 断言** | CI 红 | 步骤 6 把「同步 `LoginView.test.ts`」列为显式子任务，不等它红了才发现 | — |
| R-6（评审 R5 加第三选项） | **Sparkline/Gauge 接不上真实数据** | 留两个零引用组件，或诱发造假数据（违反 D7） | 步骤 10 **三选一**：① 接 `DemoPanel.elapsedMs` / `ValidateView` 命中占比；② **保留组件 + 保留测试 + 本轮不接数据**（← **默认选项**）；③ 删除。<br>**必须有第②项的理由**：这两个组件生产零引用，但 `viz.test.ts` 有 5 条测试撑着（Gauge `:56/:67/:72`、Sparkline `:102/:108`），**选③就必然打掉 5 条单测**，与 D6「27 条继续绿」和 N-8 直接冲突——原计划只写了①②没写这条约束 | — |
| R-7 | **中文 fallback 割裂**（英文 Inter、中文雅黑） | 观感不统一 | 已知代价（D5）；用可变字重 + 字距 + 字号层级弥补；**验收时人工核中英混排行** | 去掉 `@font-face`，`--font-ui` 回系统栈（1 行） |
| R-8 | **e2e 档位跑错**（编排默认 auth 档，而 bench/playbooks/ruler 点 tenant-chip） | 报一堆看不懂的超时 | §10.2 已写死先切 header 档的命令 | — |
| R-9 | **改了前端只 `--build console` 没效果** | 以为没生效反复折腾 | 前端由 gateway 镜像托管：`npm run build` 后 `docker compose -f deploy/docker-compose.yml up -d --build gateway`（或 `./deploy.sh --frontend-only`） | — |
| R-10 | **用户不喜欢新方向** | 整轮返工 | 步骤 2 结束时就有 80% 观感，**在此设一个中途确认点**给用户看截图再决定是否继续 | `git revert` 步骤 2（单 commit，token 层完全可逆） |

**总回滚路径**：每步一个独立 commit；最坏情况 `git revert` 步骤 2（令牌换代）即可回到旧观感，其余步骤（effects.css / 字体 / 组件形制）在旧令牌下也能正常工作，不会连锁崩。

---

## 附录 A · WCAG 精算表（深空遥测方向）

> 按 sRGB 相对亮度精算，非目测。正文目标 ≥4.5，UI 组件边界 ≥3.0。
> **独立评审复算了 23 组，20 组差值 ≤0.01**；不一致的 3 组（浅色 `--accent-2`）已按复算值更正（见下表加粗行）。

| 组合 | 暗色 | 浅色 |
|---|---|---|
| `--text` on `--bg` | 16.37 ✓ | 17.34 ✓ |
| `--text` on `--bg-elev` | 15.31 ✓ | 18.88 ✓ |
| `--text` on `--bg-soft` | 14.48 ✓ | 17.95 ✓ |
| `--text-soft` on `--bg` | 9.07 ✓ | 7.22 ✓ |
| `--text-soft` on `--bg-elev` | 8.49 ✓ | 7.86 ✓ |
| `--text-soft` on `--bg-soft` | 8.03 ✓ | 7.47 ✓ |
| **`--text-faint` on `--bg`** | **6.41 ✓** | **5.17 ✓** |
| **`--text-faint` on `--bg-elev`** | **6.00 ✓** | **5.63 ✓** |
| **`--text-faint` on `--bg-soft`** | **5.67 ✓** | **5.35 ✓** |
| `--accent` on `--bg` | 5.97 ✓ | 5.33 ✓ |
| `--accent` on `--bg-elev` | 5.58 ✓ | 5.81 ✓ |
| `--accent` on `--bg-hover` | 4.75 ✓ | 5.06 ✓ |
| `--accent-2`(青) on `--bg` | 10.89 ✓ | **5.58 ✓**（评审复算更正，原写 5.07 是按旧色 `#0E7490` 算的） |
| `--accent-2`(青) on `--bg-elev` | 10.32 ✓ | **6.07 ✓**（原写 5.72） |
| `--accent-2`(青) on `--bg-hover` | 9.6 ✓ | **5.29 ✓**（原写 4.71） |
| `--text-invert` on `--accent` | 6.06 ✓ | 5.81 ✓ |
| `--ok` / `--warn` / `--err` / `--blue` on `--bg-elev` | 10.41 / 10.14 / 7.25 / 7.49 ✓ | 5.39 / 6.33 / 6.54 / 7.23 ✓ |
| **`--border-ctl` on `--bg-elev`**（≥3.0） | **3.11 ✓** | **3.29 ✓** |
| **`--border-ctl` on `--bg`**（≥3.0） | **3.32 ✓** | **3.03 ✓** |
| `--dv-1..5` on `--bg-elev`（≥3.0） | 5.58 / 10.32 / 10.41 / 10.14 / 7.10 ✓ | 5.81 / 5.72 / 5.39 / 6.33 / 6.23 ✓ |

**已知且刻意的例外**：`--border` / `--border-strong` 均 <3:1（1.26–1.86）。**这与现状一致、不是新引入的缺陷**（现仓库浅色 `--border-strong` on white 仅 1.82、`--border` 仅 1.34）。正确修法不是把装饰性分隔线拉到 3:1（会丑），而是新增 `--border-ctl` 专供**输入控件边界**——这正是本轮的做法。
`--ramp-1/-2` 同样 <3:1，属设计意图（最浅两档本就该融进底色），因此**占比条必须有描边或最小可见宽度**，不能只靠色块表意。

---

## 附录 B · 效果层片段库（`effects.css` 的内容大纲）

```css
/* ── 关键帧（收敛自 5 份 spin + 2 份 shimmer） ── */
@keyframes spin  { to { transform: rotate(360deg) } }
@keyframes sweep { from { background-position: -120% 0, 0 0 } to { background-position: 220% 0, 0 0 } }
@keyframes ping  { to { box-shadow: 0 0 0 10px transparent } }
@keyframes rise  { from { opacity: 0; transform: translateY(10px) } }

/* ── 玻璃面：必须 @supports 包裹 + -webkit- 双写 + 小屏关闭 ── */
.u-glass { background: var(--surface-glass); box-shadow: inset 0 1px 0 rgba(255,255,255,.06); }
@supports ((backdrop-filter: blur(1px)) or (-webkit-backdrop-filter: blur(1px))) {
  @media (min-width: 768px) and (hover: hover) {
    .u-glass { -webkit-backdrop-filter: blur(14px) saturate(150%); backdrop-filter: blur(14px) saturate(150%); }
  }
}

/* ── 主按钮辉光：只在 hover 出现，且动 box-shadow 的是 hover 态切换而非 transition box-shadow ── */
/* ── 渐变标题：必须 @supports not 兜底，否则 clip 失败时标题消失 ── */
@supports not (background-clip: text) { .u-gradient-text { color: var(--text); background: none } }

/* ── 入场 stagger：纯 CSS nth-child 递增 delay，仅 Tier A ── */
/* ── 脉冲指示点：ping，仅用于"在跑"语义，禁止出现在 Tier C ── */
```

> **禁用**：`@property` 驱动的边框流光（Firefox 128- 静默不动）、canvas/rAF 背景、`background-attachment: fixed`。
