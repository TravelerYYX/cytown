-- P6 用户资料扩展：签名/性别/系统头像库/用户标签；MySQL 8。软关联不建外键。
SET NAMES utf8mb4;

-- app_user 加列：个性签名、性别
ALTER TABLE app_user
  ADD COLUMN signature VARCHAR(150) NOT NULL DEFAULT '' COMMENT '个性签名(前端上限50字,留冗余)' AFTER avatar,
  ADD COLUMN gender TINYINT NOT NULL DEFAULT 2 COMMENT '0男 1女 2保密' AFTER signature,
  ADD CONSTRAINT chk_user_gender CHECK(gender IN (0,1,2));

-- 系统头像库（后端统一管理，用户从中挑选）
CREATE TABLE IF NOT EXISTS app_system_avatar (
 id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
 url VARCHAR(255) NOT NULL COMMENT '头像URL(相对路径,如 /uploads/sys-avatar/xx.png)',
 type TINYINT NOT NULL DEFAULT 0 COMMENT '0静态 1动态',
 sort INT NOT NULL DEFAULT 0 COMMENT '组内排序,越小越前',
 status TINYINT NOT NULL DEFAULT 0 COMMENT '0启用 1停用',
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
 update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
 PRIMARY KEY(id),
 KEY idx_type_status_sort(type,status,sort),
 CONSTRAINT chk_avatar_type CHECK(type IN (0,1)),
 CONSTRAINT chk_avatar_status CHECK(status IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统头像库';

-- 用户标签库（系统预置 + 用户自定义沉淀）
CREATE TABLE IF NOT EXISTS app_tag (
 id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
 name VARCHAR(20) NOT NULL COMMENT '标签名',
 category VARCHAR(20) NOT NULL DEFAULT '' COMMENT '分类:game/本体/interest/mbti等',
 source TINYINT NOT NULL DEFAULT 0 COMMENT '0系统预置 1用户自定义沉淀',
 status TINYINT NOT NULL DEFAULT 0 COMMENT '0启用 1停用',
 sort INT NOT NULL DEFAULT 0 COMMENT '分类内排序,越小越前',
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
 update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
 PRIMARY KEY(id),
 UNIQUE KEY uk_category_name(category,name),
 KEY idx_category_status(category,status,sort),
 CONSTRAINT chk_tag_source CHECK(source IN (0,1)),
 CONSTRAINT chk_tag_status CHECK(status IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户标签库';

-- 用户标签关联（tag_id 为 NULL 时表示自定义标签，使用 custom_name）
CREATE TABLE IF NOT EXISTS app_user_tag (
 id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
 user_id BIGINT NOT NULL COMMENT '用户ID',
 tag_id BIGINT NULL COMMENT '系统标签ID(自定义标签为NULL)',
 custom_name VARCHAR(20) NULL COMMENT '自定义标签名(tag_id为NULL时使用)',
 sort_order INT NOT NULL DEFAULT 0 COMMENT '用户标签展示顺序,越小越前',
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
 PRIMARY KEY(id),
 UNIQUE KEY uk_user_tag(user_id,tag_id),
 KEY idx_user_sort(user_id,sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户标签关联';
