# data-testid 契约表（E2E 选择器稳定层）

> 决策 D6：E2E 断言逻辑 100% 继承旧基线，只把选择器从散落 class 名收敛到这张 data-testid 契约表。
> 组件重构可改 class/DOM，但**不得改这些 testid**（改了要同步改 E2E 脚本并在此登记）。

## 全局 / 外壳
| testid | 位置 | 用途 |
|---|---|---|
| `theme-btn` | App.vue | 主题切换 |
| `tenant-bar` | ConsoleShell（dev 档） | dev 档租户栏容器 |
| `tenant-input` | ConsoleShell | X-Tenant-Id 输入 |
| `tenant-chip-{acme\|beta\|__dev__}` | ConsoleShell | 快捷切租户 |
| `auth-bar` | ConsoleShell（auth 档） | 登录身份条容器 |
| `auth-tenant` | ConsoleShell | 显示 token aud 派生租户 |
| `logout` | ConsoleShell | 登出 |
| `tab-list` / `tab-new` / `tab-validate` | ConsoleShell | 三个子标签 |

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
