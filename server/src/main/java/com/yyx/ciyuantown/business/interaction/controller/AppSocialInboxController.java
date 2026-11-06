package com.yyx.ciyuantown.business.interaction.controller;

import com.yyx.ciyuantown.business.appauth.annotation.AppLoginRequired;
import com.yyx.ciyuantown.business.interaction.service.SocialInboxService;
import com.yyx.ciyuantown.common.api.Result;
import com.yyx.ciyuantown.common.interceptor.AuthInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequestMapping("/app/social") @RequiredArgsConstructor @AppLoginRequired
public class AppSocialInboxController {
    private final SocialInboxService inbox;
    private Long user(HttpServletRequest request){return (Long)request.getAttribute(AuthInterceptor.ATTR_USER_ID);}
    @GetMapping("/summary") public Result<Map<String,Object>> summary(HttpServletRequest request){return Result.success(inbox.summary(user(request)));}
    @GetMapping("/notifications") public Result<Map<String,Object>> list(HttpServletRequest request,
        @RequestParam String kind,@RequestParam(defaultValue="false") boolean following,
        @RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="20") int pageSize){
        return Result.success(inbox.list(user(request),kind,following,pageNum,pageSize));
    }
    public record Read(List<String> ids){}
    @PostMapping("/read") public Result<Void> read(HttpServletRequest request,@RequestBody Read body){inbox.read(user(request),body.ids());return Result.success();}
}
