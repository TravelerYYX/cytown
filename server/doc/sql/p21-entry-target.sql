-- MySQL 8; repeatable. Existing entries retain their HTTP/HTTPS destinations.
USE ciyuantown;
SET NAMES utf8mb4;
SET @entry_ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='home_entry' AND column_name='target_type')=0,
 'ALTER TABLE home_entry ADD COLUMN target_type VARCHAR(10) NOT NULL DEFAULT ''link'' COMMENT ''link: web URL; app: internal page'' AFTER icon_url', 'SELECT 1');
PREPARE entry_stmt FROM @entry_ddl;
EXECUTE entry_stmt;
DEALLOCATE PREPARE entry_stmt;
SET @entry_ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='mine_entry' AND column_name='target_type')=0,
 'ALTER TABLE mine_entry ADD COLUMN target_type VARCHAR(10) NOT NULL DEFAULT ''link'' COMMENT ''link: web URL; app: internal page'' AFTER icon_url', 'SELECT 1');
PREPARE entry_stmt FROM @entry_ddl;
EXECUTE entry_stmt;
DEALLOCATE PREPARE entry_stmt;
