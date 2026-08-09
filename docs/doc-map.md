# Doc Map（由 /doc-sync 维护）
lastSyncedCommit: 899aee4        # 文档已同步到重构尾巴 F3+M1.4+M2.1+M2.2+M2.3 完成态
lastSyncedAt: 2026-07-22

## 映射
| 代码区域 / 模块 | 相关文档 | 类型 | 说明 |
|---|---|---|---|
| 全局 / 项目结构 / 构建命令 | CLAUDE.md | 概览+架构 | 给 Claude 的指南：技术栈/约定/坑/代码结构/扩展点 |
| 全局 / 快速开始 / 目录树 / 部署 | README.md | 概览 | 项目简介 + 每 Step 请求示例 + 前端/部署起法 |
| activity-common/console/decision（活动引擎） | docs/activity-marketing.md | 模块 | 活动营销模块用法 + console/decision 决策面 |
| drools-lab（Step 1–18 教学） | docs/steps-guide.md | 模块 | 各 Step 详解 + REST 接口 + DRL 语义 |
| Drools 能力/选型（与本仓库结构无关，概念文档） | docs/drools-capabilities.md, docs/drools-vs-aviator.md, docs/drools-use-cases.md, docs/rete-intuition.md | 概念 | 不随模块拆分变，doc-sync 一般不动 |
| 面试复习（QLExpress vs Drools 原理/用法/手写题） | docs/interview/qlexpress-vs-drools.md, docs/interview/coding-drills.md | 概念 | 个人复习资料；仅"我在项目里怎么用的"一节引用真实文件路径，重构后需核对 |
| 部署编排（compose/网关/双 app/只读账号/观测/容灾） | docs/deployment.md | 部署 | ✅ 已建（活文档）；现状锚另见 docs/plans/prod-arch-refactor-0719-1330/PROGRESS.md |
| Casdoor auth 端到端交付 | docs/delivery/drools-casdoor-auth/** | 交付 | 认证方案、状态、review、QA 与最终验收证据 |
| QA 复用环境档案（**活文档**，非 dated 快照） | docs/qa/QA_PROFILE.md | QA | ✅ 已随四模块同步（启动命令/健康检查/回归数）；qa-test skill 复用 |
| 历史 QA/测试快照（dated） | docs/qa/activity-multitenant-0719/**, docs/tests/** | 归档 | 点时间记录，不重写 |
| 权益模型重构（评估→计划→裁决→进度） | docs/plans/benefit-model-refactor-0808-2218/** | 交付 | FINAL_PLAN / DECISION_RECORD(D1–D12) / REVIEW-FINDINGS / **PROGRESS.md（进度锚，新会话读这份接手）** |
| 控制台视觉设计（票券工学） | docs/plans/console-ui-coupon-mechanics-0808-2251/** | 交付 | DESIGN_SPEC / DECISION_RECORD（含 PR-0~PR-4 实施记录与两处有意偏离）/ BACKEND-GAPS（设计依赖但后端不存在的接口） |
| 历史进度（activity-marketing 移植 / 原生前端台） | IMPLEMENTATION_PROGRESS.md | 归档 | ✅ 已加"已被 F3/四模块重构取代"顶部横幅（引用的 app.js/examples.js 已删） |

## 变更类型
- 2026-07-20 重构尾巴（arch-change）：Maven 单模块 → 四模块（activity-common / drools-lab / activity-console:8081 / activity-decision:8082）+ 两独立 Spring Boot app + nginx 网关(8095) + prometheus/grafana；前端 F3 退役旧原生页；M1.4 发布代际轮询预热。
- 2026-08-09 架构重构第一批（arch-change）：后端 P0 全部 + P1-1 代际快照包 + P1-2/1-3 分层引擎（阶梯/折扣/资格移出 Drools）；
  前端 PR-0~PR-4（票券工学颜色+形制换代、viz 图表原语、TierRuler 刻度尺）。基线 后端 218 / 前端 101 / e2e 31 全绿。
  ⚠️ 本条由实施会话手写，非 /doc-sync 产出——下次跑 /doc-sync 时请核对 CLAUDE.md 与 README 是否已同步（测试数已改，架构章节未改）。
- 2026-07-22 认证交付（security/deployment）：Compose 默认 Casdoor auth；console + decision JWT 边界、8095 PKCE callback、门户自动入口、双租户 E2E、CI 与回滚手册完成。
