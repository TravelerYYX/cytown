-- P7 角色卡片背景：用户选择的背景图文件名（前端静态资源，服务端不存图片，仅存文件名）；MySQL 8。
SET NAMES utf8mb4;

ALTER TABLE app_user
  ADD COLUMN card_background VARCHAR(32) NOT NULL DEFAULT '01.png' COMMENT '角色卡片背景文件名(前端静态资源 user_background 目录)' AFTER gender;
