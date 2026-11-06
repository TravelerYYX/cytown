package com.yyx.ciyuantown.business.publish.controller;
import com.yyx.ciyuantown.business.appauth.annotation.AppLoginRequired;
import com.yyx.ciyuantown.business.appuser.mapper.AppUserMapper;
import com.yyx.ciyuantown.business.post.mapper.PostMediaMapper;
import com.yyx.ciyuantown.business.post.service.PostService;
import com.yyx.ciyuantown.business.publish.service.*;
import com.yyx.ciyuantown.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.nio.file.*;
import jakarta.servlet.http.HttpServletRequest;
import com.yyx.ciyuantown.common.interceptor.AuthInterceptor;
import com.yyx.ciyuantown.common.redis.RedisHotspotService;
import java.time.Duration;

@RestController @RequestMapping("/app/post") @RequiredArgsConstructor
public class MediaDownloadController {
 private final PostService posts;private final PostMediaMapper media;private final AppUserMapper users;
 private final com.yyx.ciyuantown.business.publish.mapper.UploadAssetMapper assets;
 private final com.yyx.ciyuantown.common.storage.StorageService storage;
 private final WatermarkService watermark;private final RedisHotspotService redis;
 @GetMapping("/{postId}/media/{mediaId}/download") @AppLoginRequired
 public ResponseEntity<Resource> download(HttpServletRequest request,@PathVariable Long postId,@PathVariable Long mediaId,@RequestParam(defaultValue="preview") String version){
  if(!java.util.Set.of("preview","original").contains(version))throw new BizException("图片版本不合法");
  Long userId=(Long)request.getAttribute(AuthInterceptor.ATTR_USER_ID);if(!redis.allow("download",String.valueOf(userId),30,Duration.ofMinutes(1)))throw new BizException("下载过于频繁，请稍后再试");
  var post=posts.requirePost(postId,true);if(!Boolean.TRUE.equals(post.getAllowDownload()))throw new BizException("作者未允许下载");
  var item=media.selectById(mediaId);if(item==null||!postId.equals(item.getPostId()))throw new BizException("媒体不存在");
  var a=item.getAssetId()==null?null:assets.selectById(item.getAssetId());if(a!=null&&!"ready".equals(a.getStatus()))throw new BizException("文件仍在处理中");
  String url=a==null?item.getUrl():"video".equals(item.getType())?a.getUrl():"original".equals(version)?a.getOriginalUrl():a.getPreviewUrl();
  Path workspace=null;
  try{workspace=Files.createTempDirectory("ct-download-");Path source=storage.materialize(url,workspace.resolve("input"));if(!Files.isRegularFile(source))throw new BizException("媒体文件不存在");
   Path output=source;if(Boolean.TRUE.equals(post.getDownloadWatermark())){var author=users.selectById(post.getAuthorId());String uid=author==null||author.getPublicUid()==null?"":String.valueOf(author.getPublicUid());output=watermark.create(source,item.getType(),post.getAuthorNickname()+"  UID:"+uid,postId,mediaId,workspace.resolve("watermark"));}
   final Path temp=workspace;var stream=new java.io.FilterInputStream(Files.newInputStream(output)){@Override public void close()throws java.io.IOException{try{super.close();}finally{PublishMediaService.deleteWorkspace(temp);}}};
   Resource body=new InputStreamResource(stream);return ResponseEntity.ok().contentType(MediaType.parseMediaType(com.yyx.ciyuantown.common.storage.StorageService.mime(output))).contentLength(Files.size(output)).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\""+output.getFileName()+"\"").body(body);
  }catch(BizException e){PublishMediaService.deleteWorkspace(workspace);throw e;}catch(Exception e){PublishMediaService.deleteWorkspace(workspace);throw new BizException("下载文件生成失败");}
 }
}
