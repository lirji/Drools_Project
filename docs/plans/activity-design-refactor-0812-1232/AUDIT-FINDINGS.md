# 活动引擎结构性审查 · 原始发现与对抗判定

> 生成于 2026-08-12。六个维度并行勘察（每维度最多 6 条发现），再由独立的怀疑派审查者逐条验证。
> 综合结论见 [`FINAL_PLAN.md`](FINAL_PLAN.md)。**本文件是原料，不是结论**——其中相当一部分定性在验证阶段被推翻或降级。

判定含义：`CONFIRMED` 证据与问题均属实、方案可行；`DOWNGRADE` 问题存在但被夸大或方案落地方式有误（以 correction 为准）；`REJECT` 证据不实或违反硬约束。

---

## 求值引擎与权益形态分派

### [DOWNGRADE → P1] #1 权益形态分派被切成 5 处 if/三元链，求值侧兜底按金额原样发

**验证**：逐行核对：BenefitEvaluator L116-193 确为 5 个 if(form==X){...continue;} + L191-192 无条件兜底；L126-137 的「随机必须排在 null guard 之前」注释属实（分派靠行序）；BenefitForm L62-67 of() 与 L70-73 isSupportedUnit() 确是两条手工同步的判别链；ActivityMarketingService L359-420 / L488-517 确是两处独立按形态分叉；logic.ts L44/147-175/210-239 确是三条独立三元链。证据零瑕疵，缺编译期约束这点也成立（Java 21 枚举 switch 表达式不写 default 确实强制穷尽）。降级理由有三：① 今天没有活着的缺陷——形态只有 4 个枚举常量，L191 那条兜底恰好就是 AMOUNT 分支，而「未知单位回落 AMOUNT」是 BenefitForm 类注释里明写的 fail-safe 取向（假阳性类型 1：作者已解释且理由成立），把它当「兜底发错钱」是半错的定性；成本要等到加第七种形态才兑现，而六形态已全部落地、无第七种的需求。② 「没有一种会报错」不实：漏 isSupportedUnit 走的是写入口 throw + 明确文案（fail-closed，最轻的那种）。③ 方案「读写两侧的形态知识从此只有一份」做不到——前端 3 条三元链 + EditorView 的 ~10 条校验分支是 TS，Java 策略共享不到它们（benefit/forms/ 下 6 个 per-form 组件已存在，字段层其实早已拆过）。真正能落地的收益是后端那一半，仍值 P1。

**修正落地方式**：分两步落：第一步只把 computeAmounts 的 5 段 if 改成 `switch (form) { case AMOUNT -> …; case RATIO_ZHE -> …; }` 不写 default——AMOUNT 必须写成显式 arm，别让「未知单位」的 fail-safe 从 BenefitForm.of（有注释、有测试）漂到求值器的最后一行；随机分支的位置敏感性用「AMOUNT arm 内部先判 takeType」表达，不再依赖行序。这一步零行为变更、金标 52 例可直接当门禁。第二步再考虑策略对象承载 validate(req)（ActivityCreateRequest 在 activity-common/domain，console 委派可行）。前端保持独立判别，别在方案里承诺跨语言单一权威。

### [DOWNGRADE → P1] #2 applyLadder→computeAmounts→merge 靠共享可变候选上的 4 个布尔位传状态，重跑必须手工三清

**验证**：证据全部复核属实：ActivityCandidate L66-78 四个默认值（eligible=true / computedAmount=ZERO / amountComputed=false / ladderApplied=false）确是四条隐式契约；BenefitEvaluator L85-87 阶梯只设 computedAmount+ladderApplied、故意不设 amountComputed（覆盖语义靠它，golden 用例 ladderAndRatioTogetherAgreeOnBothPaths 就在钉这个）；L119/L149-152 与 L196-220 notApplicable 注释原文确实自陈「契约写在注释里、却没有落到数据结构上，是这个 bug 的全部成因」；ActivityQueryService L297-303 safeFallback 手工清 3 个字段、L212-219 explain 前后拍快照 diff——一字不差。诊断成立。降 P1 而非 P0 的理由：两个已知危害（0 元幽灵、漏清 ladderApplied）都已修复并被 NotApplicableCandidateTest / 金标钉住，今天没有活着的错钱；而这是全部六条里改动面最大的一条，直接落在金标钉死的那段代码上，需要单独批次而不是 P0 抢修。方案本身不违反任何硬约束（不复活 DRL、不碰随机种子链、不改 5 次查询、不让 decision 写库），走库/快照等价性也不受影响（两条路共用同一个求值器）。

**修正落地方式**：落地时必须保留两件今天挂在候选上的东西，否则会悄悄改变行为：① 淘汰原因——items()（ActivityQueryService L253-267）读的是 c.getRejectReason()，资格阶段与算额阶段共用这一个字段，所以 BenefitOutcome.NotApplicable 必须带 reasonCode+why 并由编排层回填到明细，且 metrics.reject("benefit", …) 这个告警埋点一个都不能丢；② merge 的入选谓词今天是单一的 isEligible，改成 outcome 表后必须是「资格通过 ∧ 有 Payable outcome」，否则被算额淘汰的候选会重新进入 MAX/PRIORITY 竞争（这正是 a0ec639 修掉的那个 bug 的镜像）。建议只改 computeAmounts/merge 的内部协议，applyLadder 先返回 Map<activityId,BigDecimal> 即可，setter 保留（ActivityDrlBuilder 的 ladder DRL 与 12 处测试仍在写它们）。

### [DOWNGRADE → P2] #3 redPackageRangeAmount 一列三种载荷，四处判别规则互不一致

**验证**：行号属实（RandomRangeParser L8-24/L57-75、LadderRangeParser L29-30、ActivityQueryService L396-405、ActivityMarketingService L488-517、logic.ts L147-175），但「互不一致」这个定性经不起验证：三处 Java 判别其实是同一条规则的三次实现——顶层类型先把数组/对象切成不相交两半（LadderRangeParser 非数组直接 return，RandomRangeParser 非对象直接 return null），unit 只用来把「对象」再切成 random/nth。我逐形态推演了冲突面：AMOUNT+random+数组、NTH+数组、RATIO+数组三种脏数据，今天读侧结论与写侧结论一致（分别是 reject / reject / 折扣覆盖阶梯 20 元），没有找到实际分歧。唯一真分歧在前端 logic.ts L162-174（unit=元、takeType=1、range 是对象时判成 ladder/parsed=false，后端判成 fixed），而那处有明确注释解释为「防止一次编辑把原值悄悄清空」——属于假阳性类型 1，且这种值在写入口本来就被拒。所以剩下的是「同一条约定实现了四遍」的重复成本，不是「今天就不一致」。另外「ladderDefs 形状优先让折+阶梯先落档是巧合」也偏重：RATIO_ZHE 的 redPackageAmount 写入口强制非空，L149 的 guard 走不到，阶梯结果必然被覆盖或候选被 notApplicable 淘汰，结论不依赖执行顺序。

**修正落地方式**：方向对（一列多载荷该有单一解析出口），但落点要换：在 activity-common 加一个 `RangePayload parse(BenefitForm, Integer takeType, String json)` 密封返回值，由 ActivityQueryService.ladderDefs / BenefitEvaluator（nth、random）/ ActivityMarketingService.validateRangeColumn 三处共同调用即可。**不要**按方案说的挪到 DecisionDataLoader.flatten 与 DecisionSnapshot.materialize：那会把解析失败的发现时机从「每次决策、按候选 fail-closed 并打 metrics.reject(benefit, bad-random-range)」挪到「快照后台构建时」，那条告警链会断；同时快照要多带一份解析产物，正是 SnapshotParityTest 守的走库/快照等价面上新开一个分歧口子（坑 16 的同款失败形态：不报错、不回退、只有对账才发现）。

### [DOWNGRADE → P2] #4 作用域基数是 private static，六形态里三种执行方式全靠人记

**验证**：行号全部属实（baseAmount L257-267 三档语义、L168-176/L180-189 两处重复 baseCodeOr+baseUnknownOr、nthDiscount L398-409 走第四参、AMOUNT L191-192 不碰作用域、BenefitMath L134-136/L147-148 三参/四参并存）。但三处定性偏了：① capToOrderAmount 用 ctx.getOrderAmount()「与作用域基数不是同一个数」被当成证据——那是刻意的，封顶要保证的是应付金额不为负，按作用域小计封顶反而是改钱；② AMOUNT 不调 baseAmount 不是「忘了」，满减/直减的面额本就与基数无关（CLAUDE.md 坑 17 明确记载修复归属在取数层收窄候选身份，并由 SnapshotParityTest#narrowedBindingStopsPayingOnBothPaths 钉住），把它说成求值层的漏洞留着不修是误读；③ NTH_ZHE 需要的是逐行作用域过滤而不是标量基数，一个 base() 值对象统一不了它，所谓「三套执行方式」里至少两套是语义使然。真正剩下的重复只有 baseCodeOr/baseUnknownOr 那两行、复制了两次。方案里的 `usesScope()` 属于假阳性类型 2：没有任何调用方消费这个布尔，除了「实现接口时必须回答」之外不产生约束，为了模式而模式。

**修正落地方式**：只做最小的那一半：把 `baseAmount + baseCodeOr + baseUnknownOr` 三者合成一个私有 record（如 `ScopedBase(BigDecimal value, String rejectCode, String rejectWhy)`），FIXED_PRICE / RATIO_ZHE 各调一次即可，消掉两处重复的 null 处理组合。不要引入 usesScope() 声明，也不要动 BenefitMath 的三参重载（它只有 13 处测试调用、零生产调用，退休它是纯改测试）。AMOUNT 与取数层收窄的分工按 CLAUDE.md 原样保留——把它挪进求值层就是改钱。

### [DOWNGRADE → P2] #5 merge 里 STACK 与单选各自 return、各自封顶，没有共同出口

**验证**：结构描述属实：L319-334 STACK 分支自己累加/选主/封顶/return，L336-349 单选分支 hit(winner) 后再封顶一次，L303-309 类注释确实自陈「删掉三参重载就是怕新调用点绕过封顶」而方法体内部恰好有两个出口。ActivityRuleResult L26/L40-46 也核对无误：hitAmount 默认 ZERO、hit() 会 append 一条 BenefitOutcome 而 STACK 分支不走 hit()，两分支在 benefits 列表上确实不对称。但两处夸大：① L316-317 那个「第三出口跳过封顶」不是 fail-open——eligible 为空时 hitAmount 还是初始 ZERO，没有可被截断的东西，说它「靠默认值兜住属巧合」站不住；② benefits 列表我全仓库 grep 过，getBenefits() 零消费方，纯前瞻结构，不对称今天没有任何影响面。加第四种合并策略的成本是真的，但 StackStrategy 是 4 值的外部契约（响应体与指标标签都带它），新增频率极低。

**修正落地方式**：方向对（封顶必须只有一个物理出口），但别引入 MergePolicy 接口 + policyOf 映射：4 个枚举值只对应 2 种行为，为两个分支套策略+模板是假阳性类型 2。正确落地是 merge 内部单出口重排——先算出 (hitId, hitName, amount) 三元组（STACK 走累加+pickByPriority，单选走 pickByAmount/pickByPriority），再统一写 result、统一 capToOrderAmount、统一 return，方法体从三个 return 收敛成一个。顺带在 STACK 分支补 BenefitOutcome 让 benefits 对称（零风险，今天无消费方）。等真出现第三种合并行为时再抽 policy。

### [CONFIRMED → P2] #6 DistributionMode 的 fromCode 在热路径不可用，读侧另写私有判别、写侧手写第三份比较

**验证**：逐行属实：DistributionMode L25-31 fromCode 未知 code 抛 IllegalArgumentException；BenefitEvaluator L414-426 distributionOf 是 private static、注释明写「不能直接用 fromCode——那会让一条脏数据打断整批候选的算额」；ActivityMarketingService L505-506 是手写等值比较；logic.ts L158 是裸 `=== 2`。与 BenefitForm.of（fail-safe 公开在枚举上）的不对称也确实存在，方案（新增 of() 宽容入口 + fromCode 改名 strict）方向正确、代价约 5 行、不触碰任何硬约束（不改随机金额本身，只改 takeType 的判别归属；语义与今天 distributionOf 逐字节一致，金标不动）。两点要说清但不改结论：① 我 grep 了全仓库，DistributionMode.fromCode **零调用方**（其余 fromCode 命中全是 StackStrategy/ActivityType/RuleLogic 等其它枚举），所以「公开 API 用了就出事」是潜在而非现存危害；② 「三套解释规则」略有膨胀——写侧那行是等值比较不是第三套解释，实际是「读侧宽容 + 写侧严格 + 一段死代码」。因已是最低档 P2 且推荐动作完全正确，维持 CONFIRMED。

**修正落地方式**：落地时顺手确认 fromCode 无调用方后直接删掉或标 @Deprecated 私有化，比改名成 fromCodeStrict 更干净（没有调用方要保护）。前端 `=== 2` 抽常量可以做，但别把它算进「读写单一权威」的收益里——TS 侧仍是独立一份。BenefitMath 三参重载不要在这一条里顺手退休（见 #4，纯改测试）。

---

## 写平面：校验、落库与状态流转

### [DOWNGRADE → P1] #1 [P0] 六形态无单一表示：(unit, takeType, rangeAmount JSON 形状) 三元组在写侧/读侧/前端各拼一遍，三处无同步机制

**验证**：行号全对：ActivityMarketingService.java:359-420 validateBenefitForm（FIXED_PRICE 另判 inventory、NTH_ZHE 另判折数域）、:488-531 validateRangeColumn（:505-506 引入 takeType 第三判别位）、BenefitEvaluator.java:116-194 computeAmounts（顺序确为 随机→null guard→NTH→FIXED_PRICE→RATIO→兜底 AMOUNT）、:420-426 distributionOf、BenefitForm.java:62-73 只有 4 个常量（ladder/random 确实不在枚举里）、frontend/src/console/logic.ts:147-160 benefitFormOf + :210-232 benefitRequestFields。三点夸大：①「没有任何编译期或测试期的同步机制」不实——frontend/e2e/e2e-validation.mjs:134-171 与 :578 会用写平面 POST /activity-marketing/create 真建并发布 12 个玩法，再对决策结果逐条断言金额（:597-609），这正是一条跨写侧/读侧/前端的形态同步门禁；读侧另有 DecisionGoldenSetTest 52 例。②「漏读侧会把打8折当减8元」引用的 BenefitEvaluator.java:178-179 是在描述一个已经修掉的历史 bug，而未知单位回落 AMOUNT 是 BenefitForm.java:13-15 明确论证过的取向（历史数据行为字节不变），不是疏漏。③ 前端那份判别是 TS，Java sealed 类型换不掉它——refactor 后仍剩 logic.ts + playbooks.ts 两处要手工同步，「9 处收敛成 1 处」做不到。

**修正落地方式**：方向对但收益要按实际范围重估：只收敛 Java 侧四个 dispatch 点（validateBenefitForm / validateRangeColumn / computeAmounts / distributionOf）到一个分类器，且必须原样保留三条语义：未知单位→AMOUNT 的宽容回落（不能因为 sealed 就改成抛异常）、computeAmounts 里「随机分支排在 redPackageAmount==null guard 之前」、「阶梯未落档→notApplicable 而非 0 元候选」。ladder/random 不是 BenefitForm 常量是刻意的（它们由 takeType 与 range JSON 形状决定），新建 sealed 层次等于新增一套类型，务必薄；落地后必须 DecisionGoldenSetTest 52 例 + e2e:validate 全绿才算等价。前端那份判别只能靠共享 fixture/契约测试对齐，不要在计划里承诺它会被消除。

### [DOWNGRADE → P1] #2 [P0] 没有「活动」聚合，「当前是哪一版」在五处各解释一次，且分两套互斥定义（最高未删 vs 最高 ONLINE），null 是第三义

**验证**：五处引用全部核实无误：ActivityMarketingService.java:132-134（编辑基线取 findFirstByActivityIdAndIsDelOrderByVersionDesc）、:223-227（changeStatus version==null 同上）、:334-336（getDetail 同上）、:859-864 + :967-974（claim 走 currentOnlineVersion = 最高 ONLINE）、DecisionDataLoader.java:276-291（先按 ONLINE 过滤再取最高版，注释明确顺序不能反）；:269-275 的批量下线事故 javadoc 与 :329-331 的 list() 返回全部版本行也属实。夸大在「今天仍有一处没修 + 没有任何测试覆盖」：ListView.vue:297-303 有专门的 versionMismatch computed，:600 有 warn Banner 提示「详情取的是最高版、可能不是你点的那一行」，benchModel.ts:78-84 的 mergeRows 也刻意用 ONLINE 行做 primary；且 getDetail 的另一个调用方 EditorView.vue:421 要的恰恰就是最高版草稿（编辑就该编草稿），所以 getDetail 取最高版不是纯粹的 bug，只是同一个出口服务了两种意图。真正未覆盖的只是那条 UI 提示没有测试。

**修正落地方式**：保留「值对象 + 具名查询」方向，但落地要小：把已存在的私有 currentOnlineVersion(:967-974) 提升成公开具名出口，另加一个 latestDraftVersion()，让 create/changeStatus/getDetail/claim 各自显式选一个，删掉 Integer version==null 的隐式第三义。不要顺手把 getDetail 改成返回线上版——EditorView 依赖它返回草稿，改了会让编辑器加载到不可编辑的旧配置；要的话是给 detail 加一个显式的 servingVersion 字段，并给 ListView 那条 versionMismatch 提示补一个前端单测。

### [DOWNGRADE → P1] #3 [P1] create 用 isEdit 布尔压三种动作；changeStatus 无迁移合法性表，副作用散在三段 if

**验证**：状态机那一半全部属实：ActivityMarketingService.java:229-230 只做 fromCode 不判迁移；ActivityStatus.java:13 的 PENDING_EFFECT(3) 是 fromCode 认可的合法码，而全 main 源码 grep 只有这一处定义、无任何生产者或消费者；:232-234（四眼）与 :237-246（退役旧线上版）确实只在 target==ONLINE 下跑；:135-137 拒绝编辑 OFFLINE 与 :247 无条件放行 OFFLINE→ONLINE 的不对称属实；Controller:101-104 的 bulk-status 不校验 targetStatus 且一律 200 属实。两处细节不准：① 「控制台显示待生效」不成立——benchModel.ts:51-57 的 deriveState 把非 ONLINE/OFFLINE 一律归为 draft，状态 3 在工作台上显示成草稿；② 不是「决策侧当它下线且无人知道」，:253-254 对任何状态变化都 bump 代际，所以传播是有的，只是这一版永远进不了任何读路径。create 那一半我判为过度设计：:126-164 是 3 个分支约 35 行，拆成两个命令对象 + 模板方法后，读者要跨两个实现类才能拼回「编辑时线上版保留、草稿被顶掉」这条唯一重要的语义，理解成本上升而非下降。

**修正落地方式**：只做状态机这一半：在 changeStatus 里加一张允许迁移表（from×to → 副作用），把四眼与退役旧线上版挂成 ONLINE 迁移的 action；同时在写入口直接拒 targetStatus=3（PENDING_EFFECT 无生产者也无消费者，与其立法不如封口），并让 bulk-status 在进循环前先校验一次 targetStatus，避免几十条各自失败。create 的 isEdit 分支保持原样，最多把 isEdit 那段抽成一个具名私有方法 resolveTargetVersion(...)；不要引入命令对象 + 模板方法。改动必须保住 OfflinePropagationTest 与 bulk 相关用例。

### [DOWNGRADE → P2] #4 [P1] 六段手写 setter 重抄五个脚手架字段；同一实体两处装配字段集不同；七个实体逐字重复 tenantId/isDel/时间戳且无 @MappedSuperclass

**验证**：重复本身属实：saveManage/saveRule/saveCondition/saveGifts/saveManualBindings/savePoolRefs 六段（:577-711）确实各抄一遍，全仓 grep 到 66 处 setIsDel/setCreatedStime/setModifiedStime，且 grep MappedSuperclass 零命中。但核心伤害论证不成立：七个实体的 is_del / version / created_stime / modified_stime 全部是 @Column(nullable=false)（逐个核过 ActivityManageEntity:91/102/105、ActivityRuleEntity:68/71/74、ActivityConditionEntity:41/59/62、ActivityGiftEntity:37/58/61、ActivitySpuBindingEntity:45/60/63、PoolRefEntity:37/43/46、ActivityStrategyEntity:49/52/55），漏 setIsDel(0) 会在 flush 时被 Hibernate 的 nullability 检查或 DB NOT NULL 直接打回，是响亮失败，不是「存进去了、控制台看得见、决策一分钱不发」的静默失败——finding 描述的那个故障模式在当前 schema 下不可能发生。另一条证据也误读：ActivityPoolMatchService.java:135-148 填 poolId 而写平面 :678-694 不填，是因为前者 bindSource=1（商品池圈选）后者 bindSource=0（手动绑定，本就没有池），属设计而非漂移。

**修正落地方式**：按 P2 的纯整洁度做即可：加两层 @MappedSuperclass（租户+双时间戳 / 再加 is_del，因为 ActivityGrantEntity 确无 is_del 列），把七个实体的重复字段与注释收上去。不要造「belongTo 装配器让漏填不可表达」——数据库的 NOT NULL 已经在做这件事，再加一层流式 API 只是把同样的强制搬到更远的地方；六段 save* 里的显式 setter 保留可读性更好，真嫌重复就抽一个 stamp(entity, activityId, version, now) 的三行工具方法。

### [DOWNGRADE → P2] #5 [P1] 977 行 / 15 依赖的 @Service 同时是配置写入口、代际传播点、发放台账与幂等登记；两套事务语义混在一类，且 updateByVersion→create 自调用让 create 的 @Transactional 失效

**验证**：体量与同居事实属实：文件 977 行、构造器 15 参（:76-106）、claim/release/grantsOfOrder 在 :848-964 与 create/changeStatus 同类、objectMapper 与 AtomicInteger seq 并列于 :69-70。但两条最刺人的证据都站不住：① :211 的 updateByVersion 自身就标了 @Transactional(rollbackFor = Exception.class)，与 create(:110) 完全同款，所以 :216 的自调用虽然绕过代理，这条路径上仍有一个语义完全相同的事务在跑，「create 的事务注解今天根本没生效」在行为上是零影响；② claimInventory(:848) 的裸 @Transactional 与 rollbackFor=Exception 的差别只在受检异常上，而该方法体内没有任何受检异常来源（业务失败一律 return ok=false，见 :853-921），实际等价。③「console-write-authority 的保护面是按方法枚举的」不实——ActivityResourceServerConfig.java:62-69 是按 HTTP 路径枚举（/create、/*/status、/bulk-status、/*/claim、/*/release），拆 service 根本不碰它。④「每次改都要重跑 244 例」与拆不拆类无关，surefire 按模块跑。

**修正落地方式**：当作 P2 的内聚整理来做：把 claim/release/grantsOfOrder + ClaimResult 搬进独立的 GrantService（它与 create/changeStatus 零共享状态，只共用 blankToNull 与 NOT_DEL 两个常量），ActivityMarketingService 保留同名委派方法以免动 controller。搬迁时注意 GrantLedgerTest 是直接调 service 方法并断言 .ok()/.reason() 的，保留委派签名即可零改测试；顺手把 :216 的自调用改成不经代理也无歧义的私有实现方法。不要为「两套 rollbackFor」立项，统一成一种即可，别写成事故叙事。

### [DOWNGRADE → P2] #6 [P2] claim/release 靠 boolean ok + 中文 reason 承载结果分类，controller 只能按布尔映射状态码，四种语义压成同一个码；release 空 orderId 返 404 导致客户端放弃冲正

**验证**：证据逐条核实无误：ClaimResult 七字段在 :806-812；七个失败点分别在 :853/855/862/868/887/892/921，全靠 reason 中文串区分；Controller:123 claim ok?200:409、:136 release ok?200:404，:110-114 的 javadoc 确实自陈「用 409 是为了让调用方靠状态码分流」；service:941-943 空 orderId 走 ok=false 因而返回 404 也属实。reason 已成事实 API 这点还有旁证：GrantLedgerTest.java:189 直接断言 reason().contains("每人限领")。严重度 P2 判得对，但落地方案有硬伤：把 ClaimResult 换成 sealed 变体层次，不同变体的字段集不同，Jackson 序列化出来的 JSON 必然变形，与「响应体一个字节不变」自相矛盾；且现有测试是对 ClaimResult 直接调 .ok()/.reason() 的，「全部现有测试一行不改」也做不到。另外 release 的杀伤面比描述窄：orderId 是 @RequestParam(required=true)，完全不传时 Spring 直接 400，只有传空串才会掉进 404 那一支。

**修正落地方式**：保持 ClaimResult 这一个 record 不换类型，只加一个 failure kind 枚举字段（BAD_REQUEST / NOT_FOUND / OUT_OF_STOCK / PER_USER_LIMIT / …），ok/reason 原样保留以维持响应体与既有断言；controller 改成按 kind 映射 400/404/409（release 的缺参与空串统一 400、真找不到发放记录才 404、库存与限领是 409）。若担心新增字段影响契约，把 kind 标 @JsonIgnore 只用于服务端分流。不要引入 sealed 结果层次。

---

## 决策编排与响应契约

### [DOWNGRADE → P2] #1 [P0] 「资格→阶梯→算额→合并」管线在 spuDiscountInternal 与 safeFallback 各写一遍，第二遍靠手工 reset 循环清理计算态

**验证**：证据行号全部属实：ActivityQueryService.java:206-221 主管线、294-320 safeFallback、297-303 reset 三字段、a0ec639 在本文件只加了 setLadderApplied(false) 那 2 行（我 git show 过 diff，逐字符对上）。但 P0 的伤害论证站不住——那个 reset 循环在今天的两条调用路径上都是可证明的空操作：① engine-disabled 路径（196-202）在 applyLadder/computeAmounts 之前就进 safeFallback，全部候选还是初值（computedAmount=ZERO / amountComputed=false / ladderApplied=false），reset 无事可做；② empty-decision 路径（234-236）只有在 merge 返回空时才走到，而我读了 BenefitEvaluator.merge:311-349 与 pickByAmount:475-481 / pickByPriority:484-493——只要 eligible 非空，winner 必非 null、hitActivityId 必被 setter 填上，主路径 222 行的闸门就直接返回了。也就是说 safeFallback 只在 eligible 集合为空时才被调用，而 reset 循环第一行 `if (!c.isEligible()) continue` 恰好把所有候选跳过。applyLadder:83 与 computeAmounts:118 同样跳过 ineligible。结论：漏掉某个计算态字段的 reset 在当前代码里不会改变任何一分钱，a0ec639 那 2 行是纯防御性补丁，不是「已经真实踩过一次的坑」。剩下的真问题只有『三步调用重复一遍 + 顺序不变量没被结构固化』，属于可读性/维护性

**修正落地方式**：方案 (b) 抽 BenefitPipeline（或就一个 private 方法把 applyLadder→computeAmounts→merge 三行包起来）方向正确、成本极低，可以做；顺序不变量用管线对象固化的论证也成立。方案 (a) 的『反射断言字段清单 ⊆ reset 实现』要砍掉：它需要额外引入标注哪些字段算计算态的机制（ActivityCandidate 里配置态与计算态平铺，反射分不出 activityId 与 ladderApplied），而它守的那条路径今天根本不产生副作用——等于给一段无效代码加一个脆的元测试。正确落地是把三字段清理收成 ActivityCandidate.resetComputation()（一处可改），并在 safeFallback 上写明『此处 eligible 恒空、reset 是对未来调用点的防御』；不要为它新增反射测试。改动务必保持 merge 入参与顺序不变（金标集 52 例钉在这条链上）

### [DOWNGRADE → P1] #2 [P0] discount/gifts/addon 三通道各手抄一遍骨架，横切能力（计时/候选数/命中/金额/回退/审计/decisionId）靠复制粘贴对齐，addon 通道几乎没埋点

**验证**：骨架重复与埋点漂移属实：ActivityQueryService.java:176-178/191/198（discount）、334-336/342/345（gifts）、AddOnPurchaseService.java:95-104（addon）三段确实同形；AddOnPurchaseService.java:39-42 构造器确实没有 DecisionMetrics，物理上打不了点；110-119 那条『打在唯一出口』的自立规矩与 353-355/368-369 把 metrics.hit 打在两个内部分支里，确实自相矛盾（虽然当前两个分支合起来覆盖完整，是结构脆弱不是当前少计）；auditLog 唯一调用点在 120 行写死 SCENE_DISCOUNT，我 grep 全仓确认只有这一处。但 8/6/1.5 这个覆盖度表把 addon 低估了：DecisionDataLoader.load:136 与 153 无条件打 metrics.decisionSource(type.name(), snapshot|db)，addon 走同一个 loader，所以『快照回退在监控上完全不存在』这句是错的——source 维度三通道都有；DecisionEligibilityService.applyJava:109-110 还给 addon 打了 fallback(condition-tree-unavailable) 与 reject。addon 真正缺的是 duration / candidates / hit / amount / decisionId / audit。另外『加价购是扣真库存的通道』有偷换：扣库存的是 console 写平面的 claim（ActivityMarketingService），AddOnPurchaseService 只报价、决策服务连只读账号，报价通道无埋点是可观测缺口，不是发钱/发货风险

**修正落地方式**：先做机械修复、别一上来抽基类：① 给 AddOnPurchaseService 注入 DecisionMetrics，用 metrics.timeDecision(SCENE_ADDON, ...) 包住 options/quote，补 candidates/hit（quote 命中时）——这是 20 行以内、零语义风险的改动，拿回今天缺的那 80%；② 把 gifts 的 metrics.hit 收到 buyAndGetGifts 出口一处（注意语义：买赠没有单一赢家，出口要按 GiftView 里 gifts 的来源活动去重计数，不能照抄 discount 的 hitActivityId 写法，否则会改变计数口径）。抽象层面建议只抽一个横切埋点 helper（记录器/装饰器），不要抽 abstract DecisionChannel + final decide()：addon 是两阶段、quote() 自己再调 options()（AddOnPurchaseService.java:142），硬塞进 decide() 模板会出现『一次 quote 触发两层 decide 计时』这种伪指标，属于为模式而模式

### [DOWNGRADE → P2] #3 [P1] 出参 record 分量装配无单一权威：DiscountView 多构造点 + 3 个全分量重列 helper；4 个兼容构造悄悄填 DecisionProvenance.db()

**验证**：事实基本属实，我逐条 grep 验证：GiftView 四参（ActivityQueryService.java:479-482）全仓 0 调用方（生产与测试都没有）；AddOnQuote 五参（67-71）同样 0 调用方；AddOnQuote 六参（73-77）只有 ActivityMarketingAddOnAliasTest:99/128 两个测试用；AddOnOptions 两参（51-54）只有该测试 76 行用；生产三处 AddOnQuote（138/147/154）与三处 GiftView（338/356/371）确实都走全参。作者在 436-439 自陈 helper 是全分量重列式。但夸大之处有三：① 『6 个构造点』数不准——真正的 new DiscountView 只有 224、316、450、455、460 五处，其中 237 行是 withTraces().withMode() 不是构造点；② Materials 三参（DecisionDataLoader.java:110-120）在生产里有 4 个调用方（189/194/213/218，全在 loadFromDb），且作者写明缺省 db() 是『保守且真实』的取值，属于已解释且理由成立的设计，finding 自己也放过了它；③ 183 行硬编码 "MAX" 与 resolveStrategy 今天不会分歧，而且我读了 DecisionDataLoader.java:247-258：候选为空时 bizLine 为 null，走 resolveStrategy 会白发一次 strategyRepo 查询，硬编码在这条路径上是有实际理由的（只是没写注释）。综合下来这是纯维护性隐患，无当前缺陷，够不上 P1

**修正落地方式**：把便宜且明确正确的那一半先做：删掉 0 调用方的 GiftView 四参与 AddOnQuote 五参；AddOnOptions 两参 / AddOnQuote 六参改由测试显式传 DecisionProvenance.db()（正好让测试声明自己在测哪条路）；Materials 三参保留但改名 Materials.fromDb(...)，把『我确实走了库』从省略变显式。DiscountView 的 DecisionOutcome + 单一 assembler 是可选项，做的话务必：分量顺序与 JSON 字段名逐字节不变（前端 ValidateView.vue:200-212 的对拍表按字段名读 hit/hitActivityId/hitAmount/hitVersion/clamped/items），并保留 183 行不查库的空候选出口（改成 resolveStrategy 会多打一次 DB）

### [CONFIRMED] #4 [P1] 档位用裸 boolean explain 穿四层，且 ActivityQueryService 与 AddOnPurchaseService 的无参重载默认值相反（false vs true）

**验证**：全部行号复核属实：ActivityQueryService.java:102-104 与 324-326 无参重载 → false；AddOnPurchaseService.java:89-91 与 130-132 无参重载 → true；87 行注释确实是上一次『写死 true 导致资格淘汰明细随热路径外泄』的墓碑。调用点分布也对：console ActivityMarketingController 的 spu-discount / gifts 显式传 true，而 addon/options（约 176 行）与 addon/quote（约 187 行）用默认值；decision DecisionPlaneController 的 addon/options（122）与 addon/quote（136）显式传 false，而 spu-discount（110）与 gifts（143）用默认值——6 个入口里确有 4 个靠『默认值恰好对着自己这一侧』才安全，而两个默认值方向相反。这是同一个包里两个服务对同一概念给出相反缺省，读者无法本地推理，属于真实的 API 设计缺陷而不是纯口味问题

**修正落地方式**：载重的部分是**删掉无参重载**、强制每个入口表态（4 个 controller 入口 + ActivityQuerySafetyFallbackTest/AddOnPurchaseTest 若干桩要跟着改），这一步零行为变化、编译器兜底。换 DecisionMode 枚举可以顺手做（比 boolean 在调用点可读），但不要按 finding 说的现在就铺 none/structural-only/full-explain 三档——那是没有需求依据的预留，且 explain 目前只控制 traces 生成，三档会引入『结构性 trace 到底含哪些』的新语义争议。枚举只放 HOT_PATH / EXPLAIN 两个常量即可

### [DOWNGRADE → P2] #5 [P1] 决策留痕是编排方法里手工拼的单行 JSON（引号责任分裂、转义只处理双引号），且只挂在红包通道，买赠有 decisionId 无日志、加价购连 decisionId 都没有

**验证**：事实复核属实：140-168 是手工 StringBuilder + 15 占位符模板；165 行 hitActivityId 的引号确实在实参里而 scene/strategy/mode 的在模板里；151-152 转义只 replace("\"", "'")；120 行是 auditLog 全仓唯一调用点（grep 确认）；333 行买赠生成 decisionId 却全方法无 audit；AddOnOptions/AddOnQuote 两个 record 确实没有 decisionId 分量。但两处夸大：① 引号约定不一致其实有一个成立的理由——hitActivityId 可为 null，引号放实参里才能输出裸 null 而不是 "null"，而 scene/strategy/mode 在所有出口都非 null（miss() 也传 strategy.name()）；这是未写注释的取舍，不是随手写的分裂。② 转义不全目前不可触发：reject 原因全是代码里的固定中文字面量（BenefitEvaluator.notApplicable 拼的是常量、DecisionEligibilityService 是『不满足资格条件』/『资格条件不可判定』），不含反斜杠或换行；而 userId 是 Long（SpuDiscountRequest record 第 2 分量），不会产生非法 JSON。所以是latent 隐患。③ 『审计只覆盖一个通道』是 CLAUDE.md 已明确记载并接受的已知落差，不是被忽视的问题

**修正落地方式**：方案方向对，但落地要改两点：(1) **不要换 Jackson**——作者在 137-138 行明确写了『格式刻意是单行 JSON…热路径开销是一次字符串拼接，不做序列化框架调用』，这属于已解释且理由成立的决策；正确做法是把拼装搬进 DecisionAuditor 组件后，让引号与转义只有一处实现（一个 quoteOrNull(String) + escape 覆盖 \\ 与控制字符），保持手工写法。(2) 载重的收益在**扩通道**而不是重写序列化：给 buyAndGetGifts 出口加 audit 调用、给 AddOnOptions/AddOnQuote 加 decisionId 分量并落日志。加 decisionId 是纯增量、不影响前端对拍（ValidateView.vue:200-212 的 diff 表是白名单字段，不会因新字段飘红）。审计仍只落日志、绝不落库（decision 只读账号边界不动）

### [CONFIRMED → P2] #6 [P2] engineMode(boolean) 一个字符串在三个出口表达三件事，且同时是 HTTP 契约、timer 分桶标签与审计字段；类里还留着两个 @SuppressWarnings("unused") 的死开关

**验证**：逐条复核属实：411 行 `engine ? "rule-engine" : "legacy"`；183 行无候选 → legacy；196-202 开关关闭 → legacy；237 行真正的 empty-decision 回退反而 withMode(engineMode(true)) 改回 rule-engine；107-108 mode 确实是 DecisionMetrics.timeDecision 的唯一业务分桶标签（DecisionMetrics.java:124-134 把它 tag 进 activity.decision.duration）；166 行 mode 进审计；407-410 三句免责注释；62-76 两个 @Value + @SuppressWarnings 的死字段代码里确实无读取点。所以『duration 的 mode 维度实际约等于有没有候选』这个判断成立，语义超载是真的

**修正落地方式**：(a) 换 EngineMode 枚举、code() 产出同样字符串 —— 可做，但必须逐字节不变（前端 types.ts 有 mode 字段、e2e 与面板都读它）。(c) 删死字段 + 启动期对退役 key 打 WARN —— 收益最高、风险最低，优先做。(b) 要收窄：回退**已经**是可观测的——DecisionMetrics.fallback(scene, reason) 带 reason 标签（engine-disabled / empty-decision / condition-tree-unavailable），CLAUDE.md 说的『回退率是头号告警项』读的就是这条 counter，不是无处可查。所以不要为此给已有的 duration timer 增加标签（会让现有 Prometheus 序列分裂、面板查询失配）；真想做就只在**响应出参**上纯增量加一个 fallbackReason 分量供验证页展示，指标侧维持现状

---

## 领域模型与不变量表达

### [DOWNGRADE → P1] #1 权益形态无类型：六形态行为散在 BenefitEvaluator if-链 + 两个 validate 方法，无编译期穷尽

**验证**：证据逐条属实：BenefitEvaluator.java:116-193 确为 if-链，191-192 是裸落 default（把 redPackageAmount 当元发）；129 的随机分支确在 149 的 null guard 之前，注释三段解释顺序契约；ActivityMarketingService.java:366 `if (form != BenefitForm.RATIO_ZHE)` 与 367/370 的金额护栏、488-517 validateRangeColumn 末尾 514 落阶梯解析，都对得上；`grep 'switch (form)'` 零命中，而 ConditionTreeEvaluator:109 确是穷尽 switch 表达式。但降级两点：① 伤害全是「加第七种形态」的未来成本，当前无 bug，也没有任何金标/对拍在漏；② 方案落地有实质错误——BenefitForm 是 4 值枚举（AMOUNT/RATIO_ZHE/FIXED_PRICE/NTH_ZHE），所谓「六形态」里的随机（redPackageTakeType 驱动，129 行与 AMOUNT 复合判别）和阶梯（redPackageRangeAmount 驱动，applyLadder 里另算、且刻意不设 amountComputed）根本不是 BenefitForm 的取值，「注册表键 BenefitForm」给不出这两个槽位；而且枚举本来就是密封的，不需要密封接口。

**修正落地方式**：分两步、别做注册表。第一步（收益 80%）：把 computeAmounts 循环体里 NTH_ZHE/FIXED_PRICE/RATIO_ZHE/AMOUNT 那段改成一个 **switch 表达式**（arrow switch 语句对枚举常量并不强制穷尽，必须是表达式或模式 switch 才会编译报错），每支 yield 一个小 record(金额, 失败码, 失败文案)，由循环体统一 setComputedAmount/notApplicable；**两道横切 guard 必须留在 switch 之外并保持原顺序**——`AMOUNT && RANDOM_AMOUNT` 分支在前、`redPackageAmount == null → ladderApplied` 判定在后且对所有形态生效，任何把它们塞进形态分支的写法都会改变随机与阶梯的发放结果。改完必须跑 DecisionGoldenSetTest(52 例) + SnapshotParityTest + NotApplicableCandidateTest#legitimateZeroSurvives。第二步（可选）：若要收敛写侧校验，dispatch 键要用「形态 + 是否随机」的复合判别，不能只用 BenefitForm；ActivityCreateRequest 在 activity-common，物理上可下沉，但 MAX_AMOUNT 等阈值语义一个字不能动。

### [CONFIRMED] #2 ActivityCandidate 把配置与本轮计算态焊死，逼出 19 分量影子类 CandidateTemplate + 两条手写装配路径 + 注释里的清字段契约

**验证**：逐条核实：ActivityCandidate.java:16-79 确为 19 个配置字段与 6 个计算态字段（eligible/rejectReason/computedAmount/amountComputed/ladderApplied/gifts）同居；DecisionSnapshot.java:151-157 类注释明写「不能直接复用 ActivityCandidate」并复制出 CandidateTemplate（数了确为 19 个位置参数，DecisionSnapshotBuilder:117-128 那次调用确有连续 8 个 Integer/String 实参可换位而不编译失败）；DecisionSnapshot.java:194 那行 setRedPackageMaxDiscount 上方的注释「漏拷这一行 = 快照不封顶、DB 封顶」是一次已发生的漂移，与坑 16 的 scopedSpuIds 是同一条缝的第二次；DecisionDataLoader.java:322-352 flatten 与 toCandidate 确是同一份映射的两份手写。ActivityQueryService:297-303 safeFallback 手工清三个字段、且注释说明 eligible/rejectReason 故意不清，属实。死字段也核实了：`grep getExtraConfigType|getExtraDataJson` 全仓库只有 ActivityCandidate 自己的 getter/setter；`getBenefitForm()` 同样零调用方，它 132 行「DRL 的 LHS 用它做判别」的注释已过期——buildGiftDrl 的 LHS 只碰 eligible/gifts。方案在这个仓库可行：OfferSpec 需额外携带 CandidateTemplate 独有的 startTime/endTime（DecisionSnapshot:137 的时间窗过滤用），走库侧从 ActivityManageEntity 取得到，填了也不改行为（走库时间窗在 SQL 里）；scopedSpuIds 是逐请求交集，留在候选侧是对的；gifts 若下沉到 spec，ActivityCandidate 必须保留委托 getGifts()，否则买赠 DRL 的 `gifts.size() > 0` 会失配。落地后必须由 SnapshotParityTest（含 narrowedBindingStopsPayingOnBothPaths）+ 金标 52 例把等价性钉住。

### [DOWNGRADE → P2] #3 决策属性键是散落的字符串字面量，randomSeedSpu/orderLines/userId 三个最要命的键不在唯一守卫覆盖内，重命名写侧全绿

**验证**：事实全部核实：写侧 DecisionEligibilityService.java:56-74 共 9 个字面量（含 73 行 randomSeedSpu、74 行 orderLines）；读侧 ActivityRuleContext:94/104/120 与 BenefitEvaluator:449-453（指纹第三段 ctx.textAttr("randomSeedSpu")）；白名单 RuleSchemaRegistry:104-126 只含 orderAmount/quantity/userDistrictId/userTags/spuId/storeId 六个，确实不含那三个键；DecisionContextFieldsTest 只断言「白名单 ⊆ 写侧键」，RandomAmountTest:54 自己 putAttr 旧键、SpuIdConditionCompatTest:104 断言的是 get 返回 null——所以「只改写侧键名」确实无一测试变红，随机红包会全量重抽。降级理由：这不是领域模型缺陷而是**测试守卫缺口**，且触发条件是一次特定的手工改名，而 73 行头上恰好有三行注释专门警告不许动这条种子链；把 Map 属性袋换成泛型 AttrKey<T> 在这里是「为模式而模式」——白名单侧本就是租户可配的数据驱动 schema，T 除非配套改造 numberAttr/textAttr/listAttr 签名（方案自己说不动）否则是装饰性的。

**修正落地方式**：落地砍到两步、零行为变更：① 把代码内部硬引用的那 4 个键（userId / randomSeedSpu / orderLines / orderAmount）提成一处 `public static final String` 常量（放 DecisionEligibilityService 或一个 DecisionAttrs 常量类），写侧 73/74、BenefitEvaluator:449-453、ActivityRuleContext:94/104/120、ActivityQueryService:402 的 ladderField 全部引用它——**字符串取值一个字节都不能变**，否则就是坑 15 说的种子链断裂；② 在 DecisionContextFieldsTest 补一条「键集合钉死」用例：断言 requestAttributes(sample()).keySet() 恰好等于那 9 个键的字面量集合（用例里写死字面量，不引用常量，否则常量改名测试跟着改名照样绿）。不要引入 AttrKey<T>。

### [DOWNGRADE → P2] #4 12 个资格算子语义写两遍（ConditionTreeEvaluator 解释 / RuleConditionTranslator emit DRL），javadoc 声称的对拍测试不存在，ARRAY 兼容层 default 会静默漏配

**验证**：代码事实属实：ConditionTreeEvaluator:94-105 ARRAY 兼容 4 case + 105 行 `default -> { }`，109 起 12 case 穷尽 switch 表达式；RuleConditionTranslator:87-107 + 111 起是镜像的第二份；84-85 的「逐条对齐」注释在；`find -name '*EligibilityGoldenSet*'` 零命中，ConditionTreeEvaluator:40 引用的 DroolsEligibilityGoldenSetTest 确实不存在。但**核心伤害不成立**：翻译出的约束在生产里从不被求值。核对了全部消费点——ActivityMarketingService:172 与 :759(previewEligibility) 只 `buildEligibilityDrl` + `compileOrGet`（编译校验，preview 返回的是「条件合法，规则编译通过」，根本不产生真值）；决策侧 DecisionEligibilityService.applyJava:100-102 只把 eligibilityDefs 当「这个活动有约束」的**标记集合**用（树缺失时 fail-closed 拒绝），资格 100% 走 ConditionTreeEvaluator；ruleRuntime 侧唯一被执行的是 buildGiftDrl(ActivityDrlBuilder:135-146)，LHS 只有 eligible/gifts。所以两侧漂移今天既不会「预览通过、线上相反」（预览不判真值），也不会改任何一分钱；剩下的真问题只有两条，都是单路径的：一句骗人的 javadoc，和求值器自己那个 `default -> {}`。

**修正落地方式**：不要做「一个算子承载 eval+emit 的策略注册表」——那会把一条 CLAUDE.md 明确定性为非生产求值器的 DRL 翻译路径升格成与求值器对等的第二权威，正好是仓库反复警告的方向。正确落地三条：① 删掉/订正 ConditionTreeEvaluator:40 那句「等价性由 DroolsEligibilityGoldenSetTest 对拍」，改写成「本类是唯一生产求值器；RuleConditionTranslator 的产物仅用于写入口编译校验，不参与求值」；② 把 ConditionTreeEvaluator:94-105 的 ARRAY 兼容块改成穷尽形式（switch 表达式 yield Optional<Boolean>/三态，或显式列全 12 个 case），让新增算子在 ARRAY 字段上编译报错而不是静默落进标量分支——这是真正会改资格结论的那一半；③ 翻译器侧同样处理即可，无需与求值器共享结构；若确实想验对齐，写一条**测试专用**的对拍（编译并 fire 翻译出的 DRL 对同一批 fixture），测试内执行 DRL 不构成生产第二权威。

### [DOWNGRADE → P2] #5 ConditionNode 一类兼任分组与叶子，非法状态可表示 + Jackson 写得出读不回 + 未知 logic 抛异常打断整次决策

**验证**：四条证据都核实过：ConditionNode.java:20-53 单 POJO 五字段、isGroup() 由 logic 派生；DecisionDataLoader:401-410 的注释与 FAIL_ON_UNKNOWN_PROPERTIES=false 补丁在；ConditionTreeEvaluator:57-68 只要 logic 非空就当分组、同节点的 field/op/value 被静默忽略；RuleLogic:28-34 fromCode 对未知 code 抛 IllegalArgumentException，而 ConditionTreeEvaluator:60 直接调它，spuDiscountInternal(170-230) 一路无 catch。但伤害 ① 被夸大：写入口已经把畸形树挡住了——ActivityMarketingService:168-172 每次 create 都跑 translator.translate，RuleConditionTranslator:52-58 对分组同样调 fromCode（未知 logic 抛）、且分组无 children 直接抛「分组节点缺少子条件」，异常会让整个创建事务回滚，什么都不落库；也就是说「同时带 logic 与 field」这类树只能靠直连库写进来，且两侧（翻译器与求值器）对它的解读是一致的，不产生走库/快照分歧或金额分歧。方案本身方向没错但性价比不成立：今天节点形态只有两种、两个消费者对它的分派逻辑各只有 3 行，引入「DTO + 解析后密封模型 + 转换层」是净增理解成本，属于为模式而模式。

**修正落地方式**：只做两件小事：① 把 ConditionTreeEvaluator:60 的 RuleLogic.fromCode 包成 fail-closed——解析不出来就当这棵树不可判定、返回 false（候选淘汰）并打 metrics.reject，与 ActivityRuleContext:38-45 给 numberAttr 加的护栏同一口径；这条直接消掉「一条脏 logic 把请求打成 500」，改动 3 行、不碰任何合法路径的发放金额。② 在 isGroup() 上加 @JsonIgnore（或改名成非 getter 形态），把「写得出读不回」的不对称从注释挪回类型，DecisionDataLoader 的宽容 mapper 可以保留作双保险。密封模型等到真出现第三种节点形态（NOT 组 / 命名条件片段）时再上，届时它才有第二个消费者与第二次分派要收敛。

### [DOWNGRADE → P2] #6 ActivityRuleResult 是四套写协议共用的可变 god object，「命中」由两字段拼出，benefits 只写不读；DiscountView 12 分量手抄三遍

**验证**：证据全部属实：ActivityRuleResult.java:10-18 类注释确列 ELIGIBILITY/DISCOUNT/LADDER/GIFT 四套写口径；28/45 行 benefits 在 hit() 里写入，`grep getBenefits|BenefitOutcome` 除自身定义外**零读者**（连测试都没有），是纯死结构；merge(BenefitEvaluator:311-350) 确实 STACK 走 setHitAmount+setHitActivityId、单选走 hit()；ActivityQueryService:222 出口闸门确为 `getHitActivityId() != null || getHitAmount().signum() > 0`；DiscountView(436-462) 12 分量 + miss/withTraces/withMode 三处全分量重列，属实。降级的是因果链与方案：说「负奖励能出门的根因就是这个双字段闸门」不成立——负数出门靠的是 `hitActivityId != null` 这一半，换成 `hit != null` 的单事实闸门**照样放行负数**，除非让 Hit 构造器要求 amount>0，而那会误杀合法的 0 元命中（阶梯首档 reward=0 经 applyLadder→MAX 选中→今天 hit:true，NotApplicableCandidateTest#legitimateZeroSurvives 就是钉这个的），属于「表面等价、实际改发放行为」。另核实 pickByPriority(484-493) 对非空 eligible 永不返回 null，所以「amount>0 而 id 为 null」不可达，MergeResult 建模本身不会丢状态。

**修正落地方式**：按收益/风险拆三档做：① 立即可做、零风险：删除 benefits 字段、hit() 里那行 benefits.add 以及 BenefitOutcome（已验证零读者），顺带删掉类注释里的「P1-10 前瞻结构」段——它让人误以为存在多权益并存能力。② 低风险：DiscountView 的三个全分量 wither 换成一个 with 派生（或 Builder），保持 record 分量与 JSON 契约一字不变。③ MergeResult(strategy, Hit?, clamped, traces) 可做，但两条硬约束：Hit 必须允许 amount==0（合法 0 元命中）与 amount<0（脏数据的现有出门行为不能在这一轮偷偷改），即新闸门语义必须严格等于今天的 `id != null || amount>0`；想堵负数要单独立项、单独对拍，别混进重构。三档改完都要跑 DecisionGoldenSetTest 52 例 + ActivityQuerySafetyFallbackTest + SnapshotParityTest。

---

## 横切关注点与可测试性

### [DOWNGRADE → P1] #1 [P0] 无领域异常层/错误码/@ControllerAdvice：热路径 fromCode 抛异常打成 500，写平面状态码错配

**验证**：证据基本属实：全仓 grep `ControllerAdvice|@ExceptionHandler|ResponseStatusException` 实测 0 命中；唯一映射是 ActivityMarketingController 手写的 bad()/conflict()/record ErrorResponse（我读到 create:71-79、status:82-90、detail:152-156 三处同形 try/catch，末尾 private bad/conflict + ErrorResponse 确如所述）；RuleOperator.fromCode / RuleLogic.fromCode / StackStrategy.fromCode 三处都是 `throw new IllegalArgumentException("未知…")`，ConditionTreeEvaluator:60/:77 直调，DecisionEligibilityService.applyJava 与 ActivityQueryService.spuDiscountInternal 全链路无 try/catch（metrics.timeDecision:124-135 也只是 body.get() 不吞异常），DecisionPlaneController 无 catch、decision 的 application.yml 确实没有 server.error.include-message。BenefitEvaluator:410-420 的注释「不能直接用 DistributionMode.fromCode——它对未知 code 抛异常，那会让一条脏数据打断整批候选的算额」逐字为真，与 evalLeaf 缺字段 fail-closed 的自相矛盾成立。四眼 IllegalStateException（enforceFourEyes 两处）经 status 端点 catch 成 409 而非 403，属实；ActivityMarketingService 的 `msg.contains("uk_am_tenant_request")` 把 message 当控制流 key 也属实。降级理由：P0 的头条伤害「一行脏数据就把整次请求打成 500」在今天被写平面挡住了——create 路径 ActivityMarketingService:168 先走 RuleConditionTranslator.translate（:76 同样 fromCode 抛），非法算子在创建/预览期就是 400，条件树不可能经 API 进库；能触发的只剩越过 API 的直改库/数据迁移/枚举演进。也就是说这是「结构性缺陷 + 潜在暴露面」，不是 P0 级的既存线上故障。

**修正落地方式**：方案方向对，落地需三处收紧：① tryFromCode 只做**读路径**去异常化（ConditionTreeEvaluator/StackStrategy 读侧），写平面 RuleConditionTranslator 保持抛 —— 这点原文已写对，务必照做；② advice 里 `IllegalArgumentException → 400` 的兜底不要一次性铺到 decision 平面，decision 今天的 IAE 只可能来自脏数据或真 bug，统一 400 会把 bug 伪装成客户端错误，decision 侧建议只注册 ActivityException + 一条 500 的结构化兜底（带 code=INTERNAL，不回显 message）；③ DecisionSnapshotBuilder:152 的 StackStrategy.fromCode 跑在**后台构建线程**上，advice 覆盖不到，要 fail-safe 回落 MAX（与 DecisionDataLoader.resolveStrategy 的 orElse(MAX) 对齐），否则「脏策略行 → 快照建不出来 → 静默走库」这条比 500 更隐蔽。console 侧现有 per-endpoint catch 迁移期保留，避免状态码逐字漂移。

### [CONFIRMED] #2 [P1] 淘汰原因两份真相（中文串 vs 原因码）手工配对；scene 标签分裂成四套词汇表

**验证**：逐条核对全部属实。BenefitEvaluator 的 notApplicable(c, code, why) 调用点确在 :133/:150/:159/:172/:185，写入点 :213-220 是 `c.reject("本活动不适用：" + why)` 与 `metrics.reject("benefit", reasonCode)` 两条独立语句；DecisionEligibilityService:110-111 是先码后串（fallback+reject 再 reject 文案）、:122-125 是先串后码且无「本活动不适用：」前缀，顺序确实相反；ActivityDrlBuilder:82 在 DRL 里 emit `$c.reject("不满足资格条件");` 是第三份拷贝。漂移实证：DecisionMetrics 的 reject javadoc 写 price-above-order，BenefitEvaluator FIXED_PRICE 分支实际发 price-above-base，docs/activity-marketing.md:243 只补了「以代码为准」。scene 四套词汇实测成立：ActivityQueryService 的 spu-discount/gifts、AddOnPurchaseService 的 addon、BenefitEvaluator:219 硬编码 benefit、DecisionDataLoader:136/153 用 `metrics.decisionSource(type.name(), …)` = RED_PACKAGE/BUY_AND_GET/ADD_ON_PURCHASE，docs/activity-marketing.md:245 已自认「按 scene join 两组指标会得到空结果」。前端 ValidateView.vue 直接渲染 item.rejectReason、ValidateView.test.ts:443/452 断言 '不满足资格条件'，方案里的枚举 message 与今天逐字节一致（含 baseCodeOr/baseUnknownOr 那对动态配对塌缩成 OUT_OF_SCOPE 常量，文案也对得上），不改发放金额、不改淘汰判据。

**修正落地方式**：两点实施注意（不影响判定）：① `decisionSource` 从 ActivityType.name() 改成 DecisionScene.code() 会改变已有 Prometheus 序列的标签值，Grafana 面板与告警要同批改，属有意的契约变更，需在 deploy/docs 里同步；② ActivityDrlBuilder 生成的 DRL 让它 emit `RejectReason.INELIGIBLE` 常量而不是字面量（header 已 import engine 包，加一个 import 即可），该 DRL 在生产只被编译校验、不被执行，改它零风险但能消掉第三份拷贝。

### [DOWNGRADE → P2] #3 [P1] 埋点做在纯计算层：BenefitEvaluator 唯一构造依赖是 DecisionMetrics，导致 scene 只能硬编码 "benefit"；提议 DecisionObserver 观察者接口

**验证**：事实全部核实：BenefitEvaluator:44-49 唯一字段/唯一构造参数就是 DecisionMetrics，只用于 :219 reject 与 :378 clamped；:217-218 注释自认 scene 填的是「阶段」；DecisionMetrics:112-119 noop() 存在于 main（含真 SimpleMeterRegistry）且 12 个调用点除定义外全在 test；ActivityQuerySafetyFallbackTest 的 EmptyOnceBenefitEvaluator extends BenefitEvaluator 并 super(DecisionMetrics.noop()) 属实；AddOnPurchaseService 全文确实零 DecisionMetrics 引用。但两条伤害被夸大：① 「无法脱离 Micrometer 存在 / 要创建 12 个 SimpleMeterRegistry 才跑得起来」——noop() 是一行、SimpleMeterRegistry 无 I/O 无线程，且 javadoc 明确写了「用真的 SimpleMeterRegistry 而不是 null 对象：调用点无需空判，行为与生产一致」，属作者已解释且成立的取舍（本审查明确要防的第一类假阳性）；② 「加价购没埋点是因为埋点散在各层」——AddOnPurchaseService 只是没调 metrics，补埋点不需要观察者接口。而真正成立的那一半（求值层拿不到 scene）与 #2 完全重叠，用 #2 的 DecisionScene 就能解掉，不必新引入一套 Observer 类型层次（默认空实现接口 + MetricObserver record + 两个重载）——那是为了模式而模式，理解成本净增。

**修正落地方式**：最小落地：把 DecisionScene 作为参数传进 computeAmounts/merge（这些方法本来就已经在传 ctx 和 explain，多一个通道枚举不是新概念），metrics 依赖留在求值器里；或者更轻——ActivityQueryService:212 已经算出 applicableBefore，淘汰事件完全可以在编排层依据候选上的结构化 rejectCode（#2 新增的位）补打，求值器连 metrics 都不用留。clamped() 同理。若确实想让 BenefitEvaluator 变纯函数/可 final，先删掉 EmptyOnceBenefitEvaluator 这个继承桩（改用 Mockito spy 或直接构造空 ActivityRuleResult 的 loader 桩），不要为它保留可继承性。加价购埋点单独补，不要绑进这条重构。

### [DOWNGRADE → P2] #4 [P1] boolean explain 穿透 5 个类 13 个方法；姊妹服务默认值相反；trace 自由文本不可解析导致 items[] 第二份真相

**验证**：签名清单实测完全对得上（grep `boolean explain` 恰好 13 处，行号逐一吻合：AddOnPurchaseService:93/134、ActivityQueryService:106/170/328/332、DecisionEligibilityService:94、BenefitEvaluator:312/368、ActivityDrlBuilder:59/70/106/135）；默认值相反属实（ActivityQueryService:102-104 单参默认 false，AddOnPurchaseService:89-91 单参默认 true）；ActivityQueryService:212 的 applicableBefore 靠比对前后 id 列表反推算额阶段淘汰，属实且是本条最实的证据。但伤害①是误读：console 的 /activity-marketing/addon/options 走 1 参重载=true，与 /activity-marketing/spu-discount 显式传 true 是同一套「console=试算档、decision=热路径 false」的既定分档（DecisionPlaneController:122/136 都显式传 false，代码注释与 CLAUDE.md 都写明了），不是「今天仍然外泄」的残留缺陷；当年的真 bug（热路径写死 true）已经修掉。去掉这条之后，剩下的是「正确但不紧急」的可读性/防呆问题，配 P2。

**修正落地方式**：① ActivityDrlBuilder 的 explain 必须**保留为构建期布尔**：它改变生成的 DRL 文本，而 compileOrGet 的缓存 key 就是 tenant+DRL 全文（ActivityRuleRuntimeService:181-186），把它改成从 ctx.trace() 推导会让缓存键与 trace 档位耦合、同一份规则被编译两遍。DecisionTrace 只铺读路径（QueryService/EligibilityService/BenefitEvaluator/AddOnPurchaseService）。② 真正的防呆是删掉 AddOnPurchaseService 的 1 参重载、让两个调用方都显式声明档位，而不是把默认从 true 翻成 false（翻默认会静默改掉 console 试算页今天的输出）。③ ActivityRuleContext 会被 insert 进 StatelessKieSession 当 fact（买赠链路），往它身上挂可变 sink 前先确认 DRL 侧不碰这个字段，且 Collecting 实例不跨请求复用。

### [DOWNGRADE → P2] #5 [P2] ActivityRuleRuntimeService 一类七职责；fail-safe 靠 instanceof 猜异常，会把消费端异常误报成 fire-ceiling

**验证**：结构证据属实（文件实测 296 行不是 297，其余分块行号吻合：足迹常量 :60-64、Caffeine+weigher :101-106、编译池 :107-111、metrics.bindKieBaseCache :113、compileOrGet 租户前缀 key :181-186、safeRun :222-234、fallbackReason :262-270、FireCeilingListener 尾部）；:155-164 的注释确认 evalEligibility/evalDiscount/evalLadder 已删、生产 DRL 只剩 evalGift；compileOrGet 双契约（ActivityMarketingService:172 创建期要它抛 / safeRun 要它被吞）属实；:84-88 测试专用构造器属实。但伤害②「实打实的错报」在今天不可达：唯一执行的 DRL 是 buildGiftDrl，其 consequence 只有 `result.addEligible($c); result.getGifts().addAll($c.getGifts());`，不调用 BenefitMath（ActivityDrlBuilder:46 那句 import 只是 header 模板，gift 规则里没用到），所以举证的 BenefitMath:273 SHA-256 IllegalStateException 走不进这条链；且 Drools 会把 consequence 异常包成自己的 wrapper 而非 IllegalStateException，落在 eval-error 而不是 fire-ceiling。也就是说 instanceof 分类是「未来会咬人的写法」，不是当前的线上误报，不该拿「头号告警项」为它加权。

**修正落地方式**：拆分本身可做，但保住三条不变量：① compileOrGet 拆出去后必须仍保留「写平面拿到的是带行号的抛出、读平面被 safeRun 吞成 null 回退」这对相反契约，建议 KieBaseCache.get 抛 DrlCompileException，读侧单独包一层；② 缓存 key 必须继续带租户前缀（:184），weigher 依赖 key 里含 DRL 全文来数规则数（:143-157 indexOf("rule \"")），拆类时别把 key 换成 hash，否则权重直接归零、预算失效（这正是原文自己指出的耦合，拆分时最容易踩）；③ fallbackReason 的标签取值集合（compile-error/fire-ceiling/eval-error）不要改名，它已经进了 Prometheus 与文档；用 sealed RuleFailure 只改产生方式、不改标签字面量。原文的 harm② 建议在提案里删掉或改写成「未来风险」。

### [CONFIRMED] #6 [P2] activity.marketing.* 散成 @Value 字段注入，长出四种测试接缝；两个死字段被反射测试钉住删不掉

**验证**：逐条实测：@Value 注入点 ActivityQueryService:59/66/74、ActivityMarketingService:73、GenerationWarmService:56、RoleGateFilter:40，加 ActivityRuleRuntimeService:93-95 三个构造参数默认值；全仓 @ConfigurationProperties 只有 TenantProperties(prefix="activity.tenant") 一处，「范式已在、只用在另一族」属实。ActivityQueryService:66-76 两个 @Value + @SuppressWarnings("unused") + 注释「仅保留配置兼容」确认是死字段；ActivityQuerySafetyFallbackTest 的 query() helper 里连续三行 ReflectionTestUtils.setField(query, "ruleEngineEnabled"/"javaBenefitEval"/"javaEligibilityEval", …) 确实存在，而 legacyFalseFlagsCannotSwitchProductionBackToDrools 真正的断言是 verifyNoInteractions(runtime)（我读到 :156、:163 两处），与那两个字段被置成什么值无关——「测试把它的清理对象锁死」这个判断成立，且删字段会让测试炸在 ReflectionTestUtils 上而不是断言上，属于最难察觉的一类固化。DecisionMetrics.noop()、ActivityRuleRuntimeService 的测试构造器、GenerationWarmService.rebuildStaleSnapshots 的 package-private 可见性也都核实无误。方案复用既有范式、不新增抽象，且不触碰任何 CLAUDE.md 硬约束（不回退 DRL、不造第二权威、不动随机种子、不改快照/走库等价、不给 decision 写权限）。

**修正落地方式**：两个落地陷阱：① record 绑定**不继承 @Value 的 `:true` 默认**——`rule-engine.enabled` 缺省会绑成 false，等于静默关掉买赠 DRL 与 engine 分支。必须在 compact constructor 里显式兜底（enabled 缺省 true、cache-max-weight-kb 262144、max-fires-base 2000、max-fires-per-rule 200、snapshot.max-age-ms 60000），并加一条测试断言「空 yml 下 enabled 仍为 true」。② snapshot 段只有 decision 读、four-eyes 只有 console 读，绑定时缺段会是 null 子 record，取值前要 @DefaultValue 或在 record 里给非空默认，否则 console 启动即 NPE。另建议把「删两个死字段 + 删三行 ReflectionTestUtils」作为独立的第一个 commit 先落（它不依赖 @ConfigurationProperties 改造），这样锁死关系解开得最快、回滚面最小。

---

## 取数、快照与双路径等价

### [DOWNGRADE → P1] 「行→候选」的字段扇出有三份实现（flatten / entity→CandidateTemplate / template→toCandidate），只有一份被编译器守住；且条件行归约规则在两条路上已漂移

**验证**：证据基本属实。DecisionDataLoader.java:324-346 是 17 个 setter 的第一份扇出；DecisionSnapshotBuilder.java:117-128 是 18 位置参数构造（唯一编译期强制）；DecisionSnapshot.java:178-200 是第三份 setter 扇出，:194 确有「漏拷这一行 = 快照不封顶 / DB 封顶」的事故注释——说明这类漏抄真发生过。extraConfigType/extraDataJson 我全仓 grep 只命中 ActivityCandidate.java:37-38 的字段与 141-145 的 getter/setter，无任何装配路径写入，死字段属实。归约漂移也确实存在：DB 侧 eligibility() 在 :374 对**整行** putIfAbsent，drl 与 tree 必来自同一行；快照侧 :103 对 drl、:105 对 tree 各自 putIfAbsent（且 tree 额外要求 parse 成功），两个字段结构上可来自不同行。但把它定 P0（『今天就不等价』）夸大了：写平面唯一的条件写入口是 ActivityMarketingService.saveCondition（:640-656），只在 create/版本化编辑时按 (activityId, version) 写一行，编辑走新版本号，全仓无第二处 conditionRepo.save，所以同键多行今天只能由手工 SQL/迁移产生。也就是说钱漂的场景是潜在的，不是既成的；而字段扇出的维护性风险是真实且已有前科的。

**修正落地方式**：合并方向可行，但落地要钉三点：① toCandidate 必须保留 scopedSpuIds 的 null / 空集语义差异（ActivityCandidate.java:55-60 明写 null=作用域未知按整单、空集=已知），DB 侧现在是 scope.getOrDefault(id, Set.of()) 给空集，快照侧同理，统一后不能有任何一条退回 null，否则 AMOUNT 以外五形态基数会变；② 条件行归约统一取 DB 那份语义（整行取第一条，第一行树坏就 fail-closed 淘汰），不要统一成快照那份（跨行拼 drl+tree），后者会让坏数据静默变成『资格通过照常发钱』；③ 合并后必须跑 SnapshotParityTest 全量 + DecisionGoldenSetTest 52 例，确认逐字段对拍仍绿。extraConfigType/extraDataJson 建议直接删字段而不是补装配——补装配等于给一个没人读的字段造新的漂移面。

### [DOWNGRADE → P1] publish/refresh/rollback 三态同形、无校验、非原子；publish 的唯一调用方在重试路径上会把回滚目标销毁

**验证**：核心机制属实：DecisionSnapshotStore.java:34-38 的 current.put 与 previous.put 是两条独立语句；:64-73 的 rollback 无代际校验，无条件相信 previous。GenerationWarmService.java:153-157 先 publish，:159-160 才查 ACTIVE artifact、:164 才 warmAsync；:96-100 catch RuntimeException 且不更新 lastSeen，所以 artifactRepo 抖一次异常，下一轮会对**同一代际**再 publish 一次，previous 被覆盖成同代，rollback 从此是空转。Javadoc :15-17 声称『publish 只在预编译完成后调用』与实际顺序确实相反。但两处证据不成立/harm 被夸大：① 『两个线程并发 publish 同一桶』不可达——publish 唯一调用方挂在 GenerationPollScheduler 的单个 @Scheduled(fixedDelay) 上，Spring 默认单线程调度器，同一实例不会并发进入；② 更关键的是 rollback **今天没有任何生产调用方**（我 grep 全仓：生产代码里只有 GenerationWarmService 注释提到它，调用点只在 SnapshotParityTest 与 OfflinePropagationTest），decision 侧唯一的 controller 是 DecisionPlaneController，没有回滚端点。所以『故障当天你以为回滚了实际没有』这个场景今天根本走不到——运维压根按不下那个按钮。缺陷是真的、修法也对，但它是潜在缺陷（回滚一旦接线就会踩），不是 P0 级在产事故。

**修正落地方式**：SnapshotSlot(current, previous) + ConcurrentHashMap.compute 的方向没问题，且不改任何决策语义（读侧只读 current）。落地时把两件事一起做，否则修了也白修：① 把 publish 挪到预热之后，或至少让 warmTenantBizLine 里 publish 与后续步骤共享同一个失败边界（现在 :155 成功 / :159 失败 = 半完成状态被记为一次发布）；② 补一个生产可达的回滚入口（decision 侧只读端点或运维命令），否则 CLAUDE.md 里『回滚是 BenefitEvaluator 出 bug 的止损手段』这句话本身是空头承诺，比 previous 被覆盖更严重。PUBLISH 只在代际前进时占回滚槽位这条判定是对的，直接堵住重试重复 publish。

### [DOWNGRADE → P2] Materials 是 loader 的嵌套 record，快照只好另造 Materialized；load() 手工缝合、热路径复制整租户条件树、定序不变量在类型之外

**验证**：结构性证据属实：Materials 确实嵌在 DecisionDataLoader.java:104-121（含三参兼容构造），DecisionSnapshot.java:149 的 Materialized 只有 2 个分量且注释自称『与 Materials 同形』；load 在 :138-151 两次遍历 snaps、手算 minGen、手拼 provenance；:146 的 trees.putAll 确实按桶全量复制；DecisionEligibilityService.java:98-106 下游只按候选 id 查；ordered() 只靠 :150/:154 两次手工调用；测试确实直接 new 三参 Materials（:306/:314，:170/:187 的多候选场景未定序）。但三条伤害里两条站不住或量级很小：① 『tenant 名含 | 跨租户串桶』**不可达**——TenantIds.java:23 的 GRAMMAR 是 ^[A-Za-z0-9_-]{1,64}$，header / dev-default / aud-map / pattern 反解全部过这条校验（TenantContextFilter:61），| 进不来；② forTenant 的『跨租户全表扫描』扫的是**桶**（租户数×业务线数，量级几百），不是活动数；trees 复制的是**有条件树的活动**，不是全部在线活动，微秒级开销，跟被排在旁边的条件树求值/六形态算额比可以忽略；③ 定序失守今天只发生在测试桩上，生产三条装配路径都从 load 出口过 ordered()。所以这是一条真实的内聚问题，但不是 P1 的性能/正确性问题。

**修正落地方式**：提升 Materials 为独立值对象、删掉 Materialized、trees 只装命中候选——这三步与下游行为等价（DecisionEligibilityService 只做 trees.get(candidateId)），可以做。但『用规范构造器强制定序』要谨慎：它会顺带改变所有测试桩的候选顺序，而 pickByAmount/pickByPriority 打平时是严格 >（先到先得），个别断言可能因此翻面——改完必须跑 DecisionGoldenSetTest 52 例与 SnapshotParityTest 确认赢家没变。BucketKey record 可以做（顺手去掉前缀扫描），但别把它当安全修复写进理由，| 的歧义在当前 grammar 下不存在。

### [CONFIRMED] 快照构建期是无门禁的 N+1 + 全表扫描，且每 60 秒兜底重建对每个桶完整重跑

**验证**：逐条核对全部属实。DecisionSnapshotBuilder.java:74-79 用 findByActivityStatusAndIsDel(ONLINE, 0) 捞该租户全部在线活动，再用 Java if 丢掉非本桶的；:137-142 的 bindingRepo.findByActivityIdAndVersionAndIsDel 确实在 for (m : live.values()) 循环体内，每活动一次；ActivitySpuBindingRepository 只有 3 个方法（按 spuId 批量、按单 (activityId,version)、按 bindSource），确实没有 findByActivityIdInAndIsDel，N+1 是接口缺口逼出来的。GenerationWarmService.java:116-136 对 snapshotStore.all() 每个超龄桶各调一次完整 build，轮询默认 3000ms、阈值默认 60000ms（:56-57），所以每桶约每分钟重跑一遍全套。DecisionQueryCountTest.java:47 的 EXPECTED_QUERIES=5 只钉热路径，构建期确无门禁。谓词双份也属实：builder :140 的 effective/isDel 是 Java if，DataLoader 侧是 SQL 派生查询；resolveStrategy 在 builder :149-154 与 loader :249-257 各抄一份（含 MAX 兜底）。这条开销确实不随请求量增长、只随活动目录规模增长，压测照不出，且全打在只读账号那条连接上。

**修正落地方式**：第一层（补 findByActivityIdInAndIsDel、把 bizLine 下推 SQL）安全且收益直接，先做。第二层 buildAll(tenant) 有个必须钉住的陷阱：代际号是**按 (tenant, bizLine) 一行**的（ActivityGenerationEntity），poller 是在某条业务线代际前进时才 publish 该桶。若 buildAll 用触发那条线的 generation 去发布同租户其它桶，会把别的业务线的 provenance.generation 写成错的数、并把它们的 previous 槽位一起占掉（正是 refresh 存在的理由）。正确落地：buildAll 只用于兜底重建路径（走 refresh、每桶沿用自己的 stale.generation），或在发布路径上让每个桶各自从 generation 表读回自己的代际号；发布路径仍只 publish 触发的那一个桶。另外『分组时数出 bizLine 为空的活动并打 warn + 计数器』这条建议很值得做，它把 CLAUDE.md 里那条只能靠诊断端点照出来的故障提前到构建期。

### [DOWNGRADE → P2] 读写共用同一批 JpaRepository 且都在 common，『decision 结构上写不了库』只由 MySQL 授权在运行期保证

**验证**：事实全部属实：ActivityManageRepository.java:13 extends JpaRepository，:61-94 三个 @Modifying（扣库存/还库存/软删版本）与只读方法并排；DecisionSnapshotBuilder.java:45-64 注入的正是这个可写接口；ActivityPoolMatchService 在 activity-common、带 @Service + @Transactional，:105/:110/:128 三处 bindingRepo.save 写绑定表，而 decision 的 scanBasePackages=com.lrj.drools，所以它确实是只读进程里存在的 bean（我确认它唯一调用方在 console 的 ActivityMarketingService）。我另外核对：common 里除它之外没有第二个写平面服务（grep .save(/.saveAll(/@Modifying 在 common/service 下只命中这三行）。但严重度按 P1 偏高：今天没有任何真实写路径在 decision 被触发，且已有两道运行期护栏（deploy/mysql-init 只 GRANT SELECT、DecisionDdlGuardTest 钉死 ddl-auto=validate）。这是一条把运行期保证提前到编译期的加固，不是在产缺陷。

**修正落地方式**：落地方式要注意 Spring Data 的细节：同一实体挂两个 repository 接口是允许的，但 common 侧必须 extends Repository<T, ID>（不是 CrudRepository/JpaRepository）才能真正让 save/delete 在类型上不存在——这一点原方案说对了。顺序建议：先把 ActivityPoolMatchService 上浮到 console（它已经只有 console 调用，零风险），再拆 ActivityManageRepository/ActivitySpuBindingRepository 的读接口给 DecisionDataLoader 与 DecisionSnapshotBuilder。拆时别顺手改任何查询方法签名（decrementInventory 的原子 UPDATE 谓词一个字都不能动），并确认 @TenantId 判别式过滤在 Repository<> 派生查询上一样生效（跑 DecisionTenantHeaderTest 验证）。

### [DOWNGRADE → P2] 物料来源判定散在 load() 的 if/else 与 resolveStrategy 里各做一次且判据不同；跨业务线时合并策略由 activityId 字典序决定

**验证**：代码事实属实：DecisionDataLoader.java:133-155 的 if/else 各自手写 metrics.decisionSource 字面量与 provenance 拼装；:248-250 的 resolveStrategy 独立用 snapshots.get(tenant, bizLine) != null 再判一次，判据确实与 load 的 forTenant(tenant) 非空不同；:249 取 candidates.get(0).getBizLine()，而 :176-177 刚按 activityId 字典序排过，所以跨业务线购物车的 STACK/MAX 归属确实由 id 排序决定，且全仓无注释无测试。但两条伤害都被拔高了：① 『候选走快照、策略却查库』今天不可达——builder :75 按 bizLine 精确匹配收活动，快照候选的 bizLine 必等于其桶键，故 snapshots.get(tenant, 首候选bizLine) 在快照命中时必非 null；候选为空的分支在 ActivityQueryService.java:180-185 提前 return，压根不会走到 resolveStrategy。所以这是『再加第三种来源时会踩』的潜在耦合，不是现存不一致。② 跨业务线策略归属确实是任意的，但它本质上没有正确答案（两条线各有策略，只能挑一个），今天的行为至少确定且两路一致；这是缺文档缺测试，不是发钱错误。

**修正落地方式**：sealed MaterialSource 这层抽象对当前只有两种来源、且顺序固定的场景收益有限，容易变成为模式而模式。更划算的最小落地：把 resolveStrategy 的第二次来源判定删掉，改由 load 把已解析的 strategy 与 bizLine 一起随 Materials 返回（来源判定从此只有一处）；把 candidates.get(0).getBizLine() 提成 Materials.bizLine() 并写明『跨业务线时取 activityId 最小者所属业务线』+ 补一个钉住该行为的测试。取值规则必须原样保持，任何『改成取最大金额那条线』之类的顺手优化都会悄悄改变合并结果。metrics.decisionSource 的字面量与 provenance.source 统一由 DecisionProvenance 出，也可以顺带做。

---

## 附：勘察阶段的完整发现（含证据行号与骨架代码）

### 维度：权益形态求值层（BenefitEvaluator / BenefitMath / BenefitForm / DistributionMode / *RangeParser）

#### 1. [P0] 权益形态的分派、校验、载荷解析、前端映射被切成 5 处互不相识的 if/三元链，且求值侧的兜底分支是「按金额原样发」——加一种形态要改 10+ 处，漏任何一处都不是编译错误，而是静默发错钱。

**证据**：
- `/Users/liruijun/personal/LLM/drools-demo/activity-common/src/main/java/com/lrj/drools/activity/engine/BenefitEvaluator.java:116-193（computeAmounts：5 个 if(form==X){...continue;} + L191-192 无条件兜底 setComputedAmount(redPackageAmount)）`
- `/Users/liruijun/personal/LLM/drools-demo/activity-common/src/main/java/com/lrj/drools/activity/engine/BenefitEvaluator.java:126-137（注释明写「随机必须排在 null guard 之前」——分派顺序是行序，不是数据）`
- `/Users/liruijun/personal/LLM/drools-demo/activity-common/src/main/java/com/lrj/drools/activity/domain/BenefitForm.java:62-73（of() 与 isSupportedUnit() 是两条必须手工同步的判别链）`
- `/Users/liruijun/personal/LLM/drools-demo/activity-console/src/main/java/com/lrj/drools/activity/service/ActivityMarketingService.java:359-420（validateBenefitForm：结构是 if(form != RATIO_ZHE){ 嵌套 if(FIXED_PRICE)/else if(NTH_ZHE) ... return; } 的「非折扣」桶）`
- `/Users/liruijun/personal/LLM/drools-demo/activity-console/src/main/java/com/lrj/drools/activity/service/ActivityMarketingService.java:488-517（validateRangeColumn：第二次按形态分派；其 L479-482 注释自陈写侧曾 100% 拒掉「第 N 件折」与「随机红包」两种合法配置）`
- `/Users/liruijun/personal/LLM/drools-demo/frontend/src/console/logic.ts:44,147-174,224-240（benefitFormOf + benefitDraftFromRule + benefitRequestFields 三条独立三元链；EditorView.vue 另有 49 处形态分支）`

**今天怎么伤人**：实测触点：加第七种形态（如「每满 X 减 Y」）要改 BenefitForm 枚举常量+UNIT_ 常量+of()+isSupportedUnit()（4 处）、BenefitEvaluator.computeAmounts 插一个位置敏感的 if（1 处）、BenefitMath 新静态方法（1 处）、range 列解析（第 4 种用途，1 处）、ActivityMarketingService 两个方法各一支（2 处）、前端 logic.ts 3 处三元链 + playbooks.ts 卡片 + EditorView.vue 表单分支（≈5 处）、金标集新 @Nested（1 处）——12 个文件约 18 处，全部无编译期约束。失败模式按漏的位置分三种，没有一种会报错：漏 computeAmounts 的分支 → 落到 L191 兜底，「每满 100 减 10」被当成「减 100 元」原样发出去（金额是正数、决策成功、日志干净）；漏 isSupportedUnit → 写平面拒绝这个合法单位；漏 validateRangeColumn → 新形态的 range JSON 被当阶梯解析。这不是假想：validateRangeColumn 自己的注释记录了这个 bug 已经真实发生过一次——读侧认识三种语义、写侧只认识一种，于是另外两种被判成脏数据全量拒收。

**方案**：密封接口 + 每形态一个策略实现（Java 21 的策略模式），把「怎么算钱」「range 列怎么解读」「写入口怎么校验」三件事收进同一个类型里；BenefitForm 保留为持久化判别位（'元/折/价/件折' 的字符串语义与 fail-safe 回落一个字节不动），但 `resolve(form, mode)` 用**枚举 switch 表达式且不写 default**——加一个枚举常量而不加分支就编译不过，这正是今天缺的那道编译期约束。为什么不是访问者：形态之间没有共同的遍历结构，访问者只会多一层间接；为什么不是模板方法：六形态的算额步骤形状不同（随机不看 redPackageAmount、NTH 要行、PRICE/RATIO 要基数），共享的只有「算不出来就淘汰」这一条，用 Outcome 密封返回值表达比用父类钩子干净。写平面把 validateBenefitForm/validateRangeColumn 的形态分支也委派给同一批策略对象的 `validate(req)`，读写两侧的形态知识从此只有一份。

```java
public sealed interface BenefitStrategy
        permits FixedAmount, RandomAmount, Ratio, FixedPrice, NthItem {

    /** 本形态是否必须有 redPackageAmount 才谈得上算额。随机型是唯一的 false（钱在 range 列里），
     *  这正是今天 L126-137 那条「顺序不能反」的注释想说的事——现在它是声明，不是行序。 */
    default boolean requiresAmountField() { return true; }

    /** reasonCode 是 DecisionMetrics 的标签值，改名等于改告警，迁移期必须逐字保留 */
    Outcome compute(BenefitInput in);

    sealed interface Outcome {
        record Payable(BigDecimal amount) implements Outcome {}
        record NotApplicable(String reasonCode, String why) implements Outcome {}
    }

    /** 写入口校验也走这里：形态知识全仓一份 */
    void validate(ActivityCreateRequest req);

    /** 判别位 → 策略。今天这段判别是 computeAmounts 里 if 的先后顺序（unit × takeType 两轴） */
    static BenefitStrategy resolve(BenefitForm form, DistributionMode mode) {
        return switch (form) {                    // 无 default：加形态漏分支 = 编译失败
            case AMOUNT      -> mode == DistributionMode.RANDOM_AMOUNT ? RANDOM : FIXED;
            case RATIO_ZHE   -> RATIO;
            case FIXED_PRICE -> PRICE;
            case NTH_ZHE     -> NTH;
        };
    }
}
```

**迁移**：三小步，每步单独可发布。① 只抽策略、不动任何算法：五个策略类的 compute 逐行搬 computeAmounts 的对应分支，仍调同一批 BenefitMath 静态方法（取整层不碰），computeAmounts 变成「resolve → requiresAmountField 前置 guard → compute → 写候选字段」，行序语义变成 requiresAmountField + resolve 的显式表达。此步的等价性由 DecisionGoldenSetTest 52 例（Ratio 13 例覆盖折扣/封顶/脏数据、Ladder 12 例覆盖覆盖语义）+ NotApplicableCandidateTest（四形态 fail-closed + 合法 0 元存活）+ SnapshotParityTest 三层守住，且**不改一个 reasonCode 字符串**，指标面板零影响。② 把 ActivityMarketingService 两个 validate 方法的形态分支委派给策略的 validate()，BenefitFormValidationTest / FixedPriceAndClaimTest 是安全网。③ 前端 logic.ts 的三条三元链收敛成一张 `FORM_SPEC` 表（unit / 需要哪些草稿字段 / range 序列化），logic.test.ts + logic.roundtrip.test.ts 守住往返。

**风险**：① reasonCode 与 trace 文案是外部可见契约（metrics 标签 + 控制台试算展示），搬运时任何一个字符串手滑都会静默打断告警或让 e2e 视觉断言飘红——迁移 PR 必须对这些字面量做纯文本 diff 复核。② `resolve` 的两轴判别把「form==AMOUNT && takeType==RANDOM」提前到分派点，而今天它是 L129 那个 if 的复合条件；若某形态将来也想支持 takeType=2，新结构会更早暴露冲突（是好事，但会让一批既有脏数据的表现从「按形态发」变成「按随机发」）——迁移期 resolve 必须逐字复制现有复合条件，不要顺手泛化。③ 若团队半年内确定不会再加形态，只做 ① 即可，②③ 的收益不足以承担跨模块 PR 的风险。

#### 2. [P0] applyLadder → computeAmounts → merge 三段之间没有返回值，全靠在共享的可变 ActivityCandidate 上打布尔标记（eligible / amountComputed / ladderApplied / computedAmount）传状态；协议只写在注释里，任何重跑都必须手工擦干净这几个字段。

**证据**：
- `/Users/liruijun/personal/LLM/drools-demo/activity-common/src/main/java/com/lrj/drools/activity/domain/ActivityCandidate.java:66-78（eligible=true / computedAmount=ZERO / amountComputed / ladderApplied 四个默认值就是四条隐式契约）`
- `/Users/liruijun/personal/LLM/drools-demo/activity-common/src/main/java/com/lrj/drools/activity/engine/BenefitEvaluator.java:85-87（阶梯设 computedAmount + ladderApplied，**故意不设** amountComputed——「固定金额覆盖阶梯」的线上语义全靠这个不设）`
- `/Users/liruijun/personal/LLM/drools-demo/activity-common/src/main/java/com/lrj/drools/activity/engine/BenefitEvaluator.java:119,149-152（`if (c.isAmountComputed()) continue;` 与「redPackageAmount==null 且未落档 → 淘汰」）`
- `/Users/liruijun/personal/LLM/drools-demo/activity-common/src/main/java/com/lrj/drools/activity/engine/BenefitEvaluator.java:196-220（notApplicable 的整段注释在解释「契约写在注释里、却没有落到数据结构上，是这个 bug 的全部成因」）`
- `/Users/liruijun/personal/LLM/drools-demo/activity-common/src/main/java/com/lrj/drools/activity/service/ActivityQueryService.java:297-306（safeFallback 必须手工清 3 个字段，注释：「落档留痕也是计算态，必须一起清」）`
- `/Users/liruijun/personal/LLM/drools-demo/activity-common/src/main/java/com/lrj/drools/activity/service/ActivityQueryService.java:212-219（explain 要在 computeAmounts 前后各拍一次 eligibleIds 快照，才能还原「本阶段淘汰了谁」）`

**今天怎么伤人**：① 读懂 computeAmounts 这 78 行必须同时在脑子里装住 4 个隐式契约：阶梯不设 amountComputed（覆盖语义靠它）、eligible 默认 true 且 reject 是唯一淘汰通道、computedAmount 默认 ZERO 导致「算不出来」与「减 0 元」不可分（所以才要第 5 个字段 ladderApplied）、阶段之间零返回值全靠原地改。② 这套结构已经生产过一个真 bug：候选带着 eligible=true / computedAmount=0 进合并，PRIORITY 下凭 priority 挤掉能减 10 元的活动（commit a0ec639「阶梯未落档的候选必须淘汰，不能留成 0 元幽灵挤掉真优惠」），而修复手段是**再加一个布尔位**——结构让修复本身加重了问题。③ 每一个新的求值调用点都要重新记住三清：safeFallback 是第一个，漏清 ladderApplied 的表现是「上一轮的落档留痕让本轮该淘汰的候选活下来」，运行时才暴露、金额照发、日志干净。④ explain 的「谁被算额阶段淘汰」只能靠前后快照 diff 还原，因为淘汰是就地改对象、没留下事件。

**方案**：把「求值产出」从 fact 上剥离成不可变结果表：applyLadder 返回 `Map<activityId, BigDecimal>`（缺席即未落档，ladderApplied 这个布尔位直接消失），computeAmounts 吃 ladderHits 作显式入参、返回 `Map<activityId, BenefitOutcome>`（密封结果类型区分 Payable / NotApplicable，「算不出来」与「减 0 元」在类型上就不同，不再需要靠第二个布尔位补），merge 只吃结果表。这样重跑天然幂等（safeFallback 不必三清，因为没有残留态可清），explain 的淘汰清单直接从结果表读。选密封结果类型而不是继续加字段：字段是加法、类型是分类，今天的困境正是「用 4 个正交布尔位编码 3 种互斥状态」。候选对象上的 setter 保留为兼容出口（ActivityDrlBuilder 的 ladder DRL 仍写它们，虽然没有生产调用方），只是主链路不再从它读。

```java
public sealed interface BenefitOutcome {
    /** source 只用于 explain / 明细，不参与判定 */
    record Payable(BigDecimal amount, Source source) implements BenefitOutcome {}
    record NotApplicable(String reasonCode, String why) implements BenefitOutcome {}
    enum Source { LADDER, FIXED, RANDOM, RATIO, PRICE, NTH }
}

// 阶梯：缺席 = 没落档。ladderApplied 布尔位随之删除
Map<String, BigDecimal> applyLadder(ActivityRuleContext ctx,
                                    List<ActivityCandidate> cs, List<LadderActivityDef> defs);

// 算额：ladderHits 是显式入参而不是候选上的残留态；
// 「本形态算得出就覆盖阶梯」的线上语义原样保留——只是从「谁没设 amountComputed」
// 变成一句能读懂的代码：outcome != null ? outcome : ladderHits.get(id)
Map<String, BenefitOutcome> computeAmounts(ActivityRuleContext ctx,
                                           List<ActivityCandidate> cs,
                                           Map<String, BigDecimal> ladderHits);

// 合并：只吃结果表 → 幂等，safeFallback 不再需要手工清三个字段
ActivityRuleResult merge(ActivityRuleContext ctx, List<ActivityCandidate> cs,
                         Map<String, BenefitOutcome> outcomes,
                         StackStrategy strategy, boolean explain);
```

**迁移**：双写过渡，四步。① computeAmounts 增加返回值（结果表），同时继续写候选上的四个字段——此步零行为变化，可单独合入。② merge 与 ActivityQueryService.items() 改读结果表，候选字段降为只写不读；此时跑 DecisionGoldenSetTest 全 52 例 + SnapshotParityTest，两条装配路径的等价性不受影响（结果表按 activityId 定序，与现有 ordered() 契约一致，不引入新的迭代序依赖）。③ 删除候选上的 amountComputed / ladderApplied 写入与 safeFallback 的三清；此步是唯一真正改结构的一步，回归靠 NotApplicableCandidateTest#legitimateZeroSurvives（首档 reward=0 的合法 0 元必须存活）+ Ladder 那 12 例（含「订单金额缺失 → 阶梯不参与，退回固定金额」这条覆盖语义）。④ 收尾把 explain 的前后快照 diff 换成读结果表。

**风险**：① 「固定金额覆盖阶梯」是线上语义且被金标钉死，重写成 `outcome != null ? outcome : ladderHits.get(id)` 时必须逐例核对：当形态算出 NotApplicable 时，今天的行为是**淘汰候选**（连阶梯值也一起丢掉，见 L159/172/185 的 notApplicable+continue），不是回落阶梯值——这是最容易在重构里「顺手改好」的一处，改了就是改钱。② ActivityDrlBuilder.buildLadderDrl 生成的 DRL 仍 setComputedAmount（ActivityDrlBuilder.java:121），虽无生产调用方但被测试与 examples/capacity 使用，删字段前要确认这两处。③ 若近期不打算新增求值调用点，也不打算再动阶梯语义，收益主要是可读性与 safeFallback 的地雷排除，可以只做 ①②、把 ③ 留到下一次真要改语义时一起做。

#### 3. [P1] redPackageRangeAmount 一列承载三种载荷（阶梯数组 / 随机区间 / {"nth":N}），归属靠「顶层 JSON 类型 + 判别位」的口头约定分工，而三处判别规则互不一致：ladderDefs 是形状优先（完全不看形态），RandomRangeParser 与写平面校验是形态优先。

**证据**：
- `/Users/liruijun/personal/LLM/drools-demo/activity-common/src/main/java/com/lrj/drools/activity/engine/RandomRangeParser.java:8-24（类注释就是这条口头分工的出处：「数组→阶梯，对象→随机」）`
- `/Users/liruijun/personal/LLM/drools-demo/activity-common/src/main/java/com/lrj/drools/activity/engine/RandomRangeParser.java:57-75（parseNth 与 parse 共用「JSON 对象」形态，只能靠 unit 再分一次）`
- `/Users/liruijun/personal/LLM/drools-demo/activity-common/src/main/java/com/lrj/drools/activity/engine/LadderRangeParser.java:29-30（`if (!arr.isArray()) return tiers;` 是分工的另一半，两个类靠注释互相约定）`
- `/Users/liruijun/personal/LLM/drools-demo/activity-common/src/main/java/com/lrj/drools/activity/service/ActivityQueryService.java:396-404（ladderDefs 不看 BenefitForm：任何 range 能解析出档位的候选都进 applyLadder）`
- `/Users/liruijun/personal/LLM/drools-demo/activity-console/src/main/java/com/lrj/drools/activity/service/ActivityMarketingService.java:488-517（写侧第三套判别顺序：NTH → PRICE → AMOUNT+random → 否则阶梯）`
- `/Users/liruijun/personal/LLM/drools-demo/frontend/src/console/logic.ts:147-174（前端第四套判别：unit 优先、然后 takeType、然后 JSON.parse 看是不是数组）`

**今天怎么伤人**：这一列的真实类型是 `Tiers | Range | Nth | none`，但在 Java 里是 String，语义由「谁先解析成功」决定。今天四处判别顺序各不相同，一致纯属人工维护——加第四种载荷要在四处各改一遍，只要有一处先认领，其它三处就拿不到；而认领冲突的表现是「活动在控制台显示已上线、决策侧一声不响不适用」（validateRangeColumn 的 L484-486 注释自己写明了这个失败形态）。另外 ladderDefs 的形状优先让「折 + 阶梯」这类脏数据先落档、再被折扣覆盖——这个绕路今天靠 computeAmounts 的覆盖语义收场，金标 DecisionGoldenSetTest#ladderAndRatioTogetherAgreeOnBothPaths 就是在钉这个巧合。

**方案**：值对象 + 单一判别点：把这一列在**候选装配时**（DecisionDataLoader.flatten / DecisionSnapshot.materialize 两条路）一次性解析成密封的 RangePayload，读写两侧都调同一个 `parse(form, takeType, json)`。为什么是密封接口而不是继续两个 parser：三种载荷是互斥的和类型，密封接口让「新增第四种」变成一次编译期扫描（所有 switch 都会亮红），而两个静态 parser 的分工只能靠注释。顺带的收益是两条装配路径共用同一个解析出口，比今天各自持有原始字符串更难产生走库/走快照的分歧。

```java
public sealed interface RangePayload {
    record Tiers(List<LadderTier> tiers) implements RangePayload {}
    record Random(BigDecimal min, BigDecimal max) implements RangePayload {}
    record Nth(int n) implements RangePayload {}
    record None(String raw) implements RangePayload {}   // raw 留着，编辑器不许静默清空历史值

    /** 全仓唯一判别点。读侧（装配）与写侧（validateRangeColumn）都调它 */
    static RangePayload parse(BenefitForm form, Integer takeType, String json) {
        if (json == null || json.isBlank()) return new None(json);
        return switch (form) {                       // 无 default：加形态必须回答「这一列怎么解读」
            case NTH_ZHE     -> nthOrNone(json);
            case FIXED_PRICE -> new None(json);      // 写侧已拒；读侧原样忽略
            case RATIO_ZHE   -> tiersOrNone(json);   // 保留今天的脏数据归属（金标 ladderAndRatioTogether）
            case AMOUNT      -> DistributionMode.of(takeType) == DistributionMode.RANDOM_AMOUNT
                                ? randomOrNone(json) : tiersOrNone(json);
        };
    }
}
```

**迁移**：① 先只引入 RangePayload.parse 并让 ladderDefs 改成「payload instanceof Tiers 才进 applyLadder」——我逐组合核对过这一步是行为等价的：form=RATIO/PRICE/NTH 时今天虽然会先落档，但随后该形态的分支要么覆盖阶梯值、要么 notApplicable 淘汰候选，落档结果一律被丢弃；form=AMOUNT+takeType=2 且 range 是数组时今天落档后随机解析失败仍走 notApplicable。改成形态优先后各组合结果不变，DecisionGoldenSetTest#ladderAndRatioTogetherAgreeOnBothPaths（期望减 20，折扣覆盖阶梯）仍成立。② 写平面 validateRangeColumn 改调同一个 parse，报错文案保持不变（BenefitFormValidationTest 断言了文案）。③ 装配路径改为携带已解析的 payload，SnapshotParityTest 守两条路等价。④ 前端 logic.ts 的判别链最后收敛。

**风险**：① 步骤 ① 的等价性依赖「阶梯落档结果在其它形态下必被丢弃」这条推理——虽然我按六形态逐组合核对过，但它属于必须由金标集实测确认的那类结论，不能只靠推导合入；建议先在测试里加一个显式断言把这条推理钉死，再改 ladderDefs。② parse 成为单点后，它的 fail-safe 取向（解析失败 → None 而不是抛）必须与今天四处各自的 fail-closed 完全一致，否则会把「活动不适用」变成「活动按另一种载荷发钱」。③ 若短期内不会新增第四种载荷，只做 ①② 即可，③④ 的跨模块改动收益有限。

#### 4. [P1] 「这个活动的钱算在多少商品上」是本层的核心不变量，但它是 BenefitEvaluator 的一个 private static 方法，六种形态里只有 3 种调它、1 种走另一条 scope 通道、2 种完全不碰——作用域在形态间有三套执行方式，全靠人记。

**证据**：
- `/Users/liruijun/personal/LLM/drools-demo/activity-common/src/main/java/com/lrj/drools/activity/engine/BenefitEvaluator.java:257-267（baseAmount，private static，三档语义全在注释里）`
- `/Users/liruijun/personal/LLM/drools-demo/activity-common/src/main/java/com/lrj/drools/activity/engine/BenefitEvaluator.java:168-176,180-189（FIXED_PRICE / RATIO_ZHE 各调一次 baseAmount，并各自重复 baseCodeOr + baseUnknownOr 的 null 处理组合）`
- `/Users/liruijun/personal/LLM/drools-demo/activity-common/src/main/java/com/lrj/drools/activity/engine/BenefitEvaluator.java:398-409（NTH_ZHE 不走 baseAmount，改把 scopedSpuIds 传进 BenefitMath.nthItemDiscount 的第四参）`
- `/Users/liruijun/personal/LLM/drools-demo/activity-common/src/main/java/com/lrj/drools/activity/engine/BenefitEvaluator.java:191-192（AMOUNT 分支完全不碰作用域——CLAUDE.md 坑 17 记录的线上多发就出在这里，最后只能在 DecisionDataLoader 补一道淘汰）`
- `/Users/liruijun/personal/LLM/drools-demo/activity-common/src/main/java/com/lrj/drools/activity/engine/BenefitEvaluator.java:368-387（capToOrderAmount 用 ctx.getOrderAmount() 封顶，与作用域基数不是同一个数）`
- `/Users/liruijun/personal/LLM/drools-demo/activity-common/src/main/java/com/lrj/drools/activity/engine/BenefitMath.java:134-136,147-148（scope 用 null 参数编码「不限定」，三参/四参重载并存）`

**今天怎么伤人**：新增形态时「要不要用作用域」今天是一个可以忘的问题，而不是必须回答的问题——因为没有任何签名逼你回答。忘掉的代价有先例：AMOUNT 形态不调 baseAmount，于是「v1 绑 A/B → v2 只绑 A」之后单查 B，走库路径照发 50 元、走快照根本不是候选，两条路发不同的钱且全程无异常（坑 17 / SnapshotParityTest#narrowedBindingStopsPayingOnBothPaths）。修复只能补在取数层，求值层的漏洞原样留着。另外 baseAmount 的三档 null 语义（null=不可知 → 淘汰）要求每个调用点自己写一遍 baseCodeOr/baseUnknownOr 组合，今天两处重复、第三个形态接入时就是第三份。

**方案**：把作用域提成值对象 BenefitScope（封装 scopedSpuIds + orderAmount + lines，暴露 base() / lines() / covers()），由 computeAmounts 统一构造一次传给策略；并在策略接口上加一个必答的 `usesScope()` 声明——AMOUNT 返回 false，把今天的「没写」改成「写明决定不用」。值对象而不是继续放静态方法：三档判定 + null=不可知 是一组不变量，值对象能把它和它的解释绑在一起，也能让 BenefitMath 的 scope=null 重载退休（用 BenefitScope.unrestricted() 表达「不限定」，比 null 参数少一次误读）。这一条与前两条的策略改造是同一次落地的自然产物，不需要独立 PR。

```java
/** 「这个活动的钱算在多少商品上」——从 private static 提成值对象 */
public record BenefitScope(Set<Long> spuIds, BigDecimal orderAmount, List<BenefitMath.Line> lines) {

    /** 逐字节搬 BenefitEvaluator.baseAmount 的三档，顺序不能反：
     *  ① spuIds==null（作用域未知）→ 整单；② 覆盖全部请求 SPU → 整单；③ 真子集 → 行小计；
     *  拿不到行 → null（不可知，调用方必须淘汰候选，绝不用整单顶替） */
    public BigDecimal base() { ... }

    /** 供 NTH 用：作用域内的行。scope 未知时等价于全部行 */
    public List<BenefitMath.Line> scopedLines() { ... }

    public static BenefitScope unrestricted(BigDecimal orderAmount, List<BenefitMath.Line> lines) { ... }
}

public sealed interface BenefitStrategy ... {
    /** 必答题：今天 AMOUNT 是「忘了用」，改造后是「声明不用」——差别在于前者无法被 review 发现 */
    boolean usesScope();
}
```

**迁移**：随第 1 条的策略抽取一起做，纯搬运：BenefitScope.base() 逐行复制 baseAmount，包括 ①②③ 三档的顺序与 null 语义；策略拿到的是已构造好的 scope，不再各自调 ctx。NTH 的 scopedLines() 要与 BenefitMath.nthItemDiscount 的第四参行为一致（scope 非空时「没带 spuId 的行一律剔除」），先保留四参重载、等所有调用点迁完再删三参重载。安全网：DecisionGoldenSetTest 的 Ratio/Precision 组、NthItemDiscountTest、SnapshotParityTest#narrowedBindingStopsPayingOnBothPaths（这条专门守作用域收窄在两条路上一致）。usesScope() 首版只是声明位、不接线到任何逻辑，零行为风险。

**风险**：① base() 的三档顺序是「修一个多发的 bug 会换来一个全线不发的 bug」的那种敏感地带（第②档承载今天绝大多数流量），搬运时必须保持 `requested.isEmpty() || scope.containsAll(requested)` 这个复合条件逐字不变——把空集当成「无作用域」或当成「作用域为空」都会立刻炸掉线上不传订单行的全部券。② 顺带「把 capToOrderAmount 也换成按作用域基数封顶」看起来更正确，但那是改钱：金标 STACK 封顶用例断言的是订单金额 100，换基数就变成另一个数——本条明确不建议动封顶口径。③ 如果不做第 1 条的策略抽取，单独引入 BenefitScope 只是把 private static 换个位置，收益不足以单独立项。

#### 5. [P2] merge 里 STACK 与单选两条分支各自组装结果、各自调一次封顶、各自 return，没有共同出口；新增合并策略要复制这四步，漏掉封顶那一步是静默 fail-open。

**证据**：
- `/Users/liruijun/personal/LLM/drools-demo/activity-common/src/main/java/com/lrj/drools/activity/engine/BenefitEvaluator.java:319-334（STACK 分支：自己累加、自己 pickByPriority 选主活动、自己 capToOrderAmount、自己 return）`
- `/Users/liruijun/personal/LLM/drools-demo/activity-common/src/main/java/com/lrj/drools/activity/engine/BenefitEvaluator.java:336-349（单选分支：result.hit(winner) 后再调一次 capToOrderAmount）`
- `/Users/liruijun/personal/LLM/drools-demo/activity-common/src/main/java/com/lrj/drools/activity/engine/BenefitEvaluator.java:303-309（类注释自陈：三参 merge 重载被刻意删掉，因为「留着它，任何一个新调用点都能在毫无察觉的情况下绕过封顶，而这正是 fail-open 最典型的长法」——但方法体内部的两个出口正是同一个长法）`
- `/Users/liruijun/personal/LLM/drools-demo/activity-common/src/main/java/com/lrj/drools/activity/engine/BenefitEvaluator.java:316-317（eligible 为空时第三个出口，跳过封顶——今天靠 ActivityRuleResult.hitAmount 默认 ZERO 兜住，属于巧合而非设计）`
- `/Users/liruijun/personal/LLM/drools-demo/activity-common/src/main/java/com/lrj/drools/activity/domain/ActivityRuleResult.java:26,40-46（hit() 会额外 append 一条 BenefitOutcome，而 STACK 分支不走 hit()，两条分支在 benefits 列表上的产出不对称）`

**今天怎么伤人**：加第四种合并策略（例如「取金额前 N 大累加」「按券类型分组各取一张」）要复制 STACK 分支的四步；漏掉封顶那一步的表现是减免额可以超过订单金额，而这在监控上恰好看不见——clamped 指标是「正常业务恒为 0」的告警项，漏调封顶让它继续恒为 0，与健康状态完全同形。此外 benefits 列表在两条分支上产出不对称（单选有一条 BenefitOutcome、STACK 一条没有），这是留给「多权益并存」的前瞻结构，将来接上时会发现 STACK 下的明细是空的。

**方案**：模板方法 + 策略：把「选谁、选多少」抽成 MergePolicy，merge 退化成固定的四步模板（筛 eligible → policy.select → 组装 result → 统一封顶 → return），封顶只出现一次，物理上绕不过。为什么是模板方法而不是给每个策略一个完整 merge：需要保证的恰恰是「所有策略共享同一个出口」，模板方法是唯一能在结构上强制这件事的模式（策略模式单独用只会把封顶复制到每个策略里）。StackStrategy 枚举保持不变（它是外部契约，响应体和指标标签都带它），只是多一个 policyOf 映射。

```java
private interface MergePolicy {
    /** main 可为 null；trace 仅 explain 时使用 */
    record Selection(BigDecimal amount, ActivityCandidate main, boolean asHit, String trace) {}
    Selection select(List<ActivityCandidate> eligible, boolean explain);
}

public ActivityRuleResult merge(ActivityRuleContext ctx, List<ActivityCandidate> candidates,
                               StackStrategy strategy, boolean explain) {
    ActivityRuleResult r = new ActivityRuleResult();
    r.setStrategy(strategy);
    List<ActivityCandidate> eligible = candidates.stream().filter(ActivityCandidate::isEligible).toList();
    if (!eligible.isEmpty()) {
        MergePolicy.Selection s = policyOf(strategy).select(eligible, explain);
        // asHit 保留今天的不对称：单选走 hit()（会 append BenefitOutcome），STACK 只写标量
        if (s.asHit() && s.main() != null) r.hit(s.main());
        else { r.setHitAmount(s.amount()); if (s.main() != null) { r.setHitActivityId(s.main().getActivityId());
                                                                   r.setHitActivityName(s.main().getActivityName()); } }
        if (explain && s.trace() != null) r.trace(s.trace());
    }
    capToOrderAmount(ctx, r, explain);   // 唯一出口
    return r;
}
```

**迁移**：单文件、单方法内重构，一步到位即可。三个 policy 实现（STACK / MAX / PRIORITY+MUTEX）逐行搬现有分支，pickByAmount / pickByPriority 原样复用（严格 `>` 的先到先得平局规则不能动——它与 DecisionDataLoader.ordered() 的定序契约配套，见 CLAUDE.md「决策链路现状」）。trace 文案必须逐字保留（控制台试算展示 + e2e 断言）。安全网：DecisionGoldenSetTest 的 Merge 组 9 例（覆盖 MAX 三候选/单候选/零候选/金额并列、PRIORITY 数字小者胜/同 priority 比金额、MUTEX 同语义、STACK 累加/单候选）+ Ratio 组的 STACK 封顶用例。空候选走统一出口后会多跑一次 capToOrderAmount，那是 no-op（hitAmount 默认 ZERO ≤ order 直接 return），行为不变。

**风险**：① benefits 列表的不对称必须原样保留（asHit 位就是为它留的）——顺手让 STACK 也 append 一条会改变 ActivityRuleResult.benefits 的内容，那是 P1-10 留的前瞻结构，虽然今天没有读取方，但不该在这个批次里悄悄改。② 封顶统一出口后，若将来有人想给某个策略「不封顶」，会需要显式开洞——这是好事，但要在评审里说清，避免有人为了绕过而复活第二个出口。③ 今天只有 4 个策略且新增频率极低，如果近期没有新策略需求，这条可以排在前四条之后。

#### 6. [P2] DistributionMode 自带的 fromCode 在决策热路径上不可用（未知 code 抛异常会打断整批候选算额），于是求值层另写了一份私有的「未知回落固定」判别，写平面又手写了第三份比较——同一个枚举有三套解释规则，一致纯属巧合。

**证据**：
- `/Users/liruijun/personal/LLM/drools-demo/activity-common/src/main/java/com/lrj/drools/activity/domain/DistributionMode.java:25-31（fromCode：未知 code 抛 IllegalArgumentException）`
- `/Users/liruijun/personal/LLM/drools-demo/activity-common/src/main/java/com/lrj/drools/activity/engine/BenefitEvaluator.java:414-426（distributionOf：private static，注释明写「不能直接用 fromCode——那会让一条脏数据打断整批候选的算额」）`
- `/Users/liruijun/personal/LLM/drools-demo/activity-console/src/main/java/com/lrj/drools/activity/service/ActivityMarketingService.java:505-506（写侧第三份：手写 `DistributionMode.RANDOM_AMOUNT.code() == req.redPackageTakeType()`）`
- `/Users/liruijun/personal/LLM/drools-demo/frontend/src/console/logic.ts:159（前端第四份：`rule.redPackageTakeType === 2` 裸魔数）`

**今天怎么伤人**：生产真正执行的语义（未知 → 固定金额）藏在 BenefitEvaluator 的 private static 里，而枚举自己公开的 API 是一个在热路径上用了就出事的方法。任何第二个读 takeType 的地方都要重新决定一次「未知怎么办」，今天读写两侧碰巧一致，但没有任何东西保证下一处也一致；不一致的表现是同一条脏数据在写侧被当固定金额放行、在读侧被当随机型走区间解析（区间缺失 → 候选淘汰），即「创建成功、上线成功、就是不发钱」。这与 BenefitForm.of 的取向（未知回落 AMOUNT，且这个 fail-safe 是公开 API）形成刺眼的不对称——两个判别位，一个把 fail-safe 放在枚举上，一个把它藏在私有方法里。

**方案**：把 fail-safe 提到枚举上，与 BenefitForm.of 对齐：新增 `DistributionMode.of(Integer)` 作为**读路径唯一入口**（未知回落 FIXED_AMOUNT，绝不抛），把现有 fromCode 改名为 fromCodeStrict 并明确标注「仅写入口校验用——写侧就该拒绝脏数据」。这不是加抽象，是把已经存在的两套语义各自命名，让「读路径宽容、写路径严格」这条本项目一以贯之的取向在 API 上可见。同理顺手把 BenefitMath 的 scope=null 重载（第 4 条提到的）与前端的裸魔数 2 一起收进常量。

```java
public enum DistributionMode {
    FIXED_AMOUNT(1, "固定金额"), RANDOM_AMOUNT(2, "随机金额");

    /** 读路径唯一入口：未知 code 一律回落固定金额（与 BenefitForm.of 同取向，脏数据表现为「按旧行为发」）。
     *  绝不抛——一条脏配置不该打断整批候选的算额。 */
    public static DistributionMode of(Integer code) {
        return code != null && code == RANDOM_AMOUNT.code ? RANDOM_AMOUNT : FIXED_AMOUNT;
    }

    /** 写入口校验专用：未知 code 抛异常。写侧拒绝脏数据是对的，别在读侧调它。 */
    public static DistributionMode fromCodeStrict(Integer code) {
        if (code == null) return null;
        for (DistributionMode m : values()) if (m.code == code) return m;
        throw new IllegalArgumentException("未知发放方式 code: " + code);
    }
}
```

**迁移**：三处替换、零行为变化：BenefitEvaluator.distributionOf 删掉、调用点改 DistributionMode.of(c.getRedPackageTakeType())（逻辑逐字相同）；ActivityMarketingService:505-506 改 `DistributionMode.of(req.redPackageTakeType()) == RANDOM_AMOUNT`；fromCode 若在别处被调用，先 grep 确认（当前 grep 结果显示只有枚举内部与测试引用）。安全网：RandomAmountTest（随机形态判别与确定性金额）+ BenefitFormValidationTest + DecisionGoldenSetTest 全量。前端那处魔数换常量由 logic.test.ts 守。

**风险**：① 改名 fromCode → fromCodeStrict 是源码级不兼容改动，虽然是 internal 库但要一次改干净，否则会留下两个同义方法（比今天更糟）。② 若团队认为「读路径宽容」这条取向应该显式收紧成「未知 code 直接淘汰候选」，那属于改钱（今天未知 code 按固定金额发），必须单独立项对拍，不能塞进这次改名。③ 这条是纯优雅性收敛，不解决任何正在发生的故障；如果排期紧，它应该排在最后。

---

### 维度：写平面（activity-console）：ActivityMarketingService / ArtifactService / GenerationService / ActivityCreateRequest / ActivityMarketingController

#### 1. [P0] 「六种权益形态」在代码里没有任何单一表示——它实际是 (redPackageAmountUnit, redPackageTakeType, redPackageRangeAmount 的 JSON 形状) 三元组，写侧、读侧、前端各自把这个三元组重新拼一遍，三处之间没有任何编译期或测试期的同步机制。

**证据**：
- `/Users/liruijun/personal/LLM/drools-demo/activity-console/src/main/java/com/lrj/drools/activity/service/ActivityMarketingService.java:359-420（validateBenefitForm：按 form 的 if-else，FIXED_PRICE 另判 inventory、NTH_ZHE 另判折数域）`
- `/Users/liruijun/personal/LLM/drools-demo/activity-console/src/main/java/com/lrj/drools/activity/service/ActivityMarketingService.java:488-531（validateRangeColumn：同一个 BenefitForm.of 再判一次，并在 505-506 引入第三个判别位 redPackageTakeType）`
- `/Users/liruijun/personal/LLM/drools-demo/activity-common/src/main/java/com/lrj/drools/activity/engine/BenefitEvaluator.java:116-194（computeAmounts：第三次判别，顺序是 随机 → null guard → NTH → FIXED_PRICE → RATIO → 兜底 AMOUNT）`
- `/Users/liruijun/personal/LLM/drools-demo/activity-common/src/main/java/com/lrj/drools/activity/engine/BenefitEvaluator.java:420-426（distributionOf：读侧自己的 takeType 判别，与写侧 505-506 是两份代码）`
- `/Users/liruijun/personal/LLM/drools-demo/activity-common/src/main/java/com/lrj/drools/activity/domain/BenefitForm.java:62-73（枚举只有 4 个常量，覆盖不住文档里的 6 形态）`
- `/Users/liruijun/personal/LLM/drools-demo/frontend/src/console/logic.ts:148-160 与 :229（前端第三份判别，形态名是 6 个：fixed/random/ladder/ratio/price/nth）`
- `/Users/liruijun/personal/LLM/drools-demo/activity-console/src/main/java/com/lrj/drools/activity/service/ActivityMarketingService.java:478-484（注释自述：写侧只认识阶梯一种语义时，「第二件半价」与「随机金额红包」两种合法配置在写入口 100% 被拒，报错文案还讲的是运营从没碰过的阶梯）`

**今天怎么伤人**：这个缺陷已经造成过一次实际故障，注释就写在 ActivityMarketingService.java:478-484：读侧认识三种 range 语义、写侧只认识一种，于是两种合法玩法在写入口被全量拒绝。今天的代价可以量化：新增第七种形态要改 6 个文件的 9 处（BenefitForm 的 of/isSupportedUnit、validateBenefitForm、validateRangeColumn、BenefitEvaluator.computeAmounts、RandomRangeParser 或 LadderRangeParser、logic.ts 的 benefitFormOf 与 benefitRequestFields、playbooks.ts），漏写侧 = 合法配置被拒（已发生），漏读侧 = 按 AMOUNT 兜底把「打 8 折」当「减 8 元」发出去且日志全绿（BenefitEvaluator.java:178-179 自述）。读懂库里一个 redPackageRangeAmount 值，脑子里要同时装住 3 个隐式契约：顶层 JSON 类型、unit 判别位、takeType 判别位。

**方案**：密封接口 + 唯一判别工厂（不是策略注册表：形态数量固定且封闭，sealed 能换来 exhaustive switch 的编译期强制，注册表换不来）。把三元组一次性解释成一个类型化的 BenefitSpec，写侧校验与读侧求值都对同一个 sealed 层次做无 default 的 switch，新增形态时每个 dispatch 点都编译失败。BenefitMath 仍是唯一数学层，BenefitSpec 只回答「这个数是什么意思」。

```java
public sealed interface BenefitSpec {
    record Fixed(BigDecimal amount) implements BenefitSpec {}
    record Random(BigDecimal min, BigDecimal max) implements BenefitSpec {}
    record Ladder(List<LadderTier> tiers, BigDecimal fallback) implements BenefitSpec {}
    record Ratio(BigDecimal zhe, BigDecimal cap) implements BenefitSpec {}
    record FixedPrice(BigDecimal price) implements BenefitSpec {}
    record Nth(int n, BigDecimal zhe) implements BenefitSpec {}

    /** 全仓唯一判别点。分支顺序逐行照搬 BenefitEvaluator.computeAmounts 今天的顺序。 */
    static BenefitSpec of(String unit, Integer takeType, BigDecimal amount, String rangeJson) { /* … */ }
    /** 对外契约不变：ActivityCandidate.getBenefitForm() 仍输出 AMOUNT/RATIO_ZHE/FIXED_PRICE/NTH_ZHE */
    BenefitForm legacyForm();
}

// 写平面 validateBenefitForm 变成（无 default → 加形态即编译失败）：
switch (BenefitSpec.of(req.redPackageAmountUnit(), req.redPackageTakeType(),
                       req.redPackageAmount(), req.redPackageRangeAmount())) {
    case Ratio r      -> { requireZheRange(r.zhe()); requireCap(r.cap()); }
    case FixedPrice p -> { requirePositive(p.price()); requireInventory(req.inventory()); }
    case Nth n        -> requireZheRange(n.zhe());
    case Ladder l     -> requireRewardsWithin(l.tiers(), MAX_AMOUNT);
    case Random r     -> requireRange(r);
    case Fixed f      -> requireAmountWithin(f.amount(), MAX_AMOUNT);
}
```

**迁移**：三步，读侧放最后。① 只加 BenefitSpec.of，不接任何调用点，写一个对拍测试：对 BenefitFormValidationTest 与 DecisionGoldenSetTest 用到的全部输入组合，断言 of() 的分类结果与现有三处 if 链一致；② 写侧换成 exhaustive switch，安全网是 BenefitFormValidationTest + ActivityMarketingEdgeTest + FixedPriceAndClaimTest（金标集不覆盖写侧校验）；③ 读侧最后换，门禁是 DecisionGoldenSetTest（52 例）+ SnapshotParityTest + NotApplicableCandidateTest + RandomAmountTest，且 computeAmounts 的分支顺序必须逐行照搬——尤其「随机分支必须排在 redPackageAmount==null 的 guard 之前」（BenefitEvaluator.java:126-137）。前端形态名与 sealed 子类型对齐后，可由 /field-dict 下发形态清单，消掉第三处。

**风险**：③ 直接动钱。② 与 ③ 若分两次发布，中间态是「写侧按新判别放行/拒绝、读侧仍按旧 if 链算额」——必须保证 ② 只做更严的校验（只多拒不多放），否则会出现存得进去却算不出钱的配置。另有对外契约风险：BenefitForm 的四个名字是 ActivityCandidate.getBenefitForm()（ActivityCandidate.java:133）与 ActivityQueryService.java:262 的响应字段，sealed 层次必须额外提供 legacyForm() 保持四值输出。不该做的情况：如果近期没有新增形态的计划，做完 ①+② 就停，③ 的风险高于收益。

#### 2. [P0] 写平面只有「活动版本行」这一个概念，没有「活动」这个聚合，于是「当前是哪一版」在五个地方各解释一次，且分成两套互斥定义（最高未删版本 vs 最高 ONLINE 版本），null 还是第三义。

**证据**：
- `/Users/liruijun/personal/LLM/drools-demo/activity-console/src/main/java/com/lrj/drools/activity/service/ActivityMarketingService.java:132-134（编辑基线 = 最高未删版本）`
- `/Users/liruijun/personal/LLM/drools-demo/activity-console/src/main/java/com/lrj/drools/activity/service/ActivityMarketingService.java:223-227（changeStatus 的 version==null = 最高未删版本）`
- `/Users/liruijun/personal/LLM/drools-demo/activity-console/src/main/java/com/lrj/drools/activity/service/ActivityMarketingService.java:334-336（getDetail = 最高未删版本，即草稿）`
- `/Users/liruijun/personal/LLM/drools-demo/activity-console/src/main/java/com/lrj/drools/activity/service/ActivityMarketingService.java:859-864 与 :967-974（claim = 最高 ONLINE 版本）`
- `/Users/liruijun/personal/LLM/drools-demo/activity-common/src/main/java/com/lrj/drools/activity/service/DecisionDataLoader.java:277-291（决策 = 先按 ONLINE 过滤再取最高版本）`
- `/Users/liruijun/personal/LLM/drools-demo/activity-console/src/main/java/com/lrj/drools/activity/service/ActivityMarketingService.java:269-275（javadoc 自述已发生的事故：批量下线 23 个全打到草稿，正在发钱的线上版一个都没停，回执还报 23 个全部成功）`
- `/Users/liruijun/personal/LLM/drools-demo/activity-console/src/main/java/com/lrj/drools/activity/service/ActivityMarketingService.java:329-331（list() 返回全部版本行，同一活动多行，由前端自己归并）`

**今天怎么伤人**：「当前版本」有两套定义、五个调用点，靠 javadoc 要求调用方传显式 version 兜着（:274）——这条约定已经失效过一次并造成线上继续发钱、回执全绿的静默失败。今天仍有一处没修：控制台详情页（getDetail）恒定展示最高版本＝草稿，而决策服务的是线上版，于是运营在详情页看到的配置不是正在发钱的那一份，这条没有任何测试覆盖。加任何一个新写入口（复制活动、延长时间、批量改优先级）之前，必须先把这五处读一遍才知道该打哪一行。

**方案**：值对象 + 具名查询，把 Integer/null 这个三义参数换成类型。不用完整聚合根（JPA 实体已是行级模型，硬做聚合会牵动全部 repository），只引入一个把「一个活动的全部版本行」装起来的查询对象，对外只暴露两个具名出口。

```java
public sealed interface VersionSelector {
    record Exact(int version) implements VersionSelector {}
    /** 正在发钱的那一版：最高 ONLINE。下线 / claim / 决策对齐用它 */
    enum Serving implements VersionSelector { INSTANCE }
    /** 最高未删版本（可能是草稿）。编辑基线 / 详情用它 */
    enum Latest  implements VersionSelector { INSTANCE }
}

public record ActivityVersions(List<ActivityManageEntity> rows) {   // 一次查询，两个具名出口
    public Optional<ActivityManageEntity> serving() { /* max(version) where status==ONLINE */ }
    public Optional<ActivityManageEntity> latest()  { /* max(version) */ }
    public Optional<ActivityManageEntity> resolve(VersionSelector s) {
        return switch (s) {                       // 无 default
            case VersionSelector.Exact e -> rows.stream().filter(r -> r.getVersion() == e.version()).findFirst();
            case VersionSelector.Serving ignored -> serving();
            case VersionSelector.Latest  ignored -> latest();
        };
    }
}
// getDetail 返回 { serving, draft } 两块；changeStatus(String, VersionSelector, ActivityStatus)
```

**迁移**：小步三段。① 只加 ActivityVersions，让 currentOnlineVersion(:967) 与 getDetail(:334) 都改走它，行为逐字节不变；② changeStatus 加 VersionSelector 重载，旧 Integer 签名保留成 v==null ? Latest : Exact(v)，把内部调用点逐个换过去；③ 最后把 /list 与详情改成「一个活动一行 + servingVersion/draftVersion」的聚合视图。安全网：BulkStatusTest、GrantLedgerTest、DecisionScopeGoldenTest（v1/v2 并存那几例在 DecisionScopeGoldenTest.java:163-168）、SnapshotParityTest。

**风险**：③ 改响应形状，会打到前端活动列表与 e2e:catalog，必须与前端同批发。①② 零行为变更可单独上。不该做的情况：如果短期内不再加写入口，只做 ① 把两种定义各收敛成一个具名方法就已消掉大半风险，②③ 可以不做。

#### 3. [P1] create() 用 isEdit 布尔把「新建」「顶替草稿」「基于线上版开新草稿」三种业务动作压在一个方法里；而状态流转本身完全没有合法性表——changeStatus 只校验 targetStatus 是不是合法枚举码，不校验这次迁移是否被允许，迁移的副作用（四眼、退役旧线上版、bump 代际）散在三段独立 if 里。

**证据**：
- `/Users/liruijun/personal/LLM/drools-demo/activity-console/src/main/java/com/lrj/drools/activity/service/ActivityMarketingService.java:126-164（isEdit 分支：三条路径 + 两种并发保护）`
- `/Users/liruijun/personal/LLM/drools-demo/activity-console/src/main/java/com/lrj/drools/activity/service/ActivityMarketingService.java:229-230（只做 fromCode，无迁移合法性判断）`
- `/Users/liruijun/personal/LLM/drools-demo/activity-console/src/main/java/com/lrj/drools/activity/service/ActivityMarketingService.java:232-234（四眼只挂在 target==ONLINE）与 :237-246（指针切换也只在 target==ONLINE）`
- `/Users/liruijun/personal/LLM/drools-demo/activity-console/src/main/java/com/lrj/drools/activity/service/ActivityMarketingService.java:135-137（create 拒绝编辑 OFFLINE）与 :247-249（changeStatus 无条件把 OFFLINE 置回 ONLINE）`
- `/Users/liruijun/personal/LLM/drools-demo/activity-common/src/main/java/com/lrj/drools/activity/domain/ActivityStatus.java:10-13（PENDING_EFFECT=3 是 fromCode 认可的合法码）`
- `/Users/liruijun/personal/LLM/drools-demo/activity-common/src/main/java/com/lrj/drools/activity/service/DecisionDataLoader.java:282 与 /Users/liruijun/personal/LLM/drools-demo/activity-common/src/main/java/com/lrj/drools/activity/snapshot/DecisionSnapshotBuilder.java:74（读侧只认 ONLINE）`
- `/Users/liruijun/personal/LLM/drools-demo/activity-console/src/main/java/com/lrj/drools/activity/controller/ActivityMarketingController.java:101-104（/bulk-status 不校验 targetStatus，且一律 200）`

**今天怎么伤人**：三条今天就能触发的路径：① targetStatus=3 被完整接受并落库，控制台显示「待生效」而决策侧当它是下线——没有任何业务入口能让它「生效」，而 /bulk-status 一次能把几十个活动打进这个黑洞且回执全绿；② OFFLINE→ONLINE 直接复活一个 create() 明令拒绝编辑的活动（:135-137 vs :247），复活的是老配置且没人能改它；③ ONLINE→NORMAL 把正在服务的版本退回草稿，而退役/接管逻辑只在 target==ONLINE 时跑（:237），于是该活动一版都不在线、库里也没有任何东西记录这是一次「撤回发布」。读懂「发布」这个动作今天要同时看 create 的 148-160 与 changeStatus 的 237-249 两段，相隔 90 行。

**方案**：显式迁移表（状态机）把「合法迁移 + 该迁移的副作用」钉在一处；create 侧对称拆成两个命令对象共用模板方法，把 isEdit 的三层 if 变成两个实现类。选状态机而不是策略：这里要表达的是「从哪来到哪去」的二元关系与它的副作用集合，策略模式只能表达「到哪去」。

```java
enum Effect { FOUR_EYES, RETIRE_OTHER_ONLINE, BUMP_GENERATION }

enum Transition {
    PUBLISH  (NORMAL,  ONLINE,  EnumSet.of(FOUR_EYES, RETIRE_OTHER_ONLINE, BUMP_GENERATION)),
    TAKE_DOWN(ONLINE,  OFFLINE, EnumSet.of(BUMP_GENERATION)),
    REPUBLISH(OFFLINE, ONLINE,  EnumSet.of(FOUR_EYES, RETIRE_OTHER_ONLINE, BUMP_GENERATION)),
    WITHDRAW (ONLINE,  NORMAL,  EnumSet.of(BUMP_GENERATION));   // OfflinePropagationTest 在用，必须列为合法
    // PENDING_EFFECT 不出现在表里 = 没有任何入口能到达它
    final ActivityStatus from, to; final Set<Effect> effects;
    static Transition of(ActivityStatus from, ActivityStatus to) {
        return Arrays.stream(values()).filter(t -> t.from == from && t.to == to).findFirst()
            .orElseThrow(() -> new IllegalStateException("非法状态流转: " + from + " → " + to));
    }
}

// changeStatus 变成：查表 → 按 effects 逐个执行 → 落状态。三段 if 消失。
Transition t = Transition.of(ActivityStatus.fromCode(row.getActivityStatus()), target);
if (fourEyesEnabled && t.effects.contains(FOUR_EYES)) enforceFourEyes(row);
if (t.effects.contains(RETIRE_OTHER_ONLINE)) retireOtherOnline(activityId, row.getVersion());
row.setActivityStatus(target.code());
```

**迁移**：迁移表先以「只打点不拒绝」的形态上线一版，把线上真实出现过的 (from,to) 打出来，确认没有运营在用 PENDING_EFFECT 或别的路径，再改成拒绝。注意 OfflinePropagationTest.java:95-113 今天显式跑 ONLINE→OFFLINE→NORMAL→ONLINE 全链，迁移表必须把 WITHDRAW 与 REPUBLISH 列为合法，否则这条测试红——它是先改测试还是先改行为，需要拍板而不是顺手改。其余安全网：ActivityFourEyesTest、GenerationBumpTest、BulkStatusTest、TenantIsolationTest。

**风险**：把非法迁移改成拒绝是行为变更，且会让库里已经是 status=3 的历史行没有出口——迁移表必须给每个「历史可达但不再允许」的状态留一条单向出口（→OFFLINE）。不该做的情况：如果 PENDING_EFFECT 本来就是给「定时生效」预留的半成品而不是黑洞，那正确的顺序是先把定时生效做掉，再谈迁移表，否则会把一个待实现需求当成 bug 封死。

#### 4. [P1] 六张版本化子表各有一段手写 setter 映射，activityId / version / isDel=0 / createdStime / modifiedStime 这五个字段每段重抄一遍；同一个实体在两个类里各装配一次且字段集不同；七个实体类逐字重复 tenantId/isDel/两个时间戳四个字段，却没有 @MappedSuperclass。

**证据**：
- `/Users/liruijun/personal/LLM/drools-demo/activity-console/src/main/java/com/lrj/drools/activity/service/ActivityMarketingService.java:577-614 / 620-638 / 640-657 / 659-676 / 678-694 / 696-711（saveManage / saveRule / saveCondition / saveGifts / saveManualBindings / savePoolRefs 六段）`
- `/Users/liruijun/personal/LLM/drools-demo/activity-common/src/main/java/com/lrj/drools/activity/service/ActivityPoolMatchService.java:135-148（第二处装配 ActivitySpuBindingEntity：这里填 poolId，写平面 :678-694 那段不填）`
- `/Users/liruijun/personal/LLM/drools-demo/activity-console/src/main/java/com/lrj/drools/activity/ActivityDemoSeeder.java:69-77（第三处手写同一批脚手架字段）`
- `/Users/liruijun/personal/LLM/drools-demo/activity-common/src/main/java/com/lrj/drools/activity/persistence/ActivityManageEntity.java:41-43 与 ActivityRuleEntity.java:33-35、ActivityConditionEntity.java:34-36、ActivityGiftEntity.java:30-32、ActivitySpuBindingEntity.java:32-34、PoolRefEntity.java:30-32、ActivityStrategyEntity.java:32-34（七处逐字重复的 @TenantId 字段 + 同一段注释）`
- `全 main 源码里 setIsDel / setCreatedStime / setModifiedStime 共 66 处调用`

**今天怎么伤人**：新增一张版本化子表要凭记忆装住五条隐式契约，而漏任何一条都没有任何信号：漏 setIsDel(0) 会让列变 null，于是 DecisionDataLoader 那五次查询全部带的 isDel=0 谓词一条都命中不了——表现是「配置存进去了、控制台看得见、决策一分钱不发」；漏 setVersion 同理。没有编译期检查、没有运行期异常、没有测试覆盖这类遗漏。同一实体两处装配已经出现字段集分歧（poolId 填不填），这类分歧只会越走越远。

**方案**：两层 @MappedSuperclass + 一个把 (activityId, version, now) 一次性绑定的装配器。目的不是少打字，是让「漏填脚手架字段」变成不可表达——落库前必须先经过 belongTo。activity_grant 没有 is_del，所以基类必须拆两层而不是一层。

```java
@MappedSuperclass
public abstract class TenantRow {
    @TenantId @Column(name = "tenant_id") private String tenantId;
    @Column(name = "created_stime") private Instant createdStime;
    @Column(name = "modified_stime") private Instant modifiedStime;
    public void stamp(Instant now) { this.createdStime = now; this.modifiedStime = now; }
}

@MappedSuperclass
public abstract class VersionedRow extends TenantRow {   // ActivityGrantEntity 不继承这层（无 is_del）
    @Column(name = "activity_id") private String activityId;
    private Integer version;
    @Column(name = "is_del") private Integer isDel;
    @SuppressWarnings("unchecked")
    public <T extends VersionedRow> T belongTo(String activityId, Integer version, Instant now) {
        this.activityId = activityId; this.version = version; this.isDel = 0; stamp(now);
        return (T) this;
    }
}

// 写平面：save* 只描述差异字段，脚手架字段一律走 belongTo
ruleRepo.save(ActivityRuleEntity.from(req).belongTo(activityId, version, now));
bindingRepo.save(ActivitySpuBindingEntity.manual(b).belongTo(activityId, version, now));
```

**迁移**：纯映射搬迁，列名与 DDL 一个字节不变。分两步降风险：第一步只把 createdStime/modifiedStime 提到 TenantRow（无隔离语义，出错也只是时间戳），跑一遍 console 全量；第二步再提 tenantId 与 isDel，跑完 TenantIsolationTest 才算过。decision 侧的 ddl-auto: validate 是免费的验证器——字段落错列它会直接起不来。SnapshotParityTest 会照出走库/走快照两条路的字段差异。

**风险**：Hibernate 对 @MappedSuperclass 的字段访问类型（field vs property）必须与现有实体一致，否则 @TenantId 可能静默失效——租户列失效等于跨租户串数据，比这次重构本身严重得多，所以 tenantId 必须单独一步并以 TenantIsolationTest 为门禁。不该做的情况：如果短期内不再加子表，只做装配器（belongTo / 静态工厂）不做 @MappedSuperclass，收益的八成已经拿到、租户风险为零。

#### 5. [P1] 一个 @Service 977 行、15 个构造器注入，同时是六张配置表的写入口、发布代际传播点、秒杀发放台账和幂等登记处；三条互不相干的变更线共用一个文件，还在同一个类里混了两套事务语义。

**证据**：
- `/Users/liruijun/personal/LLM/drools-demo/activity-console/src/main/java/com/lrj/drools/activity/service/ActivityMarketingService.java:76-106（15 参构造：9 个 repository + 6 个 service）`
- `/Users/liruijun/personal/LLM/drools-demo/activity-console/src/main/java/com/lrj/drools/activity/service/ActivityMarketingService.java:848-924 / 938-958 / 960-964（claim / release / grantsOfOrder 与 create、changeStatus 同居一类，但与它们零共享状态，只共用 blankToNull 与 NOT_DEL 常量）`
- `/Users/liruijun/personal/LLM/drools-demo/activity-console/src/main/java/com/lrj/drools/activity/service/ActivityMarketingService.java:110（create 是 @Transactional(rollbackFor = Exception.class)）与 :848（claimInventory 是裸 @Transactional，只回滚 RuntimeException），两者相隔 738 行且无任何交叉提示`
- `/Users/liruijun/personal/LLM/drools-demo/activity-console/src/main/java/com/lrj/drools/activity/service/ActivityMarketingService.java:69-70（objectMapper 与 AtomicInteger seq 作为实例字段挂在同一个类上，一个服务条件树序列化、一个服务 ID 生成）`
- `/Users/liruijun/personal/LLM/drools-demo/activity-console/src/main/java/com/lrj/drools/activity/service/ActivityMarketingService.java:216（updateByVersion 自调用 create——同类自调用不过 Spring 代理，create 自己的 @Transactional 今天根本没生效）`

**今天怎么伤人**：活动配置、发布传播、发放台账三条变更线改同一个文件，每次改都要重跑这个类牵动的 244 例集成测试。console-write-authority 的保护面是按方法枚举的，加一个写方法就要记得同步一次。最刺人的是事务语义：同一个类里两套 rollbackFor，且 updateByVersion→create 的自调用让 create 的事务注解在这条路径上完全失效——这类陷阱在 977 行、15 依赖的类里没有人会主动去核对。

**方案**：按限界职责切三个协作者，ActivityMarketingService 退化成委派壳，controller 与全部现有测试一行不改。这里不是要引入新抽象，只是把已经存在的三条接缝显式化。

```java
@Service
public class ActivityMarketingService {          // 只剩委派：方法签名与返回类型逐字不变
    private final ActivityAuthoringService authoring;   // create/updateByVersion/preview/detail/list
    private final ActivityLifecycleService lifecycle;   // changeStatus/bulk/四眼/artifact+generation
    private final GrantLedgerService       grants;      // claim/release/grantsOfOrder

    public CreateResult create(ActivityCreateRequest r) { return authoring.create(r); }
    public CreateResult updateByVersion(ActivityCreateRequest r) { return authoring.revise(r); }
    public CreateResult changeStatus(String id, Integer v, Integer s) { return lifecycle.changeStatus(id, v, s); }
    public BulkStatusResult bulkChangeStatus(List<BulkStatusItem> it, Integer s) { return lifecycle.bulk(it, s); }
    public ClaimResult claimInventory(String id, Integer v, Integer n, String u, String o) {
        return grants.claim(id, v, n, u, o);
    }
    public ClaimResult releaseGrant(String orderId, String id) { return grants.release(orderId, id); }
}
// 依赖数：authoring 10、lifecycle 3、grants 2、facade 3 —— 没有一个再回到 15
```

**迁移**：纯搬移，一次一个职责。先把 CreateResult / ClaimResult / ActivityDetail / BulkStatus* 这些内部 record 顶层化（它们出现在方法签名里，不先移会让 import 全仓乱），编译不过的地方就是需要跟进的调用点，测试会立刻暴露。然后依次搬 grants（最独立）→ lifecycle → authoring。安全网是编译 + console 全量集成测试。

**风险**：事务边界是唯一的真风险点：updateByVersion→create 今天是自调用（create 的 @Transactional 不生效），拆到不同 bean 后会突然经过代理生效。两者今天的属性恰好相同（都是 rollbackFor=Exception.class），所以理论上等价，但这一步必须单独一个 commit 并由 ActivityIdempotencyTest 确认 requestId 冲突时的回滚语义没变。不该做的情况：如果这一轮已经决定做前面的形态收敛与版本收敛，切分应排在最后——三处 diff 同时动这个文件会互相打架。

#### 6. [P2] claim / release 的结果分类靠 boolean ok + 中文 reason 字符串承载，controller 只能按一个布尔映射 HTTP 状态码，于是四种语义完全不同的失败被压成同一个码，而设计意图恰恰是要靠状态码分流。

**证据**：
- `/Users/liruijun/personal/LLM/drools-demo/activity-console/src/main/java/com/lrj/drools/activity/service/ActivityMarketingService.java:806-812（ClaimResult 七个字段，ok/replay/reason 三者组合出六种含义）`
- `/Users/liruijun/personal/LLM/drools-demo/activity-console/src/main/java/com/lrj/drools/activity/service/ActivityMarketingService.java:853 / 855 / 862 / 868 / 887 / 892 / 921（七个不同失败原因，全部只靠 reason 字符串区分：缺 activityId、数量非正、无上线版本、版本不存在、缺 userId、超每人限领、库存不足）`
- `/Users/liruijun/personal/LLM/drools-demo/activity-console/src/main/java/com/lrj/drools/activity/controller/ActivityMarketingController.java:123（claim：ok ? 200 : 409）`
- `/Users/liruijun/personal/LLM/drools-demo/activity-console/src/main/java/com/lrj/drools/activity/controller/ActivityMarketingController.java:136（release：ok ? 200 : 404）与 ActivityMarketingService.java:941-943（release 缺 orderId/activityId 也走 ok=false → 客户端收到 404）`
- `/Users/liruijun/personal/LLM/drools-demo/activity-console/src/main/java/com/lrj/drools/activity/controller/ActivityMarketingController.java:110-114（javadoc 明写「用 409 而不是 200+ok:false，是为了让调用方的重试/降级逻辑能靠状态码分流」）`

**今天怎么伤人**：分流的前提是状态码有区分度，今天没有：参数错误、活动不存在、超每人限领、库存不足四种语义在 HTTP 层都是 409，调用方要区分只能匹配中文 reason 字符串，改一个字就是破坏性变更。release 侧后果更实：orderId 传空会返回 404「没有对应的发放记录」，客户端会把自己的参数 bug 当成「这一单本来就没领过」而放弃冲正——库存就此永久蒸发，而这正是 release 这条路径存在的全部理由。

**方案**：密封接口 + 模式匹配（Java 21），只改「决定状态码」这一层，响应体 JSON 一个字节不变。选 sealed 而不是错误码枚举：枚举仍然可以被 default 分支吞掉，sealed switch 在新增结果类型时强制每个出口显式选一次状态码。

```java
public sealed interface ClaimOutcome {
    record Granted(String activityId, int version, int qty, long grantId) implements ClaimOutcome {}
    record Replayed(String activityId, int version, int qty, long grantId) implements ClaimOutcome {}
    record SoldOut(String activityId, int version) implements ClaimOutcome {}
    record PerUserLimit(String activityId, int already, int want, int limit) implements ClaimOutcome {}
    record BadRequest(String what) implements ClaimOutcome {}
    record NotFound(String what) implements ClaimOutcome {}
}

// controller：无 default → 新增结果类型必须显式选状态码
return switch (marketing.claim(...)) {
    case ClaimOutcome.Granted  g -> ResponseEntity.ok(ClaimResult.of(g));
    case ClaimOutcome.Replayed r -> ResponseEntity.ok(ClaimResult.of(r));
    case ClaimOutcome.SoldOut  s -> ResponseEntity.status(409).body(ClaimResult.of(s));
    case ClaimOutcome.PerUserLimit p -> ResponseEntity.status(409).body(ClaimResult.of(p));
    case ClaimOutcome.BadRequest b -> ResponseEntity.badRequest().body(ClaimResult.of(b));
    case ClaimOutcome.NotFound  n -> ResponseEntity.status(404).body(ClaimResult.of(n));
};
```

**迁移**：两个 commit。第一个：内部返回类型换成 ClaimOutcome，ClaimResult.of(outcome) 逐条产出与今天完全相同的字段值（含 reason 的中文原文），controller 仍按 ok 映射 → 零行为变更，GrantLedgerTest / FixedPriceAndClaimTest 全绿即证明等价。第二个：把状态码映射切到 switch，这一步是契约变更（引入 400），需要前端与调用方确认。注意 FixedPriceAndClaimTest 那 9 例吃 h2 文件库，本地要串行跑。

**风险**：第二步会打到任何按 409 无脑重试的调用方——参数错误从「被重试掉」变成「立刻 400 失败」，这正是目的，但要提前通知而不是顺手改。不该做的情况：如果 claim/release 目前只有本仓前端一个调用方，第二步的收益有限，做完第一步（把结果分类从字符串搬到类型上）即可。

---

### 维度：领域模型（activity/domain + engine 的条件树/schema 层）

#### 1. [P0] 「权益形态」是这个领域最核心的多态点，却没有任何类型：BenefitForm 只是个字符串判别位，六形态的行为散在 BenefitEvaluator 的 if-链、写平面两个 validate 方法和 BenefitMath 里，没有一处能被编译器强制穷尽。

**证据**：
- `activity-common/src/main/java/com/lrj/drools/activity/engine/BenefitEvaluator.java:116-193 —— computeAmounts 用 `if (form == BenefitForm.NTH_ZHE)` / `FIXED_PRICE` / `RATIO_ZHE` 的 if-链，不是 switch；末尾 191 行裸落 default`
- `activity-common/src/main/java/com/lrj/drools/activity/engine/BenefitEvaluator.java:129 与 149 —— 随机分支必须排在 `redPackageAmount == null` 这道 guard 之前，形态间的**顺序**是隐式契约，注释写了 3 段来解释`
- `activity-console/src/main/java/com/lrj/drools/activity/service/ActivityMarketingService.java:359-420 validateBenefitForm —— 366 行 `if (form != BenefitForm.RATIO_ZHE)` 是个「非 A 即其余」的兜底分支`
- `activity-console/src/main/java/com/lrj/drools/activity/service/ActivityMarketingService.java:488-517 validateRangeColumn —— 同一列按形态分叉，末尾 514 行落阶梯解析`
- `activity-common/src/main/java/com/lrj/drools/activity/domain/BenefitForm.java:55-73 —— UNIT_ 常量 / of() / isSupportedUnit() 三处并列，加一种形态要同时改`
- `全仓库 `grep 'switch (form)'` 零命中；对比 ConditionTreeEvaluator.java:109 的穷尽 `switch (op)`（加一个算子会编译报错）——同一个仓库里两种做法并存，说明不是做不到`

**今天怎么伤人**：加第七种形态（比如「满 N 件减 M 元」「阶梯折」）要改 5 个文件至少 9 处：BenefitForm 4 处、BenefitMath 1 个函数、BenefitEvaluator 1 个 if 且**必须插对位置**、validateBenefitForm 1 处、validateRangeColumn 1 处、前端单位下拉 1 处。其中三处漏了**不报编译错、不抛异常**：① BenefitEvaluator 漏改 → 新形态落到 191 行的裸 default，把折数/一口价当元发出去（就是当年「打 8 折被当成减 8 元」那条注释描述的事故）；② validateBenefitForm 漏改 → 新形态落进 366 行的非折扣分支，继承「redPackageMaxDiscount 必须为空」(370) 和 [0,MAX_AMOUNT] 金额护栏 (367)，对一个折数型形态是错的护栏；③ validateRangeColumn 漏改 → 落到 514 行阶梯解析，合法配置在写入口 100% 被拒且报错文案讲的是运营没碰过的「阶梯分档」——这个坑历史上已经踩过一次，480-483 行的注释就是它的现场。

**方案**：用**密封接口 + 注册表**（不是访问者、不是模板方法）：形态数量有限且封闭，密封接口能让 `switch` 穷尽检查生效；每个形态的「怎么算钱」和「写入口怎么校验」是同一份知识，应该在同一个文件里，所以注册表键 BenefitForm、值实现 compute + validate 两个方法。不选访问者是因为访问者要为每个新操作改所有形态类；不选模板方法是因为六形态之间没有公共骨架（随机型连 redPackageAmount 都不读）。算数仍然只调 BenefitMath，本重构一行数学都不碰。

```java
public sealed interface BenefitRule
        permits AmountRule, RandomRule, RatioRule, FixedPriceRule, NthZheRule {

    /** 没配 redPackageAmount 时算不算「无金额来源」。随机型 false——它的钱来自区间 */
    default boolean needsConfiguredAmount() { return true; }

    /** @return amount 非 null = 算出来了；否则 rejectCode/why 交给 notApplicable */
    Outcome compute(ActivityRuleContext ctx, ActivityCandidate c);

    /** 写入口校验：形态知识只此一份，validateBenefitForm/validateRangeColumn 委托过来 */
    void validate(ActivityCreateRequest req);

    record Outcome(BigDecimal amount, String rejectCode, String why) {
        static Outcome ok(BigDecimal a) { return new Outcome(a, null, null); }
        static Outcome no(String c, String w) { return new Outcome(null, c, w); }
    }
}

@Component
public class BenefitRules {
    private final Map<BenefitForm, BenefitRule> byForm;   // 构造时断言覆盖 values()，漏一个直接启动失败
    private final BenefitRule random;
    public BenefitRule resolve(BenefitForm f, DistributionMode m) {
        return (f == BenefitForm.AMOUNT && m == DistributionMode.RANDOM_AMOUNT) ? random : byForm.get(f);
    }
}
```

**迁移**：可小步，且每步行为等价可验：① 先只搬 compute——把 BenefitEvaluator.computeAmounts 的五个分支逐个抽成 BenefitRule 实现，方法体逐字节复制（含 `if (off == null) notApplicable(...)` 的 reasonCode 字符串），computeAmounts 缩成「resolve → needsConfiguredAmount 门 → compute → set 或 reject」约 12 行；关键是把 149 行那道 ladder guard 表达成 `needsConfiguredAmount()`，随机型返回 false 就与今天的分支顺序完全等价，包括「固定金额覆盖阶梯」这个必须保留的线上语义。② 再搬 validate（console 侧），此步只动写平面。安全网：DecisionGoldenSetTest 52 例 + NotApplicableCandidateTest（legitimateZeroSurvives 那条专门守首档 0 元）+ RandomAmountTest（确定性金额）+ BenefitFormValidationTest + SnapshotParityTest；建议第 ① 步做完先只跑 common+console 全量，再做第 ②。另加一条新守卫：注册表构造时断言 `EnumSet.allOf(BenefitForm.class)` 全覆盖，把「加了形态忘了实现」从静默发错钱变成启动失败。

**风险**：最大的风险是**顺序语义在抽取中走样**——今天的 if-链里，随机型在 amount==null guard 之前、其余在之后，这个位置差别决定了「同时配阶梯和固定金额时谁覆盖谁」。抽取时如果把 guard 也下放到各形态里，AMOUNT 与 NTH/FIXED/RATIO 就会各自实现一遍，反而多了漂移点。必须保留成编排层的单一门（needsConfiguredAmount），并在 PR 里贴出改造前后 computeAmounts 的逐分支对照。第二个风险是有人趁机「修好」固定金额覆盖阶梯——那是当前线上语义，必须单独立项。**不该做的情况**：如果近半年没有新增形态的计划，且团队没人能一次跑通 52 例金标 + parity，这条的收益要等到下一次加形态才兑现，可以排在第 2 条之后。

#### 2. [P0] ActivityCandidate 把「活动配置」和「本次决策的计算草稿」焊在同一个可变 POJO 上，直接逼出了一个 19 字段的影子类 CandidateTemplate、两条各自手写的装配路径、以及一条只写在注释里的「用完手工清三个字段」契约。

**证据**：
- `activity-common/src/main/java/com/lrj/drools/activity/domain/ActivityCandidate.java:16-79 —— 19 个配置字段与 6 个计算态字段（eligible/rejectReason/computedAmount/amountComputed/ladderApplied/gifts）同居`
- `activity-common/src/main/java/com/lrj/drools/activity/snapshot/DecisionSnapshot.java:151-157 —— 类注释明写「不能直接复用 ActivityCandidate——规则执行期会 modify 它的字段。快照必须是只读的」，于是复制出 CandidateTemplate`
- `activity-common/src/main/java/com/lrj/drools/activity/service/DecisionDataLoader.java:322-352 flatten（20 行 setter）vs activity-common/.../snapshot/DecisionSnapshotBuilder.java:117-128 + DecisionSnapshot.java:178-201 toCandidate（15 行 setter）—— 同一份 (ActivityManageEntity, ActivityRuleEntity, gifts) → 候选 的映射写了两遍`
- `activity-common/src/main/java/com/lrj/drools/activity/snapshot/DecisionSnapshot.java:194 —— 「漏拷这一行的表现是「快照路径不封顶、DB 路径封顶」」，这条注释本身就是一次已发生的漂移`
- `CLAUDE.md 坑 16（scopedSpuIds 只填一边 → 两条路发不同的钱）—— 同一条缝的第二次事故`
- `activity-common/src/main/java/com/lrj/drools/activity/service/ActivityQueryService.java:297-303 safeFallback —— 手工清 computedAmount/amountComputed/ladderApplied 三个字段，注释解释「留着上一轮的 true，本轮没落档的候选就淘汰不掉了」`
- `activity-common/src/main/java/com/lrj/drools/activity/domain/ActivityCandidate.java:37-38 extraConfigType/extraDataJson —— 两条装配路径都不填、全仓库无读者，已经漂成死字段`
- `activity-common/src/main/java/com/lrj/drools/activity/domain/ActivityCandidate.java:132-133 getBenefitForm() —— 注释称「DRL 的 LHS 用它做判别」，但 ActivityDrlBuilder.java:79/89/119/139 里 DRL 只碰 activityId / eligible / gifts 三个访问器，这个 getter 已无调用方`

**今天怎么伤人**：① 加一个配置字段（下一次业务扩展几乎必然发生）要改 5 处：ActivityCandidate 字段+getter/setter、flatten、CandidateTemplate 分量、DecisionSnapshotBuilder 构造实参、toCandidate。漏任意一处的表现是「同一张券在走库与走快照两条路上发不同的钱」——不报错、不回退、日志干净，只有对账时才发现，这已经发生过两次（scopedSpuIds、redPackageMaxDiscount）。② 加一个计算态字段还要额外记得改 safeFallback 的清理清单，这条契约在类型系统里没有任何表示。③ 读懂一次决策要同时在脑子里装住：哪些字段是配置（只读）、哪些是本轮草稿、哪三个要清、哪一个（rejectReason/eligible）故意不清、null 与空集在 scopedSpuIds 上语义不同——五个隐式契约，全靠注释。④ CandidateTemplate 是 19 个分量的位置参数 record，DecisionSnapshotBuilder:117-128 那次调用有 8 个相邻的同类型参数（Integer/String），换位不会编译失败。

**方案**：拆成**不可变值对象 + 每次决策一份的可变求值态**：OfferSpec（record，装「活动这一版长什么样」）+ ActivityCandidate（持 OfferSpec 只读引用 + 本轮计算态）。关键收益不是「优雅」，而是把两条装配路径收敛成**一个静态工厂** `OfferSpec.from(manage, rule, gifts)`——CandidateTemplate 直接消失（快照存 OfferSpec 本身，它本来就是不可变的），坑 16 那类漂移变成结构上不可能。计算态的「重置」从手工清三个字段变成 `spec.newCandidate(scope)` 重新 new 一个，safeFallback 的隐式契约随之消失。选值对象而不是「给 ActivityCandidate 加个 reset()」，是因为 reset() 仍然要人记得列全字段——问题一模一样。

```java
/** 活动这一版长什么样。快照直接存它；走库/快照/手工构造三条路唯一的装配出口 */
public record OfferSpec(String activityId, String activityName, Integer activityType, String bizLine,
                        Integer version, int priority, Instant startTime, Instant endTime,
                        Integer redPackageTakeType, BigDecimal redPackageAmount,
                        String redPackageAmountUnit, String redPackageRangeAmount,
                        BigDecimal redPackageMaxDiscount, List<GiftResult> gifts) {

    public static OfferSpec from(ActivityManageEntity m, ActivityRuleEntity r, List<GiftResult> gifts) { /* 唯一一份 */ }

    public BenefitForm form() { return BenefitForm.of(redPackageAmountUnit); }

    /** scopedSpuIds 是逐请求的交集，只能在这里传入，不能冻进 spec（null=作用域未知，语义不变） */
    public ActivityCandidate newCandidate(Set<Long> scopedSpuIds, boolean withGifts) { … }
}

public final class ActivityCandidate {
    private final OfferSpec spec;            // 配置：只读
    private final Set<Long> scopedSpuIds;
    private boolean eligible = true; private String rejectReason;
    private BigDecimal computedAmount = BigDecimal.ZERO;
    private boolean amountComputed, ladderApplied;

    /** DRL 只需要这三个访问器 + reject()，委托给 spec，买赠 DRL 一个字都不用改 */
    public String getActivityId() { return spec.activityId(); }
    public boolean isEligible() { return eligible; }
    public List<GiftResult> getGifts() { return spec.gifts(); }
}
```

**迁移**：三小步，每步可独立合并：① 先建 OfferSpec + from()，让 flatten 与 DecisionSnapshotBuilder **都改成调它**，ActivityCandidate 暂时不动（仍然 setter 装配，只是数据从 spec 来）——这一步就已经消灭了两条路的字段漂移，SnapshotParityTest 是现成的安全网。② 把 CandidateTemplate 换成 OfferSpec（快照类少一个 19 分量 record），materialize 改调 newCandidate。③ 最后才把 ActivityCandidate 的配置字段换成 spec 委托，同时删掉 extraConfigType/extraDataJson 与 getBenefitForm()（已确认零调用方，含 DRL）。**DRL 连带成本比文档估计的低**：grep 证实 ActivityDrlBuilder 只用到 activityId/eligible/gifts + reject()，其余 15 个字段改名对 DRL 无影响。安全网：SnapshotParityTest（narrowedBindingStopsPayingOnBothPaths 那条正是守这条缝的）、DecisionGoldenSetTest 52 例、BenefitScopeTest、DecisionQueryCountTest（保证第 ① 步没多查库）。

**风险**：① 第 ③ 步动的是 DRL fact 的访问器面，买赠是唯一还在跑 DRL 的通道，且 DRL 是运行时编译——`mvn compile` 绿不代表没坏。必须在这一步后跑一次真实的买赠冒烟请求（CLAUDE.md 坑 4/6），不能只看单测。② OfferSpec 若做成不可变 record，`gifts` 必须 List.copyOf，而 DecisionSnapshot 今天靠 toCandidate 里的 `new ArrayList<>(gifts)` 给每次决策一份可写副本——如果买赠 DRL 或将来某处会往 gifts 里 add（ActivityCandidate.addGift 就是干这个的），改成共享不可变 List 会抛 UnsupportedOperation。这一点必须在第 ② 步前先确认 addGift 的生产调用方（今天看是零）。**不该做的情况**：如果近期要动快照的存储形态（比如序列化到堆外/Redis），先别做，等那个决策落定——OfferSpec 的形状会被它反向约束。

#### 3. [P1] 决策上下文的属性键是散落在 5 个文件里的字符串字面量，写侧、读侧、白名单三者之间没有任何类型链接；而其中两个最要命的键（randomSeedSpu、orderLines）恰恰不在唯一那道守卫的覆盖范围内，测试自己还硬编码了同一批字面量，导致重命名写侧可以全绿通过。

**证据**：
- `activity-common/src/main/java/com/lrj/drools/activity/service/DecisionEligibilityService.java:56-74 —— 写侧 9 个字面量 key`
- `activity-common/src/main/java/com/lrj/drools/activity/domain/ActivityRuleContext.java:94 / 104 / 120 —— 读侧字面量 "orderAmount" / "spuId" / "orderLines"`
- `activity-common/src/main/java/com/lrj/drools/activity/engine/BenefitEvaluator.java:449-453 —— 读侧 "userId" / "orderAmount" / "quantity" / "randomSeedSpu"（种子指纹链）`
- `activity-common/src/main/java/com/lrj/drools/activity/engine/RuleSchemaRegistry.java:104-126 —— 白名单里第三份字面量`
- `activity-common/src/main/java/com/lrj/drools/activity/service/ActivityQueryService.java:402 —— ladderField 写死 "orderAmount"`
- `activity-common/src/test/java/com/lrj/drools/activity/DecisionContextFieldsTest.java:43-55 —— 唯一守卫，只断言「白名单 ⊆ 写侧键」；orderLines/randomSeedSpu/userId **不在白名单里**，完全没被钉住`
- `activity-common/src/test/java/com/lrj/drools/activity/RandomAmountTest.java:54 —— 测试自己 `c.putAttr("randomSeedSpu", 990011L)`，绕开了 requestAttributes`
- `activity-common/src/test/java/com/lrj/drools/activity/SpuIdConditionCompatTest.java:104 —— `assertEquals(null, attrs.get("randomSeedSpu"), …)`，断言的是「值为 null」，键改名后 get 返回 null，照样绿`
- `activity-common/src/test/java/com/lrj/drools/activity/BenefitScopeTest.java:208-210 —— 同样硬编码 "orderAmount"/"spuId"/"orderLines"`

**今天怎么伤人**：把 DecisionEligibilityService:73 的写侧键从 randomSeedSpu 改成别的（重构、拼写订正、跟前端对齐命名，都是一次性 IDE 重命名会漏掉字面量的典型场景），生产的指纹第三段立刻变成 "null"——**全量随机红包一次性重抽**，用户刷新变价、历史对账全断。而全仓库**没有一个测试会红**：RandomAmountTest 自己写死了旧键、SpuIdConditionCompatTest 只断言 null、DecisionContextFieldsTest 根本不看这个键。同理把 orderLines 改名 → 第 N 件折与「作用域是真子集」两条路径全部 fail-closed 静默不发，只有 NthItemDiscountTest 因为走了 requestAttributes 会红（它是唯一一条走对了的）。今天读懂一次决策要同时记住：哪些键是运营可配的白名单字段、哪三个键（userId/randomSeedSpu/orderLines）是代码内部用、不该被条件引用、且没有守卫。

**方案**：引入**类型化属性键**（AttrKey<T> 值对象）作为「代码引用的那批键」的唯一定义点，把三处字面量收敛成一处常量。刻意**不**把整个属性袋换成 typed POJO——白名单本来就是租户可配的数据驱动 schema（RuleSchemaRegistry.register），Map 支撑是对的；问题只出在「代码里硬引用的那 4-5 个键」上，它们本质是编译期已知的常量，就该有编译期身份。DRL 左值访问器 numberAttr/textAttr/listAttr 的签名一个字不动（它们的 key 来自 SchemaField.key()，是运行时数据）。

```java
/** 代码内部硬引用的属性键。运营可配的 schema 字段仍走字符串，不受影响 */
public record AttrKey<T>(String key) {
    public static final AttrKey<BigDecimal> ORDER_AMOUNT = new AttrKey<>("orderAmount");
    public static final AttrKey<BigDecimal> QUANTITY     = new AttrKey<>("quantity");
    public static final AttrKey<Long>       USER_ID      = new AttrKey<>("userId");
    /** 随机红包确定性种子的 SPU 段。**改这个字符串 = 全量红包重抽**，见 CLAUDE.md 坑 15 */
    public static final AttrKey<Long>       RANDOM_SEED_SPU = new AttrKey<>("randomSeedSpu");
    public static final AttrKey<List<SpuDiscountRequest.OrderLine>> ORDER_LINES =
            new AttrKey<>("orderLines");
}

// ActivityRuleContext 增两个方法，旧的 numberAttr/textAttr/listAttr 原样保留（DRL 左值）
public <T> void put(AttrKey<T> k, T v) { putAttr(k.key(), v); }
public BigDecimal number(AttrKey<BigDecimal> k) { return numberAttr(k.key()); }

// BenefitEvaluator.drawRandom：
//   ctx.textAttr("randomSeedSpu")  →  ctx.text(AttrKey.RANDOM_SEED_SPU)
// requestAttributes 用 AttrKey 常量做 key，字面量全仓库只剩 AttrKey 一处
```

**迁移**：一次性小改动，纯机械：把 5 个文件里的字面量替换成 AttrKey 常量引用即可，字符串值一个字节不变 → **产出的指纹与今天逐字节相同**，随机金额不会变（这是行为等价的证明方式，不靠测试）。再补两条守卫把测试网的洞堵上：① 一条断言 `requestAttributes(sample()).keySet()` 恰好等于 AttrKey 常量集 ∪ 白名单集（多一个少一个都红）；② 把 RandomAmountTest:45-54 与 BenefitScopeTest:208-210 的手写 putAttr 改成走 requestAttributes 或 AttrKey 常量，让它们真的能感知重命名。安全网：改完 RandomAmountTest 的具体金额断言必须**原样通过**——如果它变了，说明指纹被改动了，立刻回退。

**风险**：风险很低但有一个真陷阱：如果有人在做这个替换时顺手把 `randomSeedSpu` 这个字符串「统一」成 `seedSpu`，就把一个零风险重构变成了全量红包重抽。所以 AttrKey.RANDOM_SEED_SPU 的字段注释必须把这条写死在常量旁边（骨架里已写），并且 PR 里要求 diff 中 `"randomSeedSpu"` 这个字面量出现且仅出现一次。**不该做的情况**：如果计划让 Track B 的租户级 schema 覆盖这几个内部键（今天不会，它们刻意不在白名单里），typed key 会变成假承诺——那种情况下应该先决定内部键与 schema 键的边界。

#### 4. [P1] 12 个资格算子的语义写了两遍——ConditionTreeEvaluator 直接解释一遍、RuleConditionTranslator emit 成 DRL 又一遍，靠注释里的「必须逐条对齐」维持；而 javadoc 声称守住等价性的对拍测试在仓库里根本不存在，ARRAY 兼容层的两个 default 分支还会让新增算子静默漏配。

**证据**：
- `activity-common/src/main/java/com/lrj/drools/activity/engine/ConditionTreeEvaluator.java:92-160 —— ARRAY 兼容 4 case + 主 switch 12 case`
- `activity-common/src/main/java/com/lrj/drools/activity/engine/RuleConditionTranslator.java:86-138 —— 同样 ARRAY 兼容 4 case + 主 switch 12 case，是同一套语义的第二份实现`
- `activity-common/src/main/java/com/lrj/drools/activity/engine/RuleConditionTranslator.java:84-85 —— 注释：「与 ConditionTreeEvaluator 的存量兼容层**逐条对齐**。两处不一致的后果是「控制台预览通过、线上求值结论相反」」`
- `activity-common/src/main/java/com/lrj/drools/activity/engine/ConditionTreeEvaluator.java:40 —— 「等价性由 DroolsEligibilityGoldenSetTest 用同一组金标断言在两条路上对拍」；`find . -name '*EligibilityGoldenSet*'` 零命中，该测试不存在`
- `activity-common/src/main/java/com/lrj/drools/activity/engine/ConditionTreeEvaluator.java:105 与 RuleConditionTranslator.java:107 —— 两处 `default -> { }`，新增算子在 ARRAY 字段上静默走通用分支`
- `activity-common/src/test/java/com/lrj/drools/activity/RuleConditionTranslatorTest.java —— 只断言 emit 出来的字符串；SpuIdConditionCompatTest 只测求值器一侧；没有任何测试把两侧放在一起比`

**今天怎么伤人**：加一个算子（notContainsAny / startsWith / 时间区间，都是运营迟早会要的）要在两个类里各写一遍；两个主 switch 是穷尽表达式会编译报错（这半边是安全的），但两个 ARRAY 兼容块的 `default -> {}` 不会——于是新算子在 ARRAY 字段（spuId / userTags，正好是最常配的两个）上，翻译器和求值器会各自落到不同的通用分支，表现就是注释自己写的那句「控制台预览通过、线上求值结论相反」。而运营唯一的自证手段就是预览。今天要判断两侧是否还对得上，只能人肉逐 case 对读 ~90 行代码，没有任何自动化。

**方案**：把「一个算子」做成**一个策略对象承载两半行为**（eval + emit），放进一个构造时校验完整性的 EnumMap 注册表。不用访问者是因为这里只有两个操作且长期不会变；不用把方法塞进 RuleOperator 枚举本体是因为 eval 需要 ctx/schema、emit 需要转义器，塞进枚举会把 domain 包反向依赖到 engine 包。ARRAY 兼容层从「两处手写镜像」变成算子自己声明的一个 `onArray()` 映射，两个调用方读同一份声明。

```java
public interface OperatorSpec {
    RuleOperator op();
    boolean eval(SchemaField f, Object raw, ActivityRuleContext ctx);
    String  emit(SchemaField f, Object raw, String acc);
    /** ARRAY 字段上的集合语义改写；null = 不改写，走本体。两个调用方读同一份声明 */
    default OperatorSpec onArray() { return null; }
}

@Component
public class OperatorRegistry {
    private final EnumMap<RuleOperator, OperatorSpec> specs = new EnumMap<>(RuleOperator.class);

    OperatorRegistry(List<OperatorSpec> all) {
        all.forEach(s -> specs.put(s.op(), s));
        var missing = EnumSet.allOf(RuleOperator.class);
        missing.removeAll(specs.keySet());
        // 加了算子却没实现 → 启动失败，而不是 ARRAY 字段上悄悄走错分支
        if (!missing.isEmpty()) throw new IllegalStateException("算子未实现: " + missing);
    }

    public OperatorSpec resolve(RuleOperator op, FieldValueType type) {
        OperatorSpec s = specs.get(op);
        if (type == FieldValueType.ARRAY && s.onArray() != null) return s.onArray();
        return s;
    }
}
```

**迁移**：分两步：① 先**只补测试**——写那份 javadoc 声称存在的对拍测试：对 12 算子 × 5 种 valueType 各构造若干 (属性值, 条件值) 样例，一侧调 ConditionTreeEvaluator.matches，另一侧把 translate 出来的约束塞进 `ActivityRuleContext(<约束>)` 用 ActivityRuleRuntimeService.compileOrGet 真编译执行，断言两侧同结论。仓库已具备 KieHelper 运行时编译能力，这一步零生产代码改动，先把安全网建起来。② 再做 OperatorSpec 抽取，此时 ① 的表格测试就是行为等价的判据。写平面的 IllegalArgumentException 抛出时机与文案必须原样（ActivityEligibilityGuardTest / RuleConditionTranslatorTest 守着）。

**风险**：两个 catch：① 翻译器对非法输入**抛异常**、求值器对同样输入**返回 false**，这是刻意的（创建期严格、运行期 fail-closed）。抽成同一个 OperatorSpec 时必须保留这个不对称，否则要么创建期放行脏配置、要么一条脏数据在运行期打断整次决策。建议 emit 与 eval 分别保留各自的错误约定，不要「统一异常模型」。② 第 ① 步的对拍测试会在 CI 里真编译 DRL，每个用例约十几毫秒，12×5 的表可能跑到几秒——要放在 common 模块并接受这个成本，不要为了快而改成只比字符串（那就退化成今天的 RuleConditionTranslatorTest，什么也没多守）。**不该做的情况**：如果已经决定把 generated_drl 这条编译校验链路整体退役（今天它还是生产数据），那第 ② 步的价值大幅下降，只做第 ① 步即可。

#### 5. [P2] ConditionNode 用一个类兼任「分组」和「叶子」两种形态，靠 `logic != null` 这个派生判别位区分——非法状态可表示、Jackson 出现写得出读不回的不对称（已用关闭 FAIL_ON_UNKNOWN_PROPERTIES 打补丁）、且未知 logic 值会在求值时抛异常打断整次决策。

**证据**：
- `activity-common/src/main/java/com/lrj/drools/activity/domain/ConditionNode.java:20-53 —— 一个 POJO 五个字段，isGroup() 由 `logic != null && !logic.isBlank()` 派生`
- `activity-common/src/main/java/com/lrj/drools/activity/service/DecisionDataLoader.java:401-410 —— 「isGroup() 是个派生的 boolean getter，Jackson 序列化时会额外写出一个 "group" 字段，而反序列化时它没有对应的 setter……写得出、读不回」，只能靠 `FAIL_ON_UNKNOWN_PROPERTIES=false` 兜`
- `activity-common/src/main/java/com/lrj/drools/activity/engine/ConditionTreeEvaluator.java:57-68 —— 只要 logic 非空就当分组处理，同一节点上的 field/op/value **被静默忽略**`
- `activity-common/src/main/java/com/lrj/drools/activity/domain/RuleLogic.java:28-34 —— fromCode 对未知 code 抛 IllegalArgumentException`
- `activity-common/src/main/java/com/lrj/drools/activity/engine/ConditionTreeEvaluator.java:60 —— 直接调 fromCode，调用链 applyJava → spuDiscountInternal 一路无 catch，对比 ActivityRuleContext.java:38-45 已经专门为「一条脏配置不该打断整次决策」加过护栏`

**今天怎么伤人**：① 一棵被写坏的树（同时带 logic 和 field，或者叶子挂了 children）不会报任何错，只会按另一种含义算——而这是决定发不发钱的资格判定。② 每加一种节点形态（比如将来要支持 NOT 组、或引用命名条件片段）都得再加一个派生判别位，判别逻辑要在求值器和翻译器各改一遍，且没有编译期穷尽检查。③ Jackson 那条不对称是已经付过的税：读写两侧用了不同配置的 mapper，这个坑写在注释里而不是类型里，下一个人接手同样会踩。④ RuleLogic.fromCode 那条是与 numberAttr 同一类的风险，他们已经为 numberAttr 加过护栏，这里还留着——一条脏 logic 值能把整个请求打成 500 而不是让那个候选 fail-closed。

**方案**：保留 ConditionNode 作为**线上 JSON / DB 契约的 DTO 不动**（它存在 condition_tree_json 里，改它等于改数据），另建一个**密封接口的解析后模型**给求值器和翻译器消费。密封接口 + 模式匹配是这里的正解，因为节点形态是一个封闭的小集合、且两个消费者都要按形态分派——用继承层次会让分派散回各子类，用访问者对两个操作来说太重。解析在今天已经存在的两个入口做（DecisionDataLoader.parseTree / 快照构建期），热路径零额外开销。

```java
/** 解析后的条件模型。ConditionNode 仍是 JSON/DB 契约，不动 */
public sealed interface Condition {
    record Group(RuleLogic logic, List<Condition> children) implements Condition {}
    record Leaf(String field, RuleOperator op, Object value) implements Condition {}
    /** 解析不出来的节点：读路径恒 false（fail-closed），写路径由 translate 抛错拒绝 */
    record Invalid(String why) implements Condition {}

    /** 读路径专用：**永不抛异常**，脏数据降级成 Invalid，与 numberAttr 的护栏取向一致 */
    static Condition parse(ConditionNode dto) { … }
}

// 求值器：穷尽 switch，加一种节点形态编译期就红
private boolean eval(Condition c, ActivityRuleContext ctx, Map<String, SchemaField> schema) {
    return switch (c) {
        case Condition.Group g -> g.children().isEmpty() ? true
                : g.logic() == RuleLogic.OR
                    ? g.children().stream().anyMatch(x -> eval(x, ctx, schema))
                    : g.children().stream().allMatch(x -> eval(x, ctx, schema));
        case Condition.Leaf l -> evalLeaf(l, ctx, schema);
        case Condition.Invalid ignored -> false;
    };
}
```

**迁移**：① 先加 Condition + parse()，让 ConditionTreeEvaluator 内部先 parse 再 eval，外部签名（matches(ConditionNode, ctx, schema)）保持不变——这一步对调用方零影响。② 再把 parse 上移到 DecisionDataLoader.parseTree 与 DecisionSnapshotBuilder，让快照直接存 Condition（顺便省掉热路径的重复解析）。③ 翻译器最后改。行为等价的判据要逐条对齐今天的边界：空组返回 true（ConditionTreeEvaluator:59）、字段不在白名单返回 false（:73）、树为 null 恒通过（:52）。安全网：ActivityEligibilityGuardTest、SpuIdConditionCompatTest、DecisionGoldenSetTest 的 Eligibility 6 例、以及本清单第 4 条建议新建的算子对拍表。

**风险**：最大的风险是**顺手改掉 fail 语义**：今天未知 logic 会抛异常（表现为整次决策 500），改成 Invalid → false 会让同一条脏数据从「请求失败」变成「这个活动静默不发」。方向上后者更对、也与仓库其它地方的 fail-closed 一致，**但它是一次行为变更**，必须单独立项、单独说明，不能夹在结构重构里悄悄带过（否则某个一直靠 500 告警发现脏配置的运维流程会瞎掉）。保守做法：第 ① 步 parse 遇到未知 logic 仍然抛，把降级留给独立 PR。**不该做的情况**：条件树形态近期不会扩展（不加 NOT 组、不加引用片段）时，这条的收益主要是防御性的，可以排在前四条之后。

#### 6. [P2] ActivityRuleResult 是一个承载四套互斥写协议的可变 god object，「命中」不是一个事实而是两个字段的组合，还挂着一个只写不读的 benefits「前瞻结构」；配套的出参 DiscountView 是 12 分量 record + 三处手写全量重列，文档自己承认漏传一个分量只会静默丢值。

**证据**：
- `activity-common/src/main/java/com/lrj/drools/activity/domain/ActivityRuleResult.java:10-18 —— 类注释列出 ELIGIBILITY / DISCOUNT / LADDER / GIFT 四套「各场景写入口径」共用同一个对象`
- `activity-common/src/main/java/com/lrj/drools/activity/domain/ActivityRuleResult.java:28 与 45 —— benefits 字段在 hit() 里写入；`grep getBenefits` 全仓库零调用方，是纯死结构`
- `activity-common/src/main/java/com/lrj/drools/activity/domain/ActivityRuleResult.java:40-46 hit() vs :67 setHitAmount() —— STACK 走后者、单选走前者，「命中」由两个字段拼出来`
- `activity-common/src/main/java/com/lrj/drools/activity/service/ActivityQueryService.java:222 —— 出口判据被迫写成 `disc.getHitActivityId() != null || disc.getHitAmount().signum() > 0``
- `activity-common/src/main/java/com/lrj/drools/activity/engine/BenefitEvaluator.java:78-81 —— 「决策出口的闸门是 `hitActivityId != null || hitAmount > 0`——OR 短路让负数照样出门」，负奖励能出门的根因就是这个双字段闸门，今天靠在落档前多加一道 signum 检查绕过`
- `activity-common/src/main/java/com/lrj/drools/activity/service/ActivityQueryService.java:436-440 与 447-462 —— 「三个 helper 是全分量重列式（miss/withTraces/withMode）：漏传一个分量不会编译失败，只会静默丢值」，12 个分量手抄三遍`

**今天怎么伤人**：① 读 merge() 要同时记住四套写协议里哪一套在用、以及 STACK 与单选写的是不同字段——这是「合并策略」这个领域概念没有被建模的直接代价。② 「命中」被表达成 OR 闸门后，任何一个能产生负金额或零金额的新路径都会从这道门溜出去；今天是在 BenefitEvaluator 里逐个补 signum 检查来堵，每加一种权益形态就要记得再堵一次。③ DiscountView 加一个分量要手工核对三处重列共 36 个位置参数，且相邻同类型 String（source/strategy/mode/decisionId）换位能编译通过——文档已经把这条风险写出来了，说明踩过。④ benefits/BenefitOutcome 是纯负债：它让读代码的人以为存在「多权益并存」的能力，实际零读者。

**方案**：把「合并的产出」建模成**值对象**：MergeResult(strategy, Hit?, clamped, traces)，Hit 是一个 record（activityId, activityName, amount），于是「命中」是「hit != null」这一个事实，出口的双字段 OR 闸门自然消失。ActivityRuleResult 退化成买赠 DRL 唯一还需要的那个输出面（收集 eligible 候选 + trace），不再被红包链路共用。DiscountView 的三处 wither 换成一个 Builder 或 `with` 派生方法（或把它拆成 DiscountView(核心, Explain, Provenance) 三层嵌套值对象），让加分量只需改一处。

```java
/** 合并产出。命中是一个事实，不是两个字段的组合 */
public record MergeResult(StackStrategy strategy, Hit hit, boolean clamped, List<String> traces) {
    public record Hit(String activityId, String activityName, BigDecimal amount) {
        public Hit {
            // 出口闸门收进构造器：负金额/零金额根本构造不出 Hit，不必在每个形态分支里各堵一次
            if (amount == null || amount.signum() <= 0)
                throw new IllegalArgumentException("命中金额必须为正: " + amount);
        }
    }
    public boolean missed() { return hit == null; }
    public static MergeResult miss(StackStrategy s, List<String> t) { return new MergeResult(s, null, false, t); }
}

/** 买赠 DRL 唯一还需要的输出面；红包链路不再共用它 */
public final class GiftRuleOutput {
    private final List<ActivityCandidate> eligible = new ArrayList<>();
    private final List<String> traces = new ArrayList<>();
    public void addEligible(ActivityCandidate c) { if (c != null) eligible.add(c); }
    public void trace(String s) { if (s != null) traces.add(s); }
}
```

**迁移**：三小步：① 先删 benefits/BenefitOutcome（零读者，纯减法，不改任何行为）。② 把 ActivityRuleResult 拆成 GiftRuleOutput（DRL global，名字保持 `result` 以免改 ActivityDrlBuilder 的 emit）+ MergeResult（纯 Java）；merge() 返回 MergeResult，ActivityQueryService:222 的 OR 闸门换成 `!r.missed()`。③ DiscountView 的三处重列换成 Builder。行为等价点：Hit 构造器的正数校验必须与今天 OR 闸门的**实际**放行集合一致——今天 `hitActivityId != null` 单独就能放行金额为 0 的命中（STACK 下所有候选都算 0 元时会出现），所以第 ② 步不能直接上骨架里那个严格构造器，得先用金标集确认这种组合是否真的存在于线上语义中；若存在则 Hit 允许 0、把正数校验留给独立 PR。安全网：DecisionOutputContractTest、DecisionGoldenSetTest 的 Merge 9 例、SnapshotParityTest。

**风险**：骨架里的 Hit 正数校验是**行为变更**：今天 `hitActivityId != null` 那一支会放行 0 元命中，把它变成构造失败会改掉响应（hit:true,amount:0 → hit:false）。这正是硬约束里说的「不得顺手修好看起来像 bug 的既有语义」，所以第 ② 步必须先原样保留放行集合，正数校验单开一个 PR 并配对拍。另外 GiftRuleOutput 改名会碰到 DRL 的 global 声明与 RHS 调用（ActivityDrlBuilder:82/91/141），DRL 是运行时编译，必须冒烟一次买赠请求验证，不能只看 `mvn compile`。**不该做的情况**：DiscountView 是对外响应契约，前端 e2e 与指标面板都读它——第 ③ 步只应改内部构造方式，绝不能顺手调整字段顺序或名字；如果近期没有要给 DiscountView 加分量的计划，第 ③ 步可以不做。

---

### 维度：读路径编排层与出参契约（ActivityQueryService / DecisionEligibilityService / AddOnPurchaseService / 两个 Controller）

#### 1. [P0] 「资格→阶梯→算额→合并」这条管线在 spuDiscountInternal 与 safeFallback 里各写了一遍，第二遍靠手工逐字段清理候选身上的可变计算态——「重算一次」这个操作在类型层面根本不存在，只存在于一个人肉列举的 for 循环里。

**证据**：
- `/Users/liruijun/personal/LLM/drools-demo/activity-common/src/main/java/com/lrj/drools/activity/service/ActivityQueryService.java:206-221（主管线：applyJava → applyLadder → computeAmounts → merge）`
- `/Users/liruijun/personal/LLM/drools-demo/activity-common/src/main/java/com/lrj/drools/activity/service/ActivityQueryService.java:294-320（safeFallback：同样四步；297-303 是手工 reset 循环，只清 computedAmount / amountComputed / ladderApplied 三个字段）`
- `/Users/liruijun/personal/LLM/drools-demo/activity-common/src/main/java/com/lrj/drools/activity/domain/ActivityCandidate.java:69-81（配置态与计算态 eligible / rejectReason / computedAmount / amountComputed / ladderApplied 同居一个可变 POJO）`
- `git 50eaca2^..a0ec639 —— 提交 a0ec639『阶梯未落档的候选必须淘汰』给候选加第 4 个计算态字段 ladderApplied 时，在本文件里只改了 2 行，就是那个 reset 循环；commit message 原文：『safeFallback 清算额状态时一并清掉该留痕，否则回退重算会用上一轮的陈旧标记』`
- `/Users/liruijun/personal/LLM/drools-demo/activity-common/src/main/java/com/lrj/drools/activity/engine/BenefitEvaluator.java:196-215（notApplicable 用 c.reject() 淘汰，与资格淘汰共用同一个 eligible 位与 rejectReason 字段）`

**今天怎么伤人**：① 加一个计算态字段的真实成本是改 3 处（ActivityCandidate 加字段 + BenefitEvaluator 置位 + safeFallback reset 循环），其中第 3 处**没有任何编译期或测试期强制**——漏了就是回退路径拿上一轮的中间态算钱：不抛异常、不计 fallback、日志干净，而金标集 52 例主要跑主路径，照不出回退路径的陈旧位。这个坑已经真实踩过一次（a0ec639）。② reset 循环第一行 `if (!c.isEligible()) continue;` 藏着一条没写在任何签名上的契约：算额阶段淘汰的候选（notApplicable → reject）在回退时不能复活，而资格阶段淘汰的也不能——两种语义完全相反的淘汰共用一个布尔位，读这 6 行代码要同时在脑子里装住「哪些 reject 可清、哪些不可清、为什么两者恰好都不清」。③ 两处管线的真实差异其实只有一个布尔（要不要先 reset），却以 30 行重复代码的形式存在，任何一次对主管线的顺序调整都必须记得同步另一处。

**方案**：不引新框架，做两件事：(a) 把「一次决策算出来的东西」收敛成候选上的一个 resetComputation() 方法——加计算态字段时只有这一处要改，并用一个反射断言测试把「字段清单 ⊆ reset 实现」变成红测试；(b) 把四步管线抽成一个 BenefitPipeline.run(...)，两个调用点都调它，`rerun` 这个布尔就是今天两段代码唯一的真实差异。选模板/管线对象而不是策略模式：三步的**顺序与数量是不变量**（阶梯必须在算额前、算额必须在合并前），需要被结构固化的是顺序，不是可替换的实现。

```java
// ActivityCandidate：加计算态字段时唯一要改的地方
public void resetComputation() {
    this.computedAmount = BigDecimal.ZERO;
    this.amountComputed = false;
    this.ladderApplied  = false;   // ← 新字段只加在这里
}

// 新增 BenefitPipeline（activity-common/service）：唯一的一份「阶梯→算额→合并」
final class BenefitPipeline {
    private final BenefitEvaluator benefits;
    ActivityRuleResult run(ActivityRuleContext ctx, List<ActivityCandidate> cands,
                           List<LadderActivityDef> ladders, StackStrategy strategy,
                           boolean explain, boolean rerun) {
        if (rerun) cands.stream().filter(ActivityCandidate::isEligible)   // ← 语义原样保留
                        .forEach(ActivityCandidate::resetComputation);
        benefits.applyLadder(ctx, cands, ladders);
        benefits.computeAmounts(ctx, cands);
        return benefits.merge(ctx, cands, strategy, explain);
    }
}
```

**迁移**：三小步，每步独立可发布：① 先只加 resetComputation()，把 safeFallback:299-302 三行替换成一次调用（纯搬移，DecisionGoldenSetTest 52 例 + ActivityQuerySafetyFallbackTest + NotApplicableCandidateTest 全绿即等价）；② 抽 BenefitPipeline，两个调用点替换，SnapshotParityTest 保证走库/走快照两条取数路仍等价；③ 补反射守卫测试（枚举 ActivityCandidate 里的计算态字段名清单，断言 reset 后全为初值），把今天靠 code review 守的不变量变成红测试。

**风险**：唯一风险是 reset 的**触发条件**被顺手改动：今天只 reset eligible 的候选，若改成全量 reset，算额阶段被淘汰的候选会复活并重新参与合并——那是改钱，必须原样保留 filter(isEligible)。若想真正区分「资格淘汰」与「算额淘汰」，得先给 reject 加阶段维度，属于另一个批次，**不要在这一步顺手做**。另外别把 BenefitEvaluator 一起改成「输入候选、输出 outcome 列表」的纯函数式——那会动到金标集断言的中间态，成本和风险都上一个量级。

#### 2. [P0] discount / gifts / addon 三个通道各自手抄了一遍「load → buildContext → applyJava → 各自后处理」的骨架，横切能力（计时 / 候选数 / 命中 / 金额 / 回退计数 / 审计 / decisionId）靠复制粘贴对齐——今天已经漏了一大半，且漏的正好是能扣真库存的那个通道。

**证据**：
- `/Users/liruijun/personal/LLM/drools-demo/activity-common/src/main/java/com/lrj/drools/activity/service/ActivityQueryService.java:176-178 + 191 + 198（discount 骨架）`
- `/Users/liruijun/personal/LLM/drools-demo/activity-common/src/main/java/com/lrj/drools/activity/service/ActivityQueryService.java:334-336 + 342 + 345（gifts 骨架，同形）`
- `/Users/liruijun/personal/LLM/drools-demo/activity-common/src/main/java/com/lrj/drools/activity/service/AddOnPurchaseService.java:95-104（addon 骨架，同形）`
- `/Users/liruijun/personal/LLM/drools-demo/activity-common/src/main/java/com/lrj/drools/activity/service/AddOnPurchaseService.java:39-42（构造器**没有 DecisionMetrics**——addon 通道物理上不可能打点）`
- `/Users/liruijun/personal/LLM/drools-demo/activity-common/src/main/java/com/lrj/drools/activity/service/AddOnPurchaseService.java:34 与 104（SCENE_ADDON 全类只用在 eligibility.applyJava 一处）`
- `/Users/liruijun/personal/LLM/drools-demo/activity-common/src/main/java/com/lrj/drools/activity/service/ActivityQueryService.java:110-119（注释明确立规矩：hit/amount 要打在『唯一出口』上，否则回退路径会系统性少计）`
- `/Users/liruijun/personal/LLM/drools-demo/activity-common/src/main/java/com/lrj/drools/activity/service/ActivityQueryService.java:353-355 与 368-369（gifts 的 metrics.hit 恰恰打在**内部两个分支**里，违反上面那条自立的规矩）`
- `/Users/liruijun/personal/LLM/drools-demo/activity-common/src/main/java/com/lrj/drools/activity/service/ActivityQueryService.java:120（auditLog 唯一调用点，写死 SCENE_DISCOUNT）`

**今天怎么伤人**：按 8 项横切能力（timeDecision / candidates / hit / amount / fallback / audit / decisionId / reject）逐项点名，三通道的覆盖度是 **8 / 6 / 1.5**：discount 全有；gifts 缺 amount 与 audit（于是『这个买赠活动这个月送出去多少钱的赠品』查不到，/decision/v1/by-activity 的 amounts 只反映红包）；addon 只有 reject 一项（经 eligibility 内部间接打点），**没有计时、没有候选数、没有命中、没有金额、没有 decisionId、没有审计**。而加价购是两阶段报价→claim 扣真库存的通道：它变慢、快照回退、候选爆炸这三类事故在监控上完全不存在，只能靠用户投诉发现——CLAUDE.md 把『回退率是头号告警项』写在第一位，三通道里却只有一个通道全量埋了它。新增第四个通道（券包 N 选一之类）要手工复制这 8 个动作，漏哪个都不会编译失败、也不会有测试红。

**方案**：用模板方法（抽象基类 DecisionChannel）而不是策略模式：三通道之间真正不同的只有两点——物料类型（ActivityType + 要不要带赠品）与「候选怎么装配成出参」；而骨架的**步骤顺序和横切动作的位置**恰恰是必须被强制统一的部分。策略模式会把整段流程交给实现方，正是今天漂移的成因。基类把 timeDecision/candidates/hit/amount/audit 收进 final 的 decide()，子类只实现 assemble()/empty()，『打在唯一出口』这条规矩由结构保证而不是靠注释。

```java
abstract class DecisionChannel<OUT> {                 // activity-common/service
    protected final DecisionDataLoader loader;
    protected final DecisionEligibilityService eligibility;
    protected final DecisionMetrics metrics;
    protected final DecisionAuditor auditor;
    protected abstract String scene();                 // 有限集合标签
    protected abstract ActivityType type();
    protected abstract boolean withGifts();
    protected abstract OUT assemble(DecisionCtx c);    // 通道唯一的差异
    protected abstract OUT empty(DecisionCtx c);
    protected abstract DecisionSummary summarize(OUT out);  // 供出口统一打点/审计

    public final OUT decide(SpuDiscountRequest req, DecisionMode mode) {
        return metrics.timeDecision(scene(), () -> {
            var m = loader.load(req.spuIdList(), type(), withGifts());
            metrics.candidates(scene(), m.candidates().size());
            var c = new DecisionCtx(newDecisionId(), req, m, mode, new ArrayList<>());
            OUT out = m.candidates().isEmpty() ? empty(c) : run(c);   // run 内含 buildContext+applyJava+assemble
            var s = summarize(out);
            if (s.hit()) { metrics.hit(scene(), s.activityId()); metrics.amount(scene(), s.activityId(), s.amount()); }
            auditor.write(c, s);                        // 三通道统一留痕
            return out;
        }, o -> summarize(o).mode());
    }
}
```

**迁移**：一次一个通道，三个 PR：① discount 先搬（横切最全，搬完不应产生任何指标/日志差异，DecisionMetricsTest / DecisionObservabilityTest / DecisionGoldenSetTest 是安全网）；② gifts 次之——搬完会**新增** amount 与 audit 两个出口，这是新增能力不是行为变更，需在 PR 里明说并接受面板出现新序列，同时把 353-355/368-369 两处 hit 上移到出口（计数集合不变，只是位置变）；③ addon 最后，先给 AddOnOptions/AddOnQuote 加 decisionId（纯增量分量）再接基类。

**风险**：AddOnPurchaseService.quote() 不要强行套进 decide()——它的形状是『跑一遍 options 再筛』，不是一次独立决策；套进去会让 timeDecision 记两次、candidates 记两次。让 quote 调 options 那个 channel 即可。另一个风险是给 addon 新增指标会让 activity.decision.* 出现 scene="addon" 的新序列，若已有告警规则按 scene 白名单写死，需同步。若团队近期确定不加第四个通道，可以只做『把横切收进基类』的一半、不动 assemble——收益（addon 可观测）已经拿到八成。

#### 3. [P1] 出参 record 的分量装配没有单一权威：DiscountView 是 12 分量 record，有 6 个手工构造点 + 3 个全分量重列 helper；GiftView / AddOnOptions / AddOnQuote / Materials 另有 4 个「悄悄填 DecisionProvenance.db()」的兼容构造。两类结构的共同后果是——漏传一个分量编译照过，只会静默丢值或静默谎报来源。

**证据**：
- `/Users/liruijun/personal/LLM/drools-demo/activity-common/src/main/java/com/lrj/drools/activity/service/ActivityQueryService.java:436-439（作者自陈：『三个 helper 是全分量重列式，漏传一个分量不会编译失败，只会静默丢值』）`
- `/Users/liruijun/personal/LLM/drools-demo/activity-common/src/main/java/com/lrj/drools/activity/service/ActivityQueryService.java:446-462（miss / withTraces / withMode 三处重列）`
- `6 个构造点：同文件 183、224-227、237、312、316-319、450-451`
- `/Users/liruijun/personal/LLM/drools-demo/activity-common/src/main/java/com/lrj/drools/activity/service/ActivityQueryService.java:237（一行里连着 withTraces(...).withMode(...)，为一次决策重建 3 个 view）`
- `/Users/liruijun/personal/LLM/drools-demo/activity-common/src/main/java/com/lrj/drools/activity/service/ActivityQueryService.java:183（空候选出口把 strategy 硬编码成字面量 "MAX"，其余出口走 loader.resolveStrategy）对照 /Users/liruijun/personal/LLM/drools-demo/activity-common/src/main/java/com/lrj/drools/activity/service/DecisionDataLoader.java:248-258`
- `兼容构造：ActivityQueryService.java:479-482（GiftView 四参）、AddOnPurchaseService.java:51-54（AddOnOptions 两参）、AddOnPurchaseService.java:67-71 与 73-77（AddOnQuote 五参 / 六参）、DecisionDataLoader.java:110-120（Materials 三参）`
- `这 4 个兼容构造在**生产代码里 0 调用方**：AddOnQuote 三个构造点（AddOnPurchaseService.java:138/147/154）全走七参，GiftView 三个构造点（ActivityQueryService.java:338/356/371）全走五参，AddOnOptions 两个（100/118）全走三参`

**今天怎么伤人**：① 上一轮加 provenance 这一个分量，就要改 3 个 helper + 6 个构造点共 9 处，且 9 处全部没有编译期保护；下一个分量（fallbackReason / scopeBase）同样代价。② withTraces/withMode 存在的**唯一理由**是 safeFallback 已经把 view 拼好了、调用方还要往上补两个分量——它是发现 1 那条重复管线的下游症状，两处一起改才划算。③ 兼容构造的行为是「默默声称走了库」。坑 18 与整个『优惠验证』页存在的理由，就是 provenance 必须可信；而现在任何新调用点都能在不写 provenance 的情况下编译通过，把一条快照路径标成 db——那会让验证页的对拍彻底失效（两侧 source 都是 db 就照不出快照陈旧），而对拍飘绿比飘红更彻底地骗人。④ strategy 这一个概念在出口上有两份来源，今天恰好都等于 MAX 所以看不出来；哪天 resolveStrategy 的兜底默认值一改，「无活动」响应就与其它出口不一致。

**方案**：装配集中到唯一一个 DiscountViewAssembler（或 DiscountView.of(DecisionOutcome...) 静态工厂）：管线内部只传一个小的 DecisionOutcome 值对象（record，漏传编译不过），环境量（decisionId / provenance / mode）在装配点统一注入，withXxx 全部消失。兼容构造直接删——测试改为显式传 DecisionProvenance.db()，正好让测试也声明自己在测哪条路；Materials 的三参可保留但改成 `Materials.fromDb(...)` 静态工厂，把「我确实走了库」从省略变成显式声明。选值对象 + 单一装配点而不是 Builder：Builder 仍允许漏设分量，而 record 参数表是编译器强制的。

```java
/** 管线内部的中间结果：只有决策本身的分量，没有 mode/decisionId/provenance 这些环境量 */
record DecisionOutcome(ActivityRuleResult result, List<DiscountItem> items,
                       StackStrategy strategy, List<String> traces, EngineMode mode) {}

final class DiscountViewAssembler {                       // 出参的唯一装配点
    static DiscountView assemble(DecisionOutcome o, String decisionId,
                                 DecisionProvenance p, List<ActivityCandidate> cands) {
        var r = o.result();
        boolean hit = r != null && r.getHitActivityId() != null;
        return new DiscountView(hit,
                hit ? r.getHitActivityId()   : null,
                hit ? r.getHitActivityName() : null,
                hit ? r.getHitAmount()       : BigDecimal.ZERO,
                o.strategy().name(), o.traces(), o.mode().code(),
                hit ? versionOf(cands, r.getHitActivityId()) : null,
                r != null && r.isClamped(), decisionId, o.items(), p);
    }
}
// 加第 13 个分量：只有这一处 + DecisionOutcome 要改，别处漏传 = 编译失败
```

**迁移**：① 先把 6 个构造点收敛到 assembler（纯重构，DecisionOutputContractTest 逐字段钉死 + DecisionGoldenSetTest 52 例）；183 那处硬编码 strategy 要显式换成 resolveStrategy(List.of()) 的返回值——今天等值，换成派生才算消除第二来源；② 删 miss/withTraces/withMode；③ 删 4 个兼容构造，编译器会指出全部失配点（只有测试）。

**风险**：JSON 契约必须保持**扁平**——前端 DiscountDecisionResponse 直接读 12 个平铺字段（/Users/liruijun/personal/LLM/drools-demo/frontend/src/shared/types.ts:179-197），所以 DecisionOutcome 只能留在服务内部，别顺手把 DiscountView 也拆成嵌套 record，那是破坏性契约变更。删兼容构造在本仓库零风险（grep 证实无生产调用方），但若这些 record 将来要当对外 SDK 类型发布，则应改为 @Deprecated 保留一个版本。

#### 4. [P1] 「热路径」与「控制台试算」这个档位概念用一个裸 boolean explain 表达，穿过 controller→service→eligibility 四层；更糟的是两个服务的**无参重载默认值相反**（ActivityQueryService 默认 false，AddOnPurchaseService 默认 true），于是「不传参数」在两个服务里意味着相反的安全等级。

**证据**：
- `/Users/liruijun/personal/LLM/drools-demo/activity-common/src/main/java/com/lrj/drools/activity/service/ActivityQueryService.java:102-104（spuDiscount 无参重载 → explain=false）`
- `/Users/liruijun/personal/LLM/drools-demo/activity-common/src/main/java/com/lrj/drools/activity/service/ActivityQueryService.java:324-326（buyAndGetGifts 无参重载 → false）`
- `/Users/liruijun/personal/LLM/drools-demo/activity-common/src/main/java/com/lrj/drools/activity/service/AddOnPurchaseService.java:89-91 与 130-132（options / quote 无参重载 → **true**）`
- `/Users/liruijun/personal/LLM/drools-demo/activity-common/src/main/java/com/lrj/drools/activity/service/AddOnPurchaseService.java:87（注释自陈上一次事故：『此前这里写死 true，资格淘汰明细恒随热路径响应外泄』）`
- `调用点被迫各记各的：console 显式传 true（/Users/liruijun/personal/LLM/drools-demo/activity-console/src/main/java/com/lrj/drools/activity/controller/ActivityMarketingController.java:162、167）却在 176、187 用默认值；decision 显式传 false（/Users/liruijun/personal/LLM/drools-demo/activity-decision/src/main/java/com/lrj/drools/activity/controller/DecisionPlaneController.java:122、136）却在 110、143 用默认值`

**今天怎么伤人**：6 个 REST 入口里有 4 个靠「默认值恰好对」才安全，而这两个默认值方向相反——同一个人在同一天写这两行，一个必须显式传参、另一个必须不传，没有任何签名信息提示他方向。写反的代价是单向的：热路径误开 explain 会把逐候选 rejectReason 与命中推导（活动内部结构）随响应体发给下游调用方，且每候选多一次字符串拼接——这条路已经真实发生过一次（AddOnPurchaseService:87 的注释就是事故的墓碑）。而且 boolean 在调用点是无名的：`addOn.quote(req, id, item, false)` 读到最后一个 false 时，要回源码才知道它是 explain 而不是别的开关。

**方案**：用枚举 DecisionMode 取代 boolean，并且**删掉无参重载**——今天的默认值是「谁先写谁定」，删掉之后编译器强制每个入口表态。选枚举而不是布尔，除了消歧义还有扩展位：档位将来大概率要分三档（none / structural-only / full-explain，热路径其实连结构性 trace 都不需要），布尔到那天必须改签名，枚举只加常量。

```java
/** 决策档位 = 「这次决策给谁看」。热路径给下游系统，试算给运营看链路。 */
public enum DecisionMode {
    HOT_PATH(false),      // /decision/v1/*   ：逐候选资格明细不外泄
    SIMULATION(true);     // /activity-marketing/* ：控制台试算
    private final boolean explain;
    DecisionMode(boolean e) { this.explain = e; }
    public boolean explain() { return explain; }
}

// 服务方法只接受枚举，且**没有无参重载**：调用点必须声明自己是哪一档
public DiscountView  spuDiscount(SpuDiscountRequest req, DecisionMode mode) { ... }
public GiftView      buyAndGetGifts(SpuDiscountRequest req, DecisionMode mode) { ... }
public AddOnOptions  options(SpuDiscountRequest req, DecisionMode mode) { ... }
public AddOnQuote    quote(SpuDiscountRequest req, String actId, String item, DecisionMode mode) { ... }
```

**迁移**：一次性且完全由编译器引导：加枚举 → 改 4 个签名 → 删 4 个无参重载 → 编译器列出全部调用点（生产 6 处 + 测试若干），逐点填回它**今天实际的**档位（console = SIMULATION，decision = HOT_PATH）。行为等价的判据就是这一步不改任何档位取值。安全网：ActivityMarketingAddOnAliasTest（console 别名档）、DecisionObservabilityTest，以及 e2e:validate ——它的对拍逻辑显式把 traces 列为『两侧 explain 档位不同』的正常差异，等于已经把这个档位当契约在测。

**风险**：测试里大量 `spuDiscount(req)` 要改，但全部是编译期可见的失败，不存在静默漂移。真正要小心的是别顺手改掉某个入口的档位取值——console 的试算若被改成 HOT_PATH，运营页面上的逐候选淘汰原因会整片消失，而那是验证页的核心信息。若团队已决定让 console 也不再需要 trace（下游不消费），那更彻底的做法是直接删掉这个参数，不必先包一层枚举。

#### 5. [P1] 决策留痕是在编排方法里手工拼出来的单行 JSON，引号责任分裂在日志模板与实参之间；而且它只挂在红包一个通道上——买赠生成了 decisionId 却从不落日志，加价购连 decisionId 字段都没有。

**证据**：
- `/Users/liruijun/personal/LLM/drools-demo/activity-common/src/main/java/com/lrj/drools/activity/service/ActivityQueryService.java:140-168（手工 StringBuilder 拼 items + 15 个占位符的模板）`
- `同文件 159-167：`\"scene\":\"{}\"` 的引号在模板里，而 `\"hitActivityId\":{}` 的引号在实参里（165 行 `"\"" + v.hitActivityId() + "\""`）——同一份模板里两种约定并存`
- `同文件 151-152：rejectReason 的转义只把 `"` 换成 `'`，反斜杠与换行不处理`
- `同文件 120：auditLog 唯一调用点，写死 SCENE_DISCOUNT`
- `同文件 333：买赠生成了 decisionId，全方法无任何 audit 调用`
- `/Users/liruijun/personal/LLM/drools-demo/activity-common/src/main/java/com/lrj/drools/activity/service/AddOnPurchaseService.java:49-55 与 64-78（AddOnOptions / AddOnQuote 两个出参 record 都没有 decisionId 分量）`

**今天怎么伤人**：①『这一单为什么减这么多』的唯一可回溯记录只覆盖三通道之一。加价购能通过 claim 扣真库存，它报了什么价在系统里查不到任何痕迹；买赠给了用户一个 decisionId，客服拿去查日志一无所获——这条落差今天是靠 CLAUDE.md 写一段话兜着，而不是靠结构兜着。② 引号责任分裂的代价是延迟爆炸的：下一个人加 String 分量时最自然的写法是 `\"foo\":{}` + 传原始字符串，产出的是非法 JSON；后果不是报错，是**日志采集侧静默丢弃这一条**——而它恰恰是出事时唯一的证据。转义只处理 `"` 同理，reject 原因将来一旦带上用户输入或异常消息就会破坏整行。③ 审计这个横切关注点被硬编码进了编排方法，导致它天然只能覆盖写它的那个通道。

**方案**：把审计从编排里拆成一个 DecisionAuditor 组件（装饰器位置：由发现 2 的 DecisionChannel 基类在出口统一调用），留痕对象用 record 描述、序列化交给 classpath 上已有的 Jackson（DecisionDataLoader 已经在用 ObjectMapper），让引号与转义**只有一处实现**。不引新日志框架、不落库（decision 连只读账号这条边界不动）。

```java
@Component
public class DecisionAuditor {                      // 三通道共用
    private static final Logger audit = LoggerFactory.getLogger("activity.decision.audit");
    private final ObjectWriter w;                   // mapper.writerFor(AuditRecord.class)
    public void write(AuditRecord r) {
        if (!audit.isInfoEnabled()) return;
        try { audit.info(w.writeValueAsString(r)); }        // 引号/转义只有一份实现
        catch (Exception e) { audit.warn("audit-serialize-failed decisionId={}", r.decisionId()); }
    }
}
public record AuditRecord(String decisionId, String scene, Long userId, List<Long> spuIds,
                          BigDecimal orderAmount, boolean hit, String hitActivityId,
                          Integer hitVersion, BigDecimal amount, String strategy,
                          boolean clamped, String mode, String source, Long generation,
                          List<ItemAudit> items) {}
public record ItemAudit(String activityId, Integer version, String form,
                        BigDecimal amount, boolean applied, String reject) {}
```

**迁移**：① 先原样迁 discount：新增一个测试断言输出可被 JSON 解析且键集合与今天一致（键顺序允许变，decisionId 这个检索键必须在）；② 再把 gifts 接上（新增能力，需在 PR 里说明日志量会变化）；③ addon 先加 decisionId 分量（纯增量，前端 types 里加可选字段）再接上。

**风险**：换成 Jackson 后热路径多一次序列化——它在 `audit.isInfoEnabled()` 之后，且只比今天的 StringBuilder 贵常数倍；若压测显示 P99 有感，退回手写 writer，关键不是用哪个库而是**只有一处**负责引号。真正不该做的情形：如果日志采集侧已经按今天的字段顺序写了 grok/正则而不是 JSON 解析，改字段顺序会打碎采集链路——动手前必须先确认采集侧是 JSON parser。

#### 6. [P2] engineMode(boolean) 这个字符串档位在三个出口上表达三件互不相干的事，而它同时是 HTTP 契约字段、Prometheus timer 的分桶标签、审计日志字段；同一个类里还留着两个已经退役、@SuppressWarnings("unused") 标着的 @Value 开关。

**证据**：
- `/Users/liruijun/personal/LLM/drools-demo/activity-common/src/main/java/com/lrj/drools/activity/service/ActivityQueryService.java:411（`engine ? "rule-engine" : "legacy"`）`
- `同文件 183：无候选 → "legacy"；196-202：总开关关闭 → "legacy"；237：空决策**回退**却显式改回 "rule-engine"`
- `同文件 107-108：`metrics.timeDecision(SCENE_DISCOUNT, ..., DiscountView::mode)` —— mode 是耗时 timer 唯一的业务分桶维度`
- `同文件 166：mode 也是审计日志字段`
- `同文件 407-410：注释要用三句话解释 mode **不**表示什么（不声明算额执行器、只是总开关的兼容档位）`
- `同文件 62-76：两个 @Value + @SuppressWarnings("unused") 的死开关 java-benefit-eval / java-eligibility-eval，代码里从不读取`

**今天怎么伤人**：① 「这次决策有没有回退」不能靠 mode 回答：mode=legacy 里混着『这单根本没有任何活动』（生产上绝大多数请求都落在这里）与『引擎开关关闭』两件事，而真正的回退（empty-decision）反被 237 行改回了 rule-engine。于是 activity.decision.duration 的 mode 维度实际等价于『有没有候选』，作为耗时分桶维度没有信息量，回退只能另看 fallback counter。② 一个字符串需要三句免责声明才能读懂，说明它已经不是一个概念：legacy 不表示回退、rule-engine 不表示走了 Drools（红包链路早就不进 DRL）、mode 不表示算额执行器。③ 两个死开关：运维在 yml 里配 java-benefit-eval=false 得不到任何反馈（不报错、不 warn、行为不变），而它历史上是有意义的——今天靠 CLAUDE.md 写两段话解释『配了也没用』，文档在替结构承担说明责任。

**方案**：(a) 把布尔换成 EngineMode 枚举，mode 字符串由 code() 产出（契约逐字节不变）；(b) 把「有没有回退、为什么回退」拆成正交的 FallbackReason 分量——纯增量地加进出参与 timer 标签，旧字段一字不改，让『回退率』这个头号告警项能从决策出参本身读出来；(c) 死开关删字段，改成启动期对退役 key 的显式 WARN，让『配了没用』变成一条可见的启动日志而不是一段文档。

```java
public enum EngineMode {
    RULE_ENGINE("rule-engine"), LEGACY("legacy");
    private final String code; EngineMode(String c){ this.code = c; }
    public String code(){ return code; }        // 出参字符串逐字节不变
}
/** 与 mode 正交：为什么走了回退。NONE = 没回退。 */
public enum FallbackReason { NONE, NO_CANDIDATE, ENGINE_DISABLED, EMPTY_DECISION }

@Component
class RetiredPropertyGuard implements InitializingBean {
    private static final List<String> RETIRED = List.of(
        "activity.marketing.rule-engine.java-benefit-eval",
        "activity.marketing.rule-engine.java-eligibility-eval");
    private final Environment env;
    public void afterPropertiesSet() {
        RETIRED.stream().filter(env::containsProperty).forEach(k -> log.warn(
            "配置项 {} 已退役、不会改变任何行为：红包资格与算额固定走 Java 求值层", k));
    }
}
```

**迁移**：① boolean → 枚举，mode() 输出保持不变（安全网：DecisionGoldenSetTest:401/408、ActivityQuerySafetyFallbackTest:57/61/122/138/177、DecisionMetricsTest:62/66 全绿即等价）；② 加 fallbackReason 分量与 timer 标签——**加**不**换**，mode 标签保留，Grafana 现有 query 不受影响；③ 删两个死字段 + 加启动守卫，同步把 CLAUDE.md 里那两段解释缩成一行。

**风险**：给 timer 换标签会打碎现有面板，所以只能加不能换；前端 `mode: string` 类型不受影响。若近期计划把 mode 从响应里彻底删掉（确认客户端已不读），那就直接删，不值得先包一层枚举再删。删死开关前要确认 deploy/docker-compose.yml 与各环境 yml 里没人还在配它们（配了也只是多一条 WARN，不会启动失败——守卫刻意不做 fail-fast，避免为了整洁把线上拦在门外）。

---

### 维度：横切关注点（错误处理 / 观测埋点 / explain 与 trace / 规则运行时职责 / 配置与测试接缝）

#### 1. [P0] 整个仓库没有领域异常层、没有错误码、也没有一个 @ControllerAdvice：业务失败一律裸抛 IllegalArgumentException/IllegalStateException + 中文 message，HTTP 状态靠 controller 逐个 try/catch 按异常「类」猜。后果有两条——决策热路径上的枚举 fromCode 会抛，而 decision 侧一个 handler 都没有，一行脏数据就把整次请求打成 500；写平面则把语义完全不同的错误挤进同两个类，状态码必然错配。

**证据**：
- `/Users/liruijun/personal/LLM/drools-demo 全仓 grep `ControllerAdvice|@ExceptionHandler|ResponseStatusException` = 0 命中（含 drools-lab）；唯一的映射是手写的 /Users/liruijun/personal/LLM/drools-demo/activity-console/src/main/java/com/lrj/drools/activity/controller/ActivityMarketingController.java:249-257 `bad()/conflict()/record ErrorResponse(String error)``
- `同一个 try/catch 在 ActivityMarketingController.java:72-79、84-90、152-156 抄了三遍；drools-lab 另有 10 处同形 catch（ScannerController / LoyaltyController / HotReloadController / CampaignController）`
- `热路径会抛：/Users/liruijun/personal/LLM/drools-demo/activity-common/src/main/java/com/lrj/drools/activity/engine/ConditionTreeEvaluator.java:60 `RuleLogic.fromCode` 与 :77 `RuleOperator.fromCode`，两者分别在 domain/RuleLogic.java:33、domain/RuleOperator.java:47 `throw new IllegalArgumentException("未知运算符: "+code)`；调用点 service/DecisionEligibilityService.java:121 `conditions.matches(...)` 无任何 try/catch`
- `同类：domain/StackStrategy.java:24 抛，被 service/DecisionDataLoader.java:256 `resolveStrategy` 与 snapshot/DecisionSnapshotBuilder.java:152 在热路径调用`
- `/Users/liruijun/personal/LLM/drools-demo/activity-decision/src/main/java/com/lrj/drools/activity/controller/DecisionPlaneController.java 全文零 try/catch；activity-decision/src/main/resources/application.yml 未设 server.error.include-message（默认 never），500 的响应体不含任何原因`
- `代码自己已经知道这个坑但只在一处绕开：engine/BenefitEvaluator.java:417 注释「不能直接用 DistributionMode.fromCode——它对未知 code 抛异常，那会让一条脏数据打断整批候选的算额」`
- `状态码错配：service/ActivityMarketingService.java:320、323 四眼校验失败抛 IllegalStateException（语义是 403 授权拒绝），经 controller:88 一律变成 **409 Conflict**`
- `message 字符串已经成了控制流的 key：service/ActivityMarketingService.java:608 `e.getMessage().toLowerCase().contains("uk_am_tenant_request")``
- `前端只有一条通道：/Users/liruijun/personal/LLM/drools-demo/frontend/src/shared/apiClient.ts:73-75 `errText` = `(j.error || j.message) || 'HTTP '+status`，原样渲染后端中文串`

**今天怎么伤人**：① 一条 `activity_condition.condition_tree_json` 里把 `ge` 写成 `gte`（或旧版本 schema 遗留），`/decision/v1/spu-discount` 直接 500 —— 不是这个活动不发，是**整车所有活动一起不发**，而响应体因 include-message=never 什么都不说，日志里只有栈。这与本类自己第 73-75 行「字段不在白名单 → fail-closed return false」的取向自相矛盾：同一个方法里，缺字段是温和降级，脏算子是炸整条链路。② 四眼拒绝返回 409，绝大多数客户端/网关把 409 当「冲突可重试」，于是「提交人不能自审自发」这条合规闸门会被无限重试打成噪声；真正该返回的是 403。③ 新增一种业务错误时，开发要选的不是「这是什么错」而是「我想要 400 还是 409」——异常类被当成状态码枚举用，语义与传输层永久绑死。④ 前端拿到的只有一个中文串：无法按错误码做差异化 UX（版本冲突→自动刷新重试 vs 折数越界→高亮那个输入框），也无法国际化；改一句后端文案就是一次未经声明的前端变更。⑤ 新增受保护端点时错误映射会漏——`/preview`、`/bulk-status`、`/claim`、`/release`、`/spu-discount` 五个端点今天就没有任何 catch。

**方案**：密封的领域异常层 + 错误码枚举 + 一个共享 @RestControllerAdvice。选它而不是「继续每个 controller try/catch」：错误码与 HTTP 状态的映射是**横切**的，它每多一个 controller 就复制一遍，而复制体之间只能靠人眼保持一致（现在已经不一致了）。选枚举而不是自定义 String code：错误码集合必须是编译期封闭的，才能让前端 switch 穷尽、让告警按 code 分桶。同时把**读路径**的枚举查找去异常化（`tryFromCode` 返回 Optional），写平面的 `fromCode` 原样保留抛出——两条路径对脏数据的正确反应本来就相反：写平面要拒绝，读平面要 fail-closed 淘汰这一个候选。

```java
// activity-common/.../error/ActivityError.java —— 错误码即契约，status 只是它的一个投影
public enum ActivityError {
    ACTIVITY_NOT_FOUND(404), VERSION_CONFLICT(409), DUPLICATE_REQUEST(409),
    FOUR_EYES_REQUIRED(403), FOUR_EYES_SELF_APPROVE(403),   // 今天错成 409
    INVALID_BENEFIT_CONFIG(400), DIRTY_RULE_DATA(422);
    private final int status;
    ActivityError(int s) { this.status = s; }
    public int status() { return status; }
    public ActivityException with(String msg) { return new ActivityException(this, msg); }
}
public final class ActivityException extends RuntimeException {
    private final ActivityError error;
    ActivityException(ActivityError e, String m) { super(m); this.error = e; }
    public ActivityError error() { return error; }
}

@RestControllerAdvice   // 放 activity-common，console 与 decision 共用同一份映射
class ActivityErrorAdvice {
    @ExceptionHandler(ActivityException.class)
    ResponseEntity<?> on(ActivityException ex) {
        return ResponseEntity.status(ex.error().status())
            .body(Map.of("code", ex.error().name(), "error", ex.getMessage())); // error 字段保留 → 前端零改动
    }
    @ExceptionHandler(IllegalArgumentException.class)  // 迁移期兜底，逐字保持旧 400 语义
    ResponseEntity<?> legacy(IllegalArgumentException ex) { /* 400 + error */ }
    @ExceptionHandler(IllegalStateException.class)     // 迁移期兜底，保持旧 409
    ResponseEntity<?> legacyConflict(IllegalStateException ex) { /* 409 + error */ }
}

// 读路径去异常化：脏枚举 → 这个候选被淘汰，而不是整次请求 500
public static Optional<RuleOperator> tryFromCode(String code) { /* 不抛 */ }
// ConditionTreeEvaluator.evalLeaf: tryFromCode(leaf.getOp()).orElse(null) == null → return false（与字段缺失同路）
```

```java
见 proposal 内嵌代码块（ActivityError 枚举 + ActivityException + 共享 Advice + tryFromCode 读路径去异常化）
```

**迁移**：四步小闭环，每步独立可发布。①【纯增量、零行为变更】加 ActivityError/ActivityException/Advice，Advice 里先只放两个 legacy handler，把 IAE→400、ISE→409 的现有映射原样搬进来，然后删掉 controller 里的三处 try/catch 与 bad()/conflict()。此时响应体一模一样（`{"error": "..."}`），`ActivityMarketingFlowTest` 一类的状态码断言全绿即证等价。②【纯增量】响应体加 `code` 字段，值先一律填 `LEGACY_BAD_REQUEST` / `LEGACY_CONFLICT`；前端 apiClient 不动（它只读 `error`）。③【逐点收敛】把 ActivityMarketingService 里的 throw 逐个换成 `ActivityError.X.with(...)`，**message 字符串一字不改**，只有四眼那两处刻意从 409 改到 403 —— 这一条是明确的契约变更，单独一个 commit、单独在 docs/activity-marketing.md 记一笔。④【fail-closed 化】给 RuleOperator/RuleLogic/StackStrategy 加 tryFromCode，只改 ConditionTreeEvaluator 与 DecisionDataLoader.resolveStrategy 两个读路径调用点；写平面 ActivityMarketingService.java:571/718 继续用会抛的 fromCode。安全网：DecisionGoldenSetTest 52 例（脏数据不在其中，行为不变即绿）、SnapshotParityTest（走库/快照两条路都要淘汰同一个候选）、ActivityMarketingFlowTest 与 console 的状态码断言；另需**新增**一个用例——条件树里塞非法 op，断言 `/decision/v1/spu-discount` 返回 200 且该候选被淘汰、其余活动照常发钱（这正是今天做不到的事）。

**风险**：最大风险在第 ③、④ 步：四眼 409→403 是**客户端可见的契约变更**，若有外部脚本按 409 分支（e2e:validate 就在验四眼被拒），必须同步改。第 ④ 步把「脏数据 500」改成「脏数据静默淘汰」，方向上是从 fail-open-ish 变成 fail-closed，不会多发钱，但会让一类配置错误从「炸得很响」变成「安静地不发」——所以 tryFromCode 落空时**必须**同时打 `metrics.reject(scene, DIRTY_RULE_DATA)` 并 log.warn，否则等于用可观测性换可用性。**什么情况下不该做**：如果只打算做 ①②（收编现有映射）而不打算做 ④，那第 ①② 步收益有限（省了几处 try/catch），可以延后；反过来 ④ 可以脱离 ①②③ 单独做，它是这条里唯一在修真实故障的部分。另外别把 Advice 放进 drools-lab 能看到的位置——那 10 处教学 controller 的 catch 是 Step 教学素材，不在本次范围。

#### 2. [P1] 「候选为什么被淘汰」在系统里有两份互不相干的真相，靠人在每个调用点手工配对：一个是给人看的中文串 `candidate.reject("...")`，一个是给 Prometheus 的原因码 `metrics.reject(scene, "...")`。同一族指标的 `scene` 标签更是分裂成四套词汇表（通道名 / 阶段名 / ActivityType 枚举名），已经导致两组指标无法 join，且这个漂移是被写进文档「以代码为准」而不是被修掉的。

**证据**：
- `配对写在同一行的两个独立字面量：/Users/liruijun/personal/LLM/drools-demo/activity-common/src/main/java/com/lrj/drools/activity/engine/BenefitEvaluator.java:133、150、159、172、185 —— 例如 `notApplicable(c, "missing-lines", "第 N 件折缺订单行或 N 非法")``
- `写入点 BenefitEvaluator.java:213-220：`c.reject("本活动不适用：" + why)` 与 `metrics.reject("benefit", reasonCode)` 是两条独立语句，谁都不校验另一个`
- `另一处配对顺序相反、且没有「本活动不适用：」前缀：/Users/liruijun/personal/LLM/drools-demo/activity-common/src/main/java/com/lrj/drools/activity/service/DecisionEligibilityService.java:110-111（先码后串）与 :122、125（先串后码）`
- `第三份拷贝、且没有指标对应项：/Users/liruijun/personal/LLM/drools-demo/activity-common/src/main/java/com/lrj/drools/activity/engine/ActivityDrlBuilder.java:82 在 DRL 里 emit `$c.reject("不满足资格条件");``
- `漂移已发生且未修：/Users/liruijun/personal/LLM/drools-demo/activity-common/src/main/java/com/lrj/drools/activity/metrics/DecisionMetrics.java:229 javadoc 写 `price-above-order`，而 BenefitEvaluator.java:172 实际发的是 `price-above-base`；docs/activity-marketing.md:243 只好补一句「以代码为准」`
- `scene 四套词汇：service/ActivityQueryService.java:50-51 `spu-discount`/`gifts`；service/AddOnPurchaseService.java:34 `addon`；engine/BenefitEvaluator.java:219 硬编码 `"benefit"`（这是**阶段**不是通道）；service/DecisionDataLoader.java:136、153 用 `type.name()` = `RED_PACKAGE`/`BUY_AND_GET`/`ADD_ON_PURCHASE``
- `同样被记成已知落差而非修复：docs/activity-marketing.md:245「按 scene join 两组指标会得到空结果」、docs/deployment.md:155「拿 scene 跟别的序列 join…都是错的」`
- `中文串是前端唯一的抓手：/Users/liruijun/personal/LLM/drools-demo/frontend/src/console/pages/ValidateView.vue:818 原样渲染 `item.rejectReason`；测试 frontend/src/console/pages/ValidateView.test.ts:443 直接断言字符串 '不满足资格条件'`

**今天怎么伤人**：① 新增第七种权益形态，「淘汰原因」这一件事要改 7 处且漏任何一处都不报错：BenefitEvaluator 的新分支、一个新原因码字面量、一个新中文字面量、DecisionMetrics 的 javadoc 清单、docs/deployment.md 指标表、docs/activity-marketing.md 原因码表、frontend/src/shared/types.ts 的注释。漏码 → 指标里这类淘汰凭空消失；漏串 → 用户看到空的「未生效」；两者都不会让任何测试变红。② 今天就已经错了：值班按 `activity_decision_reject_total{scene="gifts"}` 统计「买赠一共淘汰了多少」会**漏掉全部算额淘汰**（它们全被记在 `benefit` 这一格，且分不出是哪条通道）；而 `activity_decision_source_total{scene="gifts"}` 直接返回**空**（那条序列的标签是 `BUY_AND_GET`）。CLAUDE.md 把 reject 称作「配了但不发」的唯一信号，可这个唯一信号今天按通道切不开。③ 读懂一次淘汰要同时在脑子里装住四个隐式契约：原因码必须是有限集、中文串是前端契约、`benefit` 是阶段不是通道、`type.name()` 与 scene 不是一个词汇表。

**方案**：值对象化：把「淘汰原因」收敛成一个 `RejectReason` 枚举（码 + 文案在同一行，天然不可能配错），把 scene 收敛成 `DecisionScene` 枚举并把「阶段」拆成独立的一维标签。用枚举而不是常量类：它同时给出编译期封闭集合（防标签基数爆炸，与 DecisionMetrics.java:74 的 ACTIVITY_TAG_CAP 同一套顾虑）和穷尽性检查。不做 i18n 资源包——那是后续可选项，本条只要求「码与文案同源」。

```java
public enum RejectReason {
    INELIGIBLE("ineligible", "不满足资格条件"),
    CONDITION_UNAVAILABLE("condition-unavailable", "资格条件不可判定"),
    NO_LADDER_TIER("no-ladder-tier", "本活动不适用：阶梯未落档且无固定金额"),
    MISSING_LINES("missing-lines", "本活动不适用：第 N 件折缺订单行或 N 非法"),
    PRICE_ABOVE_BASE("price-above-base", "本活动不适用：一口价高于作用域金额或缺订单金额"),
    BAD_RATIO("bad-ratio", "本活动不适用：缺订单金额或折数越界"),
    BAD_RANDOM_RANGE("bad-random-range", "本活动不适用：随机区间缺失或非法"),
    OUT_OF_SCOPE("out-of-scope", "本活动不适用：作用域基数不可知（活动只圈了部分商品，但请求未带订单行）");
    private final String code, message;   // message 与今天的输出逐字节相同
    RejectReason(String c, String m) { this.code = c; this.message = m; }
    public String code() { return code; } public String message() { return message; }
}
public enum DecisionScene {              // 通道，唯一词汇表；取数层不再用 ActivityType.name()
    SPU_DISCOUNT("spu-discount"), GIFTS("gifts"), ADDON("addon");
    public static DecisionScene of(ActivityType t) { /* 单一映射点 */ }
}
public enum RejectStage { ELIGIBILITY, BENEFIT }   // 「阶段」从 scene 里剥出来，各占一维

// ActivityCandidate：结构化位是新增的，getRejectReason() 仍返回同一个 String（前端零改动）
public void reject(RejectReason r) { this.eligible = false; this.rejectCode = r; this.rejectReason = r.message(); }
// DecisionMetrics：标签从两维变三维，scene 恒为通道
public void reject(DecisionScene scene, RejectStage stage, RejectReason reason) { ... }
```

```java
见 proposal 内嵌代码块（RejectReason / DecisionScene / RejectStage 三个枚举 + ActivityCandidate.reject 重载 + DecisionMetrics 三维标签）
```

**迁移**：①【零风险】先只加枚举，`reject(String)` 保留，新增 `reject(RejectReason)` 重载，逐个调用点替换 —— 由于 message 逐字节复制自现有字面量，`ActivityQuerySafetyFallbackTest:109`（断言 '资格条件不可判定'）与 frontend `ValidateView.test.ts:443` 直接就是等价性证明，它们绿就说明文案没动。替换完删掉 `reject(String)`，此后配对错位在编译期就不可能了。②【指标标签变更，需协调】`decisionSource` 的 scene 从 `type.name()` 换成 `DecisionScene.of(type).tag()`；`metrics.reject` 加 `stage` 维、scene 统一成通道。这会**改变 Prometheus 序列**，所以要么在一个发布周期内同时发新旧两条（旧的加 `deprecated` 后缀），要么与 Grafana 面板同批改。安全网：activity-console/src/test/.../DecisionObservabilityTest.java:82-100 已经在按 `reason=ineligible` / `price-above-base` 断言计数，是这一步的直接门禁；DecisionMetricsTest 覆盖标签形状。③ 同批把 DecisionMetrics.java:229 的 javadoc、docs/deployment.md:150-155、docs/activity-marketing.md:231-245 三处表格改成「见 RejectReason 枚举」，让文档不再是第四份真相。

**风险**：第 ② 步动的是**线上告警赖以生效的标签**，做之前必须确认 Grafana 里哪些面板/告警按 `scene` 过滤（deploy/ 下有 prometheus + grafana 编排）。改标签本身不影响一分钱，但会在切换窗口内让面板短暂空白 —— 而 CLAUDE.md 把回退率列为头号告警项，空白窗口期要人工盯。**什么情况下不该做**：如果近期就要上大促、监控面板不允许动，那只做第 ① 步（码与文案同源，纯内部收敛，Prometheus 序列一个字节不变），把第 ② 步排到大促之后。另外 `RejectReason.message()` 一旦被更多前端逻辑当成 key 用，未来再改文案的成本会更高 —— 所以第 ① 步落地时应同批把 `rejectCode` 加进 `DiscountItem` 响应契约（纯增量），引导前端改读码而不是读串。

#### 3. [P1] 观测埋点自上而下贯穿了所有分层，连纯计算层都被迫吃一个 Spring bean：BenefitEvaluator 唯一的构造依赖就是 DecisionMetrics，只为在两处打点。这不只是「不优雅」——正因为埋点做在求值器内部，而求值器不知道自己在为哪条通道服务，它的 scene 标签只能硬编码成 "benefit"，这正是上一条里 scene 词汇表分裂的**根因**。

**证据**：
- `/Users/liruijun/personal/LLM/drools-demo/activity-common/src/main/java/com/lrj/drools/activity/engine/BenefitEvaluator.java:45-49 —— 全类唯一的实例字段与唯一的构造参数就是 `DecisionMetrics metrics`；它只在 :219 `metrics.reject("benefit", reasonCode)` 与 :378 `metrics.clamped()` 两处被用到`
- `硬编码的 "benefit" 就写在 :219，注释（:217-218）自己承认「scene 用 "benefit" 标出**阶段**（算额）而不是业务场景」—— 因为在这一层根本拿不到通道`
- `为它专门造的测试后门在生产代码里：/Users/liruijun/personal/LLM/drools-demo/activity-common/src/main/java/com/lrj/drools/activity/metrics/DecisionMetrics.java:112-119 `public static DecisionMetrics noop()`（内含一个真的 SimpleMeterRegistry）；全仓 `noop()`/`new DecisionMetrics(` 共 12 处调用`
- `测试为了打一个桩必须继承求值器：/Users/liruijun/personal/LLM/drools-demo/activity-common/src/test/java/com/lrj/drools/activity/ActivityQuerySafetyFallbackTest.java:242-258 `EmptyOnceBenefitEvaluator extends BenefitEvaluator`，构造第一件事就是 `super(DecisionMetrics.noop())`；同文件 :171、:188、:218、:230 又各造一次 noop()`
- `埋点横跨 6 个类约 24 个调用点，从 controller 一直下到纯计算：DecisionPlaneController.java:60/90/95、ActivityQueryService.java:107/114/118/178/199/234/329/336/354/362/364/369、DecisionDataLoader.java:136/153、DecisionEligibilityService.java:109/110/125、BenefitEvaluator.java:219/378、GenerationWarmService.java:74`
- `加价购通道一个自有埋点都没有：AddOnPurchaseService.java 全文无 DecisionMetrics 引用（options/quote 的耗时、命中、金额全不可观测），docs/activity-marketing.md:245 已记为已知落差`

**今天怎么伤人**：① BenefitEvaluator 在语义上是 (ctx, candidates, strategy) → result 的纯函数，是这个仓库里最该能被独立、大量、快速测试的一块（六形态 × 边界 = 组合爆炸），却因为构造依赖而**无法脱离 Micrometer 存在**；金标集与单测目前要创建 12 个 SimpleMeterRegistry 才跑得起来。② 想给某个 case 打桩，最轻的手段居然是继承一个 @Service —— 于是 BenefitEvaluator 的 `merge` 永远不能设成 final，任何一次「顺手加个 final / 改成 record」都会打断测试。③ 最实的伤害是标签：因为埋点位置错了，求值层拿不到 scene，只能填阶段名，直接造成上一条里「按通道统计淘汰数会漏掉全部算额淘汰」的线上盲区。埋点位置错一层，指标语义就错一维。④ 反向的空缺同样由此而来：加价购走的是**同一个** DecisionEligibilityService 与取数层，却因为编排层（AddOnPurchaseService）没有埋点意识而完全没有 duration/hit/amount —— 埋点散在各层时，「哪条链路埋全了」没有任何地方能一眼看出来。

**方案**：用观察者/装饰器把观测从核心里挪出去：求值层只**声明发生了什么**（一个默认全空实现的窄接口），由**知道 scene 的那一层**（编排层）决定要不要、以什么标签记下来。选观察者接口而不是 AOP 切面：这些事件不是「方法进出」，是领域事件（某个候选因某原因被淘汰、某笔减免被封顶），切面表达不了参数语义；也不选事件总线（ApplicationEventPublisher），热路径不该为每次淘汰付一次事件分发。

```java
/** 求值层只记录「发生了什么」，既不知道 Micrometer，也不知道自己在为哪条通道服务。 */
public interface DecisionObserver {
    DecisionObserver NONE = new DecisionObserver() {};   // 默认全空 → 调用点无需空判
    default void rejected(ActivityCandidate c, RejectReason r) {}
    default void clamped(BigDecimal asked, BigDecimal cap) {}
}

@Service
public class BenefitEvaluator {          // 无构造依赖 = 纯函数，可直接 new，可加 final
    public void computeAmounts(ActivityRuleContext ctx, List<ActivityCandidate> cs) {
        computeAmounts(ctx, cs, DecisionObserver.NONE);
    }
    public void computeAmounts(ActivityRuleContext ctx, List<ActivityCandidate> cs, DecisionObserver obs) { ... }
    private void notApplicable(ActivityCandidate c, RejectReason r, DecisionObserver obs) {
        c.reject(r);          // 文案与今天逐字节一致（见上一条）
        obs.rejected(c, r);   // 谁在观测、打什么标签，由调用方决定
    }
}

/** 编排层是唯一知道 scene 的地方，因此也是唯一该打标签的地方。 */
record MetricObserver(DecisionMetrics m, DecisionScene scene) implements DecisionObserver {
    public void rejected(ActivityCandidate c, RejectReason r) { m.reject(scene, RejectStage.BENEFIT, r); }
    public void clamped(BigDecimal asked, BigDecimal cap)     { m.clamped(); }
}
// ActivityQueryService: benefits.computeAmounts(ctx, candidates, new MetricObserver(metrics, SPU_DISCOUNT));
// AddOnPurchaseService 同法接上，顺带把它今天完全没有的通道埋点一次补齐
```

```java
见 proposal 内嵌代码块（DecisionObserver 窄接口 + NONE 默认实现 + MetricObserver 记录类 + BenefitEvaluator 去构造依赖）
```

**迁移**：①【纯增量】加 DecisionObserver 接口与三参重载，旧的两参签名委托给 `NONE`；BenefitEvaluator 的构造参数**先保留**，内部 metrics 调用改成 `observerOf(...)`，行为完全不变。②【切换调用方】ActivityQueryService / AddOnPurchaseService 传入 MetricObserver，此时同一条淘汰会被记两次（旧的类内埋点 + 新的 observer），用 DecisionObservabilityTest 断言计数翻倍来证明两条路等价，然后删掉类内埋点。③【摘掉依赖】删除 BenefitEvaluator 的构造参数与字段；同批可以删掉 `DecisionMetrics.noop()`（12 处调用改成直接 `new BenefitEvaluator()`），以及 ActivityQuerySafetyFallbackTest 里那个继承桩 —— 它只想让第一次 merge 返回空结果，改用 Mockito `spy` 或直接传一个 `DecisionObserver` 记录调用即可。安全网：DecisionGoldenSetTest 52 例（金额一分不变，因为埋点不参与计算）、DecisionObservabilityTest（指标计数）、SnapshotParityTest。整个过程金额路径一行不动，这是它可以安全做的根本原因。

**风险**：风险很低但有一处要盯：第 ② 步的「双记」窗口如果不小心发到线上，`activity.decision.reject` 会短暂翻倍，而它是排查「配了但不发」的读数 —— 所以第 ②③ 步应该在同一个 commit 里完成，不要分两次发布。另一处：把埋点搬到编排层后，如果将来有人直接调 BenefitEvaluator（绕过编排），淘汰将不被记录 —— 这是接口默认空实现的固有代价，要在类注释里写明「生产调用必须传 observer」，或者干脆让三参版是唯一 public 签名、两参版设成 package-private（只给测试用）。**什么情况下不该做**：如果同时正在做上一条的 RejectReason 收敛，两者应合并成一批做（notApplicable 的签名只改一次），分两批会把同一处代码改两遍。

#### 4. [P1] `explain` 这个布尔参数穿透了 5 个类 13 个方法签名，trace 则是散落在四个类里的字符串拼接。两个后果：一是同名布尔在两个姊妹服务上默认值相反（一个 false 一个 true），二是 trace 因为是自由文本而无法被机器读取，逼得团队又造了一套 `items[]` 结构化字段来回答同一个问题。

**证据**：
- `13 个签名带 `boolean explain`：service/ActivityQueryService.java:106、170、328、332；service/DecisionEligibilityService.java:94；service/AddOnPurchaseService.java:93、134；engine/BenefitEvaluator.java:312、368；engine/ActivityDrlBuilder.java:59、70、106、135`
- `全部是位置布尔实参：ActivityMarketingController.java:161 `query.spuDiscount(req, true)`；DecisionPlaneController.java:122 `addOn.options(req, false)`；ActivityQueryService.java:306 `benefits.merge(ctx, candidates, strategy, false)``
- `**默认值相反**：ActivityQueryService.java:102-104 单参重载默认 `false`；AddOnPurchaseService.java:89-91 单参重载默认 `true` —— 而 AddOnPurchaseService.java:87 的 javadoc 自己写着「此前这里写死 true，资格淘汰明细恒随热路径响应外泄」`
- `trace 拼接散落 ≥15 处：ActivityQueryService.java:217、235、314-315；BenefitEvaluator.java:325、330、342-345、385；DecisionEligibilityService.java:113、116、126、128；AddOnPurchaseService.java:99、117、146、153`
- `结构化缺失的直接证据：ActivityQueryService.java:212 `List<String> applicableBefore = explain ? eligibleIds(candidates) : List.of();` 然后 :214-220 靠**比对前后两个 id 列表**反推出哪些候选是在算额阶段被淘汰的 —— 因为 trace 只是字符串，没别的办法`
- `因为 trace 不可解析，同一个问题被第二次回答了一遍：ActivityQueryService.java:441-470 新增 `items[]` / `DiscountItem(..., applied, rejectReason)` 契约`
- `档位差异已经污染到验证工具：CLAUDE.md 记载控制台对拍必须**排除** `traces`（console 别名是 explain=true、decision 是 false），也就是两个「同语义别名」端点在这一维上结构性不可比`

**今天怎么伤人**：① 布尔穿透最典型的代价已经兑现过一次：加价购的 explain 曾经写死 true，导致**热路径响应里恒带逐候选资格淘汰明细**（谁不满足哪条门槛，等于把运营配置泄给下游）。修的方式是加一个重载，而单参重载至今仍默认 true —— 也就是 console 的 `/activity-marketing/addon/options` 今天仍然外泄，与 `/decision/v1/addon/options` 行为不同，而 CLAUDE.md 把这两个描述为「同语义验证别名」。② 新增第四条通道要在 4 个以上签名里重新穿一遍这个布尔；漏传一处编译照过（`f(req, true)` 与 `f(req, false)` 都合法），表现是那条通道的 explain 档位悄悄反了。③ 读 `spuDiscountInternal` 要同时装住：explain 控制 trace、explain 还控制 `applicableBefore` 这个诊断快照、`merge` 的 explain 和上层 explain 可以不同（safeFallback 在 :306 硬传 false 而上层可能是 true）、DRL 侧的 explain 是**构建期**开关会改变 KieBase 缓存键 —— 四个含义共用一个名字。④ 因为 trace 不可解析，「哪个活动被淘汰、为什么」这件事在响应里存在两份（traces 字符串 + items 数组），两份可以不一致而没人会发现。

**方案**：用「解释槽（explanation sink）」值对象取代布尔 + List<String>：一个密封接口，`OFF` 是零分配的空实现，`Collecting` 收结构化事件、在视图边界渲染成今天那些字符串。挂在已经贯穿所有这些方法的第一个参数 `ActivityRuleContext` 上，签名里的布尔因此整体消失。选密封接口 + 空对象而不是「保留布尔但改成枚举」：布尔的问题不只是可读性，是它把「要不要解释」这个横切决策复制到了每一层；挂在上下文上之后，这个决策只在入口做一次。结构化事件而不是字符串，是为了让 `items[]` 那套推导（比对前后 id 列表）可以直接读事件，两份真相合成一份。

```java
public sealed interface DecisionTrace permits DecisionTrace.Off, DecisionTrace.Collecting {
    DecisionTrace OFF = new Off();
    void add(TraceEvent e);
    List<String> rendered();                 // 渲染结果与今天逐字节一致
    List<TraceEvent> events();               // items[] 与审计日志直接读它，不再反推
    record TraceEvent(Kind kind, String activityId, Map<String, Object> data) {}
    enum Kind { ELIGIBLE, REJECT, LADDER_HIT, MERGE_HIT, CLAMPED, NOTE }
    final class Off implements DecisionTrace {
        public void add(TraceEvent e) {}      // 热路径连字符串都不拼，比今天的 if(explain) 还省
        public List<String> rendered() { return List.of(); }
        public List<TraceEvent> events() { return List.of(); }
    }
    final class Collecting implements DecisionTrace { /* ArrayList + 渲染器 */ }
}

// ActivityRuleContext 已经是 applyLadder/computeAmounts/merge 的首参，把槽挂在它身上
public class ActivityRuleContext { private DecisionTrace trace = DecisionTrace.OFF; public DecisionTrace trace() {…} }

// 签名从此不再有 boolean；档位在入口显式声明一次
public DiscountView spuDiscount(SpuDiscountRequest req)                    { … }  // 热路径，恒 OFF
public DiscountView spuDiscount(SpuDiscountRequest req, DecisionTrace t)   { … }  // console 试算
// merge 内部：ctx.trace().add(new TraceEvent(MERGE_HIT, winner.getActivityId(), Map.of("by", s.name(), …)));
```

```java
见 proposal 内嵌代码块（sealed DecisionTrace + Off 空对象 + TraceEvent + 挂在 ActivityRuleContext 上、签名去布尔）
```

**迁移**：①【纯增量】加 DecisionTrace 与 TraceEvent，`Collecting.rendered()` 的每一行渲染逐字复制自现有拼接代码。②【自底向上换】先换 BenefitEvaluator.merge / capToOrderAmount（它们的 explain 只影响 trace），再换 DecisionEligibilityService.applyJava，最后换编排层；每换一层，保留旧的 boolean 重载委托给 `explain ? new Collecting() : OFF`，这样任何一步都可独立发布。③ 全部换完后删掉 boolean 重载，并把 AddOnPurchaseService 的单参默认从 true 改成 OFF —— 这一条是**行为变更**（console 别名不再外泄资格明细），要单独一个 commit 并同步 docs。④ `ActivityDrlBuilder` 的 explain 是**构建期**开关（影响 DRL 文本 → 影响 KieBase 缓存键），语义与运行期的 trace 槽不同，**不要一起换**，只需改名成 `emitTrace` 把两个概念区分开。安全网：控制台试算的 e2e（`e2e:validate` / `e2e:playbooks` 断言页面上的 trace 文案）、ActivityQuerySafetyFallbackTest（:72、:92、:110-111、:142 全都在断言 trace 字符串内容，是最强的逐字等价证明）、DecisionGoldenSetTest（金额不受影响）。

**风险**：最大风险是 `ActivityRuleContext` **是一个 Drools fact**：ActivityRuleRuntimeService.java:248-251 会把它 insert 进 KieSession。给 fact 加一个可变的非序列化字段在本仓库当前用法下是安全的（DRL 的 LHS 只调 `numberAttr`/`textAttr`，Step 10 那套 marshaller 不碰这个类），但这是一条必须显式验证的边界 —— 若不放心，退而求其次把槽挂在 `ActivityRuleResult` 上（它已经是 DRL 的 global 且已有 `trace()` 方法），代价是编排层要多传一个对象。第二个风险是第 ③ 步改 console 别名的默认档位：如果有运营/QA 在用 `/activity-marketing/addon/options` 的 trace 排障，会突然看不到 —— 那就把 console 端点显式传 `Collecting`（保持现状），只把「单参重载默认 true」这个隐式行为消掉。**什么情况下不该做**：如果近期不打算新增决策通道，这条的收益主要是可读性与那个默认值不一致的隐患，可以排在前三条之后。

#### 5. [P2] ActivityRuleRuntimeService 一个 297 行的类同时是：DRL 编译器、KieBase 缓存与容量预算、足迹权重估算器、预热线程池所有者、fire 护栏、指标绑定点和 fail-safe 分类器 —— 而它今天只服务**一个**生产 DRL 场景（买赠）。其中 fail-safe 的原因分类靠 `instanceof` 猜异常类，会把「消费端异常」误报成「runaway 护栏触发」，而回退率是本项目的头号告警项。

**证据**：
- `/Users/liruijun/personal/LLM/drools-demo/activity-common/src/main/java/com/lrj/drools/activity/engine/ActivityRuleRuntimeService.java 共 297 行，职责逐块可数：足迹系数常量 :60-64；Caffeine 缓存 + 预算 + weigher :101-106；编译线程池 :107-111；指标绑定 :113；warmAsync :126-135；@PreDestroy :137-140；静态足迹/规则计数 :142-157；compileOrGet + 租户前缀 key :181-186；缓存运维 API :188-202；compile/doCompile :204-220；safeRun :222-234；run + fire 上界 :236-260；异常分类 :262-270；FireCeilingListener :277-295`
- `唯一的生产 eval 只剩买赠：:166-173 `evalGift`；:160-164 的注释自认 evalEligibility/evalDiscount/evalLadder 已随灰度开关删除`
- `按异常**类**猜原因：:266-270 `if (e instanceof IllegalArgumentException) return "compile-error"; if (e instanceof IllegalStateException) return "fire-ceiling"; return "eval-error";``
- `而 IllegalStateException 在本仓库并非 fire-ceiling 专属：engine/BenefitMath.java:273 `throw new IllegalStateException("SHA-256 不可用", e)`，且 ActivityDrlBuilder.java:46 的 DRL header 就 `import com.lrj.drools.activity.engine.BenefitMath;``
- `weigher 对**缓存键字符串**做子串扫描：:104 `weigher((String key, KieBase kb) -> footprintKb(key))`，:143-157 `countRules` 逐次 `indexOf("rule \"")`；而 key = `tenant + " " + drl` 全文（:184）`
- `compileOrGet 同时服务两个语义相反的调用方：写平面要它抛（activity-console/.../ActivityMarketingService.java:760 previewEligibility 依赖异常做校验），热路径要它被吞（:226 由 safeRun 转 null 回退）`
- `为测试保留的第二个构造器就在生产代码里：:84-88 「测试用的无指标构造」`

**今天怎么伤人**：① 「为什么我的规则没生效」这个问题的答案分布在 297 行里的五个互不相干的关注点上；其中三个（足迹系数、编译线程池、fire 护栏监听器）是纯容量/基础设施，与那唯一一条买赠规则毫无关系。任何人要动买赠逻辑，都得先在脑子里排除掉 200 行容量代码。② 实打实的错报：`activity.decision.fallback{reason="fire-ceiling"}` 是 CLAUDE.md 点名的**头号告警项**（回退会静默改金额），而只要 DRL consequence 里任何东西抛出 IllegalStateException，值班看到的就是「runaway 护栏被触发，去查规则死循环」——排查方向从一开始就是错的。③ 同一个 `compileOrGet` 承担两个相反契约（写平面要异常、读平面要静默），任何一方改动都可能误伤另一方，而没有类型层面的区分。④ weigher 每次插入都要对整份 DRL 文本做子串扫描，把「缓存权重估算」这个基础设施细节和「DRL 文本格式」永久耦合 —— 换个 DRL 生成风格（比如规则名不用双引号）权重会静默归零，表现为缓存预算失效、堆/Metaspace 涨。

**方案**：按协作者拆分（SRP 提取）+ 用密封的失败类型取代 instanceof 猜测。不用模板方法/策略：这里没有可替换的算法族，只有被硬塞进一个类的四个正交关注点；正确的手法就是把它们拆成各自可测的协作者。失败类型用 sealed interface 是为了让「回退原因」成为**声明**而不是**推断** —— 与第一条的错误码枚举同一个取向。

```java
@Component class KieBaseCache {            // Caffeine + 足迹 weigher + 预算 + stats 绑定
    KieBase get(String tenant, String drl) throws DrlCompileException;
    long weightKb(); int size(); void evictAll();
}
@Component class DrlWarmer {               // 独立编译池 + warmAsync + @PreDestroy，与决策线程隔离
    Future<?> warm(String tenant, String drl);
}
@Service class GiftRuleRunner {            // 唯一的生产 DRL 场景：建 session、挂护栏、跑、fail-safe
    ActivityRuleResult evalGift(ActivityRuleContext ctx) {
        try { return run(cache.get(TenantContext.get(), builder.buildGiftDrl(ctx.trace())), ctx); }
        catch (RuleFailure f) {            // 原因是**被抛出方声明的**，不是这里猜的
            metrics.fallback(DecisionScene.GIFTS, f.cause());
            log.warn("规则执行失败, 回退旧逻辑. cause={} bizLine={}", f.cause(), ctx.getBizLine());
            return null;
        }
    }
}
sealed interface RuleFailure permits DrlCompileException, FireCeilingExceeded, RuleEvalException {
    FallbackCause cause();                 // COMPILE_ERROR / FIRE_CEILING / EVAL_ERROR
}
// 消费端异常一律被包成 RuleEvalException，不会再冒充 FIRE_CEILING
```

```java
见 proposal 内嵌代码块（KieBaseCache / DrlWarmer / GiftRuleRunner 三分 + sealed RuleFailure 取代 instanceof 分类）
```

**迁移**：①【最小、独立、立刻可发】只修分类：把 :257 的 `throw new IllegalStateException("规则 fire 超上界…")` 换成一个私有的 `FireCeilingExceeded`，`fallbackReason` 改成 `if (e instanceof FireCeilingExceeded) …`，其余不动。这一步就消掉了头号告警的错报，改动不超过 10 行，`ActivityFireCeilingTest` 是现成的门禁。②【提取，行为等价】把 cache/weigher/预算搬进 KieBaseCache，把 executor/warmAsync/@PreDestroy 搬进 DrlWarmer，ActivityRuleRuntimeService 保留同名 public 方法委托过去 —— 现有调用方（ActivityMarketingService.previewEligibility、GenerationWarmService.warmAsync、DecisionPlaneController 无）一行不改。安全网：ActivityWarmTest / ActivityFireCeilingTest / ActivityKieBaseSizingTest（后者正是足迹系数的实测依据，能证明 weigher 搬家后权重不变）。③ 最后才把外壳改名成 GiftRuleRunner 并删掉委托层。④ 顺带把 `countRules` 的入参从「缓存键」改成「DRL 文本」（key 拼接前算好权重再传入），切断权重与 key 格式的耦合。

**风险**：第 ② 步动的是 Caffeine 缓存的构造位置 —— 若不小心变成每个协作者各建一份缓存，KieBase 会被编译两遍、足迹翻倍，而这**不会有任何测试变红**（功能全对，只是内存涨）。所以提取后必须有一个断言 `cacheSize()` 在两次 compileOrGet 同 DRL 后仍为 1 的用例（今天已有类似断言的话直接复用）。另外 `compileOrGet` 是写平面预览的编译校验入口（产物落 `activity_condition.generated_drl`，是生产数据），改它的异常类型要同步看 ActivityMarketingService.java:762 那个 `catch (Exception e)`，别让预览的错误提示消失。**什么情况下不该做**：第 ② ③ 步是纯结构收益，如果团队近期不打算再往规则运行时加场景，做完第 ① 步（修错报）就可以停 —— 那一步是唯一在修真实缺陷的部分。

#### 6. [P2] `activity.marketing.*` 这一族配置没有配置对象，散成 6 个 @Value 字段注入 + 3 个构造参数默认值，跨 5 个类；由此长出四种「生产代码里的测试接缝」——反射改私有字段、第二个测试专用构造器、package-private 方法、生产代码里的 noop() 工厂。副作用是两个已被文档宣告为「代码里从不读取」的死字段被一个反射测试钉住，删不掉。

**证据**：
- `6 处 @Value 字段/参数注入，分散在 5 个类：service/ActivityQueryService.java:59、66、74；activity-decision/.../GenerationWarmService.java:56；activity-console/.../ActivityMarketingService.java:73；tenant/RoleGateFilter.java:40；另有 engine/ActivityRuleRuntimeService.java:93-95 三个构造参数默认值`
- `仓库里其实已有正确范式，只用在了另一族配置上：tenant/TenantProperties.java:24 `@ConfigurationProperties(prefix = "activity.tenant")` —— 全 activity-common 仅此一个`
- `反射改字段：activity-common/src/test/java/com/lrj/drools/activity/ActivityQuerySafetyFallbackTest.java:235-237 连续三行 `ReflectionTestUtils.setField(query, "ruleEngineEnabled"/"javaBenefitEval"/"javaEligibilityEval", …)``
- `被钉住的死字段：ActivityQueryService.java:66-76 两个 `@Value` + `@SuppressWarnings("unused")`，注释与 CLAUDE.md 都写明「仅保留配置兼容，代码里从不读取」`
- `第二个测试专用构造器在生产代码里：engine/ActivityRuleRuntimeService.java:84-88 「测试用的无指标构造」`
- `生产代码里的测试工厂：metrics/DecisionMetrics.java:112-119 `noop()`；测试专用可见性：activity-decision/.../GenerationWarmService.java:116 `void rebuildStaleSnapshots()`（package-private 只为可测）`
- `而那个测试真正在验的断言其实与反射无关：ActivityQuerySafetyFallbackTest.java:156、163 的 `verifyNoInteractions(runtime)``

**今天怎么伤人**：① 这个测试的名字叫 `legacyFalseFlagsCannotSwitchProductionBackToDrools`，它反射把两个**已死**的字段置 false 再断言行为不变。于是：任何人做那个显然正确的清理（删掉两个 unused 字段）都会让这个测试**炸在 ReflectionTestUtils 找不到字段**上 —— 测试反过来把它的清理对象锁死了。死代码因为被测试引用而不可删，这是最难察觉的一种技术债固化。② 配置读取点散在 5 个类里，「这个模块一共有哪些开关、默认值是什么」这个问题只能靠全仓 grep 回答；`activity.marketing.snapshot.max-age-ms` 这种影响止损行为的关键阈值和 `four-eyes-enabled` 这种合规开关没有任何一处能一起看到。③ 四种测试接缝意味着生产代码的形状有四处是被测试机制决定的：`BenefitEvaluator` 不能加 final（要被继承打桩）、`ActivityRuleRuntimeService` 多一个构造器、`GenerationWarmService.rebuildStaleSnapshots` 不能设 private、`DecisionMetrics` 多一个静态工厂。

**方案**：复用仓库既有的 @ConfigurationProperties 范式（TenantProperties 已经证明它在本项目可用），把 `activity.marketing.*` 收成一个不可变 record 树，构造注入。这不是「引入新抽象」，是把一族已经存在的配置从 5 处字段注入收敛到 1 处声明 —— 顺带天生消掉反射：测试直接 `new MarketingProperties(...)`。

```java
@ConfigurationProperties(prefix = "activity.marketing")
public record MarketingProperties(RuleEngine ruleEngine, Snapshot snapshot, boolean fourEyesEnabled) {
    public record RuleEngine(boolean enabled, long cacheMaxWeightKb, int maxFiresBase, int maxFiresPerRule) {}
    public record Snapshot(long maxAgeMs) {}
}

// 构造注入，测试直接造值：不再 ReflectionTestUtils.setField、不再为测试留第二个构造器
public ActivityQueryService(DecisionDataLoader loader, ActivityRuleRuntimeService rt, DecisionMetrics m,
                            BenefitEvaluator b, DecisionEligibilityService e, MarketingProperties props) {
    this.engineEnabled = props.ruleEngine().enabled();   // 两个 java-* 死字段同批删除
}

// ActivityQuerySafetyFallbackTest 的 query(...) helper：
//   new ActivityQueryService(loader, runtime, metrics, benefits, eligibility, props(engineEnabled));
// 三行 ReflectionTestUtils 直接删；这个测试真正在验的是 :156/:163 的 verifyNoInteractions(runtime)，
// 它不需要任何字段被置成 false —— 生产从来就没读过那两个字段，这正是它要证明的事。
```

```java
见 proposal 内嵌代码块（MarketingProperties record 树 + 构造注入 + 删除反射三行与两个死字段）
```

**迁移**：①【纯增量】新增 MarketingProperties 并在两个 app 上 @EnableConfigurationProperties；application.yml 一个字节不改（前缀与层级已经对得上）。②【逐类切换】ActivityQueryService → GenerationWarmService → ActivityMarketingService → ActivityRuleRuntimeService，每类一个 commit：把 @Value 换成构造参数，同时把该类的测试从反射/第二构造器改成传 props。③ 切完 ActivityQueryService 后，同一 commit 里删掉 `javaBenefitEval` / `javaEligibilityEval` 两个死字段，并把 ActivityQuerySafetyFallbackTest 里那三行反射删掉（`verifyNoInteractions(runtime)` 原样保留，它才是真正的门禁）。④ ActivityRuleRuntimeService 切换后删掉 :84-88 那个测试专用构造器。安全网：全套现有测试 —— 因为默认值逐字复制自 @Value 的 `:default` 段，任何配置读取偏差都会立刻表现为行为变化；`DecisionDdlGuardTest` / `ActivityWarmTest` / `ActivityFireCeilingTest` / `ActivityQuerySafetyFallbackTest` 覆盖了这四个类的配置敏感路径。

**风险**：两处要小心。第一，`activity.marketing.java-benefit-eval` / `java-eligibility-eval` 这两个 key 仍可能出现在**线上环境的配置文件/环境变量**里；record 绑定在遇到未知 key 时默认是忽略的（不是失败），所以删字段不会让线上起不来 —— 但若有人开了 `spring.config.fail-on-unknown-keys` 之类的严格模式就会炸，上线前确认一次。第二，@Value 字段注入允许「Bean 造好之后字段才被填」，改成构造注入后如果某个类在构造期就读配置（ActivityRuleRuntimeService 构造期就用 cacheMaxWeightKb 建缓存并打 log），行为其实更正确，但要确认没有循环依赖。**什么情况下不该做**：如果暂时不打算删那两个死字段（比如想再留一个发布周期观察线上还有没有人配它），那第 ③ 步就先只做「反射换构造注入」，保留字段 —— 收益少一半，但风险归零。

---

### 维度：取数层与快照（DecisionDataLoader / DecisionSnapshot·Builder·Store / GenerationWarmService / persistence repositories）

#### 1. [P0] 「把活动行拍平成候选事实」这件事在仓库里有三份独立实现（走库 flatten、快照 entity→CandidateTemplate、快照 template→candidate），只有一份被编译器守住；而且其中一条归约规则（资格条件行怎么取）在两条路上已经真的漂了。

**证据**：
- `activity-common/src/main/java/com/lrj/drools/activity/service/DecisionDataLoader.java:322-350 — flatten 里 18 个 c.setXxx(...) 的字段扇出（第一份）`
- `activity-common/src/main/java/com/lrj/drools/activity/snapshot/DecisionSnapshotBuilder.java:117-128 — entity → CandidateTemplate 的 18 个位置参数（第二份，唯一被编译器强制的一份）`
- `activity-common/src/main/java/com/lrj/drools/activity/snapshot/DecisionSnapshot.java:178-201 — CandidateTemplate.toCandidate 里 17 个 setter（第三份）`
- `activity-common/src/main/java/com/lrj/drools/activity/snapshot/DecisionSnapshot.java:194 — 代码里给上一次事故留的注释：「漏拷这一行的表现是『快照路径不封顶、DB 路径封顶』」`
- `activity-common/src/main/java/com/lrj/drools/activity/service/DecisionDataLoader.java:371-387 — 走库侧条件行按**整行** putIfAbsent，generatedDrl 与 conditionTreeJson 取自同一行`
- `activity-common/src/main/java/com/lrj/drools/activity/snapshot/DecisionSnapshotBuilder.java:99-106 — 快照侧对 constraint 与 tree **各自** putIfAbsent，且 tree 额外要求解析成功 → 两个字段可能来自不同的行`
- `activity-common/src/main/java/com/lrj/drools/activity/persistence/ActivityConditionEntity.java:24-25 — 表上只有普通索引，(tenant,activity_id,version,scene) 没有唯一约束，多行是数据面允许的`
- `activity-common/src/main/java/com/lrj/drools/activity/domain/ActivityCandidate.java:37-38 — extraConfigType / extraDataJson 两个字段没有任何一条装配路径填（全仓库只有 getter/setter 定义，无调用），是三份扇出各自漏抄留下的死字段`

**今天怎么伤人**：① 加一个权益配置字段（第七形态、每单限减次数、任何新的 rule 列）今天要改 4 处装配代码，其中只有 2 处（record 头 + 18 参构造）被编译器强制，flatten 与 toCandidate 漏了照样编译通过、两条路各自的单测照样全绿；漏在哪一侧就是「同一张券在走库与走快照发不同的钱」，且只有恰好造了用到该字段场景的对拍用例才照得出——`redPackageMaxDiscount` 已经这样漏过一次，注释还挂在 DecisionSnapshot:194。② 条件行归约今天就不等价：同一 (activityId, version) 存在两行 ELIGIBILITY 条件（第一行树 JSON 坏、第二行好）时，走库侧拿到「第一行的 drl + 第一行的坏树」→ fail-closed 淘汰不发钱；走快照侧拿到「第一行的 drl + 第二行的好树」→ 资格通过照常发钱。同一个用户、同一个请求，打 console 和打 decision 结论相反，两侧日志都干净。③ 读懂任意一条路都要同时在脑子里装住「哪些字段在哪一层被填」「谁是编译期守的、谁是靠人守的」两套隐式契约。

**方案**：把「行 → 候选」收敛成唯一实现：引入值对象 `ActivityMaterial`（本质是把 `CandidateTemplate` 升格成两条路共用的物料行，并把 constraint/tree 一起收进来），它提供两个唯一的扇出方法——`of(entity...)` 与 `toCandidate(scope, withGifts)`。快照存 `ActivityMaterial`，走库路径每请求现造 `ActivityMaterial` 再 toCandidate。选值对象而不是策略/模板方法，是因为这里没有「多种算法」要多态，只有「一份字段映射被抄了三遍」；把它变成一个 record 的两个方法，编译器就能守住全部扇出点（新增分量 = 全部构造点必须改）。条件行的归约同时收敛成「整行取第一条」这一份实现，让 constraint 与 tree 结构上不可能来自不同行。

```java
// activity-common/.../material/ActivityMaterial.java —— 两条路共用的唯一「物料行」
public record ActivityMaterial(
        String activityId, String activityName, Integer activityType, String bizLine,
        Integer activityStatus, Integer activityAreaType, String districtIds,
        Integer inventory, Integer userInventory, Integer version, int priority,
        Integer takeType, BigDecimal amount, String unit, String rangeAmount, BigDecimal maxDiscount,
        Instant startTime, Instant endTime, List<GiftResult> gifts,
        String eligibilityConstraint, ConditionNode eligibilityTree) {

    /** 唯一的 entity → 物料 扇出（原 flatten 与 SnapshotBuilder 各一份）。
     *  条件行必须**整行**传进来：constraint 与 tree 同源是 fail-closed 成立的前提。 */
    public static ActivityMaterial of(ActivityManageEntity m, ActivityRuleEntity r,
                                      List<GiftResult> gifts, ActivityConditionEntity cond) { ... }

    /** 唯一的 物料 → 可变事实 扇出（原 flatten 与 CandidateTemplate.toCandidate 各一份）。
     *  scopedSpuIds 仍由调用方传：null=作用域未知 / 空集=已知为空，语义原样不动。 */
    public ActivityCandidate toCandidate(Set<Long> scopedSpuIds, boolean withGifts) { ... }

    public boolean inWindow(Instant now) { return !now.isBefore(startTime) && !now.isAfter(endTime); }
    public boolean typeIs(ActivityType t) { return t.code() == activityType; }
}
// DecisionSnapshot 用它替换 CandidateTemplate；flatten 退化成一行 map：
//   materials.stream().map(m -> m.toCandidate(scope.get(m.activityId()), withGifts)).toList()
```

**迁移**：三小步，每步可独立合并：① 把 `CandidateTemplate` 原样移出并改名 `ActivityMaterial`（加 constraint/tree 两个分量），快照侧零行为变化；② `DecisionDataLoader.flatten` 改成先造 `ActivityMaterial` 再 `toCandidate`，对着旧 flatten 的 18 个赋值逐条核对；③ 条件行归约统一成走库侧现行的「整行取第一条」，删掉快照侧的按字段 putIfAbsent。安全网：`SnapshotParityTest`（两条路逐字段 + 0-SQL 防假绿）、`DecisionGoldenSetTest` 52 例、`DecisionQueryCountTest`（本改动不动查询次数，仍是 5）。建议补一条对拍用例：同一 (activityId, version) 插两行 ELIGIBILITY 条件（一行树坏一行好），钉死两条路给同一结论。

**风险**：第 ③ 步会改变快照侧在「多条条件行」下的行为：今天可能放行，改后 fail-closed 淘汰——那确实会改钱。上线前必须先对生产库跑一次 `select activity_id, version, count(*) from activity_condition where scene='ELIGIBILITY' and enabled=1 and is_del=0 group by 1,2 having count(*)>1`；结果非空就先清数据再改代码，别让「收敛」顺手把一批活动关掉。若确认为空，③ 是纯收敛、零行为变化。第 ①② 步没有这个风险。什么情况下不该做：如果近期要拆掉 `ActivityCandidate` 这个可变事实本身（改成 record + 结果分离），应等那个改动先落地，否则这里的 `toCandidate` 会被推倒重来。

#### 2. [P0] 快照的三种状态转换（publish / refresh / rollback）签名同形、无任何校验、且不是原子的——契约整个活在 Javadoc 里；结果是它唯一的 publish 调用方在一条平凡的重试路径上就会把回滚目标销毁，而 `refresh` 正是为了防这件事才被发明出来的。

**证据**：
- `activity-common/src/main/java/com/lrj/drools/activity/snapshot/DecisionSnapshotStore.java:33-43 与 53-59 — publish 与 refresh 参数、返回、可见性几乎同形，唯一区别写在注释里（「refresh 不动上一代指针」）`
- `activity-common/src/main/java/com/lrj/drools/activity/snapshot/DecisionSnapshotStore.java:35-38 — current.put 与 previous.put 是两条独立语句，两个线程并发 publish 同一桶可让 previous 指向 current 自己`
- `activity-common/src/main/java/com/lrj/drools/activity/snapshot/DecisionSnapshotStore.java:62-73 — rollback 无条件相信 previous 是「上一次真发布」，没有代际校验`
- `activity-decision/src/main/java/com/lrj/drools/activity/engine/GenerationWarmService.java:151-168 — warmTenantBizLine 先 publish（:155），再查 ACTIVE artifact 并预热（:159-167）`
- `activity-decision/src/main/java/com/lrj/drools/activity/engine/GenerationWarmService.java:91-100 — 预热段抛 RuntimeException 时 catch 住且**不更新 lastSeen**，下一轮同一代际会再走一遍 → 第二次 publish 把 previous 覆盖成刚发布的同一代`
- `activity-common/src/main/java/com/lrj/drools/activity/snapshot/DecisionSnapshotStore.java:15-17 — Javadoc 声称「publish 只在快照完全构建并预编译完成后被调用」，而实际调用顺序是先 publish 后预编译，实现与自己写下的契约相反`

**今天怎么伤人**：回滚是这套发布模型里唯一的止损原语（CLAUDE.md 明确写「BenefitEvaluator 出 bug 时的回滚手段 = 部署回滚 + 快照 rollback」）。今天只要 `artifactRepo.findByBizLineAndStatus` 或 warmAsync 提交这一段抛一次异常（DB 抖动、编译池饱和），同一代际就会被 publish 两遍，previous 变成「刚发布的那一代」——此后 `rollback` 返回 true、日志打印「已回滚」、决策物料纹丝不动。故障当天你以为回滚了，实际没有，而且没有任何读数会告诉你（generation 不变、age 很新、source 是 snapshot）。这不是理论风险：`refresh` 这个方法本身就是为了堵住同一个坑（兜底重建占用回滚槽位）才加的，而 publish 这条门一直开着。附带一条：调用方选错方法（该 refresh 用了 publish）今天是编译通过、运行静默的。

**方案**：把「一个桶的完整状态」表达成不可变值对象 `SnapshotSlot(current, previous)`，用 `ConcurrentHashMap.compute` 做 CAS 整体替换；把三种转换收敛成一个私有 `apply(next, Transition)`，在这唯一的地方判定合法性：PUBLISH 只在代际真前进时才占用回滚槽位、REFRESH 必须同代否则拒绝并告警。选「不可变 slot + compute」而不是加锁，是因为要保护的是**两个指针的联合不变量**，用值对象把它们绑成一个原子单元最直接；转换用枚举/密封类型表达，则让「这是发布还是重建」变成参数而不是方法名，调用方选错时有一个统一的地方能拒绝它。

```java
/** 一个桶的完整状态：current + 回滚目标，整体替换。 */
record SnapshotSlot(DecisionSnapshot current, DecisionSnapshot previous) {}
private enum Transition { PUBLISH, REFRESH }

public void publish(DecisionSnapshot s) { apply(s, Transition.PUBLISH); }
public void refresh(DecisionSnapshot s) { apply(s, Transition.REFRESH); }

private void apply(DecisionSnapshot next, Transition t) {
    slots.compute(key(next.tenant(), next.bizLine()), (k, cur) -> {
        if (cur == null) return new SnapshotSlot(next, null);
        long from = cur.current().generation(), to = next.generation();
        return switch (t) {
            // 同代重复 publish（预热失败后的重试）不得占用回滚槽位，否则 rollback 退回自己
            case PUBLISH -> to > from ? new SnapshotSlot(next, cur.current())
                                      : new SnapshotSlot(next, cur.previous());
            // refresh 必须同代：异代说明调用方选错了方法，拒绝并保留现状
            case REFRESH -> to == from ? new SnapshotSlot(next, cur.previous())
                                       : warnAndKeep(k, from, to, cur);
        };
    });
}
```

**迁移**：单文件重构，对外方法签名全部不变（publish/refresh/rollback/get/forTenant/all/oldestAgeSeconds/size/clear），内部换成 `ConcurrentHashMap<Key, SnapshotSlot>`。安全网：`SnapshotParityTest#rollbackRestoresPreviousGeneration`（发布 gen1→gen2→rollback→再 rollback 应 false）与 `SnapshotStaleRebuildTest`（refresh 同代、builtAt 前进）现成可用。补两条新用例：`同代重复 publish 两次后 rollback 仍应回到上一代`、`refresh 传异代应被拒且 current 不变`。第二步（可独立）把 `warmTenantBizLine` 的 publish 移到 artifact 预热之后，让实现与 Store 的 Javadoc 契约方向一致。

**风险**：把 publish 移到预热之后会让「发布→快照可见」晚一次 repo 查询的时间（预热本身是异步提交，不阻塞），若有时序敏感的 e2e 断言需要同步调整。REFRESH 拒绝异代会把今天的静默容忍变成「这一轮不重建 + 一条 warn」——今天两条路径在同一个调度线程上串行，不会产生异代 refresh，所以实际影响为零；但如果将来把兜底重建挪到独立线程池，要先想清楚该拒绝还是该接受。什么情况下不该做：如果计划把快照存储换成进程外共享（Redis/本地文件），这层内存并发契约会整个被替换，那就别先在这里投入。

#### 3. [P1] `Materials` 被定义成取数实现 `DecisionDataLoader` 的嵌套 record，快照包无法复用，只好另造一个少两个分量的 `Materialized` 半成品；`load()` 于是变成手工缝合两个半成品的地方，把「定序」这条不变量、代际取最小、条件树合并全塞在同一段过程式代码里，并在热路径上复制整租户的条件树。

**证据**：
- `activity-common/src/main/java/com/lrj/drools/activity/service/DecisionDataLoader.java:104-121 — Materials 是 loader 的嵌套 record，外加一个三参兼容构造器`
- `activity-common/src/main/java/com/lrj/drools/activity/snapshot/DecisionSnapshot.java:148-149 — Materialized 只有 candidates + eligibilityDefs，缺 trees 与 provenance，注释自陈「与 Materials 同形」`
- `activity-common/src/main/java/com/lrj/drools/activity/service/DecisionDataLoader.java:138-151 — 两次遍历 snaps（一次收候选、一次收 trees）、手算 minGen、手拼 provenance、手 new Materials`
- `activity-common/src/main/java/com/lrj/drools/activity/service/DecisionDataLoader.java:145-146 — `trees.putAll(snap.eligibilityTrees())` 把每个桶里**每个活动**的条件树复制进新 map`
- `activity-common/src/main/java/com/lrj/drools/activity/service/DecisionEligibilityService.java:105-106 — 下游只按候选 id 查 1~3 次，其余全部是白复制`
- `activity-common/src/main/java/com/lrj/drools/activity/snapshot/DecisionSnapshotStore.java:76-82 与 117-119 — forTenant 每请求扫**跨租户**的整张 map 做字符串前缀匹配，key 是 `tenant + "|" + bizLine` 拼串`
- `activity-common/src/main/java/com/lrj/drools/activity/service/DecisionDataLoader.java:173-179 与 150,154 — ordered() 这条「合并赢家不能由迭代顺序决定」的不变量，只靠 load 出口两次手工调用维持`
- `activity-common/src/test/java/com/lrj/drools/activity/ActivityQuerySafetyFallbackTest.java:170,187,305-306 — 测试直接 new 三参 Materials，多候选场景全是**未定序**的，生产形状从未被按定序后的样子验证过`

**今天怎么伤人**：① 热路径白开销与租户规模成正比：一个有 20 条业务线、共 500 个在线活动的租户，每次决策要做 500 次 map put 复制条件树（只为查 1~3 个 key），再加一次跨租户的全表前缀扫描——这笔开销恰好长在被宣传成「零数据库查询」的那条路上，任何延迟排查都会先去看 SQL 而不是这里。② key 拼串使 tenant 名里出现 `|` 就会跨租户串桶（tenant=`a`,bizLine=`b` 与 tenant=`a|b` 撞同一个 key）。③ 定序不变量在类型之外：今天已经有 8 个 Materials 构造点（6 处测试 + 2 处早退），任何新的构造点都会静默失去定序，而它决定的是「金额并列时哪张券中奖」。④ 想给 Materials 加一个分量（比如把 strategy 收进来）要同时改 record 头、兼容构造器、ordered() 的重建、三处 `new Materials(List.of(), List.of(), Map.of())` 早退（:189/194/213）。

**方案**：把 `Materials` 提升为独立的物料契约值对象（移出 loader，放进 `activity.material` 包），让快照的 `materialize` 直接产出它，`Materialized` 整个删掉；用**规范构造器**接管定序与不可变化，让「未定序的 Materials」在类型上不存在，`ordered()` 与它的两次手工调用一起消失；trees 只装本次命中候选的。Store 的 key 换成 `record BucketKey(String tenant, String bizLine)`，顺带解决前缀扫描与拼串歧义。选值对象 + 规范构造器而不是 Builder：这里要的是「不变量在构造时被强制」，不是「构造过程分步可读」。

```java
// activity-common/.../material/Materials.java —— 两条路共用的唯一出参
public record Materials(List<ActivityCandidate> candidates,
                        List<EligibilityRuleDef> eligibilityDefs,
                        Map<String, ConditionNode> eligibilityTrees,
                        StackStrategy strategy,
                        DecisionProvenance provenance) {
    /** 规范构造器兜住「定序」：拿不到未定序的 Materials，ordered() 与它的调用点一起删掉。 */
    public Materials {
        candidates = candidates.stream()
                .sorted(comparing(ActivityCandidate::getActivityId, nullsLast(naturalOrder())))
                .toList();
        eligibilityDefs = List.copyOf(eligibilityDefs);
        eligibilityTrees = Map.copyOf(eligibilityTrees);
    }
    /** 空物料：strategy 直接给 MAX，**不查库**——保住空候选分支今天的查询次数。 */
    public static Materials empty(DecisionProvenance p) {
        return new Materials(List.of(), List.of(), Map.of(), StackStrategy.MAX, p);
    }
}

// DecisionSnapshot 直接产出 Materials，trees 只装命中候选的那几个：
public Materials materialize(List<Long> spuIds, ActivityType type, Instant now, boolean withGifts) { ... }

// DecisionSnapshotStore 的 key 换成结构化的，去掉前缀扫描与 `|` 歧义：
record BucketKey(String tenant, String bizLine) {}
private final Map<BucketKey, SnapshotSlot> slots = new ConcurrentHashMap<>();
```

**迁移**：① 先把 `Materials` 提到独立包（`DecisionDataLoader.Materials` 保留为过渡别名，或一次性改 6 处测试引用）；② 规范构造器接管定序，删 `ordered()`——已核对现有多候选测试不受影响（`ActivityQuerySafetyFallbackTest:170` 是 STACK 求和，:187 是 PRIORITY 非并列，重排都不改结论）；③ `materialize` 直接返回 Materials，`load` 的两次 snaps 遍历合成一次；④ Store 换 BucketKey。安全网：`SnapshotParityTest`（逐字段 + 0-SQL 断言，第 ③ 步改错必红）、`DecisionGoldenSetTest` 52 例、`DecisionQueryCountTest`。**关键等价点**：strategy 进 Materials 后，走库路径的空候选早退必须走 `Materials.empty(...)` 直接给 MAX 而不查策略表——否则最常见的「这个 SPU 没活动」请求会从 1 次查询变成 2 次，并且与 `ActivityQueryService` 今天早退时写死的 `"MAX"` 字面量保持一致。

**风险**：规范构造器把 candidates 变成不可变 List：先 grep 确认没有 `materials.candidates().add(...)` 之类的写入（现无）。定序前移会让所有手工构造的 Materials 也被排序，若将来有人想写「验证未排序输入」的测试会自欺——排序断言应直接写在构造器的单测里。BucketKey 改造要同步 `forTenant` 的实现与 `oldestAgeSeconds`（跨租户语义不能变，CLAUDE.md 里已经写明它与 `/decision/v1/snapshot` 的 ageSeconds 不是同一个数）。什么情况下不该做：如果快照即将改成进程外存储，Store 的 key 结构会重做，那部分先别动，②③ 仍值得单独做。

#### 4. [P1] 快照构建期本身是个没有门禁的 N+1 + 全表扫描：绑定查询在按活动的循环里，业务线过滤在内存里做，而每 60 秒的兜底重建会对**每个桶**把这套完整跑一遍。

**证据**：
- `activity-common/src/main/java/com/lrj/drools/activity/snapshot/DecisionSnapshotBuilder.java:74-79 — `findByActivityStatusAndIsDel(ONLINE, 0)` 捞该租户全部业务线的在线活动，再用 Java `if` 丢掉不属于本桶的`
- `activity-common/src/main/java/com/lrj/drools/activity/snapshot/DecisionSnapshotBuilder.java:137-142 — `bindingRepo.findByActivityIdAndVersionAndIsDel(...)` 在 `for (m : live.values())` 循环体内，每活动一次`
- `activity-common/src/main/java/com/lrj/drools/activity/persistence/ActivitySpuBindingRepository.java — 只有按 spuId 的批量方法和按单 (activityId, version) 的方法，没有 `findByActivityIdInAndIsDel`，这个 N+1 是接口层缺口逼出来的`
- `activity-decision/src/main/java/com/lrj/drools/activity/engine/GenerationWarmService.java:116-136 — 兜底重建对 `snapshotStore.all()` 里每个桶各调一次完整 build，默认每 3 秒轮询、60 秒阈值`
- `activity-common/src/main/java/com/lrj/drools/activity/snapshot/DecisionSnapshotBuilder.java:140 — `effective`/`isDel` 谓词在这里用 Java if 表达，而走库侧同一个谓词是 SQL 派生查询（DecisionDataLoader.java:265），同一条规则两种语言两个位置`
- `activity-common/src/main/java/com/lrj/drools/activity/snapshot/DecisionSnapshotBuilder.java:149-154 与 DecisionDataLoader.java:254-257 — resolveStrategy 的仓储调用与 MAX 兜底也是逐字抄了两份`
- `activity-console/src/test/java/com/lrj/drools/activity/DecisionQueryCountTest.java:48 — 查询数门禁只钉热路径 5 次，构建路径完全没有门禁`

**今天怎么伤人**：构建期的查询数是 `M 次全表扫描 + K_total 次绑定查询 + 4M 次批量查询`（M=桶数，K_total=该租户在线活动总数），并且每分钟至少重跑一遍。20 条业务线、4000 个在线活动的租户，一个 decision 实例每分钟就要发 4000+ 次绑定查询和 20 次全量扫描，全部打在只读账号那条连接上；多实例部署直接乘实例数。这条开销今天完全不可见——它不在 `activity.decision.duration` 里、不被任何测试计数、也不会随请求量增长（所以压测照不出），只随**活动目录规模**增长，表现是「平时没事，运营批量建完活动后 decision 的 DB 连接池开始排队」。另外，加一种新的构建期数据（比如商品池、门店维度）今天要在循环里再插一个查询，没有任何东西会拦。

**方案**：分两层修：先补仓储接口的批量口（绑定表的 `findByActivityIdInAndIsDel`、活动表把 bizLine 下推 SQL），把循环查询与内存过滤都消掉；再把「每桶一次全扫」改成「一次扫描 → 按 bizLine 分组 → 产出该租户全部桶」的 `buildAll(tenant)`。这不是引入新模式，而是把已经在热路径上做对的事（`DecisionDataLoader` 的固定 5 次批量取数）搬到构建期——两边本来就该共用同一套取数原语。顺带收益：分组时能直接数出 bizLine 为空的活动，把 CLAUDE.md 里「无 bizLine 的活动进不了任何桶、只能靠诊断端点照」这条从事后排查变成构建期一条 warn + 一个计数器。

```java
// ① 补批量口（绑定表的 N+1 是接口缺口逼出来的）
List<ActivitySpuBindingEntity> findByActivityIdInAndIsDel(Collection<String> ids, Integer isDel);
// ② bizLine 下推 SQL，别把整租户捞回内存再 if
List<ActivityManageEntity> findByActivityStatusAndBizLineAndIsDel(Integer st, String biz, Integer isDel);

// ③ 一次扫描产出该租户全部桶，取代「每桶一次全扫 + 每活动一次绑定查询」
@Transactional(readOnly = true)
public Map<String, DecisionSnapshot> buildAll(String tenant, ToLongFunction<String> generationOf) {
    Map<String, List<ActivityManageEntity>> byBiz = liveGroupedByBizLine();              // 1 次
    List<String> ids = allIds(byBiz);
    var rules  = index(ruleRepo.findByActivityIdInAndIsDel(ids, NOT_DEL));               // 1 次
    var gifts  = group(giftRepo.findByActivityIdInAndIsDel(ids, NOT_DEL));               // 1 次
    var conds  = index(conditionRepo.findByActivityIdInAndSceneAndEnabledAndIsDel(...)); // 1 次
    var binds  = group(bindingRepo.findByActivityIdInAndIsDel(ids, NOT_DEL));            // 1 次（原 K 次）
    byBiz.getOrDefault(null, List.of()).forEach(m ->
            log.warn("[snapshot] 活动 {} 无 bizLine，进不了任何桶", m.getActivityId()));
    return byBiz.entrySet().stream().filter(e -> e.getKey() != null)
            .collect(toMap(Map.Entry::getKey, e -> assemble(tenant, e, rules, gifts, conds, binds,
                    generationOf.applyAsLong(e.getKey()))));
}
```

**迁移**：① 先加仓储方法并替换 build 内部的循环查询与内存过滤——行为等价，`SnapshotParityTest` 直接覆盖（它对每条 bizLine 各 build 一次）；② 再加 `buildAll(tenant)`，把 `GenerationWarmService` 的两个调用点（代际推进 :151-157、兜底重建 :116-136）改成按租户聚合后一次构建，旧的 `build(tenant,bizLine)` 保留给测试与诊断；③ 补一条构建期查询数门禁测试，照 `DecisionQueryCountTest` 的写法用 Hibernate `Statistics` 断言 build 的语句数与活动数无关（今天完全没有这道闸）。第 ① 步与第 ③ 步可以先做、独立收益。

**风险**：`buildAll` 会改变故障隔离粒度：今天一个桶构建失败只丢一个桶，聚合后可能整租户不重建。缓解办法是聚合只做到「取数」为止，装配与 publish 仍按桶各自 try/catch。另外若某租户在线活动量极大（十万级），一次性全捞回内存的峰值内存比逐桶更差——那种规模下只做 ①（批量 + 下推），不要做 ②。什么情况下不该做：如果活动目录规模确定很小（个位数业务线、百级活动），这条的收益主要是「让构建期也有门禁」，优先级可以排在前三条之后。

#### 5. [P1] 读路径与写路径共用同一批 `JpaRepository` 接口，全部放在 `activity-common`，于是「decision 结构上写不了库」这条被文档当成架构不变量的东西，实际只由 MySQL 的授权在运行期保证——类型系统和 bean 图都不保证。

**证据**：
- `activity-common/src/main/java/com/lrj/drools/activity/persistence/ActivityManageRepository.java:13 — `extends JpaRepository`，`save/saveAll/delete*` 全套在 decision 的 classpath 上可直接调用`
- `activity-common/src/main/java/com/lrj/drools/activity/persistence/ActivityManageRepository.java:61-92 — 三个 `@Modifying` 更新（扣库存、还库存、软删版本）与只读方法并排在同一个接口里`
- `activity-common/src/main/java/com/lrj/drools/activity/snapshot/DecisionSnapshotBuilder.java:45,52-64 — decision 侧唯一的取数组件注入的就是这个可写接口`
- `activity-common/src/main/java/com/lrj/drools/activity/service/ActivityPoolMatchService.java:32,105-128 — 一个 `@Service` + `@Transactional`、会 `bindingRepo.save(...)` 写绑定表的写平面服务，物理位置在 common，因此在 decision 进程里是**存在的 bean**（只是今天没人调）`
- `activity-console/src/main/java/com/lrj/drools/activity/service/ActivityMarketingService.java:66,89 — 它唯一的调用方在 console`

**今天怎么伤人**：护栏的失效形态很差：一次误接线（把某个 common 里的写平面服务挂上 `@Scheduled`、或在 decision 侧新增一个顺手 `save` 的诊断端点）不会在编译期红、不会在单测红，而是在**运行期**变成 SQL 权限异常——大促当天一片 500，且报错文本指向数据库权限而不是指向那行代码。今天已经有一个具体样本：`ActivityPoolMatchService` 这个会写 `activity_spu_binding` 的服务被完整打进了只读进程。同时这也让「哪些查询属于决策热路径」失去了边界：`DecisionDataLoader` 与 `DecisionSnapshotBuilder` 面对的是一个 20+ 方法的大接口，读代码时无法一眼看出取数层到底用了哪几个口子。

**方案**：按接口做读写分离（接口隔离 + 模块边界对齐）：`activity-common` 里只保留 `XxxReadRepository extends Repository<>`（注意是 `Repository` 不是 `JpaRepository`，这样 `save/delete` 根本不在类型上存在），只声明决策取数真正需要的 find 方法；`@Modifying` 与 `JpaRepository` 继承上浮到 `activity-console` 的写接口；common 里唯一的写平面服务 `ActivityPoolMatchService` 随之上浮。取数层与快照构建器只注入 read 接口。这样「只读平面写不了库」从一条数据库授权变成一条**编译期**约束，与 `DecisionDdlGuardTest` 守的那条形成上下两道。

```java
// activity-common —— 决策侧只看得见读接口（Repository，不是 JpaRepository：没有 save/delete）
public interface ActivityManageReadRepository extends Repository<ActivityManageEntity, Long> {
    List<ActivityManageEntity> findByActivityIdInAndIsDel(Collection<String> ids, Integer isDel);
    List<ActivityManageEntity> findByActivityStatusAndBizLineAndIsDel(Integer st, String biz, Integer isDel);
}

// activity-console —— 写口只在写平面模块里存在
public interface ActivityManageRepository
        extends ActivityManageReadRepository, JpaRepository<ActivityManageEntity, Long> {
    @Modifying
    @Query("update ActivityManageEntity e set e.inventory = e.inventory - :n ... ")
    int decrementInventory(...);
}

// DecisionDataLoader / DecisionSnapshotBuilder 改注入 ActivityManageReadRepository
// ActivityPoolMatchService（common 里唯一的写平面服务）随写接口一起上浮到 console
```

**迁移**：① 纯加法：先加 read 接口，让 `DecisionDataLoader` 与 `DecisionSnapshotBuilder` 改注入它（旧接口原样不动，零风险，可单独合并）；② 把 `@Modifying` 方法与 `JpaRepository` 继承从 common 的接口挪到 console 的子接口；③ `ActivityPoolMatchService` 上浮 console。安全网主要是编译本身，加上全 reactor 的 `./mvnw test`——注意 CLAUDE.md 那条坑：改了 `activity-common` 必须先 `install -DskipTests` 或加 `-am`，否则下游模块会拿 `~/.m2` 里的旧 jar，表现成「common 绿、console 红」。②③ 之前先 grep 一遍 decision 模块的测试有没有 autowire 写接口。

**风险**：`@EnableJpaRepositories(com.lrj.drools)` 覆盖两个模块，接口挪包后 decision 的上下文里将不再有写接口 bean——任何在 decision 模块里注入它的测试会红（先 grep 确认）。同一实体上存在多个 Spring Data 接口是受支持的，但 Hibernate 二级缓存/审计切面若按接口配置过要一并检查。什么情况下不该做：如果近期还要在 common 里继续增加写平面能力，这个拆分会持续制造「这个方法该放哪」的摩擦，那就只做 ①（read 接口 + 取数层改注入），把 ②③ 推迟到写平面代码位置稳定之后。

#### 6. [P1] 「物料从哪来」是 `load()` 里的一段 if/else，而且这个判定被做了**两次**、判据还不一样；顺带把「跨业务线时用哪条线的合并策略」变成了 `ordered()` 排序的副作用——由 activityId 的字典序决定，两条路一致所以对拍恒绿，也没有任何测试。

**证据**：
- `activity-common/src/main/java/com/lrj/drools/activity/service/DecisionDataLoader.java:133-155 — load 里 if/else 两分支，各自手写 `metrics.decisionSource(...)` 字面量与 provenance 拼装`
- `activity-common/src/main/java/com/lrj/drools/activity/service/DecisionDataLoader.java:248-258 — resolveStrategy 独立地做了第二次来源判定，判据是 `snapshots.get(tenant, bizLine) != null`，与 load 的 `forTenant(tenant)` 非空不是同一个条件`
- `activity-common/src/main/java/com/lrj/drools/activity/snapshot/DecisionSnapshotBuilder.java:75 — 两个判据今天碰巧一致，靠的是「构建期按 bizLine 精确匹配」这条写在第三个文件里的不变量`
- `activity-common/src/main/java/com/lrj/drools/activity/service/DecisionDataLoader.java:249 — 策略取 `candidates.get(0).getBizLine()``
- `activity-common/src/main/java/com/lrj/drools/activity/service/DecisionDataLoader.java:176-177 — 而 candidates 刚被按 activityId 字典序排过，于是跨业务线请求的策略归属由 id 排序决定`
- `activity-common/src/main/java/com/lrj/drools/activity/service/ActivityQueryService.java:194 — 编排层直接消费这个结果，没有任何地方声明过这条规则`

**今天怎么伤人**：① 加一种物料来源（灰度双源、远端配置中心、本地兜底文件）今天要同时改四处：load 的 if/else、resolveStrategy 的第二次判定、`metrics.decisionSource` 的手写标签、provenance 的手工拼装；漏掉第二处的表现是「候选走了快照、策略却查了库」——正好把「快照命中零查询」这条承诺打破，而 `SnapshotParityTest` 的 0-SQL 断言恰好覆盖不到（它给每条业务线都发布了桶）。② 一个购物车里的 SPU 同时命中两条业务线的活动时，`STACK` 还是 `MAX` 生效取决于哪个 activityId 字典序更小。这条规则没写在任何注释里、没有任何测试、两条路一致所以对拍永远绿；`ordered()` 引入时它是被顺手改掉的（此前是 SQL/迭代顺序，同样没道理，但至少不是「排序的副作用」）。运营那边的体感是「同样两条线的活动，换个活动 ID 结果就变了」。

**方案**：把「来源」显式成密封接口 `MaterialSource`（`SnapshotMaterialSource` / `DbMaterialSource`），`load()` 退化成按序试；来源名直接当 metrics 标签与 `provenance.source`，消灭手写字面量。策略与业务线随 `Materials` 一起返回（配合上一条 Materials 重构），`resolveStrategy` 的第二次来源判定整个删掉。选密封接口而不是 Strategy + Map：来源是**有序的、封闭的**——「优先快照、回落走库」这个顺序本身是语义的一部分，`sealed` 还能让将来加第三种来源时编译器提醒所有分支点。同时把 `candidates.get(0).getBizLine()` 提成 `Materials.bizLine()` 这个有名字、有注释、有测试的决策（取值规则保持不变以维持等价）。

```java
public sealed interface MaterialSource permits SnapshotMaterialSource, DbMaterialSource {
    /** 空 = 本来源不适用（不是「没有活动」）；调用方按序试下一个来源。 */
    Optional<Materials> load(MaterialQuery q);
    /** 直接当 metrics 的 source 标签与 provenance.source，不再手写字面量。 */
    String name();
}

// DecisionDataLoader.load 退化成：
public Materials load(List<Long> spuIds, ActivityType type, boolean withGifts) {
    MaterialQuery q = new MaterialQuery(TenantContext.get(), spuIds, type, withGifts);
    for (MaterialSource s : sources) {                 // [snapshot, db]，顺序即优先级
        Optional<Materials> m = s.load(q);
        if (m.isPresent()) { metrics.decisionSource(type.name(), s.name()); return m.get(); }
    }
    return Materials.empty(DecisionProvenance.db());
}

// Materials 自带 bizLine 与 strategy：resolveStrategy 的第二次来源判定删掉，
// 「跨业务线取哪条线的策略」从 candidates.get(0) 的排序副作用变成一条有名决策：
//   /** 跨业务线时取排序后第一个候选的 bizLine —— 与改造前逐字节等价，见 MaterialsBizLineTest。 */
//   public String bizLine() { ... }
```

**迁移**：① 先做纯搬移：把 `metrics.decisionSource` 与 provenance 拼装收进各自 source 实现，load 只剩循环；② 把 strategy 随 Materials 返回（依赖上一条的 Materials 重构；**空候选分支必须用 `Materials.empty` 直接给 MAX 不查库**，否则查询次数会变）；③ 把 `candidates.get(0).getBizLine()` 显式成 `Materials.bizLine()`，取值规则一字不改，并补一条跨业务线用例把这条规则钉下来（今天没有，这是新增覆盖不是行为变更）。安全网：`SnapshotParityTest`（含 0-SQL 断言）、`DecisionQueryCountTest`、`DecisionGoldenSetTest` 52 例。

**风险**：第 ③ 步只是把隐式规则写成显式的，不改行为；但一旦写下来，很容易有人「顺手改成按金额最大/优先级最高的那条线」——那会改钱，必须明确禁止在同一个 PR 里做，需要改就单独立项走金标集。第 ② 步的空候选查询次数是唯一的等价性陷阱，改完务必看 `DecisionQueryCountTest` 的实际打印值（它只断言 ≤5，空候选场景不在其中，得手动确认）。什么情况下不该做：若团队短期内不打算加第三种物料来源，`sealed interface` 那层收益有限，可以只做 ①③——把第二次来源判定与那条隐式 bizLine 规则收掉，本身就是主要价值。

---
