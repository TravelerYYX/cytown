-- P8 官方账号体系：admin_user 区分超管/官方只读账号，支持禁用与 token 版本踢下线；MySQL 8。
SET NAMES utf8mb4;

ALTER TABLE admin_user
  ADD COLUMN user_type TINYINT NOT NULL DEFAULT 0 COMMENT '账号类型：0超级管理员 1官方只读账号' AFTER avatar,
  ADD COLUMN status TINYINT NOT NULL DEFAULT 0 COMMENT '状态：0正常 1禁用' AFTER user_type,
  ADD COLUMN token_version INT NOT NULL DEFAULT 0 COMMENT 'token版本，禁用/重置密码时递增' AFTER status,
  ADD COLUMN created_by BIGINT NULL DEFAULT NULL COMMENT '创建人(超管id)，官方账号使用' AFTER token_version,
  ADD CONSTRAINT chk_admin_type CHECK (user_type IN (0,1)),
  ADD CONSTRAINT chk_admin_status CHECK (status IN (0,1));
