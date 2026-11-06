package com.yyx.ciyuantown.business.homeconfig.controller;
import com.yyx.ciyuantown.business.homeconfig.service.HomeConfigService;
import com.yyx.ciyuantown.common.api.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/app/home") @RequiredArgsConstructor
public class AppHomeController { private final HomeConfigService service; @GetMapping("/config") public Result<HomeConfigService.AppHomeConfig> config(){return Result.success(service.appConfig());} }
