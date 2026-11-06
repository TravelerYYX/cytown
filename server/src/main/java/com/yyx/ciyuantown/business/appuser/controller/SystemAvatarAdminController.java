package com.yyx.ciyuantown.business.appuser.controller;
import com.yyx.ciyuantown.business.appuser.dto.SystemAvatarUpdateParam;
import com.yyx.ciyuantown.business.appuser.service.SystemAvatarService;
import com.yyx.ciyuantown.business.appuser.vo.SystemAvatarAdminVO;
import com.yyx.ciyuantown.common.api.Result;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;
/** 管理端系统头像维护（受 AuthInterceptor 保护，需管理员登录）。 */
@RestController
@RequestMapping("/system-avatar")
@RequiredArgsConstructor
public class SystemAvatarAdminController {
    private final SystemAvatarService avatars;
    @GetMapping("/list")
    public Result<List<SystemAvatarAdminVO>> list(@RequestParam(required=false) Integer type) {
        return Result.success(avatars.list(type));
    }
    @PostMapping("/upload")
    public Result<SystemAvatarAdminVO> upload(@RequestParam("file") MultipartFile file,
                                              @RequestParam(defaultValue="0") Integer type,
                                              @RequestParam(required=false) Integer sort) {
        return Result.success(avatars.upload(file,type,sort));
    }
    @PostMapping("/update")
    public Result<SystemAvatarAdminVO> update(@Valid @RequestBody SystemAvatarUpdateParam p) {
        return Result.success(avatars.update(p));
    }
    @PostMapping("/delete")
    public Result<Void> delete(@RequestBody java.util.Map<String,Long> body) {
        avatars.delete(body.get("id")); return Result.success();
    }
}
