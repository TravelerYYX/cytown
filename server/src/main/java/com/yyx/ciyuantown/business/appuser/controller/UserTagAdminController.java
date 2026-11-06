package com.yyx.ciyuantown.business.appuser.controller;
import com.yyx.ciyuantown.business.appuser.dto.UserTagCreateParam;
import com.yyx.ciyuantown.business.appuser.dto.UserTagUpdateParam;
import com.yyx.ciyuantown.business.appuser.service.UserTagAdminService;
import com.yyx.ciyuantown.business.appuser.vo.UserTagAdminVO;
import com.yyx.ciyuantown.common.api.Result;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;
/** 管理端用户标签库维护（受 AuthInterceptor 保护，需管理员登录）。 */
@RestController
@RequestMapping("/user-tag")
@RequiredArgsConstructor
public class UserTagAdminController {
    private final UserTagAdminService tags;
    @GetMapping("/list")
    public Result<List<UserTagAdminVO>> list() { return Result.success(tags.list()); }
    @PostMapping("/create")
    public Result<UserTagAdminVO> create(@Valid @RequestBody UserTagCreateParam p) {
        return Result.success(tags.create(p));
    }
    @PostMapping("/update")
    public Result<UserTagAdminVO> update(@Valid @RequestBody UserTagUpdateParam p) {
        return Result.success(tags.update(p));
    }
    @PostMapping("/delete")
    public Result<Void> delete(@RequestBody java.util.Map<String,Long> body) {
        tags.delete(body.get("id")); return Result.success();
    }
}
