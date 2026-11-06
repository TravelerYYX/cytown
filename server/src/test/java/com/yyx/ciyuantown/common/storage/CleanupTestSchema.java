package com.yyx.ciyuantown.common.storage;

import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;

/** Minimal resource reference schema for isolated H2 service tests. */
public final class CleanupTestSchema {
 private CleanupTestSchema() {}
 public static void create(JdbcTemplate db)throws Exception {
  db.execute("CREATE TABLE IF NOT EXISTS system_config(config_key VARCHAR(100) PRIMARY KEY,config_value TEXT,remark VARCHAR(255))");
  String[] tables={"admin_user:avatar", "app_user:avatar", "app_system_avatar:url", "home_entry:icon_url", "mine_entry:icon_url", "notice_category:icon_url", "topic:cover_url", "publish_emoji:image_url", "notice:blocks", "post:content_blocks", "post_draft:cover_url,payload", "comment:sticker_url", "post_media:url,cover_url", "comment_media:url", "upload_asset:url,cover_url,preview_url,original_url,hls_url,qualities_json"};
  for(String entry:tables){var bits=entry.split(":");var ddl=new StringBuilder("CREATE TABLE IF NOT EXISTS `"+bits[0]+"` (id BIGINT PRIMARY KEY");for(String col:bits[1].split(","))ddl.append(", ").append(col).append(" TEXT");if(bits[0].endsWith("_media"))ddl.append(",asset_id BIGINT");ddl.append(")");db.execute(ddl.toString());}
  db.execute("CREATE TABLE IF NOT EXISTS media_job(asset_id BIGINT PRIMARY KEY)");
  db.execute("CREATE TABLE IF NOT EXISTS community_level_config(config_json TEXT)");
  db.execute("CREATE TABLE IF NOT EXISTS media_storage_config(id VARCHAR(36) PRIMARY KEY,config_json TEXT)");
  db.execute("CREATE TABLE IF NOT EXISTS media_object(id VARCHAR(36) PRIMARY KEY,storage_id VARCHAR(36),object_key VARCHAR(512),public_url VARCHAR(2048),content_type VARCHAR(128),size_bytes BIGINT)");
  for(String sql:Files.readString(Path.of("doc/sql/p26-resource-cleanup.sql")).replaceAll("(?m)^--.*$", "").split(";"))if(!sql.isBlank()&&!sql.trim().startsWith("USE "))db.execute(sql);
  db.execute("ALTER TABLE media_cleanup_task ADD COLUMN IF NOT EXISTS force_delete BOOLEAN NOT NULL DEFAULT FALSE");
 }
}
