# 03 · 方案 A：原表直读的加固型模块化单体

## 定位

保持一个 Spring Boot、一个数据库和现有多表模型；在 `ActivityQueryService` 周围补状态机、stateful 护栏、本地缓存和审计。发布仍以 `activity_manage` 最新未删 ONLINE 行为事实源，不建立独立不可变产物仓库。

## 架构与职责

- `ActivityMarketingService`：创建/编辑和现有事务。
- 新 `ActivityWorkflowService`：审核状态和合法转换。
- `ActivityQueryService`：继续从 binding/manage/rule/condition/gift/strategy 组装候选；增加短 TTL 聚合缓存。
- 改造 `ActivityRuleRuntimeService`：每次执行 `KieBase.newKieSession()`，插 facts/global，调用带 filter/max 的 `fireAllRules`，watchdog `halt`，finally dispose。
- `ActivityDrlBuilder` 在生成规则时增加 release 元数据；`ReleaseAgendaFilter` 控制规则通道。
- 同进程异步线程批量写决策日志。

## 核心流程

发布：审核通过 → 更新 manage status/灰度字段 → 清本机缓存 → 后续请求按最新行构建。回滚：把目标版本恢复为未删/ONLINE，再清缓存。灰度：manage 增加 canaryVersion/percentage，按 userId 选择查哪个版本。影子：同请求异步再组装一次目标版本执行。

## 决策护栏选型

选择 stateful 会话，不采用 stateless listener 抛异常断路。理由是前者有明确 `maxFires`、`halt`、`AgendaFilter` 和 `dispose` 生命周期；后者只能在已 fire 后由 listener 异常中断，且没有可持有的 session 做 watchdog。KieBase 仍复用，会话每请求创建，不池化。

## 改动范围与成本

改动主要集中在现有 controller/service/runtime/entity/config；新增状态、审核、日志少量表。实施最快，约 2～3 个迭代可形成闭环，运维无新增组件。

## 扩展性

单进程无法独立扩缩决策面；短 TTL 缓存只能降低 DB 压力，发布和请求仍耦合原表结构。未来拆分时必须重新定义产物边界。

## 主要风险

- “恢复旧行”与现有 `is_del` 语义冲突；旧子表从未同步删除，回滚易读到混合版本。
- 发布不是“先编译后切指针”，编译失败可能发生在生效后的首请求。
- 多实例清缓存无可靠广播，灰度比例和当前版本可能短时不一致。
- 原表 N+1 仍在，缓存失效后 P99 尖刺明显。
- 影子执行重复查库，最容易反压结算线程。

## 适用结论

适合内部低 QPS 过渡，不满足任务要求的“规则产物仓库接缝”和二期低成本物理拆分，不能作为生产 FINAL_PLAN。
