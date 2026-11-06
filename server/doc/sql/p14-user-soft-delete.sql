-- P14 用户软删除；基于 P1-P13 的 MySQL 8 数据库。停写并备份后执行一次。
SET NAMES utf8mb4;

ALTER TABLE app_user
  ADD COLUMN deleted BOOLEAN NOT NULL DEFAULT FALSE COMMENT '已删除，不可通过启用恢复',
  ADD COLUMN deleted_at DATETIME NULL COMMENT '删除时间',
  ADD COLUMN deleted_by BIGINT NULL COMMENT '删除操作人 ID',
  ADD COLUMN active_qq_number VARCHAR(11) COLLATE utf8mb4_unicode_ci
    GENERATED ALWAYS AS (CASE WHEN deleted = FALSE THEN qq_number ELSE NULL END) STORED,
  DROP INDEX uk_qq,
  ADD UNIQUE KEY uk_active_qq (active_qq_number),
  ADD KEY idx_deleted_create (deleted, create_time DESC),
  ADD CONSTRAINT chk_app_deleted CHECK (deleted IN (0,1));

ALTER TABLE admin_user
  ADD COLUMN deleted BOOLEAN NOT NULL DEFAULT FALSE COMMENT '已删除，不可通过启用恢复',
  ADD COLUMN deleted_at DATETIME NULL COMMENT '删除时间',
  ADD COLUMN deleted_by BIGINT NULL COMMENT '删除操作人 ID',
  ADD COLUMN active_username VARCHAR(50) COLLATE utf8mb4_unicode_ci
    GENERATED ALWAYS AS (CASE WHEN deleted = FALSE THEN username ELSE NULL END) STORED,
  DROP INDEX uk_username,
  ADD UNIQUE KEY uk_active_username (active_username),
  ADD KEY idx_deleted_type_create (deleted, user_type, create_time DESC),
  ADD CONSTRAINT chk_admin_deleted CHECK (deleted IN (0,1)),
  ADD CONSTRAINT chk_deleted_official CHECK (deleted = FALSE OR user_type = 1);
