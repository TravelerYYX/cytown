-- Apply once after P28; back up the selected database before applying.
ALTER TABLE chat_message ADD COLUMN reply_to_id BIGINT NULL;
CREATE INDEX idx_chat_reply ON chat_message(reply_to_id);
