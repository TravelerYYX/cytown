-- Public profiles use only province-level location. Never backfill with the server's IP.
SET @profile_location_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='app_user' AND column_name='ip_province')=0,
  'ALTER TABLE app_user ADD COLUMN ip_province VARCHAR(128) NULL', 'SELECT 1');
PREPARE profile_location_stmt FROM @profile_location_ddl;
EXECUTE profile_location_stmt;
DEALLOCATE PREPARE profile_location_stmt;
SET @profile_location_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='app_user' AND column_name='ip_located_at')=0,
  'ALTER TABLE app_user ADD COLUMN ip_located_at BIGINT NULL', 'SELECT 1');
PREPARE profile_location_stmt FROM @profile_location_ddl;
EXECUTE profile_location_stmt;
DEALLOCATE PREPARE profile_location_stmt;
