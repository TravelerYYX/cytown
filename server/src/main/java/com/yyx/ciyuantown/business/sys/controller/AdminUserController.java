package com.yyx.ciyuantown.business.sys.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yyx.ciyuantown.business.sys.dto.AdminUserCreateParam;
import com.yyx.ciyuantown.business.sys.dto.AdminUserQueryParam;
import com.yyx.ciyuantown.business.sys.dto.AdminUserResetPasswordParam;
import com.yyx.ciyuantown.business.sys.dto.AdminUserStatusParam;
import com.yyx.ciyuantown.business.sys.service.AdminUserAdminService;
import com.yyx.ciyuantown.business.sys.vo.AdminUserVO;
import com.yyx.ciyuantown.common.api.Result;
import com.yyx.ciyuantown.common.interceptor.AuthInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 官方账号管理接口（仅超管可用：POST 不在只读白名单内，官方账号由 ReadOnlyInterceptor 统一拦截）
 */
@RestController
@RequestMapping("/admin-user")
@RequiredArgsConstructor
public class AdminUserController {

    private final AdminUserAdminService adminUserService;
    private final com.yyx.ciyuantown.business.appuser.service.UserDeletionService deletions;
    private final com.yyx.ciyuantown.business.appuser.service.LoginAccountService accounts;
    private final com.yyx.ciyuantown.business.appuser.service.UserDetailService details;
    private final com.yyx.ciyuantown.business.level.ExperienceAdjustmentService experienceAdjustments;

    @org.springframework.web.bind.annotation.PostMapping("/adjustExperience")
    public Result<com.yyx.ciyuantown.business.level.ExperienceAdjustmentService.AdjustmentResult> adjustExperience(
            jakarta.servlet.http.HttpServletRequest request, @Valid @RequestBody com.yyx.ciyuantown.business.appuser.dto.ExperienceAdjustmentParam input) {
        return Result.success(experienceAdjustments.adjust(operatorId(request), true, input));
    }
    @org.springframework.web.bind.annotation.GetMapping("/experienceHistory")
    public Result<java.util.Map<String,Object>> experienceHistory(jakarta.servlet.http.HttpServletRequest request,
            @org.springframework.web.bind.annotation.RequestParam Long id,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue="1") int pageNum,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue="20") int pageSize) {
        return Result.success(experienceAdjustments.history(operatorId(request), true, id, pageNum, pageSize));
    }
    @org.springframework.web.bind.annotation.GetMapping("/detail")
    public Result<com.yyx.ciyuantown.business.sys.vo.OfficialAccountDetailVO> detail(HttpServletRequest request, @org.springframework.web.bind.annotation.RequestParam Long id) {
        return Result.success(details.official(operatorId(request),id));
    }
    @PostMapping("/updateAccount")
    public Result<Void> updateAccount(HttpServletRequest request, @Valid @RequestBody com.yyx.ciyuantown.business.appuser.dto.AccountUpdateParam p) {
        accounts.updateOfficialAccount(operatorId(request),p.id(),p.account()); return Result.success();
    }

    private Long operatorId(HttpServletRequest request) {
        return (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
    }

    @PostMapping("/create")
    public Result<AdminUserVO> create(HttpServletRequest request, @Valid @RequestBody AdminUserCreateParam param) {
        return Result.success(adminUserService.create(operatorId(request), param));
    }

    @PostMapping("/delete")
    public Result<Void> delete(HttpServletRequest request, @Valid @RequestBody com.yyx.ciyuantown.business.appuser.dto.UserDeleteParam param) {
        deletions.deleteOfficial(operatorId(request),param.id());
        return Result.success();
    }

    @PostMapping("/list")
    public Result<Page<AdminUserVO>> list(@RequestBody AdminUserQueryParam query) {
        return Result.success(adminUserService.list(query));
    }

    @PostMapping("/resetPassword")
    public Result<Void> resetPassword(HttpServletRequest request, @Valid @RequestBody AdminUserResetPasswordParam param) {
        adminUserService.resetPassword(operatorId(request), param.id(), param.password());
        return Result.success();
    }

    @PostMapping("/updateStatus")
    public Result<Void> updateStatus(HttpServletRequest request, @Valid @RequestBody AdminUserStatusParam param) {
        adminUserService.updateStatus(operatorId(request), param.id(), param.status());
        return Result.success();
    }
}
