-- grant_no 跨系统传播 · transactional outbox 升级脚本（在启动带本特性的 activity-console 之后执行一次）。
--
-- 背景：console 是唯一 DDL 执行者，ddl-auto:update 会自动**新建表** activity_grant_outbox 并加索引。
--   但**唯一约束** ddl-auto 对既有表补 uk 靠不住、必须显式迁移（同 activity_grant.uk_grant_no /
--   activity_grant_entry.uk_entry_grant_type 的处置）：
--     uk_outbox_grant_event(grant_no, event_type) —— 一次发放的 GRANT_ISSUED/GRANT_REVERSED 各至多一条
--     （事件幂等硬保证，防重复发布；confirm/release 幂等重放靠它兜底）。
--
-- 幂等/顺序：本脚本假定 console 已建好表。新库直接跑亦可（表由 ddl-auto 建出）。
--   若约束已存在（fresh DB 由 Hibernate 依 @Table.uniqueConstraints 建出），ALTER 会报 "Duplicate key name"，
--   属预期——核对后跳过该条即可，不要盲目重复执行。
--
-- 依赖：本特性需 activity_grant.grant_no 已存在（见 mysql-grant-recon-onboarding.sql）。outbox.grant_no 即那份
--   全局 join 键（= recon issue_id），下游账务/渠道系统按同一 grant_no 与营销侧三方 join。

-- mysql CLI 默认会话字符集可能不是 UTF-8；解析中文注释前显式声明，避免乱码写入元数据。
SET NAMES utf8mb4;

-- ---------------------------------------------------------------------------
-- 事件幂等键：同一 grant_no 的同一 event_type 至多一条（PENDING/SENT/FAILED 全生命周期唯一）。
--   跨租户单列组合（不含 tenant_id）：grant_no 本就全局唯一，事件也按 grant_no 全局去重。
-- ---------------------------------------------------------------------------
ALTER TABLE `activity_grant_outbox`
    ADD CONSTRAINT `uk_outbox_grant_event` UNIQUE (`grant_no`, `event_type`);

-- 表级注释（新库读实体 @Comment 即可；存量库在此补齐，与 mysql-table-comments.sql 同风格）。
ALTER TABLE `activity_grant_outbox`
    COMMENT = '活动发放跨系统传播 outbox（transactional outbox，GRANT_ISSUED/GRANT_REVERSED）';

-- ---------------------------------------------------------------------------
-- KI-9 退避重试 + 死信：next_attempt_at 列由 ddl-auto 自动加（可空）。中继 findRetryable 按
--   (status='FAILED' and attempt<maxAttempt and next_attempt_at<=now) 捞可补投条目——加 (status, next_attempt_at)
--   复合索引优化退避扫描（现有 idx_outbox_status(status,id) 覆盖 PENDING 首投，此索引补 FAILED 退避补投路径）。
--   达 maxAttempt 落 DEAD 死信、退出自动补投但绝不静默丢弃，靠应用层 redriveDeadLetters 复活（下游恢复后触发）。
--   已存在则报 Duplicate key name，属预期跳过。
-- ---------------------------------------------------------------------------
CREATE INDEX `idx_outbox_status_next` ON `activity_grant_outbox` (`status`, `next_attempt_at`);
