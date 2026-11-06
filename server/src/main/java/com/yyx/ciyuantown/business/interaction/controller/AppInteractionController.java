package com.yyx.ciyuantown.business.interaction.controller;
import com.yyx.ciyuantown.business.appauth.annotation.AppLoginRequired;
import com.yyx.ciyuantown.business.interaction.service.InteractionService;
import com.yyx.ciyuantown.business.interaction.vo.InteractionResultVO;
import com.yyx.ciyuantown.common.api.Result;
import com.yyx.ciyuantown.common.interceptor.AuthInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/app/interaction")
@AppLoginRequired
@RequiredArgsConstructor
public class AppInteractionController {
    private final InteractionService interactions;
    public record ToggleParam(@NotNull(message="帖子ID不能为空") @Positive(message="帖子ID必须为正数") Long postId) {}
    public record FollowParam(@NotNull(message="用户ID不能为空") @Positive(message="用户ID必须为正数") Long userId) {}
    @PostMapping("/toggleLike")
    public Result<InteractionResultVO> like(HttpServletRequest r,@Valid @RequestBody ToggleParam p) {
        return Result.success(interactions.toggleLike((Long)r.getAttribute(AuthInterceptor.ATTR_USER_ID),p.postId()));
    }
    @PostMapping("/toggleFavorite")
    public Result<InteractionResultVO> favorite(HttpServletRequest r,@Valid @RequestBody ToggleParam p) {
        return Result.success(interactions.toggleFavorite((Long)r.getAttribute(AuthInterceptor.ATTR_USER_ID),p.postId()));
    }
    @PostMapping("/toggleFollow")
    public Result<InteractionResultVO> follow(HttpServletRequest r,@Valid @RequestBody FollowParam p){return Result.success(interactions.toggleFollow((Long)r.getAttribute(AuthInterceptor.ATTR_USER_ID),p.userId()));}
    @GetMapping("/followState")
    public Result<InteractionResultVO> followState(HttpServletRequest r,@RequestParam Long userId) {
        if(userId<1)throw new com.yyx.ciyuantown.common.exception.BizException("用户ID必须为正数");
        return Result.success(interactions.followState((Long)r.getAttribute(AuthInterceptor.ATTR_USER_ID),userId));
    }
}
