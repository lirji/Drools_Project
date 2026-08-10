# Delivery Status

## Goal

把“优惠验证”扩展为覆盖当前 12 个玩法模板、随机金额权益形态和红包/买赠/加价购三条通道的无副作用验证入口，并修复会造成假命中的资格与回退语义缺口。

## State

- Phase: Phase 6 — 返工后已复验，待提交
- Status: 代码与文档就绪；**工作树尚未提交**，且有 2 条既有金额缺陷未修（见 `POST-REWORK-REVIEW.md`）
- Last updated: 2026-08-10 晚 Asia/Taipei

> 首轮交付（14:44）之后又有一批返工（14:58–15:10），当时记录的 357 tests / 472 E2E **不覆盖返工后的代码**。
> 返工后已完整复跑：Maven **360 / 3 skip / BUILD SUCCESS**、前端 **274** + typecheck + build、Docker `e2e:validate` **472/0**。

## Completed

- 读取仓库规则、交付技能和 artifact contract。
- 完成 ValidateView、前端 API/types、12 个玩法模板、红包/买赠/add-on 后端链路、测试、CI 与 Docker 编排的基线审计。
- 基线审计曾确认 8/12 基础可用；第 N 件折缺订单行、add-on 无页面入口、gift/add-on 绕过资格、engine-disabled fallback 绕过条件并算错新形态。这些是修复前的历史输入，不是当前状态。
- 明确秒杀/add-on 只做报价，不从验证页调用有副作用且非幂等的 claim。
- 完成 AC-01~11、13 场景验收矩阵、UI 状态、技术方案、测试、CI、部署与回滚设计。
- 用户于 2026-08-10 明确回复“批准该方案”，Gate A 已通过，开始连续实施。
- 完成 console 加价购验证别名：`/addon/options` 与 `/addon/quote` 复用 canonical service，quote 失败返回 409，验证链不调用库存 claim。
- 完成后端安全切片：`DecisionEligibilityService` 固定为 discount/gifts/add-on 唯一请求上下文与资格语义；六形态固定由 `BenefitEvaluator` 求值。
- 两个旧 `java-benefit-eval` / `java-eligibility-eval` false 属性改为纯配置兼容，不再切换生产求值器；engine-disabled/empty fallback 保留已解析的 STACK/PRIORITY/MAX（以及 MUTEX）合并策略。
- 完成 ValidateView 三通道切片：13 场景、订单行唯一导出、typed API、options→quote、quote 200/409 traces 和无副作文案。
- 完成 repair-gate 静态修复：840px 场景面板断点；390/768/1440 订单行/报价结果态溢出检查；阶梯/折扣边界；加价购正库存前后不变 + 无 `*/claim`；四眼自审 409 + 异人发布。
- 完成 CI/E2E 接入与运行验收：header-only 独立 Compose project、`DROOLS_FOUR_EYES_ENABLED=true`、Chromium、失败截图/日志与 always cleanup；Docker E2E 一次通过 472/0。
- `DROOLS_AUTH_ENABLED=false DROOLS_DEV_DEFAULT_ENABLED=true DROOLS_FOUR_EYES_ENABLED=true ./deploy.sh --full` 成功；Maven 全反应堆 357 tests / 3 skipped / BUILD SUCCESS。
- Chrome 人工可视验收通过，console warning/error=[]；随后用 `./deploy.sh --skip-build --core-only` 恢复默认 auth 档，六服务就绪。

## Changed Files

- `docs/delivery/promotion-validation-all-playbooks/DELIVERY_PLAN.md` - Gate A 产品/UX/技术与验收方案。
- `docs/delivery/promotion-validation-all-playbooks/DELIVERY_STATUS.md` - 当前交付状态。
- `activity-console/src/main/java/com/lrj/drools/activity/controller/ActivityMarketingController.java` - console add-on validation aliases。
- `activity-console/src/test/java/com/lrj/drools/activity/ActivityMarketingAddOnAliasTest.java` - options/quote 200/409、上下文透传与 no-claim 契约。
- `activity-console/src/test/java/com/lrj/drools/activity/ActivityAuthIntegrationTest.java` - 新 aliases 的 JWT 401 边界。
- `activity-common/src/main/java/com/lrj/drools/activity/service/DecisionEligibilityService.java` - 统一上下文和资格求值。
- `activity-common/src/main/java/com/lrj/drools/activity/service/ActivityQueryService.java` - gift 资格与六形态安全回退。
- `activity-common/src/main/java/com/lrj/drools/activity/service/AddOnPurchaseService.java` - 两阶段资格重判与 traces。
- `activity-common/src/test/java/com/lrj/drools/activity/AddOnPurchaseTest.java` - add-on 资格/协议回归。
- `activity-common/src/test/java/com/lrj/drools/activity/ActivityQuerySafetyFallbackTest.java` - fallback 六形态、资格、兼容 false 属性和 STACK/PRIORITY 策略回归。
- `activity-console/src/test/java/com/lrj/drools/activity/ActivityMarketingLegacyTest.java` - kill switch 安全语义。
- `activity-console/src/test/java/com/lrj/drools/activity/ActivityMarketingAddOnAliasTest.java` - alias 200/409、上下文透传与 no-claim。
- `activity-console/src/test/java/com/lrj/drools/activity/AddOnPurchaseWritePlaneTest.java` - 加价购写平面契约。
- `activity-console/src/test/java/com/lrj/drools/activity/ActivityAuthIntegrationTest.java` - alias JWT 与 claim `console-write-authority` 边界。
- `activity-console/src/test/java/com/lrj/drools/activity/DroolsBenefitGoldenSetTest.java` - 旧 false 配置不得切换生产求值器。
- `frontend/src/console/pages/ValidateView.vue` - 13 场景与三通道共用验证页。
- `frontend/src/console/pages/ValidateView.test.ts` - 13 项组件契约。
- `frontend/src/console/activityApi.ts` / `frontend/src/shared/types.ts` - typed validation API 与 order lines。
- `frontend/e2e/e2e-validation.mjs` - 13 场景真链路、四眼、库存/no-claim 与结果态响应式脚本。
- `.github/workflows/ci.yml` - 聚焦 validation E2E job。

## Verification Log

| Command or check | Result | Notes |
| --- | --- | --- |
| repository/rules/skill discovery | pass | no root `CODEX_PROGRESS.md`; inline AGENTS rules active |
| ValidateView and request contract audit | finding | only discount/gifts; frontend lacks `lines` and add-on |
| gift/add-on backend trace | finding (P0) | both skip eligibility despite loaded condition materials |
| fallback trace | finding (P0) | engine-disabled branches before eligibility; legacy amount only handles ratio safely |
| claim path trace | pass (scope boundary) | real inventory side effect and non-idempotent; excluded from validation action |
| test/CI/Docker audit | finding | ValidateView has 2 tests; no focused all-playbook E2E CI job |
| delivery design audit | pass | one shared page, real online competition, no trace parsing, no inventory mutation |
| Gate A approval | pass | user message: “批准该方案” |
| `ActivityMarketingAddOnAliasTest,ActivityAuthIntegrationTest` | pass | 9 tests, 0 failures/errors; console aliases + auth boundary |
| backend safety targeted regression | pass | 实施切片观察 47/47；gift/add-on eligibility 与 six-shape fallback |
| `activity-common` full tests | pass | 138 run, 0 failures/errors, 3 skipped |
| console integration/full tests | pass | targeted 117/117 and all Surefire reports 0 failures/errors |
| ValidateView + frontend full tests | pass | 13/13 component; 25 files / 270 tests |
| frontend typecheck/build | pass | vue-tsc clean; Vite 205 modules built |
| repair-gate focused static checks | pass | 最新 ValidateView 13/13、typecheck、`node --check frontend/e2e/e2e-validation.mjs`、workflow YAML 与带 CI env 的 Compose config |
| repair-gate source review | pass (static) | 409 quote traces、840px 断点、四眼环境/自审拒绝、库存/no-claim、三档结果态断言均已在代码/脚本 |
| full Docker rebuild | pass | header-only + dev-default + four-eyes；Maven common 143(3 skip) + console 197 + decision 17 = 357，BUILD SUCCESS |
| Docker `e2e:validate` after repair | pass | 472/0；13 场景、四眼、库存/no-claim、quote traces 与三档结果态均通过 |
| Chrome manual acceptance | pass | 可视验收通过，console warning/error=[] |
| restore default auth profile | pass | `./deploy.sh --skip-build --core-only`；六服务就绪 |

## Decisions And Deviations

- 保留一张共用页，不拆 12 页。
- 采用“12 个玩法模板 + 随机金额形态”的 13 场景验收口径。
- 场景选择只准备上下文，不过滤真实候选；结果显示实际赢家，避免假验证。
- 加价购在 console 增加验证别名，canonical decision endpoints 不变。
- 生产资格固定 `DecisionEligibilityService`，六形态固定 `BenefitEvaluator`；两个旧 `java-*` false 属性只保留配置兼容。
- 安全 fallback 保留已解析合并策略，不得统一退化成 MAX。
- claim 属于 `console-write-authority` 写权限面；验证页永不调它。

## Blockers And Residual Risks

- 无已知实现/运行 blocker；交付门已关闭。
- 四眼发布、13 场景边界、秒杀/加价购库存前后不变、无 `claim` 与 390/768/1440 结果态已在 Docker E2E 472/0 中实跑通过。
- `claim` 仍不幂等；`console-write-authority` 默认空只是 demo 兼容，生产/auth 环境需显式配置。
- 当前工作树包含用户已有未提交改动，不回退或覆盖无关修改。

## Next Action

1. 读 `POST-REWORK-REVIEW.md`，决定两条既有金额缺陷（阶梯未落档的 0 元候选在 PRIORITY 下挤掉真优惠；第 N 件折跨 SPU 超发）是本批一起修还是单独立项——它们都会改变发放金额，需要金标集配套更新。
2. 决定 `frontend/src/console/logic.ts` 的「阶梯 + 底价」往返丢字段是否本批修（属本批新引入的共享 helper）。
3. 上述决定后再提交工作树（当前 48 改 + 10 新，一行未提交）。
4. 本机编排目前停在 **header 档**（`DROOLS_AUTH_ENABLED=false` + dev-default + four-eyes）。要回默认 auth 档：`./deploy.sh --skip-build --core-only`。
