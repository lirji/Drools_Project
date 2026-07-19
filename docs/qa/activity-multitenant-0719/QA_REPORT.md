# QA 报告 · 活动营销多租户（Track B）· 2026-07-19

黑盒功能测试（QA 视角，真实发请求）。被测：dev/header 档租户隔离主链路 + auth 档安全护栏。
环境：dev 档 `:8097`（h2 mem, auth 关, dev-default 开）+ auth 档 `:8099`（auth 开, dev-default 关）。commit：Track B 评审修复轮后。

## 用例 × 结果总表

| 编号 | 用例 | 优先级 | 结果 |
|---|---|---|---|
| TC01 | field-dict 返回 200 + 6 个默认字段 | P1 | ✅ PASS |
| TC02 | acme 创建红包活动 → 200 + activityId | P0 | ✅ PASS |
| TC03a | acme 列表含自己的活动 | P0 | ✅ PASS |
| TC03b | **beta 列表不含 acme 的活动（隔离）** | P0 | ✅ PASS |
| TC03c | acme 列表仍含（切租户不串） | P0 | ✅ PASS |
| TC04a | acme 上线活动 → 200 | P1 | ✅ PASS |
| TC04b | acme spu-discount 命中 | P0 | ✅ PASS |
| TC04c | **beta spu-discount 同 SPU 不命中（隔离）** | P0 | ✅ PASS |
| TC05a | acme 看自己详情 → 200 | P1 | ✅ PASS |
| TC05b | **beta 看 acme 详情 → 400（越权 fail-closed）** | P0 | ✅ PASS |
| TC06a | 非法字符 X-Tenant-Id → 400 | P1 | ✅ PASS |
| TC06b | **保留哨兵 `__no_tenant__` → 400** | P0 | ✅ PASS |
| TC06c | 无 header + dev-default → 200（回落 __dev__） | P2 | ✅ PASS |
| TC07 | 缺 activityName 创建 → 400 | P2 | ✅ PASS |
| TC08 | auth 档：health/`/hello`/静态首页 → 200 | P0 | ✅ PASS（见 BUG-1 修复后） |
| TC09 | auth 档：`/activity-marketing/*` 无 token → 401 | P0 | ✅ PASS |

**API 用例 15/15 通过（+ auth 档 2 项探活）。**

## Bug 单

### BUG-1（P0，已在 QA 中发现并修复）· auth 档非活动端点全 403
- **现象**：`activity.tenant.auth.enabled=true` 时，`GET /actuator/health`、`POST /hello`（Step1）、静态首页 `/` 全部 **403**。
- **复现**：起 auth 档 app（:8099），`curl /actuator/health` → 403。
- **预期**：这些非活动端点应放行（200）。
- **根因**：评审修复轮把 `JwtTenantFilter` 改成"解析不出租户 → 403 fail-closed"，但该过滤器挂在**覆盖全部请求**的安全链上，导致 health/其它 Step/静态页也被 403。
- **修复**：安全链拆两条——链一 `.securityMatcher("/activity-marketing/**")`（JWT + `JwtTenantFilter` 只挂这条），链二兜底 permitAll。修复后 TC08 全 200、TC09 仍 401。`./mvnw test` 55 绿。

## 未执行用例（不静默丢）
- **交互式 UI 用例（Playwright）未执行**：本环境未连 Playwright MCP（`mcp__playwright__browser_*` 不可用）。
  改为**结构性验证**：前端资源 `/`、`/assets/activity.js`、`/assets/activity.css` 均 200；`activity.js` 含 `tenantBar()` 与 4 处 `X-Tenant-Id`；`activity.css` 含 `.tenant-bar`。
  **建议**：接入 Playwright MCP 后补跑「切租户条 → acme 建活动 → 切 beta 看不到 → 切回 acme 又出现」的真点用例。
- **真 Casdoor token 端到端**：需 IdP secret 铸 token（auto-mode 拦），归用户 `!` 跑 `casdoor-m2m-verify.sh`。

## 结论
dev/header 档多租户隔离主链路 **15/15 通过**，无残留 bug；QA 过程发现并修复了 1 个 P0 auth 档回归（BUG-1）。唯一未覆盖面是交互式 UI（缺 Playwright）与真 Casdoor 端到端（用户授权项），均已如实登记。
