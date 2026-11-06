-- P12: storage, image variants and durable media jobs. No historical deletion here.
USE ciyuantown;
CREATE TABLE IF NOT EXISTS media_storage_config (
 id VARCHAR(36) PRIMARY KEY, config_json MEDIUMTEXT NOT NULL, create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS media_object (
 id VARCHAR(36) PRIMARY KEY, storage_id VARCHAR(36) NOT NULL, object_key VARCHAR(512) NOT NULL,
 public_url VARCHAR(2048) NOT NULL, content_type VARCHAR(128) NOT NULL, size_bytes BIGINT NOT NULL,
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, KEY idx_object_storage(storage_id),
 UNIQUE KEY uk_object_key(storage_id,object_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
SET @media_ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='upload_asset' AND column_name='preview_url')=0,'ALTER TABLE upload_asset ADD COLUMN preview_url VARCHAR(2048) NOT NULL DEFAULT ''''','SELECT 1');
PREPARE media_stmt FROM @media_ddl;
EXECUTE media_stmt;
DEALLOCATE PREPARE media_stmt;
SET @media_ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='upload_asset' AND column_name='original_url')=0,'ALTER TABLE upload_asset ADD COLUMN original_url VARCHAR(2048) NOT NULL DEFAULT ''''','SELECT 1');
PREPARE media_stmt FROM @media_ddl;
EXECUTE media_stmt;
DEALLOCATE PREPARE media_stmt;
SET @media_ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='upload_asset' AND column_name='hls_url')=0,'ALTER TABLE upload_asset ADD COLUMN hls_url VARCHAR(2048) NOT NULL DEFAULT ''''','SELECT 1');
PREPARE media_stmt FROM @media_ddl;
EXECUTE media_stmt;
DEALLOCATE PREPARE media_stmt;
SET @media_ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='upload_asset' AND column_name='qualities_json')=0,'ALTER TABLE upload_asset ADD COLUMN qualities_json TEXT NULL','SELECT 1');
PREPARE media_stmt FROM @media_ddl;
EXECUTE media_stmt;
DEALLOCATE PREPARE media_stmt;
SET @media_ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='upload_asset' AND column_name='resource_version')=0,'ALTER TABLE upload_asset ADD COLUMN resource_version VARCHAR(36) NOT NULL DEFAULT ''''','SELECT 1');
PREPARE media_stmt FROM @media_ddl;
EXECUTE media_stmt;
DEALLOCATE PREPARE media_stmt;
CREATE TABLE IF NOT EXISTS media_job (
 asset_id BIGINT PRIMARY KEY, config_json TEXT NOT NULL, state VARCHAR(16) NOT NULL DEFAULT 'queued',
 attempts INT NOT NULL DEFAULT 0, lease_until DATETIME NULL, worker_token VARCHAR(36) NULL,
 error_message VARCHAR(255) NOT NULL DEFAULT '',
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 KEY idx_media_job(state,lease_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
