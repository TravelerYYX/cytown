-- MySQL 8; repeatable migration, preserves existing users and configured data.
SET @has_exp = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='app_user' AND column_name='experience');
SET @ddl = IF(@has_exp=0,'ALTER TABLE app_user ADD COLUMN experience BIGINT NOT NULL DEFAULT 0 COMMENT ''累计等级经验''','SELECT 1');
PREPARE level_migration FROM @ddl;
EXECUTE level_migration;
DEALLOCATE PREPARE level_migration;
-- Existing levels receive exactly their starting experience on first migration.
UPDATE app_user SET experience=100*(LEAST(60,GREATEST(1,level))-1)+15*(LEAST(60,GREATEST(1,level))-1)*(LEAST(60,GREATEST(1,level))-2),level=LEAST(60,GREATEST(1,level)) WHERE @has_exp=0;
CREATE TABLE IF NOT EXISTS user_level_ledger (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    action VARCHAR(32) NOT NULL,
    event_key VARCHAR(160) NOT NULL,
    amount BIGINT NOT NULL,
    earned_date DATE NOT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_level_event(user_id,action,event_key),
    KEY idx_level_daily(user_id,earned_date,action),
    CONSTRAINT fk_level_ledger_user FOREIGN KEY(user_id) REFERENCES app_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS user_level_signin (
    user_id BIGINT NOT NULL,
    sign_date DATE NOT NULL,
    streak_days INT NOT NULL,
    PRIMARY KEY(user_id,sign_date),
    CONSTRAINT fk_level_signin_user FOREIGN KEY(user_id) REFERENCES app_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
