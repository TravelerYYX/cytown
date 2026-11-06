-- MySQL 8；可重复执行。只新增表，不创建示例入口。
CREATE TABLE IF NOT EXISTS mine_entry (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  title VARCHAR(20) NOT NULL,
  description VARCHAR(100) NOT NULL,
  icon_url VARCHAR(2048) NOT NULL,
  link_url VARCHAR(2048) NOT NULL,
  open_mode VARCHAR(10) NOT NULL DEFAULT 'inapp',
  show_topbar BOOLEAN NOT NULL DEFAULT TRUE,
  sort_order INT NOT NULL DEFAULT 0,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_mine_entry_enabled_sort (enabled, sort_order, id),
  CONSTRAINT chk_mine_entry_mode CHECK (open_mode IN ('inapp', 'browser'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='我的页面快捷入口';
