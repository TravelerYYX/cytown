-- P1 正式结构；MySQL 8。只新建业务表，不修改 admin_user。
SET NAMES utf8mb4;
CREATE TABLE IF NOT EXISTS `post` (
 id BIGINT NOT NULL AUTO_INCREMENT COMMENT '帖子ID',
 author_id BIGINT NULL COMMENT '未来APP用户ID，P1为空',
 author_nickname VARCHAR(50) NOT NULL COMMENT '作者昵称快照',
 author_level INT NOT NULL DEFAULT 1 COMMENT '作者等级快照',
 author_bio VARCHAR(255) NOT NULL DEFAULT '' COMMENT '作者简介快照',
 title VARCHAR(100) NOT NULL COMMENT '标题',
 content TEXT NULL COMMENT '正文',
 status TINYINT NOT NULL DEFAULT 1 COMMENT '0待审核 1已发布 2已下架',
 home_news_enabled BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否参与首页资讯轮播',
 collection VARCHAR(255) NOT NULL DEFAULT '' COMMENT '合集展示信息',
 permission VARCHAR(100) NOT NULL DEFAULT '' COMMENT '转载权限展示信息',
 like_count INT NOT NULL DEFAULT 0 COMMENT '点赞展示计数',
 favorite_count INT NOT NULL DEFAULT 0 COMMENT '收藏展示计数',
 comment_count INT NOT NULL DEFAULT 0 COMMENT '全部评论数，含一级回复',
 share_count INT NOT NULL DEFAULT 0 COMMENT '分享展示计数',
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
 update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
 PRIMARY KEY(id), KEY idx_status_create(status,create_time DESC,id DESC),
 KEY idx_home_news(home_news_enabled,status,create_time DESC,id DESC),
 CONSTRAINT chk_post_status CHECK(status IN (0,1,2))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='帖子';
CREATE TABLE IF NOT EXISTS post_media (
 id BIGINT NOT NULL AUTO_INCREMENT COMMENT '媒体ID',
 post_id BIGINT NOT NULL COMMENT '帖子ID',
 type VARCHAR(10) NOT NULL COMMENT 'image/video',
 layout VARCHAR(10) NOT NULL COMMENT 'square/portrait/wide',
 aspect_ratio DOUBLE NOT NULL COMMENT '宽高比',
 duration VARCHAR(20) NULL COMMENT '视频时长',
 url VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '媒体URL，P1允许为空',
 sort_order INT NOT NULL DEFAULT 0 COMMENT '展示顺序',
 PRIMARY KEY(id), UNIQUE KEY uk_media_order(post_id,sort_order),
 CONSTRAINT fk_media_post FOREIGN KEY(post_id) REFERENCES `post`(id) ON DELETE CASCADE,
 CONSTRAINT chk_media_type CHECK(type IN ('image','video')),
 CONSTRAINT chk_media_layout CHECK(layout IN ('square','portrait','wide')),
 CONSTRAINT chk_media_ratio CHECK(aspect_ratio>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='帖子媒体';
CREATE TABLE IF NOT EXISTS post_tag (
 id BIGINT NOT NULL AUTO_INCREMENT COMMENT '标签ID',
 post_id BIGINT NOT NULL COMMENT '帖子ID',
 name VARCHAR(100) NOT NULL COMMENT '标签文字',
 sort_order INT NOT NULL DEFAULT 0 COMMENT '展示顺序',
 PRIMARY KEY(id), UNIQUE KEY uk_tag_order(post_id,sort_order),
 CONSTRAINT fk_tag_post FOREIGN KEY(post_id) REFERENCES `post`(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='帖子标签';
CREATE TABLE IF NOT EXISTS `comment` (
 id BIGINT NOT NULL AUTO_INCREMENT COMMENT '评论ID',
 post_id BIGINT NOT NULL COMMENT '帖子ID',
 parent_id BIGINT NULL COMMENT '顶级评论为空，回复指向顶级评论',
 author_nickname VARCHAR(50) NOT NULL COMMENT '评论者昵称快照',
 author_level INT NOT NULL DEFAULT 1 COMMENT '等级',
 region VARCHAR(50) NOT NULL DEFAULT '' COMMENT '展示地区，不存用户原始IP',
 content VARCHAR(500) NOT NULL DEFAULT '' COMMENT '文本内容',
 content_type VARCHAR(10) NOT NULL DEFAULT 'text' COMMENT 'text/sticker',
 sticker_url VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '贴图URL，P1可为空',
 pinned BOOLEAN NOT NULL DEFAULT FALSE COMMENT '置顶标记',
 like_count INT NOT NULL DEFAULT 0 COMMENT '点赞数',
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
 PRIMARY KEY(id), UNIQUE KEY uk_comment_post(id,post_id),
 KEY idx_post_comments(post_id,parent_id,create_time DESC,id DESC),
 KEY idx_parent_post(parent_id,post_id),
 CONSTRAINT fk_comment_post FOREIGN KEY(post_id) REFERENCES `post`(id) ON DELETE CASCADE,
 CONSTRAINT fk_comment_parent FOREIGN KEY(parent_id,post_id) REFERENCES `comment`(id,post_id) ON DELETE CASCADE,
 CONSTRAINT chk_comment_type CHECK(content_type IN ('text','sticker'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='评论及一级回复';
