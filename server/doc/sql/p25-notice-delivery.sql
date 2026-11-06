-- P25. Back up notice, notice_category and system_config before applying ONCE.
SET NAMES utf8mb4;
ALTER TABLE app_user ADD COLUMN notice_read_since DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6);
ALTER TABLE notice_category ADD COLUMN recipient_policy VARCHAR(16) NOT NULL DEFAULT 'all';
ALTER TABLE notice ADD COLUMN audience_type VARCHAR(16) NOT NULL DEFAULT 'all';
ALTER TABLE notice ADD COLUMN push_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE notice ADD COLUMN publication_id BIGINT NULL;
CREATE TABLE IF NOT EXISTS notice_category_recipient (
 category_id BIGINT NOT NULL, user_id BIGINT NOT NULL, PRIMARY KEY(category_id,user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS notice_draft_recipient (
 notice_id BIGINT NOT NULL, user_id BIGINT NOT NULL, PRIMARY KEY(notice_id,user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS notice_publication (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, notice_id BIGINT NOT NULL,
 category_id BIGINT NOT NULL, audience_type VARCHAR(16) NOT NULL,
 unread_enabled BOOLEAN NOT NULL DEFAULT TRUE,
 published_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 INDEX idx_publication_notice(notice_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS notice_publication_recipient (
 publication_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
 PRIMARY KEY(publication_id,user_id), INDEX idx_notice_recipient_user(user_id,publication_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS notice_read (
 publication_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
 read_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(publication_id,user_id), INDEX idx_notice_read_user(user_id,publication_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS notice_push_config (
 id INT NOT NULL PRIMARY KEY, enabled BOOLEAN NOT NULL DEFAULT FALSE,
 app_id VARCHAR(100) NOT NULL DEFAULT '', endpoint VARCHAR(2048) NOT NULL DEFAULT '',
 secret_cipher TEXT NOT NULL, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT IGNORE INTO notice_push_config(id,secret_cipher) VALUES(1,'');
CREATE TABLE IF NOT EXISTS notice_push_device (
 cid VARCHAR(128) NOT NULL PRIMARY KEY, user_id BIGINT NOT NULL, token_version INT NOT NULL,
 updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 INDEX idx_notice_device_user(user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS notice_push_task (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, publication_id BIGINT NOT NULL,
 user_id BIGINT NOT NULL, cid VARCHAR(128) NOT NULL, token_version INT NOT NULL,
 request_id VARCHAR(32) NOT NULL, status VARCHAR(16) NOT NULL DEFAULT 'pending',
 attempts INT NOT NULL DEFAULT 0, next_attempt_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 last_error VARCHAR(255) NOT NULL DEFAULT '', lease_until DATETIME NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uk_notice_push_device(publication_id,cid), UNIQUE KEY uk_notice_push_request(request_id),
 INDEX idx_notice_push_pending(status,next_attempt_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- Existing published notices remain public and do not acquire unread badges.
INSERT INTO notice_publication(notice_id,category_id,audience_type,unread_enabled,published_at)
 SELECT id,category_id,'all',FALSE,COALESCE(publish_time,create_time) FROM notice
 WHERE status=1 AND publication_id IS NULL;
UPDATE notice n JOIN notice_publication p ON p.notice_id=n.id SET n.publication_id=p.id
 WHERE n.publication_id IS NULL AND n.status=1;
