# Review Report

## Scope

本轮 review 覆盖全玩法优惠验证的生产求值语义、ValidateView/add-on traces、聚焦 E2E、响应式结果态、无库存副作断言、四眼发布、CI 隔离/清理和文档。review 为独立只读检查；修复由其它实施切片完成，本报告只记录已核对的状态。

## Outcome

- Static verdict: **approved after repair**
- Open Critical/High static findings: **0**
- Runtime verdict: **PASS**
- Delivery gate: **closed** — 修复后 Docker 完整 `e2e:validate` 一次通过，pass=472 / fail=0；Chrome 人工验收也已通过。

## Findings And Repairs

| ID | Severity | Finding | Static repair/evidence | Runtime state |
| --- | --- | --- | --- | --- |
| ARCH-01 | High | `java-benefit-eval=false` / `java-eligibility-eval=false` 仍可切回第二份 DRL 语义，新形态和资格会漂移 | `ActivityQueryService` 仅绑定两属性作配置兼容；生产资格固定 `DecisionEligibilityService`，六形态固定 `BenefitEvaluator`；`DroolsBenefitGoldenSetTest` 改守“false 不切路” | backend 全反应堆 + Docker E2E PASS |
| ARCH-02 | High | safe fallback 可能统一退化成 MAX，改变 STACK/PRIORITY 发钱语义 | 进入 fallback 前由 `DecisionDataLoader.resolveStrategy()` 解析策略，`BenefitEvaluator.merge()` 重算时继续传入；`ActivityQuerySafetyFallbackTest` 位于 `activity-common` 并覆盖 STACK/PRIORITY | 单测有通过记录 |
| UI-01 | High | 768px 常驻侧栏下，场景面板的双列最小宽度会导致确定性溢出 | `ValidateView.vue` 在 840px 将场景面板改单列；E2E 在 390/768/1440 结果态量 document 和关键容器 | Docker E2E + Chrome PASS |
| API-01 | High | add-on quote 409 只保留 conflict reason，丢失本次 `AddOnQuoteResponse.traces`，UI 可展示旧 options trace | `ValidateView.vue` 维护独立 quote traces，200/409 均保留；组件测试与 E2E 断言拒绝 trace | Vitest + Docker E2E PASS |
| QA-01 | Medium | 阶梯只测中档、折扣只测封顶，错误实现可假绿 | E2E 补齐 ladder 300→50 / 600→120 / 1000→220，ratio 200→40 / 400→50 | Docker E2E PASS |
| QA-02 | Medium | add-on 库存为 null 且只检查 UI 文案，误调 claim 或产生扣减仍可假绿 | fixture 改为正库存 7，options/quote/409 前后读详情断言不变；页面级监听断言无 `*/claim` 请求 | Docker E2E PASS |
| QA-03 | Medium | 响应式检查每档都重新进入 idle 页，不覆盖长 trace、行项和报价结果 | 每档宽度均构造第 N 件行项态与 add-on quote 结果态，等待 fonts/frames 后检查溢出 | Docker E2E + Chrome PASS |
| QA-04 | Medium | E2E 宣称四眼，但 Compose/CI 默认未启用 | CI 设 `DROOLS_FOUR_EYES_ENABLED=true`；E2E 先要求 AUTHOR 自审 409，再由 APPROVER 发布 | Docker E2E PASS |

## Production Invariants Confirmed

- discount / gifts / addon 只有一份请求属性映射与资格淘汰：`DecisionEligibilityService`。
- 红包六形态只有一份生产求值器：`BenefitEvaluator`；旧红包 DRL 不是回滚路径。
- engine-disabled/empty fallback 保留当前解析合并策略，不重置为 MAX。
- options/quote 只查询/报价，验证页不调 claim。claim 属于 `console-write-authority` 写权限面，并仍不幂等。

## Test Ownership

| Module | Tests |
| --- | --- |
| `activity-common` | `ActivityQuerySafetyFallbackTest`, `AddOnPurchaseTest`, `RandomAmountTest` |
| `activity-console` | `ActivityMarketingAddOnAliasTest`, `AddOnPurchaseWritePlaneTest`, `ActivityAuthIntegrationTest`, `ActivityMarketingLegacyTest`, `DecisionGoldenSetTest`, `DroolsBenefitGoldenSetTest`, `BenefitFormValidationTest` |
| `frontend` | `src/console/pages/ValidateView.test.ts`, `e2e/e2e-validation.mjs` |

## Residual Notes

本轮未发现新的确定性 CI 项目隔离、端口、auth header 档、健康等待或 always cleanup 缺陷，且 header-only + four-eyes Docker stack 已完整跑过 `e2e:validate` 472/0。剩余边界不属于本页验收：claim 仍不幂等，生产需显式配置 `console-write-authority`，加价购真实下单/占库/履约仍需交易链路承接。
