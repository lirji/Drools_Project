# DECISION_RECORD · 前端 UX 重设计（体验 / 流畅度 / 菜单自解释）+ 视觉皮肤 v2 合并轮

> 2026-07-21。需求原文：「重新设计前端页面，优先用户使用体验度、流畅度、界面能一眼知道那个菜单是干什么的等等」。
> 本轮由 6 个只读勘察子代理（需求用户流 / UIUX 交互状态 / 前端架构 / 仓库约束 / 移动端响应式 / 测试风险）交叉背书。
> 用户已拍板（2026-07-21 AskUserQuestion）：**合并视觉皮肤 v2**；IA=**侧栏增强+概览首页+演示台目录页升级**；交互打磨**四项全做**；**断点治理纳入**。

## 背景事实（勘察共识，全部有文件证据）

- 上一轮「生产级外壳」重设计已合并（`docs/plans/frontend-console-redesign-0720-1207/`，14/14 E2E 绿）：外壳三层分离（App 门控 / AppShell 状态 / AppLayout 布局）、逐页 PageHeader、三态覆盖大体齐全、全局 focus-visible / 44px 触控 / reduced-motion 均已到位。
- 视觉皮肤 v2 计划（`docs/plans/frontend-visual-refresh-0720-1404/`，Calm Precision）**只规划未执行**：`Icon.vue` 不存在，emoji/字形图标仍散落 18+ 处，`--text-faint #98a0b3` 白底对比 ≈2.6:1 不达 WCAG AA。
- 「菜单不自解释」实锤：侧栏零图标零说明；「演示台」组无子项只有一句「目录在右侧」，DemoHome 却写「从左侧选」（互相矛盾）；18-Step 分组标题满是行话（agenda-group/CEP/FEEL）；`/` 直跳活动列表，两大区域关系从未被介绍。
- 交互不一致实锤：toast 仅 2 页在用（其余行内 Banner）；上/下线一键切换无确认；编辑器校验只聚合计数不定位；离开守卫用原生 `window.confirm`；登录按钮无 loading 态。
- 断点碎化实锤：正典宣称 1024/768/560，实际页面级是 980（Editor/Detail/Validate/DemoPanel）/760（ListView）/767（DemoShell）三套；平板 768 下 ListView 仍渲 6 列（内容区仅 ≈472px）且该档无 e2e 保护；≤760 隐表头后字段无标签（D5 计划过未实现）。
- 对计划有利：e2e 对导航暴露面极窄——侧栏仅 `tab-list`/`tab-new` 被脚本点击，**没有任何 e2e 走裸 `/ui/` 根路径**；vitest 对 class 零依赖；主包 43.9KB gz（预算 150KB）余量大。
- 红线：全部 `data-testid` 逐字保留（真值源=e2e 脚本）；`.tr`/`.text-box`/`.no-body` 三个 class 被 e2e 硬用；`catalog.ts`（1351 行）不得进主包；768/390 溢出 ≤4px；token 变量名/路由 name/`/ui/` base/存储 key（`drools-theme`/`actTenant`/`actActor`/sessionStorage 五件套）不改名；`OrderSummary` 的 `→` 字符。

## D1 与视觉皮肤 v2 的关系 → **合并为一轮**（用户拍板）

| 备选 | trade-off |
|---|---|
| **A 合并为一轮 ✅** | Icon 系统/active 态/对比度修复/死原语启用本来就是「菜单一眼可懂」的落点；拆两轮会两次动同一批文件（SidebarNav/TopBar/原语/四页）、两次回归。代价：单轮 diff 变大 → 用分阶段可停 commit 化解 |
| B 只做 UX，皮肤另跑 | 范围小，但图标/active 态是 UX 目标核心落点，剥离后本轮效果打对折 |
| C 先皮肤后 UX | v2 计划 Non-goal 明写「不改交互流程」，先跑它无法解决本次诉求，且两轮重复动员 |

合并后 v2 计划（0720-1404）**被本计划取代**，其决策 D1–D10 中与本轮相容的部分已吸收（见 FINAL_PLAN §2），该目录保留作历史参考不再执行。

## D2 导航信息架构 → **侧栏增强 + 概览首页 + 演示台目录页升级**（用户拍板，三项叠加）

| 备选 | trade-off | 结论 |
|---|---|---|
| **侧栏增强 ✅**：图标 + 每项一句说明 + active 左强调条 | 纯加法，零 e2e 冲突（testid 全保留）；768–1023 侧栏 248px 不加宽（说明文案用小字第二行，不撑宽） | 做 |
| **概览首页 /home ✅**：两区介绍 + 快捷入口 + 最近活动 | 无 e2e 走裸根路径 → `/` redirect 改指 `/home` 零冲突；新页需自带三态否则成白屏新风险面 | 做 |
| **演示台目录页升级 ✅**：DemoHome 一句话 → 分组卡片目录（人话） | catalog 留 demos 懒 chunk，主包零影响；顺带修「左侧/右侧」矛盾文案 | 做 |
| 18-Step 目录收编进全局侧栏 ❌ | SidebarNav 在主包，静态 import catalog 即破坏懒加载边界（主包 +6KB gz 且边界失守）；动态 import 可解但 <768 抽屉（≤320px 宽）嵌 33 项长滚动体验差；且 e2e-catalog 走 URL 直达不点目录，收编无自动化收益 | 不做，由「目录页升级」替代 |

## D3 首页路由落位 → **新增 `/home`，`/` 与 catch-all 改指它，`/console` redirect 保持不动**

- 备选「把 `/console` 本身变成概览页」：dev-smoke 等脚本 `goto /ui/console` 后隐含期待列表相关元素，改语义有回归面。
- 选定方案：`/home` 新懒加载路由；`{ path:'/', redirect:'/home' }`；catch-all `/:pathMatch(.*)*` → `/home`（未知路径落概览比落列表更自然）；`/console → /console/activities` 一字不改。auth 守卫自动覆盖 `/home`（不在 bare 白名单）。
- 补充（评审 M5）：经守卫的登录 returnTo 链路已核实会落回 `/home`；但**直接访问 /login（无 query）** 时 `LoginView.doLogin` 与 `authClient` 兜底默认仍是 `'/console'`——两处默认值一并改 `'/home'`（改值不改 sessionStorage key，e2e-oidc 每次登录都经守卫带 returnTo，零影响）。
- e2e 证据：5 个脚本首跳全部是 `/ui/console` 或 `/ui/demos/:id`，oidc 回调断言是宽正则 `\/ui/`——零冲突。

## D4 视觉方向 → **沿用 v2 已定的 Calm Precision，强调色默认 indigo（标为假设）**

- 方向、参考映射（LangSmith/Linear/Vercel/shadcn/Supabase/Tremor/lucide → 本项目 token/组件）与具体 token 值全部继承 v2 计划 §2–§3，不重新发明。
- 悬置项本轮一并定：**强调色默认 indigo `#4f46e5`**（备选 cobalt/violet，实施 Phase A 结束时看真实渲染可一键换值）；**圆角 14→10**；**`--text-faint` 修到 ≥4.5:1——取 `#6b7280`（白底 4.83:1）**，v2 计划抄的 `#757c8a` 实测仅 4.19:1 不达标（评审 S1），且验收按实际底色（`--bg`/`--bg-soft`）实测。三者均为改值不改名，单文件秒回退。→ 标注为**待用户在验收时确认的假设**。

## D5 断点治理 → **统一到正典 1024/768/560 + ListView <1024 卡片化**（用户拍板「纳入」）

| 备选 | trade-off |
|---|---|
| **统一正典 ✅** | 980/760/767 全部收敛：多栏塌单列统一 `<1024`；抽屉边界维持 `<768`；极窄档 `≤560`。ListView 表格 `<1024` 起改卡片式（补字段标签，修复 768 拥挤 + ≤760 无标签两笔旧债）。代价：1000px 窄桌面窗口也看到卡片——接受，换全站一致性 |
| 只修列表拥挤 | 债务继续碎化，新增 /home 还要再发明断点 |
| 不动 | 768 拥挤与无标签问题遗留 |

**768 平板保持侧栏 docked、tablet-smoke 零改**（重写 e2e 属阻断项，本轮不触碰）。卡片化必须保留行根元素 `.tr` class（e2e-dev 用 `[data-testid="list-view"] .tr` 做**存在性检查**，非计数；e2e 跑桌面默认视口，卡片化不进其视野——评审轻微 4 校正表述，防实施者过度自缚）。

## D6 破坏性操作确认 → **自绘 ConfirmDialog 原语**（替代原生 confirm 与无确认）

- 上/下线切换加确认；编辑器离开守卫从 `window.confirm` 迁入（`onBeforeRouteLeave` 返回 Promise<boolean>，vue-router 支持）。
- 自绘理由：原生 confirm 无法样式化/不进主题、阻塞主线程；自绘可带 `role="dialog"`/`aria-modal`/Esc/scrim，与抽屉同一交互语汇。z-index 用 `--z-dropdown`(200) 之上、toast(1000) 之下，新增 `--z-modal: 950`。

## D7 反馈渠道规范 → **toast=动作结果，行内 Banner=面板/表单绑定状态**

- 规范：跨页或列表内动作的结果（保存成功/上下线成功失败/复制）→ toast；与当前表单/面板强绑定的持续状态（校验错列表/请求中/响应错误/幂等命中）→ 行内 Banner。
- 按此规范补齐：ListView 上下线已用 toast（保持）；DetailView/ValidateView/DemoPanel 的**面板状态保持行内 Banner 不动**（符合规范，不强改）；EditorView 保存成功 toast+成功卡并存（保持）。即：不是「全站改 toast」，而是「定规范、消歧义」。

## D8 页面过渡 → **CSS `<transition mode="out-in">`，不用 View Transitions API**

- CSS 过渡自动被 `tokens.css` 全局 `prefers-reduced-motion` 规则灭掉（已验证该规则存在）；View Transitions 突破隐含浏览器基线（Safari<18/FF 不支持）且绕过 reduce 规则。
- 限 transform+opacity、时长 ≤160ms，复用抽屉 `.22s ease` 语汇的收紧版。
- **挂点修正（评审 S2）**：路由是两层嵌套（console 五页挂 ConsoleShell 子路由、demos 挂 DemoShell），只挂 AppShell 顶层出口时**最高频的页间切换（tab 切换）不触发过渡**。改为抽共享 `PageTransition.vue`，AppShell（跨区）+ ConsoleShell + DemoShell（页间）三处出口统一接入；每落一处出口跑 `e2e:dev` 验时序。

## D9 条件树行内校验 → **纯函数扩展 + 叶子错误态样式**（用户拍板做）

- `logic.ts` 的 `validateTree` 现只回聚合信息 → 扩展出 `invalidNodeIds(tree): Set<string>`（纯函数，可单测），ConditionLeaf 按 id 命中加 `.has-error` 红框 + 行内一句原因。
- 红线：`leaf-del/scalar-val/add-cond/add-group/logic-*/cond-group/leaf-field/leaf-op` 等 testid 与元素语义（input.value/button.disabled）逐字保留——`ConditionGroup.test.ts` 全靠它们。触发时机：失焦或点提交后才显红（避免打字中闪红）。

## D10 演示台人话文案 → **GROUPS title 人话化、subtitle 保留行话，id/结构冻结**

- 例：「入门 · 折扣」→「入门：一条数据命中多条规则」+ 副标题保留 `Step 1–3 · facts / salience / accumulate`。教学术语不丢（副标题+详情仍在），新用户扫读靠人话主标题。
- 冻结：`catalog.ts` 的 `id`/`group`/字段结构不动——e2e-catalog 用正则 `"id":\s*"([^"]+)",\s*"group":` 抽 id。DemoNav 方法圆点（color-only）补 GET/POST 文字小徽记（可达性）。

## D11 死原语处置 → **落地采用**（不是删除）

- `Field.vue` 接管 EditorView/ValidateView 裸 `.fg label`（换来字段级 has-error 红边 + hint 统一）；`Section.vue` 接管 EditorView 裸 `.sec-title` 六段。这是 v2 计划 D9 的既定方向，本轮执行。

## 非目标（Non-goals）

- 不动后端 / API / 路由 name（`/home` 为纯新增）/ `/ui/` base / 存储 key / header 注入 / auth 流程。
- 不收编 18-Step 目录进全局侧栏；catalog.ts 不进主包。
- 不改 768 平板 docked 行为、不重写 tablet-smoke。
- 不引入 UI 库 / CSS 预处理器 / View Transitions API / Suspense。
- 不删改任何既有 data-testid（只增不改）；`.tr`/`.text-box`/`.no-body` class 保留。
- 不做图表可视化、不做「最近访问」跨设备同步（首页「最近活动」直接来自 list API，不新增本地记录机制）。
- token 只加不改名；`kmodule`/Drools 侧零涉及。

## 独立评审处置记录（2026-07-21）

评审共报 2 严重 / 7 中等 / 9 轻微，处置如下（详见 FINAL_PLAN 对应标注）：

| # | 发现 | 处置 |
|---|---|---|
| S1 | `--text-faint:#757c8a` 实测 4.19:1 达不到 4.5:1 验收门 | **已修**：改 `#6b7280`（4.83:1），验收按实际底色实测 |
| S2 | 过渡只挂 AppShell 对嵌套出口（tab 切换）不生效 | **已修**：抽 `PageTransition.vue`，AppShell/ConsoleShell/DemoShell 三出口接入 |
| M1 | nav-home 在 B2 先于路由注册 | **已修**：nav-home 移到 Phase C 与路由同 commit |
| M2 | 「emoji 清零」与 e2e `→` 断言矛盾 | **已修**：验收改「装饰性清零」+ 豁免清单 |
| M3 | Icon 清单缺失 | **已修**：FINAL_PLAN §2 列 16 个 name→位置 |
| M4 | ConfirmDialog 与抽屉双写 `body.overflow` 互踩 | **已修**：新建计数式 `useScrollLock.ts` 共用 |
| M5 | 直访 /login 无 query 时 returnTo 兜底仍落 `/console` | **已修**：两处默认值改 `/home`（改值不改 key） |
| M6 | 条件树错误态数据流未定，或碰单测挂载 | **已修**：约束为可选 prop 默认空集 / 叶子本地 touched，不引 store |
| M7 | catalog e2e 抽取对格式敏感且失败静默假绿 | **已修**：禁整文件重格式化 + e2e 追加 `ids.length ≥ 30` 下限断言 |
| 轻微 1/2/3/5/9 | HomeView 目录、FOUC 非 module、logic.ts 路径、体积门归因、mono ID 溢出教训 | **已修**（采纳） |
| 轻微 4 | `.tr` 「数行」表述不准 | **已修**：校正为存在性检查 |
| 轻微 6 | 无 scrollBehavior 切页滚动位保留突兀 | **已采纳**：router 新增 scrollBehavior（纯新增零 e2e 影响） |
| 轻微 7 | ConfirmDialog open() 并发挂起 | **已采纳**：单例化，新开自动取消旧挂起 |
| 轻微 8 | 401 登出被离开确认拦 | **注明不改**：与现状原生 confirm 行为一致非回归，可选优化不列硬门 |

## 待用户验收时确认的假设

1. 强调色 indigo `#4f46e5`（Phase A 落地后看真实渲染，可换 cobalt `#2563eb`/violet `#7c3aed`，单文件改值）。
2. 圆角 14→10（同上可回退）。
3. 首页「最近活动」取列表前 8 条（复用 list API 按租户隔离），不需要专门后端端点。
4. `contract-smoke.mjs` 与 `data-testid-contract.md` 顶表过时问题：本轮顺手把 contract.md 位置栏更新到 shared/layout 现状并登记新增 testid（纯文档，不碰脚本语义）。
