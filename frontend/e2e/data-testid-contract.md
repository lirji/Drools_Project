# data-testid 契约表（E2E 选择器稳定层）

> 活文档。最后核对：2026-08-28。当前 SPA 只保留活动运营页面；旧 Demo catalog 与 `e2e:catalog`
> 已删除。下文按增量保留 testid 的引入历史，但表中标为删除的选择器不得继续用于新脚本。

> 决策 D6：E2E 断言逻辑 100% 继承旧基线，只把选择器从散落 class 名收敛到这张 data-testid 契约表。
> 组件重构可改 class/DOM，但**不得改这些 testid**（改了要同步改 E2E 脚本并在此登记）。

## 全局 / 外壳
> 位置栏已更新到 2026-07 UX 重设计后现状（外壳三件套在 `shared/layout/`）。真值源仍是 `e2e/*.mjs` 脚本。

| testid | 位置 | 用途 |
|---|---|---|
| `theme-btn` | TopBar | 主题切换（迁自 App.vue） |
| `nav-toggle` | TopBar | 汉堡按钮（<768 出现），phone-smoke 用它开抽屉 |
| `nav-home` / `nav-console` | SidebarNav | 概览 / 控制台一级入口（无 e2e 点击，仅登记） |
| `tab-list` / `tab-playbooks` / `tab-new` / `tab-validate` | SidebarNav | 控制台四个子导航项 |
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
| `binding-view` | BindingStores | 「商品绑定」卡（店铺聚合 + 点击下钻）容器 |
| `binding-store-<storeId>` | BindingStores | 店铺聚合一行（`storeId` 为 `__null__` 时是「未指定门店」桶） |
| `binding-spu-<spuId>` | BindingSpuList | 某店铺下钻明细的商品行 |
| `binding-spu-prev` / `binding-spu-next` | BindingSpuList | 下钻明细分页上一页/下一页 |
| `store-picker-toggle` | StoreProductPicker | 「从店铺勾选商品」展开/收起入口（EditorView manual 模式） |
| `store-picker-panel` | StoreProductPicker | 内联展开的选择面板容器 |
| `store-picker-store-<storeId>` | StoreProductPicker | 店铺列表一行（单选） |
| `store-picker-product-<spuId>` | StoreProductPicker | 某店商品勾选行（内含 checkbox） |
| `store-picker-prev` / `store-picker-next` | StoreProductPicker | 商品分页上一页/下一页 |
| `store-picker-confirm` | StoreProductPicker | 「加入绑定」——把勾选结果 append 进 dr.spu |
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
| `home-go-list` / `home-go-new` | HomeView 快捷入口 | 概览页到活动列表与新建活动的快捷按钮 |
| `home-recent-{id}` | HomeView 最近活动行 | 点击进活动详情 |
| `nav-home` | SidebarNav 概览入口 | 见上「全局/外壳」 |
| `confirm-dialog` / `confirm-ok` / `confirm-cancel` | ConfirmDialog | 上下线 / 离开守卫二次确认 |

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
| `playbook-filter-{all\|reduce\|targeted\|gift\|blocked}` | `PlaybooksView` | 分类筛选 chip；计数为 0 的分组不渲染 |
| `playbook-card-{id}` | `PlaybooksView` | 玩法券卡（id 见 `playbooks.ts`） |
| `playbook-use-{id}` | `PlaybooksView` | 「用它新建」；**不可用的玩法刻意没有这个 testid** |
| `playbook-blank` | `PlaybooksView` 页头 | 从空白新建 |
| `playbook-applied` | `EditorView` | 「起点：X」提示条；普通编辑后追加「已改动」，切活动类型/权益形态后失效 |
| `mode-random` | `EditorView` 权益形态 chip | 随机金额一等形态；旧 `form-take-type` 下拉已撤销，`redPackageTakeType` 由形态推导 |

新增 e2e：`e2e-playbooks.mjs`（`npm run e2e:playbooks`），覆盖侧栏入口 / 12 张卡 /
不可用玩法写明缺什么且无按钮 / 筛选 / **跨屏预填链路** / 随机形态切换与区间输入 / 1440 与 390 零横向溢出。

## 2026-08 优惠验证全玩法

> 共用一页承载三条真实决策通道。玩法场景只准备输入形状与选择通道，不把玩法断言写进 URL，
> 也不保证命中；E2E 在独立租户内创建并上线唯一 SPU 活动，命中与否仍由服务端候选和资格条件决定。

### 场景与通道

| testid / value | 位置 | 说明 |
|---|---|---|
| `v-scenario` | ValidateView 场景选择 | 稳定 value：`flat` / `threshold` / `ladder` / `quantity` / `discount` / `tagged` / `store` / `region` / `gift` / `second-half` / `flash` / `addon` / `random` |
| `v-scenario-note` | 场景说明 | 明示场景只准备上下文、不指定活动且不保证命中 |
| `validate-mode-discount` | 通道选择 | 红包优惠；对应 `POST /activity-marketing/spu-discount` |
| `validate-mode-gifts` | 通道选择 | 买赠赠品；对应 `POST /activity-marketing/gifts` |
| `validate-mode-addon` | 通道选择 | 加价购；对应 options → 用户选择 → quote 两阶段 |
| `v-discount` / `v-gifts` / `v-addon-options` | 当前通道运行按钮 | 三者按当前场景互斥渲染；切场景会清除旧结果 |

### 决策上下文与结果

| testid | 位置 | 说明 |
|---|---|---|
| `v-spu` | 普通汇总模式 | 逗号分隔 SPU；只接受安全范围内的有限正整数 |
| `v-order-amount` / `v-quantity` | 普通汇总模式 | 有限正金额 / 正整数数量 |
| `v-user` / `v-district` / `v-store` / `v-tags` | 用户与订单上下文 | 用户、地域、门店、标签资格事实 |
| `v-lines` / `v-line-{i}` | `second-half` 明细模式 | 订单行容器 / 第 i 行；此模式不渲染汇总输入 |
| `v-line-spu-{i}` / `v-line-price-{i}` / `v-line-qty-{i}` | 明细行 | SPU、单价、数量；唯一导出 `spuIdList/orderAmount/quantity/lines` |
| `v-line-add` / `v-line-remove-{i}` / `v-line-summary` | 明细编辑 | 增行、删行、只读汇总 |
| `validate-result` | 统一结果摘要 | discount 的 hit/miss、gifts 的 hit/empty、addon 的 options/empty |
| `v-error` | 请求失败 | 输入校验或非 409 请求错误；残缺/NaN 输入不会发请求 |
| `v-inventory-note` | flash / addon | 明示试算不扣减、不占用库存 |
| `v-price-breakdown` | flash 命中结果 | 原价 / 减免 / 应付；决策只是报价 |

### 加价购两阶段

稳定交互顺序：`v-scenario=addon` → 填 `v-spu` 与订单上下文 → `v-addon-options` →
用户勾选 `v-addon-option-{i}` → `v-addon-quote`。不得自动替用户选择选项。

| testid | 说明 |
|---|---|
| `v-addon-option-{i}` | options 返回的第 i 个用户可选 radio |
| `v-addon-quote` | 以所选 `activityId + itemName` 请求服务端权威报价 |
| `v-addon-quote-result` | 200 且 `ok=true`；明示未下单、未占库存 |
| `v-addon-conflict` | 伪造、资格变化或活动/选项失效导致的独立 409 状态 |

新增真链路 e2e：`e2e-validation.mjs`（`npm run e2e:validate`，默认 BASE=8095，header-only）。
脚本覆盖 12 个模板场景 + random 的正向，threshold / ladder / quantity / targeting / gift 499→500 /
nth / flash / addon 的反向，random 同上下文复跑与区间，addon options→radio→quote + 伪造/失效 409，
flash 试算前后库存不变，以及 390 / 768 / 1440 零页面级横向溢出。测试数据按时间戳隔离，失败截图写 `/tmp`，
`finally` 尽力下线本次创建的全部活动。

**图标系统**：全站 emoji/几何字形已统一为内联 SVG `Icon.vue`（`shared/ui/Icon.vue`）。装饰性图标 `aria-hidden`，语义图标透传 `aria-label`。
**路由过渡**：`PageTransition.vue` 落 AppShell / ConsoleShell 两个出口；被全局 `prefers-reduced-motion` 兜底禁用。
**首页路由**：`/` 与 catch-all 改指 `/home`（无 e2e 走裸根路径，零冲突）；`/console` 仍 redirect `/console/activities` 不变。

## 2026-08 权益形态扩容 + 视觉换代（补登记）

> 这一节补的是**此前漏登记**的 testid 与脚本。契约表的价值在于「改了要在这里留痕」，
> 漏登记的后果是下一个人照旧表写断言、跑起来才发现对不上——本轮就发生过：
> `e2e-playbooks.mjs` 里三条断言编码的是旧现实（第二件半价标灰、随机金额禁用），
> 功能上线后没同步，直接变红。

### 权益形态表单（`EditorView`）

| testid | 出现条件 | 说明 |
|---|---|---|
| `mode-fixed` / `mode-random` / `mode-ladder` | 活动类型=红包 | 形态切换到固定金额 / 随机金额 / 阶梯分档 |
| `mode-ratio` | 活动类型=红包 | 形态切换到折扣型 |
| `form-zhe` / `form-max-discount` | `redMode='ratio'` | 折数 (0,10) 与**必填**封顶额 |
| `form-price` | `redMode='price'` | 一口价（秒杀）卖多少；配 `form-seckill-inventory` |
| `form-seckill-inventory` | `redMode='price'` | 秒杀库存；真扣减走写平面 `/{id}/claim` |
| `form-nth` / `form-nth-zhe` | `redMode='nth'` | 第几件（≥2）与折数；需决策入参带 `lines` |
| `form-range-min` / `form-range-max` | `redMode='random'` | 随机红包区间两端（存成 `{"min","max"}` 对象，与阶梯的数组互斥） |
| `ratio-plain` | `EditorView` 折扣型 | 人话预览（封顶额与到顶门槛） |
| `detail-ratio` | `DetailView` | 折扣型详情展示（折数不能按金额渲染） |
| `detail-benefit-form` / `panel-benefit-form` | `DetailView` / `ListView` 侧板 | 从 rule 数据导出的权益形态徽标 |
| `undo-red-mode` | 切换权益形态后的 toast | 撤销本次切换并恢复被清理的形态字段 |

### 阶梯刻度尺（`TierRuler`）

| testid | 说明 |
|---|---|
| `tier-ruler` | 刻度尺容器 |
| `tier-add` | 加一档 |
| `tier-knob-{i}` | 第 i 档的卡子（`e2e-tier-ruler.mjs` 直接拖它；**class `.knob` 也被脚本依赖，不可改名**） |
| `tier-reward-{i}` / `tier-issue-{i}` | 第 i 档奖励额与问题提示 |
| `tier-plain` | 人话预览 |

### 脚本清单（补齐）

| 脚本 | npm script | 档位 | 覆盖 |
|---|---|---|---|
| `e2e-tier-ruler.mjs` | `e2e:ruler` | header（BASE=8095） | 刻度尺拖拽 / 键盘可达 / 人话实时跟随 / 零横向溢出 |
| `e2e-visual-guard.mjs` | `e2e:visual` | header（BASE=8095） | **视觉与移动端红线**：手机工具条几何、小屏关玻璃、触控 ≥44px、零横向溢出、reduced-motion 无循环动画、深色面强调色主题无关、双档打印回落白底 |

> 档位提醒：编排默认 **auth 档**。除 `e2e:oidc` 外的 8 套都走 header 档，跑之前需
> `DROOLS_AUTH_ENABLED=false DROOLS_DEV_DEFAULT_ENABLED=true docker compose -f deploy/docker-compose.yml up -d`。

## 2026-08 投放地域选择器（console-district-picker）

> 落点：`EditorView` 基础信息区，`areaType===2` 时才渲染（**默认「全国」不渲染** ——
> 六条经过编辑页的 e2e 只填名称/金额/SPU，这个默认值是它们零改动的前提）。
>
> **2026-08-13 树形重设计变更**：miller 三栏级联 → 省市区**树形勾选**。组件 `DistrictCascader` → `DistrictTree`（+ 递归 `DistrictTreeNode`）。
> **删**：`district-cascader`（改名 `district-tree`）、`district-into-{code}`（下钻）、`district-hit-{code}`（独立命中列表——改为树内过滤，命中仍是 `district-opt-{code}`）、`district-crumb-*`（面包屑）、`district-chips-more`（已选清单改结构化分组、不再截断）。
> **加**：`district-tree`、`district-expand-{code}`（展开三角）、`district-selected-only`（只看已选）、`district-collapse-all`（折叠全部）。
> **保留且语义不变**：`district-opt-{code}`（勾选框，仍是 4 个 e2e 等「字典渲染完成」的信号；折叠态顶层恰 34 个，依赖子级 `v-if` 惰性挂载）、`district-search`/`-clear`/`-trunc`、`district-chips`/`-chip-x-{code}`。

| testid | 位置 | 用途 |
|---|---|---|
| `form-area-type` | EditorView | 地域类型下拉（1=全国 / 2=指定地域） |
| `district-picker` | DistrictPicker | 选择器根节点 |
| `district-toggle` | DistrictPicker | 展开/收起树面板 |
| `district-count` | DistrictPicker | 「已选 N / 146」计数 |
| `district-clear` | DistrictPicker | 清空已选 |
| `district-chips` | DistrictPicker | 完整已选清单容器（按省分组 + 未知代码单列） |
| `district-chip-x-{code}` | DistrictPicker | 移除某个已选地域 |
| `district-limit` | DistrictPicker | 达到 146 个上限的提示 |
| `district-unknown` | DistrictPicker | 含已撤销/未知代码的提示 |
| `district-warning` | DistrictPicker | 字典不可用降级条 |
| `district-raw` | DistrictPicker | 字典不可用时的裸 CSV 逃生门输入 |
| `district-empty-hint` | DistrictPicker | 一个都没选时的提示 |
| `district-tree` | DistrictTree | 树面板根节点 |
| `district-search` | DistrictTree | 搜索框（≥16px，防 iOS 聚焦缩放；打字即树内就地过滤） |
| `district-search-clear` | DistrictTree | 清空搜索 |
| `district-search-trunc` | DistrictTree | 命中过多的截断提示 |
| `district-selected-only` | DistrictTree | 「只看已选」过滤开关 |
| `district-collapse-all` | DistrictTree | 「折叠全部」回到 34 省 |
| `district-opt-{code}` | DistrictTreeNode | 树节点的勾选框（搜索态下命中也是它） |
| `district-expand-{code}` | DistrictTreeNode | 展开/收起该节点的下级（仅省/市有） |

## 2026-08 产品化与生命周期补登记

| testid | 位置 | 说明 |
|---|---|---|
| `form-currency` | EditorView | 活动币种；当前后端缺省 CNY，前端提交显式值 |
| `status-action-{id}` | ListView | 当前行主状态动作；未来草稿可预约，预约态可取消/切换 |
| `cancel-scheduled-{id}` | ListView | 取消 `PENDING_EFFECT` 预约，不影响并存的 ONLINE 服务版本 |
| `detail-loaded` | DetailView | 详情异步加载完成锚点 |
| `detail-addon` / `detail-gift-row` | DetailView | 加价购/买赠详情 |
| `detail-price` / `detail-nth` / `detail-random` | DetailView | 一口价、第 N 件折、随机金额详情 |

企业权益中台 AwardBinding 暂无独立 UI；`POST /activity-awards/v1/intents` 是内部触发接口，因此本契约不为其
虚构前端 testid。要做运营配置页时，先在 `docs/frontend.md` 明确路由与安全边界，再登记稳定选择器。
