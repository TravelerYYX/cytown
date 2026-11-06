package com.yyx.ciyuantown.business.homeconfig.controller;
import com.yyx.ciyuantown.business.homeconfig.dto.*;
import com.yyx.ciyuantown.business.homeconfig.EntryTarget;
import com.yyx.ciyuantown.business.homeconfig.entity.*;
import com.yyx.ciyuantown.business.homeconfig.service.HomeConfigService;
import com.yyx.ciyuantown.common.api.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;
@RestController @RequestMapping("/home") @RequiredArgsConstructor
public class HomeConfigController {
 private final HomeConfigService service;
 @GetMapping("/header-entry/get") public Result<HeaderEntryParam> headerEntry(){return Result.success(service.headerEntry());}
 @PostMapping("/header-entry/update") public Result<HeaderEntryParam> updateHeaderEntry(@RequestBody HeaderEntryParam p){return Result.success(service.updateHeaderEntry(p));}
 @GetMapping("/entry/pages") public Result<List<EntryTarget.Page>> entryPages(){return Result.success(EntryTarget.PAGES);}
 @GetMapping("/layout/get") public Result<HomeConfigService.LayoutConfig> layout(){return Result.success(service.layoutConfig());}
 @PostMapping("/layout/update") public Result<HomeConfigService.LayoutConfig> updateLayout(@RequestBody LayoutConfigParam p){return Result.success(service.updateLayout(p));}
 @GetMapping("/channel/list") public Result<List<HomeChannel>> channels(){return Result.success(service.channels(false));}
 @PostMapping("/channel/create") public Result<HomeChannel> createChannel(@RequestBody HomeChannelParam p){return Result.success(service.saveChannel(p,true));}
 @PostMapping("/channel/update") public Result<HomeChannel> updateChannel(@RequestBody HomeChannelParam p){return Result.success(service.saveChannel(p,false));}
 @PostMapping("/channel/delete") public Result<Void> deleteChannel(@RequestBody IdParam p){service.deleteChannel(p.id());return Result.success();}
 @PostMapping("/channel/sort") public Result<Void> sortChannels(@RequestBody SortParam p){service.sortChannels(p.ids());return Result.success();}
 @GetMapping("/entry/list") public Result<List<HomeEntry>> entries(){return Result.success(service.entries(false));}
 @PostMapping("/entry/create") public Result<HomeEntry> createEntry(@RequestBody HomeEntryParam p){return Result.success(service.saveEntry(p,true));}
 @PostMapping("/entry/update") public Result<HomeEntry> updateEntry(@RequestBody HomeEntryParam p){return Result.success(service.saveEntry(p,false));}
 @PostMapping("/entry/delete") public Result<Void> deleteEntry(@RequestBody IdParam p){service.deleteEntry(p.id());return Result.success();}
 @PostMapping("/entry/sort") public Result<Void> sortEntries(@RequestBody SortParam p){service.sortEntries(p.ids());return Result.success();}
 @GetMapping("/news/get") public Result<HomeConfigService.NewsConfig> news(){return Result.success(service.newsConfig());}
 @PostMapping("/news/update") public Result<HomeConfigService.NewsConfig> updateNews(@RequestBody NewsConfigParam p){return Result.success(service.updateNews(p));}
}
