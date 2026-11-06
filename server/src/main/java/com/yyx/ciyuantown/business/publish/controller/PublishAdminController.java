package com.yyx.ciyuantown.business.publish.controller;
import com.yyx.ciyuantown.business.publish.dto.PublishRequest;
import com.yyx.ciyuantown.business.publish.entity.*;
import com.yyx.ciyuantown.business.publish.service.PublishAdminService;
import com.yyx.ciyuantown.business.publish.service.PublishFeedService;
import com.yyx.ciyuantown.common.api.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController @RequestMapping("/publish") @RequiredArgsConstructor
public class PublishAdminController {
 private final PublishAdminService service;
 private final PublishFeedService feeds;
 @GetMapping("/settings")public Result<PublishAdminService.Settings> settings(){return Result.success(service.settings());}
 @PostMapping("/settings/update")public Result<PublishAdminService.Settings> settings(@RequestBody PublishAdminService.Settings p){return Result.success(service.updateSettings(p));}
 @GetMapping("/rules")public Result<List<PublishRule>> rules(){return Result.success(service.rules());}
 @PostMapping("/rules/update")public Result<PublishRule> rule(@RequestBody PublishRule p){return Result.success(service.updateRule(p));}
 @GetMapping("/topics")public Result<List<Topic>> topics(){return Result.success(service.topics());}
 @PostMapping("/topics/save")public Result<Topic> topic(@RequestBody Topic p){return Result.success(service.saveTopic(p));}
 @PostMapping("/topics/delete")public Result<Void> deleteTopic(@RequestBody PublishRequest.IdParam p){service.deleteTopic(p.id());return Result.success();}
 @PostMapping("/topics/sort")public Result<Void> sortTopics(@RequestBody PublishRequest.SortParam p){service.sortTopics(p.ids());return Result.success();}
 @GetMapping("/catalog/groups")public Result<List<EmojiGroup>> groups(){return Result.success(service.groups());}
 @GetMapping("/catalog/emojis")public Result<List<PublishEmoji>> emojis(){return Result.success(service.emojis());}
 @GetMapping("/catalog/colors")public Result<List<TextColor>> colors(){return Result.success(service.colors());}
 @GetMapping("/catalog/dividers")public Result<List<DividerStyle>> dividers(){return Result.success(service.dividers());}
 @PostMapping("/catalog/group/save")public Result<EmojiGroup> group(@RequestBody EmojiGroup p){return Result.success(service.saveGroup(p));}
 @PostMapping("/catalog/emoji/save")public Result<PublishEmoji> emoji(@RequestBody PublishEmoji p){return Result.success(service.saveEmoji(p));}
 @PostMapping("/catalog/color/save")public Result<TextColor> color(@RequestBody TextColor p){return Result.success(service.saveColor(p));}
 @PostMapping("/catalog/divider/save")public Result<DividerStyle> divider(@RequestBody DividerStyle p){return Result.success(service.saveDivider(p));}
 public record CatalogDelete(String type,Long id){}
 @PostMapping("/catalog/delete")public Result<Void> deleteCatalog(@RequestBody CatalogDelete p){service.deleteCatalog(p.type(),p.id());return Result.success();}
 @GetMapping("/health")public Result<PublishAdminService.Health> health(){return Result.success(service.health());}
 @PostMapping("/feed/rebuild")public Result<Integer> rebuildFeed(){return Result.success(feeds.rebuild());}
}
