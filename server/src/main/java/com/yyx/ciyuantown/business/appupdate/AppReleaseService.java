package com.yyx.ciyuantown.business.appupdate;

import com.yyx.ciyuantown.common.exception.BizException;
import com.yyx.ciyuantown.common.storage.StorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.support.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import java.nio.file.*;
import java.sql.Statement;
import java.security.*;
import java.util.*;
import java.io.*;

@Service @RequiredArgsConstructor
public class AppReleaseService {
 private final com.yyx.ciyuantown.common.storage.ResourceCleanupService cleanup;
    public static final long MAX_APK_BYTES=500L*1024*1024;
    private final JdbcTemplate db;
    private final StorageService storage;
    public record Draft(String versionName,Long versionCode,String notes,Boolean forceUpdate) {}
    public record Release(long id,String versionName,long versionCode,String notes,boolean forceUpdate,String status,
                          String packageUrl,String packageName,Long apkVersionCode,String apkVersionName,long sizeBytes,
                          String sha256,String createdAt,String publishedAt,boolean current) {}
    public record Check(boolean hasUpdate,Release release) {}
    private static final String SELECT="SELECT r.*, (c.active_release_id=r.id) AS is_current FROM app_release r LEFT JOIN app_update_channel c ON c.id=1";
    private static final RowMapper<Release> ROW=(r,n)->new Release(r.getLong("id"),r.getString("version_name"),r.getLong("version_code"),r.getString("notes"),r.getBoolean("force_update"),r.getString("status"),r.getString("package_url"),r.getString("package_name"),(Long)r.getObject("apk_version_code"),r.getString("apk_version_name"),r.getLong("size_bytes"),r.getString("sha256"),r.getString("created_at"),r.getString("published_at"),r.getBoolean("is_current"));
    public List<Release> list(){return db.query(SELECT+" ORDER BY r.id DESC",ROW);}
    public Release require(long id){return db.query(SELECT+" WHERE r.id=?",ROW,id).stream().findFirst().orElseThrow(()->new BizException("版本不存在"));}
    private void channelLock(){db.queryForObject("SELECT id FROM app_update_channel WHERE id=1 FOR UPDATE",Integer.class);}
    private Release releaseLock(long id){if(db.queryForList("SELECT id FROM app_release WHERE id=? FOR UPDATE",Long.class,id).isEmpty())throw new BizException("版本不存在");return require(id);}
    private Release draftLock(long id){var r=releaseLock(id);if(!r.status().equals("draft"))throw new BizException("已发布版本不能修改安装包或版本信息，请新建版本");return r;}
    private static void validate(Draft d){if(d.versionName()==null||d.versionName().isBlank()||d.versionName().length()>64||d.versionCode()==null||d.versionCode()<1||d.versionCode()>2100000000L||d.notes()==null||d.notes().isBlank()||d.notes().length()>10000)throw new BizException("请填写版本名称、1至2100000000的整数版本号及1至10000字更新说明");}
    @Transactional public Release create(Draft d){
        validate(d);var keys=new GeneratedKeyHolder();
        db.update(c->{var s=c.prepareStatement("INSERT INTO app_release(version_name,version_code,notes,force_update) VALUES(?,?,?,?)",new String[]{"id"});s.setString(1,d.versionName().trim());s.setLong(2,d.versionCode());s.setString(3,d.notes());s.setBoolean(4,Boolean.TRUE.equals(d.forceUpdate()));return s;},keys);
        return require(Objects.requireNonNull(keys.getKey()).longValue());
    }
    @Transactional public Release save(long id,Draft d){validate(d);var r=draftLock(id);if(r.apkVersionCode()!=null&&(r.apkVersionCode()!=d.versionCode().longValue()||(!r.apkVersionName().isEmpty()&&!r.apkVersionName().equals(d.versionName().trim()))))throw new BizException("版本信息须与已上传 APK 一致");db.update("UPDATE app_release SET version_name=?,version_code=?,notes=?,force_update=? WHERE id=?",d.versionName().trim(),d.versionCode(),d.notes(),Boolean.TRUE.equals(d.forceUpdate()),id);return require(id);}
    @Transactional public Release upload(long id,MultipartFile file){
        cleanup.track("app_release",id);
        var r=draftLock(id); validateFile(file);Path temp=null;
        try {
            temp=Files.createTempFile("ct-update-",".apk");file.transferTo(temp);
            if(Files.size(temp)!=file.getSize())throw new BizException("安装包上传不完整");
            var apk=ApkMetadata.read(temp);String sha=sha256(temp);
            // A fresh immutable object key ensures old cached bytes can never masquerade as the new APK.
            var object=storage.put(storage.active(),"app-updates/"+UUID.randomUUID()+".apk",temp,"application/vnd.android.package-archive");
            db.update("UPDATE app_release SET package_url=?,package_name=?,apk_version_code=?,apk_version_name=?,version_code=?,version_name=?,size_bytes=?,sha256=? WHERE id=?",object.url(),apk.packageName(),apk.versionCode(),apk.versionName(),apk.versionCode(),apk.versionName().isBlank()?r.versionName():apk.versionName(),object.sizeBytes(),sha,id);
            return require(id);
        }catch(IOException e){throw new BizException("安装包保存失败，请重试");}finally{if(temp!=null)try{Files.deleteIfExists(temp);}catch(IOException ignored){}}
    }
    public static void validateFile(MultipartFile f){if(f==null||f.isEmpty()||f.getSize()>MAX_APK_BYTES||f.getOriginalFilename()==null||!f.getOriginalFilename().toLowerCase(Locale.ROOT).endsWith(".apk"))throw new BizException("请上传不超过500MB的 APK 安装包");}
    static String sha256(Path p)throws IOException{try{var digest=MessageDigest.getInstance("SHA-256");try(var input=Files.newInputStream(p)){byte[] buffer=new byte[65536];for(int n;(n=input.read(buffer))!=-1;)digest.update(buffer,0,n);}return HexFormat.of().formatHex(digest.digest());}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    @Transactional public Release publish(long id){
        channelLock();var r=draftLock(id);validate(new Draft(r.versionName(),r.versionCode(),r.notes(),r.forceUpdate()));
        if(r.packageUrl()==null||r.sha256()==null||r.sizeBytes()<1||r.apkVersionCode()==null||r.apkVersionCode()!=r.versionCode()||(!r.apkVersionName().isEmpty()&&!r.apkVersionName().equals(r.versionName())))throw new BizException("请上传与版本信息一致的 APK 后再发布");
        Long max=db.queryForObject("SELECT COALESCE(MAX(version_code),0) FROM app_release WHERE status<>'draft'",Long.class);
        if(max!=null&&r.versionCode()<=max)throw new BizException("新发布的版本号须大于历史已发布版本");
        db.update("UPDATE app_release SET status='published',published_at=CURRENT_TIMESTAMP WHERE id=?",id);
        db.update("UPDATE app_update_channel SET active_release_id=? WHERE id=1",id);return require(id);
    }
    @Transactional public Release withdraw(long id){channelLock();var r=require(id);if(!r.current())throw new BizException("只能下架当前生效版本");db.update("UPDATE app_update_channel SET active_release_id=NULL WHERE id=1");db.update("UPDATE app_release SET status='withdrawn' WHERE id=?",id);return require(id);}
    @Transactional public void delete(long id){
        cleanup.track("app_release",id);
        // Match publish's lock order and wait for any draft save/upload before reading its package URL.
        channelLock();var r=releaseLock(id);
        if(r.current())throw new BizException("当前生效版本不能删除，请先下架");
        db.update("DELETE FROM app_release WHERE id=?",id);
        // File deletion is durable and occurs only after the business transaction commits.
    }
    @Transactional public Release force(long id,boolean force){channelLock();require(id);db.update("UPDATE app_release SET force_update=? WHERE id=?",force,id);return require(id);}
    public Check check(long versionCode){if(versionCode<0)throw new BizException("版本号无效");return db.query(SELECT+" WHERE r.id=c.active_release_id AND r.status='published' AND r.version_code>?",ROW,versionCode).stream().findFirst().map(r->new Check(true,r)).orElse(new Check(false,null));}
}
