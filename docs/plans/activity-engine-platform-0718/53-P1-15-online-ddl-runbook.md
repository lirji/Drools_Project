# P1-15 · 热表 online-DDL 迁移 + 回滚锚点 runbook

> Track B 生产尾项。**为什么是脚本+runbook 而非 demo 代码**：demo 用 Hibernate `ddl-auto`（进程内自动建表），无流量、无锁表问题；生产热表在 M2 后有流量，`ALTER` 会锁表/复制，须用**影子表在线迁移**。本文给可复用的真 DDL + gh-ost/pt-osc 命令 + 回滚锚点，落到本项目真实表。执行需真库 + 迁移工具，不在 demo 内跑。

## 0. 何时需要

demo 已把多租户维度从 Day1 带进（`@TenantId tenant_id` 列 + 热点索引以 `tenant_id` 打头，见 `ActivityManageEntity`/`ActivityArtifactEntity`），**新表无需改造**。P1-15 针对的是：**遗留热表 PK 仍是单租户维度**（如某 pointer 表 PK=`biz_line`），多租户后需升为 `(tenant_id, biz_line)`——这是锁表 DDL，有流量时不可直接 `ALTER`。

## 1. 真 DDL（示例：pointer 表 PK 升维）

```sql
-- 目标：把 release_pointer 的 PK 从 (biz_line) 升为 (tenant_id, biz_line)，并补租户打头的复合索引。
-- 直接 ALTER 在 InnoDB 上会 copy 全表 + 持元数据锁 → 生产禁止裸跑。
ALTER TABLE release_pointer
  DROP PRIMARY KEY,
  ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT '__single__' AFTER id,   -- 存量行回填占位租户
  ADD PRIMARY KEY (tenant_id, biz_line),
  ADD INDEX idx_rp_tenant_biz (tenant_id, biz_line);
```

> 本项目 `activity_manage` / `activity_artifact` / `activity_idempotency` 已是 `(tenant_id, …)` 复合唯一约束 + `tenant_id` 打头索引（无需此改造）；此 DDL 是遗留表升维的模板。

## 2. 在线执行（二选一，避免锁表）

### 2a. gh-ost（推荐，无触发器）
```bash
gh-ost \
  --host=$DB_HOST --port=$DB_PORT --database=$DB_NAME --table=release_pointer \
  --user=$MIG_USER --password=$MIG_PW \
  --alter="DROP PRIMARY KEY, ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT '__single__' AFTER id, ADD PRIMARY KEY (tenant_id, biz_line), ADD INDEX idx_rp_tenant_biz (tenant_id, biz_line)" \
  --initially-drop-ghost-table --initially-drop-old-table \
  --max-load=Threads_running=25 --critical-load=Threads_running=100 \
  --chunk-size=1000 --max-lag-millis=1500 \
  --cut-over=default --exact-rowcount \
  --postpone-cut-over-flag-file=/tmp/ghost.postpone \
  --execute
# 影子表增量回放追平后，rm /tmp/ghost.postpone 触发原子 rename 切换（毫秒级元数据锁）。
```

### 2b. pt-online-schema-change（触发器方案）
```bash
pt-online-schema-change \
  --alter="DROP PRIMARY KEY, ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT '__single__', ADD PRIMARY KEY (tenant_id, biz_line), ADD INDEX idx_rp_tenant_biz (tenant_id, biz_line)" \
  --max-load Threads_running=25 --critical-load Threads_running=100 \
  --chunk-size=1000 --max-lag=1.5 \
  --no-drop-old-table \
  D=$DB_NAME,t=release_pointer,u=$MIG_USER,p=$MIG_PW --execute
```

**要点**：低峰执行；`max-load`/`critical-load` 触发自动暂停/中止；`--max-lag` 保护从库复制延迟；PK 变更类 DDL 前务必确认 `tenant_id` 有默认值（存量行回填 `__single__` 占位，与 `TenantIds` 保留值一致，后续按业务回填真租户）。

## 3. 回滚锚点

迁移前建立锚点，失败可回旧结构：
1. **schema 版本 tag**：迁移前 `SHOW CREATE TABLE release_pointer` 存档 + 打 tag `pre-p1-15-<date>`。
2. **数据快照点**：低峰对该表 `mysqldump --single-transaction release_pointer > snapshot.sql`（或依赖备份系统的 PITR 时间点）。
3. **保留旧表**：gh-ost `--initially-drop-old-table` 关掉（改为迁移后手动确认再删），pt-osc `--no-drop-old-table`——切换后旧表保留一段观察期，异常可 rename 换回。
4. **灰度校验**：切换前用 gh-ost 的 `--test-on-replica` 或先在从库跑，比对影子表 checksum；应用层先**只读双跑**校验新旧一致，再放开写。
5. **回滚动作**：切换后如发现问题，在观察期内 `RENAME TABLE release_pointer TO release_pointer_bad, _release_pointer_old TO release_pointer`（毫秒级），恢复旧结构。

## 4. 接缝已就位（本项目）
- 所有缓存 / registry / 查询 key 从 Day1 带 tenant（单租户时占位常量 `__single__`/`__dev__`）——PK 升维时**应用层无 key 缺 tenant 的返工**（评审 P1-15 最高危点已规避）。
- `@TenantId` 判别式多租户在 ORM 层自动加 `tenant_id` 谓词，PK 升维是纯存储层优化（查询谓词已带 tenant），**不改业务代码**。
- 回滚有路径（§3），Track B 有明确回滚锚点。

## 5. 落地边界
真库在线迁移需生产 DB + gh-ost/pt-osc + 低峰窗口 + DBA 值守，不在 demo 内演示。本文脚本可复用、步骤可照做；demo 的 `ddl-auto` 已用 `@TenantId` + 复合索引把新表建对，无遗留升维负担。
