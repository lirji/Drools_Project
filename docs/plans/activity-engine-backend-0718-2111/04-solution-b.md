# 04 · 方案 B：不可变内容产物 + 版本指针的可拆分单体

## 定位

MVP 仍单进程部署，但从第一天形成配置面、发布面、决策面三条单向依赖。配置表只负责编辑；发布生成不可变内容产物；决策只读取产物和版本指针。二期把 decision/release-consumer 物理拆出，无需改 API 或数据协议。

## 架构与模块职责

```text
控制台 -> Config/Workflow -> 现有配置表
                           -> ArtifactBuilder -> 不可变 artifact
                           -> ReleaseService -> pointer CAS + outbox
                                                   |
电商 -> DecisionController -> ReleaseRouter -> SnapshotCache -> GuardedRuleExecutor
                                                   |
                                  异步 decision event / shadow compare
```

- Config：复用 `ActivityMarketingService` 的校验、多表写、version+1/409；新增唯一幂等和受控工作流。
- ArtifactBuilder：按明确 `activityId+version` 读取 manage/rule/condition/binding/gift，规范化排序后生成内容 JSON、受控 DRL、模板版本和 SHA-256；发布前编译。
- ManifestBuilder：把一个 `bizLine` 的有序活动 artifact 集合与唯一 APPROVED strategy version 冻结成不可变 manifest；灰度单活动时也生成“仅替换该 artifact”的完整候选 manifest，避免跨活动混代；现有 `saveStrategyIfPresent` 的静默 upsert 被独立 StrategyService 取代。
- ReleaseService：只有全部 item APPROVED/PREPARED 的 manifest 可激活；业务线指针用 `pointer_version` CAS 更新；同事务写 outbox/audit。
- Decision：请求开始固定 pointer generation；按 stable/canary/shadow 路由，直接取不可变快照，不再查配置表。
- Cache：`artifactId`/generation 为 key，有界、single-flight、发布预热；缓存 miss 若不能在内部短预算恢复则降级，不在普通请求冷编译。
- Engine：stateful 每请求会话，KieBase 复用，`fireAllRules(filter,max)` + watchdog halt + finally dispose。

## 原子发布、回滚与多实例

1. 构建和编译在短数据库事务外完成，保存 PREPARED artifact 和候选 manifest。
2. 预热本机；二期可让各实例预拉取并报告 readiness。
3. 事务内校验 `pointer_version`、切 stable/canary/shadow manifest 指针、递增 generation、写 outbox 与 audit。
4. 本进程 after-commit 更新 `AtomicReference`；多实例消费 outbox 事件，短轮询 generation 兜底。
5. 每个请求持有一份 immutable pointer/snapshot 引用，在途请求完成旧代次；新请求使用新代次。
6. 回滚只 CAS 指向旧 artifact；绝不重新翻译或编译。若目标缓存缺失，先预热再切换。

该方案承诺有界最终一致，不宣称全实例零窗口原子。二期若业务要求严于轮询窗口，可增加 PREPARING/ACK/ACTIVATE 两阶段和实例租约；接口无需变化。

## 灰度、影子与 A/B

- `ReleaseRouter` 用 SHA-256(`experimentId|releaseKey|salt|bucketKey`) 映射 0..9999；`releaseKey` 默认等于 bizLine，比例用 basis points。
- canary 组返回新产物结果，stable 组返回旧产物；比例变化只改指针路由配置并递增 generation。
- A/B 固定 experimentId、salt、起止时间和两侧产物，禁止在实验中途换 salt。
- shadow 始终返回 stable 主结果；把输入快照和 shadow artifact 放入有界执行器，超时/队列满直接丢弃并计数。
- `ReleaseAgendaFilter` 只做产物内 release channel/紧急规则禁用，不承担用户分桶。
- `pinVersion` 只对 Admin sandbox 开放，外部 Integrator 不得任意指定优惠版本。

## 改动范围与成本

需新增 workflow/artifact/pointer/outbox/audit/decision-log 表及约 15 个职责明确的类，改造现有三大 service/runtime/controller。MVP 不要求 Redis/MQ/Nexus。实施成本中等，约 4～6 个迭代，测试投入较大。

## 扩展性

决策面不写配置库；协议、产物和指针天然允许水平扩展。二期可把 outbox 传输替换为 MQ，把本地缓存加 Redis 元数据层，但 KieBase 仍驻实例内存。

## 已知弱点

- 自建产物和指针协议，要自行保证 schema 演进、事件幂等、缓存收敛和运维工具。
- MVP 单体没有真实验证网络分区下的多实例行为，物理拆分前仍需故障演练。
- artifact 内容是可重建 KieBase 的规范化源/DRL，不是跨 JVM 可直接反序列化的 KieBase 二进制；实例重启仍需受控预编译。
- 新表和双读迁移较复杂，必须经过 MySQL 级迁移演练。

## 适用结论

最符合“B 为终态、MVP 模块化单体 + 规则产物接缝”，并在复杂度和生产能力之间保持可逆性。
