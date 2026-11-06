-- P23: monthly calendar check-in. Safe to rerun; historical cleanup is a separate one-off operation.
SET NAMES utf8mb4;
SET @checkin_ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='app_user' AND column_name='coin_balance')=0,'ALTER TABLE `app_user` ADD COLUMN coin_balance BIGINT NOT NULL DEFAULT 0','SELECT 1');
PREPARE checkin_stmt FROM @checkin_ddl;
EXECUTE checkin_stmt;
DEALLOCATE PREPARE checkin_stmt;
SET @checkin_ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='app_user' AND column_name='avatar_frame_expires_at')=0,'ALTER TABLE `app_user` ADD COLUMN avatar_frame_expires_at BIGINT NULL','SELECT 1');
PREPARE checkin_stmt FROM @checkin_ddl;
EXECUTE checkin_stmt;
DEALLOCATE PREPARE checkin_stmt;
SET @checkin_ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='post' AND column_name='checkin_recommended')=0,'ALTER TABLE `post` ADD COLUMN checkin_recommended BOOLEAN NOT NULL DEFAULT FALSE','SELECT 1');
PREPARE checkin_stmt FROM @checkin_ddl;
EXECUTE checkin_stmt;
DEALLOCATE PREPARE checkin_stmt;
SET @checkin_ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='user_level_signin' AND column_name='makeup')=0,'ALTER TABLE `user_level_signin` ADD COLUMN makeup BOOLEAN NOT NULL DEFAULT FALSE','SELECT 1');
PREPARE checkin_stmt FROM @checkin_ddl;
EXECUTE checkin_stmt;
DEALLOCATE PREPARE checkin_stmt;
SET @checkin_ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='user_level_signin' AND column_name='reward_json')=0,'ALTER TABLE `user_level_signin` ADD COLUMN reward_json JSON NULL','SELECT 1');
PREPARE checkin_stmt FROM @checkin_ddl;
EXECUTE checkin_stmt;
DEALLOCATE PREPARE checkin_stmt;
SET @checkin_ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='user_level_signin' AND column_name='config_version')=0,'ALTER TABLE `user_level_signin` ADD COLUMN config_version BIGINT NULL','SELECT 1');
PREPARE checkin_stmt FROM @checkin_ddl;
EXECUTE checkin_stmt;
DEALLOCATE PREPARE checkin_stmt;
SET @checkin_ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='user_level_signin' AND column_name='claimed_at')=0,'ALTER TABLE `user_level_signin` ADD COLUMN claimed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP','SELECT 1');
PREPARE checkin_stmt FROM @checkin_ddl;
EXECUTE checkin_stmt;
DEALLOCATE PREPARE checkin_stmt;
CREATE TABLE IF NOT EXISTS checkin_config (
 id INT NOT NULL PRIMARY KEY, version BIGINT NOT NULL, config_json JSON NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS user_coin_ledger (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,user_id BIGINT NOT NULL,event_key VARCHAR(160) NOT NULL,
 amount BIGINT NOT NULL,balance_after BIGINT NOT NULL,create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uk_coin_event(user_id,event_key),CONSTRAINT fk_coin_user FOREIGN KEY(user_id) REFERENCES app_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS user_avatar_frame (
 user_id BIGINT NOT NULL,frame_id VARCHAR(32) NOT NULL,expires_at DATETIME NULL,
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,PRIMARY KEY(user_id,frame_id),
 CONSTRAINT fk_frame_user FOREIGN KEY(user_id) REFERENCES app_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT IGNORE INTO checkin_config(id,version,config_json) VALUES(1,1,'{"version":1,"makeupPrice":10,"coinIcon":{"url":"/uploads/checkin/coin.webp","originalBytes":1360100,"processedBytes":12742},"experienceIcon":{"url":"/uploads/checkin/experience.webp","originalBytes":818364,"processedBytes":12686},"frames":[{"id":"xingxi","name":"星汐 · 星愿","image":{"url":"/uploads/checkin/frame.webp","originalBytes":1446921,"processedBytes":86554}}],"days":[{"day":1,"coins":10,"experience":10,"frameId":"","frameDays":0,"primary":"coins"},{"day":2,"coins":10,"experience":10,"frameId":"","frameDays":0,"primary":"coins"},{"day":3,"coins":10,"experience":10,"frameId":"","frameDays":0,"primary":"coins"},{"day":4,"coins":10,"experience":10,"frameId":"","frameDays":0,"primary":"coins"},{"day":5,"coins":10,"experience":10,"frameId":"","frameDays":0,"primary":"coins"},{"day":6,"coins":10,"experience":10,"frameId":"","frameDays":0,"primary":"coins"},{"day":7,"coins":10,"experience":10,"frameId":"","frameDays":0,"primary":"coins"},{"day":8,"coins":10,"experience":10,"frameId":"","frameDays":0,"primary":"coins"},{"day":9,"coins":10,"experience":10,"frameId":"","frameDays":0,"primary":"coins"},{"day":10,"coins":10,"experience":10,"frameId":"","frameDays":0,"primary":"coins"},{"day":11,"coins":10,"experience":10,"frameId":"","frameDays":0,"primary":"coins"},{"day":12,"coins":10,"experience":10,"frameId":"","frameDays":0,"primary":"coins"},{"day":13,"coins":10,"experience":10,"frameId":"","frameDays":0,"primary":"coins"},{"day":14,"coins":10,"experience":10,"frameId":"","frameDays":0,"primary":"coins"},{"day":15,"coins":10,"experience":10,"frameId":"","frameDays":0,"primary":"coins"},{"day":16,"coins":10,"experience":10,"frameId":"","frameDays":0,"primary":"coins"},{"day":17,"coins":10,"experience":10,"frameId":"","frameDays":0,"primary":"coins"},{"day":18,"coins":10,"experience":10,"frameId":"","frameDays":0,"primary":"coins"},{"day":19,"coins":10,"experience":10,"frameId":"","frameDays":0,"primary":"coins"},{"day":20,"coins":10,"experience":10,"frameId":"","frameDays":0,"primary":"coins"},{"day":21,"coins":10,"experience":10,"frameId":"","frameDays":0,"primary":"coins"},{"day":22,"coins":10,"experience":10,"frameId":"","frameDays":0,"primary":"coins"},{"day":23,"coins":10,"experience":10,"frameId":"","frameDays":0,"primary":"coins"},{"day":24,"coins":10,"experience":10,"frameId":"","frameDays":0,"primary":"coins"},{"day":25,"coins":10,"experience":10,"frameId":"","frameDays":0,"primary":"coins"},{"day":26,"coins":10,"experience":10,"frameId":"","frameDays":0,"primary":"coins"},{"day":27,"coins":10,"experience":10,"frameId":"","frameDays":0,"primary":"coins"},{"day":28,"coins":10,"experience":10,"frameId":"","frameDays":0,"primary":"coins"},{"day":29,"coins":10,"experience":10,"frameId":"","frameDays":0,"primary":"coins"},{"day":30,"coins":10,"experience":10,"frameId":"","frameDays":0,"primary":"coins"},{"day":31,"coins":10,"experience":10,"frameId":"","frameDays":0,"primary":"coins"}]}');
-- Upgrade the existing first daily-check-in entry exactly once; later admin edits survive reruns.
INSERT IGNORE INTO system_config(config_key,config_value,remark) VALUES('checkin_entry_initialized','0','签到入口迁移标记');
UPDATE home_entry SET target_type='app',link_url='/pages/checkin/index',open_mode='inapp',show_topbar=TRUE
WHERE name='每日签到' AND (SELECT config_value FROM system_config WHERE config_key='checkin_entry_initialized')='0';
INSERT INTO home_entry(name,icon_url,link_url,target_type,open_mode,show_topbar,sort_order,enabled)
SELECT '每日签到','/uploads/checkin/coin.webp','/pages/checkin/index','app','inapp',TRUE,(SELECT COALESCE(MIN(sort_order),0)-1 FROM home_entry e),TRUE
WHERE NOT EXISTS(SELECT 1 FROM home_entry WHERE name='每日签到') AND (SELECT config_value FROM system_config WHERE config_key='checkin_entry_initialized')='0';
UPDATE system_config SET config_value='1' WHERE config_key='checkin_entry_initialized';

