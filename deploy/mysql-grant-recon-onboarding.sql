-- 营销发放对账地基 · 存量库升级脚本（在启动带本特性的 activity-console 之后执行一次）。
--
-- 背景：console 是唯一 DDL 执行者，ddl-auto:update 会自动**加列**（activity_grant.grant_no /
--   activity_grant.currency / activity_manage.currency）与**新建表** activity_grant_entry。
--   但两类对象 ddl-auto 靠不住、必须显式迁移：
--     1) 对**既有表**补唯一约束（Hibernate update 不保证补 uk）——uk_grant_no；
--     2) **视图**（ddl-auto 从不创建）——recon_src_marketing，供 recon 营销三方 SEG1 营销侧读。
--   另做一次性存量 currency 回填（ddl-auto 加的是可空列，存量行为 NULL）。
--
-- 幂等/顺序：本脚本假定 console 已建好表与列。新库直接跑亦可（表/列已由 ddl-auto 建出）。
-- 若某约束已存在（如 fresh DB 由 Hibernate 依 @Table.uniqueConstraints 建出），对应 ALTER 会报
--   "Duplicate key name"，属预期——核对后跳过该条即可，不要盲目重复执行。

-- mysql CLI 默认会话字符集可能不是 UTF-8；解析中文注释前显式声明，避免乱码写入元数据。
SET NAMES utf8mb4;

-- ---------------------------------------------------------------------------
-- 1) 发放主记录：发放号全局唯一（跨租户单列 uk，不含 tenant_id）。
--    grant_no 是分录台账与 recon 的 issue_id/match_key，账务/渠道侧按同一 grant_no join，必须全局不撞。
--    唯一性功能上由 claim 生成的 UUID 保证，这条约束是最后兜底。
-- ---------------------------------------------------------------------------
ALTER TABLE `activity_grant`
    ADD CONSTRAINT `uk_grant_no` UNIQUE (`grant_no`);

-- ---------------------------------------------------------------------------
-- 2) 分录台账：一次发放最多一条 ISSUE + 一条 REVERSAL（分录幂等硬保证）。
--    fresh DB 由 Hibernate 依实体 @Table.uniqueConstraints 建出，本条通常已存在可跳过；
--    存量库若手工先建过表则在此补齐。
-- ---------------------------------------------------------------------------
ALTER TABLE `activity_grant_entry`
    ADD CONSTRAINT `uk_entry_grant_type` UNIQUE (`grant_no`, `entry_type`);

-- 表级注释（新库读实体 @Comment 即可；存量库在此补齐，与 mysql-table-comments.sql 同风格）。
ALTER TABLE `activity_grant_entry` COMMENT = '活动权益发放分录台账（不可变红蓝字，ISSUE/REVERSAL）';

-- ---------------------------------------------------------------------------
-- 3) 存量币种回填：ddl-auto 加的 currency 是可空列，存量 activity_manage 行为 NULL。
--    继承出的 grant.currency 也会为空 → recon 按币种分桶异常。一次性兜底 CNY。
--    （新写入与 claim 已在应用层双兜底，本条只处理存量。）
-- ---------------------------------------------------------------------------
UPDATE `activity_manage` SET `currency` = 'CNY' WHERE `currency` IS NULL;

-- ---------------------------------------------------------------------------
-- 3b) 存量发放号回填：ddl-auto 加的 grant_no 是可空列，本特性上线前的 HELD/CONFIRMED 发放行为 NULL。
--    confirm 迟到回调会把 NULL grant_no 传给 activity_grant_entry（grant_no NOT NULL），触发
--    DataIntegrityViolation→500 且该发放永远无法确认。claim 已对新行落 UUID，本条只兜底存量。
--    UUID() 逐行取值互不相同，满足 uk_grant_no（跨租户单列唯一）；只补仍可被确认的非 RELEASED 行。
-- ---------------------------------------------------------------------------
UPDATE `activity_grant` SET `grant_no` = UUID() WHERE `grant_no` IS NULL AND `state` <> 'RELEASED';

-- ---------------------------------------------------------------------------
-- 4) recon 营销三方 SEG1 营销侧只读视图——列别名严格对齐 recon MarketingThreeWayScenario
--    的 marketingLikeDescriptor：
--      id / grant_no AS issue_id / order_id AS order_no / currency AS ccy /
--      amount_minor / entry_type / biz_status / biz_time / posting_time。
--    单租户口径：**不切 tenant**（用户 2026-08-19 拍板）。id/grant_no 本就全局唯一。
--    对账纳入判据 = 从分录表出：HELD 占用从不产生分录，天然不进；
--    退款后 ISSUE(+X)+REVERSAL(−X) 两行 → recon 按 grant_no 分组守恒对平。
-- ---------------------------------------------------------------------------
CREATE OR REPLACE VIEW `recon_src_marketing` AS
    SELECT
        `id`,
        `grant_no`     AS `issue_id`,
        `order_id`     AS `order_no`,
        `currency`     AS `ccy`,
        `amount_minor`,
        `entry_type`,
        'POSTED'       AS `biz_status`,
        `biz_time`,
        `biz_time`     AS `posting_time`
    FROM `activity_grant_entry`;
