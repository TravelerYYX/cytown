package com.yyx.ciyuantown;
import com.yyx.ciyuantown.common.storage.*;
import com.yyx.ciyuantown.common.image.*;
import com.yyx.ciyuantown.business.publish.service.*;
import com.yyx.ciyuantown.business.publish.mapper.UploadAssetMapper;
import com.yyx.ciyuantown.business.publish.entity.UploadAsset;
import com.yyx.ciyuantown.common.util.JwtUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.awt.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"ciyuantown.redis.enabled=false","ciyuantown.upload-dir=./target/media-test-uploads"}) @AutoConfigureMockMvc
class MediaIntegrationTests {
 @Autowired StorageService storage;@Autowired MediaSettingsService settings;@Autowired PublishMediaService media;@Autowired MediaTranscodeWorker worker;
 @Autowired FfmpegService ffmpeg;@Autowired UploadAssetMapper assets;@Autowired JdbcTemplate db;@Autowired MockMvc mvc;@Autowired JwtUtil jwt;@Autowired ObjectMapper json;@Autowired PlatformTransactionManager tx;
 long userId;Path root;
 @BeforeEach void setup()throws Exception{root=Files.createTempDirectory("ct-storage-test-");storage.save(new StorageService.Config("","local",root.toString(),"","","","","",""));
  db.update("INSERT INTO app_user(qq_number,email,nickname) VALUES(?,?,?)","9"+Math.floorMod(System.nanoTime(),1_000_000_000L),"media-"+System.nanoTime()+"@test.local","媒体测试");userId=db.queryForObject("SELECT MAX(id) FROM app_user",Long.class);
 }
 @AfterEach void cleanup(){db.update("DELETE FROM post WHERE author_id=?",userId);db.update("DELETE j FROM media_job j JOIN upload_asset a ON a.id=j.asset_id WHERE a.owner_id=?",userId);db.update("DELETE FROM upload_asset WHERE owner_id=?",userId);if(db.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='user_level_ledger'",Integer.class)>0){db.update("DELETE FROM user_level_ledger WHERE user_id=?",userId);db.update("DELETE FROM user_level_signin WHERE user_id=?",userId);}db.update("DELETE FROM app_user WHERE id=?",userId);db.update("DELETE FROM system_config WHERE config_key IN ('media_storage_active','media_processing')");PublishMediaService.deleteWorkspace(root);}
 private byte[] png(int w,int h)throws Exception{var image=new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB);var g=image.createGraphics();g.setColor(new Color(30,100,220,180));g.fillRect(0,0,w,h);g.dispose();var out=new ByteArrayOutputStream();ImageIO.write(image,"png",out);return out.toByteArray();}
 @Test void storageSnapshotsPathSafetyAndRollback()throws Exception{
  var first=storage.active();Path file=root.resolve("input.png");Files.write(file,png(60,40));var stored=storage.put(first,"posts/example.png",file,"image/png");assertTrue(Files.exists(storage.materialize(stored.url(),root)));mvc.perform(get(stored.url())).andExpect(status().isOk()).andExpect(content().bytes(Files.readAllBytes(file)));mvc.perform(get("/uploads/storage/"+first.id()+"/input.png")).andExpect(status().isNotFound());
  Path second=Files.createTempDirectory("ct-storage-second-");try{storage.save(new StorageService.Config("","local",second.toString(),"","","","","",""));assertEquals(first.id(),storage.find(stored.url()).orElseThrow().storageId());assertTrue(Files.exists(storage.materialize(stored.url(),root)));storage.delete(stored.url());assertFalse(Files.exists(root.resolve("posts/example.png")));}finally{PublishMediaService.deleteWorkspace(second);}
  assertThrows(Exception.class,()->storage.localPath(first,"../escape"));assertThrows(Exception.class,()->storage.localPath(first,"C:/escape"));assertThrows(Exception.class,()->storage.localPath(first,"/absolute"));
  new TransactionTemplate(tx).execute(status->{storage.put(first,"posts/rollback.png",file,"image/png");status.setRollbackOnly();return null;});assertFalse(Files.exists(root.resolve("posts/rollback.png")));
 }
 @Test void threeImageVersionsAndDownloadPermissions()throws Exception{
  var defaults=MediaSettingsService.defaults();settings.save(new MediaSettingsService.Settings(new MediaSettingsService.Images(128,78,256,85,6,3L*1024*1024*1024),defaults.videos()));byte[] original=png(700,500);var a=media.uploadImage(userId,new MockMultipartFile("file","image.png","image/png",original));
  assertNotEquals(a.originalUrl(),a.previewUrl());assertEquals("ready",a.status());assertTrue(a.coverUrl().endsWith(".webp"));assertArrayEquals(original,Files.readAllBytes(storage.materialize(a.originalUrl(),root)));var preview=ImageIO.read(storage.materialize(a.previewUrl(),root).toFile());assertEquals(256,preview.getWidth());assertTrue(preview.getColorModel().hasAlpha());assertEquals(128,ImageIO.read(storage.materialize(a.coverUrl(),root).toFile()).getWidth());
  var channel=db.queryForObject("SELECT MIN(id) FROM home_channel",Long.class);db.update("INSERT INTO post(author_id,author_nickname,channel_id,title,status,allow_download,mode) VALUES(?, '媒体测试',?,'三版本图片',1,1,'image')",userId,channel);long post=db.queryForObject("SELECT MAX(id) FROM post",Long.class);db.update("INSERT INTO post_media(post_id,asset_id,type,layout,aspect_ratio,url) VALUES(?,?,'image','wide',1.4,?)",post,a.id(),a.url());long id=db.queryForObject("SELECT MAX(id) FROM post_media",Long.class);
  String token="Bearer "+jwt.createAccessToken(userId,"app",0);
  var response=mvc.perform(get("/app/post/"+post+"/media/"+id+"/download?version=preview").header("Authorization",token)).andExpect(status().isOk()).andReturn().getResponse();assertEquals(256,ImageIO.read(new ByteArrayInputStream(response.getContentAsByteArray())).getWidth());
  var originalResponse=mvc.perform(get("/app/post/"+post+"/media/"+id+"/download?version=original").header("Authorization",token)).andReturn().getResponse();assertArrayEquals(original,originalResponse.getContentAsByteArray());
  db.update("UPDATE post SET download_watermark=1 WHERE id=?",post);var marked=mvc.perform(get("/app/post/"+post+"/media/"+id+"/download?version=preview").header("Authorization",token)).andReturn().getResponse();var markedImage=ImageIO.read(new ByteArrayInputStream(marked.getContentAsByteArray()));assertNotNull(markedImage);assertEquals(256,markedImage.getWidth());assertTrue(markedImage.getColorModel().hasAlpha());assertFalse(Arrays.equals(response.getContentAsByteArray(),marked.getContentAsByteArray()));
  db.update("UPDATE post SET allow_download=0 WHERE id=?",post);mvc.perform(get("/app/post/"+post+"/media/"+id+"/download").header("Authorization",token)).andExpect(jsonPath("$.msg").value("作者未允许下载"));
 }
 @Test void videoPublishesWhileProcessingAndRecoversLeases()throws Exception{
  Assumptions.assumeTrue(ffmpeg.isAvailable());Path video=root.resolve("fixture.mp4");ffmpeg.command(java.util.List.of("-y","-f","lavfi","-i","testsrc2=size=1280x720:rate=12","-t","5","-c:v","libx264","-pix_fmt","yuv420p",video.toString()),30,"生成测试视频失败");
  var uploaded=media.uploadVideo(userId,new MockMultipartFile("file","fixture.mp4","video/mp4",Files.readAllBytes(video)));assertEquals("processing",uploaded.status());assertEquals("",uploaded.hlsUrl());
  db.update("UPDATE system_config SET config_value='0' WHERE config_key='publish_review_required'");var channel=db.queryForObject("SELECT MIN(id) FROM home_channel",Long.class);String token="Bearer "+jwt.createAccessToken(userId,"app",0);
  var response=mvc.perform(post("/app/publish/submit").header("Authorization",token).contentType("application/json").content(json.writeValueAsString(Map.of("mode","video","channelId",channel,"title","异步视频","assetIds",java.util.List.of(uploaded.id()))))).andExpect(jsonPath("$.code").value("0000")).andReturn().getResponse();String post=json.readTree(response.getContentAsString()).path("data").path("id").asText();
  mvc.perform(get("/app/post/"+post)).andExpect(jsonPath("$.data.media[0].status").value("processing"));
  db.update("UPDATE media_job SET state='running',worker_token='expired',lease_until=DATE_SUB(NOW(),INTERVAL 1 MINUTE) WHERE asset_id=?",uploaded.id());worker.dispatch();
  UploadAsset processed=null;long deadline=System.currentTimeMillis()+90_000;while(System.currentTimeMillis()<deadline){processed=assets.selectById(uploaded.id());if(!"processing".equals(processed.getStatus()))break;Thread.sleep(300);}assertNotNull(processed);assertEquals("ready",processed.getStatus(),processed.getErrorMessage());var qualities=json.readTree(processed.getQualitiesJson());assertEquals(3,qualities.size());assertEquals(720,qualities.get(0).path("height").asInt());assertTrue(Files.size(storage.materialize(processed.getUrl(),root))>0);
  String master=Files.readString(storage.materialize(processed.getHlsUrl(),root));assertTrue(master.contains("#EXT-X-INDEPENDENT-SEGMENTS"));assertTrue(master.contains("720/index.m3u8"));assertFalse(master.contains("1080/index.m3u8"));
  mvc.perform(get("/app/post/"+post)).andExpect(jsonPath("$.data.media[0].status").value("ready")).andExpect(jsonPath("$.data.media[0].hlsUrl").isNotEmpty());
 }
 @Test void gifAndAnimatedWebpKeepFrames()throws Exception{
  Assumptions.assumeTrue(ffmpeg.isAvailable());Path gif=root.resolve("source.gif"),webp=root.resolve("source.webp");ffmpeg.command(java.util.List.of("-y","-f","lavfi","-i","testsrc2=size=160x100:rate=3","-t","1","-loop","0",gif.toString()),30,"GIF夹具失败");ffmpeg.command(java.util.List.of("-y","-i",gif.toString(),"-loop","0","-c:v","libwebp_anim",webp.toString()),30,"WebP夹具失败");
  var d=MediaSettingsService.defaults();settings.save(new MediaSettingsService.Settings(new MediaSettingsService.Images(80,78,100,85,6,d.images().cacheMaxBytes()),d.videos()));
  for(Path source:java.util.List.of(gif,webp)){String ext=source.toString().endsWith("gif")?"gif":"webp";var a=media.uploadImage(userId,new MockMultipartFile("file","animated."+ext,"image/"+ext,Files.readAllBytes(source)));Path preview=storage.materialize(a.previewUrl(),root);if(ext.equals("gif")){try(var input=ImageIO.createImageInputStream(preview.toFile())){var reader=ImageIO.getImageReaders(input).next();reader.setInput(input);assertTrue(reader.getNumImages(true)>1);assertEquals(100,reader.getWidth(0));reader.dispose();}}else{assertTrue(AnimatedWebp.isAnimated(preview));Path decoded=root.resolve("roundtrip.webp");var animation=AnimatedWebp.process(preview,decoded,100,80,null);assertEquals(100,animation.width());}}
 }
 @Test void invalidParametersAndStorageCredentialsAreRejected(){assertThrows(Exception.class,()->settings.save(new MediaSettingsService.Settings(MediaSettingsService.defaults().images(),new MediaSettingsService.Videos(0,60,128,23,1,Map.of()))));assertThrows(Exception.class,()->storage.save(new StorageService.Config("","oss","","invalid","","","","","")));assertThrows(Exception.class,()->storage.test(new StorageService.Config("","local","relative","","","","","","")));}
 @Test void cloudSecretsAreEncryptedAndMasked(){
  storage.save(new StorageService.Config("","oss","","https://oss-cn-hangzhou.aliyuncs.com","cn-hangzhou","fixture-bucket","fixture-access","fixture-secret","https://fixture.example"));var masked=storage.masked();assertEquals("********",masked.accessKey());assertEquals("",masked.secretKey());String stored=db.queryForObject("SELECT config_json FROM media_storage_config WHERE id=?",String.class,masked.id());assertFalse(stored.contains("fixture-access"));assertFalse(stored.contains("fixture-secret"));assertEquals("fixture-secret",storage.active().secretKey());assertEquals("fixture-access",storage.active().accessKey());
 }
 @Test void ftpConfigApiEncryptionAndRealDatabaseRollback()throws Exception{
  try(var ftp=new com.yyx.ciyuantown.common.storage.FtpFixture(root.resolve("ftp-api"))){
   var admin=db.queryForMap("SELECT id,token_version FROM admin_user WHERE user_type=0 AND status=0 LIMIT 1");
   String token="Bearer "+jwt.createAccessToken(((Number)admin.get("id")).longValue(),"admin",((Number)admin.get("token_version")).intValue(),0);
   mvc.perform(post("/media-config/storage").header("Authorization",token).contentType("application/json").content(json.writeValueAsString(ftp.config())))
     .andExpect(jsonPath("$.code").value("0000")).andExpect(jsonPath("$.data.provider").value("ftp")).andExpect(jsonPath("$.data.ftpPassword").value(""));
   var current=storage.active();String encrypted=db.queryForObject("SELECT config_json FROM media_storage_config WHERE id=?",String.class,current.id());
   assertFalse(encrypted.contains("secret with spaces"));
   mvc.perform(get("/media-config/storage").header("Authorization",token)).andExpect(jsonPath("$.data.ftpPassword").value(""));
   mvc.perform(post("/media-config/storage/test").header("Authorization",token).contentType("application/json").content(json.writeValueAsString(storage.masked()))).andExpect(jsonPath("$.code").value("0000"));
   assertEquals(current.id(),storage.active().id());
   mvc.perform(post("/media-config/storage").header("Authorization",token).contentType("application/json").content(json.writeValueAsString(storage.masked()))).andExpect(jsonPath("$.code").value("0000"));
   assertEquals(" secret with spaces",storage.active().ftpPassword());
   Path input=Files.writeString(root.resolve("rollback-input.txt"),"rollback");String[] url={null};
   new TransactionTemplate(tx).execute(status->{url[0]=storage.put(current,"posts/rollback.txt",input,"text/plain").url();status.setRollbackOnly();return null;});
   assertFalse(Files.exists(ftp.media.resolve("posts/rollback.txt")));assertTrue(storage.find(url[0]).isEmpty());
  }
 }
 @Test void ftpImageVariantsAndVideoHlsUseOriginalStorageAfterSwitch()throws Exception{
  Assumptions.assumeTrue(ffmpeg.isAvailable());
  try(var ftp=new com.yyx.ciyuantown.common.storage.FtpFixture(root.resolve("ftp-media"))){
   storage.save(ftp.config());
   var image=media.uploadImage(userId,new MockMultipartFile("file","ftp.png","image/png",png(64,40)));
   for(String url:java.util.List.of(image.originalUrl(),image.previewUrl(),image.coverUrl())){
    assertTrue(url.startsWith(ftp.domain+"/"));assertTrue(Files.size(storage.materialize(url,root.resolve("ftp-download")))>0);
   }
   Path video=root.resolve("ftp-video.mp4");ffmpeg.command(java.util.List.of("-y","-f","lavfi","-i","color=c=blue:size=320x240:rate=2","-t","1","-c:v","libx264","-preset","ultrafast",video.toString()),30,"FTP 视频夹具失败");
   var uploaded=media.uploadVideo(userId,new MockMultipartFile("file","ftp.mp4","video/mp4",Files.readAllBytes(video)));
   storage.save(new StorageService.Config("","local",root.resolve("after-ftp").toString(),"","","","","",""));
   worker.dispatch();var ready=awaitState(uploaded.id(),"ready");assertTrue(ready.getHlsUrl().startsWith(ftp.domain+"/"));
   var master=java.net.URI.create(ready.getHlsUrl());String manifest;
   try(var stream=master.toURL().openStream()){manifest=new String(stream.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}
   for(String child:manifest.lines().filter(line->!line.isBlank()&&!line.startsWith("#")).toList()){
    var childUri=master.resolve(child);String playlist;try(var stream=childUri.toURL().openStream()){playlist=new String(stream.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}
    for(String segment:playlist.lines().filter(line->!line.isBlank()&&!line.startsWith("#")).toList())try(var stream=childUri.resolve(segment).toURL().openStream()){assertTrue(stream.readAllBytes().length>0);}
   }
   assertTrue(manifest.contains("240/index.m3u8"));
   media.deleteAsset(assets.selectById(image.id()));media.deleteAsset(ready);
   try(var files=Files.walk(ftp.media)){assertEquals(0,files.filter(Files::isRegularFile).count());}
  }
 }
 private UploadAsset awaitState(long id,String state)throws Exception{long end=System.currentTimeMillis()+120_000;UploadAsset a;do{a=assets.selectById(id);if(state.equals(a.getStatus()))return a;if("failed".equals(a.getStatus())&&!state.equals("failed"))fail(a.getErrorMessage());Thread.sleep(200);}while(System.currentTimeMillis()<end);throw new AssertionError("Timed out waiting for "+state+": "+a.getStatus());}
 @Test void videoLadderHandles4kPortraitAndLowResolution()throws Exception{
  Assumptions.assumeTrue(ffmpeg.isAvailable());for(String size:java.util.List.of("3840x2160","720x1280","320x240")){
   Path input=root.resolve("source-"+size+".mp4");ffmpeg.command(java.util.List.of("-y","-f","lavfi","-i","color=c=blue:size="+size+":rate=2","-t","1","-c:v","libx264","-preset","ultrafast","-pix_fmt","yuv420p",input.toString()),30,"边界视频夹具失败");var upload=media.uploadVideo(userId,new MockMultipartFile("file","fixture.mp4","video/mp4",Files.readAllBytes(input)));worker.dispatch();var a=awaitState(upload.id(),"ready");var quality=json.readTree(a.getQualitiesJson()).get(0);int expected=size.equals("3840x2160")?1080:size.equals("720x1280")?720:240;assertEquals(expected,quality.path("height").asInt());assertTrue(Math.min(quality.path("width").asInt(),quality.path("actualHeight").asInt())<=1080);assertTrue(Math.max(quality.path("width").asInt(),quality.path("actualHeight").asInt())<=1920);if(size.equals("720x1280"))assertTrue(quality.path("actualHeight").asInt()>quality.path("width").asInt());if(size.equals("320x240")){assertEquals(320,quality.path("width").asInt());assertEquals(240,quality.path("actualHeight").asInt());}
  }
 }
 @Test void failedVideoCanRetryAndBecomeReady()throws Exception{
  Assumptions.assumeTrue(ffmpeg.isAvailable());Path input=root.resolve("retry.mp4");ffmpeg.command(java.util.List.of("-y","-f","lavfi","-i","color=c=red:size=320x240:rate=3","-t","1","-c:v","libx264",input.toString()),30,"重试夹具失败");byte[] bytes=Files.readAllBytes(input);var uploaded=media.uploadVideo(userId,new MockMultipartFile("file","retry.mp4","video/mp4",bytes));Path original=storage.materialize(uploaded.originalUrl(),root);db.update("UPDATE media_job SET state='running',worker_token='test',lease_until=DATE_ADD(NOW(),INTERVAL 10 MINUTE) WHERE asset_id=?",uploaded.id());Files.writeString(original,"damaged");db.update("UPDATE media_job SET state='queued' WHERE asset_id=?",uploaded.id());worker.dispatch();awaitState(uploaded.id(),"failed");Files.write(original,bytes);assertEquals("processing",media.retry(userId,uploaded.id()).status());worker.dispatch();assertEquals("ready",awaitState(uploaded.id(),"ready").getStatus());assertThrows(Exception.class,()->media.retry(userId,uploaded.id()));
 }
 @Test void selectedPortraitCoverPreservesOriginalRatio()throws Exception{
  Assumptions.assumeTrue(ffmpeg.isAvailable());Path input=root.resolve("portrait-cover.mp4");ffmpeg.command(java.util.List.of("-y","-f","lavfi","-i","color=c=blue:size=320x480:rate=2","-t","1","-c:v","libx264","-preset","ultrafast",input.toString()),30,"竖屏封面夹具失败");
  var uploaded=media.uploadVideo(userId,new MockMultipartFile("file","portrait.mp4","video/mp4",Files.readAllBytes(input)));
  var selected=media.cover(userId,uploaded.id(),new com.yyx.ciyuantown.business.publish.dto.PublishRequest.Crop(200L,0d,0d,1d,1d));
  var image=ImageIO.read(storage.materialize(selected.coverUrl(),root).toFile());assertEquals(320,image.getWidth());assertEquals(480,image.getHeight());assertNotEquals(uploaded.resourceVersion(),selected.resourceVersion());assertEquals(200L,selected.coverTimeMs());
  assertThrows(Exception.class,()->media.cover(userId,uploaded.id(),new com.yyx.ciyuantown.business.publish.dto.PublishRequest.Crop(200L,0d,0d,1d,.375d)));
 }
 @Test void jpegAndStaticWebpKeepOriginalAndDoNotUpscale()throws Exception{
  for(String ext:java.util.List.of("jpg","webp")){var image=ImageIO.read(new ByteArrayInputStream(png(64,40)));Path source=root.resolve("small."+ext);ImageVariants.write(image,ext,78,6,source);byte[] original=Files.readAllBytes(source);var a=media.uploadImage(userId,new MockMultipartFile("file","small."+ext,"image/"+(ext.equals("jpg")?"jpeg":"webp"),original));assertArrayEquals(original,Files.readAllBytes(storage.materialize(a.originalUrl(),root)));var preview=ImageIO.read(storage.materialize(a.previewUrl(),root).toFile());assertEquals(64,preview.getWidth());assertEquals(40,preview.getHeight());assertTrue(Files.size(storage.materialize(a.previewUrl(),root))<=original.length);if(ext.equals("webp"))assertTrue(preview.getColorModel().hasAlpha());assertEquals(64,ImageIO.read(storage.materialize(a.coverUrl(),root).toFile()).getWidth());}
 }

}
