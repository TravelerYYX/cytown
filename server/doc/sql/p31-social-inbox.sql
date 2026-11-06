-- P31: run schema before the updated server; MySQL 8. Backfill is repeatable and silent.
USE ciyuantown;
SET NAMES utf8mb4;
SET @repost_column = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='post' AND column_name='repost_of_post_id');
SET @ddl = IF(@repost_column=0,'ALTER TABLE post ADD COLUMN repost_of_post_id BIGINT NULL, ADD KEY idx_post_repost(repost_of_post_id)','SELECT 1');
PREPARE migration FROM @ddl;
EXECUTE migration;
DEALLOCATE PREPARE migration;

CREATE TABLE IF NOT EXISTS social_notification (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 recipient_id BIGINT NOT NULL, actor_id BIGINT NOT NULL,
 type VARCHAR(20) NOT NULL, post_id BIGINT NULL, comment_id BIGINT NULL,
 business_key VARCHAR(160) NOT NULL,
 read_at DATETIME NULL, create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uk_social_delivery(recipient_id,business_key),
 KEY idx_social_inbox(recipient_id,type,create_time DESC,id DESC),
 KEY idx_social_unread(recipient_id,read_at),
 CONSTRAINT chk_social_type CHECK(type IN ('reply','mention','repost','post_like','comment_like','follow'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS social_publication(post_id BIGINT NOT NULL PRIMARY KEY,create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP) ENGINE=InnoDB;

-- Only pre-migration rows are backfilled, including when this script is rerun later.
INSERT IGNORE INTO system_config(config_key,config_value,remark) VALUES('social_backfill_cutoff',DATE_FORMAT(NOW(),'%Y-%m-%d %H:%i:%s'),'互动通知历史回填截止时间');
SET @cutoff=(SELECT config_value FROM system_config WHERE config_key='social_backfill_cutoff');
INSERT IGNORE INTO social_publication(post_id) SELECT id FROM post WHERE status=1 AND create_time<=@cutoff;
INSERT IGNORE INTO social_notification(recipient_id,actor_id,type,post_id,comment_id,business_key,read_at,create_time)
 SELECT p.author_id,c.author_id,'reply',c.post_id,c.id,CONCAT('comment:',c.id),NOW(),c.create_time FROM `comment` c JOIN post p ON p.id=c.post_id
 WHERE c.deleted=0 AND p.author_id IS NOT NULL AND c.author_id IS NOT NULL AND p.author_id<>c.author_id AND c.create_time<=@cutoff;
INSERT IGNORE INTO social_notification(recipient_id,actor_id,type,post_id,comment_id,business_key,read_at,create_time)
 SELECT t.author_id,c.author_id,'reply',c.post_id,c.id,CONCAT('comment:',c.id),NOW(),c.create_time FROM `comment` c JOIN `comment` t ON t.id=COALESCE(c.reply_to_id,c.parent_id)
 WHERE c.deleted=0 AND t.author_id IS NOT NULL AND c.author_id IS NOT NULL AND t.author_id<>c.author_id AND c.create_time<=@cutoff;
INSERT IGNORE INTO social_notification(recipient_id,actor_id,type,post_id,business_key,read_at,create_time)
 SELECT p.author_id,l.user_id,'post_like',p.id,CONCAT('post-like:',p.id,':',l.user_id),NOW(),l.create_time FROM user_like l JOIN post p ON p.id=l.post_id
 WHERE p.author_id IS NOT NULL AND p.author_id<>l.user_id AND l.create_time<=@cutoff;
INSERT IGNORE INTO social_notification(recipient_id,actor_id,type,post_id,comment_id,business_key,read_at,create_time)
 SELECT c.author_id,r.user_id,'comment_like',c.post_id,c.id,CONCAT('comment-like:',c.id,':',r.user_id),NOW(),r.create_time FROM comment_reaction r JOIN `comment` c ON c.id=r.comment_id
 WHERE r.reaction='like' AND c.deleted=0 AND c.author_id IS NOT NULL AND c.author_id<>r.user_id AND r.create_time<=@cutoff;
INSERT IGNORE INTO social_notification(recipient_id,actor_id,type,business_key,read_at,create_time)
 SELECT followee_id,follower_id,'follow',CONCAT('follow:',followee_id,':',follower_id),NOW(),create_time FROM user_follow WHERE follower_id<>followee_id AND create_time<=@cutoff;
INSERT IGNORE INTO social_notification(recipient_id,actor_id,type,post_id,business_key,read_at,create_time)
 SELECT j.user_id,p.author_id,IF(p.repost_of_post_id IS NULL,'mention','repost'),p.id,CONCAT('publish:',p.id,':',j.user_id),NOW(),p.create_time
 FROM post p JOIN JSON_TABLE(IF(JSON_VALID(p.content_blocks),p.content_blocks,JSON_ARRAY()),'$[*].runs[*]' COLUMNS(kind VARCHAR(20) PATH '$.type',user_id BIGINT PATH '$.userId' NULL ON ERROR)) j
 WHERE p.status=1 AND j.kind='mention' AND j.user_id>0 AND p.author_id IS NOT NULL AND j.user_id<>p.author_id AND p.create_time<=@cutoff;
