package com.yyx.ciyuantown.common.storage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yyx.ciyuantown.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

/** Explicit business retirements only. Snapshots survive cascades; the outbox survives storage outages. */
@Service @RequiredArgsConstructor @Slf4j
public class ResourceCleanupService {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final StorageService storage;
    private final GlobalSettingsService settings;
    private final PlatformTransactionManager transactions;
    private final Object stateKey = new Object();
    private static final Map<String, String> COLUMNS = Map.ofEntries(
        Map.entry("admin_user", "avatar"), Map.entry("app_user", "avatar"),
        Map.entry("app_system_avatar", "url"), Map.entry("home_entry", "icon_url"),
        Map.entry("mine_entry", "icon_url"), Map.entry("notice_category", "icon_url"),
        Map.entry("system_config", "config_value"),
        Map.entry("topic", "cover_url"), Map.entry("publish_emoji", "image_url"),
        Map.entry("notice", "blocks"), Map.entry("post", "content_blocks"),
        Map.entry("post_draft", "cover_url,payload"), Map.entry("comment", "sticker_url"),
        Map.entry("post_media", "url,cover_url"), Map.entry("comment_media", "url"),
        Map.entry("app_release", "package_url"), Map.entry("community_level_config", "config_json"),
        Map.entry("upload_asset", "url,cover_url,preview_url,original_url,hls_url,qualities_json"),
        Map.entry("chat_asset", "manifest_json"), Map.entry("membership_config", "config_json"), Map.entry("membership_order", "plan_json"),
        Map.entry("shop_product", "images_json,frame_url"), Map.entry("shop_order", "product_json"),
        Map.entry("chat_message", "content"), Map.entry("user_report_image", "url"), Map.entry("user_report", "target_snapshot"));
    private static final Set<String> URL_FIELDS = Set.of("url", "imageUrl", "coverUrl", "previewUrl", "originalUrl", "hlsUrl", "stickerUrl", "iconUrl", "badgeUrl", "frameUrl", "packageUrl", "avatarUrl", "images");
    private record Target(String storageId, String key, String url) {
        String id() {
            try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((storageId + "\n" + key).getBytes(StandardCharsets.UTF_8))); }
            catch(Exception e) { throw new IllegalStateException(e); }
        }
    }
    private static class State {
        final Set<String> urls = new LinkedHashSet<>();
        final Set<Long> assets = new TreeSet<>();
        final Set<String> tasks = new LinkedHashSet<>();
    }
    private static class References {
        final Set<String> urls = new HashSet<>();
        final Set<Long> assets = new HashSet<>();
        final Set<String> prefixes = new HashSet<>();
        boolean contains(Target target) {
            return urls.contains(target.url()) || prefixes.stream().anyMatch(p -> (target.storageId()+"\n"+target.key()).startsWith(p));
        }
    }

    /** All resource-reference writers acquire this gate before business row locks. */
    public void lock() {
        if(!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Resource changes require a transaction");
        db.queryForList("SELECT id FROM media_cleanup_lock WHERE id=1 FOR UPDATE");
        if(TransactionSynchronizationManager.hasResource(stateKey)) return;
        var state = new State();
        TransactionSynchronizationManager.bindResource(stateKey, state);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void beforeCommit(boolean readOnly) {
                References allRefs = references(true);
                References refs = state.assets.isEmpty() ? null : references(false);
                // Do not allow a late reference to resurrect a queued/deleting object.
                for(var row : db.queryForList("SELECT storage_id,object_key,public_url FROM media_cleanup_task")) {
                    if(allRefs.contains(target(row))) throw new BizException("资源正在清理或已删除，请重新上传");
                }
                if(!state.urls.isEmpty() || !state.assets.isEmpty()) retire(state, refs);
            }
            @Override public void afterCommit() {
                for(String id : state.tasks) {
                    try { process(id); } catch(Exception e) { log.warn("Resource cleanup deferred: task={}, type={}", id, e.getClass().getSimpleName()); }
                }
            }
            @Override public void afterCompletion(int status) { TransactionSynchronizationManager.unbindResourceIfPossible(stateKey); }
        });
    }
    private State state() { lock(); return (State)TransactionSynchronizationManager.getResource(stateKey); }

    /** Capture before update/delete, while related rows still exist. */
    public void track(String table, Long id) {
        if(id == null) { lock(); return; }
        if(!COLUMNS.containsKey(table)) throw new IllegalArgumentException("Unsupported resource owner");
        var state = state();
        capture(table, "id=?", state, id);
        if("post".equals(table)) {
            capture("post_media", "post_id=?", state, id);
            capture("comment", "post_id=?", state, id);
            capture("comment_media", "comment_id IN (SELECT id FROM `comment` WHERE post_id=?)", state, id);
        } else if("comment".equals(table)) {
            capture("comment_media", "comment_id=?", state, id);
        }
    }
    public void trackConfig(String key) {
        capture("system_config", "config_key=?", state(), key);
    }
    private void capture(String table, String where, State state, Object... args) {
        for(var row : db.queryForList("SELECT * FROM `"+table+"` WHERE "+where, args)) {
            collectRow(table, row, state.urls, state.assets);
            if(("post_media".equals(table) || "comment_media".equals(table)) && row.get("asset_id") instanceof Number n) state.assets.add(n.longValue());
        }
    }
    public void retireAsset(Long id) {
        if(id == null) return;
        state().assets.add(id);
    }
    /** Explicit private-message deletion always removes its independent encrypted objects. */
    public void forceRetireChat(Long assetId,String jobId) {
        lock();
        var urls=new LinkedHashSet<String>();
        for(var row:db.queryForList("SELECT manifest_json FROM chat_asset WHERE id=?",assetId))
            collectJson(row.get("manifest_json").toString(),urls,new HashSet<>());
        for(String url:urls){
            Target target=locate(url);
            if(target==null)throw new BizException("私聊资源存储索引缺失，已停止删除");
            db.update("DELETE FROM media_resource_retention WHERE id=?",target.id());
            db.update("INSERT IGNORE INTO media_cleanup_task(id,storage_id,object_key,public_url,force_delete) VALUES(?,?,?,?,TRUE)",target.id(),target.storageId(),target.key(),target.url());
            db.update("UPDATE media_cleanup_task SET force_delete=TRUE WHERE id=?",target.id());
            db.update("INSERT IGNORE INTO chat_delete_object(job_id,cleanup_id) VALUES(?,?)",jobId,target.id());
        }
    }
    /** Account tombstones keep published content but release private drafts and unused uploads. */
    public void retireAccount(Long id, boolean admin) {
        track(admin ? "admin_user" : "app_user", id);
        if(!admin) {
            for(Long draft : db.queryForList("SELECT id FROM post_draft WHERE author_id=?", Long.class, id)) track("post_draft", draft);
            db.update("DELETE FROM post_draft WHERE author_id=?", id);
            state().assets.addAll(db.queryForList("SELECT id FROM upload_asset WHERE owner_id=?", Long.class, id));
        }
        db.update("UPDATE "+(admin ? "admin_user" : "app_user")+" SET avatar='' WHERE id=?", id);
    }
    /** Called by the expiry job in its own transaction; retained files are never swept later. */
    public void expireAsset(Long id) {
        lock();
        if(!references(false).assets.contains(id)) retireAsset(id);
    }
    private void retire(State state, References business) {
        for(Long id : state.assets) {
            if(business.assets.contains(id)) continue;
            var rows = db.queryForList("SELECT * FROM upload_asset WHERE id=?", id);
            Set<String> variantUrls = new LinkedHashSet<>();
            for(var row : rows) {
                collectRow("upload_asset", row, variantUrls, new HashSet<>());
                String original = Objects.toString(row.get("original_url"), "");
                storage.find(original).ifPresent(o -> {
                    String prefix = assetPrefix(o.key());
                    if(prefix != null) variantUrls.addAll(db.queryForList("SELECT public_url FROM media_object WHERE storage_id=? AND LEFT(object_key,?)=?", String.class, o.storageId(), prefix.length(), prefix));
                });
            }
            if(variantUrls.stream().anyMatch(business.urls::contains)) continue;
            state.urls.addAll(variantUrls);
            db.update("DELETE FROM media_job WHERE asset_id=?", id);
            db.update("DELETE FROM upload_asset WHERE id=?", id);
        }
        References remaining = references(true);
        boolean enabled = settings.enabled();
        for(String url : state.urls) {
            Target target = locate(url);
            if(target == null || remaining.contains(target)) continue;
            if(!enabled) {
                db.update("INSERT IGNORE INTO media_resource_retention(id,storage_id,object_key) VALUES(?,?,?)", target.id(), target.storageId(), target.key());
            } else if(db.queryForObject("SELECT COUNT(*) FROM media_resource_retention WHERE id=?", Integer.class, target.id()) == 0) {
                db.update("INSERT IGNORE INTO media_cleanup_task(id,storage_id,object_key,public_url) VALUES(?,?,?,?)", target.id(), target.storageId(), target.key(), target.url());
                state.tasks.add(target.id());
            }
        }
    }
    private Target locate(String url) {
        if(url == null || url.isBlank()) return null;
        var found = storage.find(url);
        if(found.isPresent()) { var o=found.get(); return new Target(o.storageId(),o.key(),o.url()); }
        if(url.startsWith("/uploads/levels/")) return null; // Bundled level badges have no upload registry.
        if(url.startsWith("/uploads/") && !url.startsWith("/uploads/storage/")) {
            String key = url.substring(9); StorageService.checkKey(key);
            return new Target("local-default", key, url);
        }
        return null;
    }
    private boolean chatTableExists() {
        return Boolean.TRUE.equals(db.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>) c -> {
            try(var tables=c.getMetaData().getTables(c.getCatalog(),null,"%",new String[]{"TABLE"})) {
                while(tables.next()) if("chat_asset".equalsIgnoreCase(tables.getString("TABLE_NAME"))) return true;
                return false;
            }
        }));
    }
    private boolean membershipTableExists(String name) {
        return Boolean.TRUE.equals(db.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>) c -> {
            try(var tables=c.getMetaData().getTables(c.getCatalog(),null,"%",new String[]{"TABLE"})) {
                while(tables.next()) if(name.equalsIgnoreCase(tables.getString("TABLE_NAME"))) return true;
                return false;
            }
        }));
    }
    private References references(boolean includeUploads) {
        var refs = new References();
        for(var entry : COLUMNS.entrySet()) {
            String table = entry.getKey();
            if((table.startsWith("shop_") || table.equals("chat_message")) && !membershipTableExists(table)) continue;
            if(table.startsWith("membership_") && !membershipTableExists(table)) continue;
            if("chat_asset".equals(table) && !chatTableExists()) continue;
            if(("user_report_image".equals(table) || "user_report".equals(table)) && !membershipTableExists(table)) continue;
            if("upload_asset".equals(table) && !includeUploads) continue;
            for(var row : db.queryForList("SELECT "+entry.getValue()+(table.equals("post_media") || table.equals("comment_media") || table.equals("user_report_image") ? ",asset_id" : "")+" FROM `"+table+"`"+(table.equals("chat_message") ? " WHERE kind='product' AND revoked=FALSE" : table.equals("system_config") ? " WHERE config_key='home_header_icon'" : ""))) {
                collectRow(table, row, refs.urls, refs.assets);
                if(row.get("asset_id") instanceof Number n) refs.assets.add(n.longValue());
                if(includeUploads && table.equals("upload_asset")) {
                    storage.find(Objects.toString(row.get("hls_url"), "")).ifPresent(o -> {
                        String prefix = assetPrefix(o.key()); if(prefix != null && o.key().substring(prefix.length()).startsWith("hls-")) refs.prefixes.add(o.storageId()+"\n"+o.key().substring(0,o.key().lastIndexOf('/')+1));
                    });
                }
            }
        }
        return refs;
    }
    private void collectRow(String table, Map<String,Object> row, Set<String> urls, Set<Long> assets) {
        for(String column : COLUMNS.get(table).split(",")) {
            Object raw = row.get(column); if(raw == null) continue;
            String value = raw instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : raw.toString();
            if(Set.of("payload","blocks","content_blocks","qualities_json","config_json","manifest_json","plan_json","target_snapshot","product_json","images_json","content").contains(column)) collectJson(value, column.equals("images_json") ? "images" : "", urls, assets);
            else if(!value.isBlank()) urls.add(value);
        }
    }
    private void collectJson(String value, Set<String> urls, Set<Long> assets) { collectJson(value, "", urls, assets); }
    private void collectJson(String value, String field, Set<String> urls, Set<Long> assets) {
        if(value == null || value.isBlank()) return;
        try { visit(json.readTree(value), field, urls, assets); }
        catch(Exception e) { throw new BizException("资源引用数据格式异常，已停止清理"); }
    }
    private void visit(JsonNode node, String field, Set<String> urls, Set<Long> assets) {
        if(node == null) return;
        if(node.isObject()) {
            if("assets".equals(field) || "asset".equals(field)) addId(node.path("id"), assets);
            node.fields().forEachRemaining(e -> visit(e.getValue(),e.getKey(),urls,assets));
        } else if(node.isArray()) node.forEach(n -> visit(n,field,urls,assets));
        else if(URL_FIELDS.contains(field) && node.isTextual() && !node.asText().isBlank()) urls.add(node.asText());
        else if("assetId".equals(field) || "assetIds".equals(field)) addId(node, assets);
    }
    private static void addId(JsonNode node, Set<Long> ids) {
        try { long id = Long.parseLong(node.asText()); if(id > 0) ids.add(id); } catch(NumberFormatException ignored) { }
    }
    private static String assetPrefix(String key) {
        var match = java.util.regex.Pattern.compile("^(?:posts|comments)/[0-9]+/[0-9a-fA-F-]{36}/").matcher(key);
        return match.find() ? match.group() : null;
    }
    private static Target target(Map<String,Object> row) { return new Target((String)row.get("storage_id"),(String)row.get("object_key"),(String)row.get("public_url")); }

    @Scheduled(fixedDelay=30000) public void retry() {
        try {
            for(String id : db.queryForList("SELECT id FROM media_cleanup_task WHERE completed=FALSE AND (force_delete=TRUE OR ?=TRUE) AND next_attempt_at<=CURRENT_TIMESTAMP AND (lease_until IS NULL OR lease_until<CURRENT_TIMESTAMP) ORDER BY create_time LIMIT 100", String.class,settings.enabled())) process(id);
        } catch(Exception e) { log.warn("Resource cleanup queue unavailable: type={}", e.getClass().getSimpleName()); }
    }
    private TransactionTemplate newTransaction() {
        var tx = new TransactionTemplate(transactions); tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW); return tx;
    }
    public void process(String id) {
        // Hold the reference gate through deletion: no writer can attach a file after the final reference check.
        newTransaction().executeWithoutResult(tx -> {
            db.queryForList("SELECT id FROM media_cleanup_lock WHERE id=1 FOR UPDATE");
            var eligible=db.queryForList("SELECT force_delete FROM media_cleanup_task WHERE id=?",id);
            if(eligible.isEmpty()||!settings.enabled()&&!Boolean.TRUE.equals(eligible.getFirst().get("force_delete"))) return;
            String token = UUID.randomUUID().toString();
            if(db.update("UPDATE media_cleanup_task SET worker_token=?,lease_until=?,attempts=attempts+1 WHERE id=? AND completed=FALSE AND next_attempt_at<=CURRENT_TIMESTAMP AND (lease_until IS NULL OR lease_until<CURRENT_TIMESTAMP)", token, LocalDateTime.now().plusMinutes(10), id) != 1) return;
            var row = db.queryForMap("SELECT * FROM media_cleanup_task WHERE id=?", id);
            var target = target(row);
            if(references(true).contains(target) || db.queryForObject("SELECT COUNT(*) FROM media_resource_retention WHERE id=?",Integer.class,id)>0) {
                db.update("DELETE FROM media_cleanup_task WHERE id=? AND worker_token=?", id,token); return;
            }
            try {
                storage.deleteObject(target.storageId(),target.key());
                db.update("DELETE FROM media_object WHERE storage_id=? AND object_key=?",target.storageId(),target.key());
                // Keep a tombstone so a stale client cannot bind the deleted URL later.
                db.update("UPDATE media_cleanup_task SET completed=TRUE,lease_until=NULL,worker_token=NULL,error_message='' WHERE id=? AND worker_token=?",id,token);
            } catch(Exception e) {
                int attempts = ((Number)row.get("attempts")).intValue();
                long delay = Math.min(3600, 30L << Math.min(attempts-1,7));
                db.update("UPDATE media_cleanup_task SET lease_until=NULL,worker_token=NULL,next_attempt_at=?,error_message=? WHERE id=? AND worker_token=?",LocalDateTime.now().plusSeconds(delay),e.getClass().getSimpleName(),id,token);
                log.warn("Resource cleanup failed: task={}, attempt={}, type={}",id,attempts,e.getClass().getSimpleName());
            }
        });
    }
}
