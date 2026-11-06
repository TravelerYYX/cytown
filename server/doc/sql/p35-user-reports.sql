-- MySQL 8. Apply before deploying the block/report backend. Re-runnable.
CREATE TABLE IF NOT EXISTS user_block (
  owner_id BIGINT NOT NULL,
  target_id BIGINT NOT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(owner_id,target_id), KEY idx_block_target(target_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS report_option (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  kind VARCHAR(10) NOT NULL,
  name VARCHAR(100) NOT NULL,
  sort_order INT NOT NULL DEFAULT 0,
  deleted BOOLEAN NOT NULL DEFAULT FALSE,
  seed_key VARCHAR(40) NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_report_seed(seed_key), KEY idx_report_options(kind,deleted,sort_order,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS user_report (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  reporter_id BIGINT NOT NULL,
  reported_user_id BIGINT NOT NULL,
  target_kind VARCHAR(10) NOT NULL,
  target_id BIGINT NOT NULL,
  type_id BIGINT NOT NULL,
  reason_id BIGINT NOT NULL,
  type_name VARCHAR(100) NOT NULL,
  reason_name VARCHAR(100) NOT NULL,
  content VARCHAR(800) NOT NULL,
  reporter_name VARCHAR(100) NOT NULL,
  reported_user_name VARCHAR(100) NOT NULL,
  target_snapshot LONGTEXT NOT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_reports_time(create_time,id), KEY idx_reports_reporter(reporter_id), KEY idx_reports_target(reported_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS user_report_image (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  report_id BIGINT NOT NULL,
  asset_id BIGINT NOT NULL,
  url VARCHAR(2048) NOT NULL,
  sort_order INT NOT NULL,
  UNIQUE KEY uk_report_image(report_id,asset_id), KEY idx_report_image_asset(asset_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT IGNORE INTO report_option(kind,name,sort_order,seed_key) VALUES
('type','头像违规',0,'avatar'),('type','昵称违规',1,'nickname'),('type','签名违规',2,'signature'),('type','帖子违规',3,'post'),
('reason','含垃圾或广告信息',0,'spam'),('reason','含引战辱骂信息',1,'abuse'),('reason','含政治敏感信息',2,'politics'),('reason','含暴力色情信息',3,'violent'),
('reason','含违法犯罪信息',4,'illegal'),('reason','传播封建迷信',5,'superstition'),('reason','内容涉嫌侵权',6,'copyright'),('reason','内容引人不适',7,'disturbing');
