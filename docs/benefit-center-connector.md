# 企业权益中台连接器

> 活文档。最后核对：2026-08-28；实现随提交 `08c7b6e` 落地。连接器默认不发送真实权益，必须显式完成
> SQL、binding、租户/SKU 映射和 relay 配置后才可逐租户开启。

活动平台继续负责“应发什么”的服务器端决策，`benefit-center` 负责库存、渠道履约和补发。连接器不复用 legacy `GrantEvent`，而是为活动版本保存独立 `AwardBinding`，在支付/履约触发点重新执行权威决策并生成 `AwardIntent`。

## 三种交付模式

| 模式 | 行为 | 用途 |
|---|---|---|
| `LEGACY` | 不写 AwardIntent outbox | 现网默认与回切 |
| `SHADOW` | 服务器端重算并组装/哈希，不发送 | 对拍 binding、金额和版本 |
| `CENTER` | 同事务写 `activity_award_intent_outbox`，relay 调中台公开 API | 灰度后的真实发放 |

同一活动版本的 binding 必须使用同一种模式。binding 仅允许 `DISCOUNT`/`GIFT` 到 `benefitSkuId` 的静态映射，不接收表达式、脚本、渠道号、routeId 或 tenantId；动态现金金额来自本次服务器端权威决策，不能相信调用方提交的 decision facts。

`awardBindings` 是 `POST /activity-marketing/create` 请求的版本化分量。每行字段为：

| 字段 | 约束 |
|---|---|
| `sourceKind` | `DISCOUNT` 或 `GIFT` |
| `sourceRef` | 折扣绑定的稳定引用，或买赠的 batchId |
| `benefitSkuId` | benefit-center 已登记 SKU |
| `deliveryMode` | 缺省 `LEGACY`；同版本不可混用模式 |
| `amountMode` | `FIXED` 或 `DECISION` |
| `itemTemplateJson` | 受控 JSON；现金必须有正 `amountMinor` 与三位币种，非现金不得携带金额 |

AwardIntent v1 的 item 是原子项，binding 模板的 `quantity` 当前只接受 1；一个 intent 最多 20 个原子项，
部分策略固定 `BEST_EFFORT`。需要多份权益时应保存多条稳定 `clientItemId` 的 binding，而不是让下游猜拆分规则。

## 接口与幂等

- 内部触发：`POST /activity-awards/v1/intents`，受 JWT 和 console write authority 保护。
- `sourceRequestId` 由业务触发点稳定生成；相同键与相同 payload 返回首次结果，不同 payload 冲突。
- relay 调用 `POST /openapi/v1/award-orders`，同时发送相同值的 `Idempotency-Key`。
- 事务 outbox 使用 `PENDING/FAILED/SENDING/SENT/DEAD`；多实例以 lease owner + CAS 抢占，过期 `SENDING` 可恢复，旧 worker 无法提交结果。
- 只有 ONLINE 的明确版本可以生成 intent；场景只支持 `DISCOUNT` / `GIFT`。服务端会重新调用
  `ActivityQueryService` 并只提取请求指定的 `activityId + version`，调用方不能直接提交最终发放金额。

触发请求示意（字段仍需按真实场景补齐）：

```json
{
  "activityId": "ACT-202608",
  "activityVersion": 3,
  "sourceRequestId": "pay:ORDER-1001:ACT-202608:3",
  "sourceBusinessNo": "ORDER-1001",
  "recipientRef": "USER-42",
  "scene": "DISCOUNT",
  "decisionContext": {
    "spuIdList": [1001],
    "userId": 42,
    "orderAmount": 299.00,
    "quantity": 1
  },
  "trace": {"trigger": "payment-confirmed"}
}
```

`sourceRequestId` 必须从业务事实稳定派生，不得在超时重试时换新值。相同键若生成不同 payload 会被拒绝，
这是防止“同一支付回调被重解释成另一份权益”的硬边界。

## 部署

1. 执行 [`deploy/mysql-benefit-center-connector.sql`](../deploy/mysql-benefit-center-connector.sql)。
2. 先保持 `ACTIVITY_AWARD_INTENT_RELAY_ENABLED=false`，配置活动版本 binding 并以 `SHADOW` 对拍。
3. 配置 `BENEFIT_CENTER_URL`、secret 注入的 `BENEFIT_CENTER_BEARER_TOKEN`，确认租户映射和中台 SKU 已就绪。
4. 仅对批准 tenant/version 切 `CENTER`，再开启 relay。`ACTIVITY_AWARD_INTENT_LEASE_MS` 必须大于 HTTP 最坏调用时长，默认 30 秒。

建议验收顺序：LEGACY 回归 → SHADOW 对拍 payload/hash → CENTER 但 relay 关闭检查 outbox → 测试租户开启
relay → 验证重试/租约/幂等 → 扩大租户范围。生产 bearer token 只能经 secret 注入；日志、文档和 Compose
文件都不得出现真实值。

回切只影响新的业务请求：将新版本或流量切回 `LEGACY`，已经进入中台的订单仍由中台收敛。不得清空 outbox，也不得把不确定结果重新生成为新的 `sourceRequestId`。

## 与 grant outbox 的区别

`activity_grant_outbox` 传播的是本平台已经确认/冲正的账务事件，幂等键为 `(grant_no,event_type)`；
`activity_award_intent_outbox` 发送的是要由 benefit-center 履约的中立权益意图，幂等键为
`(tenant,sourceSystem,sourceRequestId)`。两者可以并存，但不能把一个开关、webhook 或重投流程套到另一张表。
