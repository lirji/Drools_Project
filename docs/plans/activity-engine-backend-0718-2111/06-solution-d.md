# 06 · 方案 D：事件溯源发布 + Redis/MQ 的分布式决策平台

## 定位

把每次配置、审核、发布、放量、回滚都写成不可变领域事件；投影器生成当前配置、发布指针和效果视图。决策服务从 Redis/本地两级缓存读取完整快照，MQ 分发产物和决策事件，配置与决策从首期即物理分离。

## 架构与职责

- Command service：校验命令与 RBAC，只追加事件，不直接更新当前状态。
- Event store/outbox：活动聚合版本、全局顺序和幂等键。
- Projector：生成活动详情、审核队列、artifact manifest、release pointer、效果聚合。
- Redis：低延迟指针/快照元数据；KieBase 仍在各决策 JVM 本地。
- Decision service：本地快照 + guarded executor；MQ 订阅更新。
- Analytics consumer：消费决策/核销事件形成 OLAP 看板。

## 核心流程

命令追加事件 → 投影构建/编译 artifact → `ArtifactPrepared` → 多实例预热 ACK → `ReleaseActivated` → Redis CAS 更新 generation + MQ 广播。回滚追加 `ReleaseRolledBack` 事件并重新投影指针。所有历史可重放。

## 灰度/影子/AB

实验也是聚合：分桶算法版本、salt、流量比例、指标和停止条件均为事件；影子与主决策事件进入独立 topic。该方案对效果分析和审计最完整。

## 决策护栏

使用与 B 相同的 stateful guarded executor，并额外以独立线程池/舱壁隔离场景；Redis/MQ 故障时用最近一次已验证本地 stable 快照继续服务。

## 改动范围与成本

需要事件存储、投影一致性、Redis、MQ、Schema Registry/消息兼容、重放工具、死信、数据修复和分布式观测。现有 CRUD/JPA 模型需要大幅重写。预计 9～12 个迭代以上，团队学习与运维成本最高。

## 扩展性

审计、回放、多租户、跨区域、效果分析和大量决策实例的上限最高；读模型可按业务独立扩展。

## 主要风险

- 投影延迟和重放错误会让控制台状态与决策状态分离。
- Redis/MQ/event store 三种一致性语义增加排障难度。
- 从当前 `version+1/is_del` CRUD 迁移到事件溯源风险最大，违反“复用现有基座”的优先原则。
- 为尚未确认的多租户和高容量提前支付复杂度。

## 适用结论

是远期平台化参考，不适合当前单租户 MVP；只有在多租户、跨区域、强审计回放成为刚性需求后才应重新评估。
