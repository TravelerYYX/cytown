package com.yyx.ciyuantown.business.appuser.controller;
import com.yyx.ciyuantown.business.appauth.annotation.AppLoginRequired;
import com.yyx.ciyuantown.business.appuser.service.UserLocationService;
import com.yyx.ciyuantown.common.api.Result;
import com.yyx.ciyuantown.common.interceptor.AuthInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/app/user/location") @AppLoginRequired @RequiredArgsConstructor
public class AppLocationController {
    private final UserLocationService locations;
    @GetMapping public Result<UserLocationService.Location> read(HttpServletRequest r){return Result.success(locations.read((Long)r.getAttribute(AuthInterceptor.ATTR_USER_ID)));}
    @PostMapping("/refresh") public Result<UserLocationService.Location> refresh(HttpServletRequest r){return Result.success(locations.refresh((Long)r.getAttribute(AuthInterceptor.ATTR_USER_ID),r));}
}
