# 平台命名升级

> 迁移记录。最后核对：2026-08-28。平台/数据库/目录表命名仍有效；当时短暂存在的规则能力中心 UI
> 后续已随旧 Demo catalog 删除，当前前端路由以 [`../docs/frontend.md`](../docs/frontend.md) 为准。

本次升级把项目默认标识统一为“活动规则平台”，并将内置商品、店铺模型升级为正式目录模型。

## 前端入口

该次升级曾把规则能力中心从 `/ui/demos` 改为 `/ui/capabilities`；随后产品化重构删除了整套教学 Demo
catalog 页面。现在访问二者都会由 SPA catch-all 回首页。Step 1–24 的后端接口仍保留，调用方式见 README 与
`docs/steps-guide.md`，不要为 `/ui/capabilities` 新建书签或自动化。

## MySQL 存量库

新版本默认数据库名为 `activity_platform`。已有部署可以二选一：

1. 暂时继续使用原数据库：启动时显式设置 `DB_NAME` 为原库名，不搬迁数据。
2. 新建 `activity_platform`，按现有数据库运维流程完整迁移数据与账号授权，再切换 `DB_NAME`。

切换应用版本前，在实际使用的数据库中执行一次 [mysql-catalog-rename.sql](mysql-catalog-rename.sql)，把两张目录表原位重命名。脚本不会删除或覆盖数据；如果目标表已经存在会直接失败，此时应先人工核对两边数据。

表重命名完成后，可执行 [mysql-table-comments.sql](mysql-table-comments.sql) 统一刷新全部表注释。两个脚本都会先执行
`SET NAMES utf8mb4`，避免 mysql CLI 或图形化客户端沿用 latin1 等默认会话字符集，把中文注释二次编码成乱码。

## H2 本地文件

console 的默认 H2 文件由 `data/drools-demo.mv.db` 改为 `data/activity-platform.mv.db`。需要保留本地数据时，请在应用停止后移动同目录下成组的 H2 文件；不需要历史数据时让应用创建新文件即可。

## 可选目录初始化

`activity.marketing.seed-catalog-data` 默认关闭。正式环境应由商品与门店主数据同步链路写入目录；只有本地开发或验收环境需要内置目录时才显式开启。

## 历史归档

`docs/plans/`、`docs/delivery/`、`docs/tests/` 与根目录的历史进度记录保留当时的类名、表名和路径，作为决策与迁移证据，不代表当前运行配置。当前用法以 README、架构文档、部署文档和本指南为准。
