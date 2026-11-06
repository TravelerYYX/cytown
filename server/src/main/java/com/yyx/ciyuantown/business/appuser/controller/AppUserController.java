package com.yyx.ciyuantown.business.appuser.controller;
import com.yyx.ciyuantown.business.appauth.annotation.AppLoginRequired;
import com.yyx.ciyuantown.business.appuser.dto.*;
import com.yyx.ciyuantown.business.appuser.service.AppUserService;
import com.yyx.ciyuantown.business.appuser.vo.*;
import com.yyx.ciyuantown.common.api.Result;
import com.yyx.ciyuantown.common.interceptor.AuthInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;
@RestController
@RequestMapping("/app/user")
@AppLoginRequired
@RequiredArgsConstructor
public class AppUserController {
    private final AppUserService users;
    private Long id(HttpServletRequest r) { return (Long) r.getAttribute(AuthInterceptor.ATTR_USER_ID); }
    @GetMapping("/me")
    public Result<AppUserInfoVO> me(HttpServletRequest r) { return Result.success(users.me(id(r))); }
    @PostMapping("/password/set")
    public Result<Void> setPassword(HttpServletRequest r, @Valid @RequestBody AppPasswordParam p) {
        users.setPassword(id(r), p.password()); return Result.success();
    }
    @PostMapping("/password/update")
    public Result<Void> updatePassword(HttpServletRequest r, @Valid @RequestBody AppPasswordUpdateParam p) {
        users.updatePassword(id(r), p.oldPassword(), p.newPassword()); return Result.success();
    }
    @PostMapping("/updateNickname")
    public Result<AppUserInfoVO> nickname(HttpServletRequest r, @Valid @RequestBody UpdateNicknameParam p) {
        return Result.success(users.updateNickname(id(r),p.nickname()));
    }
    @PostMapping("/updateGender")
    public Result<AppUserInfoVO> gender(HttpServletRequest r, @Valid @RequestBody UpdateGenderParam p) {
        return Result.success(users.updateGender(id(r),p.gender()));
    }
    @PostMapping("/updateSignature")
    public Result<AppUserInfoVO> signature(HttpServletRequest r, @RequestBody UpdateSignatureParam p) {
        return Result.success(users.updateSignature(id(r),p.signature()));
    }
    @PostMapping("/updateCardBackground")
    public Result<AppUserInfoVO> cardBackground(HttpServletRequest r, @Valid @RequestBody UpdateCardBackgroundParam p) {
        return Result.success(users.updateCardBackground(id(r),p.background()));
    }
    @PostMapping("/updateAvatarFrame")
    public Result<AppUserInfoVO> avatarFrame(HttpServletRequest r, @Valid @RequestBody UpdateAvatarFrameParam p) {
        return Result.success(users.updateAvatarFrame(id(r), p.frameId()));
    }
    @PostMapping("/updateAvatar")
    public Result<AppUserInfoVO> avatar(HttpServletRequest r,@RequestParam("file") MultipartFile file) {
        return Result.success(users.updateAvatar(id(r),file));
    }
    @PostMapping("/selectSystemAvatar")
    public Result<AppUserInfoVO> selectSystemAvatar(HttpServletRequest r, @Valid @RequestBody SelectSystemAvatarParam p) {
        return Result.success(users.selectSystemAvatar(id(r),p.avatarId()));
    }
    @GetMapping("/systemAvatars")
    public Result<SystemAvatarGroupVO> systemAvatars() { return Result.success(users.systemAvatars()); }
    @GetMapping("/tags")
    public Result<List<TagCategoryVO>> tags() { return Result.success(users.tagLibrary()); }
    @PostMapping("/updateTags")
    public Result<AppUserInfoVO> updateTags(HttpServletRequest r, @RequestBody UpdateTagsParam p) {
        return Result.success(users.updateTags(id(r),p.items()));
    }
}
