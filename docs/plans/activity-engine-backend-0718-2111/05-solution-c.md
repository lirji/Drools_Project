# 05 · 方案 C：KJAR/KieScanner 双部署单元

## 定位

立即拆成配置服务与决策服务。每次审核发布把活动规则集构建为递增 release GAV 的 KJAR，部署到 Nexus/Artifactory；决策实例用 `KieContainer.updateToVersion` 或 KieScanner 获取版本。数据库保存发布单和 GAV 指针。

## 架构与职责

- 配置服务：现有配置写、审核、KJAR 构建、制品上传、发布单。
- 制品库：持久化不可变 KJAR；禁止生产使用同 GAV SNAPSHOT 覆盖。
- 决策服务：只读绑定/路由快照，持有 KieContainer/KieBase；stateful guarded session 执行。
- 发布协调器：先让实例下载/验证目标 GAV，再激活 stable/canary GAV。
- 效果事件通过消息系统异步输出。

## 核心流程

配置版本 APPROVED → `KieFileSystem/KieBuilder` 生成 KJAR → 推送唯一 release GAV → 实例下载并构建容器 → readiness ACK → 指针激活 → 请求按 stable/canary 选择对应 container。回滚把 GAV 指针切回旧 release。影子在独立容器执行。

## 与仓库 Step 16 的关系

可复用 `ScannerService` 已证明的 KieFileSystem、KieModuleModel、ReleaseId、KieMavenRepository、KieContainer/KieScanner API，但不能照搬其固定 `1.0.0-SNAPSHOT`、本地 `~/.m2`、进程内 generation 和 `synchronized` 单实例状态。生产必须使用远程制品库、不可变 release 版本、认证、签名/checksum、清理策略和多实例协调。

## 决策护栏

仍选择 KieBase/container 产生的 stateful session；`fireAllRules(filter,max)`、watchdog、dispose 与方案 B 相同。KJAR 解决版本分发，不解决单次执行护栏。

## 改动范围与成本

需要先把单 Maven 工程拆成 API/配置/决策/规则模型模块，建设远程制品库与发布流水线，处理 fact 类兼容、KJAR 依赖与 GAV 清理。成本高，预计 6～9 个迭代且需要 DevOps 支持。

## 扩展性

规则与应用可独立发版，跨实例产物身份清晰，适合专门规则团队和高频复杂规则。但活动配置是运营数据，给每次活动变更打 KJAR 会产生大量小制品；商品绑定/赠品/策略等非 DRL 数据仍需另一套快照协议，KJAR 不能消除它。

## 主要风险

- `kie-ci` 带 Maven resolver 重依赖与更大攻击面；仓库不可用会影响扩容/冷启动。
- fact 类与 KJAR 的二进制兼容、classloader 泄漏、旧容器回收需专项压测。
- KieScanner 轮询不等于全实例原子激活；仍需 readiness/active pointer 协调。
- 规则和配置分成两个版本源，若未统一 release manifest 会出现“规则 v2 + 赠品 v1”。
- 对单租户 MVP 明显过重。

## 适用结论

当规则团队独立、发布频率高、已有成熟 Nexus/CI/CD 时有价值；当前仓库和 MVP 不具备这些前提，不作为首期选择。
