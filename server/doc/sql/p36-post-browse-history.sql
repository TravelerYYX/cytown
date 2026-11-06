-- MySQL 8. Run after the mine-entry target migration.
CREATE TABLE IF NOT EXISTS user_post_history (
  user_id BIGINT NOT NULL,
  post_id BIGINT NOT NULL,
  viewed_at DATETIME(6) NOT NULL,
  PRIMARY KEY (user_id, post_id),
  INDEX idx_post_history_recent (user_id, viewed_at DESC, post_id DESC),
  CONSTRAINT fk_post_history_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE,
  CONSTRAINT fk_post_history_post FOREIGN KEY (post_id) REFERENCES post(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

START TRANSACTION;
INSERT IGNORE INTO system_config (config_key, config_value, remark)
VALUES ('browse_history_entry_initialized', '0', '浏览历史入口迁移标记');
SELECT config_value INTO @history_entry_initialized FROM system_config
WHERE config_key='browse_history_entry_initialized' FOR UPDATE;
SET @history_entry_seed = @history_entry_initialized='0'
  AND NOT EXISTS (SELECT 1 FROM mine_entry WHERE target_type='app' AND SUBSTRING_INDEX(link_url,'?',1)='/pages/browse-history/index');

DROP TEMPORARY TABLE IF EXISTS browse_history_entry_order;
CREATE TEMPORARY TABLE browse_history_entry_order AS
SELECT id, ROW_NUMBER() OVER (ORDER BY sort_order,id) AS position FROM mine_entry;
SELECT COALESCE(
  MIN(CASE WHEN e.enabled=TRUE AND (SUBSTRING_INDEX(e.link_url,'?',1)='/pages/checkin/index' OR e.title='每日签到') THEN o.position END),
  MAX(o.position), 0) INTO @history_entry_after
FROM browse_history_entry_order o JOIN mine_entry e ON e.id=o.id;
UPDATE mine_entry e JOIN browse_history_entry_order o ON o.id=e.id
SET e.sort_order=o.position + (o.position>@history_entry_after)
WHERE @history_entry_seed;
INSERT INTO mine_entry (title,description,icon_url,link_url,target_type,open_mode,show_topbar,sort_order,enabled)
SELECT '浏览历史','查看浏览过的帖子','/uploads/browse-history/mine-history.svg','/pages/browse-history/index','app','inapp',TRUE,@history_entry_after+1,TRUE
WHERE @history_entry_seed;
UPDATE system_config SET config_value='1' WHERE config_key='browse_history_entry_initialized';
DROP TEMPORARY TABLE browse_history_entry_order;
COMMIT;
