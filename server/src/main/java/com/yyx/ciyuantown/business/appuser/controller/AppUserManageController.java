package com.yyx.ciyuantown.business.appuser.controller;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yyx.ciyuantown.business.appuser.dto.*;
import com.yyx.ciyuantown.business.appuser.service.AppUserService;
import com.yyx.ciyuantown.business.appuser.vo.AppUserManageVO;
import com.yyx.ciyuantown.common.api.Result;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/app-user")
@RequiredArgsConstructor
public class AppUserManageController {
    private final AppUserService users;
    private final com.yyx.ciyuantown.business.appuser.service.UserDeletionService deletions;
    private final com.yyx.ciyuantown.business.appuser.service.LoginAccountService accounts;
    private final com.yyx.ciyuantown.business.appuser.service.UserDetailService details;
    private final com.yyx.ciyuantown.business.level.ExperienceAdjustmentService experienceAdjustments;

    @org.springframework.web.bind.annotation.PostMapping("/adjustExperience")
    public Result<com.yyx.ciyuantown.business.level.ExperienceAdjustmentService.AdjustmentResult> adjustExperience(
            jakarta.servlet.http.HttpServletRequest request, @Valid @RequestBody com.yyx.ciyuantown.business.appuser.dto.ExperienceAdjustmentParam input) {
        return Result.success(experienceAdjustments.adjust(operator(request), false, input));
    }
    @org.springframework.web.bind.annotation.GetMapping("/experienceHistory")
    public Result<java.util.Map<String,Object>> experienceHistory(jakarta.servlet.http.HttpServletRequest request,
            @org.springframework.web.bind.annotation.RequestParam Long id,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue="1") int pageNum,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue="20") int pageSize) {
        return Result.success(experienceAdjustments.history(operator(request), false, id, pageNum, pageSize));
    }
    private Long operator(jakarta.servlet.http.HttpServletRequest request) {
        return (Long)request.getAttribute(com.yyx.ciyuantown.common.interceptor.AuthInterceptor.ATTR_USER_ID);
    }
    @GetMapping("/detail")
    public Result<com.yyx.ciyuantown.business.appuser.vo.AppUserDetailVO> detail(@RequestParam Long id) { return Result.success(details.app(id)); }
    @PostMapping("/updateAccount")
    public Result<Void> updateAccount(jakarta.servlet.http.HttpServletRequest request, @Valid @RequestBody AccountUpdateParam p) {
        accounts.updateAppAccount(operator(request),p.id(),p.account()); return Result.success();
    }
    @PostMapping("/resetPassword")
    public Result<Void> resetPassword(jakarta.servlet.http.HttpServletRequest request, @Valid @RequestBody UserPasswordResetParam p) {
        accounts.resetAppPassword(operator(request),p.id(),p.password()); return Result.success();
    }
    @PostMapping("/list")
    public Result<Page<AppUserManageVO>> list(@RequestBody AppUserQueryParam q) { return Result.success(users.list(q)); }
    @PostMapping("/delete")
    public Result<Void> delete(jakarta.servlet.http.HttpServletRequest request, @Valid @RequestBody UserDeleteParam p) {
        deletions.deleteApp(operator(request),p.id()); return Result.success();
    }
    @PostMapping("/create")
    public Result<AppUserManageVO> create(jakarta.servlet.http.HttpServletRequest request, @Valid @RequestBody AppUserCreateParam p) { accounts.requireSuper(operator(request)); return Result.success(users.createByAdmin(p)); }
    @PostMapping("/updateStatus")
    public Result<Void> status(jakarta.servlet.http.HttpServletRequest request, @Valid @RequestBody AppUserStatusParam p) {
        accounts.requireSuper(operator(request));
        users.updateStatus(p.id(),p.status()); return Result.success();
    }
}
