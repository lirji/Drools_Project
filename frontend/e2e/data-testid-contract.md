# data-testid 契约表（E2E 选择器稳定层）

> 决策 D6：E2E 断言逻辑 100% 继承旧基线，只把选择器从散落 class 名收敛到这张 data-testid 契约表。
> 组件重构可改 class/DOM，但**不得改这些 testid**（改了要同步改 E2E 脚本并在此登记）。

## 全局 / 外壳
> 位置栏已更新到 2026-07 UX 重设计后现状（外壳三件套在 `shared/layout/`）。真值源仍是 `e2e/*.mjs` 脚本。

| testid | 位置 | 用途 |
|---|---|---|
| `theme-btn` | TopBar | 主题切换（迁自 App.vue） |
| `nav-toggle` | TopBar | 汉堡按钮（<768 出现），phone-smoke 用它开抽屉 |
| `nav-home` / `nav-console` / `nav-demos` | SidebarNav | 概览 / 控制台 / 规则能力中心一级入口（无 e2e 点击，仅登记） |
| `tab-list` / `tab-new` / `tab-validate` | SidebarNav | 控制台三个子导航项 |
| `tenant-bar` | IdentityBar（dev 档） | dev 档租户栏容器 |
| `tenant-input` | IdentityBar | X-Tenant-Id 输入 |
| `tenant-chip-{acme\|beta\|__dev__}` | IdentityBar | 快捷切租户 |
| `actor-bar` / `actor-input` | IdentityBar（dev 档） | 四眼操作者 X-Actor |
| `auth-bar` | IdentityBar（auth 档） | 登录身份条容器 |
| `auth-tenant` | IdentityBar | 显示 token aud 派生租户 |
| `logout` | IdentityBar | 登出 |
| `toast-host` | ToastHost（App.vue 全局挂载） | toast 容器 |
| `confirm-dialog` / `confirm-ok` / `confirm-cancel` | ConfirmDialog（App.vue 全局挂载） | 二次确认弹窗（UX 重设计新增，无 e2e 点击，仅登记） |

## 登录 / 回调
| testid | 位置 | 用途 |
|---|---|---|
| `login-page` | LoginView | 登录页容器 |
| `login-{tenant}` | LoginView | 每租户登录按钮（对齐旧 `请选择租户登录` 断言 + 按钮） |
| `callback-page` | CallbackView | 回调着陆页 |

## 活动工作台（F1 落地时补齐锚点）
| testid | 位置 | 用途 |
|---|---|---|
| `list-view` | ListView | 列表容器 |
| `activity-row-{id}` | ListView | 列表行（F1） |
| `editor-view` | EditorView | 表单容器 |
| `form-name` | EditorView | 活动名称输入（对齐旧 `#am-name`） |
| `form-amount` | EditorView | 红包金额（对齐旧 `#am-amount`） |
| `spu-row-input` | EditorView | SPU 绑定行输入（对齐旧 `.dyn-row input`） |
| `submit` | EditorView | 提交（对齐旧 `#am-submit`） |
| `save-success` | EditorView | 保存成功卡（对齐旧 `活动已保存`） |
| `idempotent-hit` | EditorView | 幂等命中 tag（新增断言） |
| `conflict-hint` | EditorView | 409 版本冲突提示（新增断言） |
| `detail-view` | DetailView | 详情容器 |
| `validate-view` | ValidateView | 验证容器 |

## 旧基线断言 → 新 testid 映射（迁移对照）
| 旧选择器（e2e-oidc.mjs/e2e-dev.mjs） | 新 testid |
|---|---|
| `button[data-id="ext:activity"]` | 入口改为直接访问 `/ui/console`（不再经旧导航） |
| `text=请选择租户登录` | `login-page` |
| `.tenant-bar` | `auth-bar` / `tenant-bar` |
| `.act-tab:has-text("新建活动")` | `tab-new` |
| `#am-name` / `#am-amount` / `#am-submit` | `form-name` / `form-amount` / `submit` |
| `.dyn-row input` | `spu-row-input` |
| `text=活动已保存` | `save-success` |
| `.alist, .row-empty` | `list-view`（内含行或空态） |
| `.tenant-chip:has-text("beta")` | `tenant-chip-beta` |

## 2026-07 前端重设计（frontend-console-redesign）新增/迁移

> 外壳重设计把「顶栏 nav + ConsoleShell 三 tab + 三条身份条」迁入全局 `shared/layout/`（SidebarNav / TopBar / IdentityBar），
> **所有既有 testid 逐字保留、仅换了所在组件**（迁移后各只出现一次，无重复）。以 `e2e/*.mjs` 脚本为契约真值源。

| 新增 testid | 位置 | 说明 |
|---|---|---|
| `type-chip-5` | `EditorView` 活动类型 Segmented（code 5=买赠） | 替代原先 `.chip:has-text("买赠")` 靠 class+中文文本定位的最高危易碎点；`e2e-dev-v2` 已改用它 |
| `nav-toggle` | `TopBar` 汉堡按钮（<768 出现） | `e2e-phone-smoke` 用它开抽屉 |

平板/手机 smoke：`e2e-tablet-smoke`（768 docked，零改）+ 新增 `e2e-phone-smoke`（390 抽屉），均已挂 npm script（`e2e:tablet` / `e2e:phone`）。

## 2026-07 UX 重设计（frontend-ux-redesign）新增（均只增不改，无 e2e 点击依赖，登记以备后用）

| 新增 testid | 位置 | 说明 |
|---|---|---|
| `home-view` | `HomeView`（`/home` 概览首页） | 首页容器；`home-error` 加载失败 Banner |
| `home-go-list` / `home-go-new` / `home-go-demos` | HomeView 快捷入口 | 概览页到各区的快捷按钮 |
| `home-recent-{id}` | HomeView 最近活动行 | 点击进活动详情 |
| `nav-home` | SidebarNav 概览入口 | 见上「全局/外壳」 |
| `confirm-dialog` / `confirm-ok` / `confirm-cancel` | ConfirmDialog | 上下线 / 离开守卫二次确认 |
| `demo-home-{id}` | DemoHome 目录页 demo 卡片项 | 点击进 demo 面板（与侧栏 `demo-nav-{id}` 并存） |

## 2026-08 PR-5 活动工作台（console-ui-coupon-mechanics）新增

> **既有 testid 一个未改**：`list-view` / `list-search` / `list-status-filter` / `list-refresh` /
> `list-error` / `list-empty` / `list-pager` / `activity-row-{id}` 逐字保留，
> 行元素的 class **`.tr` 也保留**（`e2e-dev-v2` 用 `[data-testid="list-view"] .tr` 定位，
> 它不是 testid 但载荷等同，改名即断）。行内「详情」按钮仍是 `role=button` 且可访问名精确等于「详情」，
> 仍跳详情页而不是开侧板——`e2e-tablet-smoke` 唯一一条「经列表进详情」的路径靠它。

| 新增 testid | 位置 | 说明 |
|---|---|---|
| `row-check-{id}` | ListView 行复选框 | 单行勾选 |
| `select-page` | ListView 表头复选框 | 本页全选（半选走 `indeterminate`） |
| `sort-name` / `sort-window` / `sort-status` | ListView 表头 | 三态排序按钮（升/降/取消，带 `aria-sort`） |
| `bulk-bar` / `bulk-count` | `BulkBar` | 批量操作条与计数 |
| `bulk-select-all-matched` / `bulk-all-matched` | `BulkBar` | 跨页全选入口与已全选提示 |
| `bulk-online` / `bulk-offline` / `bulk-clear` | `BulkBar` | 批量动作与清除选择 |
| `bulk-confirm` / `bulk-confirm-ok` / `bulk-confirm-cancel` | `BulkConfirm` | 影响摘要弹窗 |
| `bulk-confirm-count` | `BulkConfirm` | ≥10 项时的「输入数量确认」输入框 |
| `toast-view-receipt` | `ToastHost` 动作位 | 批量回执 toast 上的「查看回执」 |
| `toast-{kind}` | `ToastHost` | toast 条目（kind = info/ok/err/warn） |
| `bench-receipt` | ListView 侧板 · 回执模式 | 成功/失败逐条明细 |
| `panel-detail` | ListView 侧板 · 详情模式 | 活动摘要 |
| `side-panel` / `side-panel-close` | `SidePanel` | 右侧详情板容器与关闭键 |
| `density-comfy` / `density-compact` | ListView 页头 Segmented | 密度切换（写 `<html data-density>` + localStorage） |
| `metrics-notice` | ListView 指标区 | D6 降级说明卡（决策指标未接入） |

新增 e2e：`e2e-bench.mjs`（`npm run e2e:bench`，默认 BASE=8095），覆盖归并 / 批量四段流程 /
版本正确性 / 密度持久化 / 侧板 Esc / 零横向溢出。

## 2026-08 PR-6 玩法模板屏

| 新增 testid | 位置 | 说明 |
|---|---|---|
| `tab-playbooks` | `SidebarNav` 控制台子导航 | 新增第 2 项，插在 `tab-list` 与 `tab-new` 之间（两者均未改名） |
| `playbooks-view` | `PlaybooksView` | 模板屏容器 |
| `playbooks-note` | `PlaybooksView` | 「这些模板不新增后端能力」说明卡 |
| `playbook-filter-{all\|reduce\|targeted\|gift\|blocked}` | `PlaybooksView` | 分类筛选 chip |
| `playbook-card-{id}` | `PlaybooksView` | 玩法券卡（id 见 `playbooks.ts`） |
| `playbook-use-{id}` | `PlaybooksView` | 「用它新建」；**不可用的玩法刻意没有这个 testid** |
| `playbook-blank` | `PlaybooksView` 页头 | 从空白新建 |
| `playbook-applied` | `EditorView` | 「已按 X 模板预填」提示条 |
| `form-take-type` | `EditorView` 发放方式下拉 | 「随机金额」选项恒 `disabled`（未实现，非删除） |

新增 e2e：`e2e-playbooks.mjs`（`npm run e2e:playbooks`），覆盖侧栏入口 / 12 张卡 /
不可用玩法写明缺什么且无按钮 / 筛选 / **跨屏预填链路** / 随机金额置灰 / 1440 与 390 零横向溢出。

**图标系统**：全站 emoji/几何字形已统一为内联 SVG `Icon.vue`（`shared/ui/Icon.vue`）。装饰性图标 `aria-hidden`，语义图标透传 `aria-label`。
**路由过渡**：`PageTransition.vue` 落 AppShell / ConsoleShell / DemoShell 三出口；被全局 `prefers-reduced-motion` 兜底禁用。
**首页路由**：`/` 与 catch-all 改指 `/home`（无 e2e 走裸根路径，零冲突）；`/console` 仍 redirect `/console/activities` 不变。
