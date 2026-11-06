package com.yyx.ciyuantown.business.post.controller;

import com.yyx.ciyuantown.business.appauth.annotation.AppLoginRequired;
import com.yyx.ciyuantown.business.post.service.CommentService;
import com.yyx.ciyuantown.business.post.dto.CommentQueryParam;
import com.yyx.ciyuantown.business.post.vo.MineCommentVO;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yyx.ciyuantown.business.publish.service.PublishMediaService;
import com.yyx.ciyuantown.common.api.Result;
import com.yyx.ciyuantown.common.interceptor.AuthInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/app/comment")
@RequiredArgsConstructor
public class AppCommentController {
    private final CommentService comments;
    private final PublishMediaService media;
    @GetMapping("/mine") @AppLoginRequired
    public Result<Page<MineCommentVO>> mine(HttpServletRequest request,@ModelAttribute CommentQueryParam query) {
        return Result.success(comments.mine((Long)request.getAttribute(AuthInterceptor.ATTR_USER_ID),query));
    }
    @GetMapping("/config")
    public Result<CommentService.Config> config() { return Result.success(comments.config()); }
    @PostMapping("/assets/image") @AppLoginRequired
    public Result<PublishMediaService.AssetView> image(HttpServletRequest request,@RequestParam("file") MultipartFile file) {
        return Result.success(media.uploadCommentImage((Long)request.getAttribute(AuthInterceptor.ATTR_USER_ID),file));
    }
}
