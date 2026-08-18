-- 存量库升级脚本：在启动新版 activity-console 之前执行一次。
-- 新库无需执行；Hibernate 会直接创建 catalog_product / catalog_store。
-- 若目标表已经存在，请先核对数据，禁止直接覆盖或删除。

-- mysql CLI 的默认会话字符集可能不是 UTF-8；必须在解析中文注释前显式声明，
-- 否则 UTF-8 源文件会被按 latin1 解码并以乱码形式永久写入元数据。
SET NAMES utf8mb4;

RENAME TABLE `demo_product` TO `catalog_product`,
             `demo_store` TO `catalog_store`;

ALTER TABLE `catalog_product` COMMENT = '商品目录表';
ALTER TABLE `catalog_store` COMMENT = '店铺目录表';
