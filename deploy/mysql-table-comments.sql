-- 为已经由 Hibernate 创建的存量 MySQL 表补充表注释。
-- 新建数据库会直接读取实体上的 @Comment；存量库可在 activity-console 完成建表后执行本脚本一次。

-- 不依赖 mysql CLI 或图形化客户端的默认编码，确保中文按 UTF-8 解析并写入 utf8mb4 元数据。
SET NAMES utf8mb4;

ALTER TABLE `activity_artifact` COMMENT = '活动决策规则制品表';
ALTER TABLE `activity_condition` COMMENT = '活动资格条件表';
ALTER TABLE `activity_generation` COMMENT = '活动发布代际表';
ALTER TABLE `activity_gift` COMMENT = '活动赠品配置表';
ALTER TABLE `activity_grant` COMMENT = '活动权益发放流水表';
ALTER TABLE `activity_idempotency` COMMENT = '活动请求幂等记录表';
ALTER TABLE `activity_manage` COMMENT = '活动基础信息及版本表';
ALTER TABLE `activity_pool_ref` COMMENT = '活动与商品池关联表';
ALTER TABLE `activity_product_pool` COMMENT = '活动商品池表';
ALTER TABLE `activity_product_pool_rule` COMMENT = '商品池圈选规则表';
ALTER TABLE `activity_rule` COMMENT = '活动权益规则配置表';
ALTER TABLE `activity_spu_binding` COMMENT = '活动商品绑定表';
ALTER TABLE `activity_strategy` COMMENT = '多活动权益合并策略表';
ALTER TABLE `campaign` COMMENT = 'Drools 规则能力活动配置表';
ALTER TABLE `catalog_product` COMMENT = '商品目录表';
ALTER TABLE `catalog_store` COMMENT = '店铺目录表';
ALTER TABLE `session_snapshot` COMMENT = 'Drools 会话状态快照表';
ALTER TABLE `sys_district` COMMENT = '中国行政区划字典表';
