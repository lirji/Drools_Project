-- M2.2 · decision 只读账号。MySQL 首次初始化时执行（挂到 /docker-entrypoint-initdb.d）。
-- 坐实「单库双账号」：decision 连此账号，物理上只能 SELECT——建表/写入都被 MySQL 拒，
-- 无论应用层 ddl-auto 怎么配都碰不了 DDL（比"靠 ddl-auto=validate 自觉"更硬）。console 仍用 root 读写 + 独占 DDL。
CREATE USER IF NOT EXISTS 'decision_ro'@'%' IDENTIFIED BY 'decision_ro_pass';
-- 库级 SELECT 授权：涵盖 console 之后建的所有 activity_* 表（无需逐表授权）。
GRANT SELECT ON activity_platform.* TO 'decision_ro'@'%';
FLUSH PRIVILEGES;
