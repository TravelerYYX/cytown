-- 次元町后端管理面板数据库初始化脚本
-- 在 doc/sql 目录通过 mysql 客户端执行。管理员从已有库迁移或由部署人员创建。
SET NAMES utf8mb4;
CREATE DATABASE IF NOT EXISTS ciyuantown DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE ciyuantown;

CREATE TABLE IF NOT EXISTS admin_user (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  username VARCHAR(50) NOT NULL COMMENT '用户名',
  password VARCHAR(100) NOT NULL COMMENT '密码(BCrypt)',
  nickname VARCHAR(50) DEFAULT '' COMMENT '昵称',
  avatar VARCHAR(255) DEFAULT '' COMMENT '头像URL(相对路径)',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_username (username)
) ENGINE=InnoDB COMMENT='后台管理员表';

-- 新库保留原有开发账号；INSERT IGNORE 不会覆盖已迁移的同名管理员。
INSERT IGNORE INTO admin_user (username, password, nickname)
VALUES ('admin', '$2a$10$1OZPRaWA2kR9SmEy2U2VpeYobje2VXdLM.RnInTf2vt/ug69trZqi', '管理员');
SOURCE p1-schema.sql;
SOURCE p1-seed.sql;
SOURCE p2-schema.sql;
SOURCE p3-schema.sql;
SOURCE p4-home-news.sql;
SOURCE p5-publish.sql;

-- 已有环境执行：
-- ALTER TABLE admin_user ADD COLUMN avatar VARCHAR(255) DEFAULT '' COMMENT '头像URL(相对路径)' AFTER nickname;
-- P1 增量：SOURCE p1-migrate.sql（四张正式业务表的唯一结构定义见 p1-schema.sql；已有环境需按需单独加载种子）。
