package com.yyx.ciyuantown.common.storage;

import com.yyx.ciyuantown.common.api.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor @RequestMapping("/global-settings")
public class GlobalSettingsController {
    private final GlobalSettingsService settings;
    @GetMapping public Result<GlobalSettingsService.Settings> get() { return Result.success(settings.get()); }
    @PostMapping public Result<GlobalSettingsService.Settings> save(@RequestBody GlobalSettingsService.Settings value) {
        return Result.success(settings.save(value));
    }
}
