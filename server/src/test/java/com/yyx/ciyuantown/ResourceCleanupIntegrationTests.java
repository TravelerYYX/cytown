package com.yyx.ciyuantown;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yyx.ciyuantown.common.storage.*;
import com.yyx.ciyuantown.common.util.JwtUtil;
import com.yyx.ciyuantown.business.post.service.*;
import com.yyx.ciyuantown.business.publish.dto.PublishRequest;
import com.yyx.ciyuantown.business.publish.service.*;
import com.yyx.ciyuantown.business.appuser.service.UserDeletionService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Opt-in: verify-resource-cleanup.py creates the complete schema in a disposable MySQL database. */
@EnabledIfEnvironmentVariable(named="CT_RESOURCE_CLEANUP_TEST",matches="true")
@SpringBootTest(properties={"ciyuantown.redis.enabled=false","ciyuantown.jwt.secret=resource-cleanup-isolated-test-secret-1234567890","ciyuantown.upload-dir=./target/resource-legacy-uploads"})
@AutoConfigureMockMvc
class ResourceCleanupIntegrationTests {
 @Autowired JdbcTemplate db; @Autowired StorageService storage; @Autowired GlobalSettingsService settings;
 @Autowired ResourceCleanupService cleanup; @Autowired PostService posts; @Autowired CommentService comments;
 @Autowired PublishMediaService mediaService; @Autowired MediaTranscodeWorker worker; @Autowired FfmpegService ffmpeg;
 @Autowired PublishService publish; @Autowired UserDeletionService users; @Autowired PlatformTransactionManager transactions;
 @Autowired com.yyx.ciyuantown.business.publish.service.PublishAdminService catalogs;
 @Autowired com.yyx.ciyuantown.business.notice.service.NoticeService notices;
 @Autowired com.yyx.ciyuantown.business.homeconfig.service.HomeConfigService home;
 @Autowired com.yyx.ciyuantown.business.mineentry.MineEntryService mine;
 @Autowired com.yyx.ciyuantown.business.appuser.service.SystemAvatarService avatars;
 @Autowired MockMvc mvc; @Autowired JwtUtil jwt; @Autowired ObjectMapper json;
 @Autowired com.yyx.ciyuantown.business.chat.ChatAdministration chatAdmin;
 @Autowired com.yyx.ciyuantown.business.chat.ChatService chat;
 @TempDir Path root;
 long user,admin; StorageService.Config config;
 @BeforeEach void setup(){
  assertTrue(db.queryForObject("SELECT DATABASE()",String.class).startsWith("ciyuantown_account_test_cleanup_"));
  settings.save(new GlobalSettingsService.Settings(true));
  config=new StorageService.Config("","local",root.toAbsolutePath().toString(),"","","","","","");
  config=storage.config(storage.save(config).id());
  user=insert("app_user",Map.of("qq_number","8"+Math.floorMod(System.nanoTime(),10000000000L),"email",UUID.randomUUID()+"@cleanup.test","nickname","资源测试"));
  admin=db.queryForObject("SELECT MIN(id) FROM admin_user WHERE user_type=0",Long.class);
 }
 @AfterEach void restore(){settings.save(new GlobalSettingsService.Settings(true));db.update("DELETE FROM system_config WHERE config_key='media_storage_active'");}
 long insert(String table,Map<String,?> fields){return new SimpleJdbcInsert(db).withTableName("`"+table+"`").usingColumns(fields.keySet().toArray(String[]::new)).usingGeneratedKeyColumns("id").executeAndReturnKey(fields).longValue();}
 long seedPost(){return insert("post",Map.of("author_id",user,"author_nickname","资源测试","title","资源删除验证","status",1));}
 StorageService.Stored file(String key)throws Exception{return storage.put(config,key,Files.write(root.resolve("input"),new byte[]{1,2,3}),"image/png");}
 record Asset(long id,List<StorageService.Stored> files){}
 Asset asset()throws Exception{
  String prefix="posts/"+user+"/"+UUID.randomUUID()+"/";
  var files=new ArrayList<StorageService.Stored>();
  for(String key:List.of("original.png","preview.webp","cover.webp","cover-old.webp","hls-test/master.m3u8","hls-test/360/index.m3u8","hls-test/360/segment-000.ts","hls-test/download.mp4"))files.add(file(prefix+key));
  long id=insert("upload_asset",Map.of("owner_id",user,"media_type","image","original_name","image.png","content_type","image/png","size_bytes",3,"status","ready","attached",false,"url",files.get(1).url(),"original_url",files.getFirst().url(),"cover_url",files.get(2).url()));
  db.update("UPDATE upload_asset SET preview_url=?,hls_url=? WHERE id=?",files.get(1).url(),files.get(4).url(),id);
  return new Asset(id,files);
 }
 void attach(long post,Asset asset){insert("post_media",Map.of("post_id",post,"asset_id",asset.id(),"url",asset.files().get(1).url(),"cover_url",asset.files().get(2).url(),"type","image","layout","wide","aspect_ratio",1.5));db.update("UPDATE upload_asset SET attached=TRUE WHERE id=?",asset.id());}
 void gone(Asset asset){for(var file:asset.files()){assertFalse(Files.exists(storage.localPath(config,file.key())),file.key());assertTrue(storage.find(file.url()).isEmpty());}assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM upload_asset WHERE id=?",Integer.class,asset.id()));}
 void exists(Asset asset){for(var file:asset.files())assertTrue(Files.exists(storage.localPath(config,file.key())),file.key());}
 TransactionTemplate tx(){return new TransactionTemplate(transactions);}
 long draft(Asset asset){return insert("post_draft",Map.of("author_id",user,"mode","image","payload","{\"assets\":[{\"id\":\""+asset.id()+"\"}]}","cover_url",asset.files().get(2).url()));}
 void futureDue(){db.update("UPDATE media_cleanup_task SET next_attempt_at=CURRENT_TIMESTAMP,lease_until=NULL");}

 @Test void explicitChatDeletionOverridesDisabledSwitchAndSurvivesStorageFailure()throws Exception{
  long peer=insert("app_user",Map.of("qq_number","7"+Math.floorMod(System.nanoTime(),10000000000L),"email",UUID.randomUUID()+"@cleanup.test","nickname","私聊接收方"));
  db.update("INSERT INTO user_follow(follower_id,followee_id) VALUES(?,?)",user,peer);
  var object=file("chat/"+UUID.randomUUID()+"/0.bin");
  long asset=insert("chat_asset",Map.of("owner_id",user,"kind","image","state","ready","version","chat-test","manifest_json",json.writeValueAsString(Map.of("version","chat-test","size",3,"parts",List.of(Map.of("url",object.url(),"iv","test","bytes",3)))),"config_json","{}","created_at",System.currentTimeMillis()));
  var message=chat.send(user,new com.yyx.ciyuantown.business.chat.ChatService.Send(""+peer,"cleanup_chat_"+UUID.randomUUID(),"image","",""+asset));
  settings.save(new GlobalSettingsService.Settings(false));Path file=storage.localPath(config,object.key());Files.delete(file);Files.createDirectory(file);Files.writeString(file.resolve("blocked"),"simulate storage failure");
  var job=chatAdmin.delete(admin,Long.parseLong(message.conversationId()),Long.parseLong(message.id()));
  assertEquals("cleaning",job.state());cleanup.retry();assertEquals("retrying",chatAdmin.job(admin,job.id()).state());assertTrue(Files.exists(file.resolve("blocked")));
  Files.delete(file.resolve("blocked"));Files.delete(file);Files.writeString(file,"retry");futureDue();
  new ResourceCleanupService(db,json,storage,settings,transactions).retry();
  assertFalse(Files.exists(file));assertTrue(storage.find(object.url()).isEmpty());assertEquals("completed",chatAdmin.job(admin,job.id()).state());assertFalse(settings.enabled());
  assertTrue(chat.message(peer,Long.parseLong(message.id())).deleted());assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM chat_asset WHERE id=?",Integer.class,asset));
 }

 @Test void chatManagementRejectsReadonlyAndAppTokensOnRealInterceptors()throws Exception{
  long official=insert("admin_user",Map.of("username","chat-read-"+UUID.randomUUID(),"password","unused","nickname","只读","user_type",1));
  String readonly="Bearer "+jwt.createAccessToken(official,"admin",0,1),app="Bearer "+jwt.createAccessToken(user,"app",0,0);
  mvc.perform(get("/chat-admin/conversations").header("Authorization",readonly)).andExpect(jsonPath("$.code").value("1000"));
  mvc.perform(get("/chat-admin/assets/1/content").header("Authorization",readonly)).andExpect(jsonPath("$.code").value("1000"));
  mvc.perform(get("/chat-admin/conversations").header("Authorization",app)).andExpect(jsonPath("$.code").value("9999"));
  mvc.perform(post("/chat-admin/restrictions").header("Authorization",readonly).contentType("application/json").content("{\"userId\":\"1\",\"conversationId\":\"0\",\"action\":\"block\"}")).andExpect(jsonPath("$.msg").value("该账号为只读账号，无操作权限"));
 }

 void assertRestrictionWinsWaitingSend(boolean accountBan)throws Exception{
  long peer=insert("app_user",Map.of("qq_number","6"+Math.floorMod(System.nanoTime(),10000000000L),"email",UUID.randomUUID()+"@cleanup.test","nickname","并发私聊接收方"));
  db.update("INSERT INTO user_follow(follower_id,followee_id) VALUES(?,?)",user,peer);chat.relationship(user,peer);
  var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
  try(var executor=Executors.newFixedThreadPool(2)){
   var moderation=executor.submit(()->tx().executeWithoutResult(status->{if(accountBan)chatAdmin.status(admin,user,1);else chatAdmin.rule(admin,new com.yyx.ciyuantown.business.chat.ChatAdministration.Rule(""+user,"0","block",null));locked.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("test release timeout");}catch(InterruptedException e){throw new RuntimeException(e);}}));
   assertTrue(locked.await(5,TimeUnit.SECONDS));
   var send=executor.submit(()->{try{chat.send(user,new com.yyx.ciyuantown.business.chat.ChatService.Send(""+peer,"waiting_"+UUID.randomUUID(),"text","must be blocked",null));return false;}catch(com.yyx.ciyuantown.common.exception.BizException expected){return true;}});
   boolean waiting=false;try{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(System.nanoTime()<deadline){if(db.queryForObject("SELECT COUNT(*) FROM performance_schema.data_lock_waits w JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID AND l.ENGINE=w.ENGINE WHERE l.OBJECT_SCHEMA=DATABASE() AND l.OBJECT_NAME='chat_stream'",Long.class)>0){waiting=true;break;}Thread.sleep(20);}}finally{release.countDown();}
   moderation.get(10,TimeUnit.SECONDS);assertTrue(waiting,"sender must actually wait behind the uncommitted moderation transaction");assertTrue(send.get(10,TimeUnit.SECONDS),"committed restriction must be observed after acquiring the stream lock");
  }finally{release.countDown();}
 }
 @Test void mysqlSendWaitingBehindPrivateBanCannotUseAnOlderSnapshot()throws Exception{assertRestrictionWinsWaitingSend(false);}
 @Test void mysqlSendWaitingBehindAccountBanCannotUseAnOlderSnapshot()throws Exception{assertRestrictionWinsWaitingSend(true);}

 @Test void settingsDefaultPersistValidationAndPermissions()throws Exception{
  db.update("DELETE FROM system_config WHERE config_key='delete_resource_files_enabled'");assertTrue(settings.get().deleteResourceFilesEnabled());
  String token="Bearer "+jwt.createAccessToken(admin,"admin",0,0);
  mvc.perform(post("/global-settings").header("Authorization",token).contentType("application/json").content("{\"deleteResourceFilesEnabled\":false}")).andExpect(jsonPath("$.code").value("0000"));
  mvc.perform(get("/global-settings").header("Authorization",token)).andExpect(jsonPath("$.data.deleteResourceFilesEnabled").value(false));
  mvc.perform(post("/global-settings").header("Authorization",token).contentType("application/json").content("{}")).andExpect(jsonPath("$.code").value("1000"));
  long official=insert("admin_user",Map.of("username","cleanup-"+UUID.randomUUID(),"password","unused","nickname","只读","user_type",1));
  mvc.perform(post("/global-settings").header("Authorization","Bearer "+jwt.createAccessToken(official,"admin",0,1)).contentType("application/json").content("{\"deleteResourceFilesEnabled\":true}")).andExpect(jsonPath("$.msg").value("该账号为只读账号，无操作权限"));
  assertFalse(settings.enabled());
  mvc.perform(get("/global-settings")).andExpect(jsonPath("$.code").value("8888"));
 }
 @Test void postCascadeDeletesAllVersionsAndReplyFiles()throws Exception{
  long post=seedPost();Asset media=asset(),replyMedia=asset();attach(post,media);
  long comment=insert("comment",Map.of("post_id",post,"author_nickname","根评论","author_id",user));
  long reply=insert("comment",Map.of("post_id",post,"parent_id",comment,"author_nickname","回复","author_id",user));
  insert("comment_media",Map.of("comment_id",reply,"asset_id",replyMedia.id(),"url",replyMedia.files().get(1).url()));
  db.update("INSERT INTO media_job(asset_id,config_json) VALUES(?,'{}')",media.id());
  posts.delete(post);gone(media);gone(replyMedia);
  assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM media_job WHERE asset_id=?",Integer.class,media.id()));
 }
 @Test void rollbackLeavesRowsFilesAndNoTask()throws Exception{
  long post=seedPost();Asset media=asset();attach(post,media);
  tx().executeWithoutResult(status->{posts.delete(post);status.setRollbackOnly();});
  exists(media);assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM post WHERE id=?",Integer.class,post));
  assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM media_cleanup_task WHERE completed=FALSE AND object_key LIKE ?",Integer.class,"posts/"+user+"/%"));
 }
 @Test void disabledRetainsFilesPermanentlyAfterReenable()throws Exception{
  long post=seedPost();Asset media=asset();attach(post,media);settings.save(new GlobalSettingsService.Settings(false));posts.delete(post);exists(media);
  assertEquals(media.files().size(),db.queryForObject("SELECT COUNT(*) FROM media_resource_retention WHERE object_key LIKE ?",Integer.class,"posts/"+user+"/%"));
  settings.save(new GlobalSettingsService.Settings(true));cleanup.retry();exists(media);
  assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM media_cleanup_task WHERE completed=FALSE AND object_key LIKE ?",Integer.class,"posts/"+user+"/%"));
 }
 @Test void storageFailurePauseRestartAndRetry()throws Exception{
  var file=file("config/failure-"+UUID.randomUUID()+".png");long topic=insert("topic",Map.of("name","失败"+UUID.randomUUID(),"cover_url",file.url()));
  Path path=storage.localPath(config,file.key());Files.delete(path);Files.createDirectory(path);Files.writeString(path.resolve("blocked"),"keep");
  tx().executeWithoutResult(status->{cleanup.track("topic",topic);db.update("DELETE FROM topic WHERE id=?",topic);});
  assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM media_cleanup_task WHERE public_url=?",Integer.class,file.url()));
  settings.save(new GlobalSettingsService.Settings(false));Files.delete(path.resolve("blocked"));Files.delete(path);Files.writeString(path,"retry");futureDue();cleanup.retry();assertTrue(Files.exists(path));
  settings.save(new GlobalSettingsService.Settings(true));
  new ResourceCleanupService(db,json,storage,settings,transactions).retry();assertFalse(Files.exists(path));assertTrue(storage.find(file.url()).isEmpty());
  cleanup.retry(); // Repeated processing remains idempotent.
 }
 @Test void sharedDraftProtectsMediaUntilFinalReferenceIsDeleted()throws Exception{
  Asset media=asset();long one=draft(media),two=draft(media);
  publish.deleteDraft(user,one);exists(media);
  publish.deleteDraft(user,two);gone(media);
 }
 @Test void publishTransferProtectsMediaWhileDiscardedDraftMediaIsDeleted()throws Exception{
  Asset media=asset(),discarded=asset();long draft=draft(media);
  db.update("UPDATE post_draft SET payload=? WHERE id=?","{\"assets\":[{\"id\":"+media.id()+"},{\"id\":"+discarded.id()+"}]}",draft);
  db.update("UPDATE upload_asset SET hls_url='' WHERE id=?",media.id());
  long channel=db.queryForObject("SELECT MIN(id) FROM home_channel WHERE allow_publish=TRUE AND admin_only=FALSE",Long.class);
  publish.submit(user,new PublishRequest.Submit(draft,"image",channel,"测试发布标题","测试发布正文",null,List.of(media.id()),List.of(),List.of(),true,false,false,null));
  exists(media);gone(discarded);
 }
 @Test void replacementDeletesOnlyOldUnreferencedCover()throws Exception{
  Asset media=asset();String old=media.files().get(2).url();var replacement=file("config/new-"+UUID.randomUUID()+".webp");
  tx().executeWithoutResult(status->{cleanup.track("upload_asset",media.id());db.update("UPDATE upload_asset SET cover_url=? WHERE id=?",replacement.url(),media.id());});
  assertTrue(storage.find(old).isEmpty());assertTrue(storage.find(replacement.url()).isPresent());assertTrue(storage.find(media.files().getFirst().url()).isPresent());
 }
 @Test void emojiSnapshotProtectsFileAfterCatalogDeletion()throws Exception{
  var file=file("config/emoji-"+UUID.randomUUID()+".png");long group=insert("emoji_group",Map.of("name","共享表情"));
  long emoji=insert("publish_emoji",Map.of("group_id",group,"name","共享","image_url",file.url()));long post=seedPost();
  db.update("UPDATE post SET content_blocks=? WHERE id=?","[{\"type\":\"paragraph\",\"runs\":[{\"type\":\"emoji\",\"imageUrl\":\""+file.url()+"\"}]}]",post);
  tx().executeWithoutResult(status->{cleanup.track("publish_emoji",emoji);db.update("DELETE FROM publish_emoji WHERE id=?",emoji);});
  assertTrue(storage.find(file.url()).isPresent());posts.delete(post);assertTrue(storage.find(file.url()).isEmpty());
 }
 @Test void accountDeletionKeepsPostsButDeletesPrivateDraftsAndAvatar()throws Exception{
  Asset published=asset(),privateMedia=asset();long post=seedPost();attach(post,published);draft(privateMedia);var avatar=file("app-avatar/"+UUID.randomUUID()+".png");db.update("UPDATE app_user SET avatar=? WHERE id=?",avatar.url(),user);
  users.deleteApp(admin,user);exists(published);gone(privateMedia);assertTrue(storage.find(avatar.url()).isEmpty());
  assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM post WHERE id=?",Integer.class,post));
 }
 @Test void pendingDeletionRejectsNewReferences()throws Exception{
  var file=file("config/concurrent-"+UUID.randomUUID()+".png");long topic=insert("topic",Map.of("name","并发"+UUID.randomUUID(),"cover_url",file.url()));
  Path path=storage.localPath(config,file.key());Files.delete(path);Files.createDirectory(path);Files.writeString(path.resolve("blocked"),"failure");
  tx().executeWithoutResult(status->{cleanup.track("topic",topic);db.update("DELETE FROM topic WHERE id=?",topic);});
  assertThrows(Exception.class,()->tx().executeWithoutResult(status->{cleanup.lock();db.update("UPDATE app_user SET avatar=? WHERE id=?",file.url(),user);}));
  assertNotEquals(file.url(),db.queryForObject("SELECT avatar FROM app_user WHERE id=?",String.class,user));
  Files.delete(path.resolve("blocked"));Files.delete(path);futureDue();cleanup.retry();
 }
 @Test void commentTombstoneAndCascadeBothCleanOnlyRemovedMedia()throws Exception{
  for(boolean cascade:List.of(false,true)){
   db.update("INSERT INTO system_config(config_key,config_value) VALUES('comment_delete_cascade',?) ON DUPLICATE KEY UPDATE config_value=VALUES(config_value)",cascade?"1":"0");
   long post=seedPost();Asset rootMedia=asset(),replyMedia=asset();
   long comment=insert("comment",Map.of("post_id",post,"author_nickname","根评论","author_id",user));
   long reply=insert("comment",Map.of("post_id",post,"parent_id",comment,"author_nickname","回复","author_id",user));
   insert("comment_media",Map.of("comment_id",comment,"asset_id",rootMedia.id(),"url",rootMedia.files().get(1).url()));
   insert("comment_media",Map.of("comment_id",reply,"asset_id",replyMedia.id(),"url",replyMedia.files().get(1).url()));
   comments.delete(post,comment,user);gone(rootMedia);
   if(cascade)gone(replyMedia);else {exists(replyMedia);comments.delete(post,reply,user);gone(replyMedia);}
  }
 }
 @Test void configurationDeletionEntrypointsRetireTheirFiles()throws Exception{
  var topic=file("config/topic-"+UUID.randomUUID()+".png");long topicId=insert("topic",Map.of("name","删除"+UUID.randomUUID(),"cover_url",topic.url()));catalogs.deleteTopic(topicId);assertTrue(storage.find(topic.url()).isEmpty());
  var icon=file("config/home-"+UUID.randomUUID()+".png");long entry=insert("home_entry",Map.of("name","入口","icon_url",icon.url(),"link_url","https://example.test"));home.deleteEntry(entry);assertTrue(storage.find(icon.url()).isEmpty());
  var mineIcon=file("config/mine-"+UUID.randomUUID()+".svg");long mineId=insert("mine_entry",Map.of("title","我的入口","description","测试","icon_url",mineIcon.url(),"link_url","https://example.test"));mine.delete(mineId);assertTrue(storage.find(mineIcon.url()).isEmpty());
  var avatar=file("system-avatar/"+UUID.randomUUID()+".png");long avatarId=insert("app_system_avatar",Map.of("url",avatar.url(),"type",0));avatars.delete(avatarId);assertTrue(storage.find(avatar.url()).isEmpty());
  var noticeImage=file("notice/"+UUID.randomUUID()+".png");var categoryIcon=file("config/category-"+UUID.randomUUID()+".png");long category=insert("notice_category",Map.of("name","通知清理","icon_url",categoryIcon.url()));
  long notice=insert("notice",Map.of("category_id",category,"title","通知清理","blocks","[{\"type\":\"image\",\"url\":\""+noticeImage.url()+"\"}]"));notices.delete(notice);assertTrue(storage.find(noticeImage.url()).isEmpty());notices.deleteCategory(category);assertTrue(storage.find(categoryIcon.url()).isEmpty());
 }
 @Test void expiryProtectsDraftsAndSwitchOffRetainsUnusedFiles()throws Exception{
  Asset inDraft=asset(),unused=asset();draft(inDraft);
  tx().executeWithoutResult(status->cleanup.expireAsset(inDraft.id()));exists(inDraft);
  settings.save(new GlobalSettingsService.Settings(false));tx().executeWithoutResult(status->cleanup.expireAsset(unused.id()));exists(unused);
  settings.save(new GlobalSettingsService.Settings(true));cleanup.retry();exists(unused);
 }

 @Test void processingVideoCannotUploadAgainAfterPostDeletion()throws Exception{
  Assumptions.assumeTrue(ffmpeg.isAvailable());
  Path fixture=root.resolve("fixture.mp4");ffmpeg.command(List.of("-y","-f","lavfi","-i","testsrc2=size=160x100:rate=6","-t","1","-c:v","libx264","-pix_fmt","yuv420p",fixture.toString()),30,"生成视频夹具失败");
  var uploaded=mediaService.uploadVideo(user,new org.springframework.mock.web.MockMultipartFile("file","fixture.mp4","video/mp4",Files.readAllBytes(fixture)));
  long post=seedPost();insert("post_media",Map.of("post_id",post,"asset_id",uploaded.id(),"url","","cover_url",uploaded.coverUrl(),"type","video","layout","wide","aspect_ratio",1.6));
  var source=storage.find(uploaded.originalUrl()).orElseThrow();String prefix=source.key().substring(0,source.key().lastIndexOf('/')+1);
  String token=UUID.randomUUID().toString();db.update("UPDATE media_job SET state='running',worker_token=?,lease_until=DATE_ADD(NOW(),INTERVAL 5 MINUTE) WHERE asset_id=?",token,uploaded.id());
  var method=MediaTranscodeWorker.class.getDeclaredMethod("process",long.class,String.class);method.setAccessible(true);
  var executor=Executors.newSingleThreadExecutor();
  try {
   final java.util.concurrent.Future<?>[] future=new java.util.concurrent.Future<?>[1];
   tx().executeWithoutResult(status->{cleanup.lock();future[0]=executor.submit(()->{try{method.invoke(worker,uploaded.id(),token);}catch(Exception e){throw new RuntimeException(e);}});posts.delete(post);});
   future[0].get(30,TimeUnit.SECONDS);
   futureDue();cleanup.retry(); // Windows may keep an input file open until ffmpeg exits.
   assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM upload_asset WHERE id=?",Integer.class,uploaded.id()));
   assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM media_job WHERE asset_id=?",Integer.class,uploaded.id()));
   assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM media_object WHERE storage_id=? AND LEFT(object_key,?)=?",Integer.class,source.storageId(),prefix.length(),prefix));
  } finally {executor.shutdownNow();}
 }

 @Test void originalStorageProfileIsUsedAfterSwitchingProviders()throws Exception{
  var old=file("config/old-profile-"+UUID.randomUUID()+".png");long topic=insert("topic",Map.of("name","旧存储"+UUID.randomUUID(),"cover_url",old.url()));
  storage.save(new StorageService.Config("","local",root.resolve("new-store").toAbsolutePath().toString(),"","","","","",""));
  catalogs.deleteTopic(topic);assertFalse(Files.exists(storage.localPath(config,old.key())));assertTrue(storage.find(old.url()).isEmpty());
 }
 @Test void legacyLocalFilesDeleteWithoutRegistryAndExternalLinksAreIgnored()throws Exception{
  String key="posts/legacy-"+UUID.randomUUID()+".png";Path legacy=Path.of("target/resource-legacy-uploads").resolve(key);Files.createDirectories(legacy.getParent());Files.writeString(legacy,"legacy");
  long post=seedPost();insert("post_media",Map.of("post_id",post,"type","image","layout","wide","aspect_ratio",1.5,"url","/uploads/"+key));posts.delete(post);assertFalse(Files.exists(legacy));
  long topic=insert("topic",Map.of("name","外部"+UUID.randomUUID(),"cover_url","https://example.invalid/external.png"));catalogs.deleteTopic(topic);
  assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM media_cleanup_task WHERE completed=FALSE AND public_url='https://example.invalid/external.png'",Integer.class));
 }

 @Test void completedDeletionRejectsStaleUrlBinding()throws Exception{
  var file=file("config/stale-"+UUID.randomUUID()+".png");long topic=insert("topic",Map.of("name","已删除"+UUID.randomUUID(),"cover_url",file.url()));catalogs.deleteTopic(topic);
  assertTrue(storage.find(file.url()).isEmpty());
  assertThrows(Exception.class,()->tx().executeWithoutResult(status->{cleanup.lock();db.update("UPDATE app_user SET avatar=? WHERE id=?",file.url(),user);}));
  assertNotEquals(file.url(),db.queryForObject("SELECT avatar FROM app_user WHERE id=?",String.class,user));
 }

}
