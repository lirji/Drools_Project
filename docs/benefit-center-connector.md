# 企业权益中台连接器

活动平台继续负责“应发什么”的服务器端决策，`benefit-center` 负责库存、渠道履约和补发。连接器不复用 legacy `GrantEvent`，而是为活动版本保存独立 `AwardBinding`，在支付/履约触发点重新执行权威决策并生成 `AwardIntent`。

## 三种交付模式

| 模式 | 行为 | 用途 |
|---|---|---|
| `LEGACY` | 不写 AwardIntent outbox | 现网默认与回切 |
| `SHADOW` | 服务器端重算并组装/哈希，不发送 | 对拍 binding、金额和版本 |
| `CENTER` | 同事务写 `activity_award_intent_outbox`，relay 调中台公开 API | 灰度后的真实发放 |

同一活动版本的 binding 必须使用同一种模式。binding 仅允许 `DISCOUNT`/`GIFT` 到 `benefitSkuId` 的静态映射，不接收表达式、脚本、渠道号、routeId 或 tenantId；动态现金金额来自本次服务器端权威决策，不能相信调用方提交的 decision facts。

## 接口与幂等

- 内部触发：`POST /activity-awards/v1/intents`，受 JWT 和 console write authority 保护。
- `sourceRequestId` 由业务触发点稳定生成；相同键与相同 payload 返回首次结果，不同 payload 冲突。
- relay 调用 `POST /openapi/v1/award-orders`，同时发送相同值的 `Idempotency-Key`。
- 事务 outbox 使用 `PENDING/FAILED/SENDING/SENT/DEAD`；多实例以 lease owner + CAS 抢占，过期 `SENDING` 可恢复，旧 worker 无法提交结果。

## 部署

1. 执行 [`deploy/mysql-benefit-center-connector.sql`](../deploy/mysql-benefit-center-connector.sql)。
2. 先保持 `ACTIVITY_AWARD_INTENT_RELAY_ENABLED=false`，配置活动版本 binding 并以 `SHADOW` 对拍。
3. 配置 `BENEFIT_CENTER_URL`、secret 注入的 `BENEFIT_CENTER_BEARER_TOKEN`，确认租户映射和中台 SKU 已就绪。
4. 仅对批准 tenant/version 切 `CENTER`，再开启 relay。`ACTIVITY_AWARD_INTENT_LEASE_MS` 必须大于 HTTP 最坏调用时长，默认 30 秒。

回切只影响新的业务请求：将新版本或流量切回 `LEGACY`，已经进入中台的订单仍由中台收敛。不得清空 outbox，也不得把不确定结果重新生成为新的 `sourceRequestId`。
