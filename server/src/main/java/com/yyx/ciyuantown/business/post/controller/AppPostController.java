package com.yyx.ciyuantown.business.post.controller;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yyx.ciyuantown.business.post.dto.*;
import com.yyx.ciyuantown.business.post.service.*;
import com.yyx.ciyuantown.business.post.vo.*;
import com.yyx.ciyuantown.common.api.Result;
import lombok.RequiredArgsConstructor;
import com.yyx.ciyuantown.business.interaction.service.InteractionService;
import com.yyx.ciyuantown.common.interceptor.AuthInterceptor;
import com.yyx.ciyuantown.business.publish.dto.PublishRequest;
import com.yyx.ciyuantown.business.publish.service.PublishService;
import com.yyx.ciyuantown.business.appauth.annotation.AppLoginRequired;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/app/post")
@RequiredArgsConstructor
public class AppPostController {
    private final PostService posts;
    private final CommentService comments;
    private final InteractionService interactions;
    private final PublishService publishing;
    private final com.yyx.ciyuantown.business.homeconfig.mapper.HomeChannelMapper channels;
    private Page<PostListVO> personalized(Page<PostListVO> base, HttpServletRequest request) {
        var copy = new Page<PostListVO>(base.getCurrent(), base.getSize(), base.getTotal());
        return copy.setRecords(interactions.withState(base.getRecords(), (Long)request.getAttribute(AuthInterceptor.ATTR_USER_ID)));
    }
    @GetMapping("/list")
    public Result<Page<PostListVO>> list(@ModelAttribute PostQueryParam q, HttpServletRequest request) {
        q.validate();
        if(q.getChannelId()!=null){
            var channel=channels.selectById(q.getChannelId());
            if(channel!=null&&Boolean.TRUE.equals(channel.getShowAllPosts()))q.setChannelId(null);
        }
        return Result.success(personalized(posts.list(q,true), request));
    }
    @GetMapping("/mine") @AppLoginRequired
    public Result<Page<PostListVO>> mine(@ModelAttribute PostQueryParam q,HttpServletRequest request) {
        return Result.success(personalized(posts.listMine(q,(Long)request.getAttribute(AuthInterceptor.ATTR_USER_ID)), request));
    }
    @GetMapping("/mine-search") @AppLoginRequired
    public Result<Page<PostListVO>> searchMine(@ModelAttribute PostQueryParam q,
            @RequestParam(defaultValue="publish") String scope, HttpServletRequest request) {
        return Result.success(personalized(posts.searchMine(q,(Long)request.getAttribute(AuthInterceptor.ATTR_USER_ID),scope),request));
    }
    public record PinRequest(boolean pinned) {}
    public record EditTextRequest(String title, String content) {}
    @PostMapping("/{id}/pin") @AppLoginRequired
    public Result<Void> pin(@PathVariable Long id, @RequestBody PinRequest body, HttpServletRequest request) {
        posts.setProfilePinned(id, (Long)request.getAttribute(AuthInterceptor.ATTR_USER_ID), body.pinned());
        return Result.success();
    }
    @PostMapping("/{id}/edit-text") @AppLoginRequired
    public Result<Void> editText(@PathVariable Long id, @RequestBody EditTextRequest body, HttpServletRequest request) {
        posts.editOwnedText(id, (Long)request.getAttribute(AuthInterceptor.ATTR_USER_ID), body.title(), body.content());
        return Result.success();
    }
    @PostMapping("/{id}/delete") @AppLoginRequired
    public Result<Void> delete(@PathVariable Long id, HttpServletRequest request) {
        posts.deleteOwned(id, (Long)request.getAttribute(AuthInterceptor.ATTR_USER_ID));
        return Result.success();
    }
    @GetMapping("/following") @AppLoginRequired
    public Result<Page<PostListVO>> following(@ModelAttribute PostQueryParam q,HttpServletRequest request){return Result.success(personalized(posts.listFollowing(q,(Long)request.getAttribute(AuthInterceptor.ATTR_USER_ID)), request));}
    @GetMapping("/favorites") @AppLoginRequired
    public Result<Page<PostListVO>> favorites(@ModelAttribute PostQueryParam q,HttpServletRequest request){return Result.success(personalized(posts.listFavorites(q,(Long)request.getAttribute(AuthInterceptor.ATTR_USER_ID)), request));}
    @GetMapping("/likes") @AppLoginRequired
    public Result<Page<PostListVO>> likes(@ModelAttribute PostQueryParam q, HttpServletRequest request) {
        return Result.success(personalized(posts.listLikes(q, (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID)), request));
    }
    @GetMapping("/history") @AppLoginRequired
    public Result<Page<PostListVO>> history(@ModelAttribute PostQueryParam q, HttpServletRequest request) {
        return Result.success(personalized(posts.listHistory(q, (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID)), request));
    }
    @PostMapping("/{id}/view") @AppLoginRequired
    public Result<Void> recordView(@PathVariable Long id, HttpServletRequest request) {
        posts.recordView(id, (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID));
        return Result.success();
    }
    @GetMapping("/{id}")
    public Result<PostDetailVO> detail(@PathVariable Long id, HttpServletRequest request) {
        posts.requirePost(id,true);
        var detail = new PostDetailVO();
        org.springframework.beans.BeanUtils.copyProperties(posts.detail(id,true), detail);
        Long userId=(Long)request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        interactions.fillState(detail,userId);
        if(detail.getStatus()!=1)throw new com.yyx.ciyuantown.common.exception.BizException("帖子不存在或已下架");
        detail.setPoll(publishing.pollView(id,userId));
        return Result.success(detail);
    }
    @GetMapping("/{id}/comments")
    public Result<Page<CommentVO>> comments(@PathVariable Long id,@ModelAttribute CommentQueryParam q,HttpServletRequest request) {
        return Result.success(comments.list(id,q,(Long)request.getAttribute(AuthInterceptor.ATTR_USER_ID)));
    }
    @GetMapping("/{id}/comments/{commentId}/locate")
    public Result<CommentService.Focus> locate(@PathVariable Long id,@PathVariable Long commentId,@ModelAttribute CommentQueryParam q,HttpServletRequest request){
        return Result.success(comments.locate(id,commentId,q,(Long)request.getAttribute(AuthInterceptor.ATTR_USER_ID)));
    }
    @GetMapping("/{id}/comments/{rootId}/replies")
    public Result<Page<CommentVO>> replies(@PathVariable Long id,@PathVariable Long rootId,@ModelAttribute CommentQueryParam q,HttpServletRequest request) {
        return Result.success(comments.replies(id,rootId,q,(Long)request.getAttribute(AuthInterceptor.ATTR_USER_ID)));
    }
    @PostMapping("/{id}/comments") @AppLoginRequired
    public Result<CommentVO> createComment(@PathVariable Long id,@RequestBody CommentCreateParam p,HttpServletRequest request) {
        return Result.success(comments.create(id,(Long)request.getAttribute(AuthInterceptor.ATTR_USER_ID),p));
    }
    @PostMapping("/{id}/comments/{commentId}/reaction") @AppLoginRequired
    public Result<CommentService.ReactionResult> reactComment(@PathVariable Long id,@PathVariable Long commentId,@RequestBody CommentReactionParam p,HttpServletRequest request) {
        return Result.success(comments.react(id,commentId,(Long)request.getAttribute(AuthInterceptor.ATTR_USER_ID),p.reaction()));
    }
    @PostMapping("/{id}/comments/{commentId}/delete") @AppLoginRequired
    public Result<CommentService.DeleteResult> deleteComment(@PathVariable Long id,@PathVariable Long commentId,HttpServletRequest request) {
        return Result.success(comments.delete(id,commentId,(Long)request.getAttribute(AuthInterceptor.ATTR_USER_ID)));
    }
    @PostMapping("/{id}/poll/vote") @AppLoginRequired
    public Result<PostDetailVO.PollView> vote(@PathVariable Long id,@RequestBody PublishRequest.Vote p,HttpServletRequest request){return Result.success(publishing.vote((Long)request.getAttribute(AuthInterceptor.ATTR_USER_ID),id,p.optionId()));}
}
