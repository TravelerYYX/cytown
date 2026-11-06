package com.yyx.ciyuantown.business.publish.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yyx.ciyuantown.business.publish.entity.UploadAsset;
import com.yyx.ciyuantown.business.publish.mapper.UploadAssetMapper;
import com.yyx.ciyuantown.common.storage.*;
import com.yyx.ciyuantown.common.exception.BizException;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.cache.CacheManager;
import org.springframework.beans.factory.ObjectProvider;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;


@Service @RequiredArgsConstructor @Slf4j
public class MediaTranscodeWorker {
 private final ResourceCleanupService cleanup;
 private final JdbcTemplate db;private final UploadAssetMapper assets;private final StorageService storage;private final MediaSettingsService settings;private final FfmpegService ffmpeg;private final ObjectMapper json;private final ObjectProvider<CacheManager> cache;
 private final org.springframework.transaction.PlatformTransactionManager transactionManager;
 private volatile boolean closing;
 private final ExecutorService executor=Executors.newFixedThreadPool(8);private final Map<Long,String> running=new ConcurrentHashMap<>();
 public record Quality(int height,int width,int actualHeight,int bitrate,String url) {}
 public void enqueue(UploadAsset asset){try{db.update("INSERT INTO media_job(asset_id,config_json) VALUES(?,?)",asset.getId(),json.writeValueAsString(settings.get().videos()));}catch(Exception e){throw new BizException("转码任务创建失败");}}
 @Scheduled(fixedDelay=3000) public void dispatch(){
  if(closing)return;
  try{for(var entry:running.entrySet())db.update("UPDATE media_job SET lease_until=DATE_ADD(NOW(),INTERVAL 90 SECOND) WHERE asset_id=? AND worker_token=? AND state='running'",entry.getKey(),entry.getValue());
   int capacity=settings.get().videos().concurrency()-running.size();if(capacity<=0)return;
   var ids=db.queryForList("SELECT asset_id FROM media_job WHERE state='queued' OR (state='running' AND lease_until<NOW()) ORDER BY create_time LIMIT ?",Long.class,capacity);
   for(long id:ids){String token=UUID.randomUUID().toString();int claimed=db.update("UPDATE media_job SET state='running',worker_token=?,lease_until=DATE_ADD(NOW(),INTERVAL 90 SECOND),attempts=attempts+1 WHERE asset_id=? AND (state='queued' OR (state='running' AND lease_until<NOW()))",token,id);
    if(claimed==1){running.put(id,token);executor.submit(()->{try{process(id,token);}finally{running.remove(id);}});}}
  }catch(Exception e){log.warn("Media queue unavailable: {}",e.getClass().getSimpleName());}
 }
 public void retry(UploadAsset asset){if(!"failed".equals(asset.getStatus()))throw new BizException("仅失败的视频可重试");asset.setStatus("processing");asset.setErrorMessage("");assets.updateById(asset);if(db.update("UPDATE media_job SET state='queued',worker_token=NULL,lease_until=NULL,error_message='' WHERE asset_id=? AND state='failed'",asset.getId())!=1)throw new BizException("转码任务不存在或已被其他请求重试");evict();}
 private void process(long id,String token){Path workspace=null;StorageService.Config c=null;String prefix=null;
  try{UploadAsset asset=assets.selectById(id);if(asset==null){db.update("DELETE FROM media_job WHERE asset_id=?",id);return;}
   var original=storage.find(asset.getOriginalUrl()).orElseThrow(()->new BizException("视频源文件不存在"));c=storage.config(original.storageId());
   var options=json.readValue(db.queryForObject("SELECT config_json FROM media_job WHERE asset_id=?",String.class,id),MediaSettingsService.Videos.class);
   workspace=Files.createTempDirectory("ct-transcode-");Path input=storage.materialize(asset.getOriginalUrl(),workspace.resolve("input"));var info=ffmpeg.probe(input);
   Path out=workspace.resolve("out");Files.createDirectories(out);List<Quality> qualities=new ArrayList<>();int shortEdge=Math.min(info.width(),info.height());List<Integer> levels=new ArrayList<>();for(int h:new int[]{1080,720,480,360})if(shortEdge>=h)levels.add(h);if(levels.isEmpty())levels.add(shortEdge);
   prefix=original.key().substring(0,original.key().lastIndexOf('/')+1)+"hls-"+UUID.randomUUID()+"/";
   for(int level:levels){Path folder=out.resolve(String.valueOf(level));Files.createDirectories(folder);double scale=Math.min(1,Math.min(level/(double)shortEdge,(level*16d/9)/Math.max(info.width(),info.height())));int w=Math.max(2,(int)Math.floor(info.width()*scale/2)*2),h=Math.max(2,(int)Math.floor(info.height()*scale/2)*2);int rate=options.bitrates().getOrDefault(String.valueOf(level),800);
    ffmpeg.command(List.of("-y","-i",input.toString(),"-map","0:v:0","-map","0:a:0?","-vf","scale="+w+":"+h+",setsar=1","-r",String.valueOf(options.maxFps()),"-c:v","libx264","-preset","veryfast","-crf",String.valueOf(options.crf()),"-maxrate",rate+"k","-bufsize",rate*2+"k","-pix_fmt","yuv420p","-g",String.valueOf(options.maxFps()*options.segmentSeconds()),"-keyint_min",String.valueOf(options.maxFps()*options.segmentSeconds()),"-sc_threshold","0","-force_key_frames","expr:gte(t,n_forced*"+options.segmentSeconds()+")","-c:a","aac","-b:a",options.audioKbps()+"k","-f","hls","-hls_time",String.valueOf(options.segmentSeconds()),"-hls_playlist_type","vod","-hls_flags","independent_segments","-hls_segment_filename",folder.resolve("segment-%06d.ts").toString(),folder.resolve("index.m3u8").toString()),1800,"视频"+level+"P转码失败");
    qualities.add(new Quality(level,w,h,(rate+options.audioKbps())*1000,""));
   }
   var top=qualities.getFirst();ffmpeg.command(List.of("-y","-i",out.resolve(top.height()+"/index.m3u8").toString(),"-c","copy","-movflags","+faststart",out.resolve("download.mp4").toString()),300,"视频下载版本生成失败");
   final var outputConfig=c; final String outputPrefix=prefix;
   boolean committed=Boolean.TRUE.equals(new org.springframework.transaction.support.TransactionTemplate(transactionManager).execute(tx->{
    cleanup.lock();
    if(db.queryForObject("SELECT COUNT(*) FROM media_job j JOIN upload_asset a ON a.id=j.asset_id WHERE j.asset_id=? AND j.worker_token=? AND j.state='running'",Integer.class,id,token)!=1)return false;
   try {
   List<Quality> published=new ArrayList<>();try(var walk=Files.walk(out)){for(Path path:walk.filter(Files::isRegularFile).filter(p->!p.toString().endsWith(".m3u8")).toList())storage.put(outputConfig,outputPrefix+out.relativize(path).toString().replace('\\','/'),path,StorageService.mime(path));}
   for(var q:qualities){var object=storage.put(outputConfig,outputPrefix+q.height()+"/index.m3u8",out.resolve(q.height()+"/index.m3u8"),"application/vnd.apple.mpegurl");published.add(new Quality(q.height(),q.width(),q.actualHeight(),q.bitrate(),object.url()));}
   StringBuilder master=new StringBuilder("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-INDEPENDENT-SEGMENTS\n");for(var q:qualities)master.append("#EXT-X-STREAM-INF:BANDWIDTH=").append(q.bitrate()).append(",RESOLUTION=").append(q.width()).append('x').append(q.actualHeight()).append('\n').append(q.height()).append("/index.m3u8\n");
   Path playlist=out.resolve("master.m3u8");Files.writeString(playlist,master);String hls=storage.put(outputConfig,outputPrefix+"master.m3u8",playlist,"application/vnd.apple.mpegurl").url();String download=storage.findByKey(outputConfig.id(),outputPrefix+"download.mp4").orElseThrow().url();
   // Publish only after every segment and playlist exists, and only while holding this lease.
   String qualitiesJson=json.writeValueAsString(published);
    if(db.update("UPDATE media_job SET state='ready',lease_until=NULL,error_message='' WHERE asset_id=? AND worker_token=? AND state='running'",id,token)!=1)return false;
    var update=new UploadAsset();update.setId(id);update.setUrl(download);update.setHlsUrl(hls);update.setQualitiesJson(qualitiesJson);update.setStatus("ready");update.setErrorMessage("");update.setResourceVersion(UUID.randomUUID().toString());if(assets.updateById(update)!=1)throw new BizException("视频已被删除");return true;
   } catch(java.io.IOException io) { throw new java.io.UncheckedIOException(io); }
   }));
   if(committed)evict();else storage.deletePrefix(c.id(),prefix);
  }catch(Exception e){String message=e instanceof BizException?e.getMessage():"视频处理失败，请重试或联系管理员";if(!closing&&db.update("UPDATE media_job SET state='failed',lease_until=NULL,error_message=? WHERE asset_id=? AND worker_token=? AND state='running'",message,id,token)==1){var update=new UploadAsset();update.setId(id);update.setStatus("failed");update.setErrorMessage(message);assets.updateById(update);evict();}if(c!=null&&prefix!=null)try{storage.deletePrefix(c.id(),prefix);}catch(Exception ignored){}log.warn("Video asset {} failed: {}",id,message);
  }finally{if(workspace!=null)PublishMediaService.deleteWorkspace(workspace);}
 }
 private void evict(){var manager=cache.getIfAvailable();if(manager!=null)for(String name:List.of("post-list","post-detail")){var item=manager.getCache(name);if(item!=null)item.clear();}}
 @PreDestroy public void close(){closing=true;for(var entry:running.entrySet())try{db.update("UPDATE media_job SET state='queued',worker_token=NULL,lease_until=NULL WHERE asset_id=? AND worker_token=? AND state='running'",entry.getKey(),entry.getValue());}catch(Exception ignored){}executor.shutdownNow();try{executor.awaitTermination(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}

}
