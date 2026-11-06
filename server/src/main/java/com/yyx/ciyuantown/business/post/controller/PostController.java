package com.yyx.ciyuantown.business.post.controller;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yyx.ciyuantown.business.post.dto.*;
import com.yyx.ciyuantown.business.post.service.PostService;
import com.yyx.ciyuantown.business.post.vo.*;
import com.yyx.ciyuantown.common.api.Result;
import com.yyx.ciyuantown.business.publish.service.PublishService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/post")
@RequiredArgsConstructor
public class PostController {
    private final PostService posts;
    private final com.yyx.ciyuantown.business.post.service.CommentService comments;
    @GetMapping("/{id}/comments")
    public Result<Page<CommentVO>> comments(@PathVariable Long id,@ModelAttribute CommentQueryParam q){return Result.success(comments.listPreview(id,q));}
    @GetMapping("/{id}/comments/{rootId}/replies")
    public Result<Page<CommentVO>> replies(@PathVariable Long id,@PathVariable Long rootId,@ModelAttribute CommentQueryParam q){return Result.success(comments.repliesPreview(id,rootId,q));}
    private final PublishService publishing;
    private final com.yyx.ciyuantown.business.post.mapper.PostMapper mapper;
    public record Recommendation(Long id,Boolean recommended){}
    @PostMapping("/recommendation")
    @org.springframework.cache.annotation.CacheEvict(cacheNames={"post-list","post-detail"},allEntries=true)
    public Result<Void> recommendation(@RequestBody Recommendation p){
        if(p.id()==null||p.recommended()==null||mapper.selectById(p.id())==null)throw new com.yyx.ciyuantown.common.exception.BizException("帖子或推荐设置无效");
        var row=new com.yyx.ciyuantown.business.post.entity.Post();row.setId(p.id());row.setCheckinRecommended(p.recommended());mapper.updateById(row);return Result.success();
    }
    @PostMapping("/list")
    public Result<Page<PostListVO>> list(@RequestBody PostQueryParam q) { return Result.success(posts.list(q,false)); }
    @GetMapping("/{id}")
    public Result<PostDetailVO> detail(@PathVariable Long id) { var detail=posts.detail(id,false);detail.setPoll(publishing.pollView(id,null));return Result.success(detail); }
    @PostMapping("/updateStatus")
    public Result<Void> updateStatus(@Valid @RequestBody PostStatusParam p) { posts.updateStatus(p.id(),p.status()); return Result.success(); }
    @PostMapping("/delete")
    public Result<Void> delete(@Valid @RequestBody PostIdParam p) { posts.delete(p.id()); return Result.success(); }
    @PostMapping("/updateHomeNews")
    public Result<Void> updateHomeNews(@Valid @RequestBody PostHomeNewsParam p) { posts.updateHomeNews(p.id(),p.homeNewsEnabled()); return Result.success(); }
}
