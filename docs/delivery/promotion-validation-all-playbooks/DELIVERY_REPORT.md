# Delivery Report

## Delivered Outcome

控制台“优惠验证”已从两通道试算扩展为一张共用页，覆盖 12 个玩法模板 + random 形态，并支持 discount / gifts / addon 三通道。第 N 件折由订单行唯一导出汇总；加价购走 options → 权威 quote 两阶段；秒杀和加价购验证都不占库存。

后端同时完成生产语义收敛：

- discount / gifts / addon 固定共用 `DecisionEligibilityService` 的请求上下文与 fail-closed 资格淘汰。
- 固定、随机、阶梯、折扣、一口价、第 N 件折六形态固定由 `BenefitEvaluator` 求值。
- `java-benefit-eval` / `java-eligibility-eval` 即使为 false 也只做旧配置兼容，不切换生产主路径。
- engine-disabled/empty fallback 保留已解析的 STACK/PRIORITY/MAX（及 MUTEX）合并策略，不退化为固定 MAX。

## Main Changes

- ValidateView 提供 13 场景、类型化三通道结果、quote 200/409 traces、订单行编辑和无库存副作提示。
- console 新增 `/activity-marketing/addon/options` 与 `/addon/quote` 验证别名，沿用租户/JWT 边界和 canonical `AddOnPurchaseService`。
- gift/add-on 先跑共享资格；安全 fallback 不会把被淘汰候选放回或改变权益形态/合并策略。
- add-on 写平面已放行并校验活动类型 6；选项必须非空、品名唯一且加价金额大于 0。
- E2E 通过真实 UI/API 创建并四眼发布独立租户活动，覆盖边界、库存/no-claim、390/768/1440 结果态与失效 quote。
- CI 使用独立 Compose project，header-only + four-eyes 环境，失败上传截图/输出日志，always 清理 stack/volumes。
- claim 继续是唯一权威库存扣减，属于 `console-write-authority` 写权限面；验证页不调它。

## Runtime Verification

```bash
DROOLS_AUTH_ENABLED=false \
DROOLS_DEV_DEFAULT_ENABLED=true \
DROOLS_FOUR_EYES_ENABLED=true \
./deploy.sh --full

BASE=http://localhost:8095 npm --prefix frontend run e2e:validate
```

Observed result（**首轮 QA，2026-08-10 14:44 前**）：

- Maven full reactor: common 143（3 skipped）+ console 197 + decision 17 = **357 tests / 3 skipped / BUILD SUCCESS**。
- Docker images: console `3f1a40f4bc06`，decision `e766b7c89af1`，frontend/gateway `2e3052880f27`。
- Focused browser E2E: **pass=472 / fail=0**。
- Chrome manual acceptance: **PASS**，console warning/error 为 `[]`。

> ⚠️ **上面这轮证据不覆盖最终代码。** 首轮 QA 之后（14:58–15:10）又改了一批：删除已退役的 DRL 求值面
> （`evalEligibility` / `evalDiscount` / `evalLadder` / `buildDiscountDrl`）、`ActivityQueryService` 重写安全回退、
> 写权限矩阵补 `bulk-status` 与 `*/claim`、新增 CI `validation-e2e` job、以及 ValidateView / EditorView / DetailView
> 与 `e2e-validation.mjs` 的调整。**在此之前的 357 / 472 结论对返工后的代码不成立。**

Re-verification（**返工后复跑，2026-08-10 晚**）：

- Maven full reactor: common 143（3 skipped）+ console **200** + decision 17 = **360 tests / 3 skipped / 0 failures / BUILD SUCCESS**。
  （console 由 197→200 是 `ActivityAuthIntegrationTest` 新增 claim / bulk-status 写权限边界用例；
  与 357 的差异**只**来自这 3 个新用例，不存在「用例神秘消失」。用例数以 `Tests run:` 汇总为准，
  求和 surefire XML 会少数 50 个，见 `CLAUDE.md` 坑 14。）
- Frontend: **25 文件 / 270 用例通过**，`vue-tsc` 无错，生产构建成功。
- Docker header 档整栈重建后 `e2e:validate`: **pass=472 / fail=0**。

## Operate

默认 auth 档启动：

```bash
./deploy.sh --skip-build --core-only
```

重跑聚焦验收：

```bash
DROOLS_AUTH_ENABLED=false DROOLS_DEV_DEFAULT_ENABLED=true DROOLS_FOUR_EYES_ENABLED=true ./deploy.sh --full
BASE=http://localhost:8095 npm --prefix frontend run e2e:validate
./deploy.sh --skip-build --core-only
```

## Quality Gate

- Backend: 全反应堆 **360 tests**，0 failures/errors，3 skipped（返工后复跑）。
- Frontend: 25 files / 270 component tests、typecheck 和 production build 均通过（返工后复跑）。
- Runtime: 返工后整栈重建 `e2e:validate` **472/0**。
- Side effects: flash/add-on 库存前后不变，验证流程无 claim。
- Review: 首轮的 2 High + 4 Medium 已修复。**返工批（14:58–15:10）之后另做过一轮多维对抗 review**，
  结论见本目录 `POST-REWORK-REVIEW.md`——其中两条属于**既有**（非本批引入）的金额缺陷，尚未修复，
  合并前请先读那份报告，不要把本文件当成"无开放问题"的凭据。

## Production Boundary

本交付已完成 localhost/Docker 验收，但不包含交易提交、支付、加价购库存履约、每人限领或 claim 幂等流水。生产必须使用真实 IdP/凭据、显式配置 `activity.tenant.auth.console-write-authority`，并将“报价成功”与“库存/订单提交成功”继续严格分开。
