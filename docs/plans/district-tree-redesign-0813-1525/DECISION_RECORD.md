# 决策记录 · 投放地域「树形勾选」重设计

- 日期：2026-08-13 15:25
- 范围：`frontend/src/console/district/` 下的地域选择组件 + `EditorView.vue` 挂载段；**纯前端**，后端契约零改动
- 触发：用户反馈现三栏 miller 级联「很不好用」，四个痛点全部确认，并明确选定「树形勾选」方向
- 上一轮规划：`docs/plans/console-district-picker-0813-0940/`（D1–D10 + FINAL_PLAN），本轮**推翻其中的 D3**（见 R0）

---

## 用户确认的四痛点（本轮设计的验收靶子）

| # | 痛点 | 现状根因（带文件） | 本设计如何消除 |
|---|---|---|---|
| ① | 勾选框与「>」下钻箭头样子接近、分不清点哪个是选中 | `DistrictCascader.vue:131-140` 每行同时有 `<input checkbox>` 和紧邻的 12px `.into` 箭头，两者都无独立底色 | 展开三角**只出现在省/市行**（区县叶子占绝大多数，根本无三角）；三角做成**圆形按钮** vs checkbox 的**方形**，几何形状先于点击就能区分 |
| ② | 界面太零碎（触发按钮 / chips / 展开面板三块割裂） | `.chips`（`DistrictPicker.vue:144`）无边框背景，与下方 `.col`（有边框）视觉不连续 | 已选态直接内联在树节点上（checkbox + 半选 + 分数徽标）；已选清单卡片化、与树同框；一个面板承载全部 |
| ③ | 找地区慢（3000+ 区县逐级点） | miller 一次只能展开一条省→市链（`drill` 单路径，`DistrictCascader.vue:28`），换分支要回退面包屑 | 树可多分支同时展开、原位插入子节点；保留搜索并新增「定位到树」；`accent` 分数徽标让「哪个省选了多少」一眼可见 |
| ④ | 已选难核对（chips 堆一片） | 选多了 chips 平铺成一大片，看不出省市结构 | 树本身即已选视图（checkbox/半选/分数）；新增「只看已选」过滤 + 编辑回读时自动展开到已选路径 |

---

## R0 · 推翻上一轮 D3（否决树形）的理由回应

上一轮 `console-district-picker-0813-0940/DECISION_RECORD.md` 的 **D3 明确否决了树形**，原文两条理由：

> 「树形勾选：34 省全展开 3212 行，不做虚拟滚动就卡」
> 「手写代价：高——递归组件 + roving tabindex + 虚拟滚动」

本轮结论：**D3 的否决建立在两个可以被规避的实现假设上**，用户既已明确要树形，逐条回应如下——

| D3 原始担忧 | 本设计的规避 | 证据 |
|---|---|---|
| 全展开 3212 行不做虚拟滚动就卡 | **默认折叠**只渲染 34 个省根；子节点靠 `v-if="expanded"` **惰性挂载**，展开一个省最多插 ~37 行（实测单节点最大子数 37）。3212 全进 DOM 只发生在用户手动逐个展开全部——极端边缘，非默认路径。**不需要虚拟滚动。** | CSV 实测 34 根 / 单节点 max 37 子；`v-if` 惰性挂载同时是 e2e `count()===34` 断言成立的**硬前提**（见 R6） |
| 递归组件 + roving tabindex + 虚拟滚动手写代价高 | **不采用 ARIA tree pattern**（无 roving tabindex）。用原生嵌套 `<ul>/<li>` + 原生 `<input type=checkbox :indeterminate>` + 原生 `<button aria-expanded aria-controls>`：Tab 走焦点、Space 切勾选、原生播报「部分选中」，零自定义键盘代码。递归组件本仓已有先例 `ConditionGroup.vue`。 | 全仓 grep 无 `role=tree/treeitem`；`ListView.vue:451` 原生 `:indeterminate` 先例；`DemoNav.vue` `aria-expanded+aria-controls` 折叠先例；`ConditionGroup.vue` 递归 SFC 先例 |

> 记录立场：这是对同日 D3 的**正式推翻**，不是并行分支。推翻依据 = 用户明确偏好 + 上述两条规避使 D3 的代价/性能理由不再成立。若未来产品要求「默认全展开」或「搜索命中自动展开全部祖先链且保持其余分支展开」，D3 的性能担忧会**重新成立**，需另评估（列入非目标/风险）。

---

## R1 · 交互模型：树形勾选（已定）

- **决策**：省市区可展开树，节点行 = `[展开三角(仅省/市)] [方形 checkbox] [名字] [右侧 x/y 分数徽标(仅省/市)]`。
- **勾选语义完全复用现有纯逻辑**：勾省 = `addCode(省码)` = 只存 1 个省码（祖先/后代互斥，`districtLogic.ts:117-124`）；后端保存时才展开成后代（`DistrictQueryService.expandWithDescendants`）。**树是纯派生视图，绝不把父级展开成后代码写进 `selected`**（本轮最易被「视觉直觉」带偏的一条，`DistrictCascader.test.ts:43-47` 钉死）。
- 备选（未采纳）：经典级联下拉、保留 miller 打磨——用户已在提问环节直接选定树形，不再对比这两者的取舍，仅在下方架构/搜索维度对比落地方案。

---

## R2 · 组件架构：递归 `DistrictTreeNode`（推荐）vs 扁平行数组

| | 方案 A · 递归 DistrictTreeNode（**推荐**） | 方案 B · 扁平行数组（单组件计算 visibleRows） |
|---|---|---|
| 结构 | `DistrictTree`（壳：搜索/状态/持有 `expanded:Set`）→ 递归 `DistrictTreeNode`（原生嵌套 `<ul>/<li>`） | `DistrictTree` 单组件，纯函数 `visibleRows(index,selected,expanded)` 算出扁平行，一次性 `v-for` |
| a11y | 原生嵌套 `ul/li` + `aria-expanded/aria-controls` 指向子 `<ul>`，AT 播报层级最自然 | 扁平列表，父子的 `aria-controls` 关联难表达 |
| 深度≠level | 递归天然处理（117 个 level=3 直挂 level=1；直辖市两层） | 需在 `visibleRows` 里手工处理 |
| 先例 | `ConditionGroup.vue` 递归 SFC（本仓唯一递归先例） | 无先例 |
| 批量操作（展开到已选/定位） | `expanded:Set` 由壳集中持有，批量改一个 Set 即可 | 同样容易（改 Set） |
| 虚拟滚动友好 | 若将来要，需先拍平 | 数据已扁平，改造成本低 |
| 可测性 | 选择态推导是**纯函数**（`districtLogic.ts`），与组件形态解耦，两方案同样可测 | 同左 |

- **决策：方案 A**。理由：① a11y 用原生嵌套语义最稳（UIUX 维度强推、全仓无 ARIA tree 先例可抄）；② 深度≠level 递归天然吃下；③ 有 `ConditionGroup.vue` 递归先例；④ 我们已确定**不做虚拟滚动**，故方案 B 唯一独占的优势（扁平利于虚拟滚动）不成立；⑤ 选择态推导本就抽成纯函数单测，方案 B 的「单组件更好测」优势也被抵消。
- **代价与守卫**：递归的「任意深度」在 ≤3 层数据上是未用到的泛化——用 `expanded:Set` 集中态 + 惰性 `v-if` 把它约束住，不引入 `MAX_DEPTH` 递归炸栈风险（`childrenOf().length===0` 判叶子，非 `level===3`）。

---

## R3 · 半选/全选态：新增 `districtLogic.ts` 纯函数（**本轮最大的新逻辑**）

| 树节点态 | 判定 | 对应 `selected`（=CSV） |
|---|---|---|
| checked（全选） | `selected.includes(code)` **或** 任一祖先在 `selected`（`ancestorsOf` 命中） | 该码本身在数组；后代不在（`addCode` 已保证互斥） |
| indeterminate（半选，仅省/市） | 自身与祖先都不在 `selected`，但子树内有后代在 `selected` | 该省/市码不在数组；有 ≥1 个属于它的下级码在 |
| unchecked | 自身/祖先不在，子树也无后代在 | 无条目 |

- **决策**：新增纯函数，签名建议——
  - `checkStateOf(index, selected, code): 'checked' | 'indeterminate' | 'unchecked'`
  - `leafCountOf(index, code): number`（子树内叶子=无子节点者总数；**可对 index 预计算一张 `Map<code,number>` 缓存**）
  - `selectedLeafCountOf(index, selected, code): number`（已选叶子数，驱动「12/21」）
  - `defaultExpandedOf(index, selected): Set<string>`（已选码的祖先链，供编辑回读自动展开）
  - `toggleNode(index, selected, code): string[]`（迁移现 `DistrictCascader.toggle` 的 covered/full 早退 + `addCode`/`removeCode`）
- **正确性红线（必须单测钉死）**：`checkStateOf` **必须先短路判断自身/最近祖先是否在 `selected`，命中即 `checked`、不下探子节点**。绝不能写成「数已选子节点 / 子节点总数」——因为勾省只存省码，若字典日后新增一个区，「数比例」写法会把该省从 checked 误翻成 indeterminate；短路写法天然免疫字典漂移。（测试维度实证此为最大风险点。）
- **不改**：`addCode`/`removeCode`/`ancestorsOf`/`childrenOf`/`parseCodes`/`toCsv`/`budgetOf` 全部原样；新增的都是**只读派生函数**。回读路径（`EditorView.vue:464` 不经 `addCode` 净化）可能有祖先+后代同存或未知码，新函数必须对未净化数组也给确定结果（复用 `guard++<8` 防环）。

---

## R4 · 已选核对与「未知码」落位：升级为卡片化已选区（不是删掉 chips）

- **强约束**：纯树**无法**承载「已选但字典查不到」的已撤销码（如 `500105`）——它在 index 里没有节点。所以已选清单**不能整块砍掉**（UIUX 维度实证）。
- **决策**：
  - **主核对面 = 树本身**（inline checkbox / 半选 / 省市分数徽标）+ 顶部「已选 N/146」计数（`district-count` 保留）+ 「只看已选」过滤开关（**纯渲染过滤、不 mutate `expanded`**，只渲染有选中的分支，直接回应痛点④）+「折叠全部」安全阀（一键回 34 省折叠态）。
  - **保留完整、结构化的已选清单（用户 2026-08-13 批准调整①，覆盖原「降级为兜底」方案）**：不是只列未知码，而是**按省分组**列出全部已选（`广东省(12) › 深圳市 · 珠海市…`），可移除、可折叠；**未知/已撤销码**单列（分组外「未知代码」小节）用 `Badge kind="warn" shape="triangle"`（复用 `DistrictPicker.vue:103`）。用「结构化」而非「删除」解痛点④（chips 堆一片）。`district-chips`/`district-chip-x-{code}`/`district-chips-more` 保留。
  - 卡片化：已选清单与树用同一 `--bg-elev/--border/--radius-lg` 卡片，消除现状 `.chips` 无边框造成的「零碎感」（痛点②）。
- 备选（未采纳）：① 完全删 chips 只靠树——被「未知码无处可去」否决；② 已选清单降级为仅异常兜底区——用户明确要保留完整清单，改为结构化保留。

---

## R5 · 搜索：树内就地过滤（用户 2026-08-13 批准调整②，覆盖原「平铺列表 + 定位」方案）

- **决策**：搜索不再另起平铺列表，而是**在树内就地过滤**——`q` 非空时树只渲染 `search()` 命中节点 ∪ 其 `ancestorsOf` 祖先链，命中分支**强制展开**、命中名 `<mark>` 高亮；命中仍是 `district-opt-{code}` 树节点身份（不新增 `district-hit-{code}`）。
- **炸开风险的化解**：`search()` 保留 `limit`（50~100）截断——最多 `limit` 个命中 × 各 ≤3 祖先 ≈ ≤300 节点入 DOM，有界、不卡；超限给 `district-search-trunc` 提示。故原「否决树内过滤」的理由（50 命中横跨 30+ 省会炸开）在**有 limit + 只渲命中及祖先（不渲命中的整棵子树）**下不成立。
- **与「只看已选」**：搜索优先，`q` 非空时忽略「只看已选」（展示全部命中），两个过滤器不叠加。「定位」动作取消（过滤已直接定位）。
- **契约影响**：`district-hit-{code}`/`district-locate-{code}` 不再需要；`district-search-trunc` 保留；**`EditorView.test.ts:741`「搜索态 `district-opt-*` 不在 DOM」旧断言必须改写**为「搜索态只剩命中的 `district-opt-*`」。
- 依赖：`drill:ref<string[]>`（单路径，miller 专用）**替换为** `expanded:ref<Set<string>>`（树需多分支同时展开）。

---

## R6 · testid 与 e2e 契约迁移（闸门盲区，必须显式处理）

- **保留（DistrictPicker 自身 DOM，挂载契约不变则零改动）**：`district-picker/toggle/count/clear/warning/limit/unknown/chips/chip-x-{code}/empty-hint/raw/search/search-clear`。
- **保留并挂到树节点**：`district-opt-{code}`（勾选框）——4 个 e2e（`e2e-visual-guard:91`/`e2e-phone-smoke:41`/`e2e-tablet-smoke:34`/`e2e-dev-v2:70`）都 `waitForSelector('[data-testid="district-opt-440000"]')` 作「字典渲染完成」信号，且 `e2e-dev-v2:71-74` 断言 `count()===34`。广东 `440000` 是省根、折叠态也在 DOM，**沿用此名 = 4 个 e2e 零改选择器**。
- **硬约束（否则 e2e 静默失效）**：树展开必须 `v-if` **条件挂载**子节点，**不能**用 `v-show`/`display:none` 纯 CSS 折叠——Playwright `.count()` 不过滤可见性，CSS 折叠会让 `count()` 从 34 变成远大于 34 而**静默失败**。
- **搜索命中项**：树内过滤后命中即 `district-opt-{code}` 树节点，**不再有** `district-hit-{code}`；`district-search-trunc` 保留（截断提示）。
- **新增**：`district-expand-{code}`（展开三角按钮，替代旧 `district-into-{code}` 的角色但语义不同）；`district-selected-only`（只看已选开关）；`district-collapse-all`（折叠全部）。
- **删除**：`district-into-{code}`、`district-crumb-root`、`district-crumb-{code}`（miller 下钻/面包屑特有）、`district-hit-{code}`（无独立命中列表）。
- **同步更新**：`frontend/e2e/data-testid-contract.md` 的 `2026-08 投放地域选择器` 段（新增一小节记本轮变更）；`e2e-tablet-smoke.mjs` 的「三栏没塌？」注释措辞改为树语义；`e2e-dev-v2.mjs` 若断言语义随之调整需同改。

---

## R7 · 响应式：树是净简化（沿用正典四档，删掉 miller 的分支）

- 断点正典四档 `tokens.css:31-35`（`≥1024`/`768-1023`/`<768`/`≤560`）。
- **决策**：树在三个断点用**同一套纵向单栏**渲染——**删掉** miller 的 `<768` 塌单栏 + `768` 三栏 + 面包屑状态机（`DistrictCascader.vue:207-212`）。缩进 = `padding-left: calc(var(--sp-4) * var(--depth))`，`--depth` 用**实际祖先链长度**（`ancestorsOf().length`）而非 `level`（117 个 level=3 直挂省级，用 level 会多缩一级）。
- 横向溢出防线退化成一条通用规则：容器 `min-width:0` + 名字 `ellipsis` + 缩进步进 16px（最深 2 级=32px，390px 下可用宽 ≈310px，安全）。
- 滚动：单一容器 `max-height: min(50dvh, 480px); overflow-y:auto`（`dvh` 是本仓既定约定）。树展开只增纵向内容（触发滚动），不增横向宽度（不触发溢出）——相对 miller 的又一简化。
- 触控：`(pointer:coarse)` 全局兜底**排除 checkbox/radio**（`tokens.css:382`）且压不过 scoped，故树节点行/展开三角/搜索清空都要**逐一自补** `min-height:44px`；checkbox 命中区靠整行 `<label>` 撑 44px（沿用 `DistrictCascader.vue:186-193` 模式）。
- iOS：搜索框 `font-size:var(--fs-lg)`(16px) 沿用，`district-search` testid 保留（`e2e-visual-guard:112` 硬编码查它）。

---

## R8 · 保持内联展开（不改 SidePanel）、保持 markDirty 防线

- 沿用 `DistrictPicker.vue:5-15` 的既定理由（SidePanel push 阈值 1280 装不下、sheet 盖住 submit 让 e2e 超时）——与「三栏还是树」正交，继续成立。
- `codes:string[]` v-model 契约、`EditorView.vue:130-133` 的 `districtCodes` setter 显式 `markDirty()`、`DistrictPicker.vue:73` 根节点 `@click.stop @input.stop` **全部不动**。新树组件的所有交互元素**必须仍在 DistrictPicker 根 DOM 子树内**，否则绕开防线会重新触发 `EditorView.vue:359-362` 的 `onFormClick/markDirty`。
- `EditorView.vue:814-821` 的 `:deep(.fg > label)` 直接子代隔离：新组件**不得**把裸 `<input>/<select>/<textarea>` 作为 `<label class="full">` 的直接子元素（否则被压成竖排）。

---

## 待用户在批准时确认的假设（本计划已给默认值，可调）

1. **已选核对形态**：默认「树 inline 态 + 已选计数 + 只看已选过滤 + 未知码单列」，已选清单降级为兜底/异常区（不再是大片 chips）。若你更想保留显眼的完整已选清单，可调。
2. **搜索**：默认「保留平铺列表 + 高亮 + 定位到树」。若你想要「搜索即在树内过滤」，需接受它在多省命中时会展开较多分支。
3. **默认展开**：默认「折叠（34 省），编辑回读时自动展开到已选路径」。新建活动全折叠。
4. **分数徽标口径**：`12/21` = 该省/市下**已选叶子（区县）数 / 叶子总数**；**只在半选（indeterminate）时显示**，全选/零选都不显示（评审一致性修正）。已知边缘：逐个勾满一省全部子级（未合并成省码）→ 半选 + `N/N` 并存，是「不做自动归并」的必然表现，非 bug。
5. **是否顺带补既有测试债**：`DistrictPicker` 的 `warning/clear/chips-more/limit/empty-hint` 5 个 testid 现零测试覆盖——计划建议顺带补 `DistrictPicker.test.ts`。

## 非目标（继承 + 本轮新增）

- 不改 `district_ids` 列类型/CSV 存储/146 上限；不改后端 `mergeDistrictCondition`/`expandWithDescendants`/`stripDistrictNodes`；不改 `/districts` 接口与 `listDistricts` 全量拉取策略（D2 的三条理由仍成立）。
- 不实现「排除项」语义（祖先已选时不放开子节点单独取消）。
- 不实现「手选满一省全部子级自动折叠成省码」的自动归并（行为变更，非平移）。
- 不引入虚拟滚动、不引入新依赖、不做已撤销码迁移、不做懒加载字典。
- 不引入 ARIA `role=tree`（改用原生嵌套语义）。
