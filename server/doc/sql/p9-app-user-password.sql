-- P9 APP用户密码登录：可选密码（NULL=未设置，仅验证码登录），不影响存量用户；MySQL 8。
SET NAMES utf8mb4;

ALTER TABLE app_user
  ADD COLUMN password VARCHAR(100) NULL DEFAULT NULL COMMENT '登录密码(BCrypt)，NULL=未设置密码' AFTER email;
