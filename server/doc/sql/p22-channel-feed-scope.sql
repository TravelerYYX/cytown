-- P22: configurable channel feed scope and publishing permission.
USE ciyuantown;
SET NAMES utf8mb4;
SET @channel_scope_added=(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='home_channel' AND column_name='show_all_posts')=0;
SET @channel_ddl=IF(@channel_scope_added,
 'ALTER TABLE home_channel ADD COLUMN show_all_posts BOOLEAN NOT NULL DEFAULT FALSE COMMENT ''Show all published posts'' AFTER admin_only', 'SELECT 1');
PREPARE channel_stmt FROM @channel_ddl;
EXECUTE channel_stmt;
DEALLOCATE PREPARE channel_stmt;
SET @channel_ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='home_channel' AND column_name='allow_publish')=0,
 'ALTER TABLE home_channel ADD COLUMN allow_publish BOOLEAN NOT NULL DEFAULT TRUE COMMENT ''Allow publishing into channel'' AFTER show_all_posts', 'SELECT 1');
PREPARE channel_stmt FROM @channel_ddl;
EXECUTE channel_stmt;
DEALLOCATE PREPARE channel_stmt;

-- Keep existing publishing permissions and the default publishing destination.
-- Seed discovery once; reruns retain subsequent admin edits, ordering and deletions.
SET @discover_sort=(SELECT COALESCE(MIN(sort_order),0)-1 FROM home_channel);
INSERT INTO home_channel(name,sort_order,enabled,is_default,admin_only,show_all_posts,allow_publish)
SELECT '发现',@discover_sort,TRUE,FALSE,FALSE,TRUE,FALSE
WHERE @channel_scope_added AND NOT EXISTS(SELECT 1 FROM home_channel WHERE name='发现');
UPDATE home_channel SET show_all_posts=TRUE,sort_order=@discover_sort,allow_publish=IF(is_default,allow_publish,FALSE)
WHERE @channel_scope_added AND name='发现';
