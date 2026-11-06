-- P5 帖子发布系统；MySQL 8。请先备份并仅执行一次。
USE ciyuantown;
SET NAMES utf8mb4;

ALTER TABLE app_user ADD COLUMN public_uid BIGINT NULL COMMENT '对外公开UID，从1000开始' AFTER id,
 ADD COLUMN token_version INT NOT NULL DEFAULT 0 COMMENT '会话版本' AFTER status;
UPDATE app_user u
JOIN (SELECT id,999+ROW_NUMBER() OVER(ORDER BY id) AS assigned_uid FROM app_user) ranked ON ranked.id=u.id
SET u.public_uid=ranked.assigned_uid
WHERE u.public_uid IS NULL;
-- 应用注册事务会在拿到自增主键后立即写入 public_uid；保留 NULL 兼容运维脚本/测试夹具，
-- 唯一索引仍保证所有正式账号 UID 不重复。
ALTER TABLE app_user ADD UNIQUE KEY uk_app_user_public_uid(public_uid);

ALTER TABLE home_channel ADD COLUMN is_default BOOLEAN NOT NULL DEFAULT FALSE AFTER enabled;
UPDATE home_channel SET is_default=(id=(SELECT default_id FROM (SELECT MIN(id) default_id FROM home_channel WHERE enabled=TRUE) seed));

ALTER TABLE `post`
 ADD COLUMN mode VARCHAR(10) NOT NULL DEFAULT 'image' AFTER author_bio,
 ADD COLUMN channel_id BIGINT NULL AFTER mode,
 ADD COLUMN content_blocks JSON NULL AFTER content,
 ADD COLUMN allow_download BOOLEAN NOT NULL DEFAULT TRUE AFTER permission,
 ADD COLUMN download_watermark BOOLEAN NOT NULL DEFAULT FALSE AFTER allow_download,
 ADD COLUMN original BOOLEAN NOT NULL DEFAULT FALSE AFTER download_watermark,
 ADD CONSTRAINT chk_post_mode CHECK(mode IN ('image','video','post')),
 ADD KEY idx_post_channel_status(channel_id,status,create_time DESC,id DESC),
 ADD CONSTRAINT fk_post_channel FOREIGN KEY(channel_id) REFERENCES home_channel(id);

UPDATE `post` SET channel_id=(SELECT MIN(id) FROM home_channel WHERE enabled=TRUE) WHERE channel_id IS NULL;

ALTER TABLE post_media
 ADD COLUMN asset_id BIGINT NULL AFTER post_id,
 ADD COLUMN width INT NULL AFTER aspect_ratio,
 ADD COLUMN height INT NULL AFTER width,
 ADD COLUMN duration_ms BIGINT NULL AFTER duration,
 ADD COLUMN cover_url VARCHAR(2048) NOT NULL DEFAULT '' AFTER url,
 ADD COLUMN cover_time_ms BIGINT NULL AFTER cover_url,
 ADD COLUMN crop_json VARCHAR(255) NOT NULL DEFAULT '' AFTER cover_time_ms;

CREATE TABLE publish_rule (
 mode VARCHAR(10) NOT NULL, title_min INT NOT NULL DEFAULT 0, title_max INT NOT NULL DEFAULT 30,
 content_min INT NOT NULL DEFAULT 0, content_max INT NOT NULL DEFAULT 2000,
 image_max INT NOT NULL DEFAULT 0, video_max INT NOT NULL DEFAULT 0, total_media_max INT NOT NULL DEFAULT 0,
 max_video_mb INT NOT NULL DEFAULT 0, update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 PRIMARY KEY(mode), CONSTRAINT chk_publish_rule_mode CHECK(mode IN ('image','video','post'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='分模式发布限制';
INSERT INTO publish_rule(mode,title_min,title_max,content_min,content_max,image_max,video_max,total_media_max,max_video_mb) VALUES
 ('image',0,30,0,2000,9,0,9,0),('video',0,30,0,2000,0,1,1,0),('post',1,30,1,30000,9,3,12,0);

INSERT INTO system_config(config_key,config_value,remark) VALUES
 ('publish_review_required','1','新帖子是否先审后发'),
 ('creator_allow_download','1,true','允许下载：默认值,用户可修改'),
 ('creator_watermark','0,true','下载水印：默认值,用户可修改'),
 ('creator_original','0,true','原创：默认值,用户可修改'),
 ('publish_feature_flags','{"emoji":true,"mention":true,"color":true,"divider":true,"link":true,"poll":true}','帖子编辑器功能开关'),
 ('publish_poll_rule','{"minOptions":2,"maxOptions":10}','投票规则')
ON DUPLICATE KEY UPDATE remark=VALUES(remark);

CREATE TABLE topic (
 id BIGINT NOT NULL AUTO_INCREMENT, name VARCHAR(50) NOT NULL, description VARCHAR(255) NOT NULL DEFAULT '',
 cover_url VARCHAR(2048) NOT NULL DEFAULT '', status TINYINT NOT NULL DEFAULT 1 COMMENT '0待审核 1通过 2拒绝',
 recommended BOOLEAN NOT NULL DEFAULT FALSE, hot BOOLEAN NOT NULL DEFAULT FALSE, enabled BOOLEAN NOT NULL DEFAULT TRUE,
 sort_order INT NOT NULL DEFAULT 0, creator_id BIGINT NULL, view_count BIGINT NOT NULL DEFAULT 0,
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 PRIMARY KEY(id), UNIQUE KEY uk_topic_name(name), KEY idx_topic_visible(status,enabled,recommended,sort_order),
 CONSTRAINT chk_topic_status CHECK(status IN (0,1,2))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='发布话题';

INSERT IGNORE INTO topic(name,status,enabled,sort_order)
SELECT DISTINCT name,1,TRUE,0 FROM post_tag WHERE name<>'';

CREATE TABLE post_topic (
 post_id BIGINT NOT NULL, topic_id BIGINT NOT NULL, sort_order INT NOT NULL DEFAULT 0,
 PRIMARY KEY(post_id,topic_id), KEY idx_post_topic_topic(topic_id,post_id),
 CONSTRAINT fk_post_topic_post FOREIGN KEY(post_id) REFERENCES `post`(id) ON DELETE CASCADE,
 CONSTRAINT fk_post_topic_topic FOREIGN KEY(topic_id) REFERENCES topic(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='帖子话题关联';
INSERT IGNORE INTO post_topic(post_id,topic_id,sort_order)
SELECT pt.post_id,t.id,pt.sort_order FROM post_tag pt JOIN topic t ON t.name=pt.name;

CREATE TABLE upload_asset (
 id BIGINT NOT NULL AUTO_INCREMENT, owner_id BIGINT NOT NULL, media_type VARCHAR(10) NOT NULL,
 url VARCHAR(2048) NOT NULL, original_name VARCHAR(255) NOT NULL DEFAULT '', content_type VARCHAR(100) NOT NULL DEFAULT '',
 size_bytes BIGINT NOT NULL DEFAULT 0, width INT NULL, height INT NULL, duration_ms BIGINT NULL,
 cover_url VARCHAR(2048) NOT NULL DEFAULT '', cover_time_ms BIGINT NULL, crop_json VARCHAR(255) NOT NULL DEFAULT '',
 status VARCHAR(16) NOT NULL DEFAULT 'ready', error_message VARCHAR(255) NOT NULL DEFAULT '', attached BOOLEAN NOT NULL DEFAULT FALSE,
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 PRIMARY KEY(id), KEY idx_asset_owner(owner_id,attached,create_time),
 CONSTRAINT chk_asset_type CHECK(media_type IN ('image','video')),
 CONSTRAINT chk_asset_status CHECK(status IN ('processing','ready','failed'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户上传媒体';

CREATE TABLE post_draft (
 id BIGINT NOT NULL AUTO_INCREMENT, author_id BIGINT NOT NULL, mode VARCHAR(10) NOT NULL,
 title VARCHAR(100) NOT NULL DEFAULT '', summary VARCHAR(255) NOT NULL DEFAULT '', payload JSON NOT NULL,
 revision BIGINT NOT NULL DEFAULT 1, cover_url VARCHAR(2048) NOT NULL DEFAULT '',
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 PRIMARY KEY(id), KEY idx_draft_author(author_id,update_time DESC),
 CONSTRAINT chk_draft_mode CHECK(mode IN ('image','video','post'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='云端发布草稿';

CREATE TABLE emoji_group (
 id BIGINT NOT NULL AUTO_INCREMENT, name VARCHAR(30) NOT NULL, sort_order INT NOT NULL DEFAULT 0, enabled BOOLEAN NOT NULL DEFAULT TRUE,
 PRIMARY KEY(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='图片表情分组';
CREATE TABLE publish_emoji (
 id BIGINT NOT NULL AUTO_INCREMENT, group_id BIGINT NOT NULL, name VARCHAR(30) NOT NULL, image_url VARCHAR(2048) NOT NULL,
 sort_order INT NOT NULL DEFAULT 0, enabled BOOLEAN NOT NULL DEFAULT TRUE, PRIMARY KEY(id),
 CONSTRAINT fk_emoji_group FOREIGN KEY(group_id) REFERENCES emoji_group(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='图片表情';
CREATE TABLE text_color (
 id BIGINT NOT NULL AUTO_INCREMENT, name VARCHAR(30) NOT NULL, color_value CHAR(7) NOT NULL,
 sort_order INT NOT NULL DEFAULT 0, enabled BOOLEAN NOT NULL DEFAULT TRUE, PRIMARY KEY(id), UNIQUE KEY uk_text_color(color_value)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文字色板';
INSERT INTO text_color(name,color_value,sort_order) VALUES
 ('黑色','#202124',0),('绿色','#86e600',1),('青色','#22d3b6',2),('黄色','#ffd928',3),('橙色','#ffb31a',4),('红色','#ff5748',5),('粉色','#e84aa4',6);
CREATE TABLE divider_style (
 id BIGINT NOT NULL AUTO_INCREMENT, name VARCHAR(30) NOT NULL, preview_text VARCHAR(100) NOT NULL DEFAULT '────────',
 sort_order INT NOT NULL DEFAULT 0, enabled BOOLEAN NOT NULL DEFAULT TRUE, PRIMARY KEY(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='分割线样式';
INSERT INTO divider_style(name,preview_text,sort_order) VALUES ('实线','────────',0),('虚线','- - - - - - - -',1);

CREATE TABLE post_poll (
 id BIGINT NOT NULL AUTO_INCREMENT, post_id BIGINT NOT NULL, question VARCHAR(200) NOT NULL DEFAULT '', ends_at DATETIME NULL,
 total_votes INT NOT NULL DEFAULT 0, PRIMARY KEY(id), UNIQUE KEY uk_poll_post(post_id),
 CONSTRAINT fk_poll_post FOREIGN KEY(post_id) REFERENCES `post`(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='帖子投票';
CREATE TABLE post_poll_option (
 id BIGINT NOT NULL AUTO_INCREMENT, poll_id BIGINT NOT NULL, option_text VARCHAR(100) NOT NULL, vote_count INT NOT NULL DEFAULT 0,
 sort_order INT NOT NULL DEFAULT 0, PRIMARY KEY(id), UNIQUE KEY uk_poll_option_order(poll_id,sort_order),
 CONSTRAINT fk_poll_option_poll FOREIGN KEY(poll_id) REFERENCES post_poll(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='投票选项';
CREATE TABLE post_poll_vote (
 id BIGINT NOT NULL AUTO_INCREMENT, poll_id BIGINT NOT NULL, option_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY(id), UNIQUE KEY uk_poll_user(poll_id,user_id),
 CONSTRAINT fk_vote_poll FOREIGN KEY(poll_id) REFERENCES post_poll(id) ON DELETE CASCADE,
 CONSTRAINT fk_vote_option FOREIGN KEY(option_id) REFERENCES post_poll_option(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='投票记录';
