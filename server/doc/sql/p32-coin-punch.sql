-- MySQL 8. Run after p23-checkin.sql and the mine-entry target migration.
CREATE TABLE IF NOT EXISTS coin_punch_config (
  id TINYINT NOT NULL PRIMARY KEY,
  points BIGINT NOT NULL,
  coins BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS user_coin_punch (
  user_id BIGINT NOT NULL,
  punch_date DATE NOT NULL,
  points BIGINT NOT NULL,
  coins BIGINT NOT NULL,
  streak_days INT NOT NULL,
  PRIMARY KEY (user_id, punch_date),
  CONSTRAINT fk_coin_punch_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT IGNORE INTO coin_punch_config (id,points,coins) VALUES (1,25,10);

START TRANSACTION;
INSERT IGNORE INTO system_config (config_key,config_value,remark)
VALUES ('coin_mine_entry_initialized','0','次元币入口迁移标记');
SELECT config_value INTO @coin_entry_initialized FROM system_config
WHERE config_key='coin_mine_entry_initialized' FOR UPDATE;
SET @coin_entry_seed = @coin_entry_initialized='0'
  AND NOT EXISTS (SELECT 1 FROM mine_entry WHERE target_type='app' AND link_url='/pages/coin/index');

DROP TEMPORARY TABLE IF EXISTS coin_mine_entry_order;
CREATE TEMPORARY TABLE coin_mine_entry_order AS
SELECT id, ROW_NUMBER() OVER (ORDER BY sort_order,id) AS position FROM mine_entry;
SELECT COALESCE(
  MIN(CASE WHEN e.enabled=TRUE AND (e.link_url='/pages/checkin/index' OR e.title='每日签到') THEN o.position END),
  MAX(o.position), 0) INTO @coin_entry_after
FROM coin_mine_entry_order o JOIN mine_entry e ON e.id=o.id;
UPDATE mine_entry e JOIN coin_mine_entry_order o ON o.id=e.id
SET e.sort_order=o.position + (o.position>@coin_entry_after)
WHERE @coin_entry_seed;
INSERT INTO mine_entry (title,description,icon_url,link_url,target_type,open_mode,show_topbar,sort_order,enabled)
SELECT '次元币','查看余额和明细','/uploads/coin/mine-coin.svg','/pages/coin/index','app','inapp',TRUE,@coin_entry_after+1,TRUE
WHERE @coin_entry_seed;
UPDATE system_config SET config_value='1' WHERE config_key='coin_mine_entry_initialized';
DROP TEMPORARY TABLE coin_mine_entry_order;
COMMIT;
