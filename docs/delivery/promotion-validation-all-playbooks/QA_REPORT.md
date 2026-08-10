# QA Report

## Environment

- Date: 2026-08-10 Asia/Taipei
- Runtime entry: `http://localhost:8095`
- Validation profile: header-only tenant + dev default + four-eyes enabled
- Deploy command: `DROOLS_AUTH_ENABLED=false DROOLS_DEV_DEFAULT_ENABLED=true DROOLS_FOUR_EYES_ENABLED=true ./deploy.sh --full`
- Browser command: `BASE=http://localhost:8095 npm --prefix frontend run e2e:validate`
- Post-QA state: `./deploy.sh --skip-build --core-only` 已恢复默认 auth 档，六个服务就绪。

## Outcome

> ⚠️ 本报告记录的是 **2026-08-10 14:44 前的首轮 QA**。之后 14:58–15:10 又有一批返工，
> 下列数字**不覆盖最终代码**。返工后的复跑证据见 `DELIVERY_REPORT.md` 的 Re-verification 段
> （Maven **360 / 3 skip**、前端 274 + typecheck + build、`e2e:validate` **472/0**）。

- Verdict: **PASS**（仅对首轮那版代码成立）
- Automated browser result: **pass=472 / fail=0**
- Full Maven reactor: **BUILD SUCCESS — 357 tests, 3 skipped, 0 failures/errors**
  （返工后为 360；差异只来自 `ActivityAuthIntegrationTest` 新增的 3 个写权限边界用例）
- Manual Chrome acceptance: **PASS**，console `warning/error=[]`

## Acceptance Results

| AC | Result | Evidence |
| --- | --- | --- |
| AC-01 13 场景/三通道 | PASS | E2E 校验 12 个 `PLAYBOOKS` + random 同序，每项至少一个正向；discount/gifts/addon 都走真实 UI/API |
| AC-02 第 N 件行项唯一导出 | PASS | E2E 断言 `lines` 及由其导出的 SPU/金额/数量；ValidateView 组件测试 13/13 |
| AC-03 六形态 | PASS | 固定、随机、阶梯、折扣、一口价、第 N 件折正反/边界；阶梯 300/600/1000 与折扣未封顶/封顶均通过 |
| AC-04 买赠资格 | PASS | 499 无赠品，500 返回本轮创建的赠品；shared eligibility/fallback backend tests 通过 |
| AC-05 add-on 两阶段 | PASS | 199 无 options，200 返回选项，选中后权威 quote 200，伪造/失效 409，200/409 均保留 quote traces |
| AC-06 完整资格维度 | PASS | 金额、数量、标签、门店、地域、SPU 绑定正反例全部通过 |
| AC-07 安全 fallback/兼容属性 | PASS | `ActivityQuerySafetyFallbackTest` 覆盖六形态、STACK/PRIORITY 策略保留；`DroolsBenefitGoldenSetTest` 验证两个旧 false 属性不切生产路径 |
| AC-08 无库存副作 | PASS | flash 库存 7→7；add-on options/quote/409 前后 7→7；整页 `claimRequests.length===0` |
| AC-09 状态/错误 | PASS | 组件测试与 E2E 覆盖空结果、命中、loading、切场景清理、quote 409 及本次拒绝 trace |
| AC-10 响应式/可用性 | PASS | 390/768/1440 分别构造第 N 件行项态和 add-on 报价结果态，document 与关键容器均无溢出；Chrome 人工验收通过 |
| AC-11 构建/部署/CI 对齐 | PASS | Maven 全反应堆 357/3 skip；`./deploy.sh --full` 成功；新镜像启动并完成 472 项 E2E 断言 |

## Automated Results

| Suite/check | Result |
| --- | --- |
| `./mvnw` full reactor（由 `./deploy.sh --full` 触发） | PASS — common 143（3 skip）+ console 197 + decision 17 = 357；BUILD SUCCESS |
| backend focused safety/alias/auth suites | PASS — 实施阶段观察 47/47，alias/auth 9/9，console 相关 Surefire 0 failures/errors |
| frontend component suite | PASS — 25 files / 270 tests；ValidateView 13/13 |
| frontend typecheck + production build | PASS — vue-tsc clean，Vite 205 modules built |
| focused browser E2E | PASS — 472/0 |
| workflow/YAML/Compose static checks | PASS |
| Chrome manual acceptance | PASS — warning/error=[] |

## Runtime Artifacts

| Image | ID |
| --- | --- |
| console | `3f1a40f4bc06` |
| decision | `e766b7c89af1` |
| frontend/gateway | `2e3052880f27` |

## Security And Side-effect Checks

- four-eyes 环境已真实启用；AUTHOR 自审发布被 409 拒绝，APPROVER 发布成功。
- 验证页全流程没有请求 `*/claim`；秒杀与 add-on 库存均前后不变。
- claim 仍是写平面的权威扣减且不幂等；auth 部署应显式配置 `activity.tenant.auth.console-write-authority`。

## Conclusion

**PASS / localhost delivery complete.** 全玩法验证的自动化、Docker 运行与人工 Chrome 门均已通过；验收后已恢复默认 auth 档并确认六服务就绪。生产仍需配置实际 IdP/凭据、`console-write-authority` 和 claim 幂等/履约方案。
