# 前端重设计执行进度

> 计划 = 同目录 `FINAL_PLAN.md`（含评审 B1/B2 修订）。用户 2026-07-20 批准**全范围 Phase 0+1+2** + 授权碰测试（type-chip-5 / e2e-dev / 新增 phone-smoke / 契约登记；e2e-tablet-smoke 不改）。分支：`feat/frontend-console-redesign`。

## 阶段状态
| Phase | 内容 | 验证门 | 状态 |
|---|---|---|---|
| 0 | tokens.css 扩展（排版/纵深/焦点/布局尺寸/明色分离/coarse 兜底）+ index.html viewport | typecheck + build 绿 | ✅ `bd226fc` |
| 1 | AppLayout/AppShell/SidebarNav/TopBar/IdentityBar + App/ConsoleShell/DemoShell/DemoNav 接线（F1b+F1c 同 commit）+ `<768` 抽屉 | typecheck+vitest 26 绿 + 3 视口截图（768 docked h-overflow=0） | ✅ `bd226fc`/`+fix` |
| 2 | PageHeader/EmptyState/Button/Badge/Field/Segmented/Section + 4 页套页头 + type-chip-5 + e2e-dev + phone-smoke | typecheck 0 + build 43.9KB + vitest 26 绿 + 编辑页截图 | ✅ |

**整栈 E2E 门（起 console -Pfrontend :8097 h2 mem + 种子 实跑）✅**：
- `e2e:dev` **7/7**（含 type-chip-5：切活动类型不丢值；外壳/租户栏/建活动/条件树 keyed-diff）
- `e2e:tablet`(768 docked) **3/3**（body 溢出 0px，脚本零改保持绿——B1/B2 修复实证）
- `e2e:phone`(390 抽屉) **4/4**（汉堡→抽屉开合→导航→提交，溢出 0px）
- **共 14/14 绿，零业务回归。** 未跑 `e2e:oidc`（需 Casdoor :8000）：auth 档登录流未结构性改动，IdentityBar 逐字保留 auth-bar/auth-tenant/logout + actor 留 auth-bar 内（C2），低风险；`e2e:catalog` 未跑（DemoNav/DemoPanel 未动，仅删 external 组，e2e-catalog 直达 URL 不受影响）。

## Phase 2 变更（在 Phase 1 之后）
- 新增 `shared/ui/{PageHeader,Section,EmptyState,Button,Badge,Segmented,Field}.vue`（scoped + 仅 token）
- ListView：PageHeader（+新建活动移入）+ EmptyState + Badge 状态 + `.tr > span{min-width:0}` 防窄内容区横向溢出（保 `.tr` 类名）
- EditorView：PageHeader（面包屑）+ **活动类型选择器改 Segmented，买赠=`type-chip-5`**（消除最高危易碎点）
- DetailView：PageHeader（面包屑替孤立返回）；ValidateView：PageHeader + EmptyState
- e2e-dev-v2 L78 改用 `type-chip-5`；新增 `e2e-phone-smoke.mjs`（390 抽屉）+ 挂 `e2e:phone`/`e2e:tablet` script；契约文档登记 type-chip-5/nav-toggle
- 全部 testid 逐字保留、无运行时重复；catalog 仍独立 chunk

## 硬护栏（每步核对）
- 全部 data-testid 逐字保留、无重复；以 `e2e/*.mjs` 为真值源双向 grep。
- catalog.ts 不进主包（SidebarNav 不 eager import DemoNav）。
- auth 档不发 X-Tenant-Id；actor 文本留在 auth-bar 元素内。
- 抽屉阈值 <768；768–1023 docked；popover ≤560。z 序 drawer900<toast1000。
- 路由/API/store/逻辑零改动。

## 变更流水
（执行中追加）
