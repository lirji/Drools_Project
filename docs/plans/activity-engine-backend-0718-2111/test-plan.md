# test-designer：测试方案与验收标准

## 1. 测试原则

- 保留现有 3 个 H2 测试作为学习脚手架回归，但生产门禁必须增加 MySQL/Testcontainers；H2 不能证明唯一键、锁、LONGTEXT、时区和 Flyway 正确。
- 每条生成 DRL 必须真实 build + fire；`mvn compile` 不能替代运行时规则测试。
- 金额、命中活动、赠品、版本、generation、分组、降级原因都要断言，不能只断言 HTTP 200。
- 性能、故障和迁移测试是发布门禁，不是上线后观察项。

## 2. 单元测试

### 条件与模板

- `RuleConditionTranslator`：六个现有字段/全部允许与拒绝组合；转义；null；深度 5/6；节点总数、IN/containsAny 长度、字符串长度、数值边界。
- `LadderRangeParser`：字段别名、null max、边界、乱序、重叠、间隙、负 reward、非法 JSON；生产校验应拒绝歧义分档。
- `ActivityDrlBuilder`：四策略、资格、买赠、阶梯真编译真执行；固定输入产出稳定 checksum；同金额/同 priority 有稳定 tie-break；模板不得因 modify/update 自循环。

### 状态机与路由

- 所有合法/非法状态边；审核冻结；驳回编辑产生 version+1；Operator 自审 403。
- `ReleaseRouter` 对 0/1/500/9999/10000 basis points、缺 userId、fallback requestId、缺两者、salt/experiment 变化、跨 JVM 固定测试向量。
- pinVersion 仅 Admin sandbox；Integrator 调用 403。

### 护栏与缓存

- `GuardedRuleExecutor` 正常 fire、maxFires、watchdog halt、AgendaFilter skip、异常、global、finally dispose。
- 单 activation 慢规则验证 timeout 语义并证明不能强杀，触发 bulkhead 告警。
- cache 容量/淘汰、single-flight、checksum mismatch、schema 不兼容、负缓存短 TTL、在途旧引用不被破坏。

## 3. 集成与契约测试

### 配置/发布

1. 创建→编辑→提交→审核→PREPARED→5%→20%→100%→回滚完整流程。
2. 编译失败、checksum 错、manifest item 非 APPROVED/PREPARED、并发 publish/rollback、outbox 写失败时 pointer 不移动。
3. 相同 `requestId` 100 并发只有一个幂等记录/业务版本；不同 payload 同 key 返回冲突。
4. 同 activity version 并发编辑只有一个成功，其他 409。
5. release manifest 必须冻结一个 bizLine 的完整有序 artifact 集合和唯一 APPROVED strategy version；并发策略草稿/发布受 version/CAS 保护；pointer CAS expectedGeneration 错误返回 409。

### 决策契约

- `/decision/v1/evaluate` 必填 bizLine，同时返回折扣与赠品、manifest `decisionVersion/generation/engineMode/degraded`。
- 红包资格、阶梯边界、四种合并、买赠资格、多个 SPU、时间边界、无绑定、已下线。
- 兼容旧 `/spu-discount` 和 `/gifts` 的字段与金额；所有有意差异列白名单。
- 引擎超时/编译产物丢失/cache 故障/DB 故障返回 200 降级；认证/限流/参数错误分别是 401/403/429/400。
- 同请求开始后并发切 pointer，响应只能使用一个 generation。

### 多实例与 outbox

- 两个应用实例：按 releaseKey 的事件丢失时轮询收敛；重复/乱序事件幂等；旧 generation 不覆盖新 generation。
- 实例 A 预热失败不影响 stable；超过 lag 阈值暂停放量并告警。
- 发布后在途请求完成旧代次，新请求用新代次；回滚同理。

### 安全与观测

- RBAC 矩阵、API Key hash/轮换/禁用、暴力请求限流、超大 JSON/列表拒绝、审计不可漏。
- MeterRegistry 枚举所有 tag，断言无 activityId/userId/spuId/requestId/ruleName。
- 决策日志队列满、DB 不可用时主响应延迟不增加到阈值外，drop counter 增加。
- trace/explain 脱敏；普通调用不返回生成 DRL。

## 4. 迁移测试

在生产脱敏快照副本执行：

1. Flyway baseline 能识别现有 Hibernate 建表；重复执行幂等，禁止生产 `ddl-auto=update`。
2. 检测重复 requestId、重复 activityId/version、孤儿子表、旧 manage `is_del=1` 与子表版本关系；先报告再修复，禁止静默丢弃。
3. 为每个当前 ONLINE 活动生成 stable artifact，比较旧查询与新 snapshot 的候选、金额、赠品。
4. 历史可回滚版本只对“完整子表 + 编译通过”者生成 artifact；不完整者标记不可回滚并出报告。
5. 双读影子持续一个完整业务周期：结果差异率阈值由业务确认，所有金额差异可追溯。
6. 回退迁移只停用新 API/指针读，不删除新表；旧列/表至少保留两个稳定发布周期。

## 5. 性能与容量测试

### 护栏选型压测

同一预编译 KieBase、同一 facts 分布比较：

- stateful：newKieSession + max/filter/watchdog + dispose；
- stateless：execute + 计数 listener；
- 当前 stateless：只作性能基线，不作安全候选。

测量 1/10/50/100 candidates，资格/阶梯数量分层；单线程和目标并发；P50/P95/P99、吞吐、CPU、分配率、GC、session 泄漏、触限正确性。stateful 必须满足确认后的 API P99；stateless listener 即使更快，也必须先证明 max/timeout/清理语义等价才可重新选型。

### API 压测

- 热缓存 steady state、冷启动预热、发布瞬间、回滚瞬间、cache stampede、shadow 1%/10%、日志下游故障。
- 初始门槛：热路径 P99 < 50ms、可用性 ≥99.9%、正常决策零冷编译、错误率 <0.1%；最终数值以真实结算预算校准。
- 容量到 70% 即触发扩容/降采样；shadow 和 explain 不计入核心 SLO但不得拖累主 SLO。

## 6. 故障注入与恢复

- MySQL 主库/连接池耗尽：使用最后 stable snapshot，发布写失败，决策不阻塞。
- outbox consumer 停止/事件丢失：轮询收敛；lag 告警。
- KieBase 编译 OOM/异常：artifact 不激活；缓存负载受限。
- runaway/慢规则：max/timeout 生效，bulkhead 不被耗尽。
- 影子 executor 饱和、日志队列饱和：只丢辅助任务。
- 单实例重启/滚动发布：启动预热完成前不接流量；stable 快照可恢复。
- pointer 指向缺失 artifact：拒绝激活；运行时发现则降级并 P0 告警。

## 7. 最终测试门禁

- 现有 activity 回归全绿，且有意语义变化已评审。
- 单元、MySQL 集成、REST 契约、并发、迁移、灰度、回滚、安全、指标基数、故障注入全绿。
- 护栏压测形成 ADR 证据；stateful 路线达到性能目标且无 session/线程泄漏。
- 双读影子达到业务确认的零严重金额差异；所有剩余差异有签字白名单。
- 回滚演练从触发到全实例 generation 收敛 < 5 分钟。
- 可观测面板和告警在预发通过人工演练。
