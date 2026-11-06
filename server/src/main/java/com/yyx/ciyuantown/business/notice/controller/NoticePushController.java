package com.yyx.ciyuantown.business.notice.controller;
import com.yyx.ciyuantown.business.notice.service.NoticePushService;
import com.yyx.ciyuantown.common.api.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/notice/push") @RequiredArgsConstructor
public class NoticePushController {
 private final NoticePushService push;
 @GetMapping("/config") public Result<?> config(){return Result.success(push.masked());}
 @PostMapping("/config") public Result<?> save(@RequestBody NoticePushService.Config config){return Result.success(push.save(config));}
 @PostMapping("/test") public Result<Void> test(@RequestBody Test p){push.test(p.cid());return Result.success();}
 @PostMapping("/retry") public Result<Void> retry(@RequestBody Retry p){push.retry(p.id());return Result.success();}
 public record Test(String cid){} public record Retry(Long id){}
}
