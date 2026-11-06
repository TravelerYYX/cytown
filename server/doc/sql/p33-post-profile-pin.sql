-- MySQL 8. Apply before deploying the post management backend.
SET @post_pin_ddl = IF(
  EXISTS(SELECT 1 FROM information_schema.COLUMNS
         WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'post' AND COLUMN_NAME = 'profile_pinned_at'),
  'SELECT 1',
  'ALTER TABLE `post` ADD COLUMN profile_pinned_at DATETIME(6) NULL'
);
PREPARE post_pin_migration FROM @post_pin_ddl;
EXECUTE post_pin_migration;
DEALLOCATE PREPARE post_pin_migration;
