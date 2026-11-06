# 系统 MySQL 8 迁移记录

迁移日期：2026-10-04（Asia/Shanghai）。

## 数据来源与目标

- 来源：`D:/ciyuantown-tools/backend-p3-error.zip` 中的完整 `mysql-data`，数据库为 `ciyuantown`。该目录包含 P10 评论功能的 `comment_media` 和 `comment_reaction`，共 32 张表；未使用压缩包内较旧的 before-p10 SQL 备份。
- 目标：系统安装的 MySQL Community Server 8.0.46，Windows 服务 `MySQL80`，地址 `127.0.0.1:3306`，数据库 `ciyuantown`。
- 应用账号：`ciyuantown_app@localhost`，沿用原密码及 SELECT、INSERT、UPDATE、DELETE 权限。系统其他数据库未改动。
- 在独立目录解压并启动只读临时实例，进行逻辑导出和导入。迁移完成后已正常关闭临时 3307 实例。

## 项目配置

后端 `application.yml` 默认连接、项目 `.env` 的 `DB_URL`、IDEA 当前运行配置和数据源均已改为 3306。`scripts/start-mysql8.ps1` 使用系统 `MySQL80` 服务。后端启动脚本使用本机 `D:/JDK/jdk21` 和 `D:/apache-maven-3.9.16/bin/mvn.cmd`；原 DPAPI 凭据文件恢复至 `D:/ciyuantown-tools/credentials.xml`。

在后端目录运行 `scripts/dev.ps1`，或者在 `ciyuantoen-back` 目录运行 `start-backend.ps1`。后者为后台启动，日志写入 `backend-run.log`。后端已通过该脚本重启并监听 8080。

## 验证结果

- 全部 32 张表的行数及 `CHECKSUM TABLE ... EXTENDED` 结果与恢复出的来源一致。
- 列类型、默认值、字符集、排序规则、索引、主外键及约束一致；规范化 SHOW CREATE TABLE 中冗余字符集声明后的结构导出一致。
- 管理员 2 条、用户 5 条、帖子 12 条、评论 14 条。
- 原应用账号成功连接目标数据库，后端连接池在系统 MySQL 的进程列表中可见。
- `/app/home/config`、`/app/post/list`、`/app/notice/categories`、`/app/comment/config` 返回成功码 `0000`。
- 修改的 PowerShell 脚本均通过语法解析，Spring Boot 经 Maven 成功编译并启动。
- 当前 Redis 未连接，已验证的缓存接口通过项目已有降级机制读取 MySQL。

## 备份与核对文件

目录：`D:/ciyuantown-tools/migration-20261004-205319/`。

- `ciyuantown-source-full.sql`：恢复出的原库完整逻辑备份。
- `ciyuantown-system-mysql8.sql`：迁移后系统 MySQL 的逻辑备份。
- `verified-row-counts.tsv`、`verified-checksums.tsv`、`verified-schema.tsv`：核对结果。
- `project-config-before/`：修改前的项目配置。
- `mysql-data/`：恢复用的数据目录副本。压缩包原件保留。
