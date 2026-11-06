-- Shanghai natural days. Only the known last login day can be recovered from legacy data.
SET @activity_days_exists = (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='app_user' AND column_name='active_days');
SET @activity_ddl = IF(@activity_days_exists=0,
  'ALTER TABLE app_user ADD COLUMN active_days BIGINT NOT NULL DEFAULT 0', 'SELECT 1');
PREPARE activity_stmt FROM @activity_ddl;
EXECUTE activity_stmt;
DEALLOCATE PREPARE activity_stmt;

SET @activity_date_exists = (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='app_user' AND column_name='last_active_date');
SET @activity_ddl = IF(@activity_date_exists=0,
  'ALTER TABLE app_user ADD COLUMN last_active_date DATE NULL', 'SELECT 1');
PREPARE activity_stmt FROM @activity_ddl;
EXECUTE activity_stmt;
DEALLOCATE PREPARE activity_stmt;

UPDATE app_user SET active_days=1,last_active_date=DATE(last_login_time)
WHERE last_active_date IS NULL AND active_days=0 AND last_login_time IS NOT NULL;
