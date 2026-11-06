-- Apply once after P27. Backup the selected database first.
ALTER TABLE chat_conversation ADD COLUMN last_message_id BIGINT NOT NULL DEFAULT 0;
ALTER TABLE chat_conversation ADD COLUMN cleared_through BIGINT NOT NULL DEFAULT 0;
ALTER TABLE chat_conversation ADD COLUMN has_history BOOLEAN NOT NULL DEFAULT FALSE;
CREATE INDEX idx_chat_recent ON chat_conversation(last_message_id,id);
UPDATE chat_conversation c SET last_message_id=(SELECT COALESCE(MAX(m.id),0) FROM chat_message m WHERE m.conversation_id=c.id);
UPDATE chat_conversation SET has_history=TRUE WHERE last_message_id>0;
ALTER TABLE media_cleanup_task ADD COLUMN force_delete BOOLEAN NOT NULL DEFAULT FALSE;
CREATE INDEX idx_chat_ticket_expiry ON chat_ticket(expires_at);
CREATE TABLE chat_restriction (
 user_id BIGINT NOT NULL, conversation_id BIGINT NOT NULL DEFAULT 0,
 blocked BOOLEAN NOT NULL DEFAULT FALSE, muted_until BIGINT NOT NULL DEFAULT 0,
 operator_id BIGINT NOT NULL, updated_at BIGINT NOT NULL,
 PRIMARY KEY(user_id,conversation_id)
);
CREATE TABLE chat_deleted_message (
 message_id BIGINT NOT NULL PRIMARY KEY, conversation_id BIGINT NOT NULL,
 sender_id BIGINT NOT NULL, client_id VARCHAR(80) NOT NULL,
 UNIQUE KEY uk_chat_deleted_client(sender_id,client_id),
 KEY idx_chat_deleted_conversation(conversation_id,message_id)
);
CREATE TABLE chat_delete_job (
 id VARCHAR(36) NOT NULL PRIMARY KEY, conversation_id BIGINT NOT NULL,
 through_id BIGINT NOT NULL, single_id BIGINT NULL,
 operator_id BIGINT NOT NULL, created_at BIGINT NOT NULL,
 rows_done BOOLEAN NOT NULL DEFAULT FALSE,
 error_message VARCHAR(255) NOT NULL DEFAULT '',
 KEY idx_chat_delete_pending(rows_done,created_at)
);
CREATE TABLE chat_delete_object (
 job_id VARCHAR(36) NOT NULL, cleanup_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 PRIMARY KEY(job_id,cleanup_id)
);
CREATE TABLE chat_admin_audit (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, operator_id BIGINT NOT NULL,
 action VARCHAR(32) NOT NULL, conversation_id BIGINT NOT NULL DEFAULT 0,
 target_id VARCHAR(80) NOT NULL DEFAULT '', created_at BIGINT NOT NULL,
 detail VARCHAR(255) NOT NULL DEFAULT ''
);
