-- First restore the previous client/backend, then back up the chat tables.
-- This removes chat history and receipts. Do not run during normal deployment.
DROP TABLE IF EXISTS chat_push_task;
DROP TABLE IF EXISTS chat_ticket;
DROP TABLE IF EXISTS chat_event;
DROP TABLE IF EXISTS chat_receipt;
DROP TABLE IF EXISTS chat_message;
DROP TABLE IF EXISTS chat_video_job;
DROP TABLE IF EXISTS chat_asset;
DROP TABLE IF EXISTS chat_stream;
DROP TABLE IF EXISTS chat_member;
DROP TABLE IF EXISTS chat_conversation;
DELETE FROM system_config WHERE config_key='chat_settings';
-- Keep encrypted chat storage objects until the history backup retention expires.
