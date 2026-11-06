package com.yyx.ciyuantown.business.notice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yyx.ciyuantown.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Duration;
import java.util.*;

@Service @RequiredArgsConstructor @Slf4j
public class NoticePushService {
 private final JdbcTemplate db;private final ObjectMapper json;
 @Value("${NOTICE_CONFIG_KEY:${MEDIA_CONFIG_KEY:${ciyuantown.jwt.secret}}}") private String encryptionKey;
 private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
 public record Config(boolean enabled,String appId,String endpoint,String secret,boolean secretConfigured) {}
 public Config config(){
  return db.queryForObject("SELECT * FROM notice_push_config WHERE id=1",(rs,i)->new Config(rs.getBoolean("enabled"),rs.getString("app_id"),rs.getString("endpoint"),decrypt(rs.getString("secret_cipher")),!rs.getString("secret_cipher").isBlank()));
 }
 public Config masked(){var c=config();return new Config(c.enabled(),c.appId(),c.endpoint(),"",c.secretConfigured());}
 @Transactional public Config save(Config p){
  var old=config();String secret=p.secret()==null||p.secret().isBlank()?old.secret():p.secret();
  var c=new Config(p.enabled(),clean(p.appId()),clean(p.endpoint()),secret,!secret.isBlank());
  if(c.enabled())validate(c);
  db.update("UPDATE notice_push_config SET enabled=?,app_id=?,endpoint=?,secret_cipher=?,updated_at=CURRENT_TIMESTAMP WHERE id=1",c.enabled(),c.appId(),c.endpoint(),encrypt(secret));
  if(!c.enabled())db.update("UPDATE notice_push_task SET status='cancelled',lease_until=NULL WHERE status IN ('pending','sending')");
  return masked();
 }
 private static String clean(String value){return value==null?"":value.trim();}
 private void validate(Config c){
  try{URI uri=URI.create(c.endpoint());if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getFragment()!=null)throw new Exception();}catch(Exception e){throw new BizException("推送接口必须为有效HTTPS地址");}
  if(c.appId().isBlank()||c.appId().length()>100||c.secret().length()<32)throw new BizException("请填写应用标识及至少32位签名密钥");
 }
 public void requireEnabled(){var c=config();if(!c.enabled())throw new BizException("请先开启并配置系统推送");validate(c);}
 @Transactional public void bind(Long user,String cid){bind(user,cid,null);}
 @Transactional public void bind(Long user,String cid,Integer expectedVersion){
  validateCid(cid);Integer ver=db.queryForObject("SELECT COALESCE(token_version,0) FROM app_user WHERE id=? AND status=0 AND (deleted=FALSE OR deleted IS NULL) FOR UPDATE",Integer.class,user);
  if(expectedVersion!=null&&!expectedVersion.equals(ver))throw new BizException("登录状态已改变，请重新登录");
  db.update("UPDATE notice_push_task SET status='cancelled' WHERE cid=? AND status IN ('pending','sending') AND (user_id<>? OR token_version<>?)",cid,user,ver);
  db.update("INSERT INTO notice_push_device(cid,user_id,token_version) VALUES(?,?,?) ON DUPLICATE KEY UPDATE user_id=VALUES(user_id),token_version=VALUES(token_version),updated_at=CURRENT_TIMESTAMP",cid,user,ver);
 }
 @Transactional public void unbind(Long user,String cid){
  validateCid(cid);db.update("DELETE FROM notice_push_device WHERE cid=? AND user_id=?",cid,user);
  db.update("UPDATE notice_push_task SET status='cancelled' WHERE cid=? AND user_id=? AND status IN ('pending','sending')",cid,user);
 }
 @Transactional public void unbindAll(Long user){
  db.update("DELETE FROM notice_push_device WHERE user_id=?",user);db.update("UPDATE notice_push_task SET status='cancelled' WHERE user_id=? AND status IN ('pending','sending')",user);
 }
 private void validateCid(String cid){if(cid==null||!cid.matches("[A-Za-z0-9_-]{1,128}"))throw new BizException("设备推送标识不合法");}
 public void enqueue(Long publication){
  var devices=db.queryForList("SELECT d.* FROM notice_push_device d JOIN app_user u ON u.id=d.user_id JOIN notice_publication p ON p.id=? WHERE u.status=0 AND (u.deleted=FALSE OR u.deleted IS NULL) AND d.token_version=COALESCE(u.token_version,0) AND (p.audience_type='all' OR EXISTS (SELECT 1 FROM notice_publication_recipient r WHERE r.publication_id=p.id AND r.user_id=u.id))",publication);
  for(var d:devices)db.update("INSERT IGNORE INTO notice_push_task(publication_id,user_id,cid,token_version,request_id) VALUES(?,?,?,?,?)",publication,d.get("user_id"),d.get("cid"),d.get("token_version"),UUID.randomUUID().toString().replace("-",""));
 }
 public List<Map<String,Object>> tasks(Long notice){return db.queryForList("SELECT t.status,COUNT(*) AS count FROM notice_push_task t JOIN notice n ON n.publication_id=t.publication_id WHERE n.id=? GROUP BY t.status",notice);}
 @Transactional public void retry(Long notice){
  requireEnabled();db.update("UPDATE notice_push_task SET status='pending',attempts=0,next_attempt_at=CURRENT_TIMESTAMP,last_error='' WHERE status='failed' AND publication_id=(SELECT publication_id FROM notice WHERE id=? AND status=1)",notice);
 }
 public void test(String cid){requireEnabled();validateCid(cid);send(config(),Map.of("requestId",UUID.randomUUID().toString().replace("-",""),"cid",cid,"title","次元町推送测试","content","系统推送连接测试","payload",Map.of("type","test")));}
 @Scheduled(fixedDelayString="${ciyuantown.notice-push-delay:5000}") public void dispatch(){
  // Publication transactions and worker transactions use separate connections: uncommitted tasks cannot escape.
  try{
   var c=config();if(!c.enabled())return;
   db.update("UPDATE notice_push_task SET status='pending',lease_until=NULL WHERE status='sending' AND lease_until<CURRENT_TIMESTAMP");
   var ids=db.queryForList("SELECT id FROM notice_push_task WHERE status='pending' AND next_attempt_at<=CURRENT_TIMESTAMP ORDER BY id LIMIT 20",Long.class);
   for(Long id:ids){
    if(db.update("UPDATE notice_push_task SET status='sending',lease_until=?,attempts=attempts+1 WHERE id=? AND status='pending'",java.sql.Timestamp.from(java.time.Instant.now().plusSeconds(120)),id)!=1)continue;
    var rows=db.queryForList("SELECT t.*,n.title,n.category_id,n.id AS notice_id FROM notice_push_task t JOIN notice_publication p ON p.id=t.publication_id JOIN notice n ON n.publication_id=p.id JOIN notice_category c ON c.id=n.category_id JOIN app_user u ON u.id=t.user_id JOIN notice_push_device d ON d.cid=t.cid AND d.user_id=t.user_id AND d.token_version=t.token_version WHERE t.id=? AND t.status='sending' AND n.status=1 AND c.enabled=TRUE AND u.status=0 AND (u.deleted=FALSE OR u.deleted IS NULL) AND COALESCE(u.token_version,0)=t.token_version",id);
    if(rows.isEmpty()||!config().enabled()){db.update("UPDATE notice_push_task SET status='cancelled',lease_until=NULL WHERE id=?",id);continue;}
    var task=rows.getFirst();try{
     send(c,Map.of("requestId",task.get("request_id"),"cid",task.get("cid"),"title","次元町通知","content",truncate(task.get("title").toString(),45),"payload",Map.of("type","notice","noticeId",task.get("notice_id"),"categoryId",task.get("category_id"),"publicationId",task.get("publication_id"))));
     db.update("UPDATE notice_push_task SET status='accepted',lease_until=NULL,last_error='' WHERE id=? AND status='sending'",id);
    }catch(Exception e){
     int attempts=((Number)task.get("attempts")).intValue();
     db.update("UPDATE notice_push_task SET status=?,next_attempt_at=?,lease_until=NULL,last_error=? WHERE id=? AND status='sending'",attempts>=5?"failed":"pending",java.sql.Timestamp.from(java.time.Instant.now().plusSeconds(Math.min(3600,30*(1<<Math.min(attempts,6))))),"推送接口调用失败，请检查配置或云函数日志",id);
    }
   }
  }catch(Exception e){log.warn("通知推送任务暂不可用：{}",e.getClass().getSimpleName());}
 }
 private String truncate(String text,int points){return text.codePointCount(0,text.length())<=points?text:text.substring(0,text.offsetByCodePoints(0,points));}
 public void send(Config c,Map<String,Object> message){
  validate(c);
  try{
   var body=new LinkedHashMap<String,Object>(message);body.put("appId",c.appId());String raw=json.writeValueAsString(body),time=Long.toString(System.currentTimeMillis()),nonce=UUID.randomUUID().toString();
   String signature=sign(c.secret(),time+"\n"+nonce+"\n"+raw);
   var request=HttpRequest.newBuilder(URI.create(c.endpoint())).timeout(Duration.ofSeconds(10)).header("Content-Type","application/json").header("X-CT-Timestamp",time).header("X-CT-Nonce",nonce).header("X-CT-Signature",signature).POST(HttpRequest.BodyPublishers.ofString(raw)).build();
   var response=http.send(request,HttpResponse.BodyHandlers.ofString());var result=json.readTree(response.body());
   if(response.statusCode()!=200||!result.path("accepted").asBoolean(false))throw new BizException("推送服务未受理，请检查配置和云函数日志");
  }catch(InterruptedException e){Thread.currentThread().interrupt();throw new BizException("推送请求已中断");}catch(Exception e){throw new BizException("推送服务未受理，请检查配置和云函数日志");}
 }
 public static String sign(String secret,String input)throws Exception{Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return HexFormat.of().formatHex(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));}
 private SecretKeySpec key()throws Exception{return new SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(encryptionKey.getBytes(StandardCharsets.UTF_8)),"AES");}
 private String encrypt(String value){if(value.isEmpty())return "";try{byte[] iv=new byte[12];new SecureRandom().nextBytes(iv);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key(),new GCMParameterSpec(128,iv));return Base64.getEncoder().encodeToString(iv)+":"+Base64.getEncoder().encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new BizException("推送密钥加密失败");}}
 private String decrypt(String value){if(value.isEmpty())return "";try{var parts=value.split(":");Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.getDecoder().decode(parts[0])));return new String(cipher.doFinal(Base64.getDecoder().decode(parts[1])),StandardCharsets.UTF_8);}catch(Exception e){throw new BizException("推送密钥解密失败");}}
}
