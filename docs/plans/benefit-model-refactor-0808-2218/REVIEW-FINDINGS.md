# 评审发现（三视角原始产出，未加工）
> 由 `fullstack-refactor-plan` workflow 的三个评审 agent 产出，逐条保留。
> 裁决见 [DECISION_RECORD.md](DECISION_RECORD.md)，最终计划见 [FINAL_PLAN.md](FINAL_PLAN.md)。

---

## 视角：前后端一致性

**总体判断**

五路各自质量都很高，但**没有任何两路在权益契约的形状上是一致的**：同一个东西出现了 4 个名字（BenefitPlan IR / BenefitSpec / BenefitSchema+payload / PlayTemplate+params / activity_benefit_spec）、3 种主键类型（activityType 数字 / templateCode 字符串 / benefitType 字符串）、3 张落库表名，以及 2 套互不兼容的表单 schema。最致命的一处是：第 2 路的 `BenefitParamSpec(key,label,valueType,required,enumValues,min,max)` 里**没有 control、没有 visibleWhen、没有 rowSchema**，而第 3 路的前端渲染器恰恰靠这三样才能替掉 `v-if="dr.activityType===1"` 和阶梯 DynRowTable——也就是说后端提出的字典形态**渲染不出前端方案里的任何一个表单**，这是链路上的真实断点，不是措辞差异。其次，第 1 路正确指出决策入参 `SpuDiscountRequest` 只有 `spuIdList/orderAmount/quantity`（已核实）、没有行项，导致单价类玩法从入口就不可表达，而第 2/3/5 路完全没接这条——第 5 路的金标测试集正是建在旧请求形状上，B1 一改就要整体重写。类型生成（springdoc + openapi-typescript）技术上可行且 sealed interface + `@JsonTypeInfo(EXISTING_PROPERTY, visible=true)` 能干净导出 oneOf，但前置工作量被低估：`ActivityMarketingController` 的 create/list/detail/field-dict 全是 `ResponseEntity<?>` + 手拼 `Map.of`，不收敛成具名 record 之前生成物全是 unknown。现在不能直接进入实施计划，必须先做一次契约冻结。

### 必须修正（must_fix）

**1. 权益契约的名字与形状必须先冻结成一套，且明确三层各自的类型**

> 在任何编码前产出一份 docs/plans/*/CONTRACT-FREEZE.md，逐字定死三层：①后端权益类型元数据 = BenefitTypeSpec（采用第2路结构，但按下一条补字段）；②下发字典 = 第4路的 ParamField[]（采用 control/valueType/required/visibleWhen/rowSchema 这套字段名，废弃第3路的 BenefitField 同义词）；③提交体 = {benefitType: string, params: Record<string,unknown>}（废弃第3路的 activityType+variant+payload 三元组、废弃第1路的 templateKey）。落库表名统一为 activity_benefit，主键 (tenant_id, activity_id, version, benefit_id)。这份文件是 CONTRACT_FREEZE，后续任一路提到旧名字一律以此为准。

**2. BenefitParamSpec 必须补 control / visibleWhen / rowSchema 三个字段，否则字典渲染不出任何一个现有表单**

> 把 BenefitParamSpec 改成 record BenefitParamSpec(String key, String label, ParamControl control, FieldValueType valueType, boolean required, Object defaultValue, String unit, BigDecimal min, BigDecimal max, Integer scale, List<EnumOption> enumValues, List<BenefitParamSpec> rowSchema, Integer minRows, Integer maxRows, VisibleWhen visibleWhen, String testid)。ParamControl 闭集：TEXT/NUMBER/MONEY/PERCENT/SELECT/SEGMENTED/SWITCH/TAGS/DATETIME/ROWS/FIELD_REF。把这份枚举同时落成 contracts/param-controls.json，后端测试断言所有内置类型的 control ⊆ 该文件，前端 Vitest 断言渲染器覆盖同一文件——一份 JSON 两侧断言，这是静态类型盖不到的唯一门禁。

**3. 必须写出 BenefitValue（sealed record）→ ParamField[] 的投影函数，并把 LADDER 的档位表定义成 rowSchema**

> 在 activity-common 增加 BenefitSchemaProjector：输入 BenefitTypeSpec + allowedModes，输出 List<BenefitParamSpec>，其中 mode 投影成 control=SEGMENTED 的枚举参数，LADDER 投影成 control=ROWS + rowSchema=[min:NUMBER, max:NUMBER(nullable), reward:MONEY] + visibleWhen={key:'mode', in:['LADDER']}，RANDOM_RANGE 投影成两个带 visibleWhen 的 MONEY。加一条单测：对每个内置 BenefitTypeSpec，投影出的 ParamField[] 反向能被 BenefitSpecValidator 接受（round-trip）。

**4. 提交体里 benefitId / merge / scope / limit 四项的归属必须定死，不能留空**

> 定死：benefitId 由后端在 create/newVersion 时按 (benefitType + 序号) 生成并在响应里回带，前端只读展示、编辑时原样回传；merge/scope/limit 不进权益表单，由活动级的 stacking 配置 + BenefitTypeSpec.defaultLimit 在服务端补全（前端表单只出 params）。把这条写进 CONTRACT_FREEZE 的『前端不产出的字段』小节，并在 ActivityUpsertService 里加断言：请求体带 benefitId 以外的 merge/scope/limit 一律 400。

**5. 决策 explain 必须带 candidates[]（含 eligible / rejectReason / ladderTier / computedAmount / priority / winner），仅有 trace.steps 不够**

> 在决策响应里新增 explain.candidates: Array<{activityId, activityName, eligible, rejectReason, ladderTier:{min,max,reward}|null, computedAmount, priority, winner}>，仅在 trace level>=summary 时填充；trace.steps 每一步补 durationMs。同时把 stages 的 fallback 布尔位显式化（对应 ActivityQueryService 三处静默降级）。这份结构写进 CONTRACT_FREEZE，第3路的 explainFrom 降级路径只保留给 legacy 端点。

**6. 新端点路径必须同时改 nginx rewrite、apiClient 的 ServiceKey 和 BASES，三处缺一前端就调不到**

> 同一个 PR 内三处一起改：nginx 两条 rewrite 去掉写死版本段（/api/decision/(.*) → /decision/$1，/api/console/(.*) → /$1，由客户端自带版本）；apiClient 的 ServiceKey 扩成 'root'|'marketing'|'console'|'decision' 并补 BASES；activityApi.ts 的新方法一律走新 service key，旧方法保留标 @deprecated。改完立刻跑 frontend/e2e/contract-smoke.mjs 验证网关路由。

**7. DynRowTable 的 :key="i" 必须在 ROWS 控件落地之前修成稳定行 id**

> 给行对象注入内部 _rid（复用 logic.ts 的 nodeId 生成方式），:key 改 row._rid，提交前 normalizePayload 剥离 _rid。补一条 Vitest：三行数据删中间行后，剩余两行的字段值不变。这条必须排在 BenefitForm 之前。

**8. e2e 的 form-amount / spu-row-input 两个 testid 必须先给出稳定来源，否则平板与手机两条 smoke 直接红**

> 在 BenefitParamSpec 里加 testid 字段（上面第 2 条已含），后端内置的 RED_PACKAGE_CASH 的 amount 参数下发 testid="form-amount"；同时前端维护 benefit/legacyTestid.ts 做 key→testid 兜底。改造 BenefitForm 的同一个 PR 内必须跑 npm run e2e:tablet 与 e2e:phone，并把新增 testid 登记进 frontend/e2e/data-testid-contract.md。

**9. 行项模型（SpuDiscountRequest 是否加 items[]）必须在建金标集之前拍板**

> 在 B0 启动前给出二选一的书面决定：(A) 本轮不做单价类玩法，SpuDiscountRequest 冻结不动，能力矩阵里 A4-基数/A5-行选择/A9-分摊 明确标『本轮不支持』并从字典里移除对应 control；或 (B) 本轮就加 items[]，那么 B0 的金标 fixture 直接用新请求形状录制，且 ValidateView 的试算表单同批改造。不允许『先建金标、以后再加 items』。

**10. storeId 的 fail-closed bug 必须在录金标之前修掉**

> 作为 B0 的第一个提交单独修：把 buildContext 改成按 schemaRegistry.resolve(tenant,bizLine) 遍历白名单填充属性袋（这也顺带解掉『新增条件字段还要改 Java』），补一条以 storeId 为条件的命中回归用例，然后再录金标。

**11. activity-decision 的 ddl-auto 必须先从 update 改成 validate，且新增表放在 console 侧的迁移里**

> B0 第一批：activity-decision/src/main/resources/application.yml 的 ddl-auto 改 validate；console 引入 Flyway 并对现有 schema 打 baseline，console 的 ddl-auto 同步改 validate；deploy/docker-compose.yml 给 decision 加 depends_on: console + healthcheck 门禁；补一条 ArchGuard 断言 decision 的 ddl-auto 有效值必须是 validate。deploy/mysql-init 的只读账号已是库级 GRANT SELECT ON drools_demo.*（已核实），新表自动可读，无需改授权。

### 路线之间的冲突

- 【表单字典的形态】第2路 vs 第3路：第2路下发的 BenefitParamSpec 只有 (key,label,valueType,required,enumValues,min,max)，第3路的 BenefitControl.vue 需要 control（区分 MONEY/PERCENT/SEGMENTED/ROWS）、visibleWhen（替掉 dr.redMode 的 v-if）、columns（阶梯 3 列表）。按第2路的字典，第3路的表单一个都渲染不出来——这不是命名差异，是能力缺失。
- 【权益的主键类型】第3路用 activityType 数字（BenefitSchema.activityType: 1/5）+ variant 字符串两级；第4路用 playTemplate 字符串 code（RED_PACKET/BUY_AND_GET/COUPON_GRANT）并把 activityType 降级成 legacyActivityType；第2路用 benefitType 字符串（RED_PACKAGE_CASH/BUY_AND_GET_GIFT）且一个活动可挂 N 条。三种主键互不兼容：第3路的『一个 activityType 一套表单』语义下无法表达第2路的『红包+赠品同活动并存』。
- 【提交体形状】第3路发 {activityType, variant, payload, schemaVersion}；第4路发 {playTemplate, params}；第2路的 ActivityCreateRequest 期待 List<BenefitSpec>，每条含 benefitId/kind/value/scope/limit/merge/items/attrs 共 9 个字段。第2路要的 6 个字段（benefitId/scope/limit/merge/kind/items）在第3、4 路的提交体里一个都没有，且其中 merge 直接决定发多少钱。
- 【落库位置】第2路新建 activity_benefit（关键字段提列 + spec_json）；第4路在 activity_rule 上加 play_template + params_json 两列；第5路新建 activity_benefit_spec。三路都自称是『本次的迁移方案』，且第4路与第5路的 backfill 逻辑会写到不同的表。
- 【阶梯落档轴】第4路把 ladderOn 做成 FIELD_REF 参数（运营可选 orderAmount/mileage/completedTrips）；第2路把 driverField 做成 BenefitValue.Ladder 的字段并在迁移规则 R2 里硬编码成 "orderAmount"；第1路的 LADDER_OFF 模板把 metricField 做成 SELECT+optionsFrom=field-dict。同一个概念三个名字（ladderOn / driverField / metricField）、三种控件、且第2路的迁移会把它写死。
- 【决策端点数量】第4路用单一 POST /decision/v2/evaluate 取代 spu-discount + gifts 两个端点，靠 options.benefitTypes 过滤；第3路的沙盘保留 mode:'discount'|'gifts' 双按钮并继续调两个端点（ValidateView 现有实现即如此）；第5路的影子双跑装饰器包的是 ActivityQueryService.spuDiscount/buyAndGetGifts 两个方法。若按第4路合并端点，第5路的双跑插入点和第3路的沙盘 UI 都要重做。
- 【generation / variant 的传递方式】第3路的沙盘对比模式并发发两个请求、用 X-Generation / X-Variant 请求头区分；第4路把 generation 和 experimentOverrides 放进 body 的 options 里。两者不能共存，且第3路的『勾选两个目标并排对比』依赖的就是同一个 body 发两次只换头。
- 【trace 默认级别】第4路定 trace 生产默认 off、控制台 summary、full 需 decision-trace-authority；第3路的沙盘 PipelineTrace 与 CandidateTable 默认就要 summary 级的逐候选数据，且第5路的影子比对明确规定 traces 不参与比对。三方对 trace 是『可选调试信息』还是『沙盘的一等公民』认知不一致，直接影响 explain 结构是否必须常驻。
- 【前端改造范围】第3路把 EditorView 拆成 5 个 Section + 3 个 composable + 草稿 autosave + diffDraft + 发布状态机 + 版本 diff + A/B 全套（新增约 40 个前端文件）；第2路的最后一条只说『前端 benefit-form 动态表单，另开前端计划』；第5路的 B2 定义是『把权益表单改成 schema 驱动』。第3路的实际范围是第2/5路预期的 5 倍以上，若按第3路排期，B2 会阻塞 B3/B4。
- 【迁移风险取向】第2路对 take_type=2 且解析出 >1 档的行判 NEEDS_REVIEW『绝不猜』；第5路的开放问题 Q2 直接问『RANDOM_AMOUNT 现在的实际行为是什么，是迁移时修正还是原样保留错误行为』。第2路已经替业务做了『不猜』的决定，第5路认为这是待业务拍板的事——两路对同一批数据给出了不同的处置权归属。

### 建议砍掉（过度设计）

- 第1路的 14 个能力原子里，A13 分流实验 与 A14 决策时点/发放 不是权益模型的原子——前者是改造 B 的载体职责，后者描述的是『异步发券/返现/裂变』这三条本仓库根本不存在的兑付链路。砍到 12 个原子，A13/A14 移出权益模型另立议题。
- 第1路的 A8 取整（applyAt 三值：PER_LINE/PER_PLAN/PER_ORDER）与 A9 分摊（mode 四值 + residual 三值）：在 SpuDiscountRequest 没有行项之前，『摊到行』的目标集合是空的，三值配置无处生效。本轮只保留一个全局 scale=2 / HALF_UP 常量，分摊整块砍掉。
- 第2路的 BenefitValue.Table（多维查表）与 BenefitValue.Expression（QLExpress + AST 白名单 + InstructionSet 缓存）：没有任何现存或计划中的玩法需要它们。Expression 单独就要引一个新运行时依赖 + 写一个 AST 白名单校验器 + 承担一类新的注入面。本轮 sealed interface 只保留 FIXED / LADDER / RANDOM_RANGE 三支——这三支已经覆盖今天能配出来的全部内容，且 sealed 的好处正是以后加分支时编译期报错，不需要提前占位。
- 第2路的 BenefitScope(ORDER/STORE/SPU/SKU/USER) + 按 scopeKey 分池的 residual + BenefitAllocator 的尾差归位：分摊结果决策侧不能落库（decision 连只读账号，第2路自己的开放问题也承认这点），且没有行项就没有摊的对象。整块砍掉，本轮 scope 恒为 ORDER。
- 第2/3/4 路各自提出的 schema 运维子系统（schema_revision 审计表 + SchemaAdminService.impact() 影响面预演 + publish 五步事务 + 四眼 + 5~7 个运维端点 + 前端 schema 编辑界面）：这是在一个至今没有任何写入路径的 stub 上外挂第二个管理产品。本轮只做『RuleSchemaRegistry/BenefitSchemaRegistry 落库 + 只读下发』，写入继续走代码发版，运维端点全部推迟。
- 第3路 P0/P2 里的 DraftRestoreBanner + useDraftAutosave（localStorage 草稿暂存）+ diffDraft + VersionTimeline + VersionDiff + RollbackDialog + PublishDialog：这七个组件与『权益表单数据驱动』零关系，且 VersionDiff/Rollback 依赖后端 D10/D11 尚未修复。全部移出本轮，只保留 EditorView 拆分 + BenefitForm + 分步校验。
- 第3路 P4 的 A/B 界面全套（BucketRuler 可拖拽标尺 + RampControl + ExposureChart 内联 SVG + VariantCompareTable + 三个页面 + experimentApi 的 fake adapter）：后端零实验模型、零业务埋点（第4/5路已证实全仓无 MeterRegistry 使用）、第4路的写平面 experiment 字段留 null。做一套只能连 fake 数据的实验控制台，是在给不存在的后端写前端。整块推迟到 B5，本轮连契约都不定。
- 第4路把 /console/v1 新命名空间 + 全量分页 + Problem Details + Idempotency-Key filter + 4 个新 authority + oasdiff 破坏性变更门禁 全部捆进本次重构：每一项单看都对，但捆在一起意味着权益模型的迁移无法独立回滚，且这些横切改动会波及全部 40 个 console 测试。拆成独立的『API 卫生』批次，与权益模型解耦，本轮只保留 Idempotency-Key（因为第4路已证实前端在 EditorView.vue:204 有一处 requestId 的 workaround）。
- 第4路的 play_template.benefit_expression（把权益计算写成表达式存进 DB）：这是把『加玩法不改代码』推到极致的做法，代价是运营配置的一个字符串直接决定发多少钱，且没有编译期检查、没有单测覆盖、出错只能在生产发现。本轮明确放弃，接受『新计算原语 = 一次性 Java 改动』这个边界（第4路自己在 §0 也写了这条边界，但随后又在表里把 benefit_expression 当成必需项）。
- 第5路 B0 里的『万级 seeded RNG 差分测试（T3）』：它服务的是 B3 分层引擎，而 B3 排在 B1/B2 之后。B0 阶段建它属于为三个批次之后的事情提前投入。B0 只做 golden 集（T1）+ BigDecimal 契约（T2）+ LadderRangeParser（T5）+ 指标 + Flyway，T3 挪进 B3 作为该批次的准入门禁。

### 无人负责的空白

- 没有人写 BenefitValue（后端 sealed record）→ ParamField[]（下发字典）的投影函数。第2路给了后端类型、第4路给了字典类型，中间那一步五路无人认领——结果就是同一份结构被手工维护两遍，第一次改档位就漂移。
- benefitId 的生成方 未定。第2路把它定义成兑付幂等 + 分摊归属 + 历史对账的锚点并要求编辑时不变，但第3路的表单、第4路的 params、第5路的 backfill 都没有它的位置。若前端 mint，一次重渲染就换 id；若后端 mint，第2路要求的『编辑时保持不变』需要一条明确的回传规则，无人给出。
- 决策入参的行项模型（items[] with unitPrice）只有第1路提出问题、第4路给了字段，但没有任何一路把它排进批次、也没人认领它对第5路金标 fixture 形状的连带影响。第2路的 evaluator、第3路的沙盘 ContextPanel、第5路的 T1/T3 全都默认旧形状。
- 库存与频次（inventory/userInventory）的最终处置无人拍板。第1路把 A11-配额 列为秒杀/买赠/发券的必需项并要求 RESERVE_THEN_CONFIRM；第2路把 evaluator 设计成纯函数、把库存列为开放问题；第4路标 enforcement=DECLARED_ONLY；第5路不测。而 decision 连只读账号——『决策时扣减』与『decision 只读』是正面冲突，五路无人给出解法或明确弃权。
- 前端到底还剩多少手写契约 无人收口。第3路说 types.ts 退化成薄别名层，第4路说同样的话，但两路都承认 params 只能是 Record<string,unknown>——也就是说权益侧恰恰是生成器覆盖不到的地方。第3路的 check-contract.mjs 运行时哨兵是唯一能覆盖这一段的手段，但第4路的方案里没有它，两路各说各话地宣称『契约防漂移已解决』。
- legacy 决策端点的消费方清单 无人排查。第4路要把 spu-discount/gifts 合并成 /decision/v2/evaluate 并靠投影保兼容，第5路要给旧列定下线时间点，但仓库里没有任何东西能回答『除了本仓库前端，还有谁在调 /activity-marketing/spu-discount、还有谁在直读 activity_rule』。这个问题不答，所有兼容期与下线排期都是拍脑袋。
- 前端镜像的版本 tag 只有第5路提了一句（deploy.sh 只打 :latest，回滚时没有『上一个』可回），而第3路整个 B2 批次的『分钟级独立回滚』承诺完全建立在这个前提上。没人把它排进任何批次。
- 权益 schema 的多租户治理边界 无人定。第1路问『PlayTemplate 平台统一还是租户可自定义』，第2路问『benefitType 白名单谁定』，第4路问『租户能否覆盖内置模板的 paramsSchema』——三路各自把它列成开放问题，没有一路给出倾向或默认值。而这直接决定 BenefitSchemaRegistry 是单层还是三级回落，是架构决策不是配置项。
- 异构权益同时命中的业务默认口径 无人定。今天 spuDiscount 只捞 RED_PACKAGE、buyAndGetGifts 只捞 BUY_AND_GET，两个接口物理隔离，合并后必须给默认值。第2路默认成 legacyExclusive（全局单选、行为不变）并自陈这可能不是业务想要的；第4路默认成按 benefitType 分桶全给。两个默认值差一个数量级的发放金额，五路无人要求业务拍板前不得编码。

---

## 视角：工程可行性与是否过度设计

**总体判断**

五路诊断一致且互相印证，靶心是对的：权益侧确实只有"红包定额"一条通路，条件侧确实是可直接复制的样板。但解法总量严重超配——平台后端 main 只有 6096 行、测试 3145 行，五路合计提出 300+ 文件变更、40+ 个新 Java 类、5 张新表、7 个新前端页面/大区块，新增代码量是被改造系统的数倍。更致命的是驱动力缺失：勘察一自己在 assumptions 里承认「21 个玩法来自行业通行做法，未从代码或文档中找到任何玩法需求清单」，也就是说整套 14 原子 IR 是为不存在的需求造的。现实成本是：把 COUPONS 按现有硬编码方式再加一遍约 60-100 行、1 人日；而抽象层要回本需要 15 个以上新玩法。建议只保留「BenefitSpec 最小版 + 前端 schema 表单 + 3 条独立现网 bug 修复」，把分层引擎和快照包拆成独立路线（且快照包优先于分层引擎），A/B、沙盘、发布状态机、OpenAPI、ProblemDetail、Flyway、影子双跑全部推迟。

### 必须修正（must_fix）

**1. 整套重构的需求基线不存在，必须先做一次「第三个玩法」的真实成本实测再决定是否上抽象**

> 动任何代码前做两件事：(1) 让业务给出未来 6 个月确定上线的玩法清单，含每个玩法的配置字段；(2) 用当前硬编码方式把 COUPONS(2) 实现一遍并计时，把真实人日写进计划。若清单 ≤5 个且都是「金额型 + 固定/阶梯」变体，则直接放弃 PlayTemplate/BenefitPlan IR 整套，只做 BenefitSpec 最小版（kind + value + params Map）。把这条判据以『若清单 ≤5 则 §PlayTemplate 不实施』写进计划文档的验收标准段。

**2. SpuDiscountRequest 是否加行项（unitPrice/quantity per line）没有裁决，而它是唯一真正的架构分叉点**

> 在 B0 之前单独拍板并写进计划：选 A（不加行项）→ 立即从设计里删除 Selector/Allocation/UNIT_PRICE 基数三个原子及其在勘察二 BenefitSpec 中的对应字段，不是『以后再说』而是现在删；选 B（加行项）→ 明确 /decision/v1 的请求契约破坏性变更，并把勘察五的影子对拍范围收窄成『只对存量红包/买赠双跑』，新玩法不参与对拍。

**3. storeId 的 fail-closed 现网 bug 被绑进了 6 批次重构，应立即独立修复**

> 单独一个 commit：buildContext 补 storeId 写入 + 一条回归测试（条件含 storeId 的活动必须能命中）。先于所有重构合入主干。同时在同一 commit 里加一条断言『schema 白名单里的每个 key 都必须在 buildContext 有写入路径』，防止再犯。

**4. 勘察二的 backfill 规则 R3 会把今天实际发 0 元的存量行改成发随机金额**

> 把 R3 从『自动转换』改成 NEEDS_REVIEW（与 R4 一致），并在 dry-run 报告里单列。同时先跑一条 SQL 统计生产 take_type=2 的存量行数：若为 0，把 DistributionMode.RANDOM_AMOUNT 枚举整个删掉而不是去实现它；若非 0，逐行人工裁决后再迁。

**5. activity-decision 的 ddl-auto=update 必须独立修复，不要捆绑 Flyway 决策**

> 单独一个 commit：decision 的 ddl-auto 改 validate，并在 TenantArchGuardTest 里加一条断言（decision 模块有效 ddl-auto 必须是 validate）。是否引入 Flyway 是另一个独立决定，不阻塞这条。

**6. 五路对同一批文件给出互斥的目标形态，必须先产出唯一合并清单再动手**

> 在写第一行代码前产出一张合并表：每个受影响文件一行，列出『唯一 owner 路线 / 唯一目标形态 / 被否决的其它路线改法及否决理由』。冲突条目（至少是 contradictions 里点名的 7 条）逐条书面裁决。这张表是后续所有实施的唯一依据。

**7. B0 护栏批被塞成 8-12 人日，必须砍到 3 人日以内且不含 Flyway 与影子框架**

> B0 重新定义为四项、上限 3 人日：(1) 金标测试集砍到 40 例（4 策略 × 候选 0/1/N × 阶梯四个边界：低于首档/恰等 min/区间内/恰等 max/高于末档）；(2) storeId 修复；(3) decision ddl-auto 修复；(4) 三个指标 activity.decision.duration / .fallback{reason} / .candidates。Flyway、影子框架、10000 例差分测试全部移出 B0。

**8. 影子双跑框架应改为 JUnit 级金标对拍，取消 @Primary 装饰器 + 线程池 + 采样 + 生产比对那一整套**

> 删掉 ShadowingDecisionService / DecisionResultComparator 的生产形态。改为一个 ParityTest：读 B0 的 40 例金标 fixture，新旧两条链路各跑一遍比 hitAmount（用 compareTo 不用 equals）、hitActivityId、strategy、gifts（排序后比）。零生产风险、零线程池、零 DB 翻倍。等确认有真实生产流量后再评估是否需要在线影子。

**9. A/B 实验整块（勘察三三个新页面 + 勘察五 B5 + 勘察二 experiment 原子）必须从本轮路线图删除**

> 从本轮删除 ExperimentListView / ExperimentEditorView / ExperimentDetailView / BucketRuler / RampControl / ExposureChart / VariantCompareTable / experimentLogic / useExperimentStore / experimentApi，以及勘察二 BenefitPlan 的 experiment 字段、勘察五的 B5 批次。写进计划文档的 not-now 列表，前置条件写明『决策曝光与命中埋点上线且有 ≥2 周数据后再评估』。

**10. 前端 EditorView 拆分与权益 schema 化必须合并为一批，且不得引入四个新 UI 原语**

> 合并成一批一次改到位。新原语只保留 DataTable（ListView 已有手写实现要复用，且是保住 tablet/phone smoke 无横向溢出断言的关键手法）；Drawer/Toggle/Stepper 等到真出现第二个使用点再抽。同时删除 draftDiff 纯函数 + DiffRow 类型 + useDraftAutosave（localStorage 草稿 + baseVersion 比对 + 恢复 Banner），dirty 判定用 JSON.stringify(draft) !== JSON.stringify(baseline) 三行解决。

**11. OpenAPI 生成链路（springdoc + contracts profile + oasdiff + 5 个 CI 门禁）本轮只落 check-contract 哨兵**

> 本轮只实施 frontend/scripts/check-contract.mjs：拉 field-dict / auth-config / list / create-400 四个响应，与 shared/types.ts 的键集合逐一比对，挂进 npm run check:contract 并入 CI。springdoc 依赖、contracts profile、contracts/*.json 入库、oasdiff 破坏性变更检测、param-controls.json 双侧断言全部推迟到 controller 具名化之后。

**12. 工作量估算与合并后的变更清单严重脱节，必须重估并写进计划**

> 合并去重后重估并把数字写进计划：我的粗估是按本评审砍完后 25-35 人日（BenefitSpec 最小版 + 前端一批 + 3 条 bug 修复 + 40 例金标），不砍则 150-220 人日。同时把分层引擎（QLExpress）与代际快照包拆成两条独立路线单独估、单独排期，且顺序改为快照包优先于分层引擎——快照包解 D1/D3/D9/D11 且不改计算语义，分层引擎改计算语义、风险最高、收益最不确定。

### 路线之间的冲突

- 【抽象形状】勘察一 vs 勘察二：勘察一坚持 BenefitPlan IR 必须含 14 个正交原子（含 Selector 行选择、Allocation 分摊、Rounding.applyAt 三态、Calendar recurrence、Experiment、Lifecycle）；勘察二的 BenefitSpec 只有 7 项（benefitId/benefitType/kind/value/scope/limit/merge/items/attrs），完全没有 Selector、没有 Calendar、没有 Lifecycle，Allocation 只作为 MergePolicy 的一个 basis 枚举而非 spec 级原子。按勘察二的 schema 落地，勘察一的玩法 5（第N件M折）、8（加价购）、9（组合购/套餐）在数据结构上就无法表达。两者必须二选一，不能都实施。
- 【事实模型】勘察一 vs 勘察二 + 勘察五：勘察一 finding 2 明确 SpuDiscountRequest 只有 spuIdList/orderAmount/quantity、无行项，是比权益模型更靠前的阻塞点，『必须一起改』；勘察二的 BenefitEvalContext.baseAmounts（每 scopeKey 基数）与 BenefitScope 的 SPU/SKU 层级直接假设行项已存在，却在整份方案里从未提出要改请求契约；勘察五的六批次路线图与影子双跑设计从头到尾没有出现过『行项』二字，其对拍机制还要求请求形状不变。三路对同一个入口契约的假设互斥。
- 【合并语义的归属】勘察二 vs 勘察三 + 勘察四：勘察二把 StackStrategy 降级成 MergePolicy 的预设别名，引入每权益级的 exclusiveGroup / stackable / stackBase / applyOrder 四个配置项，并强调『applyOrder 顺序不同结果不同，必须显式配置』；但勘察三的前端设计里 StrategySection 只是『合并策略选择』一个下拉（= 今天的单一 StackStrategy），没有任何 UI 能配置 applyOrder 或 exclusiveGroup；勘察四的写平面契约同样是 stacking: { strategy: "MAX" } 一个标量。勘察二引入的每权益合并配置在另外两路的契约与 UI 里根本无法录入。
- 【前端 schema 的真相源与上线顺序】勘察三 vs 勘察四：勘察三的 P1 明确『schema 先用前端内置常量 benefit/builtinSchemas.ts + toLegacyRequest 适配器降级成现有字段，后端零改动即可上线』；勘察四的 capability-dict 设计要求 dictVersion 由后端下发、前端提交时回带、服务端可拒绝过期 schema 的提交，并把 schema 定义为服务端唯一真相源。前端持有内置 schema 常量时没有 dictVersion 可回带，勘察四的过期校验直接失效。两条迁移序列不能同时执行。
- 【决策 API 演进 vs 对拍安全网】勘察四 vs 勘察五：勘察四要用单一 /decision/v2/evaluate 取代 spu-discount + gifts 两个端点，并立下铁律『v1 端点由 v2 的服务实现投影产出，禁止两套业务逻辑』；勘察五的整套安全网建立在装饰 ActivityQueryService.spuDiscount/buyAndGetGifts 同请求两跑、逐字段比对 DiscountView 之上。若勘察四落地，被装饰的方法签名消失，且『不保留两套业务逻辑』意味着根本没有第二条链路可供影子对拍——勘察五的 S0-S5 放量判据全部落空。
- 【迁移机制】勘察二 vs 勘察五：勘察二在 assumptions 里明确『本仓库未引入 Flyway/Liquibase，故迁移方案基于 ddl-auto=update 自动建表 + console 侧幂等 CommandLineRunner 回填』；勘察五的 B0 第一件事就是引入 Flyway、把 console 的 ddl-auto 从 update 改成 validate、并加一条 ArchGuard 断言强制 validate。B0 落地后勘察二的新实体不会自动建表，其整套迁移方案的第一步就断了。
- 【decision 侧新表授权】勘察二 vs 勘察五：勘察二在 file_level_changes 里要求修改 deploy/mysql-init/『只读账号追加对 activity_benefit / rule_schema_field / benefit_type_schema / schema_revision / activity_bundle 的 SELECT 授权』；勘察五 finding 6 已核实 mysql-init 里是 GRANT SELECT ON drools_demo.* 库级通配，新增表自动可读、不需要额外 GRANT。勘察五是对的，勘察二这条是多余改动，且会误导实施者以为漏了授权会出故障。
- 【RANDOM_AMOUNT 的处置】勘察一 vs 勘察二：勘察一判定这是『已损坏』（枚举有、链路无、字段被阶梯语义抢占、运营真配会被静默吞成 0），倾向按缺陷处理；勘察二的 backfill 规则 R3 把 take_type==2 且恰好 1 档的行自动转成 RandomRange 并真去实现随机金额。同一批数据，一路认为该修 bug，一路认为该按字面语义迁移——而后者会把实际发 0 元的行改成发随机金额。

### 建议砍掉（过度设计）

- 勘察一的 6 份设计文档（docs/plans/benefit-model-0808/00 到 05）：为一个 6096 行的后端写 21 玩法清单 + 14 原子定义 + 21×14 覆盖矩阵 + 缺口分析 + PlayTemplate 规范 + 引擎绑定表，文档量会超过被设计的代码量。21×14 矩阵作为一次性思考工具有价值，作为交付物砍掉，留一份 ≤2 页备忘记录结论即可。
- 14 个能力原子砍掉 7 个：A5 Selector（NTH_ITEM/BUNDLE/USER_PICK）、A7 Cap 的三维封顶（保留单个 maxAmount 足够）、A8 Rounding 的 applyAt 三态（PER_LINE/PER_PLAN/PER_ORDER）、A9 Allocation（含 residual 四种归属策略）、A12 Calendar 的 recurrence、A13 Experiment、A14 Lifecycle 的 evalPoint × grantMode × userAction 三维。这 7 个全部只服务于勘察一自己承认『来自行业通行做法而非本仓库需求』的那 21 个玩法。保留 7 个：Audience（已有）、Scope（已有）、Threshold、Base、Benefit、Quota（仅声明位）、Stacking（已有）。
- 勘察二的求值体系：sealed BenefitValue + 5 个子 record（Fixed/Ladder/RandomRange/Table/Expression）+ 5 个 BenefitValueResolver 实现 + BenefitCompiler + CompiledBenefit + CompiledMergePlan + ExpressionEngine（QLExpress AST 白名单）。为 2 种真实存在的玩法造 5 个求值模式。砍到 2 个：FIXED、LADDER。TABLE / EXPRESSION / RANDOM_RANGE 全无需求（RANDOM_RANGE 甚至可能是要删的坏枚举）。连带砍掉 activity-common 的 QLExpress 依赖。
- 勘察二的 5 张新表砍到 1 张：只留 activity_benefit。benefit_type_schema + rule_schema_field + schema_revision 三张表 + SchemaAdminService + 5 个运维端点 + impact 影响面预演 + 四眼审批，全部是给『运营在控制台维护字段』这个功能造治理体系——而勘察二和勘察三都已确认它今天是 stub（RuleSchemaRegistry.register 主源码零调用、进程内 Map、无持久化）。它现在没有任何调用方，也就没有任何人受害，先留着。activity_bundle 属于快照包独立路线，不在本轮。
- 勘察二的 6 阶段迁移 P0-P6 + LegacyBenefitAdapter + BenefitBackfillRunner + BenefitParityChecker + benefit.mode 的 legacy/shadow/primary 三档开关 + 双写窗口 + 停写窗口 + 删列窗口：这套治理是给『存量千万行、不可停机、有外部调用方』设计的。这里的存量是 demo 数据，且勘察五已证明活动版本行不可变（编辑=新版本），backfill 天然是逐行纯函数。砍到：新表 + 双写 + 一个 backfill runner（dry-run / apply 两态）。
- 勘察三的 SandboxView 整块 7 个组件（ScenarioBar / TargetBar / ContextPanel / PipelineTrace / CandidateTable / CompareDiff / RawTrace + sandboxLogic + 单测）：现有 ValidateView 206 行已能试算。最该砍的是 PipelineTrace 的降级路径——勘察三自述『后端未返回结构化 explain 时，从现有 traces 按前缀「资格规则回退：」「折扣规则空决策，回退旧逻辑取最大」尽力解析阶段』，即用正则解析自家中文日志文案来画流水线图。这是负资产：日志文案一改图就错，且错得静默。整块砍掉，等后端真出了结构化 explain 再做。
- 勘察三的发布状态机五个组件（PublishPanel / PublishDialog / VersionTimeline / VersionDiff / RollbackDialog）+ lifecycle 六态派生 + lifecycle.test.ts：后端 D10（编辑即下线）与 D11（发布非原子、无回滚原语）都还没修，五个组件建在假设的双轨版本语义之上。砍到只留 LifecycleBadge（纯展示，约 30 行），其余等后端修完再做。
- 勘察三的 draftDiff.ts + DiffRow 类型 + draftDiff.test.ts + useDraftAutosave（localStorage 草稿 + baseVersion 比对 + DraftRestoreBanner 恢复/丢弃/冲突三态）+ EditorStepper 三态徽章 + L0/L1/L2 三层校验分离：对象是一个 536 行的表单页。现有 DOM 事件冒泡猜 dirty 确实丑，但 JSON.stringify(draft) !== JSON.stringify(baseline) 三行就解决，不需要一个带纯函数 + 单测 + 四处复用的 diff 子系统。
- 勘察四 capability-dict 响应里的 inventoryDimensions 与 frequencyDimensions 两块：所有条目 enforcement 一律标 DECLARED_ONLY——即下发一份自己承认不会执行的能力声明，让运营配了以为生效。已核实 inventory/userInventory 读进 ActivityCandidate 后全仓零读取。砍掉这两块，前端不渲染这两组配置项，比『配了但不生效 + 一个 warning』诚实。
- 勘察四的横切治理整包：ProblemDetail + 20 个错误码闭集 + ApiExceptionHandler + Idempotency-Key header 机制（含 endpoint/body_hash/response_json/expires_at 四列 + 同 key 异 body 409 + TTL 清理）+ 分页 envelope + sort 白名单 + 4 个 authority 分层（write/publish/schema/trace）。与『加玩法不改代码』零关系。尤其 Idempotency-Key：现有 requestId in body 已经工作且有 ActivityIdempotencyTest 覆盖，换成 header 是纯翻新。全部推迟。
- 勘察四的 M5 验收设计本身是对的（插一行 play_template 数据、不改任何 .java/.ts/.vue 就能上新玩法），但它排在 M0-M4 全部完成之后——所有投入花完才第一次验证抽象是否成立。这个顺序必须倒过来（见 must_fix 第 1 条），M5 的思路保留、位置前移。
- 勘察五的 Flyway 引入（V1__baseline.sql 从 mysqldump 导出人工核对 + baseline-on-migrate + console ddl-auto 改 validate + compose 环境变量改 validate + ArchGuard 断言）：方向对但不是本轮的事，本轮只新增 1 张表。真正紧急的是 decision 的 ddl-auto=update（5 分钟修，已在 must_fix 里单列）。
- 勘察五的 T3 LayeredEngineEquivalenceTest（seeded RNG 万级差分）与 T12 性能回归门禁：万级差分只有在真要换表达式引擎时才需要，而分层引擎已被建议拆成独立路线；性能门禁在 Metaspace 与 p99 都还没有基线数据时先建门禁没有意义。两者随分层引擎/快照包路线走，不进本轮。

### 无人负责的空白

- 【没人负责验证抽象是否成立】五路都在设计抽象，但没有任何一路提出最廉价的前置动作：用当前硬编码方式把 COUPONS(2) 实现一遍，量出真实人日与真实改动行数。勘察四的 M5 提了类似验收（插一行数据不改代码），但排在所有投入之后。谁来做这次成本实测、什么时候做、结果如何影响后续决策，无人负责。
- 【既有 31 个测试类的归属未裁决】勘察五把『111 个测试不改一行』立为适配层验收标准；但勘察二要改 BenefitOutcome 与 ActivityRuleResult 的形状，勘察四要改 ActivityCreateRequest 与 DiscountView 的形状，勘察三要重写 EditorView.test.ts 与 ValidateView.test.ts。两者不可能同时成立。哪些测试允许改、改的边界在哪、改了之后『不改测试』这条验收标准还剩多少效力，没人负责裁决。
- 【前端 e2e 的 form-amount 归属】勘察三识别出 form-amount 是最高危易碎点（schema 驱动后它不再是静态模板输入框，而 e2e-tablet-smoke.mjs 与 e2e-phone-smoke.mjs 两条 smoke 直接依赖它），并提出 legacyTestid.ts 兜底映射表。但这张映射表由谁维护、何时可以删、删的时候两条 smoke 怎么改，没人负责。其它四路完全没提这个风险。
- 【新增启动顺序约束的落实】勘察二指出新表会让 decision 的 ddl-auto=validate 产生硬性启动顺序（console 必须先完成 DDL），并提出给 compose 加 depends_on + healthcheck。但这个约束一旦引入，deploy.sh 的部署流程、--frontend-only 之外的其它路径、以及本地 mvnw 单模块启动的开发体验都受影响。谁来落实并实际验证一次冷启动，无人负责。（另注：勘察二同时提的『只读账号追加新表 SELECT 授权』是多余的，已核实为库级通配授权。）
- 【金额 scale 与舍入口径的全局默认】三路都碰到但都当成别人的事：勘察一列为开放问题（33.335 进位、先摊后取整还是先取整后摊、余数归属）、勘察五列为 R1 最高风险（BigDecimal scale 漂移、50 vs 50.00、STACK 累加舍入）、勘察二在 BenefitLimit 里直接给了默认 (2, HALF_UP) 但没说这个默认从哪来。这是唯一会真实差钱的一条，需要一个人给出全局默认口径并把它锁进金标测试的期望值，否则金标集本身就锁不住。
- 【新增包对既有架构不变量的影响】五路都声明 drools-lab 不动，但勘察二/四要在 activity-common 新增 domain/benefit、engine/benefit、engine/bundle、engine/schema、web 等多个包，勘察二还要在 activity-console 新增 SchemaAdminService/BundleBuildService。而 console 与 decision 的 scanBasePackages/@EntityScan/@EnableJpaRepositories 都是 com.lrj.drools，既有不变量是『decision 的 classpath 上没有写平面 bean，结构上就写不了』。新增类放哪个模块会直接影响这条不变量（例如 BenefitSpecValidator 放 common 则 decision 也会有它，BundleBuildService 放 common 则 decision 就能写 bundle）。TenantArchGuardTest 是否需要扩断言，没人负责。
- 【回滚开关本身不可用】勘察五 R10 指出现有 ruleEngineEnabled 是 static @Value 注入，改配置要重启——即所谓『有开关可秒级回滚』在今天是假象，真出事时 MTTR 从秒变小时。它把这条列为风险并建议新开关走 @ConfigurationProperties + 刷新，但没有任何一路把『修掉现有开关 + 演练一次不重启切换』列为交付项。所有五路的回滚方案都依赖开关可用这个未验证的前提。
- 【谁定义「本轮完成」】五路各自给了阶段划分（勘察二 P0-P7、勘察三 P0-P5、勘察四 M0-M6、勘察五 B0-B5），但没有一路定义跨路线的整体完成标准与验收人。合并后哪些阶段属于本轮、哪些明确推迟、推迟项的重启条件是什么（例如 A/B 需要『埋点上线且有 ≥2 周数据』、发布状态机需要『D10/D11 修完』），需要一份 not-now 列表并指定复审时点，目前无人负责。

---

## 视角：业务覆盖度与可运营性

**总体判断**

五路里只有第一路真正做了业务覆盖度验证（21 玩法 × 14 原子矩阵），而它得出的最关键结论——决策入参没有行项模型（`SpuDiscountRequest` 只有 spuIdList/orderAmount/quantity，没有每行单价），导致第N件M折、组合购、单品基数、优惠分摊四类玩法从入口就不可表达——第二路、第三路、第五路全都没有接住：第二路的 `BenefitEvalContext` 干脆把行项抹平成 `Map<String,BigDecimal> baseAmounts`，同时又声称 `BenefitScope` 支持 SKU 层，自相矛盾。第二路的权益抽象（FIXED/LADDER/RANDOM_RANGE/TABLE/EXPRESSION 五模式 + AMOUNT/RATE/ITEM/EXTERNAL 四形态）看着很正交，但抽查下来第N件M折（要按单价排序取第N件）、限时秒杀（要一口价改行价）、加价购（要两阶段用户选择）、裂变助力（要跨时间累积事件）四个玩法一个都配不出来，只能靠 `attrs` Map 和 `EXTERNAL` 这两个逃生舱糊——这就是"抽象漏了"。更要命的是库存/频次/防刷：`inventory` 和 `userInventory` 今天写进了 fact 但全仓零读取（我已核实），五路里第四路诚实地标了 `enforcement: DECLARED_ONLY`，第一路的矩阵把它标成秒杀/买赠/发券的"必需"，第五路的六个实施批次里却完全没有对应的批次——也就是说按现在的计划上线，运营配了"每人限领 1 次"和"秒杀总量 500"，实际会无限超发，且控制台不会告诉他。这份方案的技术密度远高于它的业务成熟度，我建议砍掉 A/B 实验、表达式引擎、多维查表、OpenAPI 生成链这几块，把省下的人力全部压到行项模型 + 行选择原子 + 库存预占 + 周期时间窗上。

### 必须修正（must_fix）

**1. 行项模型（items）必须定为 P0 前置，且五路的 IR / 决策契约 / 表单契约要统一到同一个 items 定义上**

> 把 SpuDiscountRequest 扩成 items[]（lineId/skuId/spuId/storeId/unitPrice/quantity/promotable）作为 B0 批次的第一件事，先于金标集建立；路2 的 BenefitEvalContext 把 baseAmounts 换成 items + 由引擎派生 scope 基数；路4 的 DecisionRequest.items 定义作为唯一真相源，路3 的沙盘 ContextPanel 增加行项表格（复用 DynRowTable）；路5 的 B0 金标 fixture 必须基于新 items 契约录制，不要先录旧的再返工。

**2. 补『行选择与排序』原子（NTH_ITEM / BUNDLE / CHEAPEST_K），并明确它由 Java 算法承载、不进表达式引擎**

> 在 IR 里新增 selector 段：{kind: NONE|NTH_ITEM|CHEAPEST_K|BUNDLE, n, order: PRICE_ASC|PRICE_DESC, repeat, maxTimes, groupBy: SPU|SCOPE}，由 Java 实现（排序 + 分组，不走 Drools 也不走表达式）；base 段增加 UNIT_PRICE 取值；allocation 增加 TARGET_LINE_ONLY。前端在 paramsSchema 里对应新增 control='SELECTOR'，用 Segmented（第几件 / 价低者享折 vs 价高者享折 / 是否循环）三个控件表达，不要暴露 groupBy 这种内部概念。

**3. 补 FIXED_PRICE（一口价）权益形态，秒杀/换购/套餐价靠现有四种 kind 表达不了**

> BenefitKind 增加 FIXED_PRICE；求值时 applied = max(0, unitPrice - fixedPrice)，分摊固定为 TARGET_LINE_ONLY，并强制打 exclusiveTag（默认排他）。同时校验期加一条 fail-closed：FIXED_PRICE 必须配 selector.scope 精确到 SKU，不允许作用在 ORDER 层。

**4. 库存与频次要么本期真做（至少 TOTAL + PER_USER 两个维度的预占-确认），要么在控制台把字段置灰并明示『当前不生效』——不能维持现在这种『有字段、有写入、决策不读』的形态**

> 二选一，本期必须落地其中之一：(A) 真做——新增 activity_quota 表 + 预占接口（RESERVE_THEN_CONFIRM，TTL 15min），由 console 侧提供写通道（decision 只读账号不动，决策命中后由调用方回调 console 预占），秒杀/买赠/发券三类玩法在校验期强制要求配 quota；(B) 不做——控制台的库存/每人限领输入框置灰 + 悬浮提示『本期为声明式，决策不扣减』，创建响应里回 warnings，并在活动详情页顶部挂 warn Banner。绝不允许维持现状。

**5. 周期时间窗（recurrence）+ 时区必须进第一版，只有绝对起止区间撑不起限时折扣/秒杀场次/会员日**

> 活动表新增 timezone(varchar 64, 默认 Asia/Shanghai) 与 recurrence_json(LONGVARCHAR)，结构 {daysOfWeek:[1-7], daysOfMonth:[1-31], timeRanges:[{from:'20:00:00', to:'20:30:00'}]}；决策热路径保留 Instant.now() 判定（不要预过滤进快照包，见路5 的 R6）；发布打包时把未来 N 天展开成绝对窗列表做预筛，热路径只做区间比较。前端在 BasicSection 增加一个 RECURRENCE 控件（星期多选 + 时段行表格）。

**6. 引入优惠层（priceLayer）+ 排他标签，解决『秒杀品不参与店铺满减』『积分抵扣必须在最后一层』这两条硬约束**

> IR 的 stacking 段增加 priceLayer 枚举 + exclusiveTags(该权益打上的标) + excludeIfTagged(遇到这些标就跳过)；合并流水线改成层间串行（前层输出的 residual 作为后层 base），层内才套 MAX/MUTEX/STACK/PRIORITY；层数与层序由业务侧确认后写死在代码里，不给运营配（见 overengineering）。前端表单只暴露一个 Segmented『优惠层：单品 / 店铺 / 平台』和一个开关『本活动商品不参与其他优惠』。

**7. 决策结果必须有落库通道（含分摊明细 allocations），否则退款、开票、客诉查询、财务对账全部无解**

> 决策服务保持只读不变，但决策响应必须带 decisionId + 完整 allocations[]（targetKey/amount/ratio），由调用方（订单系统）落自己的库；同时 console 侧新增一张 decision_record 汇总表，由订单系统在下单成功后异步回写（activityId/version/benefitId/amount/userId/orderId/generation），供活动详情页展示『已发放金额 / 已发放人次』。这张表同时是预算封顶和防刷计数的数据基础。

**8. 运营入口必须是『玩法模板 + 少量参数』，不能让运营直接编 BenefitSpec 的全部原子；硬约束：单个模板对运营暴露的必填字段 ≤ 6，其余进折叠区且必须有默认值**

> 确立两层：PlayTemplate（运营看的，数据行，声明 paramSpec + atomPreset）→ 编译 → BenefitSpec/IR（引擎看的，不对运营暴露）。paramSpec 里每个字段标 tier: BASIC | ADVANCED，BASIC 必填字段数上限 6，前端把 ADVANCED 折叠且默认全部有值；rounding/allocation/residual 这三组根本不进 paramSpec（见 overengineering）。加一条后端启动期断言：任一内置模板的 BASIC 必填字段 > 6 直接启动失败。

**9. 编辑器内嵌『按当前草稿试算』，不要让运营配完跳到 /console/validate 独立页**

> EditorRail 右栏底部内嵌一个试算卡：输入订单金额 + 件数 + 用户标签（三个字段，不要全套上下文），点『试算』调 preview 端点（把草稿的 params 直接编译成临时 IR 求值，不落库、不进代际），返回『命中 / 减免 X 元 / 命中档位』三行结果 + 一个『查看完整链路』跳沙盘。这需要后端提供 POST /console/v1/activities/preview-decision（接受未落库的草稿体）。

**10. 加价购、裂变助力、CPS/返现三类玩法必须明确划出本期范围，并在玩法矩阵和控制台上标注『暂不支持』**

> 在玩法矩阵文档里给这三类明确标 OUT_OF_SCOPE 并写清缺什么能力（两阶段决策契约 / 事件累积写通道 / 异步结算与冲正）；PlayTemplate registry 里不注册这三个模板；ActivityType 的 CPS(3) 枚举位保留但在 capability-dict 里标 availability: NOT_AVAILABLE，控制台的类型选择器不渲染。诚实地少承诺，好过上线后运营发现配不出来。

**11. 活动预算上限（成本封顶）要做成一等公民：预算耗尽自动下线**

> 活动表新增 budget_amount(decimal 12,2) 与 budget_used(decimal 12,2)；决策命中不扣（decision 只读），由上面第 7 条的 decision_record 异步回写累计；console 侧一个定时任务比对 budget_used >= budget_amount 时自动 changeStatus(OFFLINE) 并发告警；活动详情页与列表页显示预算进度条。这条比 A/B 实验重要一个数量级。

**12. 权益表单的下发契约要收敛成一个端点，现在路1/路3/路4 各设计了一套形状不同的**

> 以路4 的 capability-dict 为唯一端点（它同时下发条件字段 + 玩法模板 + 合并策略 + 枚举 + 权限，一次请求解决前端所有字典需求），字段描述子用路3 的 ParamField 形状（control/valueType/visibleWhen/rowSchema 这套已经和现有 ConditionLeaf 同构），模板层语义用路1 的 PlayTemplate（paramSpec + atomPreset + atomBinding）。field-dict 保留为它的投影，加一条 parity 测试锁住老响应字节不变。

**13. 给 attrs / EXTERNAL 两个逃生舱立纪律，否则半年后又是一堆特例字段**

> 写进代码注释与 review checklist 并加自动化断言：(1) 引擎核心代码（evaluator/merger/allocator）禁止出现任何 attrs.get("具名key") 的调用，attrs 只允许被展示层和外部兑付系统读取——加一条 ArchUnit/正则守卫测试；(2) EXTERNAL 型权益强制 amount=0 且不参与任何合并与封顶，只透传；(3) 每次新增一个 BenefitKind 或 BenefitValueMode 必须同时补 ≥2 个真实玩法的配置样例，只有一个用例的形态不准进闭集枚举。

### 路线之间的冲突

- 【行项模型】路1 明确要求 SpuDiscountRequest 扩出行项（每行 unitPrice/quantity/promotable），否则 base=UNIT_PRICE、selector=NTH_ITEM/BUNDLE、allocation 四个原子从入口不可表达；路4 的 DecisionRequest 确实设计了 items[]（lineId/spuId/storeId/quantity/unitPrice）；但路2 的 BenefitEvalContext 只接受 baseAmounts: Map<String,BigDecimal>，把行项彻底抹平成标量，同时它的 BenefitScope 又声明支持 SKU 层级——路2 内部就自相矛盾，且与路1/路4 直接冲突。路5 的 B0 金标集又是基于现有无行项的 SpuDiscountRequest 录制的。四路四个口径。
- 【合并语义的真相源】路2 说 MergePolicy（存 activity_strategy.policy_json，bizLine 级）取代 StackStrategy 成为合并语义的唯一真相源，扁平的 exclusiveGroup + applyOrder；路1 说必须先按 priceLayer 分五层、层间串行、层内才谈 MAX/MUTEX，并且要有 exclusiveTags 排他标签；路4 说按 benefitType 分桶、桶内套各自 StackStrategy、桶间独立全给。三种互不兼容的合并模型，且路2 和路4 都已经把各自的 JSON 契约写死在提案里了。
- 【权益类型是闭集还是开集】路2 定义 BenefitKind 是 sealed 闭集四值（AMOUNT/RATE/ITEM/EXTERNAL），新增模式编译期报错；路1 的 A6 列了 10 种权益形态（含 FIXED_PRICE / FREE_SHIPPING / POINTS_DEDUCT / CASHBACK / ISSUE_COUPON），路2 的四值装不下其中至少五种；路4 的 playTemplate.benefitTypes 又是自由字符串数组（"CASH"/"GIFT"/"COUPON"），完全开集无校验。同一个概念三种基数。
- 【前端权益表单的下发契约】路1 是 PlayTemplate.paramSpec（control + validators + visibleWhen）；路3 是 GET /activity-marketing/benefit-schema 返回 BenefitSchema{activityType, variants[], fields[]}（多了 variant 这一层）；路4 是 GET /console/v1/capability-dict 返回 playTemplates[].paramsSchema[]（没有 variant 层，靠 visibleWhen 表达模式切换）。三套形状不同的表单契约，前端只能实现一套渲染器。
- 【表达式引擎能不能扛住『零代码加玩法』】路4 把 play_template.benefit_expression（QLExpress）作为『加玩法只插一行数据』的最后一块，并把 M5『只插一行模板数据、不改任何 .java/.ts/.vue』定为整个方案的验收标准；但路2 的 EXPRESSION 明确禁循环禁方法调用，路1 明确指出第N件M折需要件级展开+排序、组合购需要凑单背包——路2 自己也把 selector 归给『Java 算法』。路4 的验收标准在任何需要行选择的玩法上必然失败。
- 【API 版本化与前端独立回滚】路4 要求改 deploy/nginx.conf 的 rewrite 去掉写死的版本段，并新增 /console/v1 与 /decision/v2 两组命名空间；路3 假设 /console/validate 路径与全部现有 testid 逐字不变；路5 的 B2 批次把『前端可用 deploy.sh --frontend-only 分钟级独立回滚』作为该批次的核心卖点。但网关 rewrite 一改，前端与网关必须同批发布同批回滚，路5 的独立回滚点直接失效。
- 【库存/频次的优先级】路1 的覆盖矩阵把 A11 配额标成限时秒杀、买赠、定向发券、随机红包、新人首单五个玩法的『●必需』；路4 诚实地标 enforcement: DECLARED_ONLY 并在创建响应里回 warnings；路2 把它整个丢进 open_questions（『本轮是否纳入？与 decision 只读账号正面冲突』）；路5 的六个实施批次（B0 护栏 / B1 权益模型 / B2 前端 / B3 分层引擎 / B4 快照包 / B5 A/B）里没有任何一个批次负责库存。路1 认定必需的东西，路5 的排期里根本不存在。
- 【decision 是否绝对只读】路2 和路4 都把『decision 纯只读、连只读账号、evaluator 是纯函数』当作不可动摇的架构前提；但路1 的 quota RESERVE_THEN_CONFIRM 预占、随机红包预算池扣减、每人限领的跨订单幂等计数，以及我要求补的防刷计数与预算封顶，全都需要写。五路加起来没有一路正面回答『那这些写操作走哪条通道』。
- 【analysis 的诚实度】路3 在 A/B 实验部分承认『决策链路目前零业务指标（D7），metrics 缺失时降级 EmptyState，不画假图表』；但路3 同时把 A/B 实验列为 P4 批次并设计了三个完整页面 + BucketRuler + ExposureChart + VariantCompareTable。明知看不到效果数据还要先做界面，与它自己的『诚实降级』原则冲突。

### 建议砍掉（过度设计）

- 【砍掉整块 A/B 实验界面】路3 的 P4（ExperimentListView / ExperimentEditorView / ExperimentDetailView 三个页面 + BucketRuler 可拖拽标尺 + RampControl + ExposureChart + VariantCompareTable + experimentLogic + useExperimentStore）与路5 的 B5 批次（8-10 人日）。理由：决策链路今天零业务指标，路3 自己承认效果对比只能渲染 EmptyState——做一个看不到效果的 A/B 界面是纯装饰。而且运营现在连『加一个券玩法』都做不到，先做流量分流是本末倒置。保留后端的多代际并存能力（那是回滚原语，有独立价值），砍掉全部 A/B 前端与分流逻辑，省下约 18-20 人日。
- 【砍掉 BenefitValue.EXPRESSION 与 QLExpress 依赖】路2 的 Expression record + ExpressionEngine + AST 白名单校验器（禁方法调用/new/import/循环）+ InstructionSet 预编译缓存 + activity-common 新增 QLExpress 依赖；路4 的 play_template.benefit_expression 同理。理由：既然禁循环禁方法调用，它能表达的就只有四则运算 + 三元，而这些场景已经被 RATE + cap + LADDER 完全覆盖；21 个玩法里找不到一个非它不可的。为一个不存在的需求引入表达式引擎 + 沙箱 + AST 校验器 + 预编译缓存，是本案最大的一块空转。等出现第 3 个真实需要它的玩法再引入。
- 【砍掉 BenefitValue.TABLE 多维查表】路2 的 Table(keyFields, rows, fallback) + 前端要配套做一个多维矩阵编辑器。理由：21 玩法里唯一沾边的是『会员等级价』，而那个用『枚举轴的 LADDER』或一个简单的 enum→值映射就够了。多维查表要运营在控制台维护一张 N 维矩阵，实际没人会用。
- 【砍掉 CapOverflow 三选一与 AllocationBasis 四选一，全部收敛成单一默认写死】路2 的 CapOverflow{PRORATE, TRUNCATE_LAST, DROP_LOWEST} × AllocationBasis{BY_AMOUNT, BY_QUANTITY, EVEN, BY_WEIGHT} = 12 种组合，还要进 MergePolicy 让运营配。理由：运营看不懂『超封顶时是等比缩放还是从最小的开始整条丢弃』，也不会去改。定死 PRORATE + BY_ORIGINAL_PRICE + 最大余数法，写进代码不进 schema、不进表单、不进 policy_json。同理砍掉 SeedSource 三选一（定死 REQUEST_ID）、BandPick 两选一（定死 FIRST_MATCH）、StackBase 两选一（定死 ORIGINAL，折上折等有真需求再开）。
- 【砍掉取整（rounding）与分摊（allocation）的运营可配性】路1 的 A8 把 scale/mode/applyAt 做成活动级参数，路2 的 BenefitLimit 让每条权益各自配 scale + RoundingMode。理由：取整口径是财务全局约定，绝不该让运营逐活动配——一个活动配 HALF_UP、另一个配 DOWN，对账直接崩。定成平台级常量（scale=2, HALF_UP, applyAt=PER_PLAN），从 paramSpec 里彻底移除。分摊同理，只保留内部实现，不暴露任何配置项。
- 【砍掉 OpenAPI 生成链的全套 CI 基建】路4 的 springdoc + contracts maven profile（spring-boot:start/stop + springdoc-openapi-maven-plugin）+ contracts/*.json 入库 + oasdiff 破坏性变更检测 + 5 个 CI 门禁；路5 也在 T10 里叠了 types-drift-check。理由：这是个学习脚手架，为防契约漂移引入的运维复杂度（起服务导 openapi、生成物入库、docker 跑 oasdiff、5 个门禁）远超漂移本身的成本。保留路3 提的运行时契约哨兵脚本（check-contract.mjs，半天成本，拉 4 个端点比对键集合）即可，等真有多个外部调用方再上生成器。
- 【砍掉发布状态机 UI 的一半】路3 的 P2：VersionTimeline + VersionDiff（含『仅看差异』开关 + 条件树逐叶对比）+ PublishDialog（三段式影响面）+ RollbackDialog（输入活动名二次确认）+ lifecycle 六态 + LifecycleBadge + PublishPanel + draftDiff 纯函数 + 草稿 localStorage 自动存档 + DraftRestoreBanner。理由：D10/D11 后端没修之前这些全是骨架；而且运营对『版本 diff』的实际需求远低于『我配的活动为什么没生效』。只保留 LifecycleBadge（六态判定是有价值的，能回答『为什么没生效』）+ 列表和详情的双轨版本展示（线上 v3 / 草稿 v5）。VersionDiff / RollbackDialog / 草稿自动存档全部砍掉，省约 6-8 人日。
- 【砍掉路5 的 T3 万级 seeded RNG 差分测试】理由：它是 B3 分层引擎（Drools → QLExpress）的门禁，而 B3 本身应该延后——性能优化（D1/D2/D3/D6）不是运营痛点，业务覆盖度才是。B3 不做，这套差分测试就没有被测对象。路5 的 B0 金标集（T1）必须保留且必须先做，但 T3 随 B3 一起后置。
- 【砍掉 shared/ui/Drawer、Toggle、Stepper、DataTable 里的 Stepper 与 Drawer】路3 一口气新增 4 个 UI 原语。DataTable（表格卡片化）和 Toggle 有明确复用点，保留；Stepper 是为被砍掉的实验编辑器三步流程造的，Drawer 是为被砍掉的 VersionDiff 造的，随之一起砍。

### 无人负责的空白

- 【退款 / 取消后的优惠冲正】五路一字未提。运营每天要处理退货：退一件商品该退多少优惠（依赖分摊明细）、赠品要不要收回、每人限领的名额是否返还、已发放的券是否作废、返现是否冲正。这是活动上线后运营工作量最大的一块，五路加起来零覆盖。也直接决定了配额语义是 RESERVE_THEN_CONFIRM 还是 DEDUCT_ON_HIT——而这个选择五路都悬着没定。
- 【运费 / 配送费维度】只有路1 在 A4 基数里提了一句 SHIPPING_FEE + 包邮玩法（第 12 条），路2 的 BenefitKind 四值里没有 FREE_SHIPPING，路3 的 control 类型里没有，路4 的三个模板示例里没有，路5 的批次里没有。包邮门槛是电商最基础的玩法之一（本地生活叫配送费减免），且它是独立计价维度（不落在商品行上、不进商品优惠分摊），现有所有结构都装不下。
- 【防刷】五路里『防刷』两个字一次都没出现。真实运营每天面对的是羊毛党：同设备多账号、异常地域集中、下单即退、批量注册薅新人券。至少需要设备维度的频控位（deviceId / IP）、异常行为的规则位、以及命中即记账的通道。而现在的架构（decision 只读 + evaluator 纯函数）结构上排斥任何有状态计数——这个冲突没有一路正面回答。
- 【活动预算 / 成本控制】只有路1 在随机红包那里顺带提了『预算池』。运营侧最硬的约束是『这个活动最多花 50 万，花完自动停』，没有一路把它做成活动级一等公民。没有预算封顶，一个配错的满减一晚上能烧穿季度预算。
- 【决策结果的持久化与对账】路2 明说 evaluator 纯函数不落库，路4 说 decision 只读，路2 在 open_questions 里问了 AllocationEntry 是否落库但无人拍板，路5 的批次里没有。结果是：这一单发了多少优惠、这个活动累计花了多少、某个用户的客诉能不能查到——全部无解。这也是预算封顶、防刷计数、每人限领三件事共同缺失的底座。
- 【控制台上『库存余量 / 已发放量』显示什么】路4 诚实地把 enforcement 标成 DECLARED_ONLY，但没有一路回答：那运营在活动详情页看到的『库存 10000』是什么意思？是配置值还是余量？现在是配置值且永不变化，运营会误以为是余量。这是最容易造成误判的 UI 缺口。
- 【多租户 / 多业务线下的玩法模板治理】路1、路2、路4 各自在 open_questions 里问了同一个问题（模板是平台统一维护还是租户可自定义？租户能否覆盖内置模板的 paramsSchema？），五路加起来没有一路给方案。而 RuleSchemaRegistry.overrides 至今是进程内 ConcurrentHashMap、无写入 API、重启即失——如果权益侧的 BenefitSchemaRegistry 照抄这条路，就是造第二个 stub。路2 提了落库方案，但没解决『同名模板在不同租户行为不同』的治理边界。
- 【运营的学习成本与上手路径】动态 schema 表单 + 玩法模板 + 优惠层 + 互斥组，这套东西对运营是全新的心智模型。五路里只有路1 的 summaryTemplate（一句话摘要）和路3 的 DraftSummary 两个零星点，没有任何一路设计：内置示例活动（一键复制改改就能上）、每个字段的帮助文案与真实数值示例、第一次进编辑器的引导、以及『我配的和我想的是不是一回事』的即时反馈。运营用不起来，整套重构的业务价值就是零。
- 【玩法上线后的效果归因最小闭环】不是指 A/B（那个该砍），是指最基础的『这个活动今天命中了多少次、发了多少钱、转化了多少单』。路3 在 A/B 部分承认决策链路零业务指标，路5 的 B0 埋了技术指标（duration/fallback/candidates）但没有一个业务指标（命中次数/发放金额/按活动维度）。运营看不到活动效果，就没法判断要不要续、要不要加码。这比 A/B 便宜十倍且必要十倍。
