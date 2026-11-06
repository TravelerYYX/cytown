package com.yyx.ciyuantown.business.post.controller;

import com.yyx.ciyuantown.business.post.service.PostShareSettings;
import com.yyx.ciyuantown.common.api.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor @RequestMapping("/post-share-settings")
public class PostShareSettingsController {
 private final PostShareSettings settings;
 @GetMapping public Result<PostShareSettings.Config> get(){return Result.success(settings.get());}
 @PostMapping public Result<PostShareSettings.Config> save(@RequestBody PostShareSettings.Config body){return Result.success(settings.save(body));}
}
