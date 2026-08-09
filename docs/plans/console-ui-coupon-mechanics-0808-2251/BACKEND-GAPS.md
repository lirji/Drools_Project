# 设计所依赖、但后端尚不存在的接口

> 抽自设计规范 §14。**屏 5（监控看板）与屏 6（发布与实验）整屏无接口**，
> 屏 4（决策沙盘）只有一个 `/activity-marketing/preview` 试算入口、出不了分级 trace。
> 这三屏在原型里按「指标已就绪」画，实施时必须排在后端之后，或先做未接入态（见文末降级约定）。

## 14. 依赖后端的清单

> 现状核对：`activity-console` 只有 create / status / list / `{id}` / spu-discount / gifts / preview / field-dict；`activity-decision` 只有两个 POST 决策入口。指标只以 Micrometer 形式落在 actuator/prometheus（Prometheus :9090 / Grafana :3001，SPA 既拿不到也不该直连）。

### 14.1 屏 1 工作台

| 需要 | 现状 | 契约草案 |
|---|---|---|
| 列表补充字段 | list 无 | `budgetUsed / budgetTotal`、`todayHit`、`fallbackRate`、`hitSpark: number[8]`、`grayPercent`、`version` |
| 六态 | 现只有上/下线 | `state: RUNNING\|WARMUP\|GRAY\|PENDING\|ENDED\|OFFLINE`（由后端算，不由前端从时间推） |
| 批量上下线 | 无 | `POST /activities/bulk-state` `{ids[]\|filter, target}` → `{succeeded[], failed[{id,reason}]}` |
| 跨页选择计数 | 无 | list 响应带 `totalMatched` |
| 批量撤销 | 无 | `POST /activities/bulk-state/{opId}/undo`，服务端保留 10s 窗口 |
| 核销明细导出 | 无 | `POST /activities/export`（异步任务 + 轮询） |
| **回退哨兵** | 无 | `GET /decision/v1/fallback-rate` → `{rate, threshold, updatedAt}` —— **单个标量，这是最便宜的一个接口，但价值最高，建议第一个做** |

### 14.2 屏 3 权益编辑器

| 需要 | 现状 | 说明 |
|---|---|---|
| 字段字典 | ✅ `/field-dict` | 需确认是否含 `unit / min / max / step / enumOptions`，TierRuler 的刻度量程依赖它 |
| 玩法参数 schema | ❌ | `GET /playbooks/{code}/schema` → 参数定义 + 校验规则 + 人话模板串（人话预览不应在前端硬编码句式） |
| 校验 | 部分 | 档位重叠/断档目前只能前端判；建议服务端 `POST /activities/validate` 返回同一套 code，前后端共用 |

### 14.3 屏 4 决策沙盘

| 需要 | 现状 | 契约草案 |
|---|---|---|
| 决策 trace | ❌ `preview` 只返回最终结果（ValidateView 因此只能打印原始 JSON） | `POST /preview?trace=true` → `{ stages: [{ name, kept, dropped, reasons:[{field, expected, actual, count}] }], winner, timings }` |
| 载入真实订单 | ❌ | `GET /orders/{id}/decision-context`（脱敏） |

### 14.4 屏 5 监控看板（**整屏无接口**）

| 需要 | 契约草案 |
|---|---|
| 时序指标 | `GET /decision/v1/metrics?window=1h\|24h\|7d` → `{ ts[], qps[], p50[], p95[], p99[], cacheHit[], fallbackRate[] }`（服务端聚合，SPA 不直连 Prometheus） |
| 回退原因构成 | `GET /decision/v1/fallback-reasons?window=` → `[{reason, count, pct}]` |
| 各活动决策明细 | `GET /decision/v1/by-activity?window=` → `[{activityId, hit, share, p95, fallbackRate, version, publishedAt}]` |
| 发布代际标记 | 指标响应内嵌 `markers: [{ts, type:'GENERATION', text}]`，用于图上竖线标注 |
| 网关 | nginx 需把上述路径挂到 `/ui` 同源下（当前 Prometheus/Grafana 未挂） |

### 14.5 屏 6 发布 + 实验（**整屏无接口**）

| 需要 | 契约草案 |
|---|---|
| 版本时间线 | `GET /activities/{id}/releases` → `[{version, publishedAt, publisher, summary, approvals:[{user,at,result}], rolledBackFrom?}]` |
| 四眼审批 | `POST /releases/{id}/approve` / `/reject`，服务端强制"提交人 ≠ 审批人" |
| 回滚 | `POST /releases/{id}/rollback` + 影响摘要 `GET /releases/{id}/rollback-impact` |
| 实验 | `GET /experiments/{id}` → `{buckets:[{name,pct,users,killed}], expected[], actual[]}`；`POST /experiments/{id}/kill` |

### 14.6 未接入时的统一降级约定

**绝不用假数据充数。** 屏 5 / 屏 6 在接口就绪前显示左对齐的说明卡：

> **决策指标尚未接入**
> 决策进程的耗时、命中率与回退率目前只在 Prometheus（`:9090`）中可见，控制台还没有对应的聚合接口。
> 接入后这里会显示近 24 小时的分位带与回退率量具。
> `待建接口：GET /decision/v1/metrics`

导航项对应加一个 `--text-faint` 的「未接入」小标，而不是让运营点进去看一屏假图。

---

