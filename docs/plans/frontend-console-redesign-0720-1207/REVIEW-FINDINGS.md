# 独立计划评审结论 · 前端控制台重设计（2026-07-20）

> 由独立只读子代理对照真实代码评审 `DECISION_RECORD.md` + `FINAL_PLAN.md`。结论：**发现 2 个阻断项 + 6 条改进，已全部就地修入计划后可批准。**

## 阻断项（已修）

| # | 问题 | 修法（已入计划） |
|---|---|---|
| **B1** | 平板 768 走抽屉会让 `e2e-tablet-smoke.mjs`（768×1024 直接点 `tab-new`、无抽屉交互）离屏超时红；且与「只碰一个测试」口径矛盾 | **抽屉阈值改 `<768`，768–1023 侧栏 docked 收窄**（FINAL_PLAN §6/§7 F1d、DR D7）→ tablet-smoke **零改保持绿**；抽屉行为由新增 390×844 phone smoke 覆盖 |
| **B2** | IdentityBar「窄屏 popover」断点未钉；若含 768，`tenant-bar` 在 tablet-smoke L14 不可见超时 | **popover 阈值钉死 ≤560**；≥768 `tenant-bar`/`auth-bar` 内联可见（FINAL_PLAN §6） |

## 改进（已修）

| # | 问题 | 修法 |
|---|---|---|
| I1 | external 链接实际在 `DemoNav.vue`（非 FINAL_PLAN 写的 `DemoShell.vue`）+ `catalog.ts:52`；与「DemoNav 零改」矛盾；「e2e-catalog 断言 DemoNav」依据不准 | 改指 `DemoNav.vue`，标注「仅删 external 组、不动 `demo-nav-{id}`」；DR D2 更正 e2e-catalog 直达 URL 不走 DemoNav |
| I2 | `data-testid-contract.md` 已过时，当保留清单会漏掉被 e2e 实际断言的锚点 | 保留基线**以 `e2e/*.mjs` 脚本为真值源**双向 grep，契约表当「需同步的产物」（FINAL_PLAN §8） |
| I3 | F1b 先于 F1c 会中途 testid 出现两份 → Playwright strict-mode「2 elements」+ 双 401-push | **F1b+F1c 合为同 commit 不可分割变更**；核对项加「无重复 data-testid」 |
| I4 | ToastHost `z-index:1000` 与「零改」冲突；z-scale 无数值序 | 定数值序 `sticky100<dropdown200<drawer900<toast1000`——抽屉 900 < ToastHost 1000，**ToastHost 零改仍在最上层** |
| I5 | 冷启动 `route.name===undefined` 落 AppShell，auth 档直连 /login 会闪 dev 身份条 | AppShell 分流加 `router.isReady()`，resolve 前不渲染 chrome |
| I6 | 主题 toggle 迁 TopBar 若丢 App.vue onMounted 回读 `data-theme`，首次点击方向错 | 迁移把初始回读一并搬进 TopBar（或抽 useTheme） |

## 核对通过项（评审确认成立）
- P1 FOUC/主题机制（`main.ts` mount 前写 `<html data-theme>`）不在迁移范围，toggle 迁 TopBar 不破防闪。
- P2 代码分割：`catalog.ts` 仅 demos 三文件引用、经路由懒加载；只要 eager SidebarNav 不 import DemoNav 就不进主包。
- P3 路由零破坏：`router/index.ts` 一字不改；`route.name` 分流可行。
- P4 auth 档安全不变量：header 注入在 `beforeEach` 触发，与 IdentityBar 位置无关；逐字迁 `v-if authEnabled` 保住不发 X-Tenant-Id。
- P5 401 watch 上提：现状仅 /console 有，上提到 AppShell **补上了 demos 区缺口**（是行为扩面，非纯搬运，已注明）。
- P6 阶段依赖无倒置（token→外壳→原语）。
- P7 §4 状态表与真实代码对得上（EditorView loadForEdit 确无 loading、409、脏检查；ValidateView busy/双按钮）。
- P7b 范围/回滚成立（排除 DataTable/仪表盘合理；router/store/apiClient 未动，纯前端 revert）。

## 待用户确认（评审 C 段，并入批准问题）
- **C2**：actor 文本必须仍在带 `data-testid="auth-bar"` 的元素**内部**（`e2e-oidc:52` 断言 auth-bar innerText 含 `act-alice`）——已写入 §6 约束。
- **C3/C4**：碰测试的诚实口径 = `e2e-dev`(type-chip-5) + 新增 `e2e-phone-smoke` + 契约文档；`e2e-tablet-smoke` 不改。需用户授权（批准问题 B）。

## 总体判断
**计划可批准**（2 阻断 + 6 改进已就地修入）。建议按 §0 先 Phase 0（token 地基，零风险）+ Phase 1（外壳/IA，收益最大），验收门全绿后再 Phase 2。
