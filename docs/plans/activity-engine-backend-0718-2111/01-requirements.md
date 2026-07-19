# 01 · requirements-analyst：需求与验收基线

## 1. 输入与约束

本文只深化后端生产架构，不复述产品蓝图。事实输入为：

- `docs/plans/activity-engine-platform-0718/00-product-blueprint.md`
- `docs/plans/activity-engine-platform-0718/10-architecture-gap-and-options.md`
- `src/main/java/com/lrj/drools/activity/` 全量源码、对应测试、`pom.xml` 与三份 `application*.yml`
- 同仓库真实存在的 Step 9/14/15/16 实现：`HotReloadService`、`GuardService`、`ReleaseAgendaFilter`、`MeteredRuleListener`、`ScannerService`

硬约束：决策 API 位于结算热路径；引擎故障必须返回可消费的降级结果，不能阻塞下单；运营只能提交白名单条件树，不能提交 DRL；MVP 保留并加固 `version+1`、`requestId`、409 并发冲突基座；单租户为主；本轮不改代码。

## 2. MVP 后端能力

### 2.1 配置与治理面

1. 红包（固定/随机配置、阶梯）和买赠两类活动的创建、编辑、预览、列表、详情与商品池绑定。
2. 每次编辑产生不可变版本；提交审核后冻结；通过或驳回必须记录操作者、意见和时间。
3. 合法主状态流：`DRAFT -> PENDING_REVIEW -> APPROVED -> CANARY -> ONLINE -> OFFLINE`；`PENDING_REVIEW -> REJECTED -> DRAFT(新版本)`；`CANARY/ONLINE -> ROLLED_BACK`。禁止任意状态跳转。
4. 发布前必须完成条件翻译、DRL 编译、规则模板安全检查、产物校验与预热，失败不得移动线上指针。
5. 回滚是切换到已发布且校验通过的不可变产物；若无稳定产物则下线。回滚不能重新生成规则。
6. 单租户 RBAC 至少包含 Operator、Reviewer、Admin、Viewer；默认强制 Operator 与 Reviewer 职责分离。

### 2.2 决策面

1. 新增稳定版本化入口 `POST /decision/v1/evaluate`，一次返回折扣与赠品；现有 `/activity-marketing/spu-discount`、`/gifts` 在迁移期保留。
2. 单次请求在开始时固定一份业务线 release manifest 指针快照，整个请求不得混用 stable/canary 的不同代次。
3. 指针按 `bizLine` 路由到包含一组活动 artifact 与唯一策略快照的 manifest；默认以 `userId` 做稳定百分比分桶，缺少 `userId` 时使用 `requestId`，两者都缺失则只走 stable。
4. 灰度与 A/B 共用确定性路由器但语义分开：灰度用于逐步替换；A/B 用固定实验分组做效果比较。
5. 影子执行永不改变主响应，并使用有界隔离执行器；资源紧张时可丢弃影子任务但必须计数告警。
6. 每次 Drools 执行必须同时具备 fire 数上限、挂钟超时、发布通道过滤、会话释放与结果标识。
7. 引擎、产物、缓存或 DB 异常均返回 HTTP 200 的降级响应；鉴权、限流、请求结构错误仍分别返回 401/403、429、400。

### 2.3 规则产物与多实例

1. 单活动发布对象是不可变、带 checksum/schemaVersion 的内容 artifact；业务线发布对象是把有序 artifact 集合与合并策略冻结在一起的不可变 manifest，而不是可变数据库当前行。
2. stable、canary、shadow 指针必须以单行 CAS/乐观锁原子更新，指向已验证 manifest。
3. KieBase 只在发布预热或缓存恢复时编译，正常决策不得冷编译。
4. 缓存键必须是稳定版本身份，不得再以 DRL 全文作为无界 key；缓存必须有容量、淘汰和 single-flight。
5. 多实例采用“事务内指针 + outbox、事件加短轮询兜底、响应回带 generation”的有界最终一致；零毫秒全实例切换不是 MVP 承诺。

### 2.4 可观测与效果分析

1. 指标至少覆盖 QPS、总/引擎延迟、fire 数、护栏触发、降级率、发布代次滞后、编译失败、缓存命中、影子丢弃和结果差异。
2. metrics tag 只允许 `scene/strategy/engineMode/result/guardReason/bizLine` 等受控低基数值；`activityId/userId/spuId/requestId/ruleName` 只能进日志或 trace。
3. 决策事件异步落地，不得同步阻塞主响应；事件包含实际使用的产物、分组、结果摘要和降级原因。
4. MVP 能统计曝光、命中、优惠额、延迟与灰度差异；核销率和 GMV 依赖电商侧订单/支付事件，不在平台单方可交付范围。

## 3. 已确认业务规则

- MVP 不新增 `ActivityType`，只生产化 `RED_PACKAGE(1)` 与 `BUY_AND_GET(5)`。
- 时间范围为开始、结束均包含；现代码使用 `!now.isBefore(start) && !now.isAfter(end)`。
- 资格条件为空即恒通过；非空条件由 `RuleConditionTranslator` 白名单翻译，当前最大深度为 5。
- 当前资格规则是淘汰式：不满足条件时 `ActivityCandidate.reject`；无条件活动默认通过。
- 阶梯区间是 `[min,max)`；`max=null` 由 `LadderRangeParser` 转为大数哨兵。
- `MAX` 取最高金额；`MUTEX/PRIORITY` 取 priority 数值最小者，同优先级取金额更高者；`STACK` 汇总金额并选主活动。
- 商品池自动绑定只修改 `bind_source=1` 的 AUTO 行，不修改手工绑定。
- 库存和用户库存 MVP 只作配置/展示，不做强一致扣减。
- 控制台写幂等按 `requestId`；并发版本冲突维持 HTTP 409。

## 4. 边界与非目标

- 不做多租户数据隔离、租户计费、规则市场。
- 不做秒杀库存、优惠预算强一致扣减、发券/赠品履约。
- 不接受运营上传 DRL、脚本或任意表达式；生成 DRL 只由平台模板产生。
- 不在 MVP 引入 Redis、MQ、Nexus 作为必选依赖；为二期保留接口和 outbox 接缝。
- 不承诺业务效果的因果归因；没有核销回传时只做决策侧统计。
- 不把 Step 18 `campaign` 教学链路迁入生产主链；它只作为运行时编译/rehydrate 的参考实现。

## 5. 歧义、默认决策与待验证

| 项目 | 本计划默认值 | 状态/影响 |
| --- | --- | --- |
| 审核层级 | 单级审核，强制职责分离 | **待业务确认**；多级审核会改变工作流模型 |
| 灰度维度 | `userId` 哈希，10000 个 basis-point 桶 | **待业务确认**地域/门店/cohort 是否首期需要 |
| 性能目标 | 服务端热路径 P99 < 50ms，可用性 ≥99.9% | 来源文档建议值，**上线前必须按真实容量校准** |
| 引擎默认超时 | 不硬编码；按 P99 预算反推，配置有上下界 | **待压测**，客户端不得任意放大 |
| 降级优惠语义 | 新 `/decision/v1` 默认 `NO_PROMOTION`，保证下单但不额外授予优惠；旧接口迁移期维持当前 legacy MAX | **待业务/财务确认**。当前 legacy 会忽略资格，存在超发风险 |
| 回滚目标 | 默认上一个已稳定发布产物，可显式下线 | **待业务确认**是否允许跨多个版本 |
| 日志留存 | 明细短期、聚合长期 | **待合规/容量确认**期限与脱敏要求 |
| 身份源 | 控制台 JWT/OIDC；决策方 API Key 或 mTLS | **待基础设施确认**现有网关/SSO 能力 |
| 多实例切换窗口 | 事件通知 + ≤1s 轮询兜底，响应回带 generation | **待容量演练**，若要求强一致需引入共享路由/流量闸门 |

## 6. 易遗漏的失败边界

- 同 `requestId` 并发到达时，现有“先查后写”仍可双写；必须由数据库唯一约束兜底。
- `ActivityMarketingService#create` 编辑时只把旧 `activity_manage` 设为删除，旧子表仍存活；发布产物不能依赖“最新未删行”来完成历史回滚。
- 当前 SPU 绑定查询不带 version，历史绑定可能被读出；新决策应从不可变快照取绑定关系。
- 当前阶梯先写 `computedAmount`，随后 discount DRL 的 compute 规则会以 `redPackageAmount` 再覆盖；生产产物必须通过回归测试明确并修正该语义，不能假设现实现正确。
- `MAX` 同金额可能触发多条候选，最终主活动依赖 fire 顺序；需要稳定 tie-breaker。
- `ActivityMarketingService` 的 activityId 是毫秒 + 进程内序号，多实例不能保证全局唯一；生产需 UUID/ULID 或数据库唯一约束。
- `ReleaseAgendaFilter` 只能阻止 activation 执行，不负责用户百分比分桶；百分比路由必须发生在产物选择层。
- `halt()` 等当前 activation 完成后才停止，无法强杀一个无限 RHS；安全依赖“受控模板 + 输入上限 + fire 上限 + bulkhead”组合。
- 影子任务、效果日志和发布通知必须在队列满/下游故障时可丢弃或重试，不能反压结算线程。

## 7. 可验证验收标准

1. 运营无法通过任何控制台 API 提交原始 DRL；超深/超节点/超列表条件在编译前 400。
2. 两个并发编辑只有一个成功，另一个 409；两个相同 `requestId` 并发写最终只有一份业务结果。
3. 未审核版本不能进入 canary/stable 指针；发布编译失败时线上 generation 不变。
4. 0%、5%、20%、100% 分桶可重复、跨实例一致；同一 key 在同一 experiment/salt 下分组稳定。
5. 回滚只切指针，旧 KieBase 在途请求可完成，新请求使用回滚 generation；目标 MTTR < 5 分钟。
6. 失控规则在 maxFires 处停止；超时请求返回 200 降级结果；所有路径均 dispose 会话。
7. 正常热路径零冷编译、零配置表 N+1；缓存 miss single-flight；压测达到经确认的 P99/SLO。
8. 任一 activity/user/spu/request/rule 名均未出现在 metrics tag；决策明细可按 traceId 检索。
9. 控制台越权 403、决策凭证无效 401/403、超限 429；敏感上下文不明文写普通日志。
10. 关闭配置面、发布事件或效果日志写入后，stable 决策仍可用且不阻塞下单。
