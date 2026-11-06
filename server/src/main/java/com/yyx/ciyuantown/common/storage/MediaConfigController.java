package com.yyx.ciyuantown.common.storage;
import com.yyx.ciyuantown.common.api.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
@RestController @RequiredArgsConstructor
public class MediaConfigController {
 private final StorageService storage;private final MediaSettingsService settings;
 @GetMapping("/media-config/storage") public Result<StorageService.Config> storage(){return Result.success(storage.masked());}
 @PostMapping("/media-config/storage") public Result<StorageService.Config> save(@RequestBody StorageService.Config p){return Result.success(storage.save(p));}
 @PostMapping("/media-config/storage/test") public Result<Void> test(@RequestBody StorageService.Config p){storage.test(p);return Result.success();}
 @GetMapping("/media-config/processing") public Result<MediaSettingsService.Settings> processing(){return Result.success(settings.get());}
 @PostMapping("/media-config/processing") public Result<MediaSettingsService.Settings> processing(@RequestBody MediaSettingsService.Settings p){return Result.success(settings.save(p));}
 @GetMapping("/app/media/config") public Result<java.util.Map<String,Long>> publicConfig(){return Result.success(java.util.Map.of("cacheMaxBytes",settings.get().images().cacheMaxBytes()));}
}
