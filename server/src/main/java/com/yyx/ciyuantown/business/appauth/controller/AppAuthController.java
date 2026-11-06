package com.yyx.ciyuantown.business.appauth.controller;
import com.yyx.ciyuantown.business.appauth.dto.*;
import com.yyx.ciyuantown.business.appauth.service.*;
import com.yyx.ciyuantown.business.appauth.vo.AppLoginResult;
import com.yyx.ciyuantown.business.auth.dto.RefreshTokenParam;
import com.yyx.ciyuantown.common.api.Result;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import com.yyx.ciyuantown.business.appauth.annotation.AppLoginRequired;
import com.yyx.ciyuantown.common.interceptor.AuthInterceptor;
import jakarta.servlet.http.HttpServletRequest;
@RestController
@RequestMapping("/app/auth")
@RequiredArgsConstructor
public class AppAuthController {
    private final EmailCodeService codes;
    private final AppAuthService auth;
    @PostMapping("/sendCode")
    public Result<Void> sendCode(@Valid @RequestBody SendCodeParam p) { codes.send(p.qqNumber()); return Result.success(); }
    @PostMapping("/login")
    public Result<AppLoginResult> login(@Valid @RequestBody AppLoginParam p) { return Result.success(auth.login(p.qqNumber(),p.code())); }
    @PostMapping("/loginByPassword")
    public Result<AppLoginResult> loginByPassword(@Valid @RequestBody AppPasswordLoginParam p) { return Result.success(auth.loginByPassword(p.qqNumber(),p.password())); }
    @PostMapping("/refreshToken")
    public Result<AppLoginResult> refresh(@Valid @RequestBody RefreshTokenParam p) { return Result.success(auth.refreshToken(p.getRefreshToken())); }
    @PostMapping("/logout") @AppLoginRequired
    public Result<Void> logout(HttpServletRequest request){String header=request.getHeader("Authorization");auth.logout((Long)request.getAttribute(AuthInterceptor.ATTR_USER_ID),header.substring(7));return Result.success();}
}
