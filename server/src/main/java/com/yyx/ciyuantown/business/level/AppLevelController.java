package com.yyx.ciyuantown.business.level;

import com.yyx.ciyuantown.business.appauth.annotation.AppLoginRequired;
import com.yyx.ciyuantown.common.api.Result;
import com.yyx.ciyuantown.common.interceptor.AuthInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController @RequestMapping("/app/level") @RequiredArgsConstructor
public class AppLevelController {
    private final LevelService levels;
    private Long id(HttpServletRequest r){return (Long)r.getAttribute(AuthInterceptor.ATTR_USER_ID);}
    @GetMapping("/config") public Result<LevelConfig> config(){return Result.success(levels.config());}
    @AppLoginRequired @GetMapping("/me") public Result<LevelService.Progress> me(HttpServletRequest r){return Result.success(levels.progress(id(r)));}
    @AppLoginRequired @PostMapping("/sign-in") public Result<LevelService.SignResult> sign(HttpServletRequest r){return Result.success(levels.signIn(id(r)));}
    @AppLoginRequired @GetMapping("/history") public Result<Map<String,Object>> history(HttpServletRequest r,@RequestParam(defaultValue="1")int pageNum,@RequestParam(defaultValue="20")int pageSize){return Result.success(levels.history(id(r),pageNum,pageSize));}
}
