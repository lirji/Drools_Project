# 实施计划 · 控制台地域级联多选 + 行政区字典读接口（0813-0940）

> 决策依据见同目录 `DECISION_RECORD.md`（D1–D10）。本计划**获批前不改任何代码**。

---

## 1. Goals / Non-goals

### Goals

- G1 「新建/编辑活动」页的**地域类型**从裸 `<select>` 换成 `Segmented`；**投放地域**从「地域IDs (逗号)」裸输入框换成**级联多选选择器**（省 / 地市 / 区县三级 + 搜索）。
- G2 新增 console 侧只读字典接口 `GET /activity-marketing/districts`，一次性下发裁列后的全量行政区（**实测 48.9 KB gzip**）。
- G3 **让地域真的生效**（D1-B）：保存时把选中地域展开成叶子码，合成 `userDistrictId IN (...)` 叶子，与运营自己的条件树 AND 后落进同一份 `condition_tree_json`。决策侧零改动。
- G4 补上 `district_ids` 的长度前置校验（超限从 **500** 变 **400**）。
- G5 详情页 `DetailView` 把裸 CSV 回显换成中文全称（超过 N 个折叠）。

### Non-goals

- **不**改 `activity_manage.district_ids` 的列类型与存储格式（仍是 `varchar(1024)` CSV）。
- **不**在决策链路里新增对 `districtIds` 的读取（那会触碰 `OfferSpecArchGuardTest` 与快照 parity 守卫；G3 走的是已验证的条件树链路）。
- **不**接管条件树 `ValueControl` 里的 `userDistrictId` 值控件（接口预留 `multiple`，留作 follow-up）。
- **不**做「排除项」语义（如「除深圳外的广东」）——现有字段结构表达不了。
- **不**引入任何新 npm 依赖（package.json 现有 3 个 runtime dep 是有意的架构取向）。
- **不**做已撤销代码的「一键迁移到继任代码」（需要撤销→继承映射表，当前数据集没有）。

---

## 2. 视觉方向与设计参考

**沿用既有视觉语言，不引新方向**（仓库已有成熟 token 体系与 chip 语汇，一致性 > 追新）。逐项落到具体 token：

| 元素 | 落法 | 依据 |
| --- | --- | --- |
| 地域类型二选一 | `shared/ui/Segmented.vue`（受控 + `role="group"` + 下划线选中态） | 取代 `EditorView.vue:605` 的原生 `<select>` |
| 触发器 / 搜索框 | `min-height:38px` + `1px solid var(--border-ctl)` + `--radius-sm`；`:focus-within` 换 `--accent` 边 + `--focus-ring` | 与 `.fg` 内其他输入同线（`EditorView.vue:770`）、搜索框抄 `ListView.vue:676-680` |
| 已选 chip | EditorView 自有 `.chip`（pill，`:772`），**不用** Segmented 的 `.chip`（方角+下划线 = 互斥单选语汇，混用会让运营以为点一下是切换） | `EditorView.vue:771-774` |
| 层级标记 | `Badge` `kind`：省=`accent` / 市=`blue` / 区县=`neutral` | `Badge.vue:1-47` |
| 异常态（未知码/超限） | 一律 `warn`/`err` + `shape`（第二编码通道），**禁止用 accent 编码状态** | `tokens.css:13` 是硬规矩 |
| 面板层级 | 走 `SidePanel` → 自动 `--z-panel: 880`，不新造 z 值 | `tokens.css:217-224` |
| 动效 | **不加** `.u-stagger` / `.u-pulse` | `effects.css:88` 与 `:96-97` 双重禁令，编辑页在点名列表里 |

---

## 3. 路由与页面流

**无新增路由。** 组件挂在既有 `activity-new` / `activity-edit` 两条路由指向的 `EditorView` 内。

```
/ui/console/activities/new  ─┐
/ui/console/activities/:id/edit ─┴→ EditorView
   └ Section「基础信息」
       └ 地域类型 [全国 | 指定地域]        ← Segmented
       └ v-if areaType===2 · 投放地域       ← DistrictPicker（.full 跨两列）
            └ 收起态：已选摘要 + 前 N 个 chip + 「+87」
            └ 点击 →  SidePanel
                 ├ head : 搜索框（sticky）
                 ├ body : ≥1024 三栏联动 | <1024 面包屑下钻
                 └ foot : 已选 N/146 · 清空 · 完成
```

---

## 4. 组件树

| 文件 | 新建/复用 | 职责 |
| --- | --- | --- |
| `console/district/districtLogic.ts` | **新建** | 纯函数：CSV↔`string[]`、建索引、搜索（名称/简称/拼音/首字母）、祖先链、省市全选与半选推导、长度预算、**叶子展开**（供 G3 用） |
| `console/district/DistrictPicker.vue` | **新建** | 入口件：收起态摘要 + 已选 chips + 打开面板 |
| `console/district/DistrictCascader.vue` | **新建** | 面板内容：搜索 + 三栏/下钻 + 已选区 |
| `shared/ui/SidePanel.vue` | **复用** | 容器（焦点陷阱 / Esc / 滚动锁 / 三档响应式全都现成） |
| `shared/ui/{Segmented,Badge,Banner,EmptyState,Skeleton,Button,Icon}.vue` | **复用** | 见 §2 |
| `stores/useDistrictStore.ts` | **新建** | cache + `load(signal)` + `clear()`，照 `useDictStore` 但补 signal 透传与 in-flight 去重 |
| `console/activityApi.ts` | **改** | `+listDistricts(signal?)` |
| `shared/types.ts` | **改** | `+interface District` |

目录约定照 `console/condition-tree/` 与 `console/benefit/` 的既有先例。**三级是固定的、不递归**，不学 `ConditionGroup` 的自引用。

---

## 5. 状态与边界

| 状态 | 表现 | 复用先例 |
| --- | --- | --- |
| loading（字典首拉） | 触发器仍可点、`aria-busy`；面板内三栏各挂 `<Skeleton :rows="8" />`；**已选 chips 照常渲染**（先显示裸码，字典到达后就地换名） | `Skeleton.vue:5`、`EditorView.vue:556` |
| empty（搜索无结果） | `<EmptyState icon="search" title="没有匹配的行政区" hint="试试简称或拼音，如「南山」「nanshan」" />` + 保留搜索词 + 清空按钮 | `EmptyState.vue:12`、`ListView.vue:384-389` |
| empty（一个都没选） | 触发器显示「未选择（等同不投放）」，且**进 `validationErrs`** | 与 `EditorView.vue:181-183` 拦「加价购一个都没配」同构 |
| error（接口失败） | 逐字复刻字典降级样板：`Banner kind="warn"` + 重试按钮 + **保留裸 CSV 输入作为逃生门**；已选码原样保留提交，**绝不因读不到字典就置空** | `EditorView.vue:561-566`、`:645-648` |
| 部分选中 | 省/市三态：全选 / `indeterminate` / 未选；省级 chip 显示 `广东省 12/21` 分数 | `ListView.vue:444-453` |
| 超出上限（146） | ① 顶部 `Banner warn`「已选 146/146」② 未选项 checkbox `disabled` + 行文字降 `--text-faint` ③「全选本省」不静默截断，toast 说明只加入了前 N 个 | `Banner.vue`、`useToast.ts` |
| 未知 / 已撤销码 | chip 变 `Badge kind="warn" shape="triangle"` + `500105（未知代码）`，title 写全原因；字段下一条 warn 说明（**不阻断保存**） | `TierRuler.vue:116-120` 的同款立意 |
| 禁用（areaType=1） | 维持 `v-if` 隐藏；**草稿里的 districtIds 不清空**（现状 `:487` 仅提交时置 null，是对的）；旁边一行 hint「已保存的 N 个地域会保留，切回「指定地域」即可恢复」 | — |
| 字典空表（seeder 未开） | 与「接口失败」同款降级，但文案区分「字典未就绪」与「真的没有」 | `EditorView.vue:561` |

---

## 6. API 契约

```
GET /activity-marketing/districts
    无参 → 全量（推荐，前端默认走这条）
    ?level=1|2|3        可选，按层级取
    ?parent=440000      可选，取下级；与 level 同传时 parent 优先
  200 → District[]
  400 → ActivityErrorBody { error, code }   （level 非法 / parent 非 6 位数字）

interface District {
  code:   string   // 6 位
  name:   string   // 「南山区」
  level:  1 | 2 | 3
  parent: string | null
  py:     string   // 全拼无空格，搜索用
  pyi:    string   // 首字母
}
```

- **不传** `shortName` / `provinceCode` / `cityCode` / `fullName` / `sortNo`：前三个前端可由 `parent` 链推出，`fullName` 同理，`sortNo` 已体现为数组顺序（仓库方法都是 `OrderBySortNoAsc`）——传出去等于让前端有机会二次排序、把服务端的权威顺序变成可选项。
- 空表返回 `200 []`，**不是 500**（测试环境 seeder 默认关，这是常态不是故障）。
- 分层：新建 `activity-console/.../service/DistrictQueryService.java`，照 `GenerationService` 模板（`@Service` + 构造器注入 repository + 只读方法不加 `@Transactional`）。**不在 controller 直接注入 repository**——controller 目前持有 0 个 repository，别开这个口子。
- **不返回 `DistrictEntity` 裸实体**（`/list` 返回裸实体正是 `docs/architecture.md:491-496` 记载的键序事故来源），用 `DistrictView` record。
- 鉴权：落在 `/activity-marketing/**` 之下 → `ActivityResourceServerConfig:52` 的 `securityMatcher` 通配自动覆盖；`console-write-authority` 只管 POST，GET 只需 authenticated。`TenantContextFilter` 的 URL 模式**前缀不变、无需扩**（坑 9 说的是新增路径前缀）。
- 网关与 vite proxy **零改动**（`activity-marketing` 已在 `API_PREFIXES` 与 `BASES.marketing` 里，避开坑 18）。

---

## 7. 响应式与移动端适配

### 断点表（正典四档，不新造）

| 档 | 媒体查询 | 组件形态 |
| --- | --- | --- |
| ≥1024 桌面 | 默认 | `SidePanel` overlay/push；**三栏联动**并排；已选区常驻底部 |
| 768–1023 平板 | `max-width:1023px` | `SidePanel` **全屏 sheet**（组件自身阈值，不新造）；仍三栏，每栏 `minmax(0,1fr)`，**禁止 `min-width:200px` 这类硬值**（溢出源） |
| <768 手机 | `max-width:767px` | 三栏**解体为面包屑下钻**：点省→列表整体换成下级→顶部面包屑回退；单列 |
| ≤560 | `max-width:560px` | 同上；chip 字号降 `--fs-2xs`；已选区默认折叠只显示计数 |
| 正交 · 触控 | `(pointer: coarse)` | 列表行 / chip 删除 × / 面包屑 / 触发器**各自**补 `min-height: var(--touch-min)` |

### 必须遵守的三条

1. **`(pointer: coarse)` 全局兜底压不住组件 scoped 样式**（`tokens.css:373-378` 明确警告：`button` 是 0-0-1，scoped 的 `.x` 带 `[data-v-*]` 至少 0-2-0）。凡自写 `min-height` 的元素必须各自补一条，照抄 `Segmented.vue:44` 的一行式。
2. **搜索框 `font-size` ≥16px**（用 `--fs-lg`）。否则 iOS Safari 聚焦自动放大 → 横向滚动 → 直接撞 `e2e-tablet-smoke.mjs:25` 的溢出断言。
3. **`:hover` 高亮必须包 `@media (hover: hover)`**。否则触屏上点过的项会粘住高亮，在多选场景里会被读成「已选中」——这是语义 bug 不只是观感。

---

## 8. 文件级改动清单

### 后端（activity-console / activity-common）

| 文件 | 改动 |
| --- | --- |
| `activity-common/.../domain/DistrictView.java` | **新建** record |
| `activity-console/.../service/DistrictQueryService.java` | **新建**：全量/按层级/按父级 + entity→view 映射 |
| `activity-console/.../controller/ActivityMarketingController.java` | `+@GetMapping("/districts")`；类注释的端点清单补一行（该文件既有纪律） |
| `activity-console/.../service/ActivityMarketingService.java` | ① `validateCommon` 补 `districtIds` 长度校验（>1024 → `IllegalArgumentException`）② **G3 翻译**：`areaType=2` 时把 `districtIds` 展开成叶子码，合成 `userDistrictId IN (...)` 叶子与用户条件树 AND，落进 `saveCondition` |
| `activity-console/.../DistrictSeeder.java` | 修注释里陈旧的「3595 行」→ 3212（上一轮换数据源后的遗留） |

### 前端

| 文件 | 改动 |
| --- | --- |
| `console/district/districtLogic.ts` + `.test.ts` | **新建** |
| `console/district/DistrictPicker.vue` | **新建** |
| `console/district/DistrictCascader.vue` + `.test.ts` | **新建** |
| `stores/useDistrictStore.ts` | **新建** |
| `shared/types.ts` | `+interface District` |
| `console/activityApi.ts` | `+listDistricts` |
| `console/pages/EditorView.vue` | `:604-607` 替换（Segmented + DistrictPicker）；`initialize()` 并行 load 字典；`validationErrs` 补两条（空选 / 超限）；加 `districtCodes` computed 桥 |
| `console/pages/EditorView.test.ts` | **先改三个 fetch helper 加 `/districts` 分支**，再加用例 |
| `console/pages/DetailView.vue` | `:272` 裸 CSV → 中文全称（G5） |
| `e2e/data-testid-contract.md` | 登记新 testid（该文件是契约活文档） |
| `e2e/e2e-visual-guard.mjs` | 新增编辑页 390px 段（44px 命中区 + 零横向溢出） |
| `e2e/e2e-phone-smoke.mjs` | 编辑器打开级联后补一次溢出断言（当前只在列表页量） |
| `e2e/e2e-dev-v2.mjs` | 建活动时切「指定地域」→ 选省市区 → 提交 → 详情页断言 |

---

## 9. 实施步骤（按依赖排序）

1. **后端字典接口**：`DistrictView` → `DistrictQueryService` → controller 端点 → 两个 `@SpringBootTest`（开 seeder 的形状测试 + 不开 seeder 的空表降级测试）。
2. **后端长度校验**（G4）：`validateCommon` + 边界用例（146 → 200 / 147 → 400）。独立于 1，可并行。
3. **前端类型与数据层**：`types.ts` → `activityApi.ts` → `useDistrictStore.ts`。
4. **纯逻辑层**：`districtLogic.ts` + 单测（CSV 互逆、树构建含 117 个直挂省级、长度预算、未知码保留、叶子展开）。**先于组件完成**——它承载了本次全部易错语义。
5. **组件**：`DistrictCascader.vue` → `DistrictPicker.vue` + 组件测试。
6. **接进 EditorView**：**先改 `EditorView.test.ts` 的三个 fetch helper**，再改页面，再加集成用例。
7. **G3 条件树翻译**（后端）：展开工具 + `saveCondition` 合成 + 用例（选省 → 生成的 `condition_tree_json` 含该省全部叶子码且与用户条件 AND）。
8. **G5 详情页回显**。
9. **e2e**：`data-testid-contract.md` 登记 → visual-guard 编辑页段 → phone-smoke 溢出断言 → dev-v2 全链路。
10. **文档**：`docs/architecture.md` §7 补接口与语义约定；`docs/activity-marketing.md` 补地域章节。

---

## 10. 测试策略

| 层 | 在哪 | 测什么 |
| --- | --- | --- |
| 纯逻辑单测 | `districtLogic.test.ts` | CSV↔数组互逆（空串/多余逗号/**顺序不擅自排序**）；146/147 长度预算；树构建对 117 个 `cityCode=null` 不产生孤儿；未知码保留；广东省展开 = 122 个叶子 |
| 组件测试 | `DistrictCascader.test.ts` | 选省 emit `'440000'` **而不是** 122 个码；选满 146 后第 147 个 `disabled`；`modelValue='500105,440305'` 时 500105 可见且带标记、不操作时 emit 值与传入**字符串完全相等**；空字典渲染 `district-empty`；**真实 3212 行只跑一条**「不崩且省级恰 34 项」，其余用 ≤10 行夹具 |
| 页面集成 | `EditorView.test.ts` | areaType=1 时**不发** `/districts` 请求；提交映射不变；**回读保真**（含 500105 不编辑直接提交，body 一字不差）；字典 500 时 areaType=1 不误伤保存；147 个码拦在保存前 |
| 后端集成 | `DistrictDictEndpointTest`（新）+ `ActivityMarketingEdgeTest` | 省级恰 34；`?parent=440000` 含 440300；空表 → `200 []`；147 码 → **400 而不是 500**；`districtIds='500105'` → 200（写平面刻意不校验字典存在性，否则历史活动改不动） |
| G3 翻译 | 后端集成 | 选 `440000` → `condition_tree_json` 里出现 `userDistrictId IN (122 个叶子码)` 且与用户原条件 AND；areaType=1 时不注入 |
| e2e 桌面 | `e2e-dev-v2.mjs` | 建活动全链路 + 详情回显中文 + 编辑回显选中态 |
| **e2e 移动端 390** | `e2e-visual-guard.mjs`（新增段） | 编辑页切「指定地域」→ 展开级联 → ① 全页 `button/[role=button]` 高度 **≥44px** ② `scrollWidth - clientWidth ≤ 4` |
| **e2e 移动端 390** | `e2e-phone-smoke.mjs` | 级联展开状态下的横向溢出（当前缺口：只在列表页量） |
| e2e 平板 768 | `e2e-tablet-smoke.mjs` | 已有溢出断言位置正确，只需在量之前先切「指定地域」并展开 |

**不动 `e2e-validation.mjs`**：它的 region 场景走 `userDistrictId` 条件树，且经 API 建活动传 `districtIds: null`，与本次无交集。

---

## 11. 验收标准

1. 运营在「指定地域」下能通过省/市/区县三级点选或搜索（中文/简称/拼音/首字母）选中多个地域，已选以可删 chip 呈现。
2. 保存后重新打开编辑器，选中态**逐码回显一致**；详情页显示中文全称而非裸码。
3. **G3**：选了地域的活动，其 `condition_tree_json` 含 `userDistrictId IN (...)` 且与运营原条件 AND；决策请求带该地域的 `userDistrictId` 命中、带别的不命中。
4. 选到 146 个后无法再选，提示明确；后端对 147 个码返回 **400 + 可读中文**（不是 500）。
5. 含已撤销码（`500105`）的存量活动，打开编辑器→不做任何修改→保存，`districtIds` **一字不差**。
6. **移动端（390×844）**：编辑页展开级联后 `scrollWidth - clientWidth ≤ 4`，且全页可见 `button/[role=button]` 高度 ≥44px（由 `e2e-visual-guard` 新增段断言）。
7. `./mvnw test` 与 `npx vitest run` 全绿；六条经过编辑页的 e2e 不受影响（areaType 默认仍为 1、组件仍在 `v-if` 内）。

---

## 12. 风险与回滚

| 风险 | 缓解 | 回滚代价 |
| --- | --- | --- |
| `EditorView.test.ts` 三个 mock 被打穿（约 72 用例） | 先改 helper；只在 areaType=2 时请求 | — |
| 级联面板撑破 768 横向溢出 | 每栏 `minmax(0,1fr)`，禁硬 `min-width`；tablet e2e 已在编辑器上量 | — |
| 地域变必填 → 六条 e2e 的 submit 卡住 | **areaType 默认恒为 1**，空选只在 areaType=2 时进 `validationErrs` | — |
| Teleport 脱离冒泡域 → 改了不标脏 | 不 Teleport + computed setter 里显式 `markDirty()` | — |
| G3 翻译写错 → 活动突然不发钱 | 翻译只在 areaType=2 且有选中时注入；金标集与 `ActivityEligibilityGuardTest` 不变；**新增翻译用例先行** | 删翻译方法即回到今天的行为 |
| 后端端点 | — | 极低：纯附加 `@GetMapping`，零 schema 变更 |
| 前端组件 | — | 极低：新文件，删掉即可 |
| **EditorView 字段替换** | Draft 保持 `string`（CSV），只动 `:607` 一行 | **一行 revert** |
| 长度校验 | 从 500 变 400 是行为变更，但现有 API 调用方都传 `districtIds: null` | 删一个 if |

---

## 13. 评审后修订（REVIEW 回合，全部已复核属实）

独立评审提出 5 条阻断级，逐条已亲自复核并采纳。**以下修订覆盖前文冲突处。**

| # | 问题 | 修订 |
| --- | --- | --- |
| **X1** | G3 落点选错：`translate`(:188) / `saveCondition`(:202) / `artifactService.snapshot`(:209) 三处都吃 `req.eligibilityConditionTree()`，而 `saveCondition` 首行 `if (tree == null) return` —— 「只投广东、不配其它条件」这条**最典型**路径根本不建条件行，地域依然零生效 | 合成提到 `:188` **之前**产出 `mergedTree`，上述三处**全部**改用它；用户树为 null 时也合成一棵根 AND 组，保证条件行被建出来 |
| **X2** | 「展开成叶子码」与仓库既有约定相反：`playbooks.ts:168` / `e2e-validation.mjs:314` 的 `userDistrictId` 取值都是**省级码** `310000` | 展开集合 = **选中码本身 + 其全部后代**（省→市→区县都进）。`province_code`/`city_code` 是含自身的冗余列，一次查询拿全 |
| **X3** | 无回读契约：`EditorView.vue:436` 把整份存储树灌回 UI，注入叶子会被显示给运营，且每存一次外面多包一层 AND（`RuleConditionTranslator` 有 `MAX_DEPTH=5` 硬闸，堆满就 400） | `ConditionNode` 加可空 `source` 字段，注入节点标 `"district"`；`loadForEdit` 与后端合成前**都先剥掉**旧的；合成时并进现有根 AND 组的 children，**深度不变** |
| **X4** | `SidePanel` 的 `PUSH_QUERY` 是 **1280** 不是 1024，overlay 宽 `min(94vw,458px)`，三栏装不下；≥1280 还会退化成非模态 sticky 内联块（无焦点陷阱/无滚动锁） | **改用表单内联展开面板，不用 SidePanel**。连带解掉 A7（面板盖住 submit 让 e2e 超时）、A6（插槽不符）、O6（testid 撞 ListView） |
| **X5** | DTO 砍了 `shortName` 但四处把「简称搜索」当需求 | `short` 加回契约 |

其余采纳项：`findAll(Sort.by("sortNo"))` 显式排序（A4）；`py`/`pyi` 服务端归一化为非空串（A5）；懒加载字典、areaType=1 不发请求（A1）；picker 根上截断 click 冒泡、脏值只由 computed setter 驱动（A2）；祖先/后代互斥去重（A3）；chip 样式在子组件内自带（A9，scoped CSS 拿不到父作用域）；`application.yml:64` 与 `DistrictSeeder.java:74` 两处陈旧行数一并修（A11）；DetailView 自己 load 字典并降级显示裸码（A12）；testid 随写随登记（A13）。

**保留但显式记账的偏离**：搜索框 `font-size` 用 `--fs-lg`(16px) 而非 `ListView` 的 `--fs-sm`(13px)——iOS 聚焦自动放大会撞溢出断言（A10）。

---

## 14. 待用户拍板（见 §D1）

**本计划按 D1-B（让地域真的生效）编写。** 若只要 A（纯录入体验），删去 G3 与步骤 7，并**必须**同步：把地域并进 `declarativeOnlyWarnings`、UI 加 `.declarative` 标记、补进 `docs/activity-marketing.md` 已知落差——因为仓库审计 `REVIEW.md` B2 明令「绝不能维持现状」。
