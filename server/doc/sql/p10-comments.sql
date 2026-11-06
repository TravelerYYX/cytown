-- P10 评论、回复、图片、图片表情与赞踩；MySQL 8。请先备份并仅执行一次。
USE ciyuantown;
SET NAMES utf8mb4;

ALTER TABLE `comment`
 ADD COLUMN author_id BIGINT NULL AFTER parent_id,
 ADD COLUMN reply_to_id BIGINT NULL AFTER parent_id,
 ADD COLUMN reply_to_nickname VARCHAR(50) NOT NULL DEFAULT '' AFTER reply_to_id,
 ADD COLUMN sticker_id BIGINT NULL AFTER content_type,
 ADD COLUMN dislike_count INT NOT NULL DEFAULT 0 AFTER like_count,
 ADD COLUMN deleted BOOLEAN NOT NULL DEFAULT FALSE AFTER dislike_count,
 ADD COLUMN deleted_time DATETIME NULL AFTER deleted,
 ADD KEY idx_comment_author(author_id,create_time DESC),
 ADD KEY idx_comment_reply_to(reply_to_id);

ALTER TABLE `comment` DROP CHECK chk_comment_type;
ALTER TABLE `comment`
 ADD CONSTRAINT chk_comment_type CHECK(content_type IN ('text','image','sticker')),
 ADD CONSTRAINT chk_comment_reaction_counts CHECK(like_count>=0 AND dislike_count>=0);

CREATE TABLE comment_media (
 id BIGINT NOT NULL AUTO_INCREMENT,
 comment_id BIGINT NOT NULL,
 asset_id BIGINT NOT NULL,
 url VARCHAR(2048) NOT NULL,
 width INT NULL,
 height INT NULL,
 sort_order INT NOT NULL DEFAULT 0,
 PRIMARY KEY(id), UNIQUE KEY uk_comment_media_asset(asset_id), UNIQUE KEY uk_comment_media_order(comment_id,sort_order),
 CONSTRAINT fk_comment_media_comment FOREIGN KEY(comment_id) REFERENCES `comment`(id) ON DELETE CASCADE,
 CONSTRAINT fk_comment_media_asset FOREIGN KEY(asset_id) REFERENCES upload_asset(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='评论图片';

CREATE TABLE comment_reaction (
 id BIGINT NOT NULL AUTO_INCREMENT,
 comment_id BIGINT NOT NULL,
 user_id BIGINT NOT NULL,
 reaction VARCHAR(10) NOT NULL,
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 PRIMARY KEY(id), UNIQUE KEY uk_comment_reaction_user(comment_id,user_id), KEY idx_comment_reaction_user(user_id,update_time DESC),
 CONSTRAINT fk_comment_reaction_comment FOREIGN KEY(comment_id) REFERENCES `comment`(id) ON DELETE CASCADE,
 CONSTRAINT chk_comment_reaction CHECK(reaction IN ('like','dislike'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='评论赞踩，不计入用户总获赞';

INSERT INTO system_config(config_key,config_value,remark) VALUES
 ('comment_image_max','3','每条评论或回复最多图片数，0表示关闭'),
 ('comment_delete_cascade','0','删除根评论时是否连同回复彻底删除')
ON DUPLICATE KEY UPDATE remark=VALUES(remark);
