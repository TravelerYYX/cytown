package com.yyx.ciyuantown.business.appupdate;
import com.yyx.ciyuantown.common.api.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;
@RestController @RequiredArgsConstructor
public class AppReleaseController {
 private final AppReleaseService service;
 @GetMapping("/app-releases") public Result<List<AppReleaseService.Release>> list(){return Result.success(service.list());}
 @PostMapping("/app-releases") public Result<AppReleaseService.Release> create(@RequestBody AppReleaseService.Draft d){return Result.success(service.create(d));}
 @PutMapping("/app-releases/{id}") public Result<AppReleaseService.Release> save(@PathVariable long id,@RequestBody AppReleaseService.Draft d){return Result.success(service.save(id,d));}
 @PostMapping("/app-releases/{id}/package") public Result<AppReleaseService.Release> upload(@PathVariable long id,@RequestParam MultipartFile file){return Result.success(service.upload(id,file));}
 @PostMapping("/app-releases/{id}/publish") public Result<AppReleaseService.Release> publish(@PathVariable long id){return Result.success(service.publish(id));}
 @PostMapping("/app-releases/{id}/withdraw") public Result<AppReleaseService.Release> withdraw(@PathVariable long id){return Result.success(service.withdraw(id));}
 @DeleteMapping("/app-releases/{id}") public Result<Void> delete(@PathVariable long id){service.delete(id);return Result.success();}
 public record Force(boolean forceUpdate){}
 @PatchMapping("/app-releases/{id}/force") public Result<AppReleaseService.Release> force(@PathVariable long id,@RequestBody Force p){return Result.success(service.force(id,p.forceUpdate()));}
 @GetMapping("/app/update/check") public Result<AppReleaseService.Check> check(@RequestParam long versionCode){return Result.success(service.check(versionCode));}
}
