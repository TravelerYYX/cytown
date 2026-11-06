-- P13: channel publishing restriction. Existing channels remain unrestricted.
USE ciyuantown;
SET NAMES utf8mb4;
SET @channel_ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='home_channel' AND column_name='admin_only')=0,
 'ALTER TABLE home_channel ADD COLUMN admin_only BOOLEAN NOT NULL DEFAULT FALSE COMMENT ''Only official accounts may publish'' AFTER is_default', 'SELECT 1');
PREPARE channel_stmt FROM @channel_ddl;
EXECUTE channel_stmt;
DEALLOCATE PREPARE channel_stmt;
