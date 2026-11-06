package com.yyx.ciyuantown.business.publish.controller;
import com.yyx.ciyuantown.business.appauth.annotation.AppLoginRequired;
import com.yyx.ciyuantown.business.publish.dto.PublishRequest;
import com.yyx.ciyuantown.business.publish.entity.Topic;
import com.yyx.ciyuantown.business.publish.service.*;
import com.yyx.ciyuantown.common.api.Result;
import com.yyx.ciyuantown.common.interceptor.AuthInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;

@RestController @RequestMapping("/app/publish") @AppLoginRequired @RequiredArgsConstructor
public class AppPublishController {
 private final PublishService service; private final PublishMediaService media;
 private final PublishChannelAccessService channelAccess;
 private Long user(HttpServletRequest r){return (Long)r.getAttribute(AuthInterceptor.ATTR_USER_ID);}
 @GetMapping("/config") public Result<PublishService.PublishConfig> config(HttpServletRequest r){return Result.success(service.config(channelAccess.isOfficial(user(r))));}
 @GetMapping("/topics") public Result<List<Topic>> topics(@RequestParam(required=false)String keyword){return Result.success(service.searchTopics(keyword));}
 @PostMapping("/topics/custom") public Result<Topic> custom(HttpServletRequest r,@RequestBody PublishRequest.CustomTopic p){return Result.success(service.createCustomTopic(user(r),p));}
 @GetMapping("/following") public Result<List<PublishService.FollowingView>> following(HttpServletRequest r,@RequestParam(required=false)String keyword){return Result.success(service.following(user(r),keyword));}
 @PostMapping("/assets/image") public Result<PublishMediaService.AssetView> image(HttpServletRequest r,@RequestParam("file")MultipartFile file){return Result.success(media.uploadImage(user(r),file));}
 @PostMapping("/assets/video") public Result<PublishMediaService.AssetView> video(HttpServletRequest r,@RequestParam("file")MultipartFile file){return Result.success(media.uploadVideo(user(r),file));}
 @GetMapping("/assets/{id}") public Result<PublishMediaService.AssetView> asset(HttpServletRequest r,@PathVariable Long id){return Result.success(media.get(user(r),id));}
 @PostMapping("/assets/{id}/retry") public Result<PublishMediaService.AssetView> retry(HttpServletRequest r,@PathVariable Long id){return Result.success(media.retry(user(r),id));}
 @PostMapping("/assets/{id}/cover") public Result<PublishMediaService.AssetView> cover(HttpServletRequest r,@PathVariable Long id,@RequestBody PublishRequest.Crop p){return Result.success(media.cover(user(r),id,p));}
 @GetMapping("/drafts") public Result<List<PublishService.DraftView>> drafts(HttpServletRequest r){return Result.success(service.drafts(user(r)));}
 @GetMapping("/drafts/{id}") public Result<PublishService.DraftView> draft(HttpServletRequest r,@PathVariable Long id){return Result.success(service.draft(user(r),id));}
 @PostMapping("/drafts/save") public Result<PublishService.DraftView> saveDraft(HttpServletRequest r,@RequestBody PublishRequest.DraftSave p){return Result.success(service.saveDraft(user(r),p));}
 @PostMapping("/drafts/delete") public Result<Void> deleteDraft(HttpServletRequest r,@RequestBody PublishRequest.IdParam p){service.deleteDraft(user(r),p.id());return Result.success();}
 @PostMapping("/submit") public Result<PublishService.SubmitResult> submit(HttpServletRequest r,@RequestBody PublishRequest.Submit p){return Result.success(service.submit(user(r),p));}
}
