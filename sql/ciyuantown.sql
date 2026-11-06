-- MySQL dump 10.13  Distrib 8.0.46, for Win64 (x86_64)
--
-- Target: MySQL 8.0    Database: ciyuantown
-- ------------------------------------------------------
-- Server version	8.0.46

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;

--
-- Table structure for table `admin_user`
--

DROP TABLE IF EXISTS `admin_user`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `admin_user` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `username` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '用户名',
  `password` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '密码(BCrypt)',
  `nickname` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '昵称',
  `avatar` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT 'ͷ??URL(????·??)',
  `user_type` tinyint NOT NULL DEFAULT '0' COMMENT '账号类型：0超级管理员 1官方只读账号',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '状态：0正常 1禁用',
  `token_version` int NOT NULL DEFAULT '0' COMMENT 'token版本，禁用/重置密码时递增',
  `created_by` bigint DEFAULT NULL COMMENT '创建人(超管id)，官方账号使用',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint(1) NOT NULL DEFAULT '0' COMMENT '已删除，不可通过启用恢复',
  `deleted_at` datetime DEFAULT NULL COMMENT '删除时间',
  `deleted_by` bigint DEFAULT NULL COMMENT '删除操作人 ID',
  `active_username` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci GENERATED ALWAYS AS ((case when (`deleted` = false) then `username` else NULL end)) STORED,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_active_username` (`active_username`),
  KEY `idx_deleted_type_create` (`deleted`,`user_type`,`create_time` DESC),
  CONSTRAINT `chk_admin_deleted` CHECK ((`deleted` in (0,1))),
  CONSTRAINT `chk_admin_status` CHECK ((`status` in (0,1))),
  CONSTRAINT `chk_admin_type` CHECK ((`user_type` in (0,1))),
  CONSTRAINT `chk_deleted_official` CHECK (((`deleted` = false) or (`user_type` = 1)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='后台管理员表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `app_release`
--

DROP TABLE IF EXISTS `app_release`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `app_release` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `version_name` varchar(64) NOT NULL,
  `version_code` bigint NOT NULL,
  `notes` text NOT NULL,
  `force_update` tinyint(1) NOT NULL DEFAULT '0',
  `status` varchar(16) NOT NULL DEFAULT 'draft',
  `package_url` varchar(2048) DEFAULT NULL,
  `package_name` varchar(255) DEFAULT NULL,
  `apk_version_code` bigint DEFAULT NULL,
  `apk_version_name` varchar(64) DEFAULT NULL,
  `size_bytes` bigint NOT NULL DEFAULT '0',
  `sha256` char(64) DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `published_at` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_release_history` (`status`,`version_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `app_system_avatar`
--

DROP TABLE IF EXISTS `app_system_avatar`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `app_system_avatar` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `url` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '头像URL(相对路径,如 /uploads/sys-avatar/xx.png)',
  `type` tinyint NOT NULL DEFAULT '0' COMMENT '0静态 1动态',
  `sort` int NOT NULL DEFAULT '0' COMMENT '组内排序,越小越前',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '0启用 1停用',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_type_status_sort` (`type`,`status`,`sort`),
  CONSTRAINT `chk_avatar_status` CHECK ((`status` in (0,1))),
  CONSTRAINT `chk_avatar_type` CHECK ((`type` in (0,1)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统头像库';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `app_tag`
--

DROP TABLE IF EXISTS `app_tag`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `app_tag` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '标签名',
  `category` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '分类:game/本体/interest/mbti等',
  `source` tinyint NOT NULL DEFAULT '0' COMMENT '0系统预置 1用户自定义沉淀',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '0启用 1停用',
  `sort` int NOT NULL DEFAULT '0' COMMENT '分类内排序,越小越前',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_category_name` (`category`,`name`),
  KEY `idx_category_status` (`category`,`status`,`sort`),
  CONSTRAINT `chk_tag_source` CHECK ((`source` in (0,1))),
  CONSTRAINT `chk_tag_status` CHECK ((`status` in (0,1)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户标签库';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `app_update_channel`
--

DROP TABLE IF EXISTS `app_update_channel`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `app_update_channel` (
  `id` int NOT NULL,
  `active_release_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `app_user`
--

DROP TABLE IF EXISTS `app_user`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `app_user` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '用户ID',
  `public_uid` bigint DEFAULT NULL COMMENT '对外公开UID，从1000开始',
  `qq_number` varchar(11) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '普通用户QQ号；官方社区身份为NULL',
  `email` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'QQ邮箱',
  `password` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '登录密码(BCrypt)，NULL=未设置密码',
  `nickname` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '昵称',
  `avatar` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '头像相对URL',
  `avatar_frame` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '',
  `signature` varchar(150) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '个性签名(前端上限50字,留冗余)',
  `gender` tinyint NOT NULL DEFAULT '2' COMMENT '0男 1女 2保密',
  `card_background` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '01.png' COMMENT '角色卡片背景文件名(前端静态资源 user_background 目录)',
  `level` int NOT NULL DEFAULT '1' COMMENT '等级',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '0正常 1禁用',
  `token_version` int NOT NULL DEFAULT '0' COMMENT '会话版本',
  `last_login_time` datetime DEFAULT NULL COMMENT '最后登录时间',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '注册时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `username` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '自定义账号，NULL时用QQ号',
  `official_admin_id` bigint DEFAULT NULL COMMENT '官方社区身份固定关联',
  `deleted` tinyint(1) NOT NULL DEFAULT '0' COMMENT '已删除，不可通过启用恢复',
  `deleted_at` datetime DEFAULT NULL COMMENT '删除时间',
  `deleted_by` bigint DEFAULT NULL COMMENT '删除操作人 ID',
  `active_qq_number` varchar(11) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci GENERATED ALWAYS AS ((case when (`deleted` = false) then `qq_number` else NULL end)) STORED,
  `experience` bigint NOT NULL DEFAULT '0' COMMENT '累计等级经验',
  `active_days` bigint NOT NULL DEFAULT '0',
  `last_active_date` date DEFAULT NULL,
  `coin_balance` decimal(20,1) NOT NULL DEFAULT '0.0',
  `avatar_frame_expires_at` bigint DEFAULT NULL,
  `notice_read_since` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `ip_province` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `ip_located_at` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_app_user_public_uid` (`public_uid`),
  UNIQUE KEY `uk_official_admin` (`official_admin_id`),
  UNIQUE KEY `uk_active_qq` (`active_qq_number`),
  KEY `idx_status_create` (`status`,`create_time` DESC),
  KEY `idx_deleted_create` (`deleted`,`create_time` DESC),
  CONSTRAINT `chk_app_deleted` CHECK ((`deleted` in (0,1))),
  CONSTRAINT `chk_user_gender` CHECK ((`gender` in (0,1,2))),
  CONSTRAINT `chk_user_status` CHECK ((`status` in (0,1)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='APP用户';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `app_user_tag`
--

DROP TABLE IF EXISTS `app_user_tag`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `app_user_tag` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `tag_id` bigint DEFAULT NULL COMMENT '系统标签ID(自定义标签为NULL)',
  `custom_name` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '自定义标签名(tag_id为NULL时使用)',
  `sort_order` int NOT NULL DEFAULT '0' COMMENT '用户标签展示顺序,越小越前',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_tag` (`user_id`,`tag_id`),
  KEY `idx_user_sort` (`user_id`,`sort_order`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户标签关联';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `chat_admin_audit`
--

DROP TABLE IF EXISTS `chat_admin_audit`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `chat_admin_audit` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `operator_id` bigint NOT NULL,
  `action` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  `conversation_id` bigint NOT NULL DEFAULT '0',
  `target_id` varchar(80) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '',
  `created_at` bigint NOT NULL,
  `detail` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `chat_asset`
--

DROP TABLE IF EXISTS `chat_asset`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `chat_asset` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `owner_id` bigint NOT NULL,
  `kind` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL,
  `state` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL,
  `version` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `mime` varchar(80) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '',
  `extension` varchar(12) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '',
  `size_bytes` bigint NOT NULL DEFAULT '0',
  `width` int NOT NULL DEFAULT '0',
  `height` int NOT NULL DEFAULT '0',
  `duration_ms` bigint NOT NULL DEFAULT '0',
  `manifest_json` longtext COLLATE utf8mb4_unicode_ci NOT NULL,
  `config_json` longtext COLLATE utf8mb4_unicode_ci NOT NULL,
  `cover_id` bigint DEFAULT NULL,
  `error_message` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '',
  `created_at` bigint NOT NULL,
  `attached` tinyint(1) NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_chat_asset_owner` (`owner_id`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `chat_conversation`
--

DROP TABLE IF EXISTS `chat_conversation`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `chat_conversation` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_low` bigint NOT NULL,
  `user_high` bigint NOT NULL,
  `last_message_id` bigint NOT NULL DEFAULT '0',
  `cleared_through` bigint NOT NULL DEFAULT '0',
  `has_history` tinyint(1) NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_chat_pair` (`user_low`,`user_high`),
  KEY `idx_chat_recent` (`last_message_id`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `chat_delete_job`
--

DROP TABLE IF EXISTS `chat_delete_job`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `chat_delete_job` (
  `id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `conversation_id` bigint NOT NULL,
  `through_id` bigint NOT NULL,
  `single_id` bigint DEFAULT NULL,
  `operator_id` bigint NOT NULL,
  `created_at` bigint NOT NULL,
  `rows_done` tinyint(1) NOT NULL DEFAULT '0',
  `error_message` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '',
  PRIMARY KEY (`id`),
  KEY `idx_chat_delete_pending` (`rows_done`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `chat_delete_object`
--

DROP TABLE IF EXISTS `chat_delete_object`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `chat_delete_object` (
  `job_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `cleanup_id` varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  PRIMARY KEY (`job_id`,`cleanup_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `chat_deleted_message`
--

DROP TABLE IF EXISTS `chat_deleted_message`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `chat_deleted_message` (
  `message_id` bigint NOT NULL,
  `conversation_id` bigint NOT NULL,
  `sender_id` bigint NOT NULL,
  `client_id` varchar(80) COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`message_id`),
  UNIQUE KEY `uk_chat_deleted_client` (`sender_id`,`client_id`),
  KEY `idx_chat_deleted_conversation` (`conversation_id`,`message_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `chat_event`
--

DROP TABLE IF EXISTS `chat_event`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `chat_event` (
  `user_id` bigint NOT NULL,
  `sequence_no` bigint NOT NULL,
  `kind` varchar(24) COLLATE utf8mb4_unicode_ci NOT NULL,
  `conversation_id` bigint NOT NULL,
  `message_id` bigint DEFAULT NULL,
  PRIMARY KEY (`user_id`,`sequence_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `chat_member`
--

DROP TABLE IF EXISTS `chat_member`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `chat_member` (
  `conversation_id` bigint NOT NULL,
  `user_id` bigint NOT NULL,
  `read_message_id` bigint NOT NULL DEFAULT '0',
  PRIMARY KEY (`conversation_id`,`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `chat_message`
--

DROP TABLE IF EXISTS `chat_message`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `chat_message` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `conversation_id` bigint NOT NULL,
  `sender_id` bigint NOT NULL,
  `client_id` varchar(80) COLLATE utf8mb4_unicode_ci NOT NULL,
  `kind` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL,
  `content` text COLLATE utf8mb4_unicode_ci NOT NULL,
  `asset_id` bigint DEFAULT NULL,
  `sent_at` bigint NOT NULL,
  `revoke_until` bigint NOT NULL,
  `revoked` tinyint(1) NOT NULL DEFAULT '0',
  `protected_receipt` tinyint(1) NOT NULL DEFAULT '0',
  `reply_to_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_chat_client` (`sender_id`,`client_id`),
  KEY `idx_chat_history` (`conversation_id`,`id`),
  KEY `idx_chat_reply` (`reply_to_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `chat_push_task`
--

DROP TABLE IF EXISTS `chat_push_task`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `chat_push_task` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `message_id` bigint NOT NULL,
  `user_id` bigint NOT NULL,
  `cid` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL,
  `token_version` int NOT NULL,
  `request_id` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  `state` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'pending',
  `attempts` int NOT NULL DEFAULT '0',
  `next_attempt_at` bigint NOT NULL DEFAULT '0',
  `lease_until` bigint NOT NULL DEFAULT '0',
  `last_error` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_chat_push` (`message_id`,`cid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `chat_receipt`
--

DROP TABLE IF EXISTS `chat_receipt`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `chat_receipt` (
  `operation_id` varchar(80) COLLATE utf8mb4_unicode_ci NOT NULL,
  `message_id` bigint NOT NULL,
  `user_id` bigint NOT NULL,
  `kind` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL,
  `state` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'reserved',
  `created_at` bigint NOT NULL,
  PRIMARY KEY (`operation_id`),
  KEY `idx_chat_receipt_message` (`message_id`,`state`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `chat_restriction`
--

DROP TABLE IF EXISTS `chat_restriction`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `chat_restriction` (
  `user_id` bigint NOT NULL,
  `conversation_id` bigint NOT NULL DEFAULT '0',
  `blocked` tinyint(1) NOT NULL DEFAULT '0',
  `muted_until` bigint NOT NULL DEFAULT '0',
  `operator_id` bigint NOT NULL,
  `updated_at` bigint NOT NULL,
  PRIMARY KEY (`user_id`,`conversation_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `chat_stream`
--

DROP TABLE IF EXISTS `chat_stream`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `chat_stream` (
  `user_id` bigint NOT NULL,
  `sequence_no` bigint NOT NULL DEFAULT '0',
  PRIMARY KEY (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `chat_ticket`
--

DROP TABLE IF EXISTS `chat_ticket`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `chat_ticket` (
  `ticket` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `user_id` bigint NOT NULL,
  `token_version` int NOT NULL,
  `expires_at` bigint NOT NULL,
  PRIMARY KEY (`ticket`),
  KEY `idx_chat_ticket_expiry` (`expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `chat_video_job`
--

DROP TABLE IF EXISTS `chat_video_job`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `chat_video_job` (
  `asset_id` bigint NOT NULL,
  `state` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'queued',
  `worker_token` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `lease_until` bigint NOT NULL DEFAULT '0',
  `attempts` int NOT NULL DEFAULT '0',
  PRIMARY KEY (`asset_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `checkin_config`
--

DROP TABLE IF EXISTS `checkin_config`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `checkin_config` (
  `id` int NOT NULL,
  `version` bigint NOT NULL,
  `config_json` json NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `coin_punch_config`
--

DROP TABLE IF EXISTS `coin_punch_config`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `coin_punch_config` (
  `id` tinyint NOT NULL,
  `points` bigint NOT NULL,
  `coins` decimal(20,1) NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `comment`
--

DROP TABLE IF EXISTS `comment`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `comment` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '评论ID',
  `post_id` bigint NOT NULL COMMENT '帖子ID',
  `parent_id` bigint DEFAULT NULL COMMENT '顶级评论为空，回复指向顶级评论',
  `reply_to_id` bigint DEFAULT NULL,
  `reply_to_nickname` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '',
  `author_id` bigint DEFAULT NULL,
  `author_nickname` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '评论者昵称快照',
  `author_level` int NOT NULL DEFAULT '1' COMMENT '等级',
  `region` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '展示地区，不存用户原始IP',
  `content` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '文本内容',
  `content_type` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'text' COMMENT 'text/sticker',
  `sticker_id` bigint DEFAULT NULL,
  `sticker_url` varchar(2048) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '贴图URL，P1可为空',
  `pinned` tinyint(1) NOT NULL DEFAULT '0' COMMENT '置顶标记',
  `like_count` int NOT NULL DEFAULT '0' COMMENT '点赞数',
  `dislike_count` int NOT NULL DEFAULT '0',
  `deleted` tinyint(1) NOT NULL DEFAULT '0',
  `deleted_time` datetime DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_comment_post` (`id`,`post_id`),
  KEY `idx_post_comments` (`post_id`,`parent_id`,`create_time` DESC,`id` DESC),
  KEY `idx_parent_post` (`parent_id`,`post_id`),
  KEY `idx_comment_author` (`author_id`,`create_time` DESC),
  KEY `idx_comment_reply_to` (`reply_to_id`),
  CONSTRAINT `fk_comment_parent` FOREIGN KEY (`parent_id`, `post_id`) REFERENCES `comment` (`id`, `post_id`) ON DELETE CASCADE,
  CONSTRAINT `fk_comment_post` FOREIGN KEY (`post_id`) REFERENCES `post` (`id`) ON DELETE CASCADE,
  CONSTRAINT `chk_comment_reaction_counts` CHECK (((`like_count` >= 0) and (`dislike_count` >= 0))),
  CONSTRAINT `chk_comment_type` CHECK ((`content_type` in (_utf8mb4'text',_utf8mb4'image',_utf8mb4'sticker')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='评论及一级回复';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `comment_media`
--

DROP TABLE IF EXISTS `comment_media`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `comment_media` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `comment_id` bigint NOT NULL,
  `asset_id` bigint NOT NULL,
  `url` varchar(2048) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `width` int DEFAULT NULL,
  `height` int DEFAULT NULL,
  `sort_order` int NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_comment_media_asset` (`asset_id`),
  UNIQUE KEY `uk_comment_media_order` (`comment_id`,`sort_order`),
  CONSTRAINT `fk_comment_media_asset` FOREIGN KEY (`asset_id`) REFERENCES `upload_asset` (`id`),
  CONSTRAINT `fk_comment_media_comment` FOREIGN KEY (`comment_id`) REFERENCES `comment` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='评论图片';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `comment_reaction`
--

DROP TABLE IF EXISTS `comment_reaction`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `comment_reaction` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `comment_id` bigint NOT NULL,
  `user_id` bigint NOT NULL,
  `reaction` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_comment_reaction_user` (`comment_id`,`user_id`),
  KEY `idx_comment_reaction_user` (`user_id`,`update_time` DESC),
  CONSTRAINT `fk_comment_reaction_comment` FOREIGN KEY (`comment_id`) REFERENCES `comment` (`id`) ON DELETE CASCADE,
  CONSTRAINT `chk_comment_reaction` CHECK ((`reaction` in (_utf8mb4'like',_utf8mb4'dislike')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='评论赞踩，不计入用户总获赞';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `community_level_config`
--

DROP TABLE IF EXISTS `community_level_config`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `community_level_config` (
  `id` tinyint NOT NULL,
  `version` bigint NOT NULL DEFAULT '0',
  `config_json` json NOT NULL,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  CONSTRAINT `chk_level_singleton` CHECK ((`id` = 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `divider_style`
--

DROP TABLE IF EXISTS `divider_style`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `divider_style` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `preview_text` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '────────',
  `sort_order` int NOT NULL DEFAULT '0',
  `enabled` tinyint(1) NOT NULL DEFAULT '1',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='分割线样式';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `emoji_group`
--

DROP TABLE IF EXISTS `emoji_group`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `emoji_group` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `sort_order` int NOT NULL DEFAULT '0',
  `enabled` tinyint(1) NOT NULL DEFAULT '1',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='图片表情分组';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `home_channel`
--

DROP TABLE IF EXISTS `home_channel`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `home_channel` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `sort_order` int NOT NULL DEFAULT '0',
  `enabled` tinyint(1) NOT NULL DEFAULT '1',
  `is_default` tinyint(1) NOT NULL DEFAULT '0',
  `admin_only` tinyint(1) NOT NULL DEFAULT '0' COMMENT 'Only official accounts may publish',
  `show_all_posts` tinyint(1) NOT NULL DEFAULT '0' COMMENT 'Show all published posts',
  `allow_publish` tinyint(1) NOT NULL DEFAULT '1' COMMENT 'Allow publishing into channel',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='首页频道Tab';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `home_entry`
--

DROP TABLE IF EXISTS `home_entry`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `home_entry` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `icon_url` varchar(2048) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '',
  `target_type` varchar(10) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'link' COMMENT 'link: web URL; app: internal page',
  `link_url` varchar(2048) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '',
  `open_mode` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'inapp',
  `show_topbar` tinyint(1) NOT NULL DEFAULT '1',
  `sort_order` int NOT NULL DEFAULT '0',
  `enabled` tinyint(1) NOT NULL DEFAULT '1',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  CONSTRAINT `chk_entry_mode` CHECK ((`open_mode` in (_utf8mb4'inapp',_utf8mb4'browser')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='首页快捷入口';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `login_account`
--

DROP TABLE IF EXISTS `login_account`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `login_account` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `account` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'trim后的小写登录名',
  `owner_type` tinyint NOT NULL COMMENT '0 app_user，1 admin_user',
  `owner_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_login_account` (`account`),
  UNIQUE KEY `uk_login_owner` (`owner_type`,`owner_id`),
  CONSTRAINT `chk_login_owner` CHECK ((`owner_type` in (0,1)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `media_cleanup_lock`
--

DROP TABLE IF EXISTS `media_cleanup_lock`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `media_cleanup_lock` (
  `id` int NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `media_cleanup_task`
--

DROP TABLE IF EXISTS `media_cleanup_task`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `media_cleanup_task` (
  `id` varchar(64) NOT NULL,
  `storage_id` varchar(36) NOT NULL,
  `object_key` varchar(512) NOT NULL,
  `public_url` varchar(2048) NOT NULL,
  `completed` tinyint(1) NOT NULL DEFAULT '0',
  `attempts` int NOT NULL DEFAULT '0',
  `next_attempt_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `lease_until` datetime DEFAULT NULL,
  `worker_token` varchar(36) DEFAULT NULL,
  `error_message` varchar(255) NOT NULL DEFAULT '',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `force_delete` tinyint(1) NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_cleanup_due` (`completed`,`next_attempt_at`,`lease_until`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `media_job`
--

DROP TABLE IF EXISTS `media_job`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `media_job` (
  `asset_id` bigint NOT NULL,
  `config_json` text NOT NULL,
  `state` varchar(16) NOT NULL DEFAULT 'queued',
  `attempts` int NOT NULL DEFAULT '0',
  `lease_until` datetime DEFAULT NULL,
  `worker_token` varchar(36) DEFAULT NULL,
  `error_message` varchar(255) NOT NULL DEFAULT '',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`asset_id`),
  KEY `idx_media_job` (`state`,`lease_until`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `media_object`
--

DROP TABLE IF EXISTS `media_object`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `media_object` (
  `id` varchar(36) NOT NULL,
  `storage_id` varchar(36) NOT NULL,
  `object_key` varchar(512) NOT NULL,
  `public_url` varchar(2048) NOT NULL,
  `content_type` varchar(128) NOT NULL,
  `size_bytes` bigint NOT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_object_key` (`storage_id`,`object_key`),
  KEY `idx_object_storage` (`storage_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `media_resource_retention`
--

DROP TABLE IF EXISTS `media_resource_retention`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `media_resource_retention` (
  `id` varchar(64) NOT NULL,
  `storage_id` varchar(36) NOT NULL,
  `object_key` varchar(512) NOT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `media_storage_config`
--

DROP TABLE IF EXISTS `media_storage_config`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `media_storage_config` (
  `id` varchar(36) NOT NULL,
  `config_json` mediumtext NOT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `membership_config`
--

DROP TABLE IF EXISTS `membership_config`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `membership_config` (
  `id` int NOT NULL,
  `version` bigint NOT NULL,
  `config_json` longtext NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `membership_frame_grant`
--

DROP TABLE IF EXISTS `membership_frame_grant`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `membership_frame_grant` (
  `user_id` bigint NOT NULL,
  `frame_id` varchar(32) NOT NULL,
  `expires_at` datetime(3) NOT NULL,
  PRIMARY KEY (`user_id`,`frame_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `membership_order`
--

DROP TABLE IF EXISTS `membership_order`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `membership_order` (
  `id` varchar(40) NOT NULL,
  `user_id` bigint NOT NULL,
  `request_key` varchar(80) NOT NULL,
  `plan_id` varchar(28) NOT NULL,
  `plan_json` longtext NOT NULL,
  `agreement_text` longtext NOT NULL,
  `profile_id` varchar(36) NOT NULL,
  `channel` varchar(8) NOT NULL,
  `amount_fen` bigint NOT NULL,
  `status` varchar(12) NOT NULL,
  `trade_no` varchar(80) DEFAULT NULL,
  `error_message` varchar(300) NOT NULL DEFAULT '',
  `created_at` datetime(3) NOT NULL,
  `expires_at` datetime(3) NOT NULL,
  `paid_at` datetime(3) DEFAULT NULL,
  `granted_at` datetime(3) DEFAULT NULL,
  `grant_start` datetime(3) DEFAULT NULL,
  `grant_end` datetime(3) DEFAULT NULL,
  `next_check_at` datetime(3) NOT NULL,
  `attempts` int NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_membership_request` (`user_id`,`request_key`),
  UNIQUE KEY `uk_membership_trade` (`trade_no`),
  KEY `idx_membership_reconcile` (`status`,`next_check_at`),
  KEY `idx_membership_user` (`user_id`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `membership_payment_active`
--

DROP TABLE IF EXISTS `membership_payment_active`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `membership_payment_active` (
  `channel` varchar(8) NOT NULL,
  `profile_id` varchar(36) NOT NULL,
  PRIMARY KEY (`channel`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `membership_payment_profile`
--

DROP TABLE IF EXISTS `membership_payment_profile`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `membership_payment_profile` (
  `id` varchar(36) NOT NULL,
  `channel` varchar(8) NOT NULL,
  `config_json` longtext NOT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `mine_entry`
--

DROP TABLE IF EXISTS `mine_entry`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `mine_entry` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `title` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL,
  `description` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL,
  `icon_url` varchar(2048) COLLATE utf8mb4_unicode_ci NOT NULL,
  `target_type` varchar(10) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'link' COMMENT 'link: web URL; app: internal page',
  `link_url` varchar(2048) COLLATE utf8mb4_unicode_ci NOT NULL,
  `open_mode` varchar(10) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'inapp',
  `show_topbar` tinyint(1) NOT NULL DEFAULT '1',
  `sort_order` int NOT NULL DEFAULT '0',
  `enabled` tinyint(1) NOT NULL DEFAULT '1',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_mine_entry_enabled_sort` (`enabled`,`sort_order`,`id`),
  CONSTRAINT `chk_mine_entry_mode` CHECK ((`open_mode` in (_utf8mb4'inapp',_utf8mb4'browser')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='我的页面快捷入口';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `notice`
--

DROP TABLE IF EXISTS `notice`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `notice` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `category_id` bigint NOT NULL,
  `title` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `blocks` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci,
  `post_id` bigint DEFAULT NULL,
  `status` tinyint NOT NULL DEFAULT '0',
  `publish_time` datetime DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `audience_type` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'all',
  `push_enabled` tinyint(1) NOT NULL DEFAULT '0',
  `publication_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_notice_cat_status` (`category_id`,`status`,`publish_time` DESC),
  CONSTRAINT `fk_notice_cat` FOREIGN KEY (`category_id`) REFERENCES `notice_category` (`id`),
  CONSTRAINT `chk_notice_status` CHECK ((`status` in (0,1)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='通知内容';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `notice_category`
--

DROP TABLE IF EXISTS `notice_category`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `notice_category` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `icon_url` varchar(2048) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '',
  `sort_order` int NOT NULL DEFAULT '0',
  `enabled` tinyint(1) NOT NULL DEFAULT '1',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `recipient_policy` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'all',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='通知分类';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `notice_category_recipient`
--

DROP TABLE IF EXISTS `notice_category_recipient`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `notice_category_recipient` (
  `category_id` bigint NOT NULL,
  `user_id` bigint NOT NULL,
  PRIMARY KEY (`category_id`,`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `notice_draft_recipient`
--

DROP TABLE IF EXISTS `notice_draft_recipient`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `notice_draft_recipient` (
  `notice_id` bigint NOT NULL,
  `user_id` bigint NOT NULL,
  PRIMARY KEY (`notice_id`,`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `notice_publication`
--

DROP TABLE IF EXISTS `notice_publication`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `notice_publication` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `notice_id` bigint NOT NULL,
  `category_id` bigint NOT NULL,
  `audience_type` varchar(16) NOT NULL,
  `unread_enabled` tinyint(1) NOT NULL DEFAULT '1',
  `published_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  KEY `idx_publication_notice` (`notice_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `notice_publication_recipient`
--

DROP TABLE IF EXISTS `notice_publication_recipient`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `notice_publication_recipient` (
  `publication_id` bigint NOT NULL,
  `user_id` bigint NOT NULL,
  PRIMARY KEY (`publication_id`,`user_id`),
  KEY `idx_notice_recipient_user` (`user_id`,`publication_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `notice_push_config`
--

DROP TABLE IF EXISTS `notice_push_config`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `notice_push_config` (
  `id` int NOT NULL,
  `enabled` tinyint(1) NOT NULL DEFAULT '0',
  `app_id` varchar(100) NOT NULL DEFAULT '',
  `endpoint` varchar(2048) NOT NULL DEFAULT '',
  `secret_cipher` text NOT NULL,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `notice_push_device`
--

DROP TABLE IF EXISTS `notice_push_device`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `notice_push_device` (
  `cid` varchar(128) NOT NULL,
  `user_id` bigint NOT NULL,
  `token_version` int NOT NULL,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`cid`),
  KEY `idx_notice_device_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `notice_push_task`
--

DROP TABLE IF EXISTS `notice_push_task`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `notice_push_task` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `publication_id` bigint NOT NULL,
  `user_id` bigint NOT NULL,
  `cid` varchar(128) NOT NULL,
  `token_version` int NOT NULL,
  `request_id` varchar(32) NOT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'pending',
  `attempts` int NOT NULL DEFAULT '0',
  `next_attempt_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `last_error` varchar(255) NOT NULL DEFAULT '',
  `lease_until` datetime DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_notice_push_device` (`publication_id`,`cid`),
  UNIQUE KEY `uk_notice_push_request` (`request_id`),
  KEY `idx_notice_push_pending` (`status`,`next_attempt_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `notice_read`
--

DROP TABLE IF EXISTS `notice_read`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `notice_read` (
  `publication_id` bigint NOT NULL,
  `user_id` bigint NOT NULL,
  `read_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`publication_id`,`user_id`),
  KEY `idx_notice_read_user` (`user_id`,`publication_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `post`
--

DROP TABLE IF EXISTS `post`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `post` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '帖子ID',
  `author_id` bigint DEFAULT NULL COMMENT '未来APP用户ID，P1为空',
  `author_nickname` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '作者昵称快照',
  `author_level` int NOT NULL DEFAULT '1' COMMENT '作者等级快照',
  `author_bio` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '作者简介快照',
  `mode` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'image',
  `channel_id` bigint DEFAULT NULL,
  `title` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '标题',
  `content` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT '正文',
  `content_blocks` json DEFAULT NULL,
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '0待审核 1已发布 2已下架',
  `home_news_enabled` tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否参与首页资讯轮播',
  `collection` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '合集展示信息',
  `permission` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '转载权限展示信息',
  `allow_download` tinyint(1) NOT NULL DEFAULT '1',
  `download_watermark` tinyint(1) NOT NULL DEFAULT '0',
  `original` tinyint(1) NOT NULL DEFAULT '0',
  `like_count` int NOT NULL DEFAULT '0' COMMENT '点赞展示计数',
  `favorite_count` int NOT NULL DEFAULT '0' COMMENT '收藏展示计数',
  `comment_count` int NOT NULL DEFAULT '0' COMMENT '全部评论数，含一级回复',
  `share_count` int NOT NULL DEFAULT '0' COMMENT '分享展示计数',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `checkin_recommended` tinyint(1) NOT NULL DEFAULT '0',
  `repost_of_post_id` bigint DEFAULT NULL,
  `profile_pinned_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_status_create` (`status`,`create_time` DESC,`id` DESC),
  KEY `idx_home_news` (`home_news_enabled`,`status`,`create_time` DESC,`id` DESC),
  KEY `idx_post_channel_status` (`channel_id`,`status`,`create_time` DESC,`id` DESC),
  KEY `idx_post_repost` (`repost_of_post_id`),
  CONSTRAINT `fk_post_channel` FOREIGN KEY (`channel_id`) REFERENCES `home_channel` (`id`),
  CONSTRAINT `chk_post_mode` CHECK ((`mode` in (_utf8mb4'image',_utf8mb4'video',_utf8mb4'post'))),
  CONSTRAINT `chk_post_status` CHECK ((`status` in (0,1,2)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='帖子';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `post_draft`
--

DROP TABLE IF EXISTS `post_draft`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `post_draft` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `author_id` bigint NOT NULL,
  `mode` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `title` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '',
  `summary` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '',
  `payload` json NOT NULL,
  `revision` bigint NOT NULL DEFAULT '1',
  `cover_url` varchar(2048) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_draft_author` (`author_id`,`update_time` DESC),
  CONSTRAINT `chk_draft_mode` CHECK ((`mode` in (_utf8mb4'image',_utf8mb4'video',_utf8mb4'post')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='云端发布草稿';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `post_media`
--

DROP TABLE IF EXISTS `post_media`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `post_media` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '媒体ID',
  `post_id` bigint NOT NULL COMMENT '帖子ID',
  `asset_id` bigint DEFAULT NULL,
  `type` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'image/video',
  `layout` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'square/portrait/wide',
  `aspect_ratio` double NOT NULL COMMENT '宽高比',
  `width` int DEFAULT NULL,
  `height` int DEFAULT NULL,
  `duration` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '视频时长',
  `duration_ms` bigint DEFAULT NULL,
  `url` varchar(2048) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '媒体URL，P1允许为空',
  `cover_url` varchar(2048) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '',
  `cover_time_ms` bigint DEFAULT NULL,
  `crop_json` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '',
  `sort_order` int NOT NULL DEFAULT '0' COMMENT '展示顺序',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_media_order` (`post_id`,`sort_order`),
  CONSTRAINT `fk_media_post` FOREIGN KEY (`post_id`) REFERENCES `post` (`id`) ON DELETE CASCADE,
  CONSTRAINT `chk_media_layout` CHECK ((`layout` in (_utf8mb4'square',_utf8mb4'portrait',_utf8mb4'wide'))),
  CONSTRAINT `chk_media_ratio` CHECK ((`aspect_ratio` > 0)),
  CONSTRAINT `chk_media_type` CHECK ((`type` in (_utf8mb4'image',_utf8mb4'video')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='帖子媒体';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `post_poll`
--

DROP TABLE IF EXISTS `post_poll`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `post_poll` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `post_id` bigint NOT NULL,
  `question` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '',
  `ends_at` datetime DEFAULT NULL,
  `total_votes` int NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_poll_post` (`post_id`),
  CONSTRAINT `fk_poll_post` FOREIGN KEY (`post_id`) REFERENCES `post` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='帖子投票';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `post_poll_option`
--

DROP TABLE IF EXISTS `post_poll_option`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `post_poll_option` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `poll_id` bigint NOT NULL,
  `option_text` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `vote_count` int NOT NULL DEFAULT '0',
  `sort_order` int NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_poll_option_order` (`poll_id`,`sort_order`),
  CONSTRAINT `fk_poll_option_poll` FOREIGN KEY (`poll_id`) REFERENCES `post_poll` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='投票选项';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `post_poll_vote`
--

DROP TABLE IF EXISTS `post_poll_vote`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `post_poll_vote` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `poll_id` bigint NOT NULL,
  `option_id` bigint NOT NULL,
  `user_id` bigint NOT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_poll_user` (`poll_id`,`user_id`),
  KEY `fk_vote_option` (`option_id`),
  CONSTRAINT `fk_vote_option` FOREIGN KEY (`option_id`) REFERENCES `post_poll_option` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_vote_poll` FOREIGN KEY (`poll_id`) REFERENCES `post_poll` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='投票记录';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `post_tag`
--

DROP TABLE IF EXISTS `post_tag`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `post_tag` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '标签ID',
  `post_id` bigint NOT NULL COMMENT '帖子ID',
  `name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '标签文字',
  `sort_order` int NOT NULL DEFAULT '0' COMMENT '展示顺序',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tag_order` (`post_id`,`sort_order`),
  CONSTRAINT `fk_tag_post` FOREIGN KEY (`post_id`) REFERENCES `post` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='帖子标签';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `post_topic`
--

DROP TABLE IF EXISTS `post_topic`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `post_topic` (
  `post_id` bigint NOT NULL,
  `topic_id` bigint NOT NULL,
  `sort_order` int NOT NULL DEFAULT '0',
  PRIMARY KEY (`post_id`,`topic_id`),
  KEY `idx_post_topic_topic` (`topic_id`,`post_id`),
  CONSTRAINT `fk_post_topic_post` FOREIGN KEY (`post_id`) REFERENCES `post` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_post_topic_topic` FOREIGN KEY (`topic_id`) REFERENCES `topic` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='帖子话题关联';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `publish_emoji`
--

DROP TABLE IF EXISTS `publish_emoji`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `publish_emoji` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `group_id` bigint NOT NULL,
  `name` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `image_url` varchar(2048) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `sort_order` int NOT NULL DEFAULT '0',
  `enabled` tinyint(1) NOT NULL DEFAULT '1',
  PRIMARY KEY (`id`),
  KEY `fk_emoji_group` (`group_id`),
  CONSTRAINT `fk_emoji_group` FOREIGN KEY (`group_id`) REFERENCES `emoji_group` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='图片表情';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `publish_rule`
--

DROP TABLE IF EXISTS `publish_rule`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `publish_rule` (
  `mode` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `title_min` int NOT NULL DEFAULT '0',
  `title_max` int NOT NULL DEFAULT '30',
  `content_min` int NOT NULL DEFAULT '0',
  `content_max` int NOT NULL DEFAULT '2000',
  `image_max` int NOT NULL DEFAULT '0',
  `video_max` int NOT NULL DEFAULT '0',
  `total_media_max` int NOT NULL DEFAULT '0',
  `max_video_mb` int NOT NULL DEFAULT '0',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`mode`),
  CONSTRAINT `chk_publish_rule_mode` CHECK ((`mode` in (_utf8mb4'image',_utf8mb4'video',_utf8mb4'post')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='分模式发布限制';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `report_option`
--

DROP TABLE IF EXISTS `report_option`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `report_option` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `kind` varchar(10) NOT NULL,
  `name` varchar(100) NOT NULL,
  `sort_order` int NOT NULL DEFAULT '0',
  `deleted` tinyint(1) NOT NULL DEFAULT '0',
  `seed_key` varchar(40) DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_report_seed` (`seed_key`),
  KEY `idx_report_options` (`kind`,`deleted`,`sort_order`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `shop_order`
--

DROP TABLE IF EXISTS `shop_order`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `shop_order` (
  `id` char(33) NOT NULL,
  `user_id` bigint NOT NULL,
  `product_id` bigint NOT NULL,
  `request_key` varchar(80) NOT NULL,
  `product_json` json NOT NULL,
  `method` varchar(10) NOT NULL,
  `amount_fen` bigint NOT NULL,
  `amount_coins` decimal(20,1) NOT NULL,
  `profile_id` varchar(40) DEFAULT NULL,
  `channel` varchar(4) DEFAULT NULL,
  `status` varchar(10) NOT NULL,
  `trade_no` varchar(80) DEFAULT NULL,
  `error_message` varchar(200) NOT NULL DEFAULT '',
  `created_at` datetime NOT NULL,
  `expires_at` datetime NOT NULL,
  `next_check_at` datetime NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_shop_request` (`user_id`,`request_key`),
  UNIQUE KEY `trade_no` (`trade_no`),
  KEY `idx_shop_pending` (`user_id`,`product_id`,`status`),
  KEY `idx_shop_reconcile` (`status`,`next_check_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `shop_product`
--

DROP TABLE IF EXISTS `shop_product`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `shop_product` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `sku` varchar(48) NOT NULL,
  `version` bigint NOT NULL DEFAULT '1',
  `name` varchar(80) NOT NULL,
  `description` varchar(500) NOT NULL,
  `images_json` json NOT NULL,
  `frame_url` varchar(2048) NOT NULL,
  `price_fen` bigint NOT NULL,
  `discounts_json` json NOT NULL,
  `sort_order` int NOT NULL,
  `enabled` tinyint(1) NOT NULL,
  `sold_count` bigint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `sku` (`sku`),
  KEY `idx_shop_catalog` (`enabled`,`sort_order`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `social_notification`
--

DROP TABLE IF EXISTS `social_notification`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `social_notification` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `recipient_id` bigint NOT NULL,
  `actor_id` bigint NOT NULL,
  `type` varchar(20) NOT NULL,
  `post_id` bigint DEFAULT NULL,
  `comment_id` bigint DEFAULT NULL,
  `business_key` varchar(160) NOT NULL,
  `read_at` datetime DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_social_delivery` (`recipient_id`,`business_key`),
  KEY `idx_social_inbox` (`recipient_id`,`type`,`create_time` DESC,`id` DESC),
  KEY `idx_social_unread` (`recipient_id`,`read_at`),
  CONSTRAINT `chk_social_type` CHECK ((`type` in (_utf8mb4'reply',_utf8mb4'mention',_utf8mb4'repost',_utf8mb4'post_like',_utf8mb4'comment_like',_utf8mb4'follow')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `social_publication`
--

DROP TABLE IF EXISTS `social_publication`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `social_publication` (
  `post_id` bigint NOT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `system_config`
--

DROP TABLE IF EXISTS `system_config`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `system_config` (
  `config_key` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `config_value` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '',
  `remark` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`config_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统配置';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `text_color`
--

DROP TABLE IF EXISTS `text_color`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `text_color` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `color_value` char(7) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `sort_order` int NOT NULL DEFAULT '0',
  `enabled` tinyint(1) NOT NULL DEFAULT '1',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_text_color` (`color_value`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文字色板';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `topic`
--

DROP TABLE IF EXISTS `topic`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `topic` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `description` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '',
  `cover_url` varchar(2048) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '0待审核 1通过 2拒绝',
  `recommended` tinyint(1) NOT NULL DEFAULT '0',
  `hot` tinyint(1) NOT NULL DEFAULT '0',
  `enabled` tinyint(1) NOT NULL DEFAULT '1',
  `sort_order` int NOT NULL DEFAULT '0',
  `creator_id` bigint DEFAULT NULL,
  `view_count` bigint NOT NULL DEFAULT '0',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_topic_name` (`name`),
  KEY `idx_topic_visible` (`status`,`enabled`,`recommended`,`sort_order`),
  CONSTRAINT `chk_topic_status` CHECK ((`status` in (0,1,2)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='发布话题';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `upload_asset`
--

DROP TABLE IF EXISTS `upload_asset`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `upload_asset` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `owner_id` bigint NOT NULL,
  `media_type` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `url` varchar(2048) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `original_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '',
  `content_type` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '',
  `size_bytes` bigint NOT NULL DEFAULT '0',
  `width` int DEFAULT NULL,
  `height` int DEFAULT NULL,
  `duration_ms` bigint DEFAULT NULL,
  `cover_url` varchar(2048) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '',
  `cover_time_ms` bigint DEFAULT NULL,
  `crop_json` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '',
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'ready',
  `error_message` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '',
  `attached` tinyint(1) NOT NULL DEFAULT '0',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `preview_url` varchar(2048) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '',
  `original_url` varchar(2048) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '',
  `hls_url` varchar(2048) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '',
  `qualities_json` text COLLATE utf8mb4_unicode_ci,
  `resource_version` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '',
  PRIMARY KEY (`id`),
  KEY `idx_asset_owner` (`owner_id`,`attached`,`create_time`),
  CONSTRAINT `chk_asset_status` CHECK ((`status` in (_utf8mb4'processing',_utf8mb4'ready',_utf8mb4'failed'))),
  CONSTRAINT `chk_asset_type` CHECK ((`media_type` in (_utf8mb4'image',_utf8mb4'video')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户上传媒体';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `user_avatar_frame`
--

DROP TABLE IF EXISTS `user_avatar_frame`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user_avatar_frame` (
  `user_id` bigint NOT NULL,
  `frame_id` varchar(32) NOT NULL,
  `expires_at` datetime DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`user_id`,`frame_id`),
  CONSTRAINT `fk_frame_user` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `user_block`
--

DROP TABLE IF EXISTS `user_block`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user_block` (
  `owner_id` bigint NOT NULL,
  `target_id` bigint NOT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`owner_id`,`target_id`),
  KEY `idx_block_target` (`target_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `user_coin_ledger`
--

DROP TABLE IF EXISTS `user_coin_ledger`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user_coin_ledger` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `event_key` varchar(160) NOT NULL,
  `amount` decimal(20,1) NOT NULL,
  `balance_after` decimal(20,1) NOT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_coin_event` (`user_id`,`event_key`),
  CONSTRAINT `fk_coin_user` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `user_coin_punch`
--

DROP TABLE IF EXISTS `user_coin_punch`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user_coin_punch` (
  `user_id` bigint NOT NULL,
  `punch_date` date NOT NULL,
  `points` bigint NOT NULL,
  `coins` decimal(20,1) NOT NULL,
  `streak_days` int NOT NULL,
  PRIMARY KEY (`user_id`,`punch_date`),
  CONSTRAINT `fk_coin_punch_user` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `user_favorite`
--

DROP TABLE IF EXISTS `user_favorite`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user_favorite` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `post_id` bigint NOT NULL COMMENT '帖子ID',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_post` (`user_id`,`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户收藏';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `user_follow`
--

DROP TABLE IF EXISTS `user_follow`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user_follow` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `follower_id` bigint NOT NULL COMMENT '关注者',
  `followee_id` bigint NOT NULL COMMENT '被关注者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_pair` (`follower_id`,`followee_id`),
  CONSTRAINT `chk_follow_self` CHECK ((`follower_id` <> `followee_id`))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户关注（P2预留）';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `user_level_ledger`
--

DROP TABLE IF EXISTS `user_level_ledger`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user_level_ledger` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `action` varchar(32) NOT NULL,
  `event_key` varchar(160) NOT NULL,
  `amount` bigint NOT NULL,
  `earned_date` date NOT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `operator_id` bigint DEFAULT NULL,
  `reason` varchar(200) DEFAULT NULL,
  `before_experience` bigint DEFAULT NULL,
  `after_experience` bigint DEFAULT NULL,
  `before_level` int DEFAULT NULL,
  `after_level` int DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_level_event` (`user_id`,`action`,`event_key`),
  KEY `idx_level_daily` (`user_id`,`earned_date`,`action`),
  CONSTRAINT `fk_level_ledger_user` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `user_level_signin`
--

DROP TABLE IF EXISTS `user_level_signin`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user_level_signin` (
  `user_id` bigint NOT NULL,
  `sign_date` date NOT NULL,
  `streak_days` int NOT NULL,
  `makeup` tinyint(1) NOT NULL DEFAULT '0',
  `reward_json` json DEFAULT NULL,
  `config_version` bigint DEFAULT NULL,
  `claimed_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`user_id`,`sign_date`),
  CONSTRAINT `fk_level_signin_user` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `user_like`
--

DROP TABLE IF EXISTS `user_like`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user_like` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `post_id` bigint NOT NULL COMMENT '帖子ID',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_post` (`user_id`,`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户点赞';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `user_membership`
--

DROP TABLE IF EXISTS `user_membership`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user_membership` (
  `user_id` bigint NOT NULL,
  `plan_id` varchar(28) NOT NULL,
  `expires_at` datetime(3) NOT NULL,
  PRIMARY KEY (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `user_post_history`
--

DROP TABLE IF EXISTS `user_post_history`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user_post_history` (
  `user_id` bigint NOT NULL,
  `post_id` bigint NOT NULL,
  `viewed_at` datetime(6) NOT NULL,
  PRIMARY KEY (`user_id`,`post_id`),
  KEY `idx_post_history_recent` (`user_id`,`viewed_at` DESC,`post_id` DESC),
  KEY `fk_post_history_post` (`post_id`),
  CONSTRAINT `fk_post_history_post` FOREIGN KEY (`post_id`) REFERENCES `post` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_post_history_user` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `user_report`
--

DROP TABLE IF EXISTS `user_report`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user_report` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `reporter_id` bigint NOT NULL,
  `reported_user_id` bigint NOT NULL,
  `target_kind` varchar(10) NOT NULL,
  `target_id` bigint NOT NULL,
  `type_id` bigint NOT NULL,
  `reason_id` bigint NOT NULL,
  `type_name` varchar(100) NOT NULL,
  `reason_name` varchar(100) NOT NULL,
  `content` varchar(800) NOT NULL,
  `reporter_name` varchar(100) NOT NULL,
  `reported_user_name` varchar(100) NOT NULL,
  `target_snapshot` longtext NOT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_reports_time` (`create_time`,`id`),
  KEY `idx_reports_reporter` (`reporter_id`),
  KEY `idx_reports_target` (`reported_user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `user_report_image`
--

DROP TABLE IF EXISTS `user_report_image`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user_report_image` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `report_id` bigint NOT NULL,
  `asset_id` bigint NOT NULL,
  `url` varchar(2048) NOT NULL,
  `sort_order` int NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_report_image` (`report_id`,`asset_id`),
  KEY `idx_report_image_asset` (`asset_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `verify_code`
--

DROP TABLE IF EXISTS `verify_code`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `verify_code` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `email` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '目标邮箱',
  `code` varchar(6) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '6位数字验证码',
  `expire_time` datetime NOT NULL COMMENT '过期时间',
  `used` tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否已使用',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_email_create` (`email`,`create_time` DESC),
  CONSTRAINT `chk_code_used` CHECK ((`used` in (0,1)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='邮箱验证码';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping routines for database 'ciyuantown'
--
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

-- Dump completed on 2026-11-06 22:36:27
