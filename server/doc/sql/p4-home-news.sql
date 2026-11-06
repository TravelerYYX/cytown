-- 首页资讯改为帖子显式勾选。MySQL 8，在目标开发/部署数据库执行。
-- 先完成 P1/P3 结构，再执行此脚本，最后发布后端与前端。
-- 可重复执行；不自动勾选旧帖子，也不覆盖已有资讯配置。
SET NAMES utf8mb4;
SET @home_news_ddl = IF(
 (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='post' AND column_name='home_news_enabled')=0,
 'ALTER TABLE `post` ADD COLUMN home_news_enabled BOOLEAN NOT NULL DEFAULT FALSE COMMENT ''是否参与首页资讯轮播'' AFTER status',
 'SELECT 1');
PREPARE home_news_stmt FROM @home_news_ddl;
EXECUTE home_news_stmt;
DEALLOCATE PREPARE home_news_stmt;
SET @home_news_ddl = IF(
 (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='post' AND index_name='idx_home_news')=0,
 'ALTER TABLE `post` ADD INDEX idx_home_news(home_news_enabled,status,create_time DESC,id DESC)',
 'SELECT 1');
PREPARE home_news_stmt FROM @home_news_ddl;
EXECUTE home_news_stmt;
DEALLOCATE PREPARE home_news_stmt;
INSERT IGNORE INTO system_config(config_key,config_value,remark)
VALUES ('news_empty_text','暂无资讯，敬请期待','无首页资讯时显示的文案');
