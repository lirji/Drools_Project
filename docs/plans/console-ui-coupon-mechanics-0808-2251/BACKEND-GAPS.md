# 设计所依赖、但后端尚不存在的接口

> 抽自设计规范 §14，按 2026-08-10 实现重新核对：**屏 5（监控看板）已有单实例聚合端点，但仍缺时序/分位/回退率契约；屏 6（发布与实验）仍整屏无接口**。
> 屏 4 已有 discount / gifts / addon 三通道验证与平铺 trace，仍没有按阶段聚合的淘汰原因、耗时瀑布或真实订单载入。资格已固定共用 `DecisionEligibilityService`，红包六形态固定由 `BenefitEvaluator` 求值；两个旧 `java-*` 属性**已于 2026-08-12 从代码里删除**——此前是「绑定但不读取」，现在是根本不绑定（去 yml 里找会一无所获）。
> 四眼发布、报价库存前后不变、无 `claim` 请求和 390/768/1440 结果态响应式验收已在 2026-08-10 的 Docker 完整 `e2e:validate` 中一次通过（pass=472 / fail=0），不再是只有静态接入。
> 原型中超出现有契约的部分仍必须先做未接入态（见文末降级约定），不得用假数据填图。

## 14. 依赖后端的清单

> 现状核对：`activity-console` 除 create / status / list / `{id}` / preview / field-dict 外，已有 bulk-status、claim，以及 discount / gifts / addon options / addon quote 验证入口；`activity-decision` 有同语义的三通道决策入口，并提供 `GET /metrics`、`GET /by-activity`。claim 与 create/status 同属 `console-write-authority` 守护的写路径（属性非空时强制 authority；默认空值仅为 demo 兼容）。指标 API 目前只读本进程 Micrometer 聚合，不等同于 Prometheus 的跨实例时序查询。

### 14.1 屏 1 工作台

| 需要 | 现状 | 契约草案 |
|---|---|---|
| 列表补充字段 | list 无 | `budgetUsed / budgetTotal`、`todayHit`、`fallbackRate`、`hitSpark: number[8]`、`grayPercent`、`version` |
| 六态 | 现只有上/下线 | `state: RUNNING\|WARMUP\|GRAY\|PENDING\|ENDED\|OFFLINE`（由后端算，不由前端从时间推） |
| 批量上下线 | ✅ `POST /activity-marketing/bulk-status` | `{items:[{activityId,version}],targetStatus}` → `{succeeded[],failed[{activityId,reason}]}`；部分失败仍返回 200 |
| 跨页选择计数 | 无 | list 响应带 `totalMatched` |
| 批量撤销 | 无 | `POST /activities/bulk-state/{opId}/undo`，服务端保留 10s 窗口 |
| 核销明细导出 | 无 | `POST /activities/export`（异步任务 + 轮询） |
| **回退哨兵** | 部分：`GET /decision/v1/metrics` 返回按 scene/reason 的累计次数 | 仍需服务端给出窗口化 `{rate, threshold, updatedAt}`；不能让 SPA 用累计 counter 自算告警率 |

### 14.2 屏 3 权益编辑器

| 需要 | 现状 | 说明 |
|---|---|---|
| 字段字典 | ✅ `/field-dict` | 需确认是否含 `unit / min / max / step / enumOptions`，TierRuler 的刻度量程依赖它 |
| 玩法参数 schema | ❌ | `GET /playbooks/{code}/schema` → 参数定义 + 校验规则 + 人话模板串（人话预览不应在前端硬编码句式） |
| 校验 | 部分 | 档位重叠/断档目前只能前端判；建议服务端 `POST /activities/validate` 返回同一套 code，前后端共用 |
| 玩法模板身份持久化 | ❌ | 当前 `playbook` 只存在于新建页 query 与起点 banner，保存后无法恢复；若未来落库，需存 `playbookId + appliedRevision`，并在运营改变活动类型/权益形态后清空，不能把已失真的模板名永久写入活动 |

### 14.3 屏 4 决策沙盘

| 需要 | 现状 | 契约草案 |
|---|---|---|
| 决策 trace | 部分：ValidateView 支持 12 个 playbook + random，按 discount / gifts / addon 展示结构化结果和平铺 `traces`；第 N 件上下文只从订单行导出 | 若要设计稿中的深度沙盘，仍需 `{ stages: [{ name, kept, dropped, reasons:[{field, expected, actual, count}] }], winner, timings }` |
| 载入真实订单 | ❌ | `GET /orders/{id}/decision-context`（脱敏） |

### 14.4 屏 5 监控看板（**已有聚合，仍缺时序契约**）

| 需要 | 当前实现 | 仍缺的契约 |
|---|---|---|
| 时序指标 | `GET /decision/v1/metrics` → `scope=single-instance` + 各 scene/mode 的 count/meanMs/maxMs 与 scene/reason 累计 fallback | `?window=1h\|24h\|7d` → `{ ts[], qps[], p50[], p95[], p99[], cacheHit[], fallbackRate[] }`；SPA 不直连 Prometheus |
| 回退原因构成 | `/metrics.fallback` 已给有限 reason 的累计次数 | 窗口内 `[{reason,count,pct}]` 与总回退率；累计值不能直接画近期占比 |
| 各活动决策明细 | `GET /decision/v1/by-activity` → `hits`，带 `tagCap=200` / `overCapTag=__over_cap__` | window/share/p95/fallbackRate/version/publishedAt |
| 发布代际标记 | 无 | 指标响应内嵌 `markers: [{ts,type:'GENERATION',text}]`，用于图上竖线标注 |
| 网关 | ✅ `/api/decision/metrics`、`/api/decision/by-activity` 同源转发到 decision | Prometheus/Grafana 仍不直暴露给 SPA；新增指标继续走后端聚合接口 |

### 14.5 屏 6 发布 + 实验（**无专用屏契约**）

| 需要 | 当前底层能力 | 仍缺的契约 |
|---|---|---|
| 版本时间线 | status 发布已有 version/actor，但无时间线查询 | `GET /activities/{id}/releases` → `[{version, publishedAt, publisher, summary, approvals:[{user,at,result}], rolledBackFrom?}]` |
| 四眼审批 | `activity.marketing.four-eyes-enabled=true` 时，现有 status 发布强制“提交人 ≠ 发布人”；Docker E2E 已实跑通过自审拒绝 + 异人发布。**状态码 2026-08-12 由 409 改为 403**（四眼拒绝是「没有权限」不是「状态冲突」，此前的 409 是 `IllegalStateException` 实现细节泄漏；`e2e-validation.mjs` 的断言已同步） | 专用 `POST /releases/{id}/approve` / `/reject`、审批记录与屏 6 UI |
| 回滚 | **部分已有**：`POST /decision/v1/snapshot/rollback?bizLine=`（2026-08-12 新增）把决策快照切回上一代，是求值器出 bug 时的**止损**按钮——不写库、本进程单实例、下一次代际推进即被覆盖。**活动版本回滚仍无专用契约**（要退版本只能走 console 的 status 端点重新发布旧版） | `POST /releases/{id}/rollback` + 影响摘要 `GET /releases/{id}/rollback-impact` |
| 实验 | 无 | `GET /experiments/{id}` → `{buckets:[{name,pct,users,killed}], expected[], actual[]}`；`POST /experiments/{id}/kill` |

### 14.6 未接入时的统一降级约定

**绝不用假数据充数。** 屏 5 可以展示现有单实例累计值，但时序图、分位带、窗口回退率继续显示左对齐的说明卡；屏 6 在接口就绪前整屏显示说明卡：

> **决策时序尚未接入**
> 当前接口只提供本进程累计值，不代表跨实例、近 24 小时的趋势。
> 接入窗口化聚合后，这里才显示分位带与回退率量具。
> `待补契约：GET /decision/v1/metrics?window=24h`

导航项对应加一个 `--text-faint` 的「未接入」小标，而不是让运营点进去看一屏假图。

---
