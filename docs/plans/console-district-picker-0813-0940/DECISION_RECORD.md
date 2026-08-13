# 决策记录 · 控制台地域选择器（0813-0940）

> 输入：六路并行只读调查（需求与用户流 / UIUX 与交互状态 / 前端架构 / 后端接口与仓库约束 / 移动端响应式 / 测试风险边界）。
> 凡标「**实测**」的数字都是本轮亲自跑出来的，不是外推；凡标「**推翻**」的是子代理结论被证伪的地方。

---

## D1（最关键）· 这次到底在做什么：`districtIds` 是个死字段

### 事实

全仓生产代码对 `districtIds` / `activityAreaType` 的引用只有 23 行，**全部**是定义、装配、透传 getter：

| 环节 | 位置 |
| --- | --- |
| 写入 | `ActivityMarketingService.java:755-756` |
| 装配进候选 | `OfferSpec.java:64` |
| 透传 getter（**零生产调用方**） | `ActivityCandidate.java:135,137` |
| 终点 | `DecisionSnapshot` / `DecisionDataLoader` 把它搬到底 |

`activity-common` 的 `service/`、`engine/`、`snapshot/` 三个包对这两个字段名 **grep 结果为空**。活动侧没有任何 `.drl` 引用它。也就是说：**配置进得去、出不来，没有任何一处比较或判定读它**。

这不是新发现。仓库自己的审计 `docs/plans/activity-chain-review-0811-1730/REVIEW.md` 编号 **B2「地域假开关」** 早已记录，并给出不容含糊的处置：

> 保存时把 `areaType=2 + districtIds` 翻译成 `userDistrictId IN (...)` 与用户条件树做 AND，落进同一份 `condition_tree_json`——表单字段立刻变真，决策侧一行不用改。不做翻译就必须删控件 + 并进 `declarativeOnlyWarnings`。**绝不能维持现状。**

对照组是**库存**：同样是声明式字段，但它三处都标了——UI `class="declarative"` + `disabled` + title（`EditorView.vue:595-600`）、`declarativeOnlyWarnings` 回执（`ActivityMarketingService.java:945-953`）、`docs/activity-marketing.md` 已知落差条目。**地域一处都没有**，还在 `DetailView.vue:272` 当生效配置回显。

真正生效的地域定向走的是**另一条完全独立的路**：条件树字段 `userDistrictId`（`RuleSchemaRegistry.java:110`，STRING，允许 EQ/IN/NOT_IN），由 `DecisionEligibilityService.java:62` 从请求填进属性袋。`playbooks.ts:168` 的「地域定向立减」模板走的正是它。

### 备选方案

| 方案 | 做什么 | 代价 | 后果 |
| --- | --- | --- | --- |
| **A · 只做录入体验** | 级联多选 + 读接口；同步把地域并进 `declarativeOnlyWarnings`、UI 加 `.declarative` 标记、补进文档已知落差 | 最小 | 诚实，但**运营点完省市区，活动依然不按地域投放**。等于把假开关做得更精致 |
| **B · 翻译成条件树（推荐）** | A 的全部 + 保存时把选中地域展开成叶子码，合成 `userDistrictId IN (...)` 叶子，与运营自己的条件树做 AND 落进同一份 `condition_tree_json` | 后端 +1 个翻译方法、+1 个展开工具；决策侧**零改动** | 表单字段**立刻变真**，且复用了已经过 `ActivityEligibilityGuardTest` fail-closed 验证的那条链路 |
| **C · 删控件** | 删掉地域类型与地域 IDs 两个控件 | 最小 | 与用户本次诉求直接冲突 |

### 推荐：**B**

三条理由：

1. **它是唯一同时满足「用户要的下拉多选」与「审计要求不得维持现状」的路径。** A 会造出一个更可信的假开关——运营点着省市区一路选完、详情页回显得漂漂亮亮，发钱时一个都不生效，这比现在的裸输入框**更危险**。
2. **技术代价确实小**：`condition_tree_json` 是 `text`（64 KB），装 122 个码的 IN 列表约 1 KB，绰绰有余；`ConditionNode` 本身就支持 AND 分组 + children，合成一个叶子是几十行。决策侧一行不改。
3. **它顺带解掉了 D4 的语义死结**（见下）：`district_ids` 保持人类可读的「所选层级码」，展开只发生在条件树那一份，两边各自最优。

⚠ **B 的边界**：翻译只在**保存时**发生。运营若绕过控制台直接改库里的 `district_ids`，不会自动重译。这条要写进注释，别让下一个人以为它是活的。

---

## D2 · 接口形态：全量下发 vs 按父级懒加载

### 冲突与裁决

- 「后端接口」那路主张**懒加载**，核心论据是「`nginx.conf` 有 `gzip on` 但没有 `gzip_proxied`，默认 off，所以反代出去的 JSON 是裸传 0.7 MB」。
- 「前端架构」那路主张**全量**，并给出 gzip 后的体积。

**这条论据已被实测推翻。** `gzip_proxied` 管的是「**请求**带 `Via` 头（客户端自己在代理后面）时要不要压」，不是「nginx 自己反代的响应」。实打：

```
GET http://localhost:8095/api/console/list           → 200, 43968 bytes 裸传
同一请求带 Accept-Encoding: gzip                      → Content-Encoding: gzip ✓
```

**实测 JSON 体积**（从随包 CSV 生成，gzip -9）：

| 形态 | raw | gzip |
| --- | ---: | ---: |
| 全 11 列 | 799.1 KB | 87.4 KB |
| 裁 4 列（code/name/level/parent） | 206.9 KB | 27.9 KB |
| **4 列 + 拼音（推荐）** | 285.4 KB | **48.9 KB** |

### 裁决：**全量下发，裁列 + 带拼音**

1. 48.9 KB gzip 相当于一张中等图片，一次请求全生命周期复用。
2. **回显是懒加载的死穴**：编辑态从 `districtIds` 拿到的是一串裸码，要显示「广东省/深圳市/南山区」，懒加载必须为每个码逐级反查祖先（最坏 N 次往返）；全量方案一个 Map 直接查。
3. **搜索是这个组件的核心价值**。`DistrictEntity` 的 `pinyin` 列注释白纸黑字写着「给前端做拼音搜索用」，`short_name` 写着「前端做级联选择器时列表更短，搜索也更容易命中」——**表设计时就是按全量+前端索引设计的**。后端 LIKE 查 `pinyin` 没有索引。
4. 本项目前端**没有任何处理异步级联竞态的基础设施**（无 SWR/vue-query，连 `useDictStore` 都不传 signal）。懒加载要新引入防抖/竞态/分级 loading 三套模式。

保留 `?parent=` 与 `?level=` 两个查询参数作为**备用**（仓库方法现成），但前端默认走全量。

---

## D3 · 交互模式

四种模式在「本项目零组件库、全手写」前提下的对比：

| 模式 | 手写代价 | 关键短板 |
| --- | --- | --- |
| antd 式 Cascader multiple | **最高**——要自造 combobox 语义（`aria-activedescendant` / `role=listbox+option`），全仓**零先例** | 窄下拉框塞不下「已选」，仍要另开 chips 区 |
| 左右穿梭框 Transfer | 中 | **丢掉层级**，3212 条平铺，「把整个广东加进来」做不到 |
| **三栏联动 + 已选 chips（推荐）** | **中偏低**——每一块都有现成先例 | 屏幕占地大，<768 需退化 |
| 树形勾选 Tree | 高——递归组件 + roving tabindex + 虚拟滚动 | 34 省全展开 3212 行，不做虚拟滚动就卡 |

### 推荐：**三栏联动 + 已选 chips 区，装在 `SidePanel` 里**

- 每一块都能在本仓找到先例：`.chip`（`EditorView.vue:772`）、`EmptyState`、`Skeleton`、checkbox `:indeterminate`（`ListView.vue:449-453`）、搜索框（`ListView.vue:676-680`）。
- `SidePanel.vue` 已经把焦点陷阱、Esc、滚动锁（引用计数）、三档响应式写完了，**不要重造**。
- 搜索作为第二入口横跨三栏，补上三栏模式「找一个偏远县要点三次」的短板。

---

## D4 · `district_ids` 落库语义：存所选层级码，**不展开**

- 选「广东省」就存 `440000` 一个码，**不展开**成 122 个区县码。
- 理由：**实测**广东省 122 个区县展开后是 853 字符，而 `district_ids` 是 `varchar(1024)`——**选两个省就必爆**。
- 展开只发生在 D1-B 的条件树翻译那一份（`condition_tree_json` 是 `text`，64 KB 装得下）。
- 这条约定今天没有消费方，**必须现在写死并落成注释 + 守卫用例**，否则会重蹈 `01-requirements.md:59`「districtIds 粒度待验证」三年未决的覆辙。

---

## D5 · 上限与校验：146 个，前端硬闸 + 后端 400

- **实测**：6 位码 + 逗号 = 7 字符，`7n - 1 ≤ 1024` → **n ≤ 146**。
- 现状：`validateCommon` 对 `activityName`（≤128）、`bizLine`（≤64）都有长度前置校验，**唯独漏了 `district_ids`**；超限时 `saveAndFlush` 抛 `DataIntegrityViolationException`，被为 requestId 唯一约束写的 catch 放过 → **HTTP 500**。
- 同一个坑仓库已认领并修过两次（注释里的 ISSUE-06），这是漏网的第三个。
- 处置：后端补长度前置校验 → 400；前端选满即禁选 + 明确提示。**不改列类型**（`varchar(1024)` → `TEXT` 是 schema 变更，回滚成本从「删一个方法」跳到「改列」，不值得）。

---

## D6 · 未知 / 已撤销代码：保留 + 标记，**绝不静默丢弃**

- 字典里 `500105`（江北区）、`500112`（渝北区）**确定不存在**（2025-11 撤销，民政部废止，`DistrictSeederTest` 双向金丝雀钉死）。
- 库里存量活动的 `district_ids` 是自由文本，完全可能含这两个码或任意乱输入。
- **最危险的实现**是「渲染时按字典过滤」：运营打开编辑器→保存一次，丢掉的码永久没了，全链路不报错。
- 处置：未知码原样保留、chip 上标 `Badge kind="warn" shape="triangle"` + 「该代码不在当前字典中，可能已撤销」，**不阻断保存**。用一条「不做任何编辑直接提交，`districtIds` 一字不差」的用例钉死。

---

## D7 · 响应式：统一走 `SidePanel` 的 sheet 档，不新造断点

- 断点正典（`tokens.css:31-35`）四档：≥1024 / 768–1023 / <768 / ≤560，且明写「新写样式一律只用正典四档」。
- `SidePanel` 的模态阈值是 **1024**（<1024 全屏 sheet）。平板档也走 sheet：**单一形态、零新断点**。若为平板保留内联三栏，就要在组件里再判一次 767，与 SidePanel 的 1024 契约叠加，会出现「SidePanel 认为自己是模态、页面却在内联渲染」的状态错位。
- <768 三栏解体为**面包屑式下钻**（点省 → 列表整体换成该省的下级 → 顶部面包屑回退），单列 342px 足够。

---

## D8 · 状态与数据流

- 新建 `stores/useDistrictStore.ts`，照 `useDictStore.ts` 的 cache + load + clear 三件套，但**补上它的两个已知缺口**：透传 `AbortSignal`、in-flight Promise 去重。
- 字典由 EditorView 取好当 **prop** 下发给组件（照 `condition-tree` 的既有做法），组件不自己调 store —— 可单测、可被 `ValidateView` / `ValueControl` 复用。

---

## D9 · v-model 契约：组件内 `string[]`，CSV 转换留在 EditorView 边界

- `Draft.districtIds` **保持 `string`**（CSV），`:61 / :81 / :429 / :487` 四个触点一个都不动。
- EditorView 加一个 computed 桥（照抄 `ValueControl.vue:25-28` 的 `listStr` 范式），`set` 里**显式调 `markDirty()`**。
- **回滚面因此只有一行**：`EditorView.vue:607` 那个 `<label>`。若改成 `string[]`，回滚面 ×5，且 `:487` 的提交映射是契约用例守着的高风险区。

### ⚠ Teleport 陷阱

`EditorView.vue:582` 的脏值追踪靠 `@input` / `@click` **事件冒泡**。新组件若把面板 `<Teleport>` 到 body，就脱离了冒泡域 → **改了地域但保存键不认为脏**，`onBeforeRouteLeave` 守卫漏掉这次编辑。要么不 Teleport，要么显式 `markDirty()`（本方案两者都做）。

---

## D10 · 测试爆破面：先改 mock 再动组件

`EditorView.test.ts` 的三个 fetch helper 都是「按 URL 分派、其余兜底」：`dictOk()` 对**任何 URL** 返回 FieldDict、`captureCreates()` 其余返回 **404**、`backendReturns()` 其余返回 **detail JSON**。新的 `/districts` 请求会被各喂一种错形状，约 72 个用例大面积受影响。

处置两条：① 先给三个 helper 加 `/districts` 分支；② **只在 `areaType===2` 时才发请求**，把爆破面压到近零，同时保住六条经过编辑页的 e2e（它们都只填 name/amount/spu，areaType 保持默认 1）。
