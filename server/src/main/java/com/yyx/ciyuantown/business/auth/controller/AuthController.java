package com.yyx.ciyuantown.business.auth.controller;

import com.yyx.ciyuantown.business.auth.dto.LoginParam;
import com.yyx.ciyuantown.business.auth.dto.RefreshTokenParam;
import com.yyx.ciyuantown.business.auth.dto.UpdateNicknameParam;
import com.yyx.ciyuantown.business.auth.dto.UpdatePasswordParam;
import com.yyx.ciyuantown.business.auth.dto.UpdateUserNameParam;
import com.yyx.ciyuantown.business.auth.service.AuthService;
import com.yyx.ciyuantown.business.auth.vo.LoginResult;
import com.yyx.ciyuantown.business.auth.vo.UserInfoVO;
import com.yyx.ciyuantown.common.api.Result;
import com.yyx.ciyuantown.common.interceptor.AuthInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 认证接口（对齐 Soybean-Admin 前端约定）
 */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public Result<LoginResult> login(@Valid @RequestBody LoginParam param) {
        return Result.success(authService.login(param.getUserName(), param.getPassword()));
    }

    @PostMapping("/refreshToken")
    public Result<LoginResult> refreshToken(@Valid @RequestBody RefreshTokenParam param) {
        return Result.success(authService.refreshToken(param.getRefreshToken()));
    }

    @GetMapping("/getUserInfo")
    public Result<UserInfoVO> getUserInfo(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return Result.success(authService.getUserInfo(userId));
    }

    @PostMapping("/updateUserName")
    public Result<Void> updateUserName(HttpServletRequest request, @Valid @RequestBody UpdateUserNameParam param) {
        Long userId = (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        authService.updateUserName(userId, param.getUserName());
        return Result.success();
    }

    @PostMapping("/updateNickname")
    public Result<Void> updateNickname(HttpServletRequest request, @Valid @RequestBody UpdateNicknameParam param) {
        Long userId = (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        authService.updateNickname(userId, param.getNickname());
        return Result.success();
    }

    @PostMapping("/updateAvatar")
    public Result<String> updateAvatar(HttpServletRequest request, @RequestParam("file") MultipartFile file) {
        Long userId = (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return Result.success(authService.updateAvatar(userId, file));
    }

    @PostMapping("/updatePassword")
    public Result<Void> updatePassword(HttpServletRequest request, @Valid @RequestBody UpdatePasswordParam param) {
        Long userId = (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        authService.updatePassword(userId, param.getOldPassword(), param.getNewPassword());
        return Result.success();
    }
}
