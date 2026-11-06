-- Per-account avatar frame. Empty string means no frame; existing users keep that choice.
SET @frame_column_exists = (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='app_user' AND column_name='avatar_frame');
SET @frame_ddl = IF(@frame_column_exists=0,
  'ALTER TABLE app_user ADD COLUMN avatar_frame VARCHAR(32) NOT NULL DEFAULT '''' AFTER avatar',
  'SELECT 1');
PREPARE frame_stmt FROM @frame_ddl;
EXECUTE frame_stmt;
DEALLOCATE PREPARE frame_stmt;
