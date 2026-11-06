package com.yyx.ciyuantown.business.publish.service;
import com.yyx.ciyuantown.business.publish.dto.PublishRequest;
import com.yyx.ciyuantown.business.publish.entity.*;
import com.yyx.ciyuantown.business.publish.mapper.*;
import com.yyx.ciyuantown.common.exception.BizException;
import com.yyx.ciyuantown.common.storage.*;
import com.yyx.ciyuantown.common.image.ImageVariants;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;

@Service @RequiredArgsConstructor
public class PublishMediaService {
 private final com.yyx.ciyuantown.common.storage.ResourceCleanupService cleanup;
 private final org.springframework.jdbc.core.JdbcTemplate db;
 private static final Set<String> IMAGE_EXT=Set.of("jpg","jpeg","png","webp","gif");
 private static final Set<String> VIDEO_EXT=Set.of("mp4","mov","m4v","webm","mkv");
 private final com.yyx.ciyuantown.business.appuser.service.AppUserAccessService userAccess;
 private final UploadAssetMapper assets;private final PublishRuleMapper rules;private final FfmpegService ffmpeg;
 private final StorageService storage;private final MediaSettingsService settings;private final ImageVariants images;private final MediaTranscodeWorker worker;private final ObjectMapper json;
 public record AssetView(Long id,String mediaType,String url,String coverUrl,String status,String errorMessage,Long sizeBytes,Integer width,Integer height,Long durationMs,Long coverTimeMs,String cropJson,String previewUrl,String originalUrl,String hlsUrl,Object qualities,String resourceVersion) {
  public static AssetView of(UploadAsset a){Object qualities=List.of();try{if(a.getQualitiesJson()!=null)qualities=new ObjectMapper().readTree(a.getQualitiesJson());}catch(Exception ignored){}return new AssetView(a.getId(),a.getMediaType(),a.getUrl(),a.getCoverUrl(),a.getStatus(),a.getErrorMessage(),a.getSizeBytes(),a.getWidth(),a.getHeight(),a.getDurationMs(),a.getCoverTimeMs(),a.getCropJson(),a.getPreviewUrl(),a.getOriginalUrl(),a.getHlsUrl(),qualities,a.getResourceVersion());}
 }
 @Transactional public AssetView uploadImage(Long userId,MultipartFile file){return uploadImage(userId,file,"posts");}
 @Transactional public AssetView uploadCommentImage(Long userId,MultipartFile file){return uploadImage(userId,file,"comments");}
 private AssetView uploadImage(Long userId,MultipartFile file,String area){userAccess.requireForUpdate(userId);validate(file,IMAGE_EXT,"image/");if(file.getSize()>20L*1024*1024)throw new BizException("图片大小不能超过20MB");Path workspace=null;var c=storage.active();String prefix=area+"/"+userId+"/"+UUID.randomUUID()+"/";
  try{workspace=Files.createTempDirectory("ct-image-");String ext=extension(file);Path original=workspace.resolve("original."+ext);file.transferTo(original);var result=images.create(original,ext,workspace,settings.get().images());
   var source=storage.put(c,prefix+original.getFileName(),original,StorageService.mime(original));var preview=storage.put(c,prefix+result.preview().getFileName(),result.preview(),StorageService.mime(result.preview()));var cover=storage.put(c,prefix+"cover.webp",result.cover(),"image/webp");
   UploadAsset a=base(userId,file,"image");a.setOriginalUrl(source.url());a.setPreviewUrl(preview.url());a.setUrl(preview.url());a.setCoverUrl(cover.url());a.setWidth(result.width());a.setHeight(result.height());a.setStatus("ready");assets.insert(a);return AssetView.of(a);
  }catch(Exception e){try{storage.deletePrefix(c.id(),prefix);}catch(Exception ignored){}if(e instanceof BizException b)throw b;throw new BizException("图片上传失败");}finally{deleteWorkspace(workspace);}
 }
 @Transactional
 public AssetView uploadVideo(Long userId,MultipartFile file){userAccess.requireForUpdate(userId);ffmpeg.requireAvailable();validate(file,VIDEO_EXT,"video/");int max=uploadVideoMaxMb();if(max>0&&file.getSize()>max*1024L*1024L)throw new BizException("视频大小不能超过"+max+"MB");Path workspace=null;var c=storage.active();String prefix="posts/"+userId+"/"+UUID.randomUUID()+"/";
  try{workspace=Files.createTempDirectory("ct-video-");Path original=workspace.resolve("original."+extension(file)),cover=workspace.resolve("cover.webp");file.transferTo(original);var info=ffmpeg.probe(original);long time=ffmpeg.automaticCover(original,cover,info);var source=storage.put(c,prefix+original.getFileName(),original,Optional.ofNullable(file.getContentType()).orElse("video/mp4"));var poster=storage.put(c,prefix+"cover.webp",cover,"image/webp");
   UploadAsset a=base(userId,file,"video");a.setOriginalUrl(source.url());a.setUrl("");a.setWidth(info.width());a.setHeight(info.height());a.setDurationMs(info.durationMs());a.setCoverUrl(poster.url());a.setCoverTimeMs(time);a.setStatus("processing");assets.insert(a);worker.enqueue(a);return AssetView.of(a);
  }catch(Exception e){try{storage.deletePrefix(c.id(),prefix);}catch(Exception ignored){}if(e instanceof BizException b)throw b;throw new BizException("视频上传失败");}finally{deleteWorkspace(workspace);}
 }
 @Transactional(readOnly=true) public AssetView get(Long userId,Long id){return AssetView.of(require(userId,id));}
 @Transactional public AssetView retry(Long userId,Long id){userAccess.requireForUpdate(userId);var a=require(userId,id);worker.retry(a);return AssetView.of(a);}
 @Transactional public AssetView cover(Long userId,Long id,PublishRequest.Crop p){cleanup.track("upload_asset",id);userAccess.requireForUpdate(userId);UploadAsset a=require(userId,id);if(!"video".equals(a.getMediaType()))throw new BizException("只有视频可以设置封面");double x=number(p.x()),y=number(p.y()),w=number(p.width()),h=number(p.height());if(x<0||y<0||w<=0||h<=0||x+w>1.0001||y+h>1.0001)throw new BizException("封面裁剪区域不合法");Path workspace=null;
  try{workspace=Files.createTempDirectory("ct-cover-");Path input=storage.materialize(a.getOriginalUrl(),workspace.resolve("input")),output=workspace.resolve("cover.webp");var info=ffmpeg.probe(input);if(Math.abs(w/h-1)>.03)throw new BizException("封面裁剪区域必须保持视频原比例");long time=p.timeMs()==null?Math.min(1500,info.durationMs()/2):p.timeMs();ffmpeg.cover(input,output,time,new FfmpegService.Crop(x,y,w,h),info);
   var source=storage.find(a.getOriginalUrl()).orElseThrow();var c=storage.config(source.storageId());String key=source.key().substring(0,source.key().lastIndexOf('/')+1)+"cover-"+UUID.randomUUID()+".webp";a.setCoverUrl(storage.put(c,key,output,"image/webp").url());a.setCoverTimeMs(time);a.setCropJson(json.writeValueAsString(p));a.setResourceVersion(UUID.randomUUID().toString());assets.updateById(a);db.update("UPDATE post_media SET cover_url=?,cover_time_ms=?,crop_json=? WHERE asset_id=?",a.getCoverUrl(),a.getCoverTimeMs(),a.getCropJson(),id);return AssetView.of(a);
  }catch(BizException e){throw e;}catch(Exception e){throw new BizException("封面更新失败");}finally{deleteWorkspace(workspace);}
 }
 public Path path(String url){return storage.materialize(url,Path.of(System.getProperty("java.io.tmpdir"),"ct-media-read"));}
 @Transactional public void deleteAsset(UploadAsset a){cleanup.retireAsset(a.getId());}
 public UploadAsset require(Long userId,Long id){UploadAsset a=id==null?null:assets.selectById(id);if(a==null||!Objects.equals(a.getOwnerId(),userId))throw new BizException("媒体不存在");return a;}
 private UploadAsset base(Long userId,MultipartFile file,String type){var a=new UploadAsset();a.setOwnerId(userId);a.setMediaType(type);a.setOriginalName(Optional.ofNullable(file.getOriginalFilename()).orElse(""));a.setContentType(Optional.ofNullable(file.getContentType()).orElse(""));a.setSizeBytes(file.getSize());a.setCoverUrl("");a.setPreviewUrl("");a.setOriginalUrl("");a.setHlsUrl("");a.setQualitiesJson("[]");a.setResourceVersion(UUID.randomUUID().toString());a.setCropJson("");a.setErrorMessage("");a.setAttached(false);return a;}
 private int uploadVideoMaxMb(){var applicable=rules.selectBatchIds(List.of("video","post"));if(applicable.stream().anyMatch(r->r.getMaxVideoMb()==null||r.getMaxVideoMb()==0))return 0;return applicable.stream().map(PublishRule::getMaxVideoMb).max(Integer::compareTo).orElse(0);}
 private static void validate(MultipartFile file,Set<String> ext,String prefix){if(file==null||file.isEmpty())throw new BizException("上传文件不能为空");if(!ext.contains(extension(file))||file.getContentType()==null||!file.getContentType().startsWith(prefix))throw new BizException("文件格式不受支持");}
 private static String extension(MultipartFile file){String name=Optional.ofNullable(file.getOriginalFilename()).orElse("");return name.contains(".")?name.substring(name.lastIndexOf('.')+1).toLowerCase(Locale.ROOT):"";}
 private static double number(Double value){return value==null?0:value;}
 public static void deleteWorkspace(Path directory){if(directory==null)return;try(var files=Files.walk(directory)){for(Path file:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(file);}catch(Exception ignored){}}
}
