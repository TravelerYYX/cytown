-- P24 Android releases; rerunnable, no automatic publication.
SET NAMES utf8mb4;
CREATE TABLE IF NOT EXISTS app_release (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 version_name VARCHAR(64) NOT NULL,
 version_code BIGINT NOT NULL,
 notes TEXT NOT NULL,
 force_update BOOLEAN NOT NULL DEFAULT FALSE,
 status VARCHAR(16) NOT NULL DEFAULT 'draft',
 package_url VARCHAR(2048) NULL,
 package_name VARCHAR(255) NULL,
 apk_version_code BIGINT NULL,
 apk_version_name VARCHAR(64) NULL,
 size_bytes BIGINT NOT NULL DEFAULT 0,
 sha256 CHAR(64) NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 published_at DATETIME NULL,
 INDEX idx_release_history (status,version_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS app_update_channel (
 id INT NOT NULL PRIMARY KEY,
 active_release_id BIGINT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT IGNORE INTO app_update_channel(id,active_release_id) VALUES(1,NULL);
