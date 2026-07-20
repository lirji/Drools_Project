# 前端重设计执行进度

> 计划 = 同目录 `FINAL_PLAN.md`（含评审 B1/B2 修订）。用户 2026-07-20 批准**全范围 Phase 0+1+2** + 授权碰测试（type-chip-5 / e2e-dev / 新增 phone-smoke / 契约登记；e2e-tablet-smoke 不改）。分支：`feat/frontend-console-redesign`。

## 阶段状态
| Phase | 内容 | 验证门 | 状态 |
|---|---|---|---|
| 0 | tokens.css 扩展（排版/纵深/焦点/布局尺寸/明色分离/coarse 兜底）+ index.html viewport | typecheck + build 绿 | 🚧 |
| 1 | AppLayout/AppShell/SidebarNav/TopBar/IdentityBar + App/ConsoleShell/DemoShell/DemoNav 接线（F1b+F1c 同 commit）+ `<768` 抽屉 | typecheck+vitest 26 绿 + e2e 全绿 + tablet-smoke 零改绿 | ⬜ |
| 2 | PageHeader/Toolbar/Section/EmptyState/Button/Badge/Field/Segmented + 逐页套页头 + type-chip-5 + e2e-dev + phone-smoke | 同上 + phone-smoke 绿 | ⬜ |

## 硬护栏（每步核对）
- 全部 data-testid 逐字保留、无重复；以 `e2e/*.mjs` 为真值源双向 grep。
- catalog.ts 不进主包（SidebarNav 不 eager import DemoNav）。
- auth 档不发 X-Tenant-Id；actor 文本留在 auth-bar 元素内。
- 抽屉阈值 <768；768–1023 docked；popover ≤560。z 序 drawer900<toast1000。
- 路由/API/store/逻辑零改动。

## 变更流水
（执行中追加）
