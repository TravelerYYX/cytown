-- Stop the P25 application before rollback. Preserve the P25 tables/backup if reads or audit data are needed.
-- Older application versions cannot enforce targeted audiences: hide those notices first.
UPDATE notice SET status=0 WHERE audience_type='users';
UPDATE notice_push_config SET enabled=FALSE WHERE id=1;
UPDATE notice_push_task SET status='cancelled' WHERE status IN ('pending','sending');
DROP TABLE notice_push_task;
DROP TABLE notice_push_device;
DROP TABLE notice_push_config;
DROP TABLE notice_read;
DROP TABLE notice_publication_recipient;
DROP TABLE notice_publication;
DROP TABLE notice_draft_recipient;
DROP TABLE notice_category_recipient;
ALTER TABLE notice DROP COLUMN audience_type;
ALTER TABLE notice DROP COLUMN push_enabled;
ALTER TABLE notice DROP COLUMN publication_id;
ALTER TABLE notice_category DROP COLUMN recipient_policy;
ALTER TABLE app_user DROP COLUMN notice_read_since;
