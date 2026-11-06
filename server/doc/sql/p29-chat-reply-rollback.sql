-- Removes reply relationships; ordinary message bodies and history are retained.
DROP INDEX idx_chat_reply ON chat_message;
ALTER TABLE chat_message DROP COLUMN reply_to_id;
