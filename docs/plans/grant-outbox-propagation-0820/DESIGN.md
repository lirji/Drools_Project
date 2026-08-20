# grant_no 跨系统传播 · Transactional Outbox 设计(drools-demo)

> 推模式:drools 发放确认/冲正时把带 grant_no 的事件写入 outbox,relay 异步推给下游(账务/渠道系统),
> 使下游能按 grant_no(=recon issue_id)与营销侧三方 join。drools 无 MQ → transactional outbox + HTTP webhook relay。
> 借鉴 drools 既有范式:ActivityIdempotencyEntity(表)、confirmGrant/releaseGrant 的 @Transactional(同事务写点)、
> ActivityLifecycleScheduler/XxlJob(relay 调度);并仿 recon 的 outbox+AlertRelayService+WebhookAlertDispatcher。
> 日期:2026-08-20。停在设计定稿,拍板后实现。

## 1. 表:activity_grant_outbox(transactional outbox)

| 列 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK | |
| grant_no | VARCHAR(64) NOT NULL | 发放流水号 = 下游 join 键(recon issue_id) |
| order_id | VARCHAR(64) NOT NULL | |
| activity_id | VARCHAR(64) NOT NULL | |
| event_type | VARCHAR(24) NOT NULL | GRANT_ISSUED / GRANT_REVERSED |
| entry_type | VARCHAR(16) NOT NULL | ISSUE / REVERSAL(与分录台账一致) |
| amount_minor | BIGINT NOT NULL | 带符号分(ISSUE +,REVERSAL −) |
| currency | VARCHAR(8) NOT NULL | |
| payload | TEXT | 事件全文 JSON(供下游消费,含上述字段 + biz_time) |
| status | VARCHAR(16) NOT NULL | PENDING / SENT / FAILED |
| attempt | INT NOT NULL DEFAULT 0 | 重试次数 |
| created_at | TIMESTAMP NOT NULL | |
| sent_at | TIMESTAMP NULL | |
| (tenant_id/created_stime/modified_stime) | | 继承 TenantScopedEntity |

唯一约束 `uk_outbox_grant_event(grant_no, event_type)`:一次发放的 ISSUED/REVERSED 事件各一条(幂等,防重复发布)。

## 2. 写点(transactional outbox — 事件与发放同事务原子)

- **confirmGrant**(现 @Transactional,追 ISSUE 分录后):**同事务**写 1 条 outbox `GRANT_ISSUED`(PENDING,amount_minor=+X)。
- **releaseGrant**(CONFIRMED→RELEASED,追 REVERSAL 后):**同事务**写 1 条 `GRANT_REVERSED`(PENDING,amount_minor=−X)。HELD→RELEASED **不写**(从未发放)。
- 原子性:发放状态 + 分录台账 + outbox 事件在同一 @Transactional 内,要么全成要么全滚——杜绝「发了钱但事件丢」。幂等由 `uk_outbox_grant_event` 兜底(同 confirm 幂等重放不重复写事件)。

## 3. relay 发布(借鉴 ActivityLifecycleScheduler + recon AlertRelay)

- **`GrantOutboxRelay`**:复用 drools 既有调度双模式(@Scheduled / xxl-job handler,与 ActivityLifecycle 同款)。poll `status=PENDING` 事件 → 经 dispatcher 发布 → 成功置 SENT / 失败 FAILED + attempt++。
- **`GrantEventDispatcher` SPI**(可插拔,同 recon AlertDispatcher/ReversalExecutor 范式):
  - 默认 `LoggingGrantEventDispatcher`(**只打日志,不真发**,dev/未配置);
  - 生产 `@Primary WebhookGrantEventDispatcher`(POST payload 到账务系统 webhook URL,仿 recon WebhookAlertDispatcher,带幂等键=grant_no+event_type、可选签名头)。
- **事务边界**:relay 逐条以 `REQUIRES_NEW` 短事务置 SENT/FAILED,外部 I/O(HTTP)**不占长事务**(仿 recon AlertRelayService.relayOnce)。
- **门控**:`activity.grant-outbox.enabled`(默认关,对既有零影响);`activity.grant-outbox.webhook-url`(空则退化 logging)。

## 4. 下游消费(本仓外集成契约)

账务/渠道系统提供 webhook endpoint 接收 `GRANT_ISSUED`/`GRANT_REVERSED`,把 `grant_no` 落到自己流水的 `issue_id` 字段,金额/渠道号按自己口径记。**这是 drools 仓外的实现**;drools 侧只保证「带 grant_no 的事件可靠发出」(至少一次,靠 outbox + 重试 + 幂等键)。

## 5. 文件清单

**新增(activity-common)**
- `persistence/ActivityGrantOutboxEntity.java`(TenantScopedEntity 子类,uk_outbox_grant_event)。
- `persistence/ActivityGrantOutboxRepository.java`(追加 + poll PENDING + 置态)。
- `spi/GrantEventDispatcher.java`(SPI)。

**新增(activity-console)**
- `service/LoggingGrantEventDispatcher.java`(默认实现)。
- `service/GrantOutboxRelay.java`(poller,@Scheduled/xxl-job)。
- (可选 `service/WebhookGrantEventDispatcher.java` + 配置类,或留作生产接线点)。

**修改**
- `service/GrantService.java`:confirmGrant/releaseGrant 同事务写 outbox(注入 OutboxRepository)。
- `config/*`:relay 调度装配 + `activity.grant-outbox.*` 配置门控。
- 迁移脚本 `deploy/*.sql`:建 activity_grant_outbox + uk(ddl-auto 建表,uk 走显式迁移,同 grant_entry)。

**测试**
- confirm 写 GRANT_ISSUED、release 写 GRANT_REVERSED、HELD→RELEASED 不写;幂等重放不重复写;relay poll→dispatch→SENT、失败→FAILED+重试;门控关时零写入。

## 6. 约束与边界(遵守 drools 架构)

- 多租户 TenantScopedEntity;发放事件只在 console 写平面产生(decision 只读)。
- console 独占 DDL(ddl-auto 建表,uk 显式迁移);幂等靠 DB uk。
- 门控默认关 → 对既有 328+27 测试零影响。
- **至少一次投递**(非精确一次):下游须按 `(grant_no, event_type)` 幂等消费(payload 带该幂等键)。
- 真正的跨系统打通仍依赖**下游账务系统实现 webhook 消费**(本仓外)。

## 7. 与拉模式的关系

现有 `GET /activity-marketing/grants?orderId=` 仍在(拉模式 fallback):outbox 事件丢失/下游未就绪时,账务侧可按 order_id 兜底拉 grant_no。推拉并存,推为主、拉兜底。
