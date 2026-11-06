package com.yyx.ciyuantown.business.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yyx.ciyuantown.business.publish.service.FfmpegService;
import com.yyx.ciyuantown.common.image.*;
import com.yyx.ciyuantown.common.storage.*;
import com.yyx.ciyuantown.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.multipart.MultipartFile;
import javax.imageio.ImageIO;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

@Service @RequiredArgsConstructor @Slf4j
public class ChatMedia {
 private final JdbcTemplate db;private final ObjectMapper json;private final ChatService chat;private final ChatSettings settings;
 private final ChatVault vault;private final ResourceCleanupService cleanup;private final FfmpegService ffmpeg;private final StorageService storage;
 private final PlatformTransactionManager transactions;
 private final ExecutorService workers=Executors.newFixedThreadPool(4);
 private final Set<Long> running=ConcurrentHashMap.newKeySet();
 private volatile boolean closing;
 private static long n(Object value){return value==null?0:((Number)value).longValue();}
 public void access(long user,long asset){
  var a=chat.assetRow(asset);
  boolean attached=db.queryForObject("SELECT COUNT(*) FROM chat_message m JOIN chat_conversation c ON c.id=m.conversation_id LEFT JOIN chat_asset p ON p.id=m.asset_id WHERE (m.asset_id=? OR p.cover_id=?) AND m.id>c.cleared_through AND m.revoked=FALSE AND (c.user_low=? OR c.user_high=?)",Integer.class,asset,asset,user,user)>0;
  Object attachedFlag=a.get("attached");boolean own=n(a.get("owner_id"))==user&&!Boolean.TRUE.equals(attachedFlag)&&!(attachedFlag instanceof Number flag&&flag.intValue()!=0);
  if(!attached&&!own)throw new BizException("媒体已撤销或无权访问");
 }
 private long create(long user,String kind,Path file,String ext,String mime,int width,int height,long duration,ChatSettings.Config config)throws Exception{
  String version=UUID.randomUUID().toString();var manifest=vault.store(file,version);
  var values=new HashMap<String,Object>();values.put("owner_id",user);values.put("kind",kind);values.put("state","ready");values.put("version",version);values.put("mime",mime);values.put("extension",ext);values.put("size_bytes",Files.size(file));values.put("width",width);values.put("height",height);values.put("duration_ms",duration);values.put("manifest_json",json.writeValueAsString(manifest));values.put("config_json",json.writeValueAsString(config));values.put("created_at",chat.now());
  return new SimpleJdbcInsert(db).withTableName("chat_asset").usingColumns(values.keySet().toArray(String[]::new)).usingGeneratedKeyColumns("id").executeAndReturnKey(values).longValue();
 }
 @Transactional public ChatService.Asset image(long user,MultipartFile file,boolean original,String kind){
  if(!Set.of("image","emoji").contains(kind))throw new BizException("无效的图片类型");validate(user,file,20L*1024*1024);String ext=extension(file);if(!Set.of("jpg","jpeg","png","gif","webp").contains(ext))throw new BizException("支持JPG、PNG、GIF和WebP图片");
  Path work=null;try{cleanup.lock();work=Files.createTempDirectory("ct-chat-image-");Path input=work.resolve("input."+ext),output=work.resolve("output."+ext);file.transferTo(input);var image=ImageIO.read(input.toFile());if(image==null||(long)image.getWidth()*image.getHeight()>100_000_000)throw new BizException("图片无法解码或像素过大");var config=settings.get();var opts=config.images();
   if(original)Files.copy(input,output);
   else if("gif".equals(ext))ffmpeg.resizeGif(input,output,opts.maxEdge());
   else if("webp".equals(ext)&&AnimatedWebp.isAnimated(input))AnimatedWebp.process(input,output,opts.maxEdge(),opts.quality(),null);
   else ImageVariants.write(ImageVariants.resize(image,opts.maxEdge()),ext,opts.quality(),opts.pngCompression(),output);
   if(!original&&Math.max(image.getWidth(),image.getHeight())<=opts.maxEdge()&&Files.size(output)>=Files.size(input))Files.copy(input,output,StandardCopyOption.REPLACE_EXISTING);
   var actual=ImageIO.read(output.toFile());long id=create(user,kind,output,ext,"image/"+(Set.of("jpg","jpeg").contains(ext)?"jpeg":ext),actual.getWidth(),actual.getHeight(),0,config);return chat.asset(id);
  }catch(BizException e){throw e;}catch(Exception e){throw new BizException("私聊图片处理失败");}finally{deleteWorkspace(work);}
 }
 @Transactional public ChatService.Asset emoji(long user,long emoji){
  cleanup.lock();var rows=db.queryForList("SELECT e.image_url FROM publish_emoji e JOIN emoji_group g ON g.id=e.group_id WHERE e.id=? AND e.enabled=TRUE AND g.enabled=TRUE",emoji);if(rows.isEmpty())throw new BizException("表情不可用");
  Path work=null;try{work=Files.createTempDirectory("ct-chat-emoji-");String url=rows.getFirst().get("image_url").toString();Path source=storage.materialize(url,work);String ext=source.getFileName().toString().replaceFirst("^.*\\.","").toLowerCase(Locale.ROOT);var image=ImageIO.read(source.toFile());if(image==null)throw new BizException("表情无法解码");long id=create(user,"emoji",source,ext,"image/"+(Set.of("jpg","jpeg").contains(ext)?"jpeg":ext),image.getWidth(),image.getHeight(),0,settings.get());return chat.asset(id);}catch(BizException e){throw e;}catch(Exception e){throw new BizException("表情读取失败");}finally{deleteWorkspace(work);}
 }
 @Transactional public ChatService.Asset video(long user,MultipartFile file){
  validate(user,file,500L*1024*1024);ffmpeg.requireAvailable();String ext=extension(file);if(!Set.of("mp4","mov","m4v","webm","mkv","avi","3gp").contains(ext))throw new BizException("不支持的视频格式");Path work=null;
  try{cleanup.lock();work=Files.createTempDirectory("ct-chat-video-");Path input=work.resolve("input."+ext);file.transferTo(input);var info=ffmpeg.probe(input);long id=create(user,"video",input,ext,Optional.ofNullable(file.getContentType()).orElse("video/mp4"),info.width(),info.height(),info.durationMs(),settings.get());db.update("UPDATE chat_asset SET state='processing' WHERE id=?",id);db.update("INSERT INTO chat_video_job(asset_id) VALUES(?)",id);return chat.asset(id);}catch(BizException e){throw e;}catch(Exception e){throw new BizException("私聊视频上传失败");}finally{deleteWorkspace(work);}
 }
 @Transactional public ChatService.Asset retry(long user,long id){var a=chat.assetRow(id);if(n(a.get("owner_id"))!=user||!"failed".equals(a.get("state")))throw new BizException("仅本人失败的视频可以重试");db.update("UPDATE chat_asset SET state='processing',error_message='' WHERE id=?",id);db.update("UPDATE chat_video_job SET state='queued',worker_token=NULL,lease_until=0 WHERE asset_id=? AND state='failed'",id);return chat.asset(id);}
 private void validate(long user,MultipartFile f,long max){if(db.queryForObject("SELECT COUNT(*) FROM app_user WHERE id=? AND status=0 AND (deleted=FALSE OR deleted IS NULL)",Integer.class,user)!=1)throw new BizException("用户不可用");if(f==null||f.isEmpty()||f.getSize()>max)throw new BizException("文件为空或超出上传上限");}
 private String extension(MultipartFile f){return Optional.ofNullable(f.getOriginalFilename()).orElse("").replaceFirst("^.*\\.","").toLowerCase(Locale.ROOT);}
 @Scheduled(fixedDelay=2000) public void dispatch(){if(closing)return;try{
  for(long id:running)db.update("UPDATE chat_video_job SET lease_until=? WHERE asset_id=? AND state='running'",chat.now()+120000,id);
  int free=Math.max(0,settings.get().videos().concurrency()-running.size());if(free==0)return;
  for(long id:db.queryForList("SELECT asset_id FROM chat_video_job WHERE state='queued' OR (state='running' AND lease_until<?) ORDER BY asset_id LIMIT ?",Long.class,chat.now(),free)){String token=UUID.randomUUID().toString();if(db.update("UPDATE chat_video_job SET state='running',worker_token=?,lease_until=?,attempts=attempts+1 WHERE asset_id=? AND (state='queued' OR (state='running' AND lease_until<?))",token,chat.now()+120000,id,chat.now())==1){running.add(id);workers.submit(()->{try{process(id,token);}finally{running.remove(id);}});}}
 }catch(Exception e){log.warn("Chat video dispatch unavailable: {}",e.getClass().getSimpleName());}}
 private void process(long id,String token){Path work=null;try{
  var a=chat.assetRow(id);var config=json.readValue(a.get("config_json").toString(),ChatSettings.Config.class);var options=config.videos();work=Files.createTempDirectory("ct-chat-transcode-");Path input=work.resolve("input"),output=work.resolve("video.mp4"),cover=work.resolve("cover.webp");vault.materialize(vault.parse(a.get("manifest_json").toString()),input);var info=ffmpeg.probe(input);
  int shortEdge=Math.min(info.width(),info.height());double scale=Math.min(1,options.maxHeight()/(double)shortEdge);int width=Math.max(2,(int)(info.width()*scale/2)*2),height=Math.max(2,(int)(info.height()*scale/2)*2);
  ffmpeg.command(List.of("-y","-i",input.toString(),"-map","0:v:0","-map","0:a:0?","-vf","scale="+width+":"+height+",setsar=1","-r",""+options.maxFps(),"-c:v","libx264","-preset","veryfast","-crf",""+options.crf(),"-maxrate",options.maxKbps()+"k","-bufsize",options.maxKbps()*2+"k","-pix_fmt","yuv420p","-c:a","aac","-b:a",options.audioKbps()+"k","-movflags","+faststart",output.toString()),1800,"私聊视频压缩失败");
  ffmpeg.automaticCover(input,cover,info,options.coverMaxEdge(),options.coverQuality());var actual=ffmpeg.probe(output);final Path video=output,poster=cover;
  new TransactionTemplate(transactions).executeWithoutResult(tx->{cleanup.track("chat_asset",id);if(db.queryForObject("SELECT COUNT(*) FROM chat_video_job WHERE asset_id=? AND worker_token=? AND state='running'",Integer.class,id,token)!=1)throw new BizException("视频任务已失效");try{
   var image=ImageIO.read(poster.toFile());long coverId=create(n(a.get("owner_id")),"cover",poster,"webp","image/webp",image.getWidth(),image.getHeight(),0,config);String version=UUID.randomUUID().toString();var manifest=vault.store(video,version);
   db.update("UPDATE chat_asset SET state='ready',version=?,mime='video/mp4',extension='mp4',size_bytes=?,width=?,height=?,duration_ms=?,manifest_json=?,cover_id=? WHERE id=?",version,Files.size(video),actual.width(),actual.height(),actual.durationMs(),json.writeValueAsString(manifest),coverId,id);db.update("UPDATE chat_video_job SET state='ready',lease_until=0 WHERE asset_id=? AND worker_token=?",id,token);
  }catch(Exception e){throw new BizException("视频存储失败");}});
 }catch(Exception e){db.update("UPDATE chat_video_job SET state='failed',lease_until=0 WHERE asset_id=? AND worker_token=?",id,token);db.update("UPDATE chat_asset SET state='failed',error_message='视频处理失败，可重试' WHERE id=? AND state='processing'",id);log.warn("Chat video {} failed: {}",id,e.getClass().getSimpleName());}finally{deleteWorkspace(work);}}
 @jakarta.annotation.PreDestroy public void close(){closing=true;workers.shutdownNow();}
 public static void deleteWorkspace(Path work){if(work==null)return;try(var walk=Files.walk(work)){for(Path p:walk.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}catch(Exception ignored){}}
}
