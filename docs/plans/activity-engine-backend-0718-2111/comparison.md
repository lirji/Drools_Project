# 候选方案对比、风险评审与 plan-judge 结论

## 1. 统一评分规则

每项 1～5 分，5 分表示更优。对“改动风险、复杂度、测试难度、回滚成本”采用反向可读口径：5=低风险/低复杂度/易测试/低成本。权重用于当前单租户 MVP，不代表所有阶段。

| 维度 | 权重 | A 原表单体 | B 内容产物+指针 | C KJAR 双部署 | D 事件溯源分布式 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 正确性 | 25% | 2 | 5 | 4 | 4 |
| 改动风险（低为高分） | 15% | 4 | 3 | 2 | 1 |
| 复杂度（低为高分） | 10% | 5 | 3 | 2 | 1 |
| 可维护性 | 15% | 2 | 4 | 3 | 2 |
| 扩展性 | 15% | 2 | 4 | 5 | 5 |
| 测试难度（易为高分） | 10% | 4 | 3 | 2 | 1 |
| 回滚成本（低为高分） | 10% | 2 | 5 | 4 | 4 |
| **加权总分 / 5** | **100%** | **2.85** | **4.05** | **3.30** | **2.75** |

评分不是为了机械选最高分：B 的优势来自“发布正确性和低成本回滚”恰好是本任务 P0；它在改动/测试上明显弱于 A，在极限扩展上弱于 C/D。

## 2. 横向架构比较

| 项目 | A | B | C | D |
| --- | --- | --- | --- | --- |
| 发布事实源 | 最新配置行 | 不可变内容 artifact + CAS pointer | KJAR GAV + manifest pointer | 领域事件 + 投影 pointer |
| MVP 部署 | 单体 | 单体，物理可拆 | 立即双服务 + 制品库 | 多服务 + Redis/MQ/event store |
| 热路径 | DB 多表 + TTL cache | 本地 immutable snapshot | KieContainer + 配置快照 | 本地 + Redis 两级快照 |
| 多实例收敛 | 清缓存/TTL | outbox 事件 + generation 轮询 | 制品下载 + 激活协调 | MQ + Redis CAS + 投影 |
| 回滚 | 恢复旧数据状态 | 指针切旧 artifact | 指针切旧 GAV | 追加回滚事件 |
| 当前代码复用 | 最高 | 高 | 中 | 低 |
| 首期运维依赖 | MySQL | MySQL | MySQL + Nexus/CI | MySQL/event store + Redis + MQ |

## 3. risk-reviewer：失败场景

### 3.1 兼容性

- A：继续暴露旧响应最容易，但旧的“买赠不跑资格”和“全局 legacy 忽略资格”也容易被固化。
- B：通过兼容 adapter 保留旧接口，新 API 明确 `decisionVersion/degraded`；需要双跑比对防金额变化。
- C：KJAR fact 类与应用版本可能二进制不兼容；需要 schema/模型版本门禁。
- D：CRUD 到事件命令是语义重写，兼容风险最大。

### 3.2 事务与数据迁移

- A 的状态/版本/缓存失效跨边界，无法真正原子。
- B 把“构建 artifact”和“激活 pointer”分开：编译失败不动 pointer；pointer/outbox/audit 同事务。风险是历史 `is_del=1` 版本回填，必须按 `activityId+version` 显式读并核对所有子表。
- C 的数据库事务无法覆盖远程制品上传；必须用 PREPARED/UPLOADED/ACTIVE saga，孤儿 KJAR 定期清理。
- D 追加事件原子，但投影最终一致；修复投影必须支持幂等重放。

### 3.3 并发与幂等

- 四案都必须为控制面请求建立数据库唯一幂等记录，不能只依赖 `findFirstByRequestIdAndIsDel`。
- B/C/D 的放量、全量、回滚通过 expected generation/pointerVersion CAS；冲突返回 409，禁止 last-write-wins。
- 同一个 artifact checksum 的并发构建应复用；失败不得缓存异常永久态。
- 决策纯查询不需要持久化幂等结果，但同 requestId + 同 generation 必须路由稳定；若输入 hash 不同，应记录冲突而非静默复用。

### 3.4 性能与护栏

- A 缓存 miss 仍有 N+1；C 冷容器下载/类加载；D Redis/MQ 增加外部故障面；B 的本地 snapshot 最符合 50ms 预算。
- 四案的 Drools 执行都不能靠 HTTP timeout 替代引擎护栏。
- stateful 方案的明确上限优于 stateless listener 断路；但 `halt()` 不能中断单个无限 RHS。模板 RHS 必须受控且禁止运营代码，条件节点/列表/候选/档位有硬上限。
- 影子执行必须独立线程池、短超时、低采样、有界队列；禁止 `CallerRunsPolicy` 把影子退回结算线程。

### 3.5 安全

- 所有方案必须增加控制台 RBAC、决策调用方认证、输入大小/速率限制、审计与敏感字段脱敏。
- `generatedDrl` 只由服务生成；即使数据库被篡改，ArtifactBuilder 也应从条件树重新翻译并比对 checksum，不盲信存量 DRL。
- 产物需 SHA-256 和模板/schema 版本；C 还需制品签名和仓库凭证治理。
- `pinVersion`、dryRun、explain 完整 trace 只能授权内部角色，防止外部绕过发布和枚举规则。

### 3.6 灰度与回滚

- 分桶输入必须规范化，算法版本固化；Java `hashCode`、随机数、实例本地计数都不可使用。
- 比例调整与 salt 变更是两种操作；变 salt 会重洗全体用户，必须禁止隐式发生。
- 指针更新后旧 artifact 至少保留“最大请求时长 + 回滚窗口”；缓存不能立即强制驱逐在途引用。
- 多实例发生代次分裂时，响应 `decisionVersion/generation` 和 lag 指标用于定位；超过收敛阈值自动暂停继续放量。
- 回滚必须可在配置面故障时由受控运维命令/API 执行；但不能绕过审计和 CAS。

### 3.7 观测与效果数据

- 同步写决策日志会把数据库故障传导到结算，所有方案都必须非阻塞。
- 丢日志不影响主决策，但要用 `decision_event_dropped_total` 监控数据完整性。
- 影子差异必须区分“产物差异、降级差异、超时/未执行”，否则效果看板会误报。
- 规则名、活动 id、用户 id 进入 Prometheus 会造成高基数，必须以代码测试和 meter filter 双重阻断。

## 4. 三个重点技术点裁决

### 4.1 护栏

选每请求 stateful `KieSession`，复用 KieBase，不复用/池化 session。标准顺序：new session → listener/filter → setGlobal/insert → watchdog → `fireAllRules(filter,maxFires)` → 判定触限/超时 → finally cancel watchdog/dispose。

stateless + listener 仅作为压测对照，不作为默认候选：它没有 `fireAllRules(max)` 入口，listener 在 `afterMatchFired` 抛异常属于间接控制，也无法等价提供持有 session 的 watchdog。压测要比较吞吐、P95/P99、分配率、GC、创建/销毁成本；若 stateful 未达标，先优化产物/候选/会话创建，不牺牲硬上限。只有在针对当前 Drools 8.44.2 的中断、清理、并发压力测试全部通过且有相同护栏语义时，才可重新 ADR。

### 4.2 产物仓库

MVP 选 B 的内容 artifact + release manifest + 指针：它与“规则即运营数据”更匹配；单活动 artifact 封装 binding/gift，业务线 manifest 原子冻结活动集合和唯一 strategy，避免跨活动混代。Step 16 KJAR 仅作为二期备选，因为 KJAR 单独解决 DRL 分发，不能天然封装全部活动数据；当前虽已有 `kie-ci`，生产仍需远程仓库与运维链路。

### 4.3 灰度/影子/AB

流量路由在 artifact pointer 层，`ReleaseAgendaFilter` 只做产物内部规则通道。默认 userId 确定性分桶；shadow 非阻塞；审核状态与发布状态分离；所有比例/指针变更均 CAS + audit。全局 `rule-engine.enabled` 保留为应急 kill switch，不再充当灰度。

## 5. plan-judge 最终合并结论

以 B 为主，吸收：

- A 的单体部署与现有事务/409/条件翻译复用，控制 MVP 运维复杂度；
- C 的不可变版本身份、预拉取再激活和未来制品适配接口；
- D 的 outbox、不可变审计和事件 schema 思想，但不引入事件溯源/Redis/MQ。

明确不吸收：A 的原表直接发布、C 的首期 KJAR/Nexus、D 的首期事件溯源。所选方案的最大弱点是自建 artifact/pointer 协议和迁移测试成本；FINAL_PLAN 必须以 schemaVersion、checksum、CAS、双读影子、MySQL 迁移演练和故障注入来约束它。
