-- Private chat. Apply after P26; backup before migration.
CREATE TABLE IF NOT EXISTS chat_conversation (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 user_low BIGINT NOT NULL, user_high BIGINT NOT NULL,
 UNIQUE KEY uk_chat_pair(user_low,user_high)
);
CREATE TABLE IF NOT EXISTS chat_member (
 conversation_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
 read_message_id BIGINT NOT NULL DEFAULT 0,
 PRIMARY KEY(conversation_id,user_id)
);
CREATE TABLE IF NOT EXISTS chat_stream (
 user_id BIGINT NOT NULL PRIMARY KEY, sequence_no BIGINT NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS chat_asset (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, owner_id BIGINT NOT NULL,
 kind VARCHAR(16) NOT NULL, state VARCHAR(16) NOT NULL,
 version VARCHAR(64) NOT NULL, mime VARCHAR(80) NOT NULL DEFAULT '',
 extension VARCHAR(12) NOT NULL DEFAULT '', size_bytes BIGINT NOT NULL DEFAULT 0,
 width INT NOT NULL DEFAULT 0, height INT NOT NULL DEFAULT 0, duration_ms BIGINT NOT NULL DEFAULT 0,
 manifest_json LONGTEXT NOT NULL, config_json LONGTEXT NOT NULL,
 cover_id BIGINT NULL, error_message VARCHAR(255) NOT NULL DEFAULT '',
 created_at BIGINT NOT NULL, attached BOOLEAN NOT NULL DEFAULT FALSE,
 KEY idx_chat_asset_owner(owner_id,created_at)
);
CREATE TABLE IF NOT EXISTS chat_video_job (
 asset_id BIGINT NOT NULL PRIMARY KEY, state VARCHAR(16) NOT NULL DEFAULT 'queued',
 worker_token VARCHAR(64) NULL, lease_until BIGINT NOT NULL DEFAULT 0,
 attempts INT NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS chat_message (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, conversation_id BIGINT NOT NULL,
 sender_id BIGINT NOT NULL, client_id VARCHAR(80) NOT NULL,
 kind VARCHAR(16) NOT NULL, content TEXT NOT NULL, asset_id BIGINT NULL,
 sent_at BIGINT NOT NULL, revoke_until BIGINT NOT NULL,
 revoked BOOLEAN NOT NULL DEFAULT FALSE, protected_receipt BOOLEAN NOT NULL DEFAULT FALSE,
 UNIQUE KEY uk_chat_client(sender_id,client_id), KEY idx_chat_history(conversation_id,id)
);
CREATE TABLE IF NOT EXISTS chat_receipt (
 operation_id VARCHAR(80) NOT NULL PRIMARY KEY, message_id BIGINT NOT NULL,
 user_id BIGINT NOT NULL, kind VARCHAR(16) NOT NULL, state VARCHAR(16) NOT NULL DEFAULT 'reserved',
 created_at BIGINT NOT NULL, KEY idx_chat_receipt_message(message_id,state)
);
CREATE TABLE IF NOT EXISTS chat_event (
 user_id BIGINT NOT NULL, sequence_no BIGINT NOT NULL,
 kind VARCHAR(24) NOT NULL, conversation_id BIGINT NOT NULL, message_id BIGINT NULL,
 PRIMARY KEY(user_id,sequence_no)
);
CREATE TABLE IF NOT EXISTS chat_ticket (
 ticket VARCHAR(64) NOT NULL PRIMARY KEY, user_id BIGINT NOT NULL,
 token_version INT NOT NULL, expires_at BIGINT NOT NULL
);
CREATE TABLE IF NOT EXISTS chat_push_task (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, message_id BIGINT NOT NULL,
 user_id BIGINT NOT NULL, cid VARCHAR(128) NOT NULL, token_version INT NOT NULL,
 request_id VARCHAR(32) NOT NULL, state VARCHAR(16) NOT NULL DEFAULT 'pending',
 attempts INT NOT NULL DEFAULT 0, next_attempt_at BIGINT NOT NULL DEFAULT 0,
 lease_until BIGINT NOT NULL DEFAULT 0, last_error VARCHAR(255) NOT NULL DEFAULT '',
 UNIQUE KEY uk_chat_push(message_id,cid)
);

-- Existing follows become empty conversations without writes on conversation-list reads.
INSERT IGNORE INTO chat_conversation(user_low,user_high)
 SELECT LEAST(follower_id,followee_id),GREATEST(follower_id,followee_id) FROM user_follow WHERE follower_id<>followee_id GROUP BY LEAST(follower_id,followee_id),GREATEST(follower_id,followee_id);
INSERT IGNORE INTO chat_member(conversation_id,user_id) SELECT id,user_low FROM chat_conversation;
INSERT IGNORE INTO chat_member(conversation_id,user_id) SELECT id,user_high FROM chat_conversation;
