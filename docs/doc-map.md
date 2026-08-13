# Doc Map（由 /doc-sync 维护）
lastSyncedCommit: 5b7ba69  # refactor/activity-design 分支末端。上一条留的「待填」已结清：
                           # 当时只在工作树里的两批（链路评审整改 / 验证页改打决策平面）后来落成了 50eaca2 及之前的提交
lastSyncedAt: 2026-08-13

## 映射
| 代码区域 / 模块 | 相关文档 | 类型 | 说明 |
|---|---|---|---|
| 全局 / 项目结构 / 构建命令 | CLAUDE.md | 概览+架构 | 给 Claude 的指南：技术栈/约定/坑/代码结构/扩展点 |
| **全局架构**（模块拓扑 / 读写平面 / 决策链路分层 / 发布模型 / 多租户 / 数据模型 / 部署拓扑） | docs/architecture.md | 架构 | ✅ **新建（活文档，2026-08-11）**。末尾「关键不变量」表是改代码前的必读；改模块边界 / 决策链路 / 发布链路时必须同步这份 |
| **技术点梳理**（面试·答辩·onboarding） | docs/tech-highlights.md | 概念+架构 | ✅ **新建（活文档，2026-08-11）**。每条「问题 → 做法 → 代码位置 → 可被追问」；末尾「已知落差」表与 activity-marketing.md 的同名小节要一起改 |
| **容量与引擎选型**（Drools / QLExpress / 纯 Java 实测） | docs/capacity-model.md + examples/capacity/ | 架构+基准 | ✅ **新建（活文档，2026-08-11）**。数字由 `./examples/capacity/run.sh` 实跑得出，**换机器要复跑重标定**；与 plans/activity-engine-platform-0718/50-P0-5-memory-capacity-model.md（dated 归档）互为补充 |
| 全局 / 快速开始 / 目录树 / 部署 | README.md | 概览 | 项目简介 + 每 Step 请求示例 + 前端/部署起法 |
| activity-common/console/decision（活动引擎） | docs/activity-marketing.md | 模块 | 活动营销模块用法 + console/decision 决策面 |
| drools-lab（Step 1–18 教学） | docs/steps-guide.md | 模块 | 各 Step 详解 + REST 接口 + DRL 语义 |
| Drools 能力/选型（与本仓库结构无关，概念文档） | docs/drools-capabilities.md, docs/drools-vs-aviator.md, docs/drools-use-cases.md, docs/rete-intuition.md | 概念 | 不随模块拆分变，doc-sync 一般不动 |
| 面试复习（QLExpress vs Drools 原理/用法/手写题） | docs/interview/qlexpress-vs-drools.md, docs/interview/coding-drills.md | 概念 | 个人复习资料；仅"我在项目里怎么用的"一节引用真实文件路径，重构后需核对。**能力对照在这份，成本/容量对照在 docs/capacity-model.md**，两份别写重 |
| 部署编排（compose/网关/双 app/只读账号/观测/容灾） | docs/deployment.md | 部署 | ✅ 已建（活文档）；现状锚另见 docs/plans/prod-arch-refactor-0719-1330/PROGRESS.md |
| Casdoor auth 端到端交付 | docs/delivery/drools-casdoor-auth/** | 交付 | 认证方案、状态、review、QA 与最终验收证据 |
| QA 复用环境档案（**活文档**，非 dated 快照） | docs/qa/QA_PROFILE.md | QA | ✅ 已随四模块同步（启动命令/健康检查/回归数）；qa-test skill 复用 |
| 历史 QA/测试快照（dated） | docs/qa/activity-multitenant-0719/**, docs/tests/** | 归档 | 点时间记录，不重写 |
| 权益模型重构（评估→计划→裁决→进度） | docs/plans/benefit-model-refactor-0808-2218/** | 交付 | FINAL_PLAN / DECISION_RECORD(D1–D12) / REVIEW-FINDINGS / **PROGRESS.md（进度锚，新会话读这份接手）** |
| 控制台视觉设计（票券工学） | docs/plans/console-ui-coupon-mechanics-0808-2251/** | 交付 | DESIGN_SPEC / DECISION_RECORD（含 PR-0~PR-4 实施记录与两处有意偏离）/ BACKEND-GAPS（设计依赖但后端不存在的接口） |
| **frontend/**（Vue3 SPA，挂 /ui/，由 gateway 镜像托管） | frontend/e2e/data-testid-contract.md（**契约·活文档**）、docs/plans/frontend-tech-visual-0809-1424/**（现状最近的一份） | 前端 | ⚠️ **没有前端"现状"活文档**：视觉/组件契约散在四代 dated 计划归档里（console-redesign-0720-1207 / visual-refresh-0720-1404 / ux-redesign-0721-0852 / tech-visual-0809-1424）。改前端要靠考古。建议后续新建 docs/frontend-ui.md 收敛（令牌三态主题 / effects.css / viz 原语 / Hero·Stat / 自托管字体 / 视觉红线 e2e） |
| 前端视觉换代（深空遥测 · dark-first） | docs/plans/frontend-tech-visual-0809-1424/** | 交付 | DECISION_RECORD（G1–G10 诊断 + D0–D11）/ FINAL_PLAN（令牌映射全表）/ REVIEW（6红12黄4蓝处置）/ PROGRESS（进度锚）/ style-tile.html（三方向样板屏） |
| 活动引擎全链路评审（建活动 → 上线 → 决策 → 领取 → 对账 的断点全景） | docs/plans/activity-chain-review-0811-1730/REVIEW.md | 交付 | **dated 归档，正文不重写**。B1–B9 九个断点 + P0-1~P2-15 分级；2026-08-11 那批整改（作用域 / 封顶 / 下线也 bump / claim 幂等 + 流水 / 决策审计日志）就是照这份做的——想知道某处改动**为什么**要改，先读它 |
| 验证页改打决策平面（provenance + 快照诊断端点） | docs/plans/validate-decision-plane-0811-1640/** | 交付 | DECISION_RECORD（D1–D10，每条带**被否方案**列：只回显三个值 / 平铺三字段 / 页面自动降级 / 前端排序补救）+ PROGRESS（gateway :8095 实跑证据 + 未做项）。承接上一行的 P1-9 |
| **活动引擎结构性重构**（设计模式 / 可读性 / 扩展性） | docs/plans/activity-design-refactor-0812-1232/** | 交付 | **FINAL_PLAN**（4 根因 + 18 项 + 6 批次 + 明确不做清单）/ **AUDIT-FINDINGS**（36 条原始发现与逐条对抗判定，含 11 条 P0 全部被推翻的记录）/ **BREAKING-CHANGES（对外契约变更清单，改调用方前必读）**。已被 CLAUDE.md / README / architecture / QA_PROFILE 四处列为必读 |
| 历史进度（activity-marketing 移植 / 原生前端台） | IMPLEMENTATION_PROGRESS.md | 归档 | ✅ 已加"已被 F3/四模块重构取代"顶部横幅（引用的 app.js/examples.js 已删） |

## 变更类型
- 2026-07-20 重构尾巴（arch-change）：Maven 单模块 → 四模块（activity-common / drools-lab / activity-console:8081 / activity-decision:8082）+ 两独立 Spring Boot app + nginx 网关(8095) + prometheus/grafana；前端 F3 退役旧原生页；M1.4 发布代际轮询预热。
- 2026-08-09 架构重构第一批（arch-change）：后端 P0 全部 + P1-1 代际快照包 + P1-2/1-3 分层引擎（阶梯/折扣/资格移出 Drools）；
  前端 PR-0~PR-4（票券工学颜色+形制换代、viz 图表原语、TierRuler 刻度尺）。基线 后端 218 / 前端 101 / e2e 31 全绿。
  ✅ 该条留的待办已在 2026-08-09 的 /doc-sync 中办掉：CLAUDE.md 架构章节与测试数均已按实跑核对更正。
- 2026-07-22 认证交付（security/deployment）：Compose 默认 Casdoor auth；console + decision JWT 边界、8095 PKCE callback、门户自动入口、双租户 E2E、CI 与回滚手册完成。
- 2026-08-09 视觉换代 + 权益形态扩容（new-feature + arch-change）：
  ① **前端「深空遥测」视觉换代**（dark-first 令牌换代、effects.css 效果层、Hero/Stat 原语、
     自托管 Inter/JetBrains Mono 拉丁子集 80KB、新增 e2e:visual 视觉红线守卫）；
  ② **活动引擎五项能力**：随机金额（确定性随机）/ 第 N 件折（决策入口补 lines，**唯一契约升级**）/
     一口价 + claim 库存原子扣减 / 加价购两阶段 / 决策指标两端点（activityId 标签有 200 基数上限）。
  两条架构决策值得记住：**决策 ≠ 提交**（决策服务连只读账号、物理写不了库，故库存扣减只能在写平面 claim）；
  **加价购第二阶段不读客户端价格**（价格重查配置，从根上杜绝改价，因而不需要 quoteToken 与密钥管理）。
  基线：后端 314（common 121 含 3 skip / console 176 / decision 17）、前端 vitest 154、e2e 9 套 83 条断言全绿。
  ⚠️ 仓库仍无 CHANGELOG.md / docs/adr/ —— 本轮经确认**刻意不建**，变更留档继续走 docs/plans/<日期>/ 与本文件。

- 2026-08-11 文档同步 + 架构/技术点/容量三份新活文档（doc-sync + new-doc）：
  ① **新建三份活文档**：`docs/architecture.md`（架构总览 + 关键不变量表）、`docs/tech-highlights.md`（技术点清单 + 已知落差）、
     `docs/capacity-model.md`（三引擎容量实测）+ 基准代码 `examples/capacity/`（**刻意在 Maven 源码根外**，同 examples/aviator：它引 QLExpress）。
  ② **20 条文档漂移全部修正**（每条经独立对抗校验，零误报）。其中 high 级的是四类**幽灵资产/幽灵开关**：
     旧红包算额 DRL（`buildDiscountDrl` + 三个 eval）已删但文档还写"保留作对照"、"翻回对照开关"根本不存在、
     `buildEligibilityDrl` 被误标成"隔离对照资产"（它其实是生产数据，落 `activity_condition.generated_drl`）、
     CLAUDE.md 把 Step 17 `DmnService` 说成不走 `DroolsConfig` 的 KieContainer（**恰恰相反**，它构造注入的就是那个）。
  ③ 顺带修了**代码注释**里的两处幽灵：`BenefitEvaluator` javadoc 引用的 `BenefitEvaluatorParityTest` 全仓不存在（连同 39→52 例订正）、
     `TenantContextFilter` javadoc 还写着"只挂 /activity-marketing/*"（早已扩到 /decision/v1/*）。
  ④ README 补了**整节缺失的 Step 3**（此前从 Step 2 直接跳到 Step 4，`/cart/checkout` 从未介绍）。
  基线：后端 371（common 150 含 3 gated skip / drools-lab 0 / console 204 / decision 17），本机实跑（**该批次的时点值**，非当前基线；当前基线见本文件最后一条变更）。
  容量结论一句话：每活动常驻 **纯 Java 1.8 KB / QLExpress 8.4 KB / Drools ~180 KB**——差的是量级，不是调优空间。

- 2026-08-11 链路评审整改（arch-change + bugfix）：照 `docs/plans/activity-chain-review-0811-1730/REVIEW.md` 的 P0/P1 做掉四件事：
  ① **权益作用域**——`ActivityCandidate.scopedSpuIds` + `BenefitEvaluator` 的 baseAmount 三档 + `BenefitMath.scopedSubtotal`。
     此前减免基数恒是整单，9.9 一口价会把同车 5000 元电视一起带走；作用域是真子集又没带 `lines` 时按**不适用**处理，不拿均价凑。
  ② **出口封顶 + 逐活动明细**——`DiscountView.clamped` / `items[]`，STACK 不再裸累加到超过订单金额。
  ③ **下线也推进代际**——`bump` 从「只在上线分支」扩到任何状态流转，另加快照 `builtAt` 兜底重建。
     修的是最要命的一条：**下线按钮在生产链路上不生效**，快照继续按原配置发钱。注意 `ArtifactService` 里那条刻意保留的缺口：
     bizLine 为空的活动跳过 bump（它本来就进不了任何快照桶，凑哨兵值会造出跨桶重复候选）。
  ④ **留痕与止损**——新表 `activity_grant`；claim 幂等（唯一约束 tenant+order_id+activity_id）+ 不传 version 解析成**当前 ONLINE 版本**
     + 每人限领 `userInventory` + `POST /{id}/release` 冲正 + `GET /grants` 客服查单；红包通道补审计日志（decisionId / items；买赠与加价购不落盘）。
  另有两处判定语义变更：spuId 资格条件 NUMBER→ARRAY 放宽成「包含」；随机红包种子指纹规范化并改读 `randomSeedSpu`
  （`DecisionEligibilityService` 专门把它维持成「第一件」的旧值，否则历史已发金额会集体重抽、对账全崩）。

- 2026-08-11 验证页改打决策平面 + provenance + 快照诊断端点（arch-change + feature）：决策记录见 `docs/plans/validate-decision-plane-0811-1640/`。
  ① **先修了一条真 bug**（D1）：`DecisionDataLoader.loadFromDb` 的候选身份不按版本收窄——绑定查询不带 version、旧版本绑定行不软删，
     于是「v1 绑 A/B → 编辑成 v2 只绑 A」后单查 B，走库路径仍把它当候选（作用域为空），而 **AMOUNT 形态压根不调 baseAmount**，
     直接把 `redPackageAmount` 发出去：**走库照发 50 元、走快照根本不是候选**。现按「当前线上版本的绑定 ∩ 请求 SPU 为空 ⇒ 不是候选」淘汰，
     守卫 `SnapshotParityTest#narrowedBindingStopsPayingOnBothPaths`。
  ② **候选定序**（D7）：`DecisionDataLoader.ordered()` 在两条路的合流点按 activityId 排序。快照侧倒排值是 `Set.copyOf`，
     迭代序由 JDK SALT 决定、**每次 JVM 启动翻面**，而 pickByAmount/pickByPriority 打平时先到先得——不定序则「金额并列谁赢」既不稳定、两条路也对不上。
  ③ **新增 `DecisionProvenance`**（source + generation + buckets），贯通 `Materials` → `DiscountView`/`GiftView`/`AddOnOptions`/`AddOnQuote`
     四个响应契约（都保留兼容构造器），审计日志同步补 source/generation。generation 取参与本次决策的桶里**最落后的一代**（多桶时是下确界，`buckets` 是这个约定的诚实声明）。
  ④ **新增诊断端点 `GET /decision/v1/snapshot[?activityId=]`**：回本租户快照桶清单与「某活动在哪个桶 / 不在任何桶」。
     它存在的理由是 provenance 三个值在**最要命的那条故障上全绿**——bizLine 为空的活动进不了任何桶，而兜底重建只遍历已存在的桶、永远建不出不存在的那个。
     只读、不发起决策、**不占 activityId 指标标签位**（见坑 13）。配套 `GET /activity-marketing/generation?bizLine=`（console，行不存在返回 0）作为 generation 的参照物。
  ⑤ **前端「优惠验证」页改打决策平面**：`apiClient.decision` 的 base 是**网关前缀 `/api/decision`**（写成 `/decision/v1` 会落到 nginx 兜底 location 打到 console 拿 404），
     dev 另配独立 proxy `^/api/decision(/|$)` → :8082 带 rewrite（**不能复用已有的 `decision` 前缀**，那是 Step 7 教学端点 `/decision/calculate` 的）。
     页面加平面选择器（决策服务 / 控制台走库 / 两条都打对拍，**默认决策服务**）、物料来源徽章、逐活动明细表、快照探针；
     「决策服务不可达」与「决策未命中」是**两种状态**（401/403 单独判为「可达但未授权」，不降级）。
     双打对拍排除五类正常差异（decisionId / traces / mode / items 顺序 / strategy 瞬态），**两侧 source 都是 snapshot 时判红**（那是在拿快照跟自己比）；
     页面明写：对拍只能照出取数层分歧，两条路共用同一份求值器，**绿 ≠ 算对了**。
  ⑥ e2e：`e2e-validation.mjs` 的 ENDPOINTS 与 quote 断言改成 `/api/decision/*`，新增 `waitForSnapshot` 显式等桶；
     `e2e-tablet-smoke`/`e2e-phone-smoke` 显式点「控制台走库」（它们测布局不测平面，默认 BASE 是裸 console）。
  基线（本机实跑，**以 `Tests run:` 汇总为准，别求和 surefire XML，见 CLAUDE.md 坑 14**）：
  后端 `./mvnw clean test` **430**（activity-common 166 含 3 skipped / drools-lab 0 / activity-console 244 / activity-decision 20）；
  前端 `npx vitest run` **283**（25 个测试文件）。
  ↑ 这两个数是**该批次的时点值**，已被下一条变更取代（当前 476 / 285）。

- 2026-08-13 活动引擎结构性重构 + 文档同步（arch-change + refactor + doc-sync）：
  起因是一句「整个项目没有使用到设计模式，活动这部分可读性、拓展性不高」。先做六维并行审查（36 条发现、逐条对抗验证），
  **11 条被提为 P0 的发现验证后一条都没留下**——推翻理由集中在三类：作者注释里已解释且理由成立、伤害路径今天不可达、
  提出的抽象反而增加理解成本。结论是这套代码**没有在产的结构性故障**，问题几乎全是「未来成本」，
  于是方案的排序依据定为「这个抽象今天就在阻止一类静默事故复发」而不是「这里可以套一个模式」。
  归并出的**根因**是：契约密度极高但**只写在注释里、没有落到类型上**——每一条「必须记得」都是一次未来的静默事故。
  ① **单一装配入口**：新增不可变 `OfferSpec`，收敛走库 `flatten` / 快照 `CandidateTemplate` / `toCandidate` 三份手写字段扇出
     （只有中间那份被编译器守着，这条缝已裂开过两次：坑 16 `scopedSpuIds`、`DecisionSnapshot:194` 的 `redPackageMaxDiscount`）；
     `CandidateTemplate` 删除，由 `OfferSpecArchGuardTest` 钉死。
  ② **编译期穷尽**：`computeAmounts` 六形态分派改成**无 `default` 的 switch 表达式**（漏分支即编译失败）——
     注意必须是**表达式**，arrow switch 语句对枚举并不强制穷尽；两道横切 guard（随机排在 `redPackageAmount==null` 之前）留在 switch 外。
  ③ **单一词汇表**：`RejectReason`（code 进指标 + message 进 rejectReason，此前两条独立语句手工配对、已实证漂移过
     `price-above-order` vs `price-above-base`）、`DecisionScene`（收敛四套 scene 词汇）、`DecisionAttrs`（钉住决策属性键）、
     `DecisionMode`（取代裸 boolean explain 并删掉四个默认值方向相反的无参重载）。
  ④ **类型级只读边界**：六个 `*ReadRepository extends Repository<T,ID>`（非 `JpaRepository`，`save`/`delete` 在类型上不存在），
     `ActivityPoolMatchService` 上浮 console。此前「decision 写不了库」只靠运行期只读账号。
  ⑤ **领域异常层**：`ActivityException` + `ActivityErrorCode` + 两个 `@RestControllerAdvice`。
     两处**刻意不注册**的兜底同样重要：console 不注册 `ISE→409`（会把 `list`/`grants` 上的内部 bug 伪装成客户端错误）、
     decision 不注册 `IAE→400`（同理）。
  ⑥ 写平面拆 `GrantService` + `ActivityVersionResolver` + 状态迁移表 + `targetStatus=3` 封口；
     快照消构建期 N+1、`SnapshotSlot` 原子替换、**新增生产可达的回滚端点**（此前 `rollback` 零生产调用方，
     文档承诺的「回滚是止损手段」是空头支票）。
  **对外契约变更**（详见 BREAKING-CHANGES.md）：四眼 409→**403**、claim 恒 409→**400/404/409 分流**、release 缺参→400、
  bulk-status 非法 targetStatus→400、`activity_decision_source_total` 的 scene 标签值改用 `DecisionScene.code()`
  （已核对 grafana/prometheus 均无消费者）、详情响应新增 `servingVersion`。
  **等价审查抓到两条真问题并已修**（提交 `6ed0e77`）：快照桶归属被下推成 SQL 相等，生产 MySQL 的
  `utf8mb4_0900_ai_ci` 大小写不敏感会让 `Retail` 漏进 `retail` 桶（**改变了谁能被发钱**，而测试跑 H2 照不出来
  → 新增坑 19 + `SnapshotBizLineCollationTest` 用 `IGNORECASE=TRUE` 复现生产排序规则）；console 的 `ISE→409` 兜底作用域过宽。
  ⚠️ 一条值得记住的教训：**`e2e-validation.mjs` 里断言四眼 409 的用例在整轮重构中一直是红的却没人知道**——
  e2e 不在 `./mvnw test` 与 `vitest` 的闸门里。闸门覆盖不到的契约，改了不会有人告诉你。
  基线（本机实跑，以 `Tests run:` 汇总为准，见坑 14）：后端 **476**（common 193 含 3 skipped / drools-lab 0 /
  console 256 / decision 27）、前端 **285**（25 个测试文件）。增量全部来自新增的结构性护栏用例，不是新功能。
  同批 doc-sync：7 份活文档更新、**22 处不实描述经对抗校验修掉**；另处理 5 个缺口（本文件回写、
  `steps-guide` 的「decision 只读」断言订正、`servingVersion` 补文档、`BACKEND-GAPS` 三处订正、
  `benefit-model-refactor/PROGRESS.md` 加顶部横幅）。
