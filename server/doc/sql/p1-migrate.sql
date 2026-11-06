-- 使用 mysql 客户端在 doc/sql 目录执行；现有管理员保持原样。
USE ciyuantown;
SOURCE p1-schema.sql;
-- 只有帖子表为空的新开发环境才单独执行 p1-seed.sql。
