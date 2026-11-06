package com.yyx.ciyuantown.business.chat;
import com.yyx.ciyuantown.common.api.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
@RestController @RequiredArgsConstructor
public class ChatAdminController {
 private final ChatSettings settings;
 @GetMapping("/chat-settings") public Result<ChatSettings.Config> get(){return Result.success(settings.get());}
 @PostMapping("/chat-settings") public Result<ChatSettings.Config> save(@RequestBody ChatSettings.Config config){return Result.success(settings.save(config));}
 @GetMapping("/chat-settings/offline-reminder") public Result<ChatSettings.OfflineReminder> offlineReminder(){return Result.success(settings.offlineReminder());}
 @PostMapping("/chat-settings/offline-reminder") public Result<ChatSettings.OfflineReminder> saveOfflineReminder(@Valid @RequestBody ChatSettings.OfflineReminder reminder){return Result.success(settings.saveOfflineReminder(reminder));}
}
