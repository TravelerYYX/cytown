package com.yyx.ciyuantown.business.appuser.controller;

import java.math.BigDecimal;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yyx.ciyuantown.business.appuser.mapper.AppUserMapper;
import com.yyx.ciyuantown.business.appuser.mapper.UserStatisticsMapper;
import com.yyx.ciyuantown.business.appuser.service.UserLocationService;
import com.yyx.ciyuantown.business.appuser.vo.UserStatisticsVO;
import com.yyx.ciyuantown.business.interaction.service.InteractionService;
import com.yyx.ciyuantown.business.level.LevelService;
import com.yyx.ciyuantown.business.post.dto.PostQueryParam;
import com.yyx.ciyuantown.business.post.service.PostService;
import com.yyx.ciyuantown.business.post.vo.PostListVO;
import com.yyx.ciyuantown.common.api.Result;
import com.yyx.ciyuantown.common.exception.BizException;
import com.yyx.ciyuantown.common.interceptor.AuthInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/app/user") @RequiredArgsConstructor
public class AppProfileController {
    private final AppUserMapper users;
    private final UserStatisticsMapper statistics;
    private final LevelService levels;
    private final UserLocationService locations;
    private final PostService posts;
    private final InteractionService interactions;
    private final com.yyx.ciyuantown.business.report.UserBlockService blocks;

    // Explicit public projection: never serialize the authenticated account DTO here.
    public record Profile(String userId,String publicUid,String nickname,String avatar,String avatarFrame,
                          Long avatarFrameExpiresAt,int gender,String signature,String cardBackground,
                          int level,String title,BigDecimal coinBalance,String ipProvince,UserStatisticsVO statistics,
                          boolean following,boolean followedBy,boolean blockedByMe) {}
    private void requireUser(Long id) {
        var user=users.selectById(id);
        if(user==null||user.hasBeenDeleted()||user.getStatus()!=0)throw new BizException("用户不存在或已注销");
    }
    @GetMapping("/{userId}/profile")
    public Result<Profile> profile(@PathVariable Long userId,HttpServletRequest request) {
        requireUser(userId);
        var u=users.selectById(userId);
        var level=levels.progress(userId);
        Long viewer=(Long)request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        var relation=viewer==null?null:interactions.followState(viewer,userId);
        return Result.success(new Profile(u.getId().toString(),u.getPublicUid()==null?"":u.getPublicUid().toString(),u.getNickname(),u.getAvatar(),
            u.getAvatarFrame(),u.getAvatarFrameExpiresAt(),u.getGender()==null?0:u.getGender(),u.getSignature(),u.getCardBackground(),
            level.level(),level.title(),u.getCoinBalance(),locations.read(userId).province(),statistics.count(userId),
            relation!=null&&relation.active(),relation!=null&&Boolean.TRUE.equals(relation.followedBy()),blocks.blocked(viewer,userId)));
    }
    @GetMapping("/{userId}/posts")
    public Result<Page<PostListVO>> posts(@PathVariable Long userId,@ModelAttribute PostQueryParam query,HttpServletRequest request) {
        requireUser(userId);
        var page=posts.listPublicByAuthor(query,userId);
        return Result.success(page.setRecords(interactions.withState(page.getRecords(),(Long)request.getAttribute(AuthInterceptor.ATTR_USER_ID))));
    }
}
