# 实施计划 · 投放地域「树形勾选」重设计

- 日期：2026-08-13 15:25 ｜ 决策记录：见同目录 `DECISION_RECORD.md`
- 一句话：把 `EditorView` 里投放地域的「三栏 miller 级联」换成「可展开的省市区树 + 原生三态勾选」，纯前端，后端零改。
- 对照审查：本计划落地后可用 `/codex-review` 对照本文件审 diff。

> **2026-08-13 用户批准时的两处调整（覆盖原推荐默认，全文以此为准）**：
> 1. **保留完整已选清单**：不把已选清单降级为「只列未知码的兜底区」，而是保留一个**完整、结构化（按省分组）**的已选清单，与树同框卡片化——用「结构化」而非「删除」来解痛点④。`district-chips`/`district-chip-x-{code}` 保留。
> 2. **搜索改树内过滤**：搜索不再另起平铺列表，而是**在树内就地过滤**——只保留命中节点及其祖先链、自动展开、`<mark>` 高亮，命中名保持 `district-opt-{code}` 节点身份。因此 `district-hit-{code}`/`district-locate-{code}` **不再需要**；`EditorView.test.ts:741`「搜索态 `district-opt-*` 不在 DOM」的旧断言**必须改写**（搜索态现在正是靠 `district-opt-*` 展示命中）。
> 其余默认（折叠+回读自动展开、顺带补 `DistrictPicker.test.ts`）按推荐保留。

> **2026-08-13 实施完成（本机验证）**：
> - 新建 `districtLogic.ts`(+`checkStateOf`/`leafCountOf`/`selectedLeafCountOf`/`defaultExpandedOf`/`toggleNode`/`searchScope`/`isLeaf`)、`DistrictTree.vue`、`DistrictTreeNode.vue`、`DistrictTree.test.ts`、`DistrictPicker.test.ts`；重写 `DistrictPicker.vue`；删 `DistrictCascader.{vue,test.ts}`。
> - 契约/e2e/注释同步：`data-testid-contract.md` 重写地域段、`EditorView.test.ts`(into→expand、搜索注释)、`EditorView.vue:817`/`e2e-visual-guard:87` 陈旧注释、`e2e-phone-smoke`(树措辞 + 新增最深缩进溢出断言)、`e2e-tablet-smoke`(树措辞)。
> - **闸门全绿**：`npx vitest run` = 362/362（28 文件，含 district 逻辑 40 + DistrictTree 19 + DistrictPicker 10 + EditorView 77）；`vue-tsc --noEmit` 干净；`vite build` 成功；`./mvnw test` 退出 0（纯前端改动、后端零影响）。
> - **未执行**：4 个 e2e（`e2e:dev`/`:tablet`/`:phone`/`:visual`）需 `:8097` 活体后端 + Playwright，无法在本会话内起栈跑；已逐一核对断言与新 DOM 一致（`district-opt-440000`/`district-toggle`/`district-search`/`district-chips` 全保留、`count()===34` 由 `v-if` 惰性挂载单测钉住），**建议合并前在活体环境跑一遍**（尤其 phone-smoke 新增的最深缩进溢出断言）。

---

## 1. Goals / Non-goals

**Goals**
- G1 消除四痛点（见 DECISION_RECORD 靶表）：勾选/展开视觉分明；一个卡片化面板收束零碎；树多分支展开 + 搜索定位加速找地区；树 inline 态 + 只看已选让核对一目了然。
- G2 交互 = 树形勾选：省/市可展开，节点原生三态 checkbox（全选/半选/未选），省/市显示「已选叶子/总叶子」分数。
- G3 落库语义**一字不改**：勾省仍只存省码，祖先/后代互斥；树是纯派生视图。
- G4 响应式净简化：一套纵向树吃下 390/768/1280，删掉 miller 的分栏/面包屑分支。
- G5 契约同步：testid 契约、4 个 e2e、`DistrictCascader.test.ts` 与 `EditorView.test.ts` 地域用例全部对齐并绿。

**Non-goals**（详见 DECISION_RECORD「非目标」）
- 不改后端/接口/CSV 存储/146 上限；不做排除项语义、自动归并、虚拟滚动、懒加载字典、ARIA tree role、已撤销码迁移；不改 `DetailView` 的只读地域回显（独立组件，另行）。

---

## 2. 视觉方向与设计参考（沿用既有视觉语言）

**结论：完全沿用仓库既有 token 与组件先例，不引入新视觉体系、不新增颜色。** 权威文件 `frontend/src/shared/styles/tokens.css`。

| 用途 | Token / 先例 | 值 · 出处 |
|---|---|---|
| 强调（选中/焦点/高亮） | `--accent` | `#8B7BFF`/浅 `#5B4BE8`（`tokens.css:95`）；**永不用于状态编码**（:13） |
| 全选行底 | `--accent-soft`，名字 `--accent` + `--fw-semibold` | 照抄 `ListView.vue:706` `.tr.on` / `DemoNav.vue .item.active` |
| 半选行底 | `color-mix(in srgb, var(--accent-soft) 46%, transparent)` | 照抄 `DemoNav.vue .group.current`（本仓现成的「次级激活」配方，非自造） |
| 分数徽标底 | `--accent-ink`（`tokens.css:100`），`.mono`（`tabular-nums`，:360-363） | `TierRuler.vue:159` 先例；比 `<Badge>` 轻，34+ 行不显重 |
| 缩进导轨 | `border-left: 1px solid var(--border)` + `padding-left`（`ConditionGroup.vue:90` 用 2px，此处取 1px 更细，自证非照搬） | 参照 `ConditionGroup.vue:90` |
| 半选横杠 | 原生 `<input :indeterminate>`，`accent-color:var(--accent)` | `ListView.vue:451/709` 唯一先例，浏览器自绘、AT 原生播报 |
| 展开三角 | 独立 `<button>` + `chevron-right` 图标 `rotate(90deg)` | `DemoNav.vue` 折叠先例（竖向用 180°，横向树用 90°），`--dur-mid/--ease-out` |
| 未知码标记 | `<Badge kind="warn" shape="triangle">` | 沿用 `DistrictPicker.vue:103`（色+形双通道，`Badge.vue:8-12`） |
| 圆角/间距/字号/焦点/触控 | `--radius-sm`(7) `--radius-lg`(14) / `--sp-1..4` / `--fs-2xs..lg` / `--focus-ring` / `--touch-min`(44) | `tokens.css:135-137,156-163,172-177,229,165` |
| loading/empty/降级 | `Skeleton`/`EmptyState`/`Banner` | 现组件已引用，签名不改 |

**节点行结构（左→右）**：`[缩进导轨] [展开三角 · 仅有子节点] [方形 checkbox] [名字] [x/y 分数徽标 · 仅半选]`
- 痛点①解法：区县叶子行**无三角**（占绝大多数，歧义天然消失）；三角是**圆形**命中容器、checkbox 是**方形**，形状先于点击可辨；两者各自 `:focus-visible` 焦点环。
- 名字层级第二通道：省/市 `--fs-md`(14) + `--fw-semibold`，区县 `--fs-sm`(13)。
- **分数徽标只在 `checkStateOf==='indeterminate'`（半选）时显示**：全选（checked）时 checkbox 已充分表达、不显分数；零选不显。已知边缘：若某省被**逐个单独勾满全部子级**（未合并成省码），`checkStateOf` 仍是 indeterminate 而 `x/y` 恰为 `N/N`——这是「不做自动归并」（非目标）的必然表现，不是 bug，可在徽标 title 里注明「未整省选择」。
- 主题：全部走 token，暗/浅自动适配（tokens.css 双档已定义），无需组件内写死颜色。

---

## 3. 路由与页面流

**无新增路由。** 组件仍挂在 `activity-new` / `activity-edit` → `EditorView` 的「基础信息」段。

```
基础信息 · 地域类型 [全国 | 指定地域]
  └ areaType===2（懒加载字典）· 投放地域 *  ← DistrictPicker（.full 跨两列，内联展开，不用 SidePanel）
       ├ 头部：选择地域(toggle) · 已选 N/146 · 清空 · [只看已选] · [折叠全部]
       ├ 展开态（一个卡片内）：
       │    ├ 搜索框（sticky）—— 打字即在【树内就地过滤】：只留命中节点+祖先链、自动展开、<mark>高亮
       │    │      · 命中仍是 district-opt-{code} 树节点（不另起列表）；超 N 条给 district-search-trunc 提示
       │    └ 省市区树（q 为空=默认折叠 34 省；点省/市三角原位展开；三态勾选）
       ├ 已选清单区（完整·结构化）：按省分组「广东省(12) › 深圳市 · 珠海市…」，可移除、可折叠；未知/已撤销码单列打警告角标
       └ 降级：字典失败 → Banner + 裸 CSV 逃生门
```

**流程要点**：切「指定地域」才拉字典（`EditorView.vue:142-146` 懒加载不变）；切回「全国」草稿 `districtIds` 不清空、仅提交置 null（D9 不变）；编辑回读先 `stripDistrictNodes` 再喂 `selected`，并自动展开到已选路径。

**视图开关叠加规则（评审 P1，按用户「树内过滤」调整后）**：
- **搜索 = 树内过滤（不再互斥另起列表）**：`q` 非空时树只渲染命中节点及其祖先链（`search()` 命中 ∪ `ancestorsOf`），命中分支强制展开、`<mark>` 高亮；命中数用 `search()` 的 `limit` 截断（≤ 50~100），超出给 `district-search-trunc`。**搜索态下命中节点仍是 `district-opt-{code}`**（故 `EditorView.test.ts:741` 旧断言「搜索态 `district-opt-*` 不在 DOM」必须改写为「搜索态只剩命中的 `district-opt-*`」）。
- **搜索优先于「只看已选」**：`q` 非空时忽略「只看已选」（展示全部命中，不再叠加「是否已选」过滤），避免两个过滤器语义打架。
- **「只看已选」是纯渲染过滤，不改 `expanded`**：`q` 为空且开启时只渲染 `checkStateOf!=='unchecked'` 分支并强制可见（不 mutate 用户手动 `expanded:Set`）；关闭回到 `expanded` 驱动。
- **「折叠全部」**：一键清空 `expanded`（回 34 省折叠态），手动展开太多时的安全阀。
- 「定位」动作**取消**（树内过滤已直接定位到命中节点，无需二次跳转）。

---

## 4. 组件树（复用 / 新建 / 改）

| 组件 | 处置 | 职责 |
|---|---|---|
| `console/pages/EditorView.vue` | **改（极小）** | 挂载点、v-model、懒加载 watch **全不动**；仅按需微调（见 §8） |
| `console/district/DistrictPicker.vue` | **改** | 壳层：toggle / 计数 / 清空 / **只看已选** / 已选兜底区 / 未知码 / 裸 CSV 逃生门 / 卡片化容器。对外 `codes` v-model + `districts/loading/failed` props **契约不变** |
| `console/district/DistrictTree.vue` | **新建（取代 DistrictCascader）** | 面板体：搜索 + 命中平铺列表 + 持有 `expanded:Set<string>` + 渲染 34 省根 + loading/empty/降级分支 |
| `console/district/DistrictTreeNode.vue` | **新建（递归 SFC，仿 `ConditionGroup.vue`）** | 单节点行：三态 checkbox / 展开三角(仅有子) / 名字 / 分数徽标；`v-if="expanded"` 惰性挂子级 `<ul>` |
| `console/district/districtLogic.ts` | **改（只增不改）** | 新增 `checkStateOf` / `leafCountOf` / `selectedLeafCountOf` / `defaultExpandedOf` / `toggleNode`（见 §6）；现有函数一律不动 |
| `console/district/DistrictCascader.vue` | **删除** | miller 三栏被树取代 |
| `shared/ui/{Icon,Badge,Banner,EmptyState,Skeleton}.vue` | **复用** | 签名不改 |
| `stores/useDistrictStore.ts` · `console/activityApi.ts` · `shared/types.ts` | **不动** | 取数/类型契约不变 |

数据流：`DistrictPicker`（持 `codes`）→ `DistrictTree`（`:selected=codes` `@update:selected`）→ `DistrictTreeNode`（`toggle`/`toggle-expand` 事件冒泡到 `DistrictTree` 统一改 Set / emit）。**所有节点仍在 `DistrictPicker` 根 `@click.stop @input.stop` 子树内**（markDirty 防线，R8）。

---

## 5. 状态与边界（逐组件）

| 状态 | 表现 | 依据/复用 |
|---|---|---|
| loading（字典首拉） | 搜索框恒在（不跳布局）+ `<Skeleton :rows="6"/>` | `DistrictCascader.vue:89`；`DistrictCascader.test.ts:161-165` |
| empty（搜索无结果） | `<EmptyState icon="search" .../>` + 保留搜索词 | `DistrictCascader.vue:93` |
| error（字典失败） | `<Banner kind="warn" testid=district-warning>` + 裸 CSV `district-raw` 逃生门（`rawDraft` 防吞逗号）**保留**；已选码原样提交不置空 | `DistrictPicker.vue:87-89,120-124` |
| 超上限 146 | 顶部 `role=status` `district-limit` 提示 + 未选节点 checkbox `disabled`+行 `dim`；已选仍可取消 | `DistrictPicker.vue:91`；`DistrictCascader.vue:100,132` |
| 未知/已撤销码 | 不在树中 → 在完整已选清单里单列（分组外「未知代码」小节），`Badge warn triangle` + 可移除；`district-unknown` 提示；**不阻断保存** | `DistrictPicker.vue:94-97,103` |
| 一个都没选 | `district-empty-hint`「未选择（等同不投放…）」+ 进 `validationErrs`（`areaType===2 && len===0`） | `DistrictPicker.vue:115`；`EditorView.vue:180` |
| 全选省 | 省 checkbox `checked`；展开后子级渲染为 `checked+disabled`（被祖先覆盖，不可单独取消——非排除项语义） | `coveredByAncestor`，`DistrictCascader.vue:54-56` |
| 半选省/市 | 原生 `indeterminate` 横杠 + 行底 46%-mix + 分数徽标 `x/y` | R3；`ListView.vue:451` |
| 直辖市（两层） | `childrenOf().length===0` 判叶子 → 无三角；不特判 `level` | `types.ts:42-43`；`DistrictCascader.test.ts:100-107` |
| 搜索态（q 非空，树内过滤） | 树只渲染命中节点+祖先链、命中分支强制展开、`<mark>` 高亮；命中仍是 `district-opt-{code}`；忽略「只看已选」；超限给 `district-search-trunc`。**`EditorView.test.ts:741` 改写为「搜索态只剩命中 `district-opt-*`」** | `search()` + `ancestorsOf`（`districtLogic.ts:78,98`） |
| 只看已选（新） | **纯渲染过滤，不 mutate `expanded`**：`q` 为空且开启时只渲染 `checkStateOf!=='unchecked'` 分支并强制可见；关闭回到 `expanded` 驱动 | 新增 |
| 折叠全部（新） | 清空 `expanded` 回 34 省折叠态 | 新增（评审 P2 安全阀） |
| 已选清单（完整·结构化） | 按省分组显示全部已选（`广东省(12) › 深圳市…`），可移除、可折叠；始终在卡片内与树同框 | 用户批准调整①；`district-chips`/`chip-x` 保留 |
| 字典 null（未拉/降级） | 树不渲染节点、不崩；裸 CSV 逃生门可用 | R8 |

---

## 6. `districtLogic.ts` 新增纯函数契约

```ts
export type CheckState = 'checked' | 'indeterminate' | 'unchecked'

// 短路：自身或任一祖先在 selected → 'checked'（不下探）；否则子树有后代在 → 'indeterminate'；否则 'unchecked'
export function checkStateOf(index: DistrictIndex, selected: string[], code: string): CheckState

// 子树内叶子（childrenOf().length===0 者）总数。建议 buildIndex 时预计算 Map<code,number> 缓存
export function leafCountOf(index: DistrictIndex, code: string): number

// 子树内「被选中覆盖」的叶子数（自身/祖先选中→全部叶子计入；否则递归求和）。驱动 12/21
export function selectedLeafCountOf(index: DistrictIndex, selected: string[], code: string): number

// selected 中每个码的祖先链并集（供编辑回读/定位自动展开）
export function defaultExpandedOf(index: DistrictIndex, selected: string[]): Set<string>

// 迁移现 DistrictCascader.toggle：已选→removeCode；被祖先覆盖或已满→原样返回；否则→addCode
export function toggleNode(index: DistrictIndex, selected: string[], code: string): string[]
```

- **红线**：`checkStateOf` 先短路自身/祖先（免疫字典漂移，见 DECISION_RECORD R3）。叶子判定用 `childrenOf().length===0`，禁用 `level===3`。
- **健壮性**：对未净化数组（回读的祖先+后代同存、未知码）不 throw、不死循环（复用 `guard++<8`）。
- **性能（评审 P2 已修正）**：`leafCountOf` 走 `buildIndex` 时预计算的静态 `Map<code,number>`。**`selectedLeafCountOf` 不能按节点各自递归子树**——折叠态下每个半选省的分数徽标仍要它，最坏近似遍历全树。改为**按 `selected` 反向折叠一次**：遍历 `selected` 里的每个码，对「叶子码」把 1 计入其祖先链上每个节点，对「非叶子码（整选的省/市）」把该码的 `leafCountOf` 计入其祖先链——一趟得到 `Map<code, selectedLeafCount>`，复杂度 `O(|selected| × 深度≤3)`，每次 `selected` 变化重算一次（`computed` 缓存），与展开与否、与总行数 3212 都无关。`checkStateOf` 同样从这张 Map + `ancestorsOf` 短路 O(深度) 得出，禁止模板里对 3212 行裸递归。

---

## 7. API 契约

**零改动。** `GET /activity-marketing/districts`（`ActivityMarketingController.java:313-318`）、`listDistricts(signal?)`（`activityApi.ts:60-61`）、`District` 类型（`types.ts:45-55`）、全量拉取策略（D2）全部不变。后端 `DistrictQueryService.expandWithDescendants` / `mergeDistrictCondition` / `stripDistrictNodes` 不碰。`DistrictDictAndScopeTest.java` / `DistrictSeederTest.java` 应原样绿。

---

## 7.5 响应式与移动端适配策略（断点表 · 逐视口 · 触控）

**核心：树是净简化——一套纵向单栏吃下全部断点，删掉 miller 的分栏/面包屑状态机。** 断点走正典四档（`tokens.css:31-35`），不新造数值。

| 视口 | 档 | 树形态 | 关键 CSS |
|---|---|---|---|
| **390**（手机 `<768`） | phone | 纵向单栏树，展开原位插子节点（无面包屑「换页」） | 缩进 `padding-left: calc(var(--sp-4) * var(--depth))`，`--depth`=`ancestorsOf().length`（**非 `level`**，117 个 level=3 直挂省级）；容器 `min-width:0`，名字 `ellipsis`；滚动 `max-height: min(50dvh,480px); overflow-y:auto` |
| **768**（平板 `768-1023`） | tablet | **同一套单栏树，无分支**——树不像 miller 要按宽定栏数，此档天然规避现有三栏在 768 的横向溢出风险 | 同上；缩进最深 2 级=32px，可用宽 ≈720px 无风险 |
| **1280**（桌面 `≥1024`，画布 ≈888px） | desktop | 同一套单栏树 | 同上；不为宽屏单独维护缩进算法 |

**交互替换（桌面→触屏）**：miller 的「点箭头下钻/面包屑回退」→ 树的「点圆形三角原位展开/折叠 + 折叠全部」；hover 态用 `@media (hover:hover)`（触屏 hover 会粘住、被误读成选中，沿用 `DistrictCascader.vue:203-205` 注释理由）。
**触控命中区**：全局 `(pointer:coarse)` 兜底**排除 checkbox/radio**（`tokens.css:382`）且压不过 scoped，故树节点行/展开三角/搜索清空/只看已选/折叠全部**逐一自补** `min-height:44px`；checkbox 命中区靠整行 `<label>` 撑 44px（`DistrictCascader.vue:186-193` 模式）。
**iOS**：搜索框 `font-size:var(--fs-lg)`(16px) 防聚焦缩放，`district-search` testid 保留（`e2e-visual-guard:112` 硬查）。
**无 safe-area/fixed**：沿用内联展开、非 sheet，`env(safe-area-*)` 不接入。
**移动端验收**（见 §11.5）：390 展开长地名深层节点零横向溢出 + 所有可点 ≥44px + 搜索 ≥16px；768 展开零横向溢出。

---

## 8. 文件级改动清单

**新建**
- `frontend/src/console/district/DistrictTree.vue`
- `frontend/src/console/district/DistrictTreeNode.vue`
- `frontend/src/console/district/DistrictTree.test.ts`（替代 `DistrictCascader.test.ts` 的树/勾选/搜索用例）
- `frontend/src/console/district/DistrictPicker.test.ts`（补现有 5 个零覆盖 testid + 已选兜底区/未知码）

**改**
- `frontend/src/console/district/districtLogic.ts`：+5 纯函数（§6），现有函数不动
- `frontend/src/console/district/districtLogic.test.ts`：+`checkStateOf`/`leafCountOf`/`selectedLeafCountOf`/`defaultExpandedOf`/`toggleNode` 单测（含红线短路、直辖市两层、未净化数组、字典漂移不误翻）
- `frontend/src/console/district/DistrictPicker.vue`：引用 `DistrictTree` 取代 `DistrictCascader`；加「只看已选」；已选区卡片化 + 未知码单列
- `frontend/src/console/pages/EditorView.vue`：**仅**在回读后按 `defaultExpandedOf` 设初始展开（可放 DistrictTree 内部完成，则 EditorView 逻辑零改）；清理 `:817` 提及 `DistrictCascader` 的陈旧注释；其余不动
- `frontend/src/console/pages/EditorView.test.ts`：地域用例里 `:665` 的 `district-into-440000` 改 `district-expand-440000`、凡点 `crumb-*` 的改为树操作；**`:741` 的「搜索态 `district-opt-*` 不在 DOM」断言改写为「搜索态只剩命中的 `district-opt-*`」**（树内过滤后命中仍是 opt 节点）；回读保真(`:681-713`)、脏值(`:721-745`)、逃生门(`:747-771`) 断言不变。**此改动并入 step 5**，否则 step 5 结束时该文件即红
- `frontend/e2e/data-testid-contract.md`：**重写** `:246-253`（不是仅追加 changelog）——删 `district-cascader/into/crumb/hit`（树内过滤后无独立命中列表）、把 `search/opt/search-trunc` 归属从 DistrictCascader 改为 DistrictTree、加 `district-expand-{code}`/`district-selected-only`/`district-collapse-all`；`chips`/`chip-x-{code}`/`chips-more` 保留（完整已选清单）；顶部加一行本轮变更说明
- `frontend/e2e/e2e-dev-v2.mjs`：树操作路径（展开→省级全选→chips 中文→提交→详情回显）；`count()===34` 断言保留（依赖 `v-if` 惰性挂载）
- `frontend/e2e/e2e-tablet-smoke.mjs` / `e2e-phone-smoke.mjs`：等待信号仍用 `district-opt-440000`；注释措辞从「三栏」改「树缩进」；溢出断言数值不变
- `frontend/e2e/e2e-visual-guard.mjs`：A-13 段的触控扫描/搜索字号断言保留；如给展开三角加 `<button>`，自动纳入 44px 扫描；清理 `:87` 提及 `district-cascader` 的陈旧注释

**删除**
- `frontend/src/console/district/DistrictCascader.vue`、`frontend/src/console/district/DistrictCascader.test.ts`

---

## 9. 实施步骤（按依赖排序）

1. **纯逻辑先行**：`districtLogic.ts` 加 5 函数 + `districtLogic.test.ts` 补齐单测，**先跑绿**（`npx vitest run districtLogic`）。这是全部易错语义的载体，先钉死。
2. **递归节点**：`DistrictTreeNode.vue`（三态 checkbox / 三角 / 分数 / `v-if` 惰性子级），props `{node,index,selected,expanded:Set,depth}`，emit `toggle`/`toggle-expand`。
3. **树体**：`DistrictTree.vue`（搜索 + 命中平铺列表 + `expanded:Set` + 渲染 34 省 + 状态分支），对外 `:selected`/`@update:selected` 与旧 `DistrictCascader` 同签名。
4. **树组件测试**：`DistrictTree.test.ts`（展开/折叠、逐级勾、省级全选 emit 单码、`indeterminate` 用 `(el as HTMLInputElement).indeterminate` 断言、搜索命中+定位、被祖先覆盖节点仍显示、直辖市无三角、真实 3212 行冒烟省级恰 34）。
5. **接壳（含 EditorView 测试同步）**：`DistrictPicker.vue` 换引用 + 只看已选 + 折叠全部 + 已选区卡片化 + 未知码单列；`DistrictPicker.test.ts` 新建；**同一步**把 `EditorView.test.ts:665` 的 `district-into-440000`→`district-expand-440000`、清理 `EditorView.vue:817` 陈旧注释——否则本步结束 `EditorView.test.ts` 即红。跑 `EditorView.test.ts` 地域段绿。
6. **回读展开**：`defaultExpandedOf` 接进 `DistrictTree`（编辑态自动展开到已选）；确认回读保真/脏值/逃生门/搜索态断言全绿。
7. **删旧**：删 `DistrictCascader.vue` + 其测试；全仓 grep 无残留引用。
8. **契约与 e2e**：更新 `data-testid-contract.md`；改 4 个 e2e 的选择器/注释；本地 `hasTouch` 档跑 `e2e-phone-smoke`/`e2e-tablet-smoke`/`e2e-visual-guard`/`e2e-dev-v2`。
9. **闸门**：`cd frontend && npx vitest run` 全绿；`./mvnw test` 全绿（后端应零影响，作回归确认）。

---

## 10. 测试策略（含移动端视口矩阵）

| 层 | 文件 | 关键用例 |
|---|---|---|
| 纯逻辑单测 | `districtLogic.test.ts` | `checkStateOf` 五类（自身选中/祖先选中→checked 不下探；部分后代→indeterminate；无→unchecked；**字典新增子节点后父码仍 checked 不误翻**；直辖市两层）；`leafCountOf`/`selectedLeafCountOf`（含直辖市、未净化数组）；`defaultExpandedOf`；`toggleNode`（covered/full 早退、复用 addCode 互斥）；现有互斥/CSV/剥离用例**原样保留** |
| 组件测 | `DistrictTree.test.ts` | 省级全选只 emit `['440000']`（复用旧 :43-47 断言）；`indeterminate` 用 `(el as HTMLInputElement).indeterminate` 断言；展开 `v-if` 挂载/折叠卸载子级；被祖先覆盖节点 `disabled+dim` 仍显示（**补现有测试空白**）；**树内过滤**：简称/拼音命中→只剩命中+祖先且展开、`<mark>` 高亮、搜索态忽略「只看已选」、超限 `district-search-trunc`；折叠全部清 `expanded`；降级 null/空/loading |
| 组件测 | `DistrictPicker.test.ts` | 完整已选清单按省分组+移除；只看已选过滤（纯渲染、不改 expanded）；未知码单列可移除；`district-count`；清空；裸 CSV 逃生门逗号不被吞；补 `warning/limit/empty-hint/chips-more`（现零覆盖） |
| 页面集成 | `EditorView.test.ts` | areaType=1 不发 `/districts`；省市互斥不重复占名额；未选拦保存；**含撤销码回读保真一字不差**（:681-713 应不变）；搜索打字不算脏值；字典 500 逃生门；回读剥离注入节点 |
| e2e · 桌面链路 | `e2e-dev-v2.mjs` | 展开→省级全选→`district-chips` 含「广东」→提交→详情含中文；`count()===34`（`v-if` 前提） |
| **e2e · 390 手机** | `e2e-phone-smoke.mjs` + `e2e-visual-guard.mjs` A-13 | 等 `district-opt-440000`；`scrollWidth-innerWidth<=4` **零横向溢出**；`district-picker` 内 button/role 全 ≥44px；`district-search` 字号 ≥16px；**新增**：展开长地名分支（新疆 `650000` → `district-expand-650000` → 其下 12 字长名「克孜勒苏柯尔克孜自治州」`653000`，再展开到其 level-3 县，取最深缩进）后仍零横向溢出 |
| e2e · 768 平板 | `e2e-tablet-smoke.mjs` | 展开后零横向溢出（注释改树语义） |
| 后端回归 | `./mvnw test` | 应零影响，确认 476 例仍绿 |

---

## 11. 验收标准

1. 四痛点逐一可演示消除（见 DECISION_RECORD 靶表）。
2. 勾省仍只落 1 个省码（`selected`/CSV 与改造前逐字节一致）；三态勾选/分数徽标正确；直辖市无三角、可正常勾。
3. 编辑既有活动：回读保真（不改直接保存 `districtIds` 一字不差）；含已撤销码不丢、打警告角标；自动展开到已选路径。
4. `cd frontend && npx vitest run` 全绿；`./mvnw test` 全绿。
5. **移动端（必含）**：390px 展开树（含深层长地名节点）`scrollWidth-innerWidth<=4` 零横向溢出；`district-picker` 内所有可点元素 ≥44px；搜索框字号 ≥16px。768px 展开零横向溢出。
6. 4 个 e2e 脚本（`hasTouch` 档）全绿；`district-opt-440000` 作渲染信号仍有效；`count()===34` 成立（证明子级 `v-if` 惰性挂载）。
7. `data-testid-contract.md` 与实际 DOM 一致（删 into/crumb、加 expand/selected-only/locate）。

---

## 12. 风险与回滚

| 风险 | 影响 | 缓解 |
|---|---|---|
| `checkStateOf` 写成「数子节点比例」而非短路 | 字典新增区 → 全选省误翻半选，静默错 | §6 红线 + 专项单测（字典漂移不误翻） |
| 子级用 CSS 折叠而非 `v-if` | `e2e-dev-v2` `count()===34` 静默失效；3212 行进 DOM 卡 | §6/R6 硬约束：`v-if` 条件挂载；组件测断言展开/折叠时子节点在/不在 DOM |
| testid 改名漏改 e2e（闸门盲区，不在 mvnw/vitest 里） | 合并后 e2e 静默红 | 保留 `district-opt-{code}`/`district-search`；§8 显式列改动；步骤 8 本地实跑 4 个 e2e |
| 树组件被 `EditorView.vue:822/824` `:deep(.fg > label / .fg > label > input)` 波及 | 行高爆、34 省只见两三行 | 该选择器是**直接子代 `>` 组合器**，只命中 `.full` label 的直接子 input——树的 `.row` label 是深层后代、`DistrictPicker` 根是 `<div>`，**本就不被命中**。红线只需：不把裸 `<input>/<select>` 作为 `<label class="full">` 的直接子级（评审已实证隔离成立） |
| 交互元素挪出 DistrictPicker 根 → 绕开 `@click.stop/@input.stop` | 搜索打字/展开误触 markDirty、清掉成功态 | 新树全部在 DistrictPicker 根子树内（R8）；`EditorView.test.ts` 脏值用例守 |
| 深层缩进 + 长地名在 390 溢出 | 撞零溢出断言 | 缩进 16px×depth（≤32）；`min-width:0`+ellipsis；e2e 新增深层长地名断言 |
| 半选/分数每次渲染重算（折叠态徽标仍要数整棵子树，评审 P2 纠正） | 大选中集下卡顿 | `leafCountOf` 预计算静态 Map；`selectedLeafCountOf` 改「按 `selected` 反向折叠到祖先」一趟成 `Map`（§6），`O(|selected|×3)`，与展开与否/总行数无关，`computed` 缓存 |

**回滚**：纯前端。后端/接口/`districtLogic` 现有函数/`EditorView` 挂载契约均不变 → 保留 `DistrictPicker` 对外 `codes` v-model 契约不变是回滚成本最低的保证。极端情况可 `git revert` 本次前端提交，后端不受影响。注意 e2e 不在 CI 闸门内，回滚/前进都要主动同步 4 个 `.mjs`（`agent-brief.md:72-74`）。
