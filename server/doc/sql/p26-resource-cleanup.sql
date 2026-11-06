-- P26: only future retirements; existing objects are never scheduled by this migration.
USE ciyuantown;
INSERT IGNORE INTO system_config(config_key,config_value,remark)
VALUES('delete_resource_files_enabled','1','删除时同时删除资源文件');
CREATE TABLE IF NOT EXISTS media_cleanup_lock (
 id INT PRIMARY KEY
) ENGINE=InnoDB;
INSERT IGNORE INTO media_cleanup_lock(id) VALUES(1);
CREATE TABLE IF NOT EXISTS media_cleanup_task (
 id VARCHAR(64) PRIMARY KEY,
 storage_id VARCHAR(36) NOT NULL,
 object_key VARCHAR(512) NOT NULL,
 public_url VARCHAR(2048) NOT NULL,
 completed BOOLEAN NOT NULL DEFAULT FALSE,
 attempts INT NOT NULL DEFAULT 0,
 next_attempt_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 lease_until DATETIME NULL,
 worker_token VARCHAR(36) NULL,
 error_message VARCHAR(255) NOT NULL DEFAULT '',
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 KEY idx_cleanup_due(completed,next_attempt_at,lease_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS media_resource_retention (
 id VARCHAR(64) PRIMARY KEY,
 storage_id VARCHAR(36) NOT NULL,
 object_key VARCHAR(512) NOT NULL,
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
