package com.yyx.ciyuantown.common.storage;

import com.aliyun.oss.*;
import com.qiniu.storage.*;
import com.qiniu.util.Auth;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yyx.ciyuantown.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Immutable storage profiles keep old objects readable after switching providers. */
@Service @RequiredArgsConstructor @Slf4j
public class StorageService {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    @Value("${ciyuantown.upload-dir:./uploads}") private String uploadDir;
    @Value("${MEDIA_CONFIG_KEY:${ciyuantown.jwt.secret}}") private String encryptionKey;
    public record Config(String id,String provider,String localDir,String endpoint,String region,String bucket,String accessKey,String secretKey,String domain,
                         String ftpHost,Integer ftpPort,String ftpUsername,String ftpPassword,String ftpRootDir) {
        public Config {
            ftpHost=clean(ftpHost);ftpPort=ftpPort==null?21:ftpPort;ftpUsername=clean(ftpUsername);
            ftpPassword=ftpPassword==null?"":ftpPassword;ftpRootDir=clean(ftpRootDir).isEmpty()?"/":clean(ftpRootDir);
        }
        // Keep source and JSON compatibility with existing storage profiles.
        public Config(String id,String provider,String localDir,String endpoint,String region,String bucket,String accessKey,String secretKey,String domain) {
            this(id,provider,localDir,endpoint,region,bucket,accessKey,secretKey,domain,"",21,"","","/");
        }
        private Config secrets(String access,String secret,String password) {
            return new Config(id,provider,localDir,endpoint,region,bucket,access,secret,domain,ftpHost,ftpPort,ftpUsername,password,ftpRootDir);
        }
    }
    public record Stored(String id,String storageId,String key,String url,String contentType,long sizeBytes) {}
    public Config active() {
        var ids=db.queryForList("SELECT config_value FROM system_config WHERE config_key='media_storage_active'",String.class);
        String root=Path.of(uploadDir).toAbsolutePath().normalize().toString();
        return ids.isEmpty()?new Config(UUID.nameUUIDFromBytes(root.getBytes(StandardCharsets.UTF_8)).toString(),"local",root,"","","","","",""):config(ids.getFirst());
    }
    public Config config(String id) {
        if("local-default".equals(id))return new Config(id,"local",Path.of(uploadDir).toAbsolutePath().normalize().toString(),"","","","","","");
        var rows=db.queryForList("SELECT config_json FROM media_storage_config WHERE id=?",String.class,id);
        if(rows.isEmpty())throw new BizException("资源存储配置不存在");
        try{Config c=json.readValue(rows.getFirst(),Config.class);return c.secrets(decrypt(c.accessKey()),decrypt(c.secretKey()),decrypt(c.ftpPassword()));}catch(Exception e){throw new BizException("资源存储配置解密失败");}
    }
    public Config masked() {var c=active();return c.secrets(c.accessKey().isEmpty()?"":"********","","");}
    @org.springframework.transaction.annotation.Transactional
    public Config save(Config submitted) {
        Config previous=active();Config c=prepare(submitted,previous,UUID.randomUUID().toString());validate(c);
        try{Config encrypted=encrypted(c);
            db.update("INSERT INTO media_storage_config(id,config_json) VALUES(?,?)",c.id(),json.writeValueAsString(encrypted));
            db.update("INSERT INTO system_config(config_key,config_value,remark) VALUES('media_storage_active',?,'当前媒体存储') ON DUPLICATE KEY UPDATE config_value=VALUES(config_value)",c.id());return masked();
        }catch(Exception e){throw new BizException("存储配置保存失败");}
    }
    public void test(Config submitted) {
        Config c=prepare(submitted,active(),UUID.randomUUID().toString());validate(c);
        Path temporary=null;String key="connection-tests/"+UUID.randomUUID()+".txt";boolean uploaded=false;String stage="临时文件创建";
        try {temporary=Files.createTempFile("storage-test-",".txt");Files.writeString(temporary,"ciyuantown storage test");stage="上传与目录访问";upload(c,key,temporary,"text/plain");uploaded=true;stage="存储读取校验";
            if("local".equals(c.provider())){if(!Files.readString(localPath(c,key)).equals("ciyuantown storage test"))throw new IOException();}
            else if("oss".equals(c.provider())){OSS client=oss(c);try(var object=client.getObject(c.bucket(),key)){if(object.getObjectContent().readAllBytes().length==0)throw new IOException();}finally{client.shutdown();}}
            else if("ftp".equals(c.provider())){FtpStorage.download(c,key,temporary);if(!Files.readString(temporary).equals("ciyuantown storage test"))throw new IOException();}
            else {var manager=new BucketManager(auth(c),qiniu(c));manager.stat(c.bucket(),key);}
            if(!"local".equals(c.provider())){stage="HTTP 公开读取";var connection=(HttpURLConnection)URI.create(publicUrl(c,key)).toURL().openConnection();connection.setConnectTimeout(15000);connection.setReadTimeout(15000);
                try{int status=connection.getResponseCode();if(status<200||status>=300)throw new PublicReadFailure("HTTP "+status);
                    try(var input=connection.getInputStream()){if(!Arrays.equals(input.readNBytes(128),"ciyuantown storage test".getBytes(StandardCharsets.UTF_8)))throw new PublicReadFailure("返回内容与上传文件不一致");}
                }finally{connection.disconnect();}}
        }catch(Exception e){
            log.warn("Storage connection test failed: provider={}, stage={}, exceptionType={}",c.provider(),stage,e.getClass().getSimpleName());
            if("ftp".equals(c.provider()))throw new BizException(ftpTestFailure(stage,e));
            throw new BizException("连接检测失败，请检查目录、地域、密钥、访问域名及公开读取权限");}
        finally{try{if(uploaded)remove(c,key);}catch(Exception e){throw new BizException("连接检测临时文件清理失败，请检查存储删除权限");}finally{if(temporary!=null)try{Files.deleteIfExists(temporary);}catch(IOException ignored){}}}
    }
    private static final class PublicReadFailure extends IOException {
        private PublicReadFailure(String detail){super(detail);}
    }
    private static String ftpTestFailure(String stage,Exception failure){
        if(failure instanceof FtpStorage.Failure)return "FTP 连接检测失败："+failure.getMessage();
        String reason=failure instanceof SocketTimeoutException?"连接或响应超时":failure instanceof ConnectException?"连接被拒绝":failure instanceof UnknownHostException?"域名无法解析":failure instanceof javax.net.ssl.SSLException?"HTTPS 证书或 TLS 连接失败":failure instanceof PublicReadFailure?failure.getMessage():"操作失败";
        String suggestion="HTTP 公开读取".equals(stage)?"请检查访问域名与 FTP 根目录的映射及 HTTP 读取权限":"请检查 FTP 服务、目录权限和被动数据端口";
        return "FTP 连接检测失败："+stage+"，"+reason+"；"+suggestion;
    }
    public Stored put(Config c,String key,Path source,String mime) {
        checkKey(key);String id=UUID.randomUUID().toString();boolean uploaded=false;
        try{Config encrypted=encrypted(c);db.update("INSERT IGNORE INTO media_storage_config(id,config_json) VALUES(?,?)",c.id(),json.writeValueAsString(encrypted));upload(c,key,source,mime);uploaded=true;String url=publicUrl(c,key);long size=Files.size(source);
            db.update("INSERT INTO media_object(id,storage_id,object_key,public_url,content_type,size_bytes) VALUES(?,?,?,?,?,?)",id,c.id(),key,url,mime,size);
            if(org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive())org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){@Override public void afterCompletion(int status){if(status!=STATUS_COMMITTED)try{remove(c,key);}catch(Exception ignored){}}});
            return new Stored(id,c.id(),key,url,mime,size);
        }catch(Exception e){if(uploaded)try{remove(c,key);}catch(Exception ignored){}throw uploadFailure(c,e);}
    }
    private BizException uploadFailure(Config c,Exception failure) {
        // Log metadata only: SQL parameters and provider exceptions can contain credentials.
        for(Throwable cause=failure;cause!=null;cause=cause.getCause()) {
            if(cause instanceof java.sql.SQLException sql) {
                log.error("Media upload database failure: provider={}, sqlState={}, errorCode={}",c.provider(),sql.getSQLState(),sql.getErrorCode());
                if(sql.getErrorCode()==1146||sql.getErrorCode()==1054)return new BizException("媒体数据库未升级，请先执行 P12 媒体存储迁移");
                if(sql.getErrorCode()==1142)return new BizException("媒体存储记录写入失败，请检查数据库账号权限");
                return new BizException("媒体存储记录写入失败，请检查后端日志");
            }
        }
        log.error("Media upload storage failure: provider={}, exceptionType={}",c.provider(),failure.getClass().getSimpleName());
        return new BizException("文件写入所选存储失败");
    }
    public String uploadFile(String area,org.springframework.web.multipart.MultipartFile file) {
        String name=Optional.ofNullable(file.getOriginalFilename()).orElse("file");String ext=name.contains(".")?name.substring(name.lastIndexOf('.')+1).toLowerCase(Locale.ROOT):"bin";
        Path temp=null;try{temp=Files.createTempFile("upload-","."+ext);file.transferTo(temp);return put(active(),area+"/"+UUID.randomUUID()+"."+ext,temp,Optional.ofNullable(file.getContentType()).orElse("application/octet-stream")).url();}
        catch(IOException e){throw new BizException("文件上传失败");}finally{if(temp!=null)try{Files.deleteIfExists(temp);}catch(IOException ignored){}}
    }
    public Optional<Stored> find(String url) {if(url==null||url.isBlank())return Optional.empty();return db.query("SELECT * FROM media_object WHERE public_url=?",(r,n)->new Stored(r.getString("id"),r.getString("storage_id"),r.getString("object_key"),r.getString("public_url"),r.getString("content_type"),r.getLong("size_bytes")),url).stream().findFirst();}
    public boolean managed(String url){return url!=null&&(url.startsWith("/uploads/")||find(url).isPresent());}
    public Optional<Stored> findByKey(String id,String key){return db.query("SELECT * FROM media_object WHERE storage_id=? AND object_key=?",(r,n)->new Stored(r.getString("id"),r.getString("storage_id"),r.getString("object_key"),r.getString("public_url"),r.getString("content_type"),r.getLong("size_bytes")),id,key).stream().findFirst();}
    public Path materialize(String url,Path workspace) {
        try{var object=find(url);if(object.isEmpty()){if(url==null||!url.startsWith("/uploads/"))throw new BizException("资源不存在");return localPath(config("local-default"),url.substring("/uploads/".length()));}
            var o=object.get();var c=config(o.storageId());if("local".equals(c.provider()))return localPath(c,o.key());
            Files.createDirectories(workspace);Path target=workspace.resolve(Path.of(o.key()).getFileName().toString());
            if("oss".equals(c.provider())){OSS client=oss(c);try(var body=client.getObject(c.bucket(),o.key())){Files.copy(body.getObjectContent(),target,StandardCopyOption.REPLACE_EXISTING);}finally{client.shutdown();}}
            else if("ftp".equals(c.provider())){FtpStorage.download(c,o.key(),target);}
            else{String privateUrl=auth(c).privateDownloadUrl(publicUrl(c,o.key()),3600);var connection=URI.create(privateUrl).toURL().openConnection();connection.setConnectTimeout(15000);connection.setReadTimeout(60000);try(var input=connection.getInputStream()){Files.copy(input,target,StandardCopyOption.REPLACE_EXISTING);}}
            return target;
        }catch(BizException e){throw e;}catch(Exception e){throw new BizException("媒体读取失败");}
    }
    public void delete(String url) {
        var object=find(url);if(object.isPresent()){var o=object.get();try{remove(config(o.storageId()),o.key());db.update("DELETE FROM media_object WHERE id=?",o.id());}catch(Exception e){throw new BizException("媒体删除失败");}}
        else if(url!=null&&url.startsWith("/uploads/")){try{Files.deleteIfExists(localPath(config("local-default"),url.substring(9)));}catch(IOException e){throw new BizException("本地媒体删除失败");}}
    }
    public void deletePrefix(String storageId,String prefix) {
        checkKey(prefix);var urls=db.queryForList("SELECT public_url FROM media_object WHERE storage_id=? AND object_key LIKE ?",String.class,storageId,prefix.replace("_","\\_").replace("%","\\%")+"%");for(String url:urls)delete(url);
    }
    public Path localPath(Config c,String key){checkKey(key);Path root=Path.of(c.localDir()).toAbsolutePath().normalize(),path=root.resolve(key).normalize();if(!path.startsWith(root)||path.equals(root))throw new BizException("资源路径不合法");return path;}
    public static void checkKey(String key){if(key==null||key.isBlank()||key.startsWith("/")||key.contains("\\")||key.contains(":" )||Arrays.asList(key.split("/")).contains(".."))throw new BizException("资源路径不合法");}
    public static String mime(Path path){String ext=path.getFileName().toString().toLowerCase(Locale.ROOT);if(ext.endsWith(".m3u8"))return "application/vnd.apple.mpegurl";if(ext.endsWith(".ts"))return "video/mp2t";if(ext.endsWith(".webp"))return "image/webp";if(ext.endsWith(".png"))return "image/png";if(ext.endsWith(".gif"))return "image/gif";if(ext.endsWith(".jpg")||ext.endsWith(".jpeg"))return "image/jpeg";return "video/mp4";}
    private String publicUrl(Config c,String key){String encoded=Arrays.stream(key.split("/")).map(v->URLEncoder.encode(v,StandardCharsets.UTF_8).replace("+","%20")).reduce((a,b)->a+"/"+b).orElseThrow();String base=c.domain();if(base.isBlank())base="/uploads/storage/"+c.id();return base.replaceAll("/+$","")+"/"+encoded;}
    private void upload(Config c,String key,Path file,String mime)throws Exception{checkKey(key);
        switch(c.provider()){case "local"->{Path target=localPath(c,key);Files.createDirectories(target.getParent());Path part=target.resolveSibling(target.getFileName()+".part");try{Files.copy(file,part,StandardCopyOption.REPLACE_EXISTING);Files.move(part,target,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(part);}}
            case "oss"->{OSS client=oss(c);try{var meta=new com.aliyun.oss.model.ObjectMetadata();meta.setContentType(mime);meta.setContentLength(Files.size(file));try(var input=Files.newInputStream(file)){client.putObject(c.bucket(),key,input,meta);}}finally{client.shutdown();}}
            case "qiniu"->{var response=new UploadManager(qiniu(c)).put(file.toFile(),key,auth(c).uploadToken(c.bucket()),null,mime,false);if(!response.isOK())throw new IOException("upload");}
            case "ftp"->FtpStorage.upload(c,key,file);
            default->throw new BizException("不支持的存储类型");}
    }
    public void deleteObject(String storageId,String key)throws Exception { remove(config(storageId),key); }
    private void remove(Config c,String key)throws Exception{checkKey(key);switch(c.provider()){
        case "local"->Files.deleteIfExists(localPath(c,key));
        case "oss"->{OSS client=oss(c);try{client.deleteObject(c.bucket(),key);}finally{client.shutdown();}}
        case "qiniu"->{try{new BucketManager(auth(c),qiniu(c)).delete(c.bucket(),key);}catch(com.qiniu.common.QiniuException e){if(e.code()!=612)throw e;}}
        case "ftp"->FtpStorage.delete(c,key);
        default->throw new BizException("不支持的存储类型");}}
    private OSS oss(Config c){return new OSSClientBuilder().build(c.endpoint(),c.accessKey(),c.secretKey());}
    private Auth auth(Config c){return Auth.create(c.accessKey(),c.secretKey());}
    private com.qiniu.storage.Configuration qiniu(Config c){var conf=new com.qiniu.storage.Configuration(switch(c.region()){case "z0"->Region.region0();case "z1"->Region.region1();case "z2"->Region.region2();case "na0"->Region.regionNa0();case "as0"->Region.regionAs0();default->Region.autoRegion();});conf.useHttpsDomains=true;return conf;}
    private Config prepare(Config p,Config old,String id){if(p==null)throw new BizException("存储配置不能为空");boolean same=Objects.equals(p.provider(),old.provider());boolean ftp="ftp".equals(p.provider());
        return new Config(id,clean(p.provider()),clean(p.localDir()),clean(p.endpoint()),clean(p.region()),clean(p.bucket()),"********".equals(p.accessKey())&&same?old.accessKey():clean(p.accessKey()),clean(p.secretKey()).isEmpty()&&same?old.secretKey():clean(p.secretKey()),clean(p.domain()),
                ftp?p.ftpHost():"",ftp?p.ftpPort():21,ftp?p.ftpUsername():"",ftp?(p.ftpPassword().isEmpty()&&same?old.ftpPassword():p.ftpPassword()):"",ftp?p.ftpRootDir():"/");}
    private void validate(Config c){if(!Set.of("local","oss","qiniu","ftp").contains(c.provider()))throw new BizException("请选择本地、OSS、七牛或 FTP 存储");if("local".equals(c.provider())){if(c.localDir().isBlank()||!Path.of(c.localDir()).isAbsolute())throw new BizException("本地存储目录必须为绝对路径");}
        else if("ftp".equals(c.provider())){FtpStorage.validate(c);if(!http(c.domain()))throw new BizException("请填写 FTP 对应的 HTTP/HTTPS 访问地址");}
        else{if(c.bucket().isBlank()||c.accessKey().isBlank()||c.secretKey().isBlank()||!http(c.domain()))throw new BizException("请填写Bucket、密钥和HTTP访问域名");if("oss".equals(c.provider())&&!http(c.endpoint()))throw new BizException("OSS Endpoint需包含http://或https://");}if(!c.domain().isBlank()&&!http(c.domain()))throw new BizException("访问域名需包含http://或https://");}
    public void checkFtp(Config c)throws IOException {FtpStorage.check(c);}
    private Config encrypted(Config c)throws Exception{return c.secrets(encrypt(c.accessKey()),encrypt(c.secretKey()),encrypt(c.ftpPassword()));}
    private static boolean http(String url){try{var uri=URI.create(url);return Set.of("https","http").contains(uri.getScheme())&&uri.getHost()!=null&&uri.getQuery()==null&&uri.getFragment()==null;}catch(Exception e){return false;}}
    private static String clean(String value){return value==null?"":value.trim();}
    private String encrypt(String value)throws Exception{if(value.isEmpty())return "";byte[] iv=new byte[12];new SecureRandom().nextBytes(iv);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key(),new GCMParameterSpec(128,iv));byte[] body=cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));return Base64.getEncoder().encodeToString(iv)+":"+Base64.getEncoder().encodeToString(body);}
    private String decrypt(String value)throws Exception{if(value.isEmpty())return "";String[] parts=value.split(":");Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.getDecoder().decode(parts[0])));return new String(cipher.doFinal(Base64.getDecoder().decode(parts[1])),StandardCharsets.UTF_8);}
    private SecretKeySpec key()throws Exception{return new SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(encryptionKey.getBytes(StandardCharsets.UTF_8)),"AES");}
}
