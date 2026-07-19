# 前端实施计划 · 活动营销报表页（阶段 3b）

> 由 `/frontend-plan` 工作流产出：先勘察 → 5 个只读子代理并行（需求/UIUX/架构/仓库约束/测试风险）→ 综合 → 决策记录 → 可执行计划 → 独立评审。**批准前不写任何前端代码。**
> 后端契约见 `IMPLEMENTATION_PROGRESS.md`（阶段 1/2/3a 已完成并验证）。

## 决策记录（架构备选对比）

现有前端是一个 IIFE（`app.js`），所有 DOM/渲染 helper（`el/card/kv/renderSummary`…）**都封闭在闭包里，外部文件无法复用**；`window` 上只暴露 `DROOLS_CATALOG`。渲染模型是"一个 demo = 一个 JSON 请求/响应面板"。活动营销需要的是多分区报表表单 + 递归条件树 + 列表/详情/验证多视图路由——与现有模型差异大。

| 方案 | 活动代码位置 | 对 app.js 改动 | list→detail 路由 | 破坏 Step 1–18 风险 | 结论 |
|---|---|---|---|---|---|
| A 扩展 demo「kind」+ selectDemo 分支 | 必须塞进 app.js 闭包 | 高（渲染器全进 app.js，混两套范式） | 别扭（假 pathParam） | 中（改的是渲染全部 18 个 demo 的函数） | 否 |
| B 新「页面」组 + renderNav/高亮改造 | app.js 闭包内 | 高（改 renderNav + 选中/高亮模型） | 自然 | 中（动到共享 nav 代码） | 否 |
| **C 独立 `activity.js` 子应用** | **独立文件，隔离** | **低（末尾导出 helper + 1 个 guarded nav 分支）** | **自然（自带 mini-router）** | **最低** | **采纳** |

**采纳 C**：`app.js` 末尾 `window.DemoUI = { el, clear, $, card, kv, tagList, boolPill, fmtMoney }`（纯增量导出，不改内部实现）；`renderNav()` 加一个 `g.external` 分支——外部组渲染一个入口按钮，点击调 `window.ActivityApp.mount($('panel'))`；`examples.js` 只加**一个** group（`external:true`），**不加任何 demo**（Step 1–18 的 id/path/summary key 全不动）；`index.html` 在 `app.js` 之后加一行 `<script src="/assets/activity.js">`。demo 渲染链路（selectDemo/renderSummary/SUMMARY）**逐字节不变**。

## 目标 / 非目标

**目标**：在演示台新增「活动营销」子应用，用报表式表单 + 条件树构建器走通：活动创建（红包固定/阶梯、买赠）、制定资格规则（白名单条件树 + 预览）、商品绑定（手动/商品池）、上下线、版本化编辑、优惠/买赠验证（含 trace 展示）。全程对接已完成的 8 个后端端点，**不改后端契约**。

**非目标**：不做商品/池 CRUD（无端点）；不做鉴权/多用户；不支持红包/买赠以外的活动类型（后端 400 且 UI 禁用）；不做版本历史/回滚浏览；不做裸 DRL 编辑；不做分页/服务端搜索；不引入任何前端框架/构建工具/CDN。

## 路由与页面流（子应用自带 mini-router，渲染进 `#panel`）

四个视图：`list`（默认）/ `form`（新建/编辑）/ `detail`（回显）/ `validate`（验证）。

- **创建红包+资格 → 上线 → 验证命中 → 编辑 → 下线 → 验证不命中**：list →「新建」→ form（type=红包，固定金额，条件树 orderAmount≥100，绑定 spu，**预览**通过）→ 提交 → list → 行「上线」→ validate（订单 200 命中；订单 50 资格淘汰不命中）→ 行「编辑」→ form（预填，改金额，提交=version+1，**状态自动回到待上线**，横幅提示需重新上线）→「上线」v2 →「下线」→ validate 不命中。
- **阶梯**：form（红包规则区一个「固定/阶梯」**UI 切换**——阶梯模式填动态档位行 → `redPackageRangeAmount` JSON，且**不**填 `redPackageAmount`；注意「阶梯」不是 `DistributionMode`，`redPackageTakeType` 仅作可选元数据）→ 上线 → validate 不同订单金额落不同档。
- **买赠**：form（type=买赠，赠品行）→ 上线 → validate（走 `/gifts`）返回赠品。
- **商品池自动圈选**：form（绑定方式=商品池，poolRefs）→ 提交返回 `autoBoundCount` → detail 看 AUTO 绑定 → validate 命中（**依赖种子数据，见下**）。

## 组件树（`activity.js` 内，纯函数式 render，`DemoUI.el` 构建）

```
ActivityApp.mount(panel)
├─ state { fieldDict, route, currentId, draft, requestId }
├─ bootstrap(): GET /field-dict → 缓存；失败→阻塞横幅
├─ router(route): list | form | detail | validate
├─ renderList()      → GET /list → .alist-row 表 + 行操作(详情/编辑/上下线)
├─ renderForm(draft) → 6 分区：
│   ├─ ①基础信息(form-grid：名称/业务线/类型chip/优先级/库存/起止时间/地域/说明)
│   ├─ ②红包规则(type=红包：固定金额 | 阶梯动态行)         ← 随类型 mount/unmount
│   ├─ ③买赠明细(type=买赠：赠品动态行)                    ← 随类型 mount/unmount
│   ├─ ④商品绑定(手动SPU动态行 | 商品池poolId动态行)
│   ├─ ⑤资格条件树(ConditionTree 组件，递归) + 预览
│   ├─ ⑥合并策略(chip：MAX/MUTEX/STACK/PRIORITY + bizLine 级副作用提示)
│   └─ 右栏 sticky：预览 / 提交 / 结果 sum-card / 错误 err-card
├─ ConditionTree(node)  递归：group(AND/OR + [+条件][+分组][🗑]) / leaf(字段▾ 运算▾ 值)
│   └─ 值控件随 operator.operand：SCALAR 单输入 / RANGE 两输入 / LIST 标签多值输入
├─ renderDetail(id) → GET /{id} → 只读 sum-card 镜像 + 只读条件树 + generatedDrl(mono)
├─ renderValidate() → SpuDiscountRequest 构造 → [查红包优惠]/[查买赠] → 命中卡 + traces 时间线
└─ helpers: dynRow 表(ladder/gift/spu/pool 共用)、tagInput 芯片、epoch 时间转换、money 格式化
```

## 状态与边界

- **全局**：field-dict 加载中（下拉禁用「加载中…」，提交禁用）/ 加载失败（阻塞横幅）。
- **列表**：空（`.muted` 空态 + 新建 CTA）。
- **表单**：提交中（按钮禁用，防重复；沿用 app.js `btn.disabled` 模式）/ 成功（sum-card：activityId/version/状态 pill/autoBoundCount；`idempotentHit` 加金色「幂等命中」标）/ 400（err-card 显 `error`）/ 409（err-card「版本冲突请重试」+ 重新拉取）/ 网络错误。
- **条件树边界**（对齐 `RuleConditionTranslator`）：空树合法（不发 `eligibilityConditionTree`，不发空 group）；空 group 非法（提交前剪除或拦截）；**MAX_DEPTH=5**（加嵌套分组按钮到第 5 层禁用）；operand 值形状（between 恰 2 值、in/containsAny 非空列表、scalar 单值）；NUMBER 值必须数值非空；字段↔运算符白名单（选字段后重填运算符下拉）。
- **类型切换红包↔买赠**：**清空**隐藏分区的 state（不只是视觉隐藏），避免脏字段落库。
- **时间**：`datetime-local` → `new Date(v).getTime()`（epoch 毫秒），客户端校验 start<end、非空，绝不发 ISO 字符串（否则 Jackson Long 绑定失败 → 通用 400）。
- **金额**：客户端校验数值 + `[0,999999]`；展示走 `fmtMoney`；命中金额可能为 0/null，格式化前保护。
- **XSS**：新渲染器**只用 `text:`，绝不用 `html:`** 传服务端/用户数据（DRL/trace/活动名/赠品名/条件值）；DRL、trace 用 `<pre>`+textContent。
- **preview**：始终 HTTP 200，读 `ok` 标志而非状态码；`ok=false` 显 `message`。

## API 契约（前端只读绑定，字段名与后端 record 逐字对齐，防漂移）

沿用 `IMPLEMENTATION_PROGRESS.md` / controller 的 8 端点。下拉全部由 `GET /field-dict` 驱动（fields/operators[operand]/logics/activityTypes/statuses/distributionModes/strategies），**不硬编码枚举**。提交体字段名严格等于 `ActivityCreateRequest` / `SpuDiscountRequest` / `ConditionNode` 组件名——Java 集成测试即前端契约守卫。

## 文件级改动清单

- **新增** `src/main/resources/static/assets/activity.js` —— `window.ActivityApp={mount}` 子应用（router + 4 视图 + 条件树组件 + 动态行 + fetch + 预览）。
- **新增** `src/main/resources/static/assets/activity.css` —— 约 20 个新类（form-grid/select-input/field-req/field-invalid/field-error/row-group/row-head/dyn-row/row-add/row-del/row-empty/tag-input/ctree*/alist-row），**全部用现有 `--` token**，纯新增选择器，不改任何现有选择器。（单独文件比塞进 styles.css 更隔离；`index.html` 加一行 link。）
- **改** `src/main/resources/static/assets/app.js` —— 仅两处纯增量：IIFE 末尾 `window.DemoUI={el,clear,$,card,kv,tagList,boolPill,fmtMoney}`（已核验 helper 名字/位置）；`renderNav()` 加 `if(g.external){…入口按钮 onclick=ActivityApp.mount($('panel'))…; return;}` 分支——**必须放在 `CATALOG.groups.forEach` 回调内、空 demo 提前 return（app.js:51 `if(!demos.length) return;`）之前**，否则无 demo 的外部组会被跳过、nav 入口不出现。**不动** selectDemo/runDemo/handleResponse/renderSummary/SUMMARY/active 高亮。
- **改** `src/main/resources/static/assets/examples.js`（**路径修正**：是 assets/ 下）—— `groups` 加一个 `{id:"activity",title:"活动营销",subtitle:"报表式配置 · 条件树 · 优惠验证",external:true}`；**不加任何 demo**。（与上面 renderNav 分支**同步生效，缺一不可**。）
- **改** `src/main/resources/static/index.html` —— `<head>` 加 `activity.css` link；body 末尾 `app.js` **之后**加 `<script src="/assets/activity.js">`。（`mount` 是点击触发，`activity.js` 只要在 mount 时**惰性**读 `window.DemoUI`，位置在 examples.js 之后即可；顺序非严格 load-bearing。）
- **新增（后端小补丁，为可演示商品池）** `src/main/java/com/lrj/drools/activity/ActivityDemoSeeder.java` —— `CommandLineRunner`，**`@ConditionalOnProperty("activity.marketing.seed-demo-data")`（application.yml 里设 true；测试用 `@TestPropertySource` 不设 → 种子在测试中不触发，不污染 `ActivityMarketingFlowTest` 的池断言）**。仅当 `demo_product` 空时种入几条 demo 商品 + 一个启用商品池 + 圈选规则。幂等；注意运行时会往当前 profile 的库（含 MySQL）写这几条 demo 数据。

## 分阶段实施步骤（按依赖排序）

1. **CSS 骨架**：新增 `activity.css`（新类，token 化，含两个断点：≤980 表单单列、≤560 行内横向滚动）；`index.html` 挂 link。
2. **app.js 接缝 + examples.js 外部组（同步）**：导出 `window.DemoUI`；`renderNav` 加 `external` 分支（放在空 demo return 之前）；`examples.js` 加 external group。两者同一步一起改，改完手工验证 nav 出现「活动营销」入口且 Step 1–18 仍正常。
3. **activity.js 壳 + 列表**：mount/router/state、bootstrap 拉 field-dict、renderList（GET /list + 行操作 status）。
4. **表单①④⑥ + 提交**：基础信息 + 商品绑定（手动/池动态行）+ 策略 + create/edit（幂等 requestId、编辑预填、状态回退横幅）。
5. **红包②/买赠③ 分区 + 动态行**：固定/阶梯切换与 JSON 序列化、赠品行、类型切换清 state。
6. **条件树组件⑤ + 预览**：递归 group/leaf、operand 值控件、字段→运算符联动、MAX_DEPTH 限制、POST /preview 作为保存前强制门。
7. **详情视图**：GET /{id} 只读回显 + 只读条件树 + generatedDrl。
8. **验证视图**：SpuDiscountRequest 构造 + /spu-discount + /gifts，命中卡 + traces 时间线。
9. **后端种子** `ActivityDemoSeeder`（可演示池）。
10. **联调冒烟**：跑测试计划的手工清单；跑 `./mvnw test` 保证后端契约仍绿。

## 测试策略

- **契约守卫**：不在 JS 里重复后端逻辑；`ActivityMarketingFlowTest`（`./mvnw test`）已覆盖创建/编辑/上下线/阶梯/买赠/池，字段名与前端提交体一致即被它守护。
- **防漂移**：下拉全由 field-dict 驱动。
- **可选零依赖单测**：仅给"条件树 → JSON 序列化器"加一个 `console.assert` 断言文件（浏览器或 node 直跑，无框架）——它是唯一复杂到会静默产错的前端逻辑；若省略，则由 preview 强制门覆盖（preview 把真 JSON 过一遍真 `RuleConditionTranslator` + 编译）。
- **手工冒烟清单**（浏览器 + curl 双写，见 test-plan 补充）：nav 出现且 Step 1–18 正常 → 建红包+资格 → 上线 → 命中 → 资格淘汰不命中 → 编辑 v2 → 下线不命中 → 阶梯三档 → 买赠赠品 → 池 autoBoundCount → 预览非法字段(ok=false)/非法运算符/合法 → 400(start≥end / 金额越界) → 409(并行两次编辑)。

## 验收标准

- [ ] 「活动营销」组出现，Step 1–18 全部照常（点几个 demo 验证）。
- [ ] 报表表单可建红包（固定/阶梯）与买赠；类型切换正确显隐并清脏 state。
- [ ] 条件树可增删嵌套、operand 值控件正确、字段/运算符白名单联动、≤5 层、预览显翻译后的约束+DRL 或错误。
- [ ] 列表/详情/上下线/版本编辑闭环；编辑后有"需重新上线"提示。
- [ ] 验证视图展示命中活动/金额/策略/mode/traces；红包与买赠两条链路都能跑。
- [ ] 池 autoBoundCount ≥1（配合种子数据）。
- [ ] 400/409/网络错误可读；preview 读 ok 标志；无 `html:` 注入点。
- [ ] `./mvnw test` 绿；桌面/移动宽度无字段重叠。

## 风险与回滚

- **字段漂移** → field-dict 驱动 + 字段名对齐 record + Java 测试守护。
- **破坏 Step 1–18** → 纯增量：新 group（不加 demo）、新 CSS 文件（不改现有选择器）、app.js 仅导出 + guarded 分支；`renderSummary` 的 try/catch 本就隔离渲染器崩溃。
- **条件树产非法 JSON** → 客户端按 operand/MAX_DEPTH 预校验 + preview 强制门 +（可选）序列化断言。
- **XSS** → 只用 `text:`。
- **池在浏览器不可演示** → 加 `ActivityDemoSeeder` 种子（本计划已含）。
- **回滚**：最省——从 `examples.js` 删掉那一个 external group（nav 即消失，数据级回滚）；或用一个常量/localStorage 开关门控该组；或 `git checkout` 三个静态文件 + 删 activity.js/activity.css + 删 seeder。后端 8 端点可保留（无害），`activity.marketing.rule-engine.enabled=false` 可退回旧 Java 逻辑。

## 待澄清 / 假设（不臆造，交付前确认）

- **[假设] bizLine** 用一个小预设下拉（如 mall / smoke 几个固定值）而非自由文本——因为策略是 bizLine 级，固定集合能让验证解析到预期策略。
- **[假设] storeId/spuId** 自由数值输入（无校验端点）；池自动圈选靠种子数据里的商品 id。
- **[假设] 时间**用 `datetime-local` → epoch 毫秒。
- **[假设] requestId** 每次新建生成 UUID，仅"重试同一提交"复用。
- **[假设] 编辑后状态回到待上线**——用横幅显著提示，不自动上线。
- **[假设] activityRule/说明** 为可选自由文本。

## 响应契约（读视图绑定，字段名逐字对齐后端；Java 测试只守请求侧，响应侧靠此表 + 手工冒烟）

- `POST /create`、`/{id}/status` → `CreateResult{ activityId, version, status, idempotentHit, autoBoundCount }`
- `GET /list` → `ActivityManageEntity[]{ id, activityId, activityName, bizLine, activityType, activityRule, activityStartTime, activityEndTime, activityStatus, activityAreaType, districtIds, priority, inventory, userInventory, version, requestId, isDel, createdStime, modifiedStime }`（时间为 **Instant → ISO-8601 字符串**，Spring Boot 默认 `write-dates-as-timestamps=false`）
- `GET /{id}` → `ActivityDetail{ manage, rules[], conditions[]{scene,conditionTreeJson,generatedDrl,enabled}, bindings[]{spuId,storeId,bindSource,effective,poolId}, gifts[], poolRefs[] }`
- `POST /spu-discount` → `DiscountView{ hit, hitActivityId, hitActivityName, hitAmount, strategy, traces[], mode }`
- `POST /gifts` → `GiftView{ gifts[]{batchId,giftName,giftType,giftNum,absoluteAmount,rightType}, traces[], mode }`
- `POST /preview` → `PreviewResult{ ok, constraint, drl, message }`（**恒 200，读 ok**）

## 独立评审修正记录（9 项已并入本计划）

跨模型独立评审（读真实仓库、带 file:line）判定 **APPROVE WITH CHANGES**，核验通过 Option C 的承重假设（8 个 helper 名字/位置、`el()` attrs 契约、`renderNav` 空组 early-return、ConditionNode 形状、MAX_DEPTH=5）。9 项修正：

1. **renderNav 分支位置**（承重）：必须在 `app.js:51` 空 demo return **之前**——已在文件级清单标注。
2. **examples.js 路径 + 纳入步骤**：真实路径是 `assets/examples.js`，且与 renderNav 分支同步（步骤 2）——已修正。
3. **阶梯不是 `DistributionMode`**：`DistributionMode` 只有 FIXED(1)/RANDOM(2)；阶梯由是否填 `redPackageRangeAmount` 决定，做成「固定/阶梯」UI 切换，`redPackageTakeType` 仅可选元数据——已修正页面流。
4. **响应契约**：新增上面「响应契约」表（list/detail/spu-discount/gifts/preview 精确字段）——已补。
5. **编辑回填时间读路径**：详情返回 **Instant ISO 字符串**（非 epoch 毫秒），回填 datetime-local 需 `new Date(iso)` 解析；写路径仍 datetime-local→epoch 毫秒。实施时以实际序列化为准。
6. **编辑幂等 no-op 陷阱**：`create` 对重复 `requestId` 短路返回旧结果。**打开编辑表单必须新铸 requestId**，仅"同一提交重试"复用——列入强约束。
7. **种子必须对测试关闭**：`ActivityDemoSeeder` 用 `@ConditionalOnProperty` 门控，application.yml 设 true、测试不设 → 不污染 `ActivityMarketingFlowTest` 池断言；并注明会往当前 profile 库（含 MySQL）写 demo 数据——已修正文件清单。
8. **`el()` disabled 陷阱**：`el` 对任何非 null 值都 `setAttribute`，`{disabled:false}` 也会禁用。加载态禁用下拉/按钮一律走 `node.disabled=true` 属性，不经 el attr。
9. **活动类型下拉**：field-dict 返回全部 5 种，但后端只接受 1/5。下拉从 dict 渲染但**只启用 RED_PACKAGE(1)/BUY_AND_GET(5)**，其余禁用——协调"不硬编码枚举"与"仅支持两类"。

**采纳的可选建议**：放弃"条件树序列化零依赖单测"，改由 `POST /preview`（走真 translator+编译）作为权威守卫；MAX_DEPTH 说明——加嵌套分组按钮在第 5 层禁用，但"加条件(叶子)"在最深组仍可用。
