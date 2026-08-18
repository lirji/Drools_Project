# XXL-JOB 3.4.2 调度中心最小化初始化脚本。
# 基于官方 doc/db/tables_xxl_job.sql，移除了全部 Sample/Demo 任务，只保留活动生命周期任务。

CREATE DATABASE IF NOT EXISTS `xxl_job`
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

CREATE USER IF NOT EXISTS 'xxl_job'@'%' IDENTIFIED BY 'xxl_job_pass';
ALTER USER 'xxl_job'@'%' IDENTIFIED BY 'xxl_job_pass';
REVOKE ALL PRIVILEGES, GRANT OPTION FROM 'xxl_job'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON `xxl_job`.* TO 'xxl_job'@'%';

USE `xxl_job`;
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `xxl_job_group`
(
    `id`           int          NOT NULL AUTO_INCREMENT,
    `app_name`     varchar(64)  NOT NULL COMMENT '执行器 AppName',
    `title`        varchar(64)  NOT NULL COMMENT '执行器名称',
    `address_type` tinyint      NOT NULL DEFAULT 0 COMMENT '执行器地址类型：0=自动注册、1=手动录入',
    `address_list` text                  COMMENT '执行器地址列表，多地址逗号分隔',
    `update_time`  datetime              DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (`id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'XXL-JOB 执行器分组';

CREATE TABLE IF NOT EXISTS `xxl_job_registry`
(
    `id`             bigint       NOT NULL AUTO_INCREMENT,
    `registry_group` varchar(50)   NOT NULL COMMENT '注册类型',
    `registry_key`   varchar(255)  NOT NULL COMMENT '注册键',
    `registry_value` varchar(255)  NOT NULL COMMENT '注册地址',
    `update_time`    datetime               DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `i_g_k_v` (`registry_group`, `registry_key`, `registry_value`) USING BTREE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'XXL-JOB 执行器注册表';

CREATE TABLE IF NOT EXISTS `xxl_job_info`
(
    `id`                        int          NOT NULL AUTO_INCREMENT,
    `job_group`                 int          NOT NULL COMMENT '执行器分组主键',
    `job_desc`                  varchar(255) NOT NULL COMMENT '任务描述',
    `add_time`                  datetime              DEFAULT NULL COMMENT '创建时间',
    `update_time`               datetime              DEFAULT NULL COMMENT '更新时间',
    `author`                    varchar(64)           DEFAULT NULL COMMENT '负责人',
    `alarm_email`               varchar(255)          DEFAULT NULL COMMENT '告警邮箱',
    `schedule_type`             varchar(50)  NOT NULL DEFAULT 'NONE' COMMENT '调度类型',
    `schedule_conf`             varchar(128)          DEFAULT NULL COMMENT '调度配置',
    `misfire_strategy`          varchar(50)  NOT NULL DEFAULT 'DO_NOTHING' COMMENT '错过调度策略',
    `executor_route_strategy`   varchar(50)           DEFAULT NULL COMMENT '执行器路由策略',
    `executor_handler`          varchar(255)          DEFAULT NULL COMMENT '任务 Handler',
    `executor_param`            text                  DEFAULT NULL COMMENT '任务参数',
    `executor_block_strategy`   varchar(50)           DEFAULT NULL COMMENT '阻塞处理策略',
    `executor_timeout`          int          NOT NULL DEFAULT 0 COMMENT '执行超时时间（秒）',
    `executor_fail_retry_count` int          NOT NULL DEFAULT 0 COMMENT '失败重试次数',
    `glue_type`                 varchar(50)  NOT NULL COMMENT 'GLUE 类型',
    `glue_source`               mediumtext            COMMENT 'GLUE 源代码',
    `glue_remark`               varchar(128)          DEFAULT NULL COMMENT 'GLUE 备注',
    `glue_updatetime`           datetime              DEFAULT NULL COMMENT 'GLUE 更新时间',
    `child_jobid`               varchar(255)          DEFAULT NULL COMMENT '子任务 ID',
    `trigger_status`            tinyint      NOT NULL DEFAULT 0 COMMENT '调度状态：0=停止、1=运行',
    `trigger_last_time`         bigint       NOT NULL DEFAULT 0 COMMENT '上次调度时间戳',
    `trigger_next_time`         bigint       NOT NULL DEFAULT 0 COMMENT '下次调度时间戳',
    PRIMARY KEY (`id`),
    KEY `i_job_group_handler` (`job_group`, `executor_handler`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'XXL-JOB 任务定义';

CREATE TABLE IF NOT EXISTS `xxl_job_logglue`
(
    `id`              int          NOT NULL AUTO_INCREMENT,
    `job_id`          int          NOT NULL COMMENT '任务主键',
    `glue_type`       varchar(50)           DEFAULT NULL COMMENT 'GLUE 类型',
    `glue_source`     mediumtext            COMMENT 'GLUE 源代码',
    `glue_remark`     varchar(128) NOT NULL COMMENT 'GLUE 备注',
    `add_time`        datetime              DEFAULT NULL COMMENT '创建时间',
    `update_time`     datetime              DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (`id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'XXL-JOB GLUE 版本记录';

CREATE TABLE IF NOT EXISTS `xxl_job_log`
(
    `id`                        bigint       NOT NULL AUTO_INCREMENT,
    `job_group`                 int          NOT NULL COMMENT '执行器分组主键',
    `job_id`                    int          NOT NULL COMMENT '任务主键',
    `executor_address`          varchar(255)          DEFAULT NULL COMMENT '本次执行器地址',
    `executor_handler`          varchar(255)          DEFAULT NULL COMMENT '任务 Handler',
    `executor_param`            text                  DEFAULT NULL COMMENT '任务参数',
    `executor_sharding_param`   varchar(20)           DEFAULT NULL COMMENT '分片参数',
    `executor_fail_retry_count` int          NOT NULL DEFAULT 0 COMMENT '失败重试次数',
    `trigger_time`              datetime              DEFAULT NULL COMMENT '触发时间',
    `trigger_code`              int          NOT NULL COMMENT '触发结果码',
    `trigger_msg`               text                  COMMENT '触发日志',
    `handle_time`               datetime              DEFAULT NULL COMMENT '执行时间',
    `handle_code`               int          NOT NULL COMMENT '执行结果码',
    `handle_msg`                text                  COMMENT '执行日志',
    `alarm_status`              tinyint      NOT NULL DEFAULT 0 COMMENT '告警状态',
    PRIMARY KEY (`id`),
    KEY `I_trigger_time` (`trigger_time`),
    KEY `I_handle_code` (`handle_code`),
    KEY `I_jobgroup` (`job_group`),
    KEY `I_jobid` (`job_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'XXL-JOB 执行日志';

CREATE TABLE IF NOT EXISTS `xxl_job_log_report`
(
    `id`            int      NOT NULL AUTO_INCREMENT,
    `trigger_day`   datetime          DEFAULT NULL COMMENT '统计日期',
    `running_count` int      NOT NULL DEFAULT 0 COMMENT '运行中数量',
    `suc_count`     int      NOT NULL DEFAULT 0 COMMENT '成功数量',
    `fail_count`    int      NOT NULL DEFAULT 0 COMMENT '失败数量',
    `update_time`   datetime          DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `i_trigger_day` (`trigger_day`) USING BTREE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'XXL-JOB 日志统计';

CREATE TABLE IF NOT EXISTS `xxl_job_lock`
(
    `lock_name` varchar(50) NOT NULL COMMENT '锁名称',
    PRIMARY KEY (`lock_name`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'XXL-JOB 调度锁';

CREATE TABLE IF NOT EXISTS `xxl_job_user`
(
    `id`         int          NOT NULL AUTO_INCREMENT,
    `username`   varchar(50)  NOT NULL COMMENT '用户名',
    `password`   varchar(100) NOT NULL COMMENT 'SHA-256 密码摘要',
    `token`      varchar(100)          DEFAULT NULL COMMENT '登录 Token',
    `role`       tinyint      NOT NULL COMMENT '角色：0=普通用户、1=管理员',
    `permission` varchar(255)          DEFAULT NULL COMMENT '允许访问的执行器分组',
    PRIMARY KEY (`id`),
    UNIQUE KEY `i_username` (`username`) USING BTREE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'XXL-JOB 用户';

INSERT IGNORE INTO `xxl_job_user` (`username`, `password`, `role`, `permission`)
VALUES ('admin', '0e8f16790cab686c5452e41d7cd7c36db21652ef6b1e02a5cc0d440a5b9d9133', 1, NULL);

INSERT IGNORE INTO `xxl_job_lock` (`lock_name`) VALUES ('schedule_lock');

INSERT INTO `xxl_job_group` (`app_name`, `title`, `address_type`, `address_list`, `update_time`)
SELECT 'activity-console-executor', '活动平台任务执行器', 0, NULL, NOW()
 WHERE NOT EXISTS (
     SELECT 1 FROM `xxl_job_group` WHERE `app_name` = 'activity-console-executor'
 );

SET @activity_job_group_id = (
    SELECT `id` FROM `xxl_job_group`
     WHERE `app_name` = 'activity-console-executor'
     ORDER BY `id` ASC LIMIT 1
);

INSERT INTO `xxl_job_info`
    (`job_group`, `job_desc`, `add_time`, `update_time`, `author`, `alarm_email`,
     `schedule_type`, `schedule_conf`, `misfire_strategy`, `executor_route_strategy`,
     `executor_handler`, `executor_param`, `executor_block_strategy`, `executor_timeout`,
     `executor_fail_retry_count`, `glue_type`, `glue_source`, `glue_remark`, `glue_updatetime`,
     `child_jobid`, `trigger_status`, `trigger_last_time`, `trigger_next_time`)
SELECT @activity_job_group_id, '活动生命周期定时上下线', NOW(), NOW(), 'activity-platform', '',
       'CRON', '0/5 * * * * ? *', 'FIRE_ONCE_NOW', 'FAILOVER',
       'activityLifecycleSweep', '', 'DISCARD_LATER', 120,
       2, 'BEAN', '', '活动生命周期批量扫描', NOW(),
       '', 1, 0, UNIX_TIMESTAMP(NOW() + INTERVAL 5 SECOND) * 1000
 WHERE NOT EXISTS (
     SELECT 1 FROM `xxl_job_info`
      WHERE `job_group` = @activity_job_group_id
        AND `executor_handler` = 'activityLifecycleSweep'
 );

COMMIT;
