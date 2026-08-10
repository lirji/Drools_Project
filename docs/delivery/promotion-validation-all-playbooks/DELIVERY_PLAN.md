# 全玩法优惠验证 Delivery Plan

> 实施注记（2026-08-10）：本文的 Repository Evidence 记录的是 Gate A 之前的基线，不是当前缺口清单。当前代码、单测、E2E 脚本与 CI 已实现；修复后 Docker 完整 `e2e:validate` 已一次通过 pass=472 / fail=0，Chrome 人工验收也已通过。最新证据以同目录的 `DELIVERY_STATUS.md` / `QA_REPORT.md` 为准。

## Requirement

扩展控制台“优惠验证”菜单，使它能安全、诚实地验证当前 12 个玩法模板，并覆盖没有独立模板卡的随机金额权益形态。验证页必须覆盖红包算价、买赠查询、加价购两阶段报价和第 N 件折订单行输入；秒杀与加价购只做无副作用报价，不在验证页扣库存。

## Repository Evidence（Gate A 基线）

- 规划当时 `frontend/src/console/pages/ValidateView.vue` 只有 `discount | gifts` 两种模式，请求没有订单行，也没有加价购入口。
- `frontend/src/shared/types.ts` 的 `SpuDiscountRequest` 漏掉后端已有的 `lines`，因此“第二件半价”在页面上必定 fail-closed。
- `frontend/src/console/playbooks.ts` 定义 12 个可创建玩法；随机金额已经是编辑器的一等权益形态，但没有独立玩法卡。
- `ActivityQueryService.buyAndGetGiftsInternal()` 加载了条件物料却没有执行资格判定；候选默认 `eligible=true`，会让“满 500 赠品”在未满门槛时假命中。
- `AddOnPurchaseService.options()/quote()` 同样没有执行资格判定，金额、数量、标签、门店和地域条件当前都不会挡住换购选项。
- `rule-engine.enabled=false` 在资格判定前直接进入 `legacyMax()`，既绕过资格，又会把一口价、第 N 件折、随机和阶梯按错误金额语义降级。
- 加价购两阶段服务已经存在；console 应用也能装配该公共服务。验证入口可增加 console explain/validation 别名，与现有 `/spu-discount`、`/gifts` 保持同一调试路径。
- 秒杀 `claim` 会真实扣库存且当前不幂等，不能接到普通验证动作；加价购 quote 也不代表占库。
- 当前工作树已有用户未提交改动；实施只触碰本交付范围文件，不回退或覆盖既有变更。

## Feasibility

- Verdict: **go，需先修后端资格与回退语义，再扩页面**
- Constraints:
  - 保留一张共用验证页，不按玩法拆页。
  - 验证对象是当前租户、当前时间、已绑定 SPU 的全部 ONLINE 活动的真实竞争结果；玩法选择只准备上下文，不绕过竞争或强制某活动命中。
  - 不在验证页执行 `claim`，不修改库存，不制造交易成功假象。
  - 不解析中文 trace 生成伪结构；展示原始 trace，关键状态使用类型化响应字段。
- Dependencies:
  - 现有 `DecisionDataLoader`、`ConditionTreeEvaluator`、`BenefitEvaluator`、加价购两阶段服务、玩法目录和 Docker 编排。
- Risks and mitigations:
  - 买赠/加价购条件绕过导致超发假象：先抽取并复用统一资格判定，正反例锁死。
  - 订单行与汇总金额/数量打架：明细模式下由行项导出 SPU、金额和数量，汇总字段只读。
  - 秒杀验证误扣库存：页面和接口都不调用 claim，并以醒目文案说明“仅报价、未占库存”。
  - 引擎关闭或空决策改变金额：安全回退统一复用 Java 资格和六形态算额，不再回退到原始字段猜金额。
  - 多个线上活动竞争造成场景与实际赢家不同：结果始终显示真实活动 ID、名称和合并策略；场景区明确不是活动过滤器。

## Product Design

- Actors and goals:
  - 运营人员：选择一个玩法场景，填入真实订单上下文，确认线上活动是否命中以及金额/赠品/换购报价是否正确。
  - 开发与 QA：用同一页复现资格边界、六种红包形态和两阶段加价购协议。
- Scope:
  - 12 个玩法模板：无门槛立减、满 X 减 Y、阶梯满减、满 N 件立减、折扣券、人群定向、门店定向、地域定向、满额赠品、第二件半价、限时秒杀一口价、加价购。
  - 额外覆盖随机金额权益形态，形成“12 个玩法 + 6 种红包权益形态 + 3 条决策通道”的验收口径。
  - 公共资格判定、全形态安全回退、订单行、类型化响应、加价购 options → quote、页面状态、测试、文档、CI 和 Docker QA。
- Out of scope:
  - 在验证页创建/发布/下线活动。
  - 绕过真实竞争的“指定活动强制命中”。
  - 秒杀或加价购的库存 claim、订单提交、支付和履约。
  - PR-7 完整决策沙盘、流量回放、批量压测和生产数据导入。
- Business rules:
  - 候选必须已绑定请求 SPU、为当前最高 ONLINE 服务版本且在实时生效窗内。
  - 有资格条件但条件树不可解析时 fail-closed；无条件活动恒通过。
  - 买赠和加价购必须与红包使用同一份资格语义。
  - 明细订单存在时，`spuIdList/orderAmount/quantity` 从有效行项唯一导出；不能同时接受互相矛盾的汇总值。
  - SPU 和数量为有限正整数，单价为有限正数；残缺行项不得发请求。
  - 一口价结果展示原价、减免和应付；第 N 件折必须带订单行；随机金额同一上下文可重放且落在配置区间。
  - 加价购第二阶段只提交 activityId + item，价格由服务端重新读取；过期或伪造选项返回 409。

## Acceptance Criteria

| ID | Observable behavior | Priority | Verification |
| --- | --- | --- | --- |
| AC-01 | 验证页提供红包、买赠、加价购三条通道，并能选择 12 个玩法场景和随机金额形态；场景会切换所需字段并填入示例上下文 | P0 | Vue component tests + browser E2E |
| AC-02 | 第 N 件折可录入多行 `spuId/unitPrice/quantity`；明细模式由行项导出 SPU、订单金额和数量，非法/残缺行项不发请求 | P0 | serializer/unit/component tests |
| AC-03 | 固定金额、随机、阶梯、折扣、一口价、第 N 件折六种红包形态都能得到正确命中/不命中、减免额与应付金额 | P0 | parameterized backend tests + UI tests |
| AC-04 | 买赠先执行资格条件；500 元命中、499 元不返回赠品，回退路径也不能汇总不合格候选 | P0 | backend integration tests + E2E |
| AC-05 | 加价购先执行资格条件，第一阶段列出适用选项，第二阶段按服务端权威价格报价；不适用时无选项，伪造/失效选项返回 409 | P0 | service/controller/component/E2E tests |
| AC-06 | 金额、数量、用户标签、门店、地域和 SPU 绑定条件的正反例均与红包资格语义一致 | P0 | playbook acceptance matrix |
| AC-07 | `rule-engine.enabled=false` 或空决策时仍执行共享资格、复用六形态 Java 算额，并保留已解析的 STACK/PRIORITY/MAX 策略；两个旧 `java-*` false 属性不得切换生产路径 | P0 | fallback/config-compat regression tests |
| AC-08 | 秒杀页面只展示一口价试算与“未扣库存”；加价购 quote 标明“未占库存”；重复验证不改变活动库存 | P0 | API inventory assertion + component/E2E copy check |
| AC-09 | 空结果、命中、资格不满足、loading、请求取消、网络错误、add-on 409 均有明确状态；切换场景清旧结果但保留可复用上下文 | P1 | Vue component tests |
| AC-10 | 390px、768px 与桌面宽度无横向溢出，模式/场景/行项/报价控件可键盘操作并有可读标签 | P1 | responsive browser checks + markup review |
| AC-11 | Maven、Vitest、typecheck、build、聚焦全玩法 E2E 和 Docker 部署均通过；文档与 testid 契约同步 | P1 | local parity + CI workflow |

### Playbook Acceptance Matrix

| 场景 | 正向 | 反向或边界 |
| --- | --- | --- |
| 无门槛立减 | 绑定 SPU 命中并精确减 10 | 未绑定 SPU 不命中 |
| 满 X 减 Y | 200 元减 20 | 199.99 不命中 |
| 阶梯满减 | 300→50、600→120、1000→220 | 299.99 不命中，档位边界正确 |
| 满 N 件立减 | 数量 2 减 15 | 数量 1 不命中 |
| 折扣券 | 200 元 8 折减 40 | 大额订单封顶减 50 |
| 随机金额 | 金额位于 `[min,max]` | 同上下文重复请求金额一致 |
| 人群定向 | 含目标标签命中 | 缺标签不命中 |
| 门店定向 | `storeId=1` 命中 | 其它门店/空值不命中 |
| 地域定向 | `310000` 命中 | 其它地域不命中 |
| 满额赠品 | 500 元返回配置赠品 | 499 元返回空列表 |
| 第二件半价 | `100 元 × 2` 减 50 | 只有 1 件或缺行项不命中 |
| 秒杀一口价 | 100 元订单、9.9 一口价，减 90.10、应付 9.90 | 订单金额 ≤ 9.9 不适用；库存不变 |
| 加价购 | options 返回 9.9 选项，选择后 quote 仍为 9.9 | 条件不满足无选项；伪造/失效项 409 |

## UI/UX Design

- Applicability: 改造现有 `ValidateView`，沿用控制台组件、色彩和响应式体系，不新增页面。
- Flow and component map:
  - 顶部“玩法场景”选择器由 `PLAYBOOKS` 派生，并补一个随机金额验证场景。
  - 场景映射到 `discount | gifts | addon` 三种能力通道；页面只保留一个上下文表单和一个随通道变化的主动作。
  - 普通场景使用汇总订单；第二件半价自动进入订单行模式，可增删行，并显示只读导出金额/数量。
  - 红包结果展示真实命中活动、减免、应付、策略、决策模式和 trace。
  - 买赠结果展示赠品清单；加价购先展示 options，用户选择后再请求 quote。
  - 秒杀与加价购结果区固定展示“仅报价，未扣/未占库存”。
- State matrix:
  - idle；invalid；loading；discount hit/miss；gift list/empty；addon options/empty；quote loading/success/stale-409；safe-fallback warning；network error；aborted stale request。
- Responsive and accessibility behavior:
  - 桌面双栏，≤1100px 主体单栏，≤840px 场景面板/行项改为可容纳 768px 常驻侧栏的垂直排列，≤700px 再收紧字段。
  - 模式/场景按钮使用 `aria-pressed` 或原生 select，loading 使用 `aria-busy`，错误使用 `role=alert`；动态 options/quote 使用 live region。

## Technical Solution

- Chosen approach:
  - 抽取共享候选资格求值组件，接收统一 `ActivityRuleContext + Materials`，由红包、买赠、加价购复用；保留现有条件 schema 与 fail-closed 语义。
  - 把请求到 `ActivityRuleContext` 的映射保留为单一来源；加价购不另写一份字段映射。
  - 红包在引擎关闭或空决策回退时改用 `BenefitEvaluator` 完成阶梯、六形态算额和合并，并保留 `DecisionDataLoader.resolveStrategy()` 已解析的 STACK/PRIORITY/MAX（以及 MUTEX）；兼容保留响应 mode/metrics，但不再用原始金额字段猜语义。
  - `java-benefit-eval` / `java-eligibility-eval` 只保留为旧配置兼容；false 不切回红包 DRL。资格固定共用 `DecisionEligibilityService`，六形态固定共用 `BenefitEvaluator`。
  - 买赠先资格淘汰，gift DRL 与 Java fallback 都只处理 eligible candidates；加价购 options/quote 每阶段都重新加载并重新判定资格。
  - 在 console 验证控制器增加 add-on options/quote 别名，让三条通道共享现有 console 鉴权、租户和 explain/DB 调试路径；canonical decision 端点保持不变。
  - 前端为 discount/gift/add-on 定义类型化请求与响应；增加 `lines`，停止在 ValidateView 使用无约束 `Record<string, unknown>`。
  - 玩法场景配置由现有 `PLAYBOOKS` 派生，额外的随机形态仅存在于验证场景目录，不改变 12 张运营模板的历史口径。
- Alternatives rejected:
  - 拆成 12 个验证页：复制上下文、请求和结果逻辑，重新引入 URL 固化形态的问题。
  - 验证页直接调用 claim：有真实库存副作用且当前不幂等。
  - 只加前端按钮：会保留 gift/add-on 条件绕过，产生假通过。
  - 用 trace 中文字符串判断阶段/形态：文案一改即失效，不是稳定契约。
  - 给请求加“强制 targetActivityId”并过滤候选：不再模拟真实线上竞争，容易把本应输给其它活动的配置判成通过。
- Modules and file map:
  - `activity-common/src/main/java/com/lrj/drools/activity/service/DecisionEligibilityService.java` — 共享请求上下文与资格求值。
  - `ActivityQueryService.java` — discount/gift 安全路径与回退。
  - `AddOnPurchaseService.java` — options/quote 资格闸门。
  - `ActivityMarketingController.java` — console add-on validation endpoints。
  - `activity-common/.../ActivityQuerySafetyFallbackTest.java` 与 `AddOnPurchaseTest.java`；`activity-console/.../ActivityMarketingAddOnAliasTest.java`、`AddOnPurchaseWritePlaneTest.java`、`ActivityAuthIntegrationTest.java` — 资格、六形态/策略 fallback、controller 409/no-claim 与写权限。
  - `frontend/src/shared/types.ts`、`console/activityApi.ts` — lines 与类型化三通道 API。
  - `frontend/src/console/pages/ValidateView.vue` 及测试 — 场景、行项、三通道结果。
  - `frontend/e2e/e2e-validation.mjs`、`package.json`、testid contract — 聚焦浏览器验收。
  - `.github/workflows/ci.yml` — 聚焦 validation E2E job。
- Contracts and data:
  - `SpuDiscountRequest.lines[] = {spuId, unitPrice, quantity}`；无明细时为 null/空，明细模式由行项生成汇总字段。
  - Discount/Gift 保持现有 JSON 字段兼容；前端增加显式 TS 类型。AddOn options/quote 沿用现有服务端协议。
  - 不新增表、不迁移数据；写平面放行已建模的加价购类型并增加必要校验，其余创建字段保持兼容。
- Security and reliability:
  - 所有入口沿用现有 JWT/header tenant 边界；客户端价格不参与 quote。`claim` 与 create/status 同属 `console-write-authority` 守护的写路径（配置非空时强制 authority），验证页不调用它。
  - 有约束但条件树不可用时 fail-closed；两阶段之间配置失效返回 409。
  - AbortController + request sequence 防止慢响应覆盖新场景。
- Observability:
  - 保留 discount/gift decision/fallback metrics；补充 add-on/gift 资格 trace，错误不记录 token 或用户敏感值。
- Compatibility and migration:
  - 现有 `/activity-marketing/spu-discount`、`/gifts` 和 `/decision/v1/*` 契约保持可用；新增字段和 console aliases 为向后兼容扩展。
  - 旧 ValidateView testid 在可行处保留，并同步正式契约；新增 testid 使用稳定语义命名。

## Implementation Sequence

1. 后端安全切片：共享资格求值，接入 gift/add-on，修正 engine-disabled/empty fallback；AC-04/05/06/07。
2. 契约切片：订单行、类型化响应、console add-on endpoints 与 controller 状态码；AC-02/03/05/08。
3. UI 切片：玩法场景、三通道、订单行唯一来源、结果与无副作用提示；AC-01/02/03/08/09/10。
4. 回归切片：逐玩法后端矩阵、ValidateView 组件矩阵、聚焦浏览器 E2E；AC-01~11。
5. 审查与交付切片：独立 review/repair、文档与 CI、完整构建、Docker 重建和运行态 QA；AC-11。

## Verification Plan

| AC/Risk | Test level | Case or command | Required evidence |
| --- | --- | --- | --- |
| AC-03/04/05/06/07 | Java unit/integration | targeted Maven tests + full `./mvnw --batch-mode package` | 13 场景正反例、fallback parity、0 failures |
| AC-01/02/05/08/09 | Vue component | `npm test -- ValidateView` + full `npm test` | 请求体、状态切换、options→quote、错误、无 claim |
| AC-02/10/11 | static/build | `npm run typecheck && npm run build` | TS 与 production bundle 成功 |
| AC-01~10 | browser E2E | `BASE=http://localhost:8095 npm run e2e:validate` | 13 场景、三 viewport、库存前后相同 |
| AC-11 | deployment | `./deploy.sh --full` + compose ps/health/HTTP | console/decision/gateway 新镜像健康，验证页 200 |
| regressions | review | diff review + targeted reruns | 无 Critical/High 未解决项 |

## Documentation Plan

- 更新 `README.md` 的优惠验证能力、E2E 命令和无副作用边界。
- 更新 `docs/activity-marketing.md` 的 gift/add-on 资格、回退语义和三通道验证说明。
- 更新 `docs/qa/QA_PROFILE.md`、现有 backend gap/decision record 的当前状态，不篡改历史裁决。
- 补齐 `frontend/e2e/data-testid-contract.md` 的 ValidateView 选择器。
- 持续维护本目录 status/review/QA/delivery artifacts。

## CI Plan

- 现有 backend/frontend jobs 自动承接新增单测、typecheck 和 build。
- 新增聚焦 `validation-e2e` job：依赖前两 job，使用 header-only tenant 档启动 MySQL、console、decision、gateway，只运行 `e2e:validate`；失败上传/打印核心服务日志，结束时始终清理 Compose。
- 不把其它全部浏览器脚本塞进该 job，避免无关时长与波动。

## Rollout And Rollback

1. 先合入后端资格/回退与测试，再启用新前端入口，避免 UI 暴露不可信结果。
2. 完整构建并以 Docker 重建 console、decision、gateway；在 header QA 档跑聚焦 E2E，再恢复用户当前 Casdoor auth 档。
3. 回滚前端可恢复旧 ValidateView；后端新增 aliases 可保留。若后端资格修复需回滚，必须整体回滚对应服务与测试，不能只关引擎开关绕过资格。
4. 无数据库迁移，回滚不涉及数据恢复。

## Assumptions And Open Decisions

- Assumption A1: “所有玩法”按当前 12 个 `PLAYBOOKS` 加随机金额形态解释，不扩展到尚未建模的生产交易玩法。
- Assumption A2: 验证的目标是当前线上综合决策，而非脱离竞争的单活动单元测试；因此不增加强制候选过滤。
- Assumption A3: 秒杀和加价购的“验证完成”指报价/资格协议完成，库存提交明确不在本页执行。
- No unresolved business decision blocks implementation after this plan is approved.

## Approval

- Status: **approved**
- Approved scope: 本方案定义的后端资格/安全回退、三通道共用验证页、13 场景验收、测试、CI、文档与 Docker QA
- Evidence: user message on 2026-08-10 — “批准该方案”
